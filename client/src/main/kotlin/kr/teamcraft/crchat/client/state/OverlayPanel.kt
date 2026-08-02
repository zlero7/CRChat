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

    private var positioned = false

    /** 화면 크기를 아직 모르는 시점(오브젝트 초기화)엔 기본 위치를 못 잡으므로, 처음 렌더될 때 한 번만 맞춘다. */
    fun ensureDefaultPosition(screenHeight: Int) {
        if (positioned) return
        main.y = (screenHeight - main.height - 34).coerceAtLeast(4)
        positioned = true
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
