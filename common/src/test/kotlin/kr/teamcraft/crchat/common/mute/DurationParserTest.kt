package kr.teamcraft.crchat.common.mute

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DurationParserTest {

    @Test
    fun `분 단위를 초로 환산한다`() {
        assertEquals(MuteDuration.Temporary(600), DurationParser.parse("10m"))
    }

    @Test
    fun `시간 단위를 초로 환산한다`() {
        assertEquals(MuteDuration.Temporary(3_600), DurationParser.parse("1h"))
    }

    @Test
    fun `일 단위를 초로 환산한다`() {
        assertEquals(MuteDuration.Temporary(259_200), DurationParser.parse("3d"))
    }

    @Test
    fun `perm 은 영구로 파싱된다`() {
        assertEquals(MuteDuration.Permanent, DurationParser.parse("perm"))
    }

    @Test
    fun `perm 은 대소문자를 가리지 않는다`() {
        assertEquals(MuteDuration.Permanent, DurationParser.parse("PERM"))
        assertEquals(MuteDuration.Permanent, DurationParser.parse("Perm"))
    }

    @Test
    fun `단위가 없으면 거부한다`() {
        assertNull(DurationParser.parse("10"))
    }

    @Test
    fun `지원하지 않는 단위는 거부한다`() {
        assertNull(DurationParser.parse("10x"))
        assertNull(DurationParser.parse("10s")) // 초 단위는 사양에 없다
    }

    @Test
    fun `0 이하는 거부한다`() {
        assertNull(DurationParser.parse("0m"))
    }

    @Test
    fun `음수는 정규식에 안 걸려 거부된다`() {
        assertNull(DurationParser.parse("-5m"))
    }

    @Test
    fun `숫자가 아니면 거부한다`() {
        assertNull(DurationParser.parse("abcm"))
    }

    @Test
    fun `빈 문자열은 거부한다`() {
        assertNull(DurationParser.parse(""))
    }

    @Test
    fun `공백이 섞이면 거부한다`() {
        assertNull(DurationParser.parse(" 10m"))
        assertNull(DurationParser.parse("10m "))
    }

    @Test
    fun `오버플로가 나는 값은 거부한다`() {
        // Long 범위를 넘는 일수 -> 조용히 wrap 되어 사고 나는 대신 명시적으로 거부되어야 한다
        assertNull(DurationParser.parse("999999999999999999d"))
    }
}
