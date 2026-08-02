package kr.teamcraft.crchat.common.hangul

/**
 * 두벌식 키맵. (기획서 부록 A.3)
 *
 * **키 코드로 매핑한다. `charTyped` 로 받은 문자를 쓰면 안 된다.**
 * `charTyped` 결과는 OS 키보드 레이아웃을 타서, 유저가 OS 입력기를 한글로 켜 두면
 * 엉뚱한 문자가 들어온다. (A.3, A.9)
 *
 * `GLFW_KEY_A`~`GLFW_KEY_Z` 는 값이 ASCII 대문자 코드와 같다(`GLFW_KEY_Q == 'Q'.code`).
 * 덕분에 이 모듈이 LWJGL 에 의존하지 않고도 GLFW 키 코드를 그대로 받을 수 있다.
 */
object Dubeolsik {

    private val BASE = mapOf(
        'Q' to 'ㅂ', 'W' to 'ㅈ', 'E' to 'ㄷ', 'R' to 'ㄱ', 'T' to 'ㅅ',
        'Y' to 'ㅛ', 'U' to 'ㅕ', 'I' to 'ㅑ', 'O' to 'ㅐ', 'P' to 'ㅔ',
        'A' to 'ㅁ', 'S' to 'ㄴ', 'D' to 'ㅇ', 'F' to 'ㄹ', 'G' to 'ㅎ',
        'H' to 'ㅗ', 'J' to 'ㅓ', 'K' to 'ㅏ', 'L' to 'ㅣ',
        'Z' to 'ㅋ', 'X' to 'ㅌ', 'C' to 'ㅊ', 'V' to 'ㅍ',
        'B' to 'ㅠ', 'N' to 'ㅜ', 'M' to 'ㅡ',
    )

    private val SHIFTED = mapOf(
        'Q' to 'ㅃ', 'W' to 'ㅉ', 'E' to 'ㄸ', 'R' to 'ㄲ', 'T' to 'ㅆ',
        'O' to 'ㅒ', 'P' to 'ㅖ',
    )

    /**
     * @param keyCode GLFW 키 코드
     * @return 대응하는 자모. 한글 키가 아니면 null
     */
    fun jamoFor(keyCode: Int, shift: Boolean = false): Char? {
        val key = keyCode.toChar().uppercaseChar()
        return if (shift) SHIFTED[key] ?: BASE[key] else BASE[key]
    }
}
