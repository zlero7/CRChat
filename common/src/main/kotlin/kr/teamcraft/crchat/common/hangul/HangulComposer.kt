package kr.teamcraft.crchat.common.hangul

/**
 * 두벌식 한글 조합기. (기획서 부록 A)
 *
 * OS IME 를 쓰지 않고 직접 조합한다. 순수 JVM 코드라 OS 와 무관하고 단위 테스트가 된다.
 *
 * 조합 상태를 완성된 문자열이 아니라 `(cho, jung, jong)` 인덱스로 들고 있는 이유는
 * 백스페이스 역조합 때문이다. 완성 문자만 들고 있으면 `한 -> 하 -> ㅎ` 을 만들 수 없다. (A.5)
 *
 * 사용 측 계약:
 * - [input] 이 돌려준 문자열은 **확정된 것**이므로 입력 버퍼에 그대로 붙인다.
 * - [preedit] 는 아직 조합 중이라 커서 앞에 밑줄로 그리고, 버퍼에는 넣지 않는다. (A.8)
 */
class HangulComposer {

    private var cho = -1
    private var jung = -1
    private var jong = 0

    /** 조합 중인 글자가 있는가. 백스페이스를 가로챌지 판단할 때 쓴다. */
    val isComposing: Boolean
        get() = cho >= 0 || jung >= 0

    /** 조합 중인 글자. 없으면 null */
    val preedit: Char?
        get() = when {
            cho >= 0 && jung >= 0 -> Jamo.compose(cho, jung, jong)
            cho >= 0 -> Jamo.CHO[cho]
            jung >= 0 -> Jamo.JUNG[jung]
            else -> null
        }

    /** 렌더용. 조합 중이 아니면 빈 문자열 */
    fun preeditText(): String = preedit?.toString() ?: ""

    /**
     * 자모 하나를 입력한다.
     * @return 이번 입력으로 **확정된** 문자열. 없으면 null
     */
    fun input(jamo: Char): String? =
        if (Jamo.isVowel(jamo)) inputVowel(jamo) else inputConsonant(jamo)

    /**
     * 조합 중인 자모를 하나 지운다.
     * @return 조합 중이어서 백스페이스를 **소비했으면** true.
     *         false 면 호출 측이 평소대로 입력 버퍼의 글자를 지워야 한다.
     */
    fun backspace(): Boolean {
        when {
            jong != 0 -> {
                val split = Jamo.splitJong(Jamo.JONG[jong])
                jong = if (split != null) Jamo.jongIndex(split.first) else 0
            }

            jung >= 0 -> {
                val split = Jamo.splitJung(Jamo.JUNG[jung])
                jung = if (split != null) Jamo.jungIndex(split.first) else -1
            }

            cho >= 0 -> cho = -1

            else -> return false
        }
        return true
    }

    /** 강제 확정. 스페이스·엔터·포커스 상실·한영 전환 시 호출한다. (A.6) */
    fun flush(): String? {
        val text = preedit?.toString()
        reset()
        return text
    }

    fun reset() {
        cho = -1
        jung = -1
        jong = 0
    }

    // ── 내부 ──────────────────────────────────────────────

    private fun inputConsonant(c: Char): String? {
        val hasSyllable = cho >= 0 && jung >= 0

        // 완성된 글자에 종성을 붙인다.
        if (hasSyllable && jong == 0) {
            val idx = Jamo.jongIndex(c)
            if (idx > 0) {
                jong = idx
                return null
            }
            // ㄸㅃㅉ 는 종성이 될 수 없다 -> 새 글자로 넘어간다.
        }

        // 종성이 이미 있으면 겹받침을 시도한다.
        if (hasSyllable && jong != 0) {
            val combined = Jamo.combineJong(Jamo.JONG[jong], c)
            if (combined != null) {
                jong = Jamo.jongIndex(combined)
                return null
            }
        }

        return startNewSyllable(c)
    }

    private fun inputVowel(v: Char): String? {
        val jungIdx = Jamo.jungIndex(v)

        // 종성을 떼어 다음 글자의 초성으로 넘긴다. `간` + `ㅏ` -> `가나` (A.4)
        if (jong != 0) {
            val jongChar = Jamo.JONG[jong]
            val split = Jamo.splitJong(jongChar)
            val moving: Char
            if (split != null) {
                // 겹받침은 뒤 자모만 넘어간다. `읽` + `ㅓ` -> `일거`
                jong = Jamo.jongIndex(split.first)
                moving = split.second
            } else {
                jong = 0
                moving = jongChar
            }
            val committed = flush()
            cho = Jamo.choIndex(moving)
            jung = jungIdx
            return committed
        }

        // 겹모음이 되면 결합하고, 안 되면 확정 후 홀로 선 모음으로 시작한다.
        if (jung >= 0) {
            val combined = Jamo.combineJung(Jamo.JUNG[jung], v)
            if (combined != null) {
                jung = Jamo.jungIndex(combined)
                return null
            }
            val committed = flush()
            jung = jungIdx
            return committed
        }

        // 초성만 있거나(=가장 흔한 경우) 아무것도 없는 상태.
        jung = jungIdx
        return null
    }

    /** 지금까지를 확정하고 [c] 로 새 글자를 시작한다. */
    private fun startNewSyllable(c: Char): String? {
        val committed = flush()
        val idx = Jamo.choIndex(c)
        return if (idx >= 0) {
            cho = idx
            committed
        } else {
            // ㄳㄵ 처럼 초성이 될 수 없는 자모. 두벌식 키맵으로는 나오지 않지만
            // 외부에서 직접 넣을 수 있으므로 조합하지 않고 그대로 흘려보낸다.
            (committed ?: "") + c
        }
    }
}
