package kr.teamcraft.crchat.client.state

import kr.teamcraft.crchat.protocol.ChatMessage
import kr.teamcraft.crchat.protocol.SenderDef

/**
 * 클라 세션 동안만 유지되는 메시지·발신자 저장소. (기획서 10장 MessageStore, 4.5 세션 한정 정책)
 * 디스크에 저장하지 않는다 — 접속 종료 시 전부 사라진다.
 */
object ChatState {

    // TODO: config.yml 의 history-limit(기본 5000) 도입 시 그 값으로 교체한다.
    private const val HISTORY_LIMIT = 500

    val messages: ArrayDeque<ChatMessage> = ArrayDeque()
    val senders: MutableMap<Int, SenderDef> = mutableMapOf()

    fun addMessage(message: ChatMessage) {
        messages.addLast(message)
        if (messages.size > HISTORY_LIMIT) messages.removeFirst()
    }

    fun addSenders(defs: List<SenderDef>) {
        for (def in defs) senders[def.id] = def
    }

    fun reset() {
        messages.clear()
        senders.clear()
    }

    /** senderId 가 없으면(시스템·공지 메시지) 채널명을 대괄호로 대신 보여준다. */
    fun displayNameFor(message: ChatMessage): String =
        message.senderId?.let { senders[it]?.displayName } ?: "[${message.channel}]"
}
