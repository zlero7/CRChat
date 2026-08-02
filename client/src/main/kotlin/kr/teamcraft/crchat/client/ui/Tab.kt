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

    /**
     * 전체·공지·시스템은 자기 채널이 없다(전체는 집계 뷰, 공지·시스템은 수신 전용) —
     * 이 탭들에서 타이핑하면 "일반"으로 보낸다.
     */
    val sendChannelId: String
        get() = when (this) {
            ALL, NOTICE, SYSTEM -> "general"
            else -> channelId ?: "general"
        }
}
