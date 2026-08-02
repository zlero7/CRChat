package kr.teamcraft.crchat.common.sanitize

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TextSanitizerTest {

    @Test
    fun `평범한 한글·영문·문장부호는 그대로 통과한다`() {
        assertEquals("안녕하세요! Hello, world.", TextSanitizer.clean("안녕하세요! Hello, world."))
    }

    @Test
    fun `PUA 영역 문자를 제거한다`() {
        val pua = 0xE001.toChar().toString() // 기본 다국어 평면 PUA
        assertEquals("가나", TextSanitizer.clean("가${pua}나"))
    }

    @Test
    fun `보조 평면 PUA 도 제거한다`() {
        val supplementaryPua = String(Character.toChars(0xF0001))
        assertEquals("가나", TextSanitizer.clean("가${supplementaryPua}나"))
    }

    @Test
    fun `섹션 기호를 제거한다`() {
        assertEquals("가나", TextSanitizer.clean("가§나"))
    }

    @Test
    fun `개행과 탭을 공백으로 치환한다`() {
        assertEquals("가 나 다", TextSanitizer.clean("가\n나\t다"))
    }

    @Test
    fun `RTL 재정렬 제어문자를 제거한다`() {
        assertEquals("abc", TextSanitizer.clean("a‮bc"))
    }

    @Test
    fun `제로폭 문자를 제거한다`() {
        assertEquals("가나", TextSanitizer.clean("가​나"))
    }

    @Test
    fun `이모지 대역을 제거한다`() {
        // U+1F600 GRINNING FACE
        val emoji = String(Character.toChars(0x1F600))
        assertEquals("가나", TextSanitizer.clean("가${emoji}나"))
    }

    @Test
    fun `결합 문자는 글자당 3개까지만 허용한다`() {
        val zalgo = "가" + "́".repeat(10) // 악센트 결합 문자 10개
        val cleaned = TextSanitizer.clean(zalgo)!!
        assertEquals(1 + 3, cleaned.codePointCount(0, cleaned.length))
    }

    @Test
    fun `허용 개수 이내 결합 문자는 그대로 남는다`() {
        val text = "가" + "́".repeat(2)
        assertEquals(text, TextSanitizer.clean(text))
    }

    @Test
    fun `정제 후 빈 문자열이면 null 을 반환한다`() {
        assertNull(TextSanitizer.clean("​​​"))
    }

    @Test
    fun `공백만 남아도 null 을 반환한다`() {
        assertNull(TextSanitizer.clean("\n\t  "))
    }

    @Test
    fun `옵션을 끄면 해당 문자를 건드리지 않는다`() {
        val config = SanitizeConfig(removeSectionSign = false)
        assertEquals("가§나", TextSanitizer.clean("가§나", config))
    }

    @Test
    fun `여러 문제 문자가 섞여도 한 번에 정리된다`() {
        val emoji = String(Character.toChars(0x1F600))
        val messy = "가​나‮다§라${emoji}마\n바"
        assertEquals("가나다라마 바", TextSanitizer.clean(messy))
    }
}
