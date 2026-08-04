package kr.teamcraft.crchat.client

import kr.teamcraft.crchat.client.mixin.ChatScreenAccessor
import kr.teamcraft.crchat.client.network.CRChatNetworking
import kr.teamcraft.crchat.client.state.ChatState
import kr.teamcraft.crchat.client.ui.ChatOverlayRenderer
import kr.teamcraft.crchat.client.ui.CRChatScreen
import kr.teamcraft.crchat.client.win.ImeDetacher
import kr.teamcraft.crchat.protocol.PROTOCOL_VERSION
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
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
 *
 * **실제 물리 한/영 키를 잡으려는 시도(WORLD_1/2, 스캔코드, KeyBinding 폴링)는 전부
 * 포기했다.** 그 키는 애초에 우리 모드가 아니라 **Windows 자체의 IME 를 통째로
 * 전환**하는 시스템 키다 — OS 가 한글 IME 로 바뀌면 그 뒤 타이핑은 OS 가 먼저 완성된
 * 한글로 조합해서 넘기는데, 우리 조합기는 그걸 이중 입력으로 보고 버린다(A.9) —
 * 그래서 "OS 전환은 되는데 채팅은 안 쳐지는" 증상이 났다. 해결책은 그 키를 쫓는 게
 * 아니라 **OS IME 는 항상 영문으로 두게 안내**하는 것이다.
 *
 * 우측 Alt 는 처음엔 게임에 keyCode 자체가 안 들어와서 포기했었는데(Windows 가 언어
 * 전환 단축키로 먼저 가로챔), [ImeDetacher] 로 게임 창의 OS IME 연결을 아예 떼어내면
 * 더 이상 안 가로채인다는 걸 KoreanPatch 모드 분석으로 알아내서 다시 살렸다 — 이제
 * 우측 Ctrl·우측 Alt 둘 다 전환 키로 쓸 수 있다([kr.teamcraft.crchat.client.ui.CRChatInputField]).
 */
class CRChatClientMod : ClientModInitializer {

    private val logger = LoggerFactory.getLogger("CRChat")

    override fun onInitializeClient() {
        CRChatNetworking.register()

        // 창(HWND)이 확실히 준비된 뒤에 떼어내야 한다 — onInitializeClient() 시점엔
        // 아직 없을 수 있다(다른 곳에서도 이미 이런 이유로 CLIENT_STARTED 를 쓴다).
        ClientLifecycleEvents.CLIENT_STARTED.register { ImeDetacher.detachIfWindows() }

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

        // 최초 1회 안내 (부록 A.9) — 물리 한/영 키를 누르면 OS IME 가 켜져서 이중 입력이
        // 난다. 켜기 전에 미리 알려준다.
        ChatState.addVanillaMessage("한/영 전환은 우측 Ctrl 또는 우측 Alt 를 쓰세요. OS 입력기(한/영 키)를 켜면 한글이 이중으로 조합됩니다.")

        logger.info("CRChat 클라이언트 초기화 (protocol v{})", PROTOCOL_VERSION)
    }
}
