package kr.teamcraft.crchat.client.ui

import net.minecraft.client.MinecraftClient
import net.minecraft.client.font.TextRenderer
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.OrderedText
import net.minecraft.text.Text

/** 채팅 UI 전체가 공유하는 글자 크기. 바닐라 기본 폰트는 크기 조절 API가 없어서 매트릭스로 직접 줄인다. */
object ChatUiScale {
    /** GUI Scale 이 충분히 높을 때(실제 픽셀이 넉넉할 때) 쓰고 싶은 값. */
    private const val PREFERRED_TEXT_SCALE = 0.8f

    /**
     * 실제 화면 픽셀 기준 최소 배율. GUI Scale(논리 단위당 실제 픽셀 수) × 우리 배율이
     * 이 값보다 작아지면, 바닐라 비트맵 폰트를 그만큼 작게 그릴 실제 픽셀이 부족해서
     * 글자 일부가 깨져 보인다(실제로 GUI Scale 2 에서 탭 글자가 잘려 보이는 문제가
     * 있었다 — Auto(더 높은 배율)에서는 멀쩡했는데, 낮은 GUI Scale 로 바꾸니 논리
     * 단위당 실제 픽셀 수가 줄어서 우리 축소분(0.8배)까지 곱해지면 실제 렌더 해상도가
     * 너무 낮아졌던 것이다.
     */
    private const val MIN_REAL_PIXEL_SCALE = 1.8f

    /**
     * 지금 GUI Scale 에서 안전한 배율을 골라 쓴다 — GUI Scale 이 낮으면(논리 단위당
     * 실제 픽셀이 적으면) [PREFERRED_TEXT_SCALE] 대신 그보다 큰(=덜 줄이는) 값을 써서
     * 실제 픽셀 해상도가 깨지는 지점 밑으로 안 떨어지게 한다. 최대 1.0(바닐라 원본
     * 크기)까지만 올린다 — 그 이상은 오히려 바닐라보다 커져 버린다.
     */
    val TEXT_SCALE: Float
        get() {
            val guiScale = MinecraftClient.getInstance().window?.scaleFactor?.toFloat() ?: 1f
            val minSafeScale = (MIN_REAL_PIXEL_SCALE / guiScale).coerceAtMost(1f)
            return PREFERRED_TEXT_SCALE.coerceAtLeast(minSafeScale)
        }

    const val LINE_HEIGHT = 8
    const val TAB_BAR_HEIGHT = 13

    /** 좌측 여백(4) + 스크롤바·우측 여백 몫. 자동 줄넘김 폭 계산에 쓴다. */
    private const val LINE_PADDING = 10

    /**
     * 패널 폭(스케일 적용 전, 실제 화면 픽셀)을 자동 줄넘김에 넘길 텍스트 폭으로 바꾼다.
     * 렌더는 [TEXT_SCALE] 만큼 축소해서 그리므로, 줄바꿈 판정은 축소 전 좌표계에서
     * 해야 한다 — 그대로 패널 폭을 넘기면 실제보다 훨씬 짧은 폭에서 줄바꿈돼 버린다.
     */
    fun wrapWidth(panelWidth: Int): Int =
        ((panelWidth - LINE_PADDING) / TEXT_SCALE).toInt().coerceAtLeast(1)
}

object TextRenderUtil {

    fun drawScaled(
        context: DrawContext,
        textRenderer: TextRenderer,
        text: Text,
        x: Int,
        y: Int,
        color: Int,
        scale: Float = ChatUiScale.TEXT_SCALE,
    ) {
        context.matrices.push()
        context.matrices.translate(x.toFloat(), y.toFloat(), 0f)
        context.matrices.scale(scale, scale, 1f)
        context.drawTextWithShadow(textRenderer, text, 0, 0, color)
        context.matrices.pop()
    }

    /** 자동 줄넘김([ChatLineFormatter.wrappedLinesFor])이 만든 줄을 그릴 때 쓴다. */
    fun drawScaled(
        context: DrawContext,
        textRenderer: TextRenderer,
        text: OrderedText,
        x: Int,
        y: Int,
        color: Int,
        scale: Float = ChatUiScale.TEXT_SCALE,
    ) {
        context.matrices.push()
        context.matrices.translate(x.toFloat(), y.toFloat(), 0f)
        context.matrices.scale(scale, scale, 1f)
        context.drawTextWithShadow(textRenderer, text, 0, 0, color)
        context.matrices.pop()
    }

    fun scaledWidth(textRenderer: TextRenderer, text: String, scale: Float = ChatUiScale.TEXT_SCALE): Int =
        (textRenderer.getWidth(text) * scale).toInt()
}
