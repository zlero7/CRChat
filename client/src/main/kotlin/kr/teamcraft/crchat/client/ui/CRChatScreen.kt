package kr.teamcraft.crchat.client.ui

import kr.teamcraft.crchat.client.network.CRChatNetworking
import kr.teamcraft.crchat.client.state.ChatAppearanceState
import kr.teamcraft.crchat.client.state.ChatHistory
import kr.teamcraft.crchat.client.state.ChatOverlayState
import kr.teamcraft.crchat.client.state.ChatState
import kr.teamcraft.crchat.client.state.OverlayPanel
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.OrderedText
import net.minecraft.text.Text
import org.lwjgl.glfw.GLFW

/**
 * 편집/입력 모드. 바닐라 `ChatScreen` 이 열리는 순간 [kr.teamcraft.crchat.client.CRChatClientMod]
 * 가 이걸로 바꿔치기한다.
 *
 * 평소 표시는 [ChatOverlayRenderer] 가 대신한다. 이 화면은 탭 전환·타이핑·
 * 크기 조절·이동·스크롤·채널 분리(우클릭)를 할 때만 뜬다.
 *
 * 지금 보고 있는 탭([ChatOverlayState.activeTab])은 이 화면 인스턴스가 아니라
 * 전역 상태에 있다 — 화면을 닫았다 다시 열어도 마지막으로 본 탭이 유지되어야 하기 때문이다.
 */
class CRChatScreen(private val initialText: String = "") : Screen(Text.literal("CRChat")) {

    private lateinit var input: CRChatInputField

    /** 명령어("/") 자동완성 — [CommandSuggestions] 참조. */
    private lateinit var commandSuggestions: CommandSuggestions

    /** 배경색 커스터마이즈 팝업 — [ChatBackgroundPicker] 참조. */
    private val backgroundPicker = ChatBackgroundPicker()

    private val tabBarHeight = ChatUiScale.TAB_BAR_HEIGHT

    /** 메인 패널에만 붙는 "채팅 / 배경색" 설정 줄. 참고 이미지처럼 탭 줄 위에 한 줄 더 있다. */
    private val settingsRowHeight = ChatUiScale.TAB_BAR_HEIGHT
    private val lineHeight = ChatUiScale.LINE_HEIGHT
    private val handleSize = 8
    private val inputHeight = 14

    /** 메인 패널은 설정 줄 + 탭 줄, 분리된 패널은 자기 헤더(채널명+닫기) 한 줄뿐이다. */
    private fun headerHeight(panel: OverlayPanel): Int =
        if (panel === ChatOverlayState.main) settingsRowHeight + tabBarHeight else tabBarHeight

    private enum class DragMode { MOVE, RESIZE }

    private var dragTarget: OverlayPanel? = null
    private var dragMode: DragMode? = null
    private var dragStartMouseX = 0.0
    private var dragStartMouseY = 0.0
    private var dragStartX = 0
    private var dragStartY = 0
    private var dragStartWidth = 0
    private var dragStartHeight = 0

    override fun init() {
        ChatOverlayState.ensureDefaultPosition(width, height)
        // 열 때마다 최신 줄이 보이도록 스크롤을 초기화한다 (바닐라 ChatScreen 도 이렇게 동작한다).
        for (panel in ChatOverlayState.panels) panel.scrollOffset = 0

        val main = ChatOverlayState.main
        input = CRChatInputField(textRenderer, main.x, main.y + main.height + 4, main.width, inputHeight)
        input.text = initialText

        commandSuggestions = CommandSuggestions(textRenderer)
        commandSuggestions.update(initialText)
    }

    // 배경 흐림·어둡게 효과를 끈다 — 평소 오버레이와 이질감이 없도록.
    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        // no-op: 일부러 아무것도 그리지 않는다
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // GUI Scale 을 바꾸거나 창 크기를 바꿔도 패널이 화면 밖으로 나가지 않게 매 프레임 보정한다.
        ChatOverlayState.clampToScreen(width, height)

        val main = ChatOverlayState.main
        input.x = main.x
        input.y = main.y + main.height + 4
        input.width = main.width

        for (panel in ChatOverlayState.panels) {
            renderPanel(context, panel, mouseX, mouseY)
        }
        input.render(context, 0xFFFFFF)

        super.render(context, mouseX, mouseY, delta)

        commandSuggestions.update(input.text)
        // 입력창 바로 위, 다른 요소들 위에 그려져야 하므로 z 를 살짝 띄운다.
        context.matrices.push()
        context.matrices.translate(0f, 0f, 200f)
        commandSuggestions.render(context, input.x, input.y, input.width)
        backgroundPicker.render(context, textRenderer)
        context.matrices.pop()
    }

    private fun renderPanel(context: DrawContext, panel: OverlayPanel, mouseX: Int, mouseY: Int) {
        val isMain = panel === ChatOverlayState.main
        val header = headerHeight(panel)

        context.fill(panel.x, panel.y, panel.x + panel.width, panel.y + header, ChatAppearanceState.panelBackgroundArgb(0xEE101018.toInt()))
        context.fill(panel.x, panel.y + header, panel.x + panel.width, panel.y + panel.height, ChatAppearanceState.panelBackgroundArgb(0xAA101018.toInt()))

        if (isMain) {
            renderSettingsRow(context, panel, mouseX, mouseY)
            renderTabs(context, panel, panel.y + settingsRowHeight, mouseX, mouseY)
        } else {
            TextRenderUtil.drawScaled(context, textRenderer, Text.literal(panel.tab?.label ?: "?"), panel.x + 4, panel.y + 3, 0xFFFFFF)
            TextRenderUtil.drawScaled(context, textRenderer, Text.literal("x"), panel.x + panel.width - 10, panel.y + 3, 0xFF8080)
        }

        renderMessages(context, panel)
        renderResizeIcon(context, panel)
    }

    /** 참고 이미지의 "채팅 ... BGM 말풍선 설정" 줄과 같은 자리 — 제목 + 배경색 설정 버튼. */
    private fun renderSettingsRow(context: DrawContext, panel: OverlayPanel, mouseX: Int, mouseY: Int) {
        TextRenderUtil.drawScaled(context, textRenderer, Text.literal("채팅"), panel.x + 4, panel.y + 3, 0xCCCCCC)

        val (labelX, labelWidth) = backgroundButtonBounds(panel)
        val hovered = mouseX in labelX..(labelX + labelWidth) && mouseY in panel.y..(panel.y + settingsRowHeight)
        val color = if (backgroundPicker.open || hovered) 0xFFFFFF else 0xAAAAAA
        TextRenderUtil.drawScaled(context, textRenderer, Text.literal(backgroundButtonLabel()), labelX, panel.y + 3, color)

        context.fill(panel.x, panel.y + settingsRowHeight - 1, panel.x + panel.width, panel.y + settingsRowHeight, 0x33FFFFFF)
    }

    private fun backgroundButtonLabel(): String =
        if (ChatAppearanceState.customBackgroundEnabled) "배경색 ●" else "배경색 ○"

    /** [renderSettingsRow] 와 클릭 판정([mouseClicked])이 같은 좌표를 쓰게 공유한다. */
    private fun backgroundButtonBounds(panel: OverlayPanel): Pair<Int, Int> {
        val label = backgroundButtonLabel()
        val labelWidth = TextRenderUtil.scaledWidth(textRenderer, label)
        val labelX = panel.x + panel.width - labelWidth - 14
        return labelX to labelWidth
    }

    private fun renderTabs(context: DrawContext, panel: OverlayPanel, rowY: Int, mouseX: Int, mouseY: Int) {
        var x = panel.x + 4
        for (tab in Tab.entries) {
            val width = TextRenderUtil.scaledWidth(textRenderer, tab.label)
            val hovered = mouseX in x..(x + width) && mouseY in rowY..(rowY + tabBarHeight)

            // 안 읽은 메시지가 있는 탭은 배경을 노랗게 강조한다 (사용자 요청).
            if (tab in ChatOverlayState.unreadTabs) {
                context.fill(x - 2, rowY + 1, x + width + 2, rowY + tabBarHeight - 1, 0x66FFEE58)
            }

            val color = when {
                tab == ChatOverlayState.activeTab -> 0xFFFFFF
                hovered -> 0xAAAAAA
                else -> 0x808080
            }
            TextRenderUtil.drawScaled(context, textRenderer, Text.literal(tab.label), x, rowY + 3, color)
            if (tab == ChatOverlayState.activeTab) {
                context.fill(x, rowY + tabBarHeight - 2, x + width, rowY + tabBarHeight - 1, 0xFFFFFFFF.toInt())
            }
            x += width + 10
        }
    }

    /** 우상단 코너 브래킷 — 잡고 끌면 우측·상단으로 크기가 늘어난다(좌하단 고정). */
    private fun renderResizeIcon(context: DrawContext, panel: OverlayPanel) {
        val x2 = panel.x + panel.width - 2
        val y1 = panel.y + 2
        context.fill(x2 - handleSize, y1, x2, y1 + 2, 0xCCFFFFFF.toInt())
        context.fill(x2 - 2, y1, x2, y1 + handleSize, 0xCCFFFFFF.toInt())
    }

    /**
     * 메인 패널은 `panel.tab` 이 항상 null(전체 패널이라는 표식)이라
     * [ChatOverlayState.activeTab] 을 따로 봐야 한다 — 이걸 빼먹으면 탭을 눌러도
     * 필터가 안 걸린다(실제로 이 버그가 있었다). 분리된 패널은 고정 채널이라 자기
     * `tab` 을 그대로 쓴다.
     */
    private fun filterChannelId(panel: OverlayPanel): String? =
        if (panel === ChatOverlayState.main) ChatOverlayState.activeTab.channelId else panel.tab?.channelId

    /**
     * 메시지가 아니라 **렌더 줄** 목록. 공지는 메시지 하나가 3줄(빈 줄 포함)이 된다.
     * 패널 폭에 맞춰 자동 줄넘김까지 끝낸 상태다 — 그래서 가로로 좁게 줄여도
     * 글자가 배경 밖으로 삐져나오지 않는다.
     */
    private fun visibleLines(panel: OverlayPanel): List<OrderedText?> {
        val channelId = filterChannelId(panel)
        val maxWidth = ChatUiScale.wrapWidth(panel.width)
        return ChatState.messages
            .filter { channelId == null || it.channel == channelId }
            .flatMap { ChatLineFormatter.wrappedLinesFor(textRenderer, it, maxWidth) }
    }

    private fun renderMessages(context: DrawContext, panel: OverlayPanel) {
        val header = headerHeight(panel)
        val lines = visibleLines(panel)
        val maxLines = (panel.height - header) / lineHeight
        val maxScroll = (lines.size - maxLines).coerceAtLeast(0)
        panel.scrollOffset = panel.scrollOffset.coerceIn(0, maxScroll)

        val endExclusive = lines.size - panel.scrollOffset
        val startIndex = (endExclusive - maxLines).coerceAtLeast(0)
        val visible = lines.subList(startIndex, endExclusive.coerceAtLeast(startIndex))

        // 자동 줄넘김만으로는 못 끊는 긴 토큰(URL 등)까지 대비해, 그려질 영역 밖으로는
        // 아예 안 나가도록 한 번 더 잘라낸다.
        context.enableScissor(panel.x, panel.y + header, panel.x + panel.width, panel.y + panel.height)
        var y = panel.y + panel.height - lineHeight
        for (line in visible.asReversed()) {
            if (line != null) {
                TextRenderUtil.drawScaled(context, textRenderer, line, panel.x + 4, y, 0xFFFFFF)
            }
            y -= lineHeight
            if (y < panel.y + header) break
        }
        context.disableScissor()

        if (lines.size > maxLines) {
            val trackTop = panel.y + header
            val trackHeight = panel.height - header
            val barX = panel.x + panel.width - 3
            val thumbHeight = (trackHeight * maxLines / lines.size).coerceAtLeast(6)
            val thumbY = trackTop + (trackHeight - thumbHeight) * (maxScroll - panel.scrollOffset) / maxScroll.coerceAtLeast(1)
            context.fill(barX, trackTop, barX + 2, trackTop + trackHeight, 0x33FFFFFF)
            context.fill(barX, thumbY, barX + 2, thumbY + thumbHeight, 0xCCFFFFFF.toInt())
        }
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        for (panel in ChatOverlayState.panels) {
            if (mouseX >= panel.x && mouseX <= panel.x + panel.width && mouseY >= panel.y && mouseY <= panel.y + panel.height) {
                val lineCount = visibleLines(panel).size
                val maxLines = (panel.height - headerHeight(panel)) / lineHeight
                val maxScroll = (lineCount - maxLines).coerceAtLeast(0)
                panel.scrollOffset = (panel.scrollOffset + verticalAmount.toInt()).coerceIn(0, maxScroll)
                return true
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (backgroundPicker.mouseClicked(mouseX, mouseY)) return true
        if (commandSuggestions.mouseClicked(mouseX, mouseY) { input.text = it }) return true

        // 크기 조절 손잡이가 최우선이다 (다른 영역과 겹쳐도 항상 잡을 수 있어야 함)
        for (panel in ChatOverlayState.panels) {
            if (inHandle(panel, mouseX, mouseY)) {
                startDrag(panel, DragMode.RESIZE, mouseX, mouseY)
                return true
            }
        }

        val main = ChatOverlayState.main
        if (inHeader(main, mouseX, mouseY)) {
            val tabRowY = main.y + settingsRowHeight
            if (mouseY < tabRowY) {
                // 설정 줄 — 배경색 버튼이 아니면 패널 이동으로 취급한다.
                val (labelX, labelWidth) = backgroundButtonBounds(main)
                if (mouseX >= labelX && mouseX <= labelX + labelWidth) {
                    backgroundPicker.toggle(main.x + 4, tabRowY + tabBarHeight + 6)
                    return true
                }
                startDrag(main, DragMode.MOVE, mouseX, mouseY)
                return true
            }

            var x = main.x + 4
            for (tab in Tab.entries) {
                val width = TextRenderUtil.scaledWidth(textRenderer, tab.label)
                if (mouseX >= x && mouseX <= x + width) {
                    if (button == 1) {
                        ChatOverlayState.detach(tab)
                    } else {
                        ChatOverlayState.activeTab = tab
                        ChatOverlayState.markRead(tab)
                        main.scrollOffset = 0
                    }
                    return true
                }
                x += width + 10
            }
            startDrag(main, DragMode.MOVE, mouseX, mouseY)
            return true
        }

        for (panel in ChatOverlayState.panels) {
            if (panel === main) continue
            if (inHeader(panel, mouseX, mouseY)) {
                if (mouseX >= panel.x + panel.width - 12) {
                    ChatOverlayState.panels.remove(panel)
                } else {
                    startDrag(panel, DragMode.MOVE, mouseX, mouseY)
                }
                return true
            }
        }

        if (input.mouseClicked(mouseX, mouseY)) return true

        return super.mouseClicked(mouseX, mouseY, button)
    }

    private fun inHeader(panel: OverlayPanel, mouseX: Double, mouseY: Double): Boolean =
        mouseX >= panel.x && mouseX <= panel.x + panel.width && mouseY >= panel.y && mouseY <= panel.y + headerHeight(panel)

    /** 리사이즈 손잡이는 이제 우상단이다 (렌더는 [renderResizeIcon] 참조). */
    private fun inHandle(panel: OverlayPanel, mouseX: Double, mouseY: Double): Boolean {
        val hx = panel.x + panel.width - handleSize - 2
        val hy = panel.y
        return mouseX >= hx && mouseX <= hx + handleSize + 2 && mouseY >= hy && mouseY <= hy + handleSize + 2
    }

    private fun startDrag(panel: OverlayPanel, mode: DragMode, mouseX: Double, mouseY: Double) {
        dragTarget = panel
        dragMode = mode
        dragStartMouseX = mouseX
        dragStartMouseY = mouseY
        dragStartX = panel.x
        dragStartY = panel.y
        dragStartWidth = panel.width
        dragStartHeight = panel.height
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, deltaX: Double, deltaY: Double): Boolean {
        if (backgroundPicker.mouseDragged(mouseX, mouseY)) return true
        val panel = dragTarget ?: return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)
        when (dragMode) {
            DragMode.MOVE -> {
                panel.x = (dragStartX + (mouseX - dragStartMouseX)).toInt().coerceIn(0, (width - panel.width).coerceAtLeast(0))
                panel.y = (dragStartY + (mouseY - dragStartMouseY)).toInt().coerceIn(0, (height - panel.height).coerceAtLeast(0))
            }

            // 우상단 손잡이 — 좌하단을 고정한 채 폭은 오른쪽으로, 높이는 위쪽으로 늘어난다.
            DragMode.RESIZE -> {
                val newWidth = (dragStartWidth + (mouseX - dragStartMouseX)).toInt().coerceIn(140, 800)
                val newHeight = (dragStartHeight - (mouseY - dragStartMouseY)).toInt().coerceIn(40, 500)
                panel.width = newWidth
                panel.height = newHeight
                panel.y = dragStartY + (dragStartHeight - newHeight)
            }

            null -> return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)
        }
        return true
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        backgroundPicker.mouseReleased()
        dragTarget = null
        dragMode = null
        return super.mouseReleased(mouseX, mouseY, button)
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        // 자동완성 목록이 떠 있으면 Tab/위/아래/Esc 는 전부 그쪽이 먼저 가져간다 — 안 그러면
        // 위/아래가 채팅 히스토리랑 겹쳐서 뭘 눌러도 목록을 못 넘긴다.
        if (commandSuggestions.isOpen) {
            when (keyCode) {
                GLFW.GLFW_KEY_TAB -> {
                    commandSuggestions.cycle(forward = (modifiers and GLFW.GLFW_MOD_SHIFT) == 0)
                    commandSuggestions.applySelected()?.let { input.text = it }
                    return true
                }
                GLFW.GLFW_KEY_UP -> {
                    commandSuggestions.cycle(forward = false)
                    commandSuggestions.applySelected()?.let { input.text = it }
                    return true
                }
                GLFW.GLFW_KEY_DOWN -> {
                    commandSuggestions.cycle(forward = true)
                    commandSuggestions.applySelected()?.let { input.text = it }
                    return true
                }
                GLFW.GLFW_KEY_ESCAPE -> {
                    commandSuggestions.close()
                    return true
                }
            }
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            submit()
            return true
        }
        // 바닐라 채팅처럼 위/아래로 이전에 보낸 메시지를 그대로 불러온다 (A.7 계획엔
        // 없었지만 사용자 요청으로 추가). 조합 중이던 글자는 history 로 덮어써도 되니
        // composer 상태 리셋은 input.text setter 에 맡긴다.
        if (keyCode == GLFW.GLFW_KEY_UP) {
            ChatHistory.previous(input.text)?.let { input.text = it }
            return true
        }
        if (keyCode == GLFW.GLFW_KEY_DOWN) {
            ChatHistory.next()?.let { input.text = it }
            return true
        }
        if (input.keyPressed(keyCode, scanCode, modifiers)) return true
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun charTyped(chr: Char, modifiers: Int): Boolean = input.charTyped(chr)

    /**
     * `/` 로 시작하면 **명령어**다 — 채팅 채널로 보내면 안 되고, 바닐라 명령 실행
     * 경로(`sendChatCommand`)로 직접 보내야 서버가 명령으로 인식한다.
     */
    private fun submit() {
        val text = input.text
        if (text.isNotBlank()) {
            ChatHistory.record(text)
            if (text.startsWith("/")) {
                MinecraftClient.getInstance().player?.networkHandler?.sendChatCommand(text.removePrefix("/"))
            } else {
                CRChatNetworking.send(ChatOverlayState.activeTab.sendChannelId, text)
            }
        }
        close()
    }

    override fun shouldPause(): Boolean = false
}
