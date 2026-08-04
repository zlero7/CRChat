package kr.teamcraft.crchat.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `crchat:msg` 채널로 오가는 패킷. (기획서 3.2)
 *
 * 페이로드 규약 주의: Bukkit 플러그인 메시지는 길이 접두 없는 순수 바이트로 온다.
 * Fabric 디코더에서 `readByteArray()` 를 쓰면 VarInt 길이를 먼저 읽으려 해서 깨진다.
 * **남은 바이트 전체를 읽어야 한다.** 이건 `client` 모듈의 디코더가 지켜야 할
 * 규칙이고, 이 모듈(순수 JSON 문자열 <-> 바이트)에는 해당하지 않는다.
 */
@Serializable
sealed class S2C {

    @Serializable
    @SerialName("hello")
    data class Hello(val protocolVersion: Int, val serverId: String) : S2C()

    @Serializable
    @SerialName("session_init")
    data class SessionInit(
        val availableChannels: List<ChannelDef>,
        val mutes: List<MuteInfo>,
    ) : S2C()

    @Serializable
    @SerialName("sender_def")
    data class SenderDefs(val senders: List<SenderDef>) : S2C()

    @Serializable
    @SerialName("message")
    data class Messages(val messages: List<ChatMessage>) : S2C()

    /** 상태성 알림. 클라가 [seconds] 부터 카운트다운한다. 채팅 로그에 남지 않는다. (12.2(1)) */
    @Serializable
    @SerialName("timer")
    data class Timer(val key: String, val seconds: Double, val label: String) : S2C()

    @Serializable
    @SerialName("mute_update")
    data class MuteUpdate(val mutes: List<MuteInfo>) : S2C()

    @Serializable
    @SerialName("channel_update")
    data class ChannelUpdate(val availableChannels: List<ChannelDef>) : S2C()

    /** [reasonKey] 는 `messages.yml` 의 키다. */
    @Serializable
    @SerialName("error")
    data class Error(val reasonKey: String) : S2C()

    /** `/채팅청소` — 클라가 갖고 있는 채팅 로그를 전부 지운다. (관리자 명령어) */
    @Serializable
    @SerialName("clear_messages")
    data object ClearMessages : S2C()
}

@Serializable
sealed class C2S {

    @Serializable
    @SerialName("hello")
    data class Hello(val protocolVersion: Int, val modVersion: String) : C2S()

    /** 전부 서버가 재검증한다 — 권한·쿨타임·뮤트·정규화. (2.4) */
    @Serializable
    @SerialName("send")
    data class Send(val channel: String, val segments: List<Segment>) : C2S()

    /** 슬롯 번호만 보낸다. 실제 아이템은 서버가 읽는다. (6.2) */
    @Serializable
    @SerialName("link_item")
    data class LinkItem(val slot: Int) : C2S()

    @Serializable
    @SerialName("request_sender")
    data class RequestSender(val senderId: Int) : C2S()

    @Serializable
    @SerialName("request_item")
    data class RequestItem(val linkId: String) : C2S()
}
