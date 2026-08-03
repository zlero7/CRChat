package kr.teamcraft.crchat.client.ui

import kr.teamcraft.crchat.client.state.ChatState
import kr.teamcraft.crchat.protocol.ChatMessage
import kr.teamcraft.crchat.protocol.Segment
import net.minecraft.client.font.TextRenderer
import net.minecraft.text.MutableText
import net.minecraft.text.OrderedText
import net.minecraft.text.Style
import net.minecraft.text.Text
import net.minecraft.text.TextColor
import net.minecraft.util.Language

/**
 * 상시 오버레이([ChatOverlayRenderer])와 편집 화면([CRChatScreen])이
 * 같은 한 줄 포맷을 쓰도록 공유한다. 아이템 링크·멘션 렌더는 다음 단계(6장).
 */
object ChatLineFormatter {

    private val SYSTEM_TAG_COLOR = TextColor.fromRgb(0xFFFF55) // 노랑 — "!" 만 이 색

    fun format(message: ChatMessage): Text {
        val body = message.segments.filterIsInstance<Segment.Text>().joinToString("") { it.value }
        return when (message.channel) {
            // 공지는 발신자 표시 없이 내용만 — 운영자 공지에 "[notice]:" 같은 접두가
            // 붙으면 정식 공지처럼 안 보인다.
            "notice" -> colored(Text.literal(body), messageColor(message))

            // 바닐라 캡처 메시지(접속 알림·명령어 피드백)는 "[!] 내용" 형태. "!" 만 노랑,
            // 대괄호·본문은 흰색(그리는 쪽에서 넘기는 기본색을 그대로 쓴다 — 따로 안 입힘).
            "system" -> Text.literal("[")
                .append(Text.literal("!").styled { it.withColor(SYSTEM_TAG_COLOR) })
                .append(Text.literal("] $body"))

            // 일반 채팅은 채널 색을 입히지 않고 흰색 그대로 둔다.
            else -> Text.literal("${ChatState.displayNameFor(message)}: $body")
        }
    }

    /**
     * 메시지 하나가 렌더 줄 몇 개가 되는지. 공지는 위아래 빈 줄로 감싸 눈에 띄게 한다
     * (`null` = 빈 줄). 나머지는 한 줄 그대로.
     */
    fun expand(message: ChatMessage): List<Text?> =
        if (message.channel == "notice") listOf(null, format(message), null) else listOf(format(message))

    /**
     * [expand] 의 각 줄을 패널 폭에 맞춰 자동 줄넘김한 실제 렌더 줄 목록으로 바꾼다.
     * 이게 없으면 패널을 좌우로 좁게 줄였을 때 글자가 배경 밖으로 그대로 삐져나온다
     * (세로 크기 조절은 보이는 줄 수만 바뀌니 문제가 없었지만, 가로는 원래 줄바꿈이 없었다).
     *
     * `maxWidth` 는 [ChatUiScale.wrapWidth] 로 계산한, 스케일 적용 전 좌표계 폭이어야 한다.
     */
    fun wrappedLinesFor(textRenderer: TextRenderer, message: ChatMessage, maxWidth: Int): List<OrderedText?> =
        expand(message).flatMap { line ->
            if (line == null) listOf(null) else wrap(textRenderer, line, maxWidth)
        }

    private fun wrap(textRenderer: TextRenderer, text: Text, maxWidth: Int): List<OrderedText> =
        textRenderer.textHandler.wrapLines(text, maxWidth, Style.EMPTY)
            .map { Language.getInstance().reorder(it) }

    private fun messageColor(message: ChatMessage): String? =
        message.segments.filterIsInstance<Segment.Text>().firstOrNull()?.color

    private fun colored(base: MutableText, hex: String?): Text {
        val color = hex?.let { runCatching { TextColor.fromRgb(it.removePrefix("#").toInt(16)) }.getOrNull() }
        return color?.let { c -> base.styled { style -> style.withColor(c) } } ?: base
    }
}
