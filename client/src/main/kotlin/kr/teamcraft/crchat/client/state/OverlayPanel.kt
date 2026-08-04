package kr.teamcraft.crchat.client.state

import kr.teamcraft.crchat.client.ui.Tab

/**
 * 화면에 떠 있는 채팅 패널 하나. (기획서 5.1 "자유 조절, 프리셋 저장")
 *
 * `tab == null` 이면 메인 패널이다 — 탭 전환·입력창이 있다.
 * 그 외에는 특정 채널만 보여주는 **분리 창**(읽기 전용)이다.
 *
 * 지금은 세션 메모리에만 있다 — 재시작하면 기본값으로 돌아간다.
 * 디스크 저장(config/ 로컬 JSON, 10장)은 다음 단계다.
 */
class OverlayPanel(
    val tab: Tab?,
    var x: Int,
    var y: Int,
    var width: Int,
    var height: Int,
    /** 아래로 몇 줄 스크롤했는지. 0 이면 최신 줄이 바닥에 붙어 있다. 편집 화면에서만 쓴다. */
    var scrollOffset: Int = 0,
)

object ChatOverlayState {

    val main: OverlayPanel = OverlayPanel(tab = null, x = 4, y = 4, width = 300, height = 90)
    val panels: MutableList<OverlayPanel> = mutableListOf(main)

    /** 메인 패널에서 지금 보고 있는 탭. 화면을 닫았다 열어도 유지된다 — [kr.teamcraft.crchat.client.ui.CRChatScreen] 은 이 값을 직접 읽고 쓴다. */
    var activeTab: Tab = Tab.ALL

    private var positioned = false

    /**
     * 화면 크기를 아직 모르는 시점(오브젝트 초기화)엔 기본 위치·크기를 못 잡으므로,
     * 처음 렌더될 때 한 번만 맞춘다. 크기를 화면 크기에 **비례**해서 잡는 이유는 —
     * 고정 픽셀값(예: 300x90)을 쓰면 GUI Scale 이 낮아 논리 좌표계가 작을 때 패널이
     * 화면을 거의 다 덮어버리고, 높을 때는 반대로 너무 작아 보이기 때문이다.
     */
    fun ensureDefaultPosition(screenWidth: Int, screenHeight: Int) {
        if (positioned) return
        main.width = (screenWidth * 0.32).toInt().coerceIn(180, 420)
        main.height = (screenHeight * 0.22).toInt().coerceIn(60, 160)
        main.y = (screenHeight - main.height - 34).coerceAtLeast(4)
        positioned = true
    }

    /**
     * 매 프레임 불러서 모든 패널을 지금 화면 크기 안으로 다시 밀어 넣는다. GUI Scale 을
     * 바꾸거나 창 크기를 바꾸면 논리 좌표계 크기 자체가 바뀌는데, 위치·크기는 최초 1회만
     * 잡고 그 뒤로 다시 보정하지 않았다 — 그래서 예전에 큰 화면 기준으로 잡아둔 좌표가
     * 작아진 새 화면보다 커서 패널이 화면 밖으로 나가거나 채팅이 잘려 안 보이는 문제가
     * 있었다. 여기서 매 프레임 잘라주면 언제 화면이 바뀌어도 항상 화면 안에 있다.
     */
    fun clampToScreen(screenWidth: Int, screenHeight: Int) {
        if (screenWidth <= 0 || screenHeight <= 0) return
        for (panel in panels) {
            panel.width = panel.width.coerceIn(1, screenWidth)
            panel.height = panel.height.coerceIn(1, screenHeight)
            panel.x = panel.x.coerceIn(0, screenWidth - panel.width)
            panel.y = panel.y.coerceIn(0, screenHeight - panel.height)
        }
    }

    /** 탭 하나를 별도 창으로 뗀다. 이미 떼어져 있으면 무시한다. */
    fun detach(tab: Tab) {
        if (panels.any { it.tab == tab }) return
        val offset = (panels.size - 1) * 20
        panels += OverlayPanel(
            tab = tab,
            x = main.x + main.width + 12 + offset,
            y = main.y + offset,
            width = 220,
            height = 70,
        )
    }
}
