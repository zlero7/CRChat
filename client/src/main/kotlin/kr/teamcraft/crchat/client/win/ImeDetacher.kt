package kr.teamcraft.crchat.client.win

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import net.minecraft.client.MinecraftClient
import org.lwjgl.glfw.GLFWNativeWin32
import org.slf4j.LoggerFactory

/**
 * 우측 Alt 를 한/영 전환 키로 못 쓰던 진짜 원인을 찾았다 ([kr.teamcraft.crchat.client.ui.CRChatInputField]
 * 클래스 문서 참조 — 진단 로그로 `keyCode=346` 이 게임에 아예 안 들어오는 것까지 확인했었다).
 * KoreanPatch 모드의 실제 메커니즘을 뜯어보니, "Right Alt 를 더 잘 잡는" 방식이 아니라
 * **게임 창에서 OS IME 연결 자체를 떼어내는** 방식이었다 — Windows 가 Right Alt/한영 키를
 * 언어 전환 단축키로 가로채는 건 그 창에 IME 가 연결돼 있을 때만 벌어지는 일이라,
 * `ImmAssociateContext(hwnd, NULL)` 로 연결을 끊으면 그 뒤로는 Right Alt keydown 이 그대로
 * 게임에 들어온다.
 *
 * 네이티브 DLL을 새로 만들어서 JNI로 붙이는 대신 JNA 로 `imm32.dll` 을 직접 호출한다 —
 * 컴파일된 바이너리를 우리가 따로 관리할 필요가 없다.
 *
 * 게임 전체에서 시작할 때 한 번만 떼어낸다(화면마다 다시 붙였다 떼지 않는다). 이 모드는
 * 원래도 "OS IME 는 항상 영문으로 두라"고 안내해 왔다(A.9) — 우리 조합기가 OS IME 를
 * 대체하는 게 전제라, 이 모드를 쓰는 동안은 다른 바닐라 텍스트 입력창(월드 이름 등)도
 * OS IME 에 기대지 않는 게 맞다.
 *
 * Windows 가 아니면(맥·리눅스) 애초에 이 문제 자체가 없으므로 아무 것도 안 한다.
 */
object ImeDetacher {

    private val logger = LoggerFactory.getLogger("CRChat")
    private var detached = false

    fun detachIfWindows() {
        if (detached) return
        if (!System.getProperty("os.name").orEmpty().contains("Windows", ignoreCase = true)) return

        try {
            val window = MinecraftClient.getInstance().window ?: return
            val hwndValue = GLFWNativeWin32.glfwGetWin32Window(window.handle)
            if (hwndValue == 0L) return

            Imm32.INSTANCE.ImmAssociateContext(Pointer(hwndValue), null)
            detached = true
            logger.info("게임 창에서 OS IME 연결을 해제했습니다 — 우측 Alt 를 한/영 전환 키로 씁니다.")
        } catch (e: Throwable) {
            // JNA 로딩 실패 등 어떤 이유로든 실패해도 게임은 계속 켜져야 한다 —
            // 실패하면 그냥 우측 Ctrl 만 계속 쓰면 된다.
            logger.warn("OS IME 연결 해제 실패 — 우측 Ctrl 로만 한/영 전환하세요: {}", e.message)
        }
    }

    private interface Imm32 : Library {
        fun ImmAssociateContext(hWnd: Pointer, hIMC: Pointer?): Pointer?

        companion object {
            val INSTANCE: Imm32 = Native.load("imm32", Imm32::class.java)
        }
    }
}
