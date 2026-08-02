package kr.teamcraft.crchat.client

import kr.teamcraft.crchat.client.network.CRChatNetworking
import kr.teamcraft.crchat.client.ui.ChatOverlayRenderer
import kr.teamcraft.crchat.client.ui.CRChatScreen
import kr.teamcraft.crchat.protocol.PROTOCOL_VERSION
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.gui.screen.ChatScreen
import org.slf4j.LoggerFactory

/**
 * 탭 UI 첫 실물이 붙는 진입점 (기획서 13장 Phase 2~3).
 *
 * 바닐라 채팅(T) 트리거는 `Keyboard` 내부에 하드코딩돼 있어 진짜 믹스인 없이는
 * 가로챌 수 없다. 대신 **바닐라 `ChatScreen` 이 열리자마자 우리 화면으로
 * 바꿔치기**하는 방식을 쓴다 — `ScreenEvents.AFTER_INIT` 은 vanilla ChatScreen
 * 의 초기화가 끝난 뒤에 훅이 걸리므로, 그 시점에 `setScreen` 을 다시 불러도
 * 중첩 호출 순서 문제가 없다 (BEFORE_INIT 에서 하면 바깥쪽 setScreen 이
 * 나중에 실행되며 우리 화면을 덮어써 버린다).
 *
 * 바닐라 채팅 렌더 자체를 지우는 건 `options.chatVisibility` 를 건드리는 방식으로
 * 시도했다가 뺐다 — 그 설정은 클라가 채팅 전송 자체를 막아버리는 부작용이 있었다.
 * 지금은 [kr.teamcraft.crchat.client.mixin.ChatHudMixin] 이 렌더만 정확히 지운다.
 */
class CRChatClientMod : ClientModInitializer {

    private val logger = LoggerFactory.getLogger("CRChat")

    override fun onInitializeClient() {
        CRChatNetworking.register()

        HudRenderCallback.EVENT.register { context, _ -> ChatOverlayRenderer.render(context) }

        ScreenEvents.AFTER_INIT.register { client, screen, _, _ ->
            if (screen is ChatScreen) {
                client.setScreen(CRChatScreen(readPrefill(screen)))
            }
        }

        logger.info("CRChat 클라이언트 초기화 (protocol v{})", PROTOCOL_VERSION)
    }

    /**
     * `/` 로 채팅을 열면 바닐라는 `ChatScreen(String)` 생성자에 "/" 를 미리 채워서 만든다.
     * 그 필드(`originalChatText`)가 private 라 getter 가 없어서 리플렉션으로 읽는다 —
     * 이 정도 읽기 전용 접근에 믹스인까지는 과하다.
     */
    private fun readPrefill(screen: ChatScreen): String = runCatching {
        val field = ChatScreen::class.java.getDeclaredField("originalChatText")
        field.isAccessible = true
        field.get(screen) as? String
    }.getOrNull().orEmpty()
}
