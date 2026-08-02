package kr.teamcraft.crchat.common.sanitize

/**
 * `filters.yml` 의 `sanitize` 섹션과 1:1 대응. (부록 C.4)
 * 전부 켜는 게 기본값이다 — 하나라도 끄면 그 우회 경로가 그대로 열린다.
 */
data class SanitizeConfig(
    val removePua: Boolean = true,
    val removeSectionSign: Boolean = true,
    val removeZeroWidth: Boolean = true,
    val removeRtlOverride: Boolean = true,
    val removeUnrenderable: Boolean = true,
    val newlineToSpace: Boolean = true,
    val maxCombiningMarks: Int = 3,
)

/**
 * 입력 문자 정규화. (기획서 7.4)
 *
 * 서버에서 반드시 걸러야 한다 — 클라에서 거르면 수정된 클라로 우회된다.
 * **순서 주의**: 이 정규화는 금칙어 검사보다 반드시 먼저 실행해야 한다.
 * 순서가 반대면 제로폭 문자를 끼워 넣는 것만으로 필터가 뚫린다.
 */
object TextSanitizer {

    // U+E000~U+F8FF(기본 다국어 평면) + 보조 평면 A/B.
    // 리소스팩 UI 아이콘·음수 스페이싱 글리프가 여기 있어, 붙여넣으면 채팅창
    // 레이아웃이 깨지거나 가짜 아이콘을 띄울 수 있다.
    private val PUA_RANGES = listOf(
        0xE000..0xF8FF,
        0xF0000..0xFFFFD,
        0x100000..0x10FFFD,
    )

    // 문자열 뒤집기로 닉네임을 위장하는 데 쓰이는 양방향 제어 문자 전부.
    private val RTL_OVERRIDE_CODEPOINTS = setOf(
        0x200E, 0x200F, // LRM, RLM
        0x202A, 0x202B, 0x202C, 0x202D, 0x202E, // LRE, RLE, PDF, LRO, RLO
        0x2066, 0x2067, 0x2068, 0x2069, // LRI, RLI, FSI, PDI
    )

    // 금칙어 우회에 쓰이는 제로폭 문자.
    private val ZERO_WIDTH_CODEPOINTS = setOf(0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF)

    // 이모지·픽토그램 계열. 커스텀 폰트에 글리프가 없어 두부(□)로 보인다.
    // 이모지는 범위 외(16장)이므로 "렌더 불가 문자" 제거는 사실상 이 대역 제거다.
    private val UNRENDERABLE_RANGES = listOf(
        0x1F1E6..0x1F1FF, // 국기 조합용 지역 표시 기호
        0x1F300..0x1FAFF, // 각종 심볼·픽토그램·이모티콘·교통 기호
        0x2600..0x27BF, // 기타 기호, 딩뱃
        0xFE00..0xFE0F, // variation selector (이모지 표시 지정자)
    )

    /**
     * @return 정제된 문자열. **정제 후 빈 문자열이면 null** — 호출부는 전송 자체를 거부해야 한다.
     */
    fun clean(raw: String, config: SanitizeConfig = SanitizeConfig()): String? {
        val out = StringBuilder(raw.length)
        var combiningRun = 0

        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            val charCount = Character.charCount(cp)
            i += charCount

            when {
                config.newlineToSpace && (cp == '\n'.code || cp == '\r'.code || cp == '\t'.code) -> {
                    out.append(' ')
                    combiningRun = 0
                }

                config.removeZeroWidth && cp in ZERO_WIDTH_CODEPOINTS -> Unit // 제거

                config.removeRtlOverride && cp in RTL_OVERRIDE_CODEPOINTS -> Unit // 제거

                config.removeSectionSign && cp == 0xA7 -> Unit // '§' 제거

                config.removePua && PUA_RANGES.any { cp in it } -> Unit // 제거

                config.removeUnrenderable && UNRENDERABLE_RANGES.any { cp in it } -> Unit // 제거

                isCombiningMark(cp) -> {
                    combiningRun++
                    if (combiningRun <= config.maxCombiningMarks) {
                        out.appendCodePoint(cp)
                    } // 초과분은 조용히 버린다 (zalgo 방지)
                }

                else -> {
                    combiningRun = 0
                    out.appendCodePoint(cp)
                }
            }
        }

        val result = out.toString()
        return result.ifBlank { null }
    }

    private fun isCombiningMark(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
        -> true
        else -> false
    }
}
