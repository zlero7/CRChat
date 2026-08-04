package kr.teamcraft.crchat.client.ui

import kr.teamcraft.crchat.client.state.ChatAppearanceState
import net.minecraft.client.font.TextRenderer
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text

/**
 * 채팅 패널 배경색 선택 팝업. 참고 이미지의 "말풍선 설정" 색상 선택기 스타일을
 * 그대로 따라간다 — 채도·명도 2D 그라데이션 사각형 + 색상(hue) 막대 + 헥스 코드 +
 * 미리보기 + 켜기/끄기 토글.
 *
 * [CommandSuggestions] 처럼 화면에 떠 있는 독립 팝업이다 — 설정 버튼을 누르면 열리고,
 * 팝업 바깥을 클릭하면 닫힌다. 색은 SV 사각형·색상 막대를 드래그하는 동안 실시간으로
 * [ChatAppearanceState] 에 반영되어 패널 배경에 바로 보인다.
 */
class ChatBackgroundPicker {

    var open = false
        private set

    private var x = 0
    private var y = 0
    private val width = 150
    private val svSize = 100
    private val hueBarHeight = 8
    private val gap = 6
    private val svStep = 2

    private var draggingSv = false
    private var draggingHue = false

    private val svY get() = y + 14
    private val hueY get() = svY + svSize + gap
    private val toggleY get() = hueY + hueBarHeight + gap + 12
    private val bottom get() = toggleY + 10

    fun toggle(anchorX: Int, anchorY: Int) {
        open = !open
        if (open) {
            // 팝업이 화면 위로 나가지 않게, 앵커 아래로 못 그리면 위로 펼친다.
            x = anchorX
            y = anchorY
        } else {
            draggingSv = false
            draggingHue = false
        }
    }

    fun close() {
        open = false
        draggingSv = false
        draggingHue = false
    }

    fun render(context: DrawContext, textRenderer: TextRenderer) {
        if (!open) return

        context.fill(x - 6, y - 6, x + width + 6, y + bottom + 6, 0xF0101018.toInt())
        context.fill(x - 6, y - 6, x + width + 6, y - 5, 0x77FFFFFF.toInt())
        context.fill(x - 6, y + bottom + 5, x + width + 6, y + bottom + 6, 0x77FFFFFF.toInt())
        context.fill(x - 6, y - 6, x - 5, y + bottom + 6, 0x77FFFFFF.toInt())
        context.fill(x + width + 5, y - 6, x + width + 6, y + bottom + 6, 0x77FFFFFF.toInt())

        TextRenderUtil.drawScaled(context, textRenderer, Text.literal("배경색"), x, y, 0xFFFFFF)

        // 채도(가로)·명도(세로) 2D 그라데이션 — 지금 고른 색상(hue) 기준.
        var py = 0
        while (py < svSize) {
            val v = 1f - py / svSize.toFloat()
            var px = 0
            while (px < svSize) {
                val sat = px / svSize.toFloat()
                val rgb = ChatAppearanceState.hsvToRgb(ChatAppearanceState.hue, sat, v)
                context.fill(x + px, svY + py, x + px + svStep, svY + py + svStep, (0xFF shl 24) or rgb)
                px += svStep
            }
            py += svStep
        }
        val markX = x + (ChatAppearanceState.saturation * svSize).toInt()
        val markY = svY + ((1f - ChatAppearanceState.value) * svSize).toInt()
        context.fill(markX - 2, markY - 2, markX + 2, markY + 2, 0xFF000000.toInt())
        context.fill(markX - 1, markY - 1, markX + 1, markY + 1, 0xFFFFFFFF.toInt())

        // 색상(hue) 막대 — 왼쪽 0도부터 오른쪽 360도까지.
        for (px in 0 until svSize) {
            val h = px / svSize.toFloat() * 360f
            val rgb = ChatAppearanceState.hsvToRgb(h, 1f, 1f)
            context.fill(x + px, hueY, x + px + 1, hueY + hueBarHeight, (0xFF shl 24) or rgb)
        }
        val hueMarkX = x + (ChatAppearanceState.hue / 360f * svSize).toInt()
        context.fill(hueMarkX - 1, hueY - 1, hueMarkX + 1, hueY + hueBarHeight + 1, 0xFFFFFFFF.toInt())

        // 헥스 코드 + 미리보기.
        val previewY = hueY + hueBarHeight + gap
        TextRenderUtil.drawScaled(context, textRenderer, Text.literal(ChatAppearanceState.hexString()), x, previewY, 0xCCCCCC)
        context.fill(x + width - 26, previewY, x + width, previewY + 9, (0xFF shl 24) or ChatAppearanceState.rgb())
        context.fill(x + width - 26, previewY, x + width, previewY + 1, 0x77FFFFFF.toInt())

        // 켜기/끄기 토글.
        val toggleLabel = if (ChatAppearanceState.customBackgroundEnabled) "커스텀 배경  ▣ 켜짐" else "커스텀 배경  ▢ 꺼짐"
        val toggleColor = if (ChatAppearanceState.customBackgroundEnabled) 0x88FF88 else 0xAAAAAA
        TextRenderUtil.drawScaled(context, textRenderer, Text.literal(toggleLabel), x, toggleY, toggleColor)
    }

    /** @return 이 클릭을 팝업이 소비했으면 true — 소비했으면 [CRChatScreen] 은 다른 처리로 넘어가면 안 된다. */
    fun mouseClicked(mouseX: Double, mouseY: Double): Boolean {
        if (!open) return false

        if (mouseX < x - 6 || mouseX > x + width + 6 || mouseY < y - 6 || mouseY > y + bottom + 6) {
            close()
            return true // 팝업 밖 클릭 — 닫기만 하고, 뒤 UI 클릭으로는 안 넘긴다.
        }

        if (mouseX in x.toDouble()..(x + svSize).toDouble() && mouseY in svY.toDouble()..(svY + svSize).toDouble()) {
            draggingSv = true
            applySv(mouseX, mouseY)
            return true
        }
        if (mouseX in (x - 2).toDouble()..(x + svSize + 2).toDouble() && mouseY in (hueY - 2).toDouble()..(hueY + hueBarHeight + 2).toDouble()) {
            draggingHue = true
            applyHue(mouseX)
            return true
        }
        if (mouseY in toggleY.toDouble()..(toggleY + 10.0)) {
            ChatAppearanceState.customBackgroundEnabled = !ChatAppearanceState.customBackgroundEnabled
            return true
        }
        return true
    }

    fun mouseDragged(mouseX: Double, mouseY: Double): Boolean {
        if (!open) return false
        if (draggingSv) {
            applySv(mouseX, mouseY)
            return true
        }
        if (draggingHue) {
            applyHue(mouseX)
            return true
        }
        return false
    }

    fun mouseReleased() {
        draggingSv = false
        draggingHue = false
    }

    private fun applySv(mouseX: Double, mouseY: Double) {
        ChatAppearanceState.saturation = ((mouseX - x) / svSize).toFloat()
        ChatAppearanceState.value = (1f - ((mouseY - svY) / svSize)).toFloat()
    }

    private fun applyHue(mouseX: Double) {
        ChatAppearanceState.hue = ((mouseX - x) / svSize * 360).toFloat()
    }
}
