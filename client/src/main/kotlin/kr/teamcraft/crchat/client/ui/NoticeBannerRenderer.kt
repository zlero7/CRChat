package kr.teamcraft.crchat.client.ui

import kr.teamcraft.crchat.client.state.NoticeBannerState
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text

/**
 * `/공지` 를 화면 가로 폭 전체의 어두운 바 위에서, 오른쪽에서 들어와 왼쪽으로 흘러
 * 나가는 배너로 보여준다 (사용자가 준 참고 이미지 스타일). 채팅 패널·오버레이와는
 * 완전히 독립된 HUD 요소라 — 채팅창을 열어놨든 닫아놨든 상관없이 그린다.
 */
object NoticeBannerRenderer {

    private const val BAR_HEIGHT = 28
    private const val SCROLL_SPEED_PX_PER_SEC = 120.0
    private const val TEXT_SCALE = 1.5f

    fun render(context: DrawContext) {
        if (!NoticeBannerState.isActive()) return
        val text = NoticeBannerState.text ?: return

        val client = MinecraftClient.getInstance()
        val textRenderer = client.textRenderer
        val screenWidth = client.window.scaledWidth
        // 화면 위쪽 1/4 지점 — 크로스헤어·핫바와 안 겹치고, 참고 이미지와 비슷한 위치.
        val barY = (client.window.scaledHeight * 0.22).toInt()

        context.fill(0, barY, screenWidth, barY + BAR_HEIGHT, 0xB0000000.toInt())
        context.fill(0, barY, screenWidth, barY + 1, 0x66FFFFFF.toInt())
        context.fill(0, barY + BAR_HEIGHT - 1, screenWidth, barY + BAR_HEIGHT, 0x66FFFFFF.toInt())

        // 오른쪽 화면 밖에서 시작해 왼쪽 화면 밖으로 완전히 나갈 때까지를 한 바퀴로 삼고,
        // 그 구간 길이로 나머지 연산을 해서 계속 반복되게 한다.
        val textWidth = (textRenderer.getWidth(text) * TEXT_SCALE).toInt()
        val totalTravel = (screenWidth + textWidth).toDouble()
        val progress = (NoticeBannerState.elapsedMs / 1000.0 * SCROLL_SPEED_PX_PER_SEC) % totalTravel
        val x = (screenWidth - progress).toInt()
        val y = barY + (BAR_HEIGHT - (textRenderer.fontHeight * TEXT_SCALE).toInt()) / 2

        context.enableScissor(0, barY, screenWidth, barY + BAR_HEIGHT)
        TextRenderUtil.drawScaled(context, textRenderer, Text.literal(text), x, y, 0xFFFFFF, TEXT_SCALE)
        context.disableScissor()
    }
}
