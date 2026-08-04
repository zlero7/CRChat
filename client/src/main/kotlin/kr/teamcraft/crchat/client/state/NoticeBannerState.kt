package kr.teamcraft.crchat.client.state

/**
 * `/공지` 를 화면 가로로 흐르는 배너로 보여주기 위한 상태. (사용자 요청 — 참고
 * 이미지처럼 채팅 로그에 묻히지 않고 확실히 눈에 띄어야 한다는 것)
 *
 * 채팅 로그(공지 탭)와는 완전히 별개다 — 공지는 여전히 [ChatState] 에도 그대로
 * 쌓인다. 이건 그 위에 추가로 잠깐 띄우는 연출일 뿐이라, 세션 메모리에만 있고
 * 저장하지 않는다.
 */
object NoticeBannerState {

    var text: String? = null
        private set

    private var startTime: Long = 0L

    /** 이 시간이 지나면 스크롤 도중이라도 배너를 치운다 — 공지 하나가 화면을 계속 가리면 안 된다. */
    private const val DISPLAY_DURATION_MS = 15_000L

    fun show(newText: String) {
        if (newText.isBlank()) return
        text = newText
        startTime = System.currentTimeMillis()
    }

    val elapsedMs: Long get() = System.currentTimeMillis() - startTime

    /** @return 지금 그려야 하면 true. 시간이 다 됐으면 여기서 스스로 정리한다. */
    fun isActive(): Boolean {
        if (text == null) return false
        if (elapsedMs > DISPLAY_DURATION_MS) {
            text = null
            return false
        }
        return true
    }
}
