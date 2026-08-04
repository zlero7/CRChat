package kr.teamcraft.crchat.client.ui

import kr.teamcraft.crchat.client.state.NoticeBannerState
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text

/**
 * `/공지` 를 화면 가로 폭 전체의 어두운 바 위에서, 오른쪽에서 들어와 왼쪽으로 흘러
 * 나가는 배너로 보여준다 (사용자가 준 참고 이미지 스타일). 채팅 패널·오버레이와는
 * 완전히 독립된 HUD 요소라 — 채팅창을 열어놨든 닫아놨든 상관없이 그린다.
 *
 * **한 바퀴만** 지나가면 사라진다 — 반복 재생하지 않는다. 화면 오른쪽 밖에서
 * 시작해 텍스트 전체가 왼쪽 밖으로 나갈 때까지의 거리([totalTravel])를 [SCROLL_SPEED_PX_PER_SEC]
 * 로 나눈 시간이 지나면 [NoticeBannerState.clear] 를 불러 스스로 끝낸다.
 * 시작·끝 [FADE_MS] 구간은 알파값을 선형으로 줄여 서서히 나타나고 사라지게 한다.
 */
object NoticeBannerRenderer {

    private const val BAR_HEIGHT = 28
    private const val SCROLL_SPEED_PX_PER_SEC = 50.0
    private const val TEXT_SCALE = 1.5f
    private const val FADE_MS = 600L

    fun render(context: DrawContext) {
        val text = NoticeBannerState.text ?: return

        val client = MinecraftClient.getInstance()
        val textRenderer = client.textRenderer
        val screenWidth = client.window.scaledWidth
        // 화면 위쪽 1/4 지점 — 크로스헤어·핫바와 안 겹치고, 참고 이미지와 비슷한 위치.
        val barY = (client.window.scaledHeight * 0.22).toInt()

        val textWidth = (textRenderer.getWidth(text) * TEXT_SCALE).toInt()
        val totalTravel = (screenWidth + textWidth).toDouble()
        val elapsedMs = NoticeBannerState.elapsedMs
        val progress = elapsedMs / 1000.0 * SCROLL_SPEED_PX_PER_SEC

        if (progress >= totalTravel) {
            NoticeBannerState.clear()
            return
        }

        val totalDurationMs = (totalTravel / SCROLL_SPEED_PX_PER_SEC * 1000).toLong()
        val remainMs = totalDurationMs - elapsedMs
        val fadeAlpha = when {
            elapsedMs < FADE_MS -> elapsedMs / FADE_MS.toFloat()
            remainMs < FADE_MS -> remainMs / FADE_MS.toFloat()
            else -> 1f
        }.coerceIn(0f, 1f)
        val a = (fadeAlpha * 255).toInt().coerceIn(0, 255)

        val barBg = (a * 0xB0 / 255 shl 24) or 0x000000
        val barBorder = (a * 0x66 / 255 shl 24) or 0xFFFFFF
        val textColor = (a shl 24) or 0xFFFFFF

        context.fill(0, barY, screenWidth, barY + BAR_HEIGHT, barBg)
        context.fill(0, barY, screenWidth, barY + 1, barBorder)
        context.fill(0, barY + BAR_HEIGHT - 1, screenWidth, barY + BAR_HEIGHT, barBorder)

        val x = (screenWidth - progress).toInt()
        val y = barY + (BAR_HEIGHT - (textRenderer.fontHeight * TEXT_SCALE).toInt()) / 2

        context.enableScissor(0, barY, screenWidth, barY + BAR_HEIGHT)
        TextRenderUtil.drawScaled(context, textRenderer, Text.literal(text), x, y, textColor, TEXT_SCALE)
        context.disableScissor()
    }
}
