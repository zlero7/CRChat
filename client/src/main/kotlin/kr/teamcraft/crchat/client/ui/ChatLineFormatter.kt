package kr.teamcraft.crchat.client.ui

import kr.teamcraft.crchat.client.state.ChatState
import kr.teamcraft.crchat.protocol.ChatMessage
import kr.teamcraft.crchat.protocol.Segment
import net.minecraft.text.Text
import net.minecraft.text.TextColor

/**
 * 상시 오버레이([ChatOverlayRenderer])와 편집 화면([CRChatScreen])이
 * 같은 한 줄 포맷을 쓰도록 공유한다. 아이템 링크·멘션 렌더는 다음 단계(6장).
 */
object ChatLineFormatter {

    fun format(message: ChatMessage): Text {
        val body = message.segments.filterIsInstance<Segment.Text>().joinToString("") { it.value }
        val color = message.segments.filterIsInstance<Segment.Text>().firstOrNull()?.color
        val base = Text.literal("${ChatState.displayNameFor(message)}: $body")
        val parsed = color?.let { hex ->
            runCatching { TextColor.fromRgb(hex.removePrefix("#").toInt(16)) }.getOrNull()
        }
        return parsed?.let { c -> base.styled { style -> style.withColor(c) } } ?: base
    }
}
