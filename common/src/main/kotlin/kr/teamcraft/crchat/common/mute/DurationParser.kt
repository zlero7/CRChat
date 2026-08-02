package kr.teamcraft.crchat.common.mute

/** `/뮤트 <닉> <기간>` 의 파싱 결과. (기획서 7.2) */
sealed class MuteDuration {
    data object Permanent : MuteDuration()
    data class Temporary(val seconds: Long) : MuteDuration()
}

/**
 * 뮤트 기간 표기(`10m` `1h` `3d` `perm`)를 파싱한다. (기획서 7.2, 부록 B.2)
 *
 * **잘못 파싱하면 영구 뮤트 사고로 이어진다.** 그래서 애매한 입력을 관대하게
 * 받아주지 않는다 — 단위 없음·잘못된 단위·0 이하·오버플로는 전부 null 이다.
 * 호출부는 null 을 "기간 형식이 잘못됨"으로 취급해 명령을 거부해야 한다
 * (조용히 permanent 로 대체하면 안 된다).
 */
object DurationParser {

    private val PATTERN = Regex("^(\\d+)([mhd])$")

    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3_600L
    private const val SECONDS_PER_DAY = 86_400L

    fun parse(input: String): MuteDuration? {
        if (input.equals("perm", ignoreCase = true)) return MuteDuration.Permanent

        val match = PATTERN.matchEntire(input) ?: return null
        val (amountText, unit) = match.destructured
        val amount = amountText.toLongOrNull() ?: return null
        if (amount <= 0) return null

        val perUnit = when (unit) {
            "m" -> SECONDS_PER_MINUTE
            "h" -> SECONDS_PER_HOUR
            "d" -> SECONDS_PER_DAY
            else -> return null
        }

        val seconds = try {
            Math.multiplyExact(amount, perUnit)
        } catch (_: ArithmeticException) {
            return null // 비현실적으로 큰 값 — 오버플로로 음수/작은 값이 되는 사고 방지
        }

        return MuteDuration.Temporary(seconds)
    }
}
