package kr.teamcraft.crchat.client.ui

import kr.teamcraft.crchat.client.state.ChatOverlayState
import kr.teamcraft.crchat.common.hangul.Dubeolsik
import kr.teamcraft.crchat.common.hangul.HangulComposer
import net.minecraft.client.MinecraftClient
import net.minecraft.client.font.TextHandler
import net.minecraft.client.font.TextRenderer
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Style
import net.minecraft.text.Text
import org.lwjgl.glfw.GLFW

/**
 * 자체 두벌식 조합기를 쓰는 채팅 입력창. (기획서 부록 A)
 *
 * 바닐라 `TextFieldWidget` 을 안 쓰는 이유는 OS IME 에 기대지 않고 우리가 직접
 * 조합하기 위해서다 — `charTyped` 가 아니라 `keyPressed` 의 GLFW 키 코드로
 * [Dubeolsik] 매핑을 거쳐 [HangulComposer] 에 넣는다 (A.3).
 *
 * `ClickableWidget` 을 상속하지 않는다 — 이 화면엔 타이핑 대상이 이거 하나뿐이라
 * 포커스·선택 시스템이 필요 없고, [kr.teamcraft.crchat.client.ui.CRChatScreen] 이
 * 키·문자·클릭 이벤트를 직접 여기로 넘겨준다.
 *
 * 커서·선택은 항상 [buffer](확정된 텍스트) 기준으로만 움직인다 — 조합기는 "지금
 * 커서 위치에서 계속 이어 친다"는 것만 알 뿐 커서가 다른 곳으로 튀는 상황은 모른다.
 * 그래서 커서를 옮기는 동작(화살표·Home/End·Ctrl+A·클릭 등)은 전부 먼저 [flush] 로
 * 조합 중이던 글자를 확정한다 — 이게 없으면 커서를 옮긴 뒤에도 엉뚱한 자리에서
 * 조합이 이어져 글자가 깨진다.
 *
 * 한/영 전환 키는 **우측 Alt 뿐**이다(우측 Ctrl 은 뺐다 — Alt 하나로 충분하고, 다른
 * Ctrl 조합(Ctrl+A 등)과 헷갈리지 않는 편이 낫다).
 * - 실제 물리 한/영 키는 일부러 안 잡는다 — 그 키는 Windows 자체의 IME 를 통째로
 *   전환하는 시스템 키라, 눌리는 순간부터 타이핑이 OS 한글 입력기를 거쳐 넘어오고
 *   우리 조합기는 그걸 이중 입력으로 보고 버린다(A.9) — "OS 전환은 되는데 채팅이
 *   안 쳐지는" 증상의 원인이었다.
 * - 우측 Alt 는 한동안 포기했었다 — 로그로 확인해보니 `keyCode=346` 이 게임에 아예
 *   안 들어왔기 때문이다(Windows 가 언어 전환 단축키로 먼저 가로챔). KoreanPatch
 *   모드 분석으로 원인을 다시 찾았다 — [kr.teamcraft.crchat.client.win.ImeDetacher]
 *   가 게임 창에서 OS IME 연결 자체를 떼어내면(`ImmAssociateContext(hwnd, NULL)`)
 *   Windows 가 더 이상 그 키를 언어 전환용으로 안 가로채서, keyCode 가 정상적으로
 *   게임까지 들어온다.
 */
class CRChatInputField(
    private val textRenderer: TextRenderer,
    var x: Int,
    var y: Int,
    var width: Int,
    /** 한 줄일 때의 최소 높이. 자동 줄넘김으로 줄이 늘어나면 [renderHeight] 가 이보다 커진다. */
    private val minHeight: Int,
) {
    private val composer = HangulComposer()
    private val buffer = StringBuilder()

    /** 확정된 텍스트([buffer]) 안에서의 커서 위치. */
    private var cursor = 0

    /** 선택 시작점. [cursor] 와 같으면 선택 없음. Shift 없이 커서를 옮기면 여기도 같이 따라간다. */
    private var selectionAnchor = 0

    /**
     * 한/영 전환 상태. [ChatOverlayState] 에 위임한다 — 이 클래스는 채팅창을 열 때마다
     * 새로 만들어지는 인스턴스라, 여기 그냥 필드로 두면 닫았다 열 때마다 항상 한글로
     * 리셋됐다(실제로 이 문제가 있었다). [ChatOverlayState.activeTab] 과 같은 이유로
     * 화면 생명주기보다 오래 살아야 하는 상태다.
     */
    private var hangulMode: Boolean
        get() = ChatOverlayState.hangulMode
        set(value) { ChatOverlayState.hangulMode = value }

    /** keyPressed 에서 자모로 이미 처리한 키가 곧이어 들어올 charTyped 를 오염시키지 않게 막는 플래그. */
    private var suppressNextChar = false

    private val maxLength = 256

    /**
     * Shift/Ctrl/Alt 등 순수 보조키 자체도 하나의 keyCode 이벤트로 들어온다.
     * "Shift+R" 은 물리적으로 Shift keydown -> R keydown 순서로 두 번 온다 —
     * 이걸 "자모가 아닌 키"로 취급해 flush() 해버리면 Shift 를 누르는 순간
     * 조합 중이던 글자가 조로 확정돼 버려서, 뒤이은 R 이 새 글자로 시작돼
     * "ㄲ" 을 받침으로 못 붙인다. 실제로 이 버그가 있었다 — 보조키는 조용히 흘려보낸다.
     */
    private val MODIFIER_KEYS = setOf(
        GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT,
        GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL,
        GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT,
        GLFW.GLFW_KEY_LEFT_SUPER, GLFW.GLFW_KEY_RIGHT_SUPER,
        GLFW.GLFW_KEY_CAPS_LOCK,
    )

    private val hasSelection: Boolean get() = selectionAnchor != cursor
    private val selectionStart: Int get() = minOf(selectionAnchor, cursor)
    private val selectionEnd: Int get() = maxOf(selectionAnchor, cursor)

    /** 조합 중인 글자까지 포함한, 커서 위치에 조합 중인 글자가 끼워진 현재 표시 텍스트. */
    var text: String
        get() {
            val committed = buffer.toString()
            return committed.substring(0, cursor) + composer.preeditText() + committed.substring(cursor)
        }
        set(value) {
            composer.reset()
            buffer.setLength(0)
            buffer.append(value.take(maxLength))
            cursor = buffer.length
            selectionAnchor = cursor
        }

    /** Enter 로 전송하기 직전에 불러서 조합 중이던 글자까지 확정한다. */
    fun flush() {
        composer.flush()?.let { insertAtCursor(it) }
    }

    fun clear() {
        buffer.setLength(0)
        cursor = 0
        selectionAnchor = 0
        composer.reset()
    }

    private fun toggleHangulMode() {
        flush()
        hangulMode = !hangulMode
    }

    private fun insertAtCursor(s: String) {
        if (buffer.length >= maxLength) return
        val insertable = s.take(maxLength - buffer.length)
        buffer.insert(cursor, insertable)
        cursor += insertable.length
        selectionAnchor = cursor
    }

    private fun deleteSelection() {
        if (!hasSelection) return
        buffer.delete(selectionStart, selectionEnd)
        cursor = selectionStart
        selectionAnchor = cursor
    }

    private fun moveCursorTo(index: Int, extendSelection: Boolean) {
        cursor = index.coerceIn(0, buffer.length)
        if (!extendSelection) selectionAnchor = cursor
    }

    private fun copySelection() {
        if (!hasSelection) return
        val window = MinecraftClient.getInstance().window ?: return
        GLFW.glfwSetClipboardString(window.handle, buffer.substring(selectionStart, selectionEnd))
    }

    private fun cutSelection() {
        if (!hasSelection) return
        copySelection()
        deleteSelection()
    }

    private fun pasteFromClipboard() {
        val window = MinecraftClient.getInstance().window ?: return
        val clip = GLFW.glfwGetClipboardString(window.handle) ?: return
        // 한 줄짜리 입력창이라 개행은 공백으로 눌러 담는다.
        val sanitized = clip.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')
        flush()
        if (hasSelection) deleteSelection()
        insertAtCursor(sanitized)
    }

    // ── 자동 줄넘김 + 오프셋 매핑 ──────────────────────────

    /** 한/영 표시(17px)+구분선 뒤, 오른쪽 여백을 뺀 실제 텍스트 시작 x. */
    private val textLeftMargin = 21
    private val textRightMargin = 4

    private fun wrapWidth(): Int =
        ChatUiScale.wrapWidth((width - textLeftMargin - textRightMargin).coerceAtLeast(20))

    /** 줄 하나가 [display] 안에서 차지하는 [start, end) 구간. 커서·선택 좌표 계산에 쓴다. */
    private data class LineRange(val start: Int, val end: Int)

    /**
     * [display] 를 자동 줄넘김한 각 줄의 원본 오프셋. 메시지 영역과 같은 폭 계산으로
     * 줄이 나뉘지만, 이쪽은 원본 문자열 안에서의 [start, end) 구간까지 알아야 커서
     * 깜빡임·선택 하이라이트를 정확한 줄·칸에 그릴 수 있다 — 그래서 문자열만 돌려주는
     * 상위 레벨 `wrapLines` 대신 오프셋을 그대로 주는 하위 레벨 오버로드를 쓴다.
     */
    private fun wrappedLineRanges(display: String): List<LineRange> {
        val ranges = mutableListOf<LineRange>()
        textRenderer.textHandler.wrapLines(display, wrapWidth(), Style.EMPTY, false) { _, start, end ->
            ranges += LineRange(start, end)
        }
        if (ranges.isEmpty()) ranges += LineRange(0, 0)
        return ranges
    }

    /** [idx](display 문자열 안의 절대 위치)가 몇 번째 줄, 그 줄의 몇 번째 칸인지. */
    private fun locate(ranges: List<LineRange>, idx: Int): Pair<Int, Int> {
        for ((i, r) in ranges.withIndex()) {
            if (idx < r.end || i == ranges.lastIndex) {
                return i to (idx - r.start).coerceIn(0, r.end - r.start)
            }
        }
        return 0 to 0
    }

    /** 줄 수에 맞춰 늘어난 실제 높이. [CRChatScreen] 이 배경·다음 요소 배치에 참조한다. */
    val renderHeight: Int
        get() = (wrappedLineRanges(text).size * ChatUiScale.LINE_HEIGHT + 6).coerceAtLeast(minHeight)

    fun render(context: DrawContext, textColor: Int) {
        val display = text
        val ranges = wrappedLineRanges(display)
        val lines = ranges.map { display.substring(it.start, it.end) }
        val boxHeight = (ranges.size * ChatUiScale.LINE_HEIGHT + 6).coerceAtLeast(minHeight)

        // 배경 + 테두리 — 이게 없으면 게임 화면과 안 구분돼서 입력창처럼 안 보인다.
        context.fill(x, y, x + width, y + boxHeight, 0xEE101018.toInt())
        context.fill(x, y, x + width, y + 1, 0x77FFFFFF.toInt()) // 위
        context.fill(x, y + boxHeight - 1, x + width, y + boxHeight, 0x77FFFFFF.toInt()) // 아래
        context.fill(x, y, x + 1, y + boxHeight, 0x77FFFFFF.toInt()) // 왼쪽
        context.fill(x + width - 1, y, x + width, y + boxHeight, 0x77FFFFFF.toInt()) // 오른쪽

        val indicator = if (hangulMode) "한" else "EN"
        val indicatorX = x + 3
        TextRenderUtil.drawScaled(context, textRenderer, Text.literal(indicator), indicatorX, y + 3, 0x88FF88)

        // 한/영 표시와 실제 입력 사이 구분선 — 줄이 늘어난 만큼 세로로 같이 늘어난다.
        context.fill(x + 17, y + 2, x + 18, y + boxHeight - 2, 0x44FFFFFF.toInt())

        val textX = x + textLeftMargin
        val scale = ChatUiScale.TEXT_SCALE

        // 입력창을 가로로 좁게 줄였을 때도(자동 줄넘김이 못 끊는 긴 토큰 대비) 테두리
        // 밖으로 삐져나오지 않도록 한 번 더 잘라낸다.
        context.enableScissor(textX, y, x + width - 2, y + boxHeight)

        if (hasSelection) {
            drawSelectionHighlight(context, ranges, lines, textX, y, scale)
        }

        var lineY = y + 3
        for (line in lines) {
            TextRenderUtil.drawScaled(context, textRenderer, Text.literal(line), textX, lineY, textColor)
            lineY += ChatUiScale.LINE_HEIGHT
        }

        if (composer.isComposing) {
            drawComposingUnderline(context, ranges, lines, textX, y)
        } else if (!hasSelection && blinkOn()) {
            drawCaret(context, ranges, lines, textX, y, scale)
        }
        context.disableScissor()
    }

    /** 500ms 마다 깜빡인다. */
    private fun blinkOn(): Boolean = (System.currentTimeMillis() / 500) % 2L == 0L

    private fun drawCaret(context: DrawContext, ranges: List<LineRange>, lines: List<String>, textX: Int, boxY: Int, scale: Float) {
        // 조합 중이 아닐 땐 preedit 가 없으니 display 상의 커서 위치는 [cursor] 그대로다.
        val (lineIndex, column) = locate(ranges, cursor)
        val lineStr = lines.getOrElse(lineIndex) { "" }
        val prefixWidth = (textRenderer.getWidth(lineStr.substring(0, column.coerceIn(0, lineStr.length))) * scale).toInt()
        val caretX = textX + prefixWidth
        val caretY = boxY + 3 + lineIndex * ChatUiScale.LINE_HEIGHT
        context.fill(caretX, caretY, caretX + 1, caretY + ChatUiScale.LINE_HEIGHT - 1, 0xFFFFFFFF.toInt())
    }

    private fun drawSelectionHighlight(
        context: DrawContext,
        ranges: List<LineRange>,
        lines: List<String>,
        textX: Int,
        boxY: Int,
        scale: Float,
    ) {
        val selStart = selectionStart
        val selEnd = selectionEnd
        for ((i, range) in ranges.withIndex()) {
            val from = maxOf(range.start, selStart)
            val to = minOf(range.end, selEnd)
            if (from >= to) continue
            val lineStr = lines[i]
            val localFrom = (from - range.start).coerceIn(0, lineStr.length)
            val localTo = (to - range.start).coerceIn(0, lineStr.length)
            val x1 = textX + (textRenderer.getWidth(lineStr.substring(0, localFrom)) * scale).toInt()
            val x2 = textX + (textRenderer.getWidth(lineStr.substring(0, localTo)) * scale).toInt()
            val rowY = boxY + 3 + i * ChatUiScale.LINE_HEIGHT
            context.fill(x1, rowY - 1, x2, rowY + ChatUiScale.LINE_HEIGHT - 1, 0x663399FF)
        }
    }

    /**
     * 조합 중인 글자 밑에 밑줄을 긋는다 (A.8). 조합 중인 자모는 항상 커서 바로
     * 뒤(=preedit 시작 지점)에 끼워지므로, 그 줄·칸만 찾으면 된다.
     */
    private fun drawComposingUnderline(context: DrawContext, ranges: List<LineRange>, lines: List<String>, textX: Int, boxY: Int) {
        val preedit = composer.preeditText()
        val (lineIndex, column) = locate(ranges, cursor)
        val lineStr = lines.getOrElse(lineIndex) { "" }
        val splitAt = column.coerceIn(0, lineStr.length)
        val preeditEnd = (splitAt + preedit.length).coerceAtMost(lineStr.length)

        val scale = ChatUiScale.TEXT_SCALE
        val prefixWidth = (textRenderer.getWidth(lineStr.substring(0, splitAt)) * scale).toInt()
        val preeditWidth = (textRenderer.getWidth(lineStr.substring(splitAt, preeditEnd)) * scale).toInt()
        val underlineY = boxY + 3 + lineIndex * ChatUiScale.LINE_HEIGHT + (textRenderer.fontHeight * scale).toInt()
        context.fill(
            textX + prefixWidth,
            underlineY,
            textX + prefixWidth + preeditWidth,
            underlineY + 1,
            0xFFFFFFFF.toInt(),
        )
    }

    /** @return 이 클릭을 소비했으면 true(입력창 영역 안). 클릭한 자리로 커서를 옮긴다. */
    fun mouseClicked(mouseX: Double, mouseY: Double): Boolean {
        val boxHeight = renderHeight
        if (mouseX < x || mouseX > x + width || mouseY < y || mouseY > y + boxHeight) return false

        flush()
        val display = text
        val ranges = wrappedLineRanges(display)
        val lines = ranges.map { display.substring(it.start, it.end) }

        val relY = (mouseY - y - 3).toInt()
        val lineIndex = (relY / ChatUiScale.LINE_HEIGHT).coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        val lineStr = lines.getOrElse(lineIndex) { "" }

        val textX = x + textLeftMargin
        val relX = ((mouseX - textX) / ChatUiScale.TEXT_SCALE).toInt().coerceAtLeast(0)
        val column = nearestColumn(lineStr, relX)

        val target = (ranges.getOrNull(lineIndex)?.start ?: 0) + column
        moveCursorTo(target, extendSelection = false)
        return true
    }

    /**
     * [getTrimmedLength] 는 "이 폭 안에 완전히 들어가는 글자 수"만 준다 — 즉 항상
     * 내림(floor) 이라, 글자 폭의 뒤쪽 절반을 클릭해도 그 글자 앞으로 커서가 간다.
     * 실제로 이게 클릭 정확도가 낮다는 문제의 원인이었다 — 클릭한 지점과 가장 가까운
     * 글자 "경계"로 스냅하도록 한 글자 더 볼지 비교한다(일반적인 텍스트 편집기 동작).
     */
    private fun nearestColumn(lineStr: String, relX: Int): Int {
        val floorCount = textRenderer.textHandler.getTrimmedLength(lineStr, relX, Style.EMPTY).coerceIn(0, lineStr.length)
        if (floorCount >= lineStr.length) return floorCount
        val floorWidth = textRenderer.getWidth(lineStr.substring(0, floorCount))
        val nextCharWidth = textRenderer.getWidth(lineStr.substring(floorCount, floorCount + 1))
        return if (relX - floorWidth > nextCharWidth / 2f) floorCount + 1 else floorCount
    }

    /** @return 이 키를 소비했으면 true. false 면 [CRChatScreen] 이 다른 처리(ESC 등)로 넘겨야 한다. */
    fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        val ctrl = (modifiers and GLFW.GLFW_MOD_CONTROL) != 0
        val shift = (modifiers and GLFW.GLFW_MOD_SHIFT) != 0

        when (keyCode) {
            GLFW.GLFW_KEY_BACKSPACE -> {
                if (hasSelection) {
                    deleteSelection()
                } else if (!composer.backspace() && cursor > 0) {
                    buffer.deleteCharAt(cursor - 1)
                    cursor--
                    selectionAnchor = cursor
                }
                suppressNextChar = false
                return true
            }

            GLFW.GLFW_KEY_DELETE -> {
                flush()
                if (hasSelection) {
                    deleteSelection()
                } else if (cursor < buffer.length) {
                    buffer.deleteCharAt(cursor)
                }
                return true
            }

            GLFW.GLFW_KEY_LEFT -> {
                flush()
                val target = if (ctrl) {
                    TextHandler.moveCursorByWords(buffer.toString(), -1, cursor, true)
                } else {
                    cursor - 1
                }
                moveCursorTo(target, shift)
                return true
            }

            GLFW.GLFW_KEY_RIGHT -> {
                flush()
                val target = if (ctrl) {
                    TextHandler.moveCursorByWords(buffer.toString(), 1, cursor, true)
                } else {
                    cursor + 1
                }
                moveCursorTo(target, shift)
                return true
            }

            GLFW.GLFW_KEY_HOME -> {
                flush()
                moveCursorTo(0, shift)
                return true
            }

            GLFW.GLFW_KEY_END -> {
                flush()
                moveCursorTo(buffer.length, shift)
                return true
            }

            GLFW.GLFW_KEY_A -> if (ctrl) {
                flush()
                selectionAnchor = 0
                cursor = buffer.length
                return true
            }

            GLFW.GLFW_KEY_C -> if (ctrl) {
                copySelection()
                return true
            }

            GLFW.GLFW_KEY_X -> if (ctrl) {
                flush()
                cutSelection()
                return true
            }

            GLFW.GLFW_KEY_V -> if (ctrl) {
                pasteFromClipboard()
                return true
            }

            // 유일한 한/영 전환 키. 클래스 문서 참조 — [kr.teamcraft.crchat.client.win.ImeDetacher]
            // 가 게임 창의 OS IME 연결을 떼어낸 덕에 우측 Alt 를 쓸 수 있게 됐다. 우측 Ctrl 은
            // 이제 안 쓴다(우측 Alt 하나로 충분해서 뺐다) — 다른 Ctrl 조합(Ctrl+A 등)과 안
            // 헷갈리게, 우측 Ctrl 도 좌측 Ctrl 과 똑같이 그냥 보조키로만 취급된다.
            GLFW.GLFW_KEY_RIGHT_ALT -> {
                toggleHangulMode()
                return true
            }
        }

        // 여기까지 안 잡힌 다른 Ctrl+키 조합(Ctrl+S 등)은 자모로 조합하면 안 된다 —
        // 그대로 흘려보낸다.
        if (ctrl) return false

        // 보조키 자체의 keydown 은 조용히 흘려보낸다 — 조합 상태를 건드리면 안 된다.
        if (keyCode in MODIFIER_KEYS) return false

        if (hangulMode) {
            val jamo = Dubeolsik.jamoFor(keyCode, shift)
            if (jamo != null) {
                if (hasSelection) deleteSelection()
                composer.input(jamo)?.let { insertAtCursor(it) }
                suppressNextChar = true
                return true
            }
        }

        // 자모가 아닌 키(숫자·기호·스페이스 등) — 조합 중이던 글자를 확정만 하고,
        // 실제 문자 삽입은 charTyped 에 맡긴다 (A.6 커밋 트리거).
        flush()
        return false
    }

    /** @return 이 문자를 이미 처리했으면(=charTyped 쪽에서 더 할 일 없으면) true */
    fun charTyped(chr: Char): Boolean {
        if (suppressNextChar) {
            suppressNextChar = false
            return true // keyPressed 에서 이미 자모로 처리한 키 — 원문자(영문 알파벳)는 버린다
        }
        // OS IME 가 한글로 켜져 있으면 완성된 한글 문자가 여기로 들어온다 —
        // 우리 조합기와 이중으로 입력되므로 버린다 (A.9).
        if (chr.code in 0xAC00..0xD7A3 || chr.code in 0x3131..0x318E) {
            return true
        }
        if (hasSelection) deleteSelection()
        insertAtCursor(chr.toString())
        return true
    }
}
