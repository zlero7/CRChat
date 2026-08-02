package kr.teamcraft.crchat.client.state

import kr.teamcraft.crchat.protocol.ChatMessage
import kr.teamcraft.crchat.protocol.Segment
import kr.teamcraft.crchat.protocol.SenderDef
import java.util.concurrent.atomic.AtomicLong

/**
 * 클라 세션 동안만 유지되는 메시지·발신자 저장소. (기획서 10장 MessageStore, 4.5 세션 한정 정책)
 * 디스크에 저장하지 않는다 — 접속 종료 시 전부 사라진다.
 */
object ChatState {

    // TODO: config.yml 의 history-limit(기본 5000) 도입 시 그 값으로 교체한다.
    private const val HISTORY_LIMIT = 500

    val messages: ArrayDeque<ChatMessage> = ArrayDeque()
    val senders: MutableMap<Int, SenderDef> = mutableMapOf()

    // 바닐라 ChatHud(4.7)에서 캡처한 메시지의 로컬 전용 id. 서버가 발급한 양수 id 와
    // 겹치지 않도록 음수로 내려간다 — 의미 있는 값은 아니고 그냥 구분용이다.
    private val vanillaIdCounter = AtomicLong(-1)

    fun addMessage(message: ChatMessage) {
        messages.addLast(message)
        if (messages.size > HISTORY_LIMIT) messages.removeFirst()
    }

    /** [kr.teamcraft.crchat.client.mixin.ChatHudMixin] 이 가로챈 접속 알림·명령어 피드백 등. "시스템" 채널로 들어간다. */
    fun addVanillaMessage(plainText: String) {
        if (plainText.isBlank()) return
        addMessage(
            ChatMessage(
                id = vanillaIdCounter.getAndDecrement(),
                channel = "system",
                senderId = null,
                segments = listOf(Segment.Text(plainText)),
                timestamp = System.currentTimeMillis(),
            ),
        )
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
