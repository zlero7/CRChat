package kr.teamcraft.crchat.client.ui

/**
 * 고정 탭. 귓속말 동적 탭은 다음 단계다 (기획서 4.5).
 * `channelId` 는 수신 필터, `sendChannelId` 는 발신 대상이다 — 전체 탭에서
 * 이 둘이 다른 이유는 4.2 "전체 탭의 이중성" 참조.
 */
enum class Tab(val channelId: String?, val label: String) {
    ALL(null, "전체"),
    NOTICE("notice", "공지"),
    GENERAL("general", "일반"),
    GUILD("guild", "길드"),
    PARTY("party", "파티"),
    SYSTEM("system", "시스템");

    /** null 이면 이 탭에서는 발신할 수 없다 (수신 전용 탭). */
    val sendChannelId: String?
        get() = when (this) {
            ALL -> "global"
            NOTICE, SYSTEM -> null
            else -> channelId
        }
}
