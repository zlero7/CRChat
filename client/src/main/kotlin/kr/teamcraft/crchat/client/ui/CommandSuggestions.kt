package kr.teamcraft.crchat.client.ui

import com.mojang.brigadier.suggestion.Suggestion
import net.minecraft.client.MinecraftClient
import net.minecraft.client.font.TextRenderer
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text
import java.util.concurrent.CompletableFuture

/**
 * "/" 로 시작하는 명령어를 칠 때 자동완성 목록을 보여준다. 클라가 서버 접속 시
 * 받는 브리가디어 명령 트리(권한으로 이미 필터링됨)를 그대로 물어보는 방식이라,
 * 설치된 플러그인 명령어까지 우리가 따로 등록할 필요 없이 전부 나온다.
 *
 * 처음엔 바닐라 `ChatInputSuggestor` 를 재사용했는데, 그건 화면 하단에 바닐라
 * 기본 폰트 크기로 뜨도록 좌표가 하드코딩돼 있어서 — 어디로든 옮기고 크기도
 * 줄일 수 있는 우리 채팅 입력창 바로 위, 작은 스케일로 띄우는 게 불가능했다.
 * 그래서 데이터(후보 목록)만 브리가디어에서 직접 받아오고, 그리기·위치는 우리
 * 입력창 스타일 그대로 직접 그린다.
 */
class CommandSuggestions(private val textRenderer: TextRenderer) {

    private var future: CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>? = null
    private var matches: List<Suggestion> = emptyList()
    private var selected = 0
    private var windowStart = 0
    private var lastText: String? = null
    private var closed = false

    /** 렌더된 각 줄의 화면 좌표 — 마우스 클릭 판정에 쓴다. */
    private var lastBox: Rect? = null
    private data class Rect(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val rowHeight: Int, val rowCount: Int)

    val isOpen: Boolean get() = !closed && matches.isNotEmpty()

    /** 입력 내용이 바뀔 때마다 부른다. "/" 로 시작하지 않으면 조용히 닫는다. */
    fun update(text: String) {
        if (text == lastText) return
        lastText = text
        closed = false
        selected = 0
        windowStart = 0

        if (!text.startsWith("/")) {
            future = null
            matches = emptyList()
            return
        }

        val handler = MinecraftClient.getInstance().player?.networkHandler
        if (handler == null) {
            matches = emptyList()
            return
        }

        val withoutSlash = text.substring(1)
        val parse = handler.commandDispatcher.parse(withoutSlash, handler.commandSource)
        val f = handler.commandDispatcher.getCompletionSuggestions(parse, withoutSlash.length)
        future = f
        f.thenRun {
            // 응답이 늦게 와서 그 사이 텍스트가 또 바뀌었으면(더 최신 future 가 있으면) 버린다.
            if (future === f) {
                matches = f.join().list.sortedBy { it.text }
                selected = 0
                windowStart = 0
            }
        }
    }

    /** Tab/위/아래로 선택을 옮긴다. 화면에 보이는 창(windowStart 기준)도 따라 스크롤한다. */
    fun cycle(forward: Boolean) {
        if (matches.isEmpty()) return
        selected = ((selected + if (forward) 1 else -1) + matches.size) % matches.size
        if (selected < windowStart) windowStart = selected
        if (selected >= windowStart + MAX_ROWS) windowStart = selected - MAX_ROWS + 1
    }

    /**
     * 지금 선택된 후보를 `currentText`(맨 앞 "/" 포함)에 적용한 결과를 돌려준다.
     * 반환값을 그대로 `input.text` 에 대입하면 된다. 이 적용으로 바뀐 텍스트는
     * [lastText] 로 미리 기록해 두므로 — 다음 프레임 [update] 가 같은 텍스트를 보고
     * 그냥 넘어가서 방금 받은 목록(및 선택 위치)이 안 날아가고 계속 Tab 으로 순환된다.
     */
    fun applySelected(currentText: String): String? {
        if (matches.isEmpty() || !currentText.startsWith("/")) return null
        val suggestion = matches[selected]
        val applied = "/" + suggestion.apply(currentText.substring(1))
        lastText = applied
        return applied
    }

    fun close() {
        closed = true
    }

    fun mouseClicked(mouseX: Double, mouseY: Double, currentText: String, onApply: (String) -> Unit): Boolean {
        val box = lastBox ?: return false
        if (mouseX < box.x1 || mouseX > box.x2 || mouseY < box.y1 || mouseY > box.y2) return false
        val row = ((mouseY - box.y1) / box.rowHeight).toInt()
        if (row in 0 until box.rowCount) {
            selected = (windowStart + row).coerceIn(0, matches.lastIndex)
            applySelected(currentText)?.let(onApply)
        }
        return true
    }

    /** 입력창 바로 위, 아래쪽 끝이 `inputY - 2` 에 오도록 위로 펼쳐 그린다. */
    fun render(context: DrawContext, inputX: Int, inputY: Int, inputWidth: Int) {
        if (!isOpen) return
        val rowHeight = ChatUiScale.LINE_HEIGHT + 2
        val visibleCount = matches.size.coerceAtMost(MAX_ROWS)
        val boxHeight = visibleCount * rowHeight + 4
        val boxY = inputY - boxHeight - 2
        val boxX = inputX
        val boxWidth = inputWidth

        context.fill(boxX, boxY, boxX + boxWidth, boxY + boxHeight, 0xEE101018.toInt())
        context.fill(boxX, boxY, boxX + boxWidth, boxY + 1, 0x77FFFFFF.toInt())
        context.fill(boxX, boxY + boxHeight - 1, boxX + boxWidth, boxY + boxHeight, 0x77FFFFFF.toInt())
        context.fill(boxX, boxY, boxX + 1, boxY + boxHeight, 0x77FFFFFF.toInt())
        context.fill(boxX + boxWidth - 1, boxY, boxX + boxWidth, boxY + boxHeight, 0x77FFFFFF.toInt())

        context.enableScissor(boxX, boxY, boxX + boxWidth, boxY + boxHeight)
        var y = boxY + 2
        for (i in 0 until visibleCount) {
            val suggestion = matches[windowStart + i]
            val isSelected = (windowStart + i) == selected
            if (isSelected) {
                context.fill(boxX + 1, y - 1, boxX + boxWidth - 1, y - 1 + rowHeight, 0x40FFFFFF)
            }
            val color = if (isSelected) 0xFFFF55 else 0xCCCCCC
            TextRenderUtil.drawScaled(context, textRenderer, Text.literal(suggestion.text), boxX + 4, y, color)
            y += rowHeight
        }
        context.disableScissor()

        lastBox = Rect(boxX, boxY, boxX + boxWidth, boxY + boxHeight, rowHeight, visibleCount)
    }

    companion object {
        private const val MAX_ROWS = 8
    }
}
