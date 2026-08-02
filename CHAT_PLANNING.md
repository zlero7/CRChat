# CRChat — 클라이언트 모드 기반 채팅 시스템 기획

> **범위**: CRRPGCore와 **별도 저장소·별도 모드**로 개발한다.
> RPG 시스템에 의존하지 않고 단독으로 동작해야 한다.
>
> **목표**: 바닐라 채팅을 완전히 대체하는 채널·탭 기반 채팅.

---

## 1. 왜 클라이언트 모드가 필요한가

바닐라 채팅으로는 넘을 수 없는 벽이 명확하다.

| 바닐라 한계 | 모드로 해결 |
|---|---|
| 히스토리 100줄 | 무제한 (설정값, 기본 5,000줄) |
| 채널 구분 불가 — 한 줄에 다 섞임 | **탭 분리 + 필터** |
| 폭·높이·투명도 고정 | 자유 조절, 프리셋 저장 |
| 클릭 이벤트가 명령어 실행 수준 | 닉네임 클릭 → 컨텍스트 메뉴 |
| 호버 툴팁이 텍스트뿐 | **아이템 링크 실물 툴팁** |
| 검색 없음 | 로컬 검색 + 하이라이트 |
| 멘션·알림 없음 | 사운드·뱃지·화면 알림 |
| 귓속말이 흐름에 묻힘 | 전용 탭 + 대화 상대별 스레드 |

즉 **채널·탭·필터·검색**이 핵심 가치고, 이건 서버만으로는 불가능하다.

---

## 2. 아키텍처

```
CRChat/                      (별도 저장소, Gradle 멀티모듈)
├── api/         외부 플러그인용 공개 API — 단독 배포
├── protocol/    채팅 패킷 정의 — 서버·클라 공유
├── plugin/      Paper 플러그인 — 채널·권한·포맷·저장·검열
└── client/      Fabric 모드 — ChatHud 대체, 탭·필터·검색
```

통신 채널: `crchat:msg`

### 2.1 핵심 결정 — 바닐라 채팅 패킷을 쓰지 않는다

일반 채팅 패킷은 **문자열 한 덩어리**라 채널·발신자·아이템 링크를 구분할 수 없다.
따라서 모드 유저에게는 **구조화된 메시지 객체**를 커스텀 채널로 보내고,
바닐라 채팅 패킷은 **취소(cancel)** 한다.

```
모드 유저   : AsyncChatEvent 취소 → crchat:msg 로 구조화 전송
바닐라 유저 : 기존 방식대로 평문 렌더 (폴백, 11장)
```

이 분기를 처음부터 넣지 않으면 나중에 전체를 뜯어야 한다.

### 2.2 멀티서버 구조 — 처음부터 전제한다

로비·필드 등으로 서버를 나눌 예정이므로, **채팅은 처음부터 네트워크 단위**로
설계한다. 나중에 붙이면 라우팅·플레이어 조회·귓속말 대상 탐색을 전부 뜯어야 한다.

```
        ┌──────────── Velocity 프록시 ────────────┐
        │  (플레이어 연결 유지, 서버 이동 담당)      │
        └──┬──────────────┬──────────────┬────────┘
           │              │              │
        [로비]         [필드1]        [필드2]     ← Paper + CRChat
           └──────────────┴──────────────┘
                          │
                   Redis Pub/Sub          ← 메시지 전파
                   Redis Hash             ← 온라인 플레이어 레지스트리
                          │
                       MySQL              ← 채팅 로그 (공용)
```

#### 전송 수단: Redis Pub/Sub

| 후보 | 판단 |
|---|---|
| Velocity 플러그인 메시징 | ❌ 대상 서버에 플레이어가 있어야 전달 가능. 빈 서버로 못 보냄 |
| **Redis Pub/Sub** | ✅ 단순, 저지연, 서버 수 무관. 마크 네트워크 표준 관행 |
| RabbitMQ / NATS | 과함. 채팅 정도에 브로커 운영 부담이 크다 |

```
채널  crchat:broadcast   전체·길드 등 다중 수신
      crchat:direct      귓속말 (대상 서버만 처리)
      crchat:presence    접속·퇴장·서버 이동
```

#### 온라인 플레이어 레지스트리

귓속말 대상을 찾으려면 "그 유저가 어느 서버에 있는가"를 알아야 한다.

```
HSET crchat:players <uuid> {"name":"제로","server":"field1","since":...}
EXPIRE 60초 + 20초마다 하트비트 갱신
```

TTL을 두는 이유는 서버가 크래시로 죽으면 퇴장 이벤트가 안 오기 때문이다.
하트비트가 끊기면 자동으로 사라진다.

#### 중복 전달 방지

발신 서버는 로컬 플레이어에게 **먼저 전달하고**, 브로드캐스트를 받을 때
`originServer`가 자기 자신이면 **무시**한다. 안 하면 발신자에게 두 번 보인다.

```kotlin
if (packet.originServer == myServerId) return
```

#### 메시지 ID

여러 서버가 동시에 발급하므로 자동 증가는 쓸 수 없다.
**스노우플레이크 + 서버 노드 ID**로 만든다. 로그 조회 시 여러 서버의 메시지를
시간순으로 정렬해야 하므로 전역 유일성과 시간 정렬성이 모두 필요하다.

**노드 ID는 `server-id`(문자열, C.2)와 별개의 정수 필드다.**
문자열을 해시해서 노드 ID를 만들면 해시 충돌 시 두 서버가 같은 ID를 조용히
공유하게 되고, 그 순간부터 스노우플레이크의 전역 유일성이 깨진다.

```yaml
# config.yml
server-id: "lobby"    # 사람이 읽는 식별자. 중복 전달 방지(2.2)에 사용
node-id: 1            # 스노우플레이크 노드 ID. 0~1023, 서버마다 겹치면 안 됨
```

기동 시 Redis(`SADD crchat:node-ids <node-id>`, `NX`)로 중복 등록을 검사하고,
이미 있으면 **서버를 띄우지 않는다.** 조용히 겹친 채로 뜨는 것보다 기동 실패가
훨씬 싸게 먹힌다.

#### Redis 장애 시

| 상황 | 동작 |
|---|---|
| 연결 끊김 | **로컬 전달은 계속** (같은 서버 안에서는 대화 가능) |
| | 관리자에게 경고, 콘솔 로그 |
| | 클라에 "일부 채널 연결 불안정" 표시 |
| 복구 | 자동 재연결. **밀린 메시지는 재전송하지 않는다** (세션 한정 정책과 일치) |

#### 서버 이동 시 클라 상태

Velocity에서 서버를 옮겨도 **프록시 세션은 유지**되므로 클라 모드의 연결은
끊기지 않는다. 따라서

- 탭 구성과 대화 내역은 **그대로 유지**한다
- 다만 새 백엔드 서버가 다시 핸드셰이크하므로 `availableChannels`를 재전송한다
- "접속 종료 시 내역 소멸"의 기준은 **프록시 연결 종료**이지 서버 이동이 아니다

이 구분을 명확히 하지 않으면 필드 이동할 때마다 채팅이 날아가는 버그가 된다.

### 2.3 채팅 서명(Chat Signing) 정책

**결론: `online-mode=true` + `enforce-secure-profile=false`**

```properties
# server.properties
online-mode=true                 # 정품 인증 유지
enforce-secure-profile=false     # 채팅 서명 요구 해제
```

두 설정은 별개다. 서명을 꺼도 정품 인증에는 아무 영향이 없다.

| 설정 | 역할 |
|---|---|
| `online-mode` | 모장 인증 서버로 계정 검증 |
| `enforce-secure-profile` | 접속 시 **프로필 공개키** 요구, 없으면 킥 |

**끄는 이유**

1. 실제 채팅은 `crchat:msg` 커스텀 채널의 바이트 배열이다.
   마인크래프트 서명 체계 바깥이라 **서명해도 보호되는 것이 없다**
2. 커스텀 런처가 인증 시 프로필 공개키를 받아오지 않으면
   `Chat disabled due to missing profile public key`로 킥된다.
   런처 구현에 따라 갈리는 사고이므로 원천 차단한다
3. `AsyncChatEvent`를 전량 취소하므로 바닐라 채팅 경로 자체가 죽어 있다

**따라오는 결과 — 미리 인지할 것**

- 모장 채팅 신고 기능이 우리 메시지에는 적용되지 않는다
  → **모더레이션은 전적으로 서버 운영 책임**이다.
  자체 신고 기능도 두지 않기로 했으므로(16장), 유일한 수단은
  **채팅 로그 조회(8장)** 다. 로그가 곧 모더레이션 도구다
- 클라 상단의 "안전하지 않은 채팅" 표시는 우리 UI가 아니라
  바닐라 채팅창에 대한 것이므로 유저 눈에 띄지 않는다

### 2.4 신뢰 경계

클라는 **표시와 입력만** 담당한다.

- 채널 권한, 쿨타임, 검열, 거리 판정은 전부 서버
- 클라가 보낸 채널명·수신자도 재검증
- 클라 필터는 **표시 필터**일 뿐, 서버는 애초에 권한 없는 채널을 보내지 않는다

---

## 3. 데이터 모델

```kotlin
@Serializable
data class ChatMessage(
    val id: Long,                     // 서버 발급 스노우플레이크 (노드 ID 포함)
    val channel: String,              // "global" | "guild" | "party" | "whisper:<uuid>" | "system"
    val senderId: Int?,               // 발신자 레지스트리 참조. null 이면 시스템 메시지
    val segments: List<Segment>,      // 리치 텍스트
    val timestamp: Long
)

/**
 * 발신자 정의. 메시지마다 싣지 않고 처음 등장할 때 1회만 전송한다.
 * 같은 사람이 계속 말하는데 UUID·닉·칭호를 매번 반복할 이유가 없다.
 * 자세한 근거는 12.2 참조.
 */
@Serializable
data class SenderDef(
    val id: Int,                      // 네트워크 세션 한정 식별자 — 접속 시 전역 1회 발급 (12.2 참조)
    val uuid: String,
    val name: String,
    val displayName: String,          // ChatDecorator 가 만든 최종 표시명 (색·칭호 포함)
    val prefix: String? = null
)

@Serializable
sealed class Segment {
    @Serializable @SerialName("text")
    data class Text(val value: String, val color: String? = null,
                    val bold: Boolean = false) : Segment()

    @Serializable @SerialName("item")
    data class Item(val linkId: String, val display: String,
                    val rarity: String) : Segment()

    @Serializable @SerialName("link")
    data class Link(val url: String, val display: String) : Segment()

    @Serializable @SerialName("mention")
    data class Mention(val uuid: String, val name: String) : Segment()
}
```

세그먼트 구조로 만든 이유는 **아이템 링크와 멘션을 나중에 끼워 넣을 수 없기**
때문이다. 평문으로 시작하면 반드시 파싱 지옥이 온다.

### 3.1 설계 규칙

**귓속말 상대는 별도 필드로 두지 않는다.**
`channel = "whisper:<uuid>"` 에서 파생시킨다. 같은 정보를 두 곳에 두면
어긋났을 때 어느 쪽을 믿을지 모호해진다.

**`ChatDecorator` 결과는 `displayName` 에 이미 반영되어 있다.**
레벨·경지·길드 같은 RPG 개념을 `SenderDef` 필드로 만들지 않는다.
CRChat 은 RPG 를 모르며, 꾸미기는 전적으로 Decorator 책임이다 (15장).

**`Segment.Player` 는 두지 않는다.**
`Mention` 과 필드가 동일해 구분 실익이 없다. 닉네임 클릭·호버 동작은
`Mention` 하나로 처리한다.


---


### 3.2 패킷 정의

채널 `crchat:msg` 하나로 양방향 통신한다.
`protocol/` 모듈에 정의하고 서버·클라가 함께 의존한다.

#### 서버 → 클라이언트

| 패킷 | 내용 | 시점 |
|---|---|---|
| `HELLO` | 프로토콜 버전, 서버 ID | 접속 직후 |
| `SESSION_INIT` | `availableChannels`, 채널 정의(색·표시명), 뮤트 상태 | 핸드셰이크 성공 후 |
| `SENDER_DEF` | 발신자 정의 1건 이상 | 처음 등장하는 발신자 |
| `MESSAGE` | `ChatMessage` 1건 이상 (틱 배칭) | 상시 |
| `TIMER` | 상태성 알림 — key, 초, 라벨 | `CRChatApi.timer()` |
| `MUTE_UPDATE` | 뮤트 부여·해제 | 즉시 |
| `CHANNEL_UPDATE` | 채널 가용성 변경 (길드 가입 등) | 상태 변화 시 |
| `ERROR` | 사유 코드 + `messages.yml` 키 | 전송 거부 시 |

#### 클라이언트 → 서버

| 패킷 | 내용 | 검증 |
|---|---|---|
| `HELLO` | 프로토콜 버전, 모드 버전 | 버전 불일치 시 킥 |
| `SEND` | 채널, 세그먼트 목록 | **전부 재검증** — 권한·쿨타임·뮤트·정규화 |
| `LINK_ITEM` | 인벤토리 슬롯 번호 | 서버가 직접 아이템을 읽는다 (6장) |
| `REQUEST_SENDER` | 모르는 `senderId` | |
| `REQUEST_ITEM` | 캐시에 없는 `linkId` | TTL·횟수 제한 |

```kotlin
@Serializable
sealed class S2C {
    @Serializable @SerialName("hello")
    data class Hello(val protocolVersion: Int, val serverId: String) : S2C()

    @Serializable @SerialName("session")
    data class SessionInit(
        val availableChannels: List<ChannelDef>,
        val mutes: List<MuteInfo>
    ) : S2C()

    @Serializable @SerialName("senders")
    data class SenderDefs(val senders: List<SenderDef>) : S2C()

    @Serializable @SerialName("msg")
    data class Messages(val messages: List<ChatMessage>) : S2C()
    ...
}

@Serializable
sealed class C2S {
    @Serializable @SerialName("hello")
    data class Hello(val protocolVersion: Int, val modVersion: String) : C2S()

    @Serializable @SerialName("send")
    data class Send(val channel: String, val segments: List<Segment>) : C2S()
    ...
}
```

#### 핸드셰이크 절차

```
접속
 ├─ 서버: HELLO 전송, 10초 타이머 시작
 ├─ 클라: HELLO 응답
 │    ├─ 버전 일치   → 서버: SESSION_INIT 전송, 이후 정상 동작
 │    └─ 버전 불일치 → 킥 (messages.yml: version-mismatch)
 └─ 10초 무응답      → 모드 없음
                       no-mod-policy 에 따라 킥 또는 폴백 (11장)
```

**타임아웃은 3초가 아니라 10초로 잡는다.** 모드 로딩이 느린 클라(느린 디스크,
다른 모드와 초기화 경쟁)에서 3초는 실제로 오탐이 난다 — 모드가 있는데
"모드 없음"으로 오판해 킥해버리면 유저 입장에서는 랜덤 접속 실패다.
10초는 실사용 체감상 안 보이면서 오탐을 없앤다.

**서버 이동 시**: 프록시 세션은 유지되므로 클라는 그대로 두고,
새 백엔드 서버가 `HELLO` → `SESSION_INIT` 만 다시 수행한다.
클라는 탭·내역을 유지한 채 채널 정의와 뮤트 상태만 갱신한다 (2.2).

#### 페이로드 규약

Bukkit 플러그인 메시지는 **길이 접두 없는 순수 바이트**로 전달된다.
Fabric `CustomPayload` 디코더에서 `readByteArray()` 를 쓰면 VarInt 길이를
먼저 읽으려 해서 깨진다. **남은 바이트 전체를 읽어야 한다.**

```kotlin
// 클라 디코더
{ buf -> UiPayload(ByteArray(buf.readableBytes()).also { buf.readBytes(it) }) }
```

이 한 줄 때문에 Phase 0 에서 하루를 날리기 쉽다.

---

## 4. 채널 설계

### 4.1 전체 구성

탭은 **전체 / 공지 / 일반 / 길드 / 파티 / 시스템 + 귓속말 N개** 로 구성된다.
(공지·일반은 실제 참고 UI를 확인한 뒤 추가됐다 — 17장 참조)

| 탭 | 수신 | 발신 | 삭제 |
|---|---|---|---|
| **전체** | **모든 메시지** + 바닐라·외부 플러그인 메시지 전부 | `global` 채널 | 불가 |
| **공지** | `/공지` 로 발행된 것만 | 발신 불가 (수신 전용, 관리자 명령어만) | 불가 |
| **일반** | `general` 만 — **같은 서버·근접 반경 내** | `general` (거리 판정은 서버, 2.4) | 불가 |
| **길드** | `guild` 만 | `guild` | 불가 |
| **파티** | `party` 만 | `party` | 불가 |
| **시스템** | `CRChatApi`로 발행된 것만 | 발신 불가 (수신 전용) | 불가 |
| **귓속말 N개** | 해당 상대와의 대화만 | 해당 상대에게 | **가능** |

**공지 vs 시스템 — 왜 분리하는가.** 둘 다 수신 전용이지만 발행 주체가 다르다.
공지는 **운영자가 직접** `/공지` 로 보내는 사람의 말이고, 시스템은 **플러그인이
자동으로** 쏟아내는 알림(쿨타임 안내 제외 등)이다. 한 탭에 섞으면 공지가
시스템 알림 사이에 묻혀서 놓치기 쉽다.

**일반 — 근접 채팅.** "전체"가 서버 전역이라면 "일반"은 반경 내 유저에게만
전달되는 로컬 채팅이다. 서버가 발신자 좌표 기준으로 반경 내 온라인 플레이어를
걸러 수신자를 정한다 — **거리 판정은 클라가 아니라 서버가 한다** (2.4).
같은 서버 안에서만 의미가 있으므로, 파티(4.6)와 마찬가지로 서버 이동 시
"근처"의 정의가 리셋된다.

### 4.2 "전체" 탭의 이중성 — 반드시 짚고 갈 것

전체 탭은 **수신은 전부, 발신은 `global` 채널**이다. 이 둘을 같은 이름으로
부르면 구현할 때 반드시 헷갈린다. 코드에서는 개념을 분리한다.

```kotlin
/** 실제 메시지가 흐르는 논리 채널 */
enum class Channel { GLOBAL, NOTICE, GENERAL, GUILD, PARTY, SYSTEM, WHISPER }

/** 클라가 보여주는 화면 단위. 채널과 1:1이 아니다. */
sealed class Tab {
    /** 모든 채널 수신, 발신은 GLOBAL */
    data object All : Tab()
    data class Single(val channel: Channel) : Tab()      // 길드·파티·시스템
    data class Whisper(val targetUuid: UUID) : Tab()
}
```

**중요**: 전체 탭에서 채널 구분은 **태그가 아니라 색으로 한다.**
`<길드>` 같은 접두 태그는 좁은 채팅창에서 폭을 잡아먹고 시선을 분산시킨다.

| 채널 | 색 | 헥스 |
|---|---|---|
| 전체 | 하양 | `#FFFFFF` |
| 공지 | 빨강 | `#FF6B6B` |
| 일반 | 연두 | `#B8E986` |
| 길드 | 노랑 | `#FFE066` |
| 파티 | 하늘색 | `#7FD8E8` |
| 귓속말 | 주황 | `#FFA657` |
| 시스템 | **지정 없음** | 발행 플러그인이 직접 색을 넣는다 |

```
[전체 탭]                          [길드 탭]
 제로 : 안녕하세요            (하양)  홍길동 : 보스 가자
 홍길동 : 보스 가자           (노랑)  제로 : ㄱㄱ
 김철수 : 물약 좀            (하늘)
 이영희 : 천군투구 팔아요?     (주황)
 회복 물약 재사용 대기 12초   (플러그인 지정)
```

**적용 범위**: 채널 색은 **메시지 본문**에 적용한다.
닉네임은 `ChatDecorator`가 지정한 등급·칭호 색을 우선한다. 둘 다 색을 먹이면
줄 전체가 얼룩덜룩해져서 오히려 읽기 어렵다.

**시스템 메시지 주의**: 색을 지정하지 않으므로, 발행 플러그인이 아무 색도
안 넣으면 기본 하양으로 렌더되어 **전체 채팅과 구분되지 않는다.**
API 문서에 "시스템 메시지는 반드시 색을 지정할 것"을 명시한다.

**접근성**: 색만으로 구분하면 색각 이상 유저가 채널을 구분할 수 없다.
설정에 `채널 태그 표시` 옵션을 두어 원할 때 태그를 켤 수 있게 한다.
기본값은 꺼짐.

### 4.3 채널 정의

```yaml
channels:
  global:
    display: "전체"
    color: "#FFFFFF"
    tag: "<전체>"          # 접근성 옵션 켤 때만 사용
    cooldown: 2.0
  notice:
    display: "공지"
    color: "#FF6B6B"
    tag: "<공지>"
    receive-only: true    # /공지 명령어로만 발행된다. 시스템과 분리하는 이유는 4.1 참조
  general:
    display: "일반"
    color: "#B8E986"
    tag: "<일반>"
    cooldown: 1.0
    radius: 100.0          # 이 반경(블록) 안의 온라인 플레이어만 수신 (거리 판정, 2.4)
  guild:
    display: "길드"
    color: "#FFE066"
    tag: "<길드>"
    cooldown: 0.5
    requires: guild        # provider 미등록 시 탭 숨김
  party:
    display: "파티"
    color: "#7FD8E8"
    tag: "<파티>"
    cooldown: 0.5
    requires: party
  whisper:
    display: "귓속말"
    color: "#FFA657"
    tag: "<귓>"
    cooldown: 0.3
  system:
    display: "시스템"
    color: null            # 지정하지 않음 — 발행 플러그인이 결정
    tag: "▶"
    receive-only: true
```

### 4.4 시스템 채널 — 외부 플러그인 개방

시스템 채널은 **CRChat이 직접 만드는 메시지가 아니다.** 물약 재사용 대기,
퀘스트 알림, 재련 결과 등은 전부 다른 플러그인이 발행한다.
따라서 **공개 API**를 제공하고 CRChat은 전달·렌더만 담당한다.

```kotlin
// crchat-api 모듈 — 다른 플러그인이 컴파일 의존
interface CRChatApi {

    /** 특정 플레이어에게 시스템 메시지 */
    fun system(player: Player, message: Component, category: String = "general")
    // message 는 색을 포함한 완성된 Component 여야 한다. CRChat 은 색을 덧입히지 않는다.

    /**
     * 상태성 알림 (쿨타임·잔여시간). 클라가 카운트다운하므로 1회만 보낸다.
     * 채팅 로그에 남지 않으며 HUD 로 렌더된다. 자세한 이유는 12.2 참조.
     */
    fun timer(player: Player, key: String, seconds: Double, label: Component)

    /** 여러 명에게 */
    fun system(players: Collection<Player>, message: Component, category: String = "general")

    /** 전체 공지 */
    fun notice(message: Component)

    companion object {
        @JvmStatic
        fun get(): CRChatApi =
            Bukkit.getServicesManager().load(CRChatApi::class.java)
                ?: error("CRChat 이 설치되어 있지 않습니다")
    }
}
```

사용 예시:

```kotlin
CRChatApi.get().system(player, Component.text("회복 물약 재사용 대기 12초"), "cooldown")
```

**category를 두는 이유**: 유저가 시스템 메시지 종류별로 켜고 끌 수 있게 하기
위해서다. `cooldown`, `quest`, `refine`, `drop` 등으로 나누면
"드랍 알림만 끄기" 같은 설정이 가능해진다. 나중에 넣으려면 못 넣는다.

- CRChat 미설치 시를 대비해 API는 **별도 모듈(`api/`)로 배포**한다
- CRChat이 없으면 호출부가 폴백해서 일반 채팅으로 보내도록 안내 문서 제공

### 4.5 귓속말 — 유저별 동적 채널

상대 한 명당 탭 하나가 자동 생성된다.

```
채널 ID: whisper:<상대 UUID>
탭 이름: 상대 닉네임
```

| 시점 | 동작 |
|---|---|
| 처음 귓속말을 보내거나 받음 | 탭 자동 생성 |
| 대화 지속 | 해당 탭 + 전체 탭에 동시 표시 |
| 탭에 마우스를 올림 | **탭 우측에 `×` 노출** |
| `×` 클릭 | 탭 삭제 + 해당 대화 내역 삭제 |
| 삭제한 상대가 다시 말을 검 | 탭 재생성 (이전 내용 없음) |
| **접속 종료** | **모든 내역·탭 소멸** |

**설계 판단 — 세션 한정**

채팅 내역은 **접속 중에만 유지**하고 종료 시 전부 사라진다.
이 결정 덕분에 구조가 크게 단순해진다.

- 클라 로컬 파일 저장 없음 → 파일 I/O·용량 관리 불필요
- 재접속 시 탭 복원 로직 불필요 → 서버가 최근 대화 상대를 내려줄 필요 없음
- 개인정보 보관 이슈가 클라 쪽에서는 사라짐
  (단, **서버 운영용 로그는 별개로 DB에 남는다.** 8장 참조 — 이 둘을 혼동하면 안 된다)

```kotlin
data class WhisperTab(
    val targetUuid: UUID,
    var targetName: String,     // 닉 변경 시 갱신
    var unread: Int = 0
)
```

#### 오프라인 상대에게 귓속말

레지스트리(2.2)에 없는 UUID면 오프라인으로 판정한다.

| 상황 | 동작 |
|---|---|
| 대화 중 상대가 접속 종료 | 탭 유지. 입력 시 `whisper-offline` 안내 후 **전송 거부** |
| 오프라인 상대에게 `/ㄱ` | 탭 생성하지 않고 안내만 |
| 자기 자신에게 | `whisper-self` 안내 |

**오프라인 메시지 보관은 하지 않는다.** 세션 한정 정책(내역 미저장)과
정면으로 충돌하기 때문이다. 쪽지 기능이 필요하면 별도 시스템으로 만든다.

> 탭 삭제는 **차단이 아니다.** 삭제해도 상대는 계속 귓속말을 보낼 수 있고
> 그때 탭이 다시 생긴다. 차단은 별도 기능(5.4)이며, UI에서 두 기능을
> 명확히 구분해 표기해야 문의가 줄어든다.

### 4.6 그룹 데이터 출처 — GroupProvider

`guild` / `party` 채널은 "이 플레이어가 어느 그룹 소속인가"를 알아야 한다.
CRChat은 그룹 시스템을 **직접 구현하지 않고 조회만** 한다.

```kotlin
enum class GroupType { PARTY, GUILD }

interface GroupProvider {
    val type: GroupType

    /** 소속 그룹 ID. 미소속이면 null */
    fun groupId(player: Player): String?

    /** 그룹원 전체 (오프라인 포함 가능, 발신 시 온라인만 필터) */
    fun members(groupId: String): Collection<UUID>

    /** 탭·표시용 이름 */
    fun displayName(groupId: String): String
}
```

`ServicesManager`에 등록하는 방식으로 외부에서 갈아끼운다.

```kotlin
Bukkit.getServicesManager().register(
    GroupProvider::class.java, MyGuildProvider(), plugin, ServicePriority.Normal
)
```

#### 파티 — 바닐라 팀 기반 (기본 제공)

CRChat이 `VanillaTeamPartyProvider`를 기본 구현으로 내장한다.

```kotlin
class VanillaTeamPartyProvider : GroupProvider {
    override val type = GroupType.PARTY

    override fun groupId(player: Player): String? =
        Bukkit.getScoreboardManager().mainScoreboard
            .getEntryTeam(player.name)
            ?.takeIf { it.name.startsWith(config.teamPrefix) }
            ?.name
    ...
}
```

**`teamPrefix` 를 두는 이유**: 바닐라 팀은 파티 외에도 닉네임 색, 충돌 판정,
`nametagVisibility` 등에 쓰인다. 접두어(`party_`)로 거른 팀만 파티로 인정하지
않으면, 관리자가 색 구분용으로 만든 팀이 통째로 파티 채널이 되어버린다.

```yaml
party:
  provider: vanilla-team
  team-prefix: "party_"      # 이 접두어 팀만 파티로 인정
```

**⚠ 멀티서버에서의 치명적 한계**

**스코어보드 팀은 서버별로 독립이다.** 로비와 필드1의 팀 정보는 서로 모른다.
따라서 바닐라 팀 기반 파티는 **같은 서버 안에서만 동작**한다.

| 상황 | 결과 |
|---|---|
| 파티원이 모두 같은 필드 서버 | 정상 동작 |
| 파티원이 로비/필드에 흩어짐 | **파티 채팅이 전달되지 않음** |
| 서버 이동 | 팀 소속이 사라져 파티 탭 비활성 |

**선택지**

| 방안 | 내용 | 비용 |
|---|---|---|
| A. 서버 로컬 파티로 인정 | 파티는 같은 필드에서만. 이동 시 해제 | 없음 |
| B. Redis에 파티 상태 저장 | `GroupProvider`를 네트워크 대응으로 구현 | 중 |
| C. 파티 시스템 직접 개발 | 초대·수락·인원제한 포함 | 큼 |

**권장: A로 시작하되 `GroupProvider` 인터페이스는 그대로 둔다.**
길드를 만들 때 어차피 Redis 기반 그룹 관리가 필요하므로, 그때 파티도
B 또는 C로 옮기면 된다. 인터페이스만 지켜두면 교체 비용이 거의 없다.

단, **유저에게는 명확히 안내해야 한다.** "파티 채팅은 같은 지역에서만
전달됩니다" 같은 문구가 없으면 버그로 문의가 들어온다.

**바닐라 팀의 그 외 한계**

| 한계 | 영향 |
|---|---|
| 초대·수락 개념 없음 | 파티 구성은 명령어/다른 플러그인이 담당 |
| 인원 제한 없음 | 필요하면 파티 플러그인 쪽에서 제한 |
| 팀 변경 이벤트 없음 | **캐시하지 말고 매번 조회**할 것 (맵 조회라 저렴하다) |
| 한 명이 한 팀만 가능 | 파티와 다른 팀 용도가 충돌 가능 |

#### 길드 — 아직 미구현

길드 시스템은 추후 개발 예정이므로, 지금은 **provider 부재 상태**로 둔다.

```kotlin
object NullGroupProvider : GroupProvider {
    override fun groupId(player: Player): String? = null
    ...
}
```

| provider 상태 | 길드 탭 |
|---|---|
| 미등록 | **탭 자체를 숨긴다** (자리도 차지하지 않음) |
| 등록됨 · 미소속 | 탭 표시, 회색 비활성 |
| 등록됨 · 소속 | 정상 동작 |

> 길드 탭만 "미등록 시 숨김"이고 파티 탭은 항상 보인다.
> 파티는 기본 provider가 있으므로 미소속이어도 회색으로 존재한다.
> **이 차이를 클라에 하드코딩하지 말고**, 서버가 핸드셰이크 시
> `availableChannels` 목록을 내려주면 클라는 그대로 그리기만 한다.

```json
// 핸드셰이크 응답 예시
{ "availableChannels": ["global", "party", "system"] }   // 길드 없음
```

---

### 4.7 바닐라·외부 메시지 라우팅

CRChat이 만들지 않은 메시지들이 존재한다. 이들의 목적지를 명확히 한다.

**규칙: 바닐라 경로로 들어온 것은 전부 전체 탭.**

| 메시지 | 목적지 | 비고 |
|---|---|---|
| 접속 / 퇴장 알림 | 전체 탭 | |
| 사망 메시지 | 전체 탭 | |
| 업적 달성 | 전체 탭 | |
| 명령어 피드백 (`/gamemode` 결과, 에러) | 전체 탭 | **필수** — 안 보이면 먹통으로 느낀다 |
| `/say`, `/tellraw` | 전체 탭 | |
| `player.sendMessage()` 쓰는 외부 플러그인 | 전체 탭 | 아래 참조 |
| `CRChatApi.system()` | **시스템 탭** | 유일하게 시스템 탭으로 가는 경로 |

#### 구현

클라가 `ChatHud`를 대체하므로, **바닐라 메시지는 `ChatHud.addMessage`로
들어온다.** 여기서 오는 것은 출처를 `VANILLA`로 태깅해 전체 탭에 넣는다.

```kotlin
enum class Source { VANILLA, CRCHAT }

// ChatHud.addMessage 가로채기
fun onVanillaMessage(text: Component) {
    store.add(ChatEntry(source = Source.VANILLA, tab = Tab.All, component = text))
}
```

**서식은 건드리지 않는다.** 사망 메시지·업적은 이미 색이 입혀져 있으므로
전체 채널 색(하양)을 덧칠하면 안 된다. 채널 색은 `CRCHAT` 출처에만 적용한다.

#### 부수 효과 — API 도입이 선택사항이 된다

`CRChatApi`를 쓰지 않는 플러그인도 `player.sendMessage()`만 하면 전체 탭에
정상 표시된다. 따라서

- 기존 플러그인을 고치지 않아도 서버가 돌아간다
- 시스템 탭으로 분리하고 싶은 것만 골라서 API로 전환하면 된다
- **API 도입을 강제할 필요가 없다**

다만 `/tellraw`나 액션바를 남발하는 플러그인은 전체 탭을 오염시킬 수 있다.
설정에 **출처별 필터**를 열어둔다.

```yaml
vanilla-messages:
  join-quit: true
  death: true
  advancement: true
  command-feedback: true     # 끄면 안 됨. 기본 true 고정 권장
```

---

### 4.8 채널 전환

**접두 기호는 사용하지 않는다.** `!`, `#`, `@` 같은 기호는 오타로 인한 사고
(귓속말이 전체에 나가는 등)의 주범이고, 탭 UI가 있으면 존재 이유가 없다.

| 방법 | 동작 |
|---|---|
| **탭 클릭** | 해당 탭으로 전환. 기본이자 권장 방식 |
| `Tab` 키 | 다음 탭 순환 |
| `/전체` `/길드` `/파티` | 해당 탭으로 전환 |
| `/ㄱ <닉> [내용]` | 귓속말 탭 생성·전환. 내용 있으면 즉시 발송 |
| 닉네임 우클릭 → 귓속말 | 탭 생성·전환 |

**현재 탭이 곧 발신 채널이다.** 시스템 탭에서는 입력창이 비활성화된다.

---

## 5. 클라이언트 UI

### 5.1 구성

```
┌─ 탭 ──────────────────────────────────────────────┐
│ [전체][길드][파티][시스템] │ [홍길동 ●2 ×][김철수]   │
├────────────────────────────────────────────────────┤
│ 14:03  제로 : 안녕하세요                     (하양) │
│ 14:03  홍길동 : 보스 가자                    (노랑) │
│ 14:04  이영희 : 천군투구 팔아요?              (주황) │
│ 14:04  회복 물약 재사용 대기 12초       (플러그인 색) │
│                                                    │
├────────────────────────────────────────────────────┤
│ 전체 ▸ 입력...                               🔍 ⚙  │
└────────────────────────────────────────────────────┘
```

- 고정 탭 4개와 귓속말 탭은 **구분선으로 분리**
- **`×`는 호버 시에만 노출** — 평소에 보이면 탭이 좁아지고 오클릭이 잦다
- 전체 탭의 채널 구분은 **본문 색**으로 한다 (4.2). 태그는 접근성 옵션
- 입력창 좌측에 현재 채널명 상시 표시 — 어디에 쓰는지 항상 보이게

### 5.2 탭 시스템

| 탭 | 삭제 | 비활성 조건 |
|---|---|---|
| 전체 | 불가 | 없음 |
| 공지 | 불가 | 없음 (입력만 비활성) |
| 일반 | 불가 | 없음 |
| 길드 | 불가 | 길드 미소속 시 회색 |
| 파티 | 불가 | 파티 미소속 시 회색 |
| 시스템 | 불가 | 없음 (입력만 비활성) |
| 귓속말 | **가능** | 없음 |

- 탭별 **읽지 않음 뱃지** (`●2`)
- 귓속말 탭이 많아지면 가로 스크롤 + `▾` 전체 목록 버튼
- 귓속말 탭 순서는 **최근 대화 순** 자동 정렬 (드래그 고정 옵션 제공)
- 비활성 탭도 자리는 유지한다. 길드 가입 시 탭이 갑자기 생기면 위치가 밀려
  오클릭이 난다

---

## 6. 아이템 링크

전체 채널·귓속말에서 거래 대화를 성립시키는 핵심 기능.

### 6.1 흐름

```
1. 유저가 인벤토리에서 Shift+클릭 또는 채팅 입력 중 단축키
2. 클라 → 서버: LINK_ITEM (슬롯 번호만 전송)
3. 서버가 해당 슬롯 아이템을 읽어 스냅샷 저장
   → linkId 발급 (TTL 30분, Redis에 저장 — 6.1.1 참조)
4. 채팅 메시지에 Segment.Item(linkId) 삽입
5. 수신자가 호버 → 클라가 캐시에 없으면 서버에 조회
```

#### 6.1.1 스냅샷 저장소 — 로컬 메모리만으로는 부족하다

전체 채널·귓속말은 Redis Pub/Sub으로 **다른 서버까지 퍼진다**(2.2). 그런데
스냅샷을 발급 서버의 로컬 메모리에만 두면, 필드1에서 링크한 아이템을 로비에
있는 유저가 호버할 때 로비 서버에는 그 스냅샷이 없어 `REQUEST_ITEM`이
실패한다.

```
❌ 로컬 메모리 캐시만                  → 발급 서버가 아니면 조회 불가
✅ Redis(`crchat:item:<linkId>`, TTL) → 어느 서버가 받아도 조회 가능
```

- 진실의 원천은 **Redis**(TTL 30분과 함께 저장). 로컬 메모리 캐시는 그 앞단
  L1으로만 둔다 — 같은 서버에서 반복 조회될 때 Redis 왕복을 생략하는 용도
- L1에 없으면 Redis 조회, Redis에도 없으면(TTL 만료) 클라에 "만료됨" 응답

### 6.2 보안

- **클라가 아이템 데이터를 만들어 보내지 않는다.** 슬롯 번호만 보내고
  실제 아이템은 서버가 읽는다. 안 그러면 존재하지 않는 아이템을 링크할 수 있다
- linkId는 추측 불가능한 랜덤 값
- 조회 횟수·TTL 제한으로 캐시 남용 방지

---

## 7. 스팸 · 검열 · 제재

| 기능 | 방식 |
|---|---|
| 도배 | 채널별 쿨타임 + 초당 메시지 수 제한 |
| 동일 문구 반복 | 최근 5개와 유사도(레벤슈타인) 비교, 임계값 초과 시 차단 |
| 금칙어 | 정규식 목록, 우회 표기(자모 분리·특수문자 삽입) 정규화 후 검사 |
| 광고 | URL·디스코드 초대 링크 패턴 탐지, 화이트리스트 예외 |
| 뮤트 | 채널별·기간제. 상세는 7.2 |

### 7.2 뮤트

**채널별 + 기간제**로 건다. "전체 채팅만 막고 길드는 허용" 같은 운영이 가능하다.

#### 데이터

```sql
CREATE TABLE chat_mute (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  uuid        CHAR(36)    NOT NULL,
  channel     VARCHAR(32) NULL,       -- NULL = 모든 채널
  until       TIMESTAMP   NULL,       -- NULL = 영구
  reason      VARCHAR(255),
  issued_by   VARCHAR(32) NOT NULL,
  created_at  TIMESTAMP   NOT NULL,
  revoked_at  TIMESTAMP   NULL,       -- 해제 시각. 행을 지우지 않는다
  INDEX idx_uuid_active (uuid, revoked_at)
);
```

**행을 삭제하지 않는다.** 해제는 `revoked_at`을 채우는 것으로 처리한다.
제재 이력이 남아야 재범 판단이 가능하다.

`channel = NULL`을 전체 뮤트로 쓰는 이유는, 채널이 늘어나도 기존 뮤트가
자동으로 새 채널에 적용되기 때문이다. 채널 목록을 나열해 저장하면
길드 채널을 추가하는 순간 기존 전체 뮤트가 뚫린다.

#### 기간

```
/뮤트 <닉> <기간> [채널] [사유]

기간 표기:  10m  1h  3d  perm
```

| 값 | 의미 |
|---|---|
| `10m` `1h` `3d` | 상대 시간. `until = now + 기간` |
| `perm` | `until = NULL` |

만료는 **발신 시점에 검사**한다. 별도 스케줄러로 일괄 해제하지 않는다.
서버가 꺼져 있는 동안 만료된 건도 자연스럽게 처리된다.
(정리용 배치는 하루 1회, `revoked_at` 없이 만료만 지난 행을 아카이브)

#### 멀티서버 동기화

뮤트는 **모든 서버에 즉시 반영**되어야 한다. 로비에서 뮤트했는데 필드에서
계속 떠들면 의미가 없다.

```
MySQL          진실의 원천
Redis Hash     crchat:mutes:<uuid> → 활성 뮤트 목록 (캐시)
Redis Pub/Sub  crchat:moderation   → 뮤트 부여·해제 시 즉시 전파
```

- 접속 시 DB에서 읽어 Redis + 로컬 캐시에 적재
- 뮤트 변경 시 `crchat:moderation` 발행 → 전 서버 캐시 갱신
- **Redis가 죽어도 로컬 캐시로 계속 동작**한다. 다만 변경 전파가 안 되므로
  콘솔 경고를 남긴다

#### 클라 표시

뮤트 상태를 클라에 전달해 **해당 탭의 입력창을 비활성**한다.

```
길드 ▸ 뮤트 상태입니다 · 남은 시간 42분 · 사유: 욕설
```

- 채널별 뮤트면 **그 탭만** 막힌다. 다른 탭은 정상
- 남은 시간은 클라가 카운트다운한다 (12.2의 상태 관리 원칙)
- **표시는 편의일 뿐, 실제 차단은 서버가 한다.** 모드를 뜯어 입력창을
  되살려도 서버에서 막힌다

### 7.3 관리자 명령어

| 명령어 | 권한 | 설명 |
|---|---|---|
| `/뮤트 <닉> <기간> [채널] [사유]` | `crchat.admin.mute` | 채널 생략 시 전체 |
| `/언뮤트 <닉> [채널]` | `crchat.admin.mute` | 채널 생략 시 전부 해제 |
| `/뮤트목록 <닉>` | `crchat.admin.mute` | 활성·과거 이력 |
| `/채팅로그 <닉> [기간]` | `crchat.admin.log` | 8장 |
| `/채팅로그 around <메시지ID>` | `crchat.admin.log` | 전후 문맥 |
| `/채팅로그 search <키워드> [기간]` | `crchat.admin.log` | 평문 검색 |
| `/공지 <내용>` | `crchat.admin.notice` | 전 서버 공지 — **공지 탭**으로 간다 (시스템 탭과 분리, 4.1) |
| `/crchat stats` | `crchat.admin` | 12.4 지표 |
| `/crchat reload` | `crchat.admin` | 채널·필터 설정 리로드 |

모든 관리 명령어는 **실행 로그를 남긴다.** 누가 누구를 언제 뮤트했는지
추적할 수 없으면 관리자 간 분쟁이 해결되지 않는다.

### 7.4 입력 문자 정규화 — 이모지를 안 써도 필수

커스텀 폰트를 쓰는 이상, **유저 입력에 섞인 특수 문자가 UI를 깨뜨릴 수 있다.**
서버에서 반드시 걸러낸다. 클라에서 거르면 우회된다.

| 대상 | 처리 | 이유 |
|---|---|---|
| **PUA 영역** (`U+E000~U+F8FF` 등) | **제거** | 리소스팩 UI 아이콘·음수 스페이싱 글리프가 여기 있다. 유저가 붙여넣으면 채팅창 레이아웃이 깨지거나 가짜 아이콘을 띄울 수 있다 |
| `§` (섹션 기호) | 제거 | 레거시 색코드 주입 |
| 개행 · 탭 (`\n`, `\r`, `\t`) | 공백 치환 | 여러 줄 메시지로 채팅창 도배 |
| RTL 제어문자 (`U+202E` 등) | 제거 | 문자열 뒤집기로 닉네임 위장 |
| 결합 문자 (zalgo) | 글자당 개수 제한 | 위아래로 뻗어 채팅창 침범 |
| 렌더 불가 문자 (유니코드 이모지 등) | **제거** | 두부(`□`)로 보여 가독성만 해친다 |
| 제로폭 문자 (`U+200B` 등) | 제거 | 금칙어 우회 |

```kotlin
object TextSanitizer {
    fun clean(raw: String): String
    /** 정제 후 빈 문자열이면 전송 자체를 거부한다 */
}
```

> **순서 주의**: 금칙어 검사는 **정규화 이후**에 해야 한다.
> 순서가 반대면 제로폭 문자를 끼워 넣는 것만으로 필터가 뚫린다.

**자동 제재는 두지 않는다.** 필터는 메시지를 차단할 뿐이고, 뮤트·차단 같은
제재는 **사람이 로그를 보고 판단**한다. 자동 뮤트는 오탐 한 번에 멀쩡한 유저를
막아버리고, 그 항의를 받아내는 비용이 얻는 것보다 크다.

---

## 8. 저장

```sql
CREATE TABLE chat_log (
  id          BIGINT PRIMARY KEY,      -- 스노우플레이크 (전역 유일)
  server_id   VARCHAR(32)  NOT NULL,   -- 발생 서버. 나중에 추가하면 과거 데이터 복구 불가
  channel     VARCHAR(32)  NOT NULL,
  sender_uuid CHAR(36),
  sender_name VARCHAR(32),             -- 당시 닉네임 스냅샷 (닉 변경 대비)
  target_uuid CHAR(36),                -- 귓속말 상대
  content     TEXT         NOT NULL,   -- 세그먼트 JSON
  plain       TEXT         NOT NULL,   -- 검색용 평문
  created_at  TIMESTAMP    NOT NULL,
  INDEX idx_sender_time (sender_uuid, created_at),
  INDEX idx_channel_time (channel, created_at),
  INDEX idx_created (created_at)        -- 보관 기간 만료 삭제용
);
```

- 비동기 배치 삽입 (100건 또는 3초 단위) — 메인 스레드 절대 차단 금지
- 보관 기간 config (기본 90일), 이후 자동 삭제
- 관리자 조회: `/채팅로그 <닉> [기간]`

**이 로그가 유일한 모더레이션 수단이다.** 유저 신고 기능도, 모장 신고도 없다.
따라서 조회 편의성이 곧 운영 품질이 된다.

| 명령어 | 용도 |
|---|---|
| `/채팅로그 <닉> [기간]` | 특정 유저의 발언 추적 |
| `/채팅로그 around <메시지ID>` | 해당 시점 전후 문맥 |
| `/채팅로그 search <키워드> [기간]` | 평문 컬럼 검색 |

`plain` 컬럼을 따로 둔 이유가 이 검색이다. 세그먼트 JSON만으로는 못 찾는다.

---

## 9. 서버 플러그인 구조

```
kr.teamcraft.crchat
├── api/        CRChatApi 구현체 (ServicesManager 등록)
├── channel/    Channel, ChannelRegistry, ChannelResolver
├── system/     SystemMessageService (카테고리별 on/off)
├── message/    ChatMessage 빌더, SegmentParser (입력 → 세그먼트)
├── format/     Formatter (등급·칭호·색상 적용)
├── filter/     SpamFilter, WordFilter, AdFilter (체인 구조)
├── link/       ItemLinkService (스냅샷 캐시)
├── mute/       MuteService, MuteCache (Redis 동기화)
├── storage/    ChatLogRepository (비동기 배치)
├── network/    RedisPublisher, RedisSubscriber, PlayerRegistry, ServerId
├── net/        ChatChannel (PluginMessage), SessionRegistry
└── command/    채널 전환·귓속말·관리 명령어
```

필터는 **체인 구조**로 둔다. 새 필터 추가 시 기존 코드를 안 건드리게.

```kotlin
interface ChatFilter {
    fun test(ctx: ChatContext): FilterResult   // PASS / BLOCK / MODIFY
}
```

---

## 10. 클라이언트 모드 구조

```
CRChatClient (Fabric 1.21.4)
├── net/        PacketHandler, MessageStore (히스토리 링버퍼)
├── ui/
│   ├── ChatScreen      바닐라 ChatScreen 대체
│   ├── ChatHud         렌더링 (탭·스크롤·하이라이트)
│   ├── TabBar / InputBar / SearchOverlay
│   └── tooltip/ItemTooltip
├── config/     크기·투명도·시스템 카테고리 on/off·차단목록 (로컬 JSON)
└── sound/      멘션·귓속말 알림
```

`MessageStore`는 **메모리 전용 링버퍼**다. 접속 종료 시 전부 소멸하므로
디스크 저장 로직이 없다. 5,000줄을 리스트로 들고 매 프레임 필터링하면
프레임드랍이 나므로, **탭별 인덱스를 따로 유지**하고 보이는 구간만 렌더한다.

전체 탭은 모든 메시지를 받으므로 인덱스가 아니라 **원본 버퍼 그대로**
참조하면 된다. 나머지 탭만 인덱스를 만든다.

`config/`에 저장하는 것은 **설정값뿐**이다 (창 크기·투명도·폰트·카테고리
on/off·차단 목록). 대화 내역은 저장하지 않는다.

---

## 11. 모드 미설치 폴백

| 항목 | 폴백 |
|---|---|
| 채널 | 탭이 없으므로 이때만 채널 태그를 붙인다 `[전체] 제로 : ...` |
| 탭·필터·검색 | 없음 (명령어로 채널 on/off만) |
| 아이템 링크 | 호버 텍스트로 축약 표시 |
| 멘션 알림 | 사운드만 |

핸드셰이크로 모드 유무를 판정하고, 같은 `ChatMessage`를 **평문으로 직렬화**해
바닐라 채팅으로 보낸다. 렌더러만 두 벌 두면 되도록 설계한다.

> 바닐라 채팅은 탭도 색 구분도 신뢰할 수 없으므로 **폴백에서만 채널 태그를
> 붙인다.** 이 분기는 `Formatter`에 두고, 도메인 로직에는 태그 개념을
> 넣지 않는다. 모드 유저에게는 태그 대신 색으로 구분한다 (4.2).

---

## 12. 성능 최적화

### 12.1 비용 구조 — 팬아웃이 전부다

메시지 1건(JSON 약 300바이트) 기준.

| 구간 | 1건당 | 판단 |
|---|---|---|
| Redis 발행 | 1회 | 서버 수 무관. **병목 아님** |
| Redis 수신 | 서버 수만큼 | 무시 가능 |
| **클라 전송** | **동접 수만큼** | ← 비용의 거의 전부 |
| DB 로깅 | 1행 | 배치하면 무시 가능 |

동접 300 기준 대역폭 (마크는 256바이트 초과 패킷을 zlib 압축, 약 1/3):

| 채팅량 | 압축 전 | 압축 후 |
|---|---|---|
| 5건/초 (평상시) | 450 KB/s | 150 KB/s |
| 20건/초 (피크) | 1.8 MB/s | 600 KB/s |

**대역폭은 위협이 아니다.** 진짜 위험은 **패킷 개수와 직렬화 횟수**이고,
그중에서도 개인 대상으로 상시 발생하는 **시스템 메시지**다.

---

### 12.2 구조적 결정 — 지금 반영해야 하는 것

나중에 넣으면 설계를 뜯어야 하는 항목들이다.

#### (1) 상태는 클라가 들고 있는다 — 효과 가장 큼

```
❌ 서버가 매초 "쿨타임 12초", "11초", "10초" ...  → 12패킷
✅ 서버가 "쿨타임 시작, 12초" 1회             → 1패킷, 클라가 카운트다운
```

**300명 × 초당 600패킷이 300명 × 12초당 1패킷이 된다.**
클라 모드를 만드는 진짜 이유가 여기 있다. 상태를 클라에 두면 트래픽이
두 자릿수 배로 줄어든다.

따라서 `CRChatApi`에 **상태성 알림 전용 진입점**을 따로 둔다.

```kotlin
interface CRChatApi {
    /** 1회성 메시지 — 채팅 로그에 남는다 */
    fun system(player: Player, message: Component, category: String = "general")

    /** 상태성 알림 — 클라가 카운트다운. 채팅 로그에 남기지 않는다 */
    fun timer(player: Player, key: String, seconds: Double, label: Component)
}
```

**원칙: 매초 갱신되는 것은 채팅이 아니다.** 쿨타임·버프 잔여시간 같은 것은
채팅창이 아니라 HUD/액션바로 간다. 채팅 로그 오염도 함께 막는다.

#### (2) 발신자 레지스트리 — 페이로드 60% 감소

지금 구조는 메시지마다 UUID(36자) + 닉네임 + 칭호 + 색을 통째로 싣는다.
같은 사람이 계속 말하는데 매번 반복된다.

```
세션 중 1회:  SENDER_DEF { id: 17, uuid, name: "제로", prefix: "[일류]", color: "#FFD866" }
이후 메시지:  { ch: 0, s: 17, seg: [...] }
```

발신자 참조를 2바이트 정수로 바꾸면 300바이트가 100바이트 밑으로 떨어진다.

**⚠ id는 클라 세션 한정이 아니라 네트워크 전역이어야 한다.**

"처음 등장하는 발신자에게 1회 전송"을 클라이언트 기준으로 하면, 클라마다
자기가 아는 발신자 집합이 다르고 같은 `id` 17이 서버 A 클라에게는 "제로",
서버 B 클라에게는 아직 미지의 ID가 된다. 그러면 (3) 단일 직렬화가 전제하는
"모두에게 동일 바이트"가 깨진다 — 발신자 참조 하나 때문에 클라마다 다시
인코딩해야 하는 상황이 온다.

```
❌ 서버 로컬/세션 한정 발급 → 클라마다 id 의미가 달라짐 → 단일 직렬화 불가
✅ 접속 시 네트워크 전역 1회 발급 (Redis INCR crchat:sender-seq) → 전 서버 동일 의미
```

- id는 **플레이어 접속 시** Redis `INCR`로 발급하고 `crchat:players`(2.2)에 함께 저장
- 발급 시점에 전 서버로 `SENDER_DEF`를 브로드캐스트할 필요는 없다 —
  각 클라는 **자신이 모르는 id를 실제로 받았을 때만** `REQUEST_SENDER`로 조회
- 레지스트리 자체는 **클라 세션 한정**으로 유지(접속 종료 시 소멸)하되,
  그 안의 id 값은 발급 시점부터 네트워크 전역에서 고정된 의미를 가진다
- 닉·칭호 변경 시 `SENDER_DEF`를 재전송해 갱신

#### (3) 단일 직렬화 — 반드시

순진하게 짜면 수신자마다 JSON을 다시 만든다.

```kotlin
// ❌ 300번 인코딩
recipients.forEach { it.send(PacketCodec.encode(msg)) }

// ✅ 1번 인코딩 후 바이트 재사용
val bytes = PacketCodec.encode(msg)
recipients.forEach { it.sendPluginMessage(plugin, CHANNEL, bytes) }
```

수신자별로 내용이 달라지면 이게 불가능한데, **차단·필터를 전부 클라 로컬로
설계한 덕분에** 모두에게 동일한 페이로드를 보낼 수 있다.
이 결정을 뒤집으면 최적화가 통째로 무너진다.

#### (4) displayName 캐싱

`ChatDecorator`가 등급·칭호·색을 계산한다. 메시지마다 돌리면 안 된다.
플레이어별 캐시하고 **등급·칭호 변동 시에만 무효화**한다.
(2)의 발신자 레지스트리와 자연스럽게 맞물린다.

#### (5) 틱 배칭

50ms 버퍼에 모았다가 **플레이어당 1패킷**으로 묶어 전송한다.
재련 결과 5줄이 한꺼번에 뜨는 상황이 1패킷이 된다.

```kotlin
class ChatBatcher {
    private val pending = mutableMapOf<UUID, MutableList<ByteArray>>()
    // 1틱(50ms)마다 flush, 또는 바이트 상한 도달 시 즉시 flush (아래 참조)
}
```

지연 50ms는 체감되지 않는다. 버스트를 접는 효과가 크다.

**⚠ 바이트 상한을 반드시 둔다.** Bukkit 플러그인 메시지는 방향별 크기 제한이
있다(서버→클라 약 32,767바이트). 50ms 동안 재련 결과 도배나 전체 공지가 몰리면
배칭이 이 상한을 조용히 넘겨 **클라 연결이 끊긴다.** 평소엔 안 터지다가
트래픽이 몰릴 때만 재현되는 버그라 원인 찾기가 특히 괴롭다.

```kotlin
class ChatBatcher {
    private val pending = mutableMapOf<UUID, MutableList<ByteArray>>()
    private val byteCount = mutableMapOf<UUID, Int>()
    private const val MAX_BATCH_BYTES = 28_000   // 상한(32,767)에 여유를 둔다

    fun offer(target: UUID, bytes: ByteArray) {
        pending.getOrPut(target) { mutableListOf() }.add(bytes)
        val total = (byteCount[target] ?: 0) + bytes.size
        byteCount[target] = total
        if (total >= MAX_BATCH_BYTES) flush(target)   // 틱을 기다리지 않고 즉시
    }
    // 1틱(50ms)마다도 flush
}
```

---

### 12.3 나중에 — 측정 후 적용

| 항목 | 내용 | 시점 |
|---|---|---|
| **바이너리 코덱** | JSON → 바이트. UUID는 long 2개, 채널은 1바이트 ordinal, 길이는 VarInt. 크기보다 **직렬화 CPU** 이득이 크다 | 트래픽 측정 후 |
| **Redis 로컬 우회** | 귓속말·길드 대상이 같은 서버면 Redis 왕복 생략 | 멀티서버 안정화 후 |
| **Redis 채널 샤딩** | `crchat:guild:<id>` 로 분리하고 해당 길드원이 있는 서버만 구독. 지금은 모든 서버가 모든 길드 채팅을 받아서 버린다 | 길드 도입 시 |
| **전역 상한** | 채널 쿨타임과 별개로 서버 전체 초당 상한(예: 30건). 매크로 대비 | 오픈 전 |
| **우선순위 드랍** | 상한 초과 시 버릴 순서를 미리 정의 | 오픈 전 |

**우선순위 드랍 순서** (뒤로 갈수록 먼저 버린다)

```
1. 귓속말        ← 절대 버리지 않음
2. 길드 / 파티
3. 시스템 (일반)
4. 전체 채팅
5. 시스템 (drop, cosmetic 등 저우선 카테고리)  ← 먼저 버림
```

과부하 때 귓속말이 사라지면 유저가 가장 크게 체감한다.
`CRChatApi`의 `category`가 여기서 다시 쓰인다.

---

### 12.4 측정 지표

최적화는 측정 없이 하지 않는다. Phase 5 시점에 아래를 계측한다.

| 지표 | 목표 |
|---|---|
| 초당 메시지 수 (채널별) | — (기준 수집) |
| 초당 전송 패킷 수 | 동접 300 기준 5,000 이하 |
| 메시지 1건 직렬화 시간 | 0.1ms 이하 |
| Redis 왕복 지연 | 5ms 이하 |
| 채팅 처리로 인한 틱 지연 | **0ms** (메인 스레드 작업 금지) |

`/crchat stats` 로 실시간 조회할 수 있게 한다.

---

### 12.5 하지 말 것

| 금지 | 이유 |
|---|---|
| 메시지 압축 직접 구현 | 마크가 이미 패킷 레벨에서 zlib 압축한다. 이중 압축은 CPU 낭비 |
| 수신자별 필터링을 서버에서 | 단일 직렬화가 불가능해진다. 필터는 클라 로컬 |
| 메시지마다 DB 동기 쓰기 | 메인 스레드가 죽는다. 반드시 비동기 배치 |
| 측정 전 바이너리 전환 | 디버깅 난이도만 올라간다. JSON으로 만들고 나중에 교체 |
| 히스토리 디스크 저장 | 세션 한정 정책과 충돌. I/O 비용만 발생 |

---

## 13. 로드맵

| Phase | 내용 | 검증 기준 |
|---|---|---|
| -1 | **한글 조합 오토마타 단독 구현 + 단위 테스트** (부록 A) | UI 없이 테스트 전원 통과 |
| 0 | protocol + `crchat:msg` 왕복, 핸드셰이크 | 모드 유무 판정 성공 |
| 1 | 서버: 채널 등록·권한·라우팅, 바닐라 채팅 취소 | 채널별 수신 대상 정확 |
| 2 | 클라: ChatHud 대체, 단일 탭 렌더 | 바닐라와 동일하게 보임 |
| 3 | 고정 탭 4개 + 전체 탭 집계·**채널 색 렌더** | 길드/파티가 색으로 구분됨 |
| 4 | 입력창 + 귓속말 동적 탭 (생성·호버 `×`·삭제·재생성) | 탭 조작 동작 |
| 4.5 | **api 모듈 + 시스템 채널** | 외부 플러그인에서 발행 성공 |
| 5 | 세그먼트 파서 + 멘션 + URL | 멘션 알림 동작 |
| 6 | 아이템 링크 | 호버 툴팁 |
| 7 | 문자 정규화(7.4) + 스팸·금칙어·광고 필터 | 우회 표기 차단 |
| 8 | **Redis 전파 + 플레이어 레지스트리** | 서버 간 전체 채팅·귓속말 전달 |
| 9 | 뮤트(채널별·기간제) + 로그 저장·조회 | 서버 간 뮤트 즉시 반영 |
| 10 | **발신자 레지스트리 + 단일 직렬화 + 틱 배칭** (12.2) | 동접 300 부하 테스트 통과 |
| 11 | 검색·설정 UI | |
| 12 | 폴백 렌더러 *(선택 — 아래 참조)* | 바닐라 클라 정상 동작 |

**Phase 2가 고비다.** 바닐라 `ChatHud`를 대체하면서 기존 시스템 메시지·
액션바·명령어 피드백이 깨지지 않게 해야 한다. 대체가 아니라 **가로채기**로
접근하는 게 안전하다.

**Phase 12(폴백)는 조건부다.** `no-mod-policy: kick` 으로 운영하면 바닐라
클라이언트가 애초에 접속하지 못하므로 폴백 렌더러가 필요 없다.
`fallback` 정책을 쓸 계획이 확정될 때만 착수한다. 그전까지는
**"모드 필수" 안내 후 킥**만 구현하면 된다 (11장).

단일 서버로만 운영할 계획이라면 Phase 8을 건너뛸 수 있다.
`redis.enabled: false` 로 두면 로컬 전달만으로 동작한다.

---

## 14. 리스크

| 항목 | 대응 |
|---|---|
| **한글 입력** | 자체 조합 오토마타로 해결 (부록 A). OS IME 미사용 |
| OS IME 이중 입력 | 안내 + `charTyped` 한글 문자 무시 |
| **ChatHud 대체 충돌** | 다른 모드와 믹스인 충돌 가능. 우선순위 조정, 대체보다 가로채기 |
| **프레임드랍** | 링버퍼 + 탭별 인덱스 + 가시 구간만 렌더 |
| 채팅 로그 DB 부하 | 비동기 배치, 인덱스 최소화, 보관 기간 제한 |
| **Redis 단일 장애점** | 끊겨도 로컬 전달 유지. 재전송 없음 |
| **파티가 서버를 못 넘음** | 바닐라 팀 한계. 초기엔 서버 로컬로 인정하고 안내 |
| 서버 이동 시 내역 소멸 오해 | 소멸 기준은 **프록시 연결 종료**임을 구현·안내에 명시 |
| 아이템 링크 위조 | 슬롯 번호만 수신, 서버가 실제 아이템 조회 |
| 금칙어 우회 | 정규화 후 검사(7.1). 완전 차단은 불가하므로 로그 기반 사후 제재로 보완 |
| 개인정보 | 클라는 내역을 저장하지 않음. **서버 로그만** 보관 기간 명시 후 이용약관 반영 |
| 뮤트 우회 | 클라 입력창 복구로는 못 뚫는다. 차단은 서버에서 |
| 페이로드 디코딩 실패 | Bukkit 은 길이 접두 없이 보낸다. `readByteArray()` 금지 (3.2) |
| 발신자 레지스트리 누락 | 클라가 모르는 `senderId` 수신 시 `REQUEST_SENDER` 로 복구 |
| **신고 경로 부재** | 유저 신고 기능이 없으므로 접수는 디스코드 등 외부 채널로. 운영 규칙에 명시 |
| 전체 탭 발신 채널 혼동 | `Channel`과 `Tab`을 코드에서 분리 (4.2). 이름 재사용 금지 |
| API 남용 | 시스템 메시지도 rate limit 적용. 플러그인별 발행량 로깅 |
| **고빈도 시스템 알림** | 상태성 알림은 `timer()`로 분리. 매초 갱신 금지 (12.2) |
| 수신자별 필터를 서버에 넣고 싶은 유혹 | 단일 직렬화가 깨진다. 필터는 클라 로컬 고정 |
| CRRPGCore와 중복 | 핸드셰이크·프로토콜 패턴은 유사하나 **코드 공유하지 않는다**. 독립성 우선 |

---

## 15. CRRPGCore와의 관계

**의존하지 않는다.** 채팅 모드만 설치해도 동작해야 한다.
연동은 **CRChat이 제공하는 API를 CRRPGCore가 호출하는 단방향**이다.

```kotlin
// CRRPGCore 쪽 — 재련 결과 알림
CRChatApi.get().system(player, Component.text("재련 성공! +16"), "refine")

// CRChat이 제공하는 확장 지점 — 발신자 태그 꾸미기
interface ChatDecorator {
    fun decorate(sender: Player, message: ChatMessage): ChatMessage
}
```

CRRPGCore가 설치되어 있으면 `ChatDecorator`를 구현해 **레벨·경지·길드 태그**를
붙인다. 없으면 기본 포맷으로 동작한다. 하드 의존 대신 이 방식을 쓴다.

반대 방향(CRChat이 CRRPGCore를 참조)은 **절대 만들지 않는다.**
한쪽만 설치해도 돌아가야 별도 프로젝트로 나눈 의미가 있다.

---

## 16. 범위 외 (Out of Scope)

의도적으로 만들지 않기로 한 것들. 나중에 다시 논의가 붙지 않도록 기록해 둔다.

| 항목 | 판단 | 나중에 붙인다면 |
|---|---|---|
| **디스코드 연동** | 하지 않음 | Redis `crchat:broadcast` 구독 어댑터가 확장점. 기존 플러그인(DiscordSRV 등)은 `AsyncChatEvent`를 후킹하므로 **우리 구조와 호환되지 않는다** |
| **유저 신고 기능** | 하지 않음 | 접수는 외부 채널(디스코드 등), 처리는 `/채팅로그` 조회 기반 수동 |
| **자동 제재** | 하지 않음 | 오탐 비용이 이득보다 크다. 필터는 차단만, 제재는 사람이 |
| **이모지** | 하지 않음 | 리소스팩 PUA 글리프 + `Segment.Emoji` 추가. 단 **폭 계산 로직을 다시 손봐야 한다** |
| 채팅 내역 클라 저장 | 하지 않음 | 세션 한정 정책과 충돌 |
| 한자 변환 / 세벌식 | 하지 않음 | 부록 A 조합기 확장 |
| 음성 채팅 | 하지 않음 | 별도 프로젝트 |

---

## 17. 확인 필요

한월 채팅 UI를 참고하려면 아래 스크린샷이 필요하다.

- 채팅창 전체 (탭 유무, 채널 표기 방식)
- 귓속말 화면
- 아이템 링크 표기 방식
- 채팅 설정 화면 (있다면)

---

## 부록 A. 한글 입력 — 자체 조합 오토마타

### A.1 방침

**OS IME를 사용하지 않는다.** 대신 모드가 직접 두벌식 조합기를 구현한다.

| 방식 | 판단 |
|---|---|
| OS IME 연동 (IMM32 / NSTextInputClient / ibus) | ❌ OS별 네이티브 3벌, GLFW가 preedit 미노출 |
| GLFW 포크 사용 | ❌ 런처가 배포하는 LWJGL을 교체해야 함. 유지보수 부담 |
| AWT 숨김 입력창 | ❌ macOS 불안정, 포커스 충돌 |
| **자체 조합 오토마타** | ✅ 순수 JVM 코드, OS 무관, 단위 테스트 가능 |

**제약**: 한자 변환 없음, 세벌식 미지원. RPG 서버 채팅에는 문제되지 않는다.

### A.2 유니코드 조합 공식

```
음절 = 0xAC00 + (초성index × 21 + 중성index) × 28 + 종성index
```

| 구분 | 개수 | 목록 |
|---|---|---|
| 초성 | 19 | ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ |
| 중성 | 21 | ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ |
| 종성 | 28 | (없음)ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ |

### A.3 두벌식 키맵

```
q ㅂ  w ㅈ  e ㄷ  r ㄱ  t ㅅ  y ㅛ  u ㅕ  i ㅑ  o ㅐ  p ㅔ
a ㅁ  s ㄴ  d ㅇ  f ㄹ  g ㅎ  h ㅗ  j ㅓ  k ㅏ  l ㅣ
z ㅋ  x ㅌ  c ㅊ  v ㅍ  b ㅠ  n ㅜ  m ㅡ

Shift  Q ㅃ  W ㅉ  E ㄸ  R ㄲ  T ㅆ  O ㅒ  P ㅖ
```

**중요**: `charTyped`가 아니라 `keyPressed(keyCode, scanCode, modifiers)`에서
**GLFW 키 상수**로 매핑한다. `charTyped`는 OS 키보드 레이아웃에 따라 결과가
달라져서, 유저가 OS IME를 한글로 켜두면 엉뚱한 문자가 들어온다.

### A.4 조합 상태 머신

```
EMPTY ──자음──▶ CHO ──모음──▶ JUNG ──자음──▶ JONG
                 │              │              │
              모음│           모음│(겹모음)    자음│(겹받침)
                 ▼              ▼              ▼
               JUNG           JUNG           JONG2
```

**핵심 규칙**

| 상황 | 처리 |
|---|---|
| JUNG 상태에서 모음 입력 | 겹모음 가능하면 결합, 아니면 **확정 후 새 글자** |
| JONG 상태에서 모음 입력 | **종성을 떼어 다음 글자의 초성으로 이동** (`간` + `ㅏ` → `가나`) |
| JONG2(겹받침)에서 모음 | 뒤 자모만 떼어 다음 초성으로 (`읽` + `ㅓ` → `일거`) |
| CHO 상태에서 자음 | 이전 글자 확정 후 새 글자 시작 |

```
겹모음  ㅗ+ㅏ=ㅘ  ㅗ+ㅐ=ㅙ  ㅗ+ㅣ=ㅚ  ㅜ+ㅓ=ㅝ
        ㅜ+ㅔ=ㅞ  ㅜ+ㅣ=ㅟ  ㅡ+ㅣ=ㅢ

겹받침  ㄱ+ㅅ=ㄳ  ㄴ+ㅈ=ㄵ  ㄴ+ㅎ=ㄶ  ㄹ+ㄱ=ㄺ  ㄹ+ㅁ=ㄻ
        ㄹ+ㅂ=ㄼ  ㄹ+ㅅ=ㄽ  ㄹ+ㅌ=ㄾ  ㄹ+ㅍ=ㄿ  ㄹ+ㅎ=ㅀ  ㅂ+ㅅ=ㅄ
```

### A.5 백스페이스 — 역조합

조합 중인 글자가 있으면 **글자 전체가 아니라 마지막 자모 하나만** 지운다.

```
한 → (Backspace) → 하 → (Backspace) → ㅎ → (Backspace) → (삭제)
```

이걸 구현하려면 조합 상태를 `(cho, jung, jong)` 인덱스로 들고 있어야 한다.
완성 문자열만 들고 있으면 역조합이 불가능하다.

### A.6 확정(commit) 시점

- 스페이스 · 엔터 · 문장부호 · 숫자 입력
- 한/영 전환
- 커서 이동 (←→, Home, End)
- 채팅창 닫기 · 포커스 상실
- **자음/모음 규칙상 새 글자가 시작될 때** (가장 잦은 경우)

### A.7 한/영 전환

`한/영` 키(`GLFW_KEY_HANGUL`)는 OS가 먼저 소비해서 모드까지 안 오는 경우가
많다. **기본값은 `우측 Alt`** 로 하고 설정에서 변경 가능하게 한다.

전환 상태는 **채팅창 좌측에 표시**한다. 지금 한글인지 영문인지 안 보이면
유저가 매번 한 글자 쳐보고 확인하게 된다.

### A.8 조합 중 렌더

```
전체 ▸ 안녕하세요 한|
                  ‾‾  ← 조합 중인 글자에 밑줄
```

조합 중인 글자는 **밑줄 + 커서 앞**에 그린다.
확정 전 문자열은 실제 입력 버퍼와 분리해서 관리한다.

```kotlin
class HangulComposer {
    private var cho = -1
    private var jung = -1
    private var jong = 0

    /** 조합 중인 글자. 없으면 null */
    val preedit: Char? get() = ...

    /** 키 입력 처리. 확정된 문자열이 있으면 반환 */
    fun input(jamo: Char): String?

    /** @return 백스페이스를 소비했으면 true */
    fun backspace(): Boolean

    /** 강제 확정 */
    fun flush(): String?
}
```

### A.9 OS IME 충돌

유저가 OS IME를 한글로 켜둔 채 접속하면 **이중 입력**이 발생한다.

| 대응 | 내용 |
|---|---|
| 안내 | 최초 실행 시 "OS 입력기는 영문으로 두세요" 1회 안내 |
| 감지 | `charTyped`로 한글 완성 문자가 들어오면 OS IME가 켜진 것 → 경고 표시 |
| 무시 | 조합기 활성 상태에서는 `charTyped`의 한글 문자를 **버린다** |

Windows 한정으로 JNI로 IMM을 끌 수도 있지만, **Phase 1 범위에서는 제외**한다.
안내와 무시만으로 실사용에 충분하다.

### A.10 검증 계획

이 기능은 **단위 테스트가 가능한 유일한 UI 로직**이다. 반드시 테스트를 쓴다.

```kotlin
@Test fun `종성 분리`() {
    assertEquals("가나", type("rksk"))      // ㄱㅏㄴㅏ
}
@Test fun `겹받침 분리`() {
    assertEquals("일거", type("dlfrj"))
}
@Test fun `백스페이스 역조합`() {
    assertEquals("하", typeThenBackspace("gksk", 2))
}
```

**Phase 2 착수 전에 조합기만 따로 만들어 테스트를 통과시킨다.**
UI에 붙인 뒤 디버깅하면 원인이 조합 로직인지 렌더인지 구분이 안 된다.

---

## 부록 B. 테스트 전략

### B.1 방침

**순수 로직만 단위 테스트한다.** MockBukkit·클라 UI 자동화는 하지 않는다.

| 대상 | 방식 | 이유 |
|---|---|---|
| 순수 로직 4종 (B.2) | **JUnit 단위 테스트** | 예외 케이스가 끝없이 나오는 곳. 투자 대비 효과 최대 |
| 서버 플러그인 (채널 라우팅·권한) | 수동 | 한 번 짜면 잘 안 바뀐다. MockBukkit은 학습 비용 대비 이득이 적다 |
| 클라 모드 UI | 수동 체크리스트 (B.5) | 자동화 사실상 불가 |
| 멀티서버·Redis | 로컬 통합 환경 수동 | 프록시 1 + 백엔드 2 구성 |

### B.2 테스트 대상 — 이 4개만

모두 **Bukkit·마크에 의존하지 않는 순수 함수**다. 그래서 테스트가 쉽다.

| 대상 | 왜 |
|---|---|
| `HangulComposer` (부록 A) | 겹받침·종성분리·역조합 등 경계 케이스 다수. **1순위** |
| `TextSanitizer` (7.4) | PUA·제로폭·RTL 등 놓치기 쉬운 문자 처리 |
| 필터 체인 (7장) | 유사도 판정, 금칙어 우회 패턴 |
| 기간 파서 (`10m`/`1h`/`perm`) | 잘못 파싱하면 영구 뮤트 사고 |

여유가 되면 `PacketCodec` 왕복(encode → decode → 원본과 동일)도 추가한다.
직렬화 버그는 런타임에 잡기 가장 괴롭다.

### B.3 설정

```kotlin
// build.gradle.kts (해당 모듈)
dependencies {
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
```

파일 위치는 `src/test/kotlin/...`. 소스와 같은 패키지 구조를 쓴다.
실행은 `./gradlew test`.

### B.4 작성 요령

처음이라면 아래 형태만 반복하면 된다.

```kotlin
class HangulComposerTest {

    @Test
    fun `종성이 다음 글자 초성으로 넘어간다`() {
        // given
        val composer = HangulComposer()

        // when
        val result = composer.typeAll("rksk")   // ㄱㅏㄴㅏ

        // then
        assertEquals("가나", result)
    }

    @Test
    fun `겹받침은 뒤 자모만 넘어간다`() {
        assertEquals("일거", HangulComposer().typeAll("dlfrj"))
    }

    @Test
    fun `백스페이스는 자모 단위로 지운다`() {
        val c = HangulComposer()
        c.typeAll("gks")        // 한
        c.backspace()           // 하
        c.backspace()           // ㅎ
        assertEquals("ㅎ", c.preeditText())
    }
}
```

**요령 3가지**

1. **테스트 이름은 한글로 문장처럼** 쓴다. 백틱으로 감싸면 공백을 쓸 수 있다.
   나중에 실패했을 때 뭐가 깨졌는지 바로 읽힌다
2. **given / when / then** 3단 구조로 쓴다. 익숙해지면 주석은 빼도 된다
3. **버그를 발견하면 먼저 그 케이스를 테스트로 만들고** 고친다.
   같은 버그가 다시 나는 것을 막는 가장 싼 방법이다

### B.5 수동 체크리스트

자동화하지 않는 영역은 체크리스트로 대신한다. Phase 완료 시마다 훑는다.

**한글 입력**
- [ ] `한글`, `밝다`, `읽어`, `괜찮아` 정상 입력
- [ ] 조합 중 백스페이스 → 자모 단위 삭제
- [ ] 조합 중 엔터 → 확정 후 전송
- [ ] 한/영 전환 후 조합 상태 초기화
- [ ] OS 입력기를 한글로 켠 상태에서 이중 입력 안 됨

**채널·탭**
- [ ] 전체 탭에서 길드·파티 메시지가 색으로 구분됨
- [ ] 길드 탭에는 길드 메시지만
- [ ] 길드 provider 미등록 시 탭 숨김
- [ ] 파티 미소속 시 탭 회색
- [ ] 시스템 탭에 `CRChatApi` 발행분만

**귓속말**
- [ ] 처음 수신 시 탭 자동 생성
- [ ] 탭 호버 시에만 `×` 노출
- [ ] 삭제 후 재수신 시 탭 재생성
- [ ] 접속 종료 후 재접속 시 내역 소멸

**멀티서버**
- [ ] 로비 ↔ 필드 간 전체 채팅 전달
- [ ] 다른 서버 유저에게 귓속말 전달
- [ ] 서버 이동 시 탭·내역 유지
- [ ] Redis 중단 시 로컬 채팅은 유지
- [ ] 뮤트가 전 서버에 즉시 반영

**부하**
- [ ] 동접 시뮬레이션 상태에서 틱 지연 0ms
- [ ] `/crchat stats` 지표가 12.4 목표 이내

### B.6 하지 않는 것

| 항목 | 이유 |
|---|---|
| MockBukkit | 학습 비용 대비 이득 적음. 라우팅 로직은 안정적이다 |
| 클라 UI 자동화 | 렌더링 검증은 사실상 불가 |
| 커버리지 목표치 | 숫자를 맞추려 의미 없는 테스트를 쓰게 된다 |
| CI 파이프라인 | 1인 개발에서는 로컬 `./gradlew test` 로 충분 |

---

## 부록 C. 설정 파일 구조

### C.1 파일 분리

```
plugins/CRChat/
├── config.yml        서버 식별·DB·Redis·성능
├── channels.yml      채널 정의 (색·쿨타임·권한)
├── filters.yml       스팸·광고·정규화 규칙
├── words.txt         금칙어 목록 (한 줄 = 한 단어)
└── messages.yml      유저에게 보이는 문구 전부
```

**분리 기준은 "변경 빈도"다.**

| 파일 | 변경 빈도 | 이유 |
|---|---|---|
| `config.yml` | 거의 없음 | 인프라 설정. 잘못 건드리면 서버가 안 뜬다 |
| `channels.yml` | 가끔 | 채널 추가·색 조정 |
| `filters.yml` | 가끔 | 임계값 튜닝 |
| `words.txt` | **매우 잦음** | 운영하며 계속 추가된다 |
| `messages.yml` | 가끔 | 문구 다듬기 |

금칙어를 `config.yml`에 넣으면 파일이 수백 줄로 불어나고, 단어 하나 추가하려다
DB 설정을 건드리는 사고가 난다. **별도 텍스트 파일**로 빼면 리로드도 이것만
따로 할 수 있다.

`messages.yml`을 나눈 이유는 다르다. 문구가 코드에 박히면 오타 하나 고치는 데
**모드까지 재빌드**해야 한다. 유저에게 보이는 문자열은 예외 없이 여기 둔다.

---

### C.2 config.yml

```yaml
# ══════════════════════════════════════════
#  CRChat — 기본 설정
#  이 파일은 서버 인프라 설정입니다. 신중히 변경하세요.
# ══════════════════════════════════════════

# 이 서버의 고유 식별자.
# 멀티서버에서 메시지 중복 전달을 막는 데 쓰입니다. 절대 겹치면 안 됩니다.
server-id: "lobby"

# 스노우플레이크 메시지 ID에 쓰는 노드 번호(0~1023).
# server-id 와는 별개입니다 — 문자열을 해시해서 쓰면 해시 충돌 시
# 두 서버가 같은 번호를 공유하게 되어 메시지 ID 유일성이 깨집니다.
# 기동 시 Redis 에 중복 등록되어 있으면 서버가 뜨지 않습니다. (2.2)
node-id: 1

# 프로토콜 버전이 다른 클라이언트는 접속을 거부합니다.
# 값을 직접 수정하지 마세요. 모드 업데이트 시 자동으로 맞춰집니다.
protocol-version: 1

# 모드가 설치되지 않은 클라이언트 처리
# kick   : 안내 후 강제 퇴장 (권장)
# fallback : 바닐라 채팅으로 폴백 (기능 제한)
no-mod-policy: kick

database:
  host: "127.0.0.1"
  port: 3306
  name: "crchat"
  user: "crchat"
  password: "변경하세요"
  # 채팅 로그 보관 기간(일). 지나면 자동 삭제됩니다.
  # 이용약관에 명시한 기간과 반드시 일치시키세요.
  log-retention-days: 90
  # 로그 저장 배치 단위. 이 개수가 쌓이거나 시간이 지나면 한 번에 씁니다.
  batch-size: 100
  batch-interval-seconds: 3

redis:
  enabled: true            # 단일 서버라면 false 로 두어도 동작합니다
  host: "127.0.0.1"
  port: 6379
  password: ""
  # 온라인 플레이어 정보 만료 시간(초).
  # 서버가 비정상 종료돼도 이 시간이 지나면 자동으로 정리됩니다.
  presence-ttl: 60
  presence-heartbeat: 20

performance:
  # 메시지를 모아 보내는 간격(ms). 0 이면 즉시 전송합니다.
  # 50ms 는 체감되지 않으면서 버스트를 크게 줄여줍니다.
  batch-interval-ms: 50
  # 클라가 보관하는 최대 줄 수. 접속 종료 시 모두 사라집니다.
  history-limit: 5000
  # 서버 전체 초당 메시지 상한. 초과분은 우선순위가 낮은 것부터 버립니다.
  global-rate-limit: 30

vanilla-messages:
  # 바닐라·외부 플러그인이 보내는 메시지를 전체 탭에 표시할지 여부
  join-quit: true
  death: true
  advancement: true
  # 명령어 실행 결과입니다. 끄면 유저가 서버 고장으로 오해합니다.
  command-feedback: true

logging:
  # 관리 명령어 실행 내역을 기록합니다. 관리자 간 분쟁 해결에 필요합니다.
  admin-actions: true
```

---

### C.3 channels.yml

```yaml
# ══════════════════════════════════════════
#  채널 정의
#  색은 전체 탭에서 채널을 구분하는 유일한 수단입니다.
#  비슷한 색을 쓰면 유저가 구분하지 못합니다.
# ══════════════════════════════════════════

global:
  display: "전체"
  color: "#FFFFFF"
  tag: "<전체>"          # 접근성 옵션 또는 폴백에서만 사용
  cooldown: 2.0
  min-level: 0
  permission: crchat.channel.global

notice:
  display: "공지"
  color: "#FF6B6B"
  tag: "<공지>"
  receive-only: true    # /공지 명령어로만 발행됩니다. 시스템과 분리하는 이유는 4.1 참조

general:
  display: "일반"
  color: "#B8E986"
  tag: "<일반>"
  cooldown: 1.0
  permission: crchat.channel.general
  radius: 100.0          # 이 반경(블록) 안의 온라인 플레이어만 수신합니다.

guild:
  display: "길드"
  color: "#FFE066"
  tag: "<길드>"
  cooldown: 0.5
  # GroupProvider 가 등록되지 않으면 탭 자체가 표시되지 않습니다.
  requires: guild

party:
  display: "파티"
  color: "#7FD8E8"
  tag: "<파티>"
  cooldown: 0.5
  requires: party

whisper:
  display: "귓속말"
  color: "#FFA657"
  tag: "<귓>"
  cooldown: 0.3

system:
  display: "시스템"
  # 색을 지정하지 않습니다. 발행하는 플러그인이 직접 색을 넣어야 합니다.
  # 색 없이 발행하면 전체 채팅과 구분되지 않습니다.
  color: null
  tag: "▶"
  receive-only: true

# ── 파티 그룹 판정 ────────────────────────
party-provider:
  type: vanilla-team
  # 이 접두어로 시작하는 스코어보드 팀만 파티로 인정합니다.
  # 접두어가 없으면 색 구분용 팀까지 파티가 되어버립니다.
  team-prefix: "party_"
```

---

### C.4 filters.yml

```yaml
# ══════════════════════════════════════════
#  스팸·검열 규칙
#  필터는 메시지를 차단만 합니다. 자동 뮤트는 하지 않습니다.
# ══════════════════════════════════════════

# 문자 정규화. 금칙어 검사보다 먼저 수행됩니다.
# 순서가 바뀌면 제로폭 문자 하나로 필터가 뚫립니다.
sanitize:
  remove-pua: true           # 리소스팩 UI 글리프 주입 차단
  remove-section-sign: true  # § 색코드 주입 차단
  remove-zero-width: true    # 금칙어 우회 차단
  remove-rtl-override: true  # 닉네임 위장 차단
  remove-unrenderable: true  # 두부(□)로 보이는 문자 제거
  newline-to-space: true
  max-combining-marks: 3     # zalgo 방지

spam:
  # 초당 최대 메시지 수
  max-per-second: 2
  # 최근 몇 개와 비교할지
  history-size: 5
  # 유사도가 이 값을 넘으면 도배로 판단합니다 (0.0 ~ 1.0)
  similarity-threshold: 0.85

ad:
  block-url: true
  block-discord-invite: true
  # 차단하지 않을 도메인
  whitelist:
    - "youtube.com"
    - "imgur.com"

words:
  enabled: true
  file: "words.txt"
  # block : 전송 자체를 거부
  # mask  : ***로 치환해 전송
  action: block
```

---

### C.5 messages.yml

```yaml
# ══════════════════════════════════════════
#  유저에게 보이는 문구
#  코드에 문자열을 직접 쓰지 마세요.
#  오타 하나 고치려고 모드를 재빌드하게 됩니다.
# ══════════════════════════════════════════

no-mod: "이 서버는 전용 런처로 접속해야 합니다."
version-mismatch: "클라이언트 버전이 맞지 않습니다. 런처를 업데이트해주세요."

cooldown: "잠시 후 다시 시도해주세요. (%seconds%초)"
muted: "뮤트 상태입니다 · 남은 시간 %remain% · 사유: %reason%"
muted-permanent: "영구 뮤트 상태입니다 · 사유: %reason%"

channel-unavailable: "%channel% 채널을 이용할 수 없습니다."
party-not-joined: "파티에 소속되어 있지 않습니다."
guild-not-joined: "길드에 소속되어 있지 않습니다."

# 파티가 서버를 넘지 못한다는 점을 반드시 안내해야 합니다.
# 없으면 버그 문의로 들어옵니다.
party-cross-server: "파티 채팅은 같은 지역에 있는 인원에게만 전달됩니다."

whisper-offline: "%player% 님은 접속 중이 아닙니다."
whisper-self: "자기 자신에게는 귓속말을 보낼 수 없습니다."

blocked-spam: "같은 내용을 반복해서 보낼 수 없습니다."
blocked-word: "사용할 수 없는 단어가 포함되어 있습니다."
blocked-ad: "링크는 전송할 수 없습니다."
blocked-empty: "전송할 내용이 없습니다."

redis-degraded: "일부 채널 연결이 불안정합니다."
```

---

### C.6 리로드

```
/crchat reload           전체
/crchat reload words     금칙어만 (가장 자주 쓰임)
```

**`config.yml`은 리로드 대상에서 제외한다.** DB·Redis 접속 정보와 `server-id`는
런타임에 바꾸면 연결 상태가 꼬인다. 이 파일은 재시작을 요구한다.

| 파일 | 리로드 |
|---|---|
| `config.yml` | ❌ 재시작 필요 |
| `channels.yml` | ✅ (접속 중인 클라에 재전송) |
| `filters.yml` | ✅ |
| `words.txt` | ✅ |
| `messages.yml` | ✅ |
