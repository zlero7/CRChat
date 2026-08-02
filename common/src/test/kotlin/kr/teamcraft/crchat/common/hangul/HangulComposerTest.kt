package kr.teamcraft.crchat.common.hangul

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 두벌식 키를 순서대로 입력하고 **확정분 + 조합 중인 글자**를 합쳐 돌려준다.
 * 대문자는 Shift 로 친 것으로 본다.
 */
private fun HangulComposer.typeAll(keys: String): String {
    val out = StringBuilder()
    for (key in keys) {
        val jamo = Dubeolsik.jamoFor(key.uppercaseChar().code, shift = key.isUpperCase()) ?: continue
        input(jamo)?.let { out.append(it) }
    }
    return out.toString() + preeditText()
}

class HangulComposerTest {

    // ── 기본 조합 ──────────────────────────────────────────

    @Test
    fun `초성과 중성이 한 글자로 합쳐진다`() {
        assertEquals("가", HangulComposer().typeAll("rk"))
    }

    @Test
    fun `종성까지 세 자모가 한 글자가 된다`() {
        assertEquals("한", HangulComposer().typeAll("gks"))
    }

    @Test
    fun `한글 두 글자`() {
        assertEquals("한글", HangulComposer().typeAll("gksrmf"))
    }

    @Test
    fun `Shift 로 쌍자음을 입력한다`() {
        assertEquals("까", HangulComposer().typeAll("Rk"))
    }

    // ── 겹모음 · 겹받침 ────────────────────────────────────

    @Test
    fun `겹모음이 결합된다`() {
        assertEquals("과", HangulComposer().typeAll("rhk"))
    }

    @Test
    fun `겹받침이 결합된다`() {
        assertEquals("읽", HangulComposer().typeAll("dlfr"))
    }

    @Test
    fun `겹모음과 겹받침이 섞인 문장`() {
        assertEquals("괜찮아", HangulComposer().typeAll("rhoscksgdk"))
    }

    @Test
    fun `받침 뒤에 자음이 오면 다음 글자가 시작된다`() {
        assertEquals("밝다", HangulComposer().typeAll("qkfrek"))
    }

    // ── 종성 분리 — 조합기의 핵심 ──────────────────────────

    @Test
    fun `종성이 다음 글자 초성으로 넘어간다`() {
        // ㄱㅏㄴㅏ : `간` 에 ㅏ 가 오면 ㄴ 이 떨어져 나온다
        assertEquals("가나", HangulComposer().typeAll("rksk"))
    }

    @Test
    fun `겹받침은 뒤 자모만 넘어간다`() {
        // `읽` + ㅓ -> `일거`. 앞 자모 ㄹ 은 종성으로 남는다
        assertEquals("일거", HangulComposer().typeAll("dlfrj"))
    }

    @Test
    fun `겹받침 뒤에 자음이 오면 겹받침이 유지된다`() {
        assertEquals("읽어", HangulComposer().typeAll("dlfrdj"))
    }

    @Test
    fun `종성이 될 수 없는 자음은 새 글자를 시작한다`() {
        // ㄸㅃㅉ 는 종성 자리에 올 수 없다
        assertEquals("다ㄸ", HangulComposer().typeAll("ekE"))
    }

    // ── 백스페이스 역조합 (A.5) ────────────────────────────

    @Test
    fun `백스페이스는 자모 단위로 지운다`() {
        val c = HangulComposer()
        c.typeAll("gks")            // 한
        assertTrue(c.backspace())
        assertEquals("하", c.preeditText())
        assertTrue(c.backspace())
        assertEquals("ㅎ", c.preeditText())
        assertTrue(c.backspace())
        assertEquals("", c.preeditText())
    }

    @Test
    fun `겹받침은 앞 자모만 남기고 지워진다`() {
        val c = HangulComposer()
        c.typeAll("dlfr")           // 읽
        c.backspace()
        assertEquals("일", c.preeditText())
    }

    @Test
    fun `겹모음은 앞 모음만 남기고 지워진다`() {
        val c = HangulComposer()
        c.typeAll("rhk")            // 과
        c.backspace()
        assertEquals("고", c.preeditText())
    }

    @Test
    fun `조합 중이 아니면 백스페이스를 소비하지 않는다`() {
        assertFalse(HangulComposer().backspace())
    }

    // ── 확정 (A.6) ────────────────────────────────────────

    @Test
    fun `flush 는 조합 중인 글자를 확정하고 비운다`() {
        val c = HangulComposer()
        c.typeAll("gks")
        assertEquals("한", c.flush())
        assertEquals("", c.preeditText())
        assertFalse(c.isComposing)
    }

    @Test
    fun `조합 중이 아닐 때 flush 는 null`() {
        assertEquals(null, HangulComposer().flush())
    }

    // ── 홀로 선 자모 ──────────────────────────────────────

    @Test
    fun `자음만 연달아 치면 각각 확정된다`() {
        assertEquals("ㄱㄱㄱ", HangulComposer().typeAll("rrr"))
    }

    @Test
    fun `결합되지 않는 모음은 각각 확정된다`() {
        assertEquals("ㅏㅏ", HangulComposer().typeAll("kk"))
    }

    @Test
    fun `모음으로 시작해도 조합이 이어진다`() {
        // ㅏ 뒤의 ㄴ 은 종성이 아니라 새 글자의 초성이다
        assertEquals("ㅏ나", HangulComposer().typeAll("ksk"))
    }
}
