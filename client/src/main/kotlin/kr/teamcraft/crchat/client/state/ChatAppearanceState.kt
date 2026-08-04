package kr.teamcraft.crchat.client.state

import kotlin.math.abs

/**
 * 채팅 패널 배경색 커스터마이즈. (사용자 요청 — 참고 이미지의 "말풍선 설정" 색상
 * 선택기와 같은 방식으로 켜고 끌 수 있는 배경색 설정)
 *
 * 세션 메모리에만 있다 — 재시작하면 기본값(꺼짐)으로 돌아간다.
 */
object ChatAppearanceState {

    /** 커스텀 배경색을 실제로 적용할지. 꺼져 있으면 기존 기본 어두운 배경을 그대로 쓴다. */
    var customBackgroundEnabled: Boolean = false

    /** 0..360 */
    var hue: Float = 220f
        set(value) { field = value.coerceIn(0f, 360f) }

    /** 0..1 */
    var saturation: Float = 0.55f
        set(value) { field = value.coerceIn(0f, 1f) }

    /** 0..1 */
    var value: Float = 0.12f
        set(value) { field = value.coerceIn(0f, 1f) }

    /** 지금 선택된 색의 RGB(0xRRGGBB, 알파 없음). */
    fun rgb(): Int = hsvToRgb(hue, saturation, value)

    fun hexString(): String = "#%06X".format(rgb())

    /**
     * 패널이 실제로 그릴 배경색. 커스텀이 꺼져 있으면 [defaultArgb] 를 그대로 돌려주고,
     * 켜져 있으면 그 알파값(투명도)은 유지한 채 RGB 만 선택한 색으로 바꾼다 — 알파까지
     * 사용자가 고르게 하면 패널이 너무 투명해지거나 완전히 불투명해져 뒤 배경(게임
     * 화면)과 구분이 안 되는 사고가 나기 쉽다.
     */
    fun panelBackgroundArgb(defaultArgb: Int): Int {
        if (!customBackgroundEnabled) return defaultArgb
        val alpha = (defaultArgb ushr 24) and 0xFF
        return (alpha shl 24) or (rgb() and 0xFFFFFF)
    }

    /** SV 사각형·색상 막대를 그릴 때 임의의 h/s/v 조합을 색으로 바꾸는 데 쓴다 — 지금 선택값과는 별개. */
    fun hsvToRgb(h: Float, s: Float, v: Float): Int {
        val c = v * s
        val x = c * (1 - abs((h / 60f) % 2 - 1))
        val m = v - c
        val (r1, g1, b1) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val r = ((r1 + m) * 255).toInt().coerceIn(0, 255)
        val g = ((g1 + m) * 255).toInt().coerceIn(0, 255)
        val b = ((b1 + m) * 255).toInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }
}
