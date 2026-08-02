package kr.teamcraft.crchat.api

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player

/**
 * 외부 플러그인이 CRChat 시스템 채널에 발행하기 위한 공개 API. (기획서 4.4)
 *
 * CRChat 미설치 시를 대비해 이 인터페이스는 `api` 모듈로 별도 배포된다.
 * [get] 이 서비스를 못 찾으면 예외를 던지므로, 호출부는 CRChat 미설치 시
 * 일반 채팅으로 폴백하도록 감싸야 한다.
 *
 * 참고(Java 호출부): 아래 메서드들의 `category` 기본값은 Kotlin 인터페이스의
 * 추상 멤버라 Java 오버로드로 컴파일되지 않는다 (`@JvmOverloads` 는 본문이
 * 있는 함수에만 적용된다). Java 에서는 `category` 를 항상 명시해야 한다.
 */
interface CRChatApi {

    /** 특정 플레이어에게 시스템 메시지. [message] 는 색을 포함한 완성된 Component 여야 한다. */
    fun system(player: Player, message: Component, category: String = "general")

    /**
     * 상태성 알림 (쿨타임·잔여시간). 클라가 카운트다운하므로 1회만 보낸다.
     * 채팅 로그에 남지 않으며 HUD 로 렌더된다. **매초 갱신되는 것은 채팅이 아니다.** (12.2(1))
     */
    fun timer(player: Player, key: String, seconds: Double, label: Component)

    /** 여러 명에게 시스템 메시지 */
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
