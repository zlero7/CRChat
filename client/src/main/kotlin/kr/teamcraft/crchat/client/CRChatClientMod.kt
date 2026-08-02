package kr.teamcraft.crchat.client

import kr.teamcraft.crchat.client.network.CRChatNetworking
import kr.teamcraft.crchat.client.ui.ChatOverlayRenderer
import kr.teamcraft.crchat.client.ui.CRChatScreen
import kr.teamcraft.crchat.protocol.PROTOCOL_VERSION
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.gui.screen.ChatScreen
import net.minecraft.network.message.ChatVisibility
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
 */
class CRChatClientMod : ClientModInitializer {

    private val logger = LoggerFactory.getLogger("CRChat")

    override fun onInitializeClient() {
        CRChatNetworking.register()

        // `onInitializeClient()` 시점엔 `MinecraftClient.options` 가 아직 null 이다
        // (실제로 NPE 로 크래시했다). GameOptions 가 준비된 뒤(CLIENT_STARTED)로 미룬다.
        ClientLifecycleEvents.CLIENT_STARTED.register { client ->
            // 바닐라 ChatHud(접속 알림·명령어 피드백 등)가 우리 오버레이 뒤에 겹쳐 보이는 걸
            // 막는다. 대신 그 메시지들은 지금 당장은 어디에도 안 뜬다 — 바닐라 메시지를
            // 우리 오버레이로 끌어오는 건(4.7) ChatHud.addMessage 가로채기가 필요한 다음 단계다.
            client.options.chatVisibility.value = ChatVisibility.HIDDEN
        }

        HudRenderCallback.EVENT.register { context, _ -> ChatOverlayRenderer.render(context) }

        ScreenEvents.AFTER_INIT.register { client, screen, _, _ ->
            if (screen is ChatScreen) {
                client.setScreen(CRChatScreen())
            }
        }

        logger.info("CRChat 클라이언트 초기화 (protocol v{})", PROTOCOL_VERSION)
    }
}
