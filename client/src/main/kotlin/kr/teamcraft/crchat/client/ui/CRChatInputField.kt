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
 * 한/영 전환 키는 우측 Ctrl (원래 계획은 우측 Alt 였으나 실사용에서 안 먹혀 교체, 아래 참조).
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

    /**
     * 한/영 전환 상태. 기본은 한글 모드.
     *
     * 우측 Alt(AltGr) 는 Windows 에서 내부적으로 가짜 좌측 Ctrl 이벤트를 같이
     * 보내는 걸로 알려져 있어(자판 종류에 따라 GLFW 가 이걸 못 걸러낼 수 있다)
     * 우측 Ctrl 로 바꿨다.
     */
    private var hangulMode = true

    /** keyPressed 에서 자모로 이미 처리한 키가 곧이어 들어올 charTyped 를 오염시키지 않게 막는 플래그. */
    private var suppressNextChar = false

    private val maxLength = 256

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

    fun render(context: DrawContext, textColor: Int) {
        val indicator = if (hangulMode) "한" else "EN"
        val indicatorX = x + 3
        TextRenderUtil.drawScaled(context, textRenderer, net.minecraft.text.Text.literal(indicator), indicatorX, y + 3, 0x88FF88)

        val textX = x + 20
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
    fun keyPressed(keyCode: Int, modifiers: Int): Boolean {
        when (keyCode) {
            GLFW.GLFW_KEY_BACKSPACE -> {
                if (!composer.backspace() && buffer.isNotEmpty()) {
                    buffer.deleteCharAt(buffer.length - 1)
                }
                suppressNextChar = false
                return true
            }

            GLFW.GLFW_KEY_RIGHT_CONTROL -> {
                flush()
                hangulMode = !hangulMode
                logger.info("한/영 전환 -> {}", if (hangulMode) "한글" else "영문")
                return true
            }
        }

        if (hangulMode) {
            val shift = (modifiers and GLFW.GLFW_MOD_SHIFT) != 0
            val jamo = Dubeolsik.jamoFor(keyCode, shift)
            logger.info("keyCode={} modifiers={} shift={} -> jamo={}", keyCode, modifiers, shift, jamo)
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
