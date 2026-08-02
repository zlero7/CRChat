package kr.teamcraft.crchat.client.ui

import kr.teamcraft.crchat.common.hangul.Dubeolsik
import kr.teamcraft.crchat.common.hangul.HangulComposer
import net.minecraft.client.font.TextRenderer
import net.minecraft.client.gui.DrawContext
import org.lwjgl.glfw.GLFW
import org.slf4j.LoggerFactory

/**
 * 자체 두벌식 조합기를 쓰는 채팅 입력창. (기획서 부록 A)
 *
 * 바닐라 `TextFieldWidget` 을 안 쓰는 이유는 OS IME 에 기대지 않고 우리가 직접
 * 조합하기 위해서다 — `charTyped` 가 아니라 `keyPressed` 의 GLFW 키 코드로
 * [Dubeolsik] 매핑을 거쳐 [HangulComposer] 에 넣는다 (A.3).
 *
 * `ClickableWidget` 을 상속하지 않는다 — 이 화면엔 타이핑 대상이 이거 하나뿐이라
 * 포커스·선택 시스템이 필요 없고, [kr.teamcraft.crchat.client.ui.CRChatScreen] 이
 * 키·문자 이벤트를 직접 여기로 넘겨준다.
 *
 * 커서 이동·드래그 선택은 아직 없다 — 지금은 끝에서만 입력·삭제되는 1차 버전이다.
 *
 * 한/영 전환 키는 **우측 Ctrl 뿐**이다. 실제 물리 한/영 키는 일부러 안 잡는다 —
 * 그 키는 Windows 자체의 IME 를 통째로 전환하는 시스템 키라, 눌리는 순간부터
 * 타이핑이 OS 한글 입력기를 거쳐 넘어오고 우리 조합기는 그걸 이중 입력으로
 * 보고 버린다(A.9) — "OS 전환은 되는데 채팅이 안 쳐지는" 증상의 원인이었다.
 */
class CRChatInputField(
    private val textRenderer: TextRenderer,
    var x: Int,
    var y: Int,
    var width: Int,
    val height: Int,
) {
    private val composer = HangulComposer()
    private val buffer = StringBuilder()
    private val logger = LoggerFactory.getLogger("CRChat/Input")

    /** 한/영 전환 상태. 기본은 한글 모드. */
    private var hangulMode = true

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

    /** 조합 중인 글자까지 포함한 현재 표시 텍스트. */
    var text: String
        get() = buffer.toString() + composer.preeditText()
        set(value) {
            buffer.setLength(0)
            buffer.append(value.take(maxLength))
            composer.reset()
        }

    /** Enter 로 전송하기 직전에 불러서 조합 중이던 글자까지 확정한다. */
    fun flush() {
        composer.flush()?.let { appendToBuffer(it) }
    }

    fun clear() {
        buffer.setLength(0)
        composer.reset()
    }

    private fun toggleHangulMode() {
        flush()
        hangulMode = !hangulMode
    }

    fun render(context: DrawContext, textColor: Int) {
        // 배경 + 테두리 — 이게 없으면 게임 화면과 안 구분돼서 입력창처럼 안 보인다.
        context.fill(x, y, x + width, y + height, 0xEE101018.toInt())
        context.fill(x, y, x + width, y + 1, 0x77FFFFFF.toInt()) // 위
        context.fill(x, y + height - 1, x + width, y + height, 0x77FFFFFF.toInt()) // 아래
        context.fill(x, y, x + 1, y + height, 0x77FFFFFF.toInt()) // 왼쪽
        context.fill(x + width - 1, y, x + width, y + height, 0x77FFFFFF.toInt()) // 오른쪽

        val indicator = if (hangulMode) "한" else "EN"
        val indicatorX = x + 3
        TextRenderUtil.drawScaled(context, textRenderer, net.minecraft.text.Text.literal(indicator), indicatorX, y + 3, 0x88FF88)

        // 한/영 표시와 실제 입력 사이 구분선
        context.fill(x + 17, y + 2, x + 18, y + height - 2, 0x44FFFFFF.toInt())

        val textX = x + 21
        val display = text
        TextRenderUtil.drawScaled(context, textRenderer, net.minecraft.text.Text.literal(display), textX, y + 3, textColor)

        if (composer.isComposing) {
            // 조합 중인 글자 밑에 밑줄을 긋는다 (A.8) — 스케일이 걸려 있으니 폭도 스케일만큼 줄여 계산한다.
            val scale = ChatUiScale.TEXT_SCALE
            val prefixWidth = (textRenderer.getWidth(buffer.toString()) * scale).toInt()
            val preeditWidth = (textRenderer.getWidth(composer.preeditText()) * scale).toInt()
            val underlineY = y + 3 + (textRenderer.fontHeight * scale).toInt()
            context.fill(
                textX + prefixWidth,
                underlineY,
                textX + prefixWidth + preeditWidth,
                underlineY + 1,
                0xFFFFFFFF.toInt(),
            )
        }
    }

    /** @return 이 키를 소비했으면 true. false 면 [CRChatScreen] 이 다른 처리(ESC 등)로 넘겨야 한다. */
    fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        // 진단용. Alt 를 눌렀을 때 이 화면까지 뭐가(혹은 아무것도 안) 오는지 확인한다.
        logger.info("keyPressed keyCode={} scanCode={} modifiers={}", keyCode, scanCode, modifiers)

        when (keyCode) {
            GLFW.GLFW_KEY_BACKSPACE -> {
                if (!composer.backspace() && buffer.isNotEmpty()) {
                    buffer.deleteCharAt(buffer.length - 1)
                }
                suppressNextChar = false
                return true
            }

            // 한/영 전환 키. 우측 Ctrl 은 검증됨. 우측 Alt 도 같은 방식(화면
            // keyPressed 직접 비교)으로 다시 시도한다 — "AltGr 문제" 는 테스트 없이
            // 넘겨짚은 것이었다. 실제 물리 한/영 키(라벨 있는 키)는 일부러 안 잡는다,
            // 클래스 문서 참조.
            GLFW.GLFW_KEY_RIGHT_CONTROL, GLFW.GLFW_KEY_RIGHT_ALT -> {
                toggleHangulMode()
                return true
            }
        }

        // 보조키 자체의 keydown 은 조용히 흘려보낸다 — 조합 상태를 건드리면 안 된다.
        if (keyCode in MODIFIER_KEYS) return false

        if (hangulMode) {
            val shift = (modifiers and GLFW.GLFW_MOD_SHIFT) != 0
            val jamo = Dubeolsik.jamoFor(keyCode, shift)
            if (jamo != null) {
                composer.input(jamo)?.let { appendToBuffer(it) }
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
        appendToBuffer(chr.toString())
        return true
    }

    private fun appendToBuffer(s: String) {
        if (buffer.length >= maxLength) return
        buffer.append(s.take(maxLength - buffer.length))
    }
}
