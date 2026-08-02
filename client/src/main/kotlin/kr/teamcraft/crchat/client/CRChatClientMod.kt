package kr.teamcraft.crchat.client

import kr.teamcraft.crchat.client.mixin.ChatScreenAccessor
import kr.teamcraft.crchat.client.network.CRChatNetworking
import kr.teamcraft.crchat.client.ui.ChatOverlayRenderer
import kr.teamcraft.crchat.client.ui.CRChatScreen
import kr.teamcraft.crchat.protocol.PROTOCOL_VERSION
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.gui.screen.ChatScreen
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import org.lwjgl.glfw.GLFW
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
                // `/` 로 채팅을 열면 바닐라가 "/" 를 미리 채워서 만든다 — 그 값을 이어받는다.
                // 리플렉션으로 필드 이름("originalChatText")을 직접 읽으면 실제 게임(중간
                // 매핑)에서는 그 이름이 없어 조용히 실패한다. Mixin Accessor 는 빌드 시점에
                // 정확히 리매핑되므로 이 방식을 쓴다.
                val prefill = (screen as ChatScreenAccessor).crchat_getOriginalChatText()
                client.setScreen(CRChatScreen(prefill))
            }
        }

        // 한/영 전환. `Screen.keyPressed` 가로채기로는 실제 한/영 키를 못 잡았다
        // (WORLD_1/2, 스캔코드까지 시도했지만 실사용에서 안 됨) — `KeyBinding` 은
        // Minecraft 가 화면 포커스와 무관하게 자체적으로 추적하는 키 상태를 읽어서
        // 훨씬 안정적이다. 기본키는 우측 Alt — 컨트롤 설정 메뉴에서 유저가 바꿀 수 있다.
        val hangulToggleKey = KeyBindingHelper.registerKeyBinding(
            KeyBinding(
                "key.crchat.toggle_hangul",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_ALT,
                "category.crchat",
            ),
        )
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            while (hangulToggleKey.wasPressed()) {
                (client.currentScreen as? CRChatScreen)?.toggleHangulMode()
            }
        }

        logger.info("CRChat 클라이언트 초기화 (protocol v{})", PROTOCOL_VERSION)
    }
}
