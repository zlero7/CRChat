package kr.teamcraft.crchat.client.ui

import net.minecraft.client.font.TextRenderer
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.OrderedText
import net.minecraft.text.Text

/** 채팅 UI 전체가 공유하는 글자 크기. 바닐라 기본 폰트는 크기 조절 API가 없어서 매트릭스로 직접 줄인다. */
object ChatUiScale {
    const val TEXT_SCALE = 0.8f
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
