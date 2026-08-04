package kr.teamcraft.crchat.plugin.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kr.teamcraft.crchat.protocol.ChatMessage
import kr.teamcraft.crchat.protocol.SenderDef

/**
 * `crchat_relay` 테이블 한 행의 payload. 클라가 보는 `crchat:msg` 프로토콜과는
 * 별개다 — 이건 순전히 서버끼리 주고받는 내부 형식이라 `protocol` 모듈에 안 둔다.
 *
 * 처음엔 채팅 메시지 전용([Chat])이었는데, `/채팅청소` 를 다른 서버까지 퍼뜨려야
 * 해서([ClearChat]) sealed class 로 일반화했다 — 같은 폴링 큐를 그대로 재사용한다,
 * 별도 테이블·폴링 루프를 하나 더 만들 필요가 없게.
 */
@Serializable
sealed class RelayEvent {

    /**
     * [senderDef] 를 같이 실어 보내는 이유: 받는 서버에는 원래 발신자의 `Player` 객체가
     * 없어서(다른 서버에 접속해 있으므로) [kr.teamcraft.crchat.plugin.sender.SenderRegistry]
     * 로 새로 만들 수가 없다 — 보낸 쪽이 만든 걸 그대로 실어 보내야 한다. 공지처럼
     * 발신자가 없는(null) 메시지도 있다.
     */
    @Serializable
    @SerialName("chat")
    data class Chat(val chatMessage: ChatMessage, val senderDef: SenderDef?) : RelayEvent()

    /** `/채팅청소` — 받는 서버는 자기 온라인 플레이어 전원에게 `S2C.ClearMessages` 를 보낸다. */
    @Serializable
    @SerialName("clear_chat")
    data object ClearChat : RelayEvent()
}

object RelayCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(event: RelayEvent): String = json.encodeToString(event)

    fun decode(raw: String): RelayEvent = json.decodeFromString(raw)
}
