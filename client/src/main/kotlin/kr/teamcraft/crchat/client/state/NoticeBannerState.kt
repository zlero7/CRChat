package kr.teamcraft.crchat.client.state

/**
 * `/공지` 를 화면 가로로 흐르는 배너로 보여주기 위한 상태. (사용자 요청 — 참고
 * 이미지처럼 채팅 로그에 묻히지 않고 확실히 눈에 띄어야 한다는 것)
 *
 * 채팅 로그(공지 탭)와는 완전히 별개다 — 공지는 여전히 [ChatState] 에도 그대로
 * 쌓인다. 이건 그 위에 추가로 잠깐 띄우는 연출일 뿐이라, 세션 메모리에만 있고
 * 저장하지 않는다.
 *
 * "한 번 지나가면 사라지게" 하기 위해, 언제 끝나는지는 여기서 고정 시간으로 정하지
 * 않는다 — 텍스트 폭·화면 폭에 따라 한 바퀴 도는 데 걸리는 시간이 다르므로, 실제로
 * 화면을 다 가로지르는 시점은 [kr.teamcraft.crchat.client.ui.NoticeBannerRenderer]
 * 가 매 프레임 진행도를 계산해서 판단하고, 다 지나가면 [clear] 를 부른다.
 */
object NoticeBannerState {

    var text: String? = null
        private set

    private var startTime: Long = 0L

    fun show(newText: String) {
        if (newText.isBlank()) return
        text = newText
        startTime = System.currentTimeMillis()
    }

    val elapsedMs: Long get() = System.currentTimeMillis() - startTime

    /** 한 바퀴를 다 지나갔을 때 렌더러가 부른다. */
    fun clear() {
        text = null
    }
}
