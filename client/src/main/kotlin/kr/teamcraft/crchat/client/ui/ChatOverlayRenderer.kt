package kr.teamcraft.crchat.client.ui

import kr.teamcraft.crchat.client.state.ChatOverlayState
import kr.teamcraft.crchat.client.state.ChatState
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext

/**
 * 항상 그려지는 채팅 오버레이. [CRChatScreen] 을 열지 않아도 평소에 보인다.
 * `Screen` 이 아니라 HUD 렌더 콜백이라 배경 흐림·어둡게 효과가 애초에 안 걸린다.
 *
 * 평소엔 **배경 없이 글자만** 게임 화면 위에 떠 있다 — 바닐라 채팅과 같은 느낌.
 * 반투명 패널 배경은 채팅을 실제로 열었을 때([CRChatScreen])만 나온다.
 *
 * 메인 패널은 [ChatOverlayState.activeTab] 을 따른다 — 채팅을 닫아도 마지막으로
 * 보던 탭이 그대로 유지되게 하기 위해서다.
 *
 * 탭 바·입력창은 여기 없다 — 그건 편집 화면에서만 다룬다. 그 화면이 열려 있는
 * 동안엔 이 렌더러가 대신 건너뛴다(자체적으로 더 자세히 그리므로).
 */
object ChatOverlayRenderer {

    private val LINE_HEIGHT = ChatUiScale.LINE_HEIGHT

    fun render(context: DrawContext) {
        val client = MinecraftClient.getInstance()
        if (client.currentScreen is CRChatScreen) return

        ChatOverlayState.ensureDefaultPosition(client.window.scaledHeight)

        for (panel in ChatOverlayState.panels) {
            val channelId = if (panel === ChatOverlayState.main) ChatOverlayState.activeTab.channelId else panel.tab?.channelId
            val filtered = ChatState.messages.filter { channelId == null || it.channel == channelId }
            val lines = filtered.flatMap { ChatLineFormatter.expand(it) }
            val maxLines = panel.height / LINE_HEIGHT
            val visible = lines.takeLast(maxLines)

            var y = panel.y + panel.height - LINE_HEIGHT
            for (line in visible.asReversed()) {
                if (line != null) {
                    TextRenderUtil.drawScaled(context, client.textRenderer, line, panel.x + 4, y, 0xFFFFFF)
                }
                y -= LINE_HEIGHT
                if (y < panel.y) break
            }
        }
    }
}
