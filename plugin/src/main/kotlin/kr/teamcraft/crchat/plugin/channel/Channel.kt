package kr.teamcraft.crchat.plugin.channel

import kr.teamcraft.crchat.api.GroupType

/**
 * 실제 메시지가 흐르는 논리 채널. (기획서 4.2)
 *
 * 클라의 "탭"과는 다른 개념이다 — 전체 탭은 모든 채널을 수신하지만 발신은
 * [GLOBAL] 하나뿐이다. 이 구분을 코드에서 지키지 않으면 반드시 헷갈린다.
 *
 * [NOTICE] 와 [GENERAL] 은 참고 UI(한월류) 확인 후 4.1 에서 추가됐다.
 */
enum class Channel(val id: String) {
    GLOBAL("global"),
    NOTICE("notice"),
    GENERAL("general"),
    GUILD("guild"),
    PARTY("party"),
    SYSTEM("system");

    companion object {
        fun fromId(id: String): Channel? = entries.find { it.id == id }
    }
}

/** channels.yml 한 항목. (부록 C.3) */
data class ChannelConfig(
    val channel: Channel,
    val display: String,
    /** null 이면 발행 플러그인이 색을 직접 지정한다 (system 채널) */
    val color: String?,
    val cooldownSeconds: Double,
    val permission: String?,
    /** non-null 이면 해당 GroupProvider 가 등록되어 있어야 채널이 열린다 */
    val requires: GroupType?,
    val receiveOnly: Boolean = false,
    /** non-null 이면 이 반경(블록) 안의 온라인 플레이어만 수신한다 (일반 채널, 4.1) */
    val radius: Double? = null,
)
