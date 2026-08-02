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
        // 공지는 발신자 표시 없이 내용만 보여준다 — 운영자 공지에 "[notice]:" 같은
        // 접두를 붙이면 정식 공지처럼 안 보인다.
        val plain = if (message.channel == "notice") body else "${ChatState.displayNameFor(message)}: $body"
        val base = Text.literal(plain)
        val parsed = color?.let { hex ->
            runCatching { TextColor.fromRgb(hex.removePrefix("#").toInt(16)) }.getOrNull()
        }
        return parsed?.let { c -> base.styled { style -> style.withColor(c) } } ?: base
    }

    /**
     * 메시지 하나가 렌더 줄 몇 개가 되는지. 공지는 위아래 빈 줄로 감싸 눈에 띄게 한다
     * (`null` = 빈 줄). 나머지는 한 줄 그대로.
     */
    fun expand(message: ChatMessage): List<Text?> =
        if (message.channel == "notice") listOf(null, format(message), null) else listOf(format(message))
}
