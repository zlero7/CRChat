package kr.teamcraft.crchat.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 리치 텍스트 세그먼트. (기획서 3장)
 *
 * 평문 문자열로 시작하지 않고 처음부터 세그먼트로 나눈 이유는 아이템 링크·멘션을
 * 나중에 끼워 넣을 수 없기 때문이다. 평문으로 시작하면 반드시 파싱 지옥이 온다.
 *
 * `Segment.Player` 는 두지 않는다 — [Mention] 과 필드가 동일해 구분 실익이 없다. (3.1)
 */
@Serializable
sealed class Segment {

    @Serializable
    @SerialName("text")
    data class Text(
        val value: String,
        val color: String? = null,
        val bold: Boolean = false,
    ) : Segment()

    @Serializable
    @SerialName("item")
    data class Item(
        val linkId: String,
        val display: String,
        val rarity: String,
    ) : Segment()

    @Serializable
    @SerialName("link")
    data class Link(
        val url: String,
        val display: String,
    ) : Segment()

    @Serializable
    @SerialName("mention")
    data class Mention(
        val uuid: String,
        val name: String,
    ) : Segment()
}
