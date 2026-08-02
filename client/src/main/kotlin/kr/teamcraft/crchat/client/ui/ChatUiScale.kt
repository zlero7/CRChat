package kr.teamcraft.crchat.client.ui

import net.minecraft.client.font.TextRenderer
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text

/** 채팅 UI 전체가 공유하는 글자 크기. 바닐라 기본 폰트는 크기 조절 API가 없어서 매트릭스로 직접 줄인다. */
object ChatUiScale {
    const val TEXT_SCALE = 0.8f
    const val LINE_HEIGHT = 8
    const val TAB_BAR_HEIGHT = 13
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

    fun scaledWidth(textRenderer: TextRenderer, text: String, scale: Float = ChatUiScale.TEXT_SCALE): Int =
        (textRenderer.getWidth(text) * scale).toInt()
}
