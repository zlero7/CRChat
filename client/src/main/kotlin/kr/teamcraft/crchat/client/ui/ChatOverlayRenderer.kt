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
 * 탭 바·입력창은 여기 없다 — 그건 편집 화면에서만 다룬다. 그 화면이 열려 있는
 * 동안엔 이 렌더러가 대신 건너뛴다(자체적으로 더 자세히 그리므로).
 */
object ChatOverlayRenderer {

    private const val LINE_HEIGHT = 10

    fun render(context: DrawContext) {
        val client = MinecraftClient.getInstance()
        if (client.currentScreen is CRChatScreen) return

        ChatOverlayState.ensureDefaultPosition(client.window.scaledHeight)

        for (panel in ChatOverlayState.panels) {
            val filtered = ChatState.messages.filter { panel.tab == null || it.channel == panel.tab.channelId }
            val maxLines = panel.height / LINE_HEIGHT
            val visible = filtered.takeLast(maxLines)

            var y = panel.y + panel.height - LINE_HEIGHT
            for (message in visible.asReversed()) {
                context.drawTextWithShadow(client.textRenderer, ChatLineFormatter.format(message), panel.x + 4, y, 0xFFFFFF)
                y -= LINE_HEIGHT
                if (y < panel.y) break
            }
        }
    }
}
