package kr.teamcraft.crchat.plugin.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kr.teamcraft.crchat.protocol.ChatMessage
import kr.teamcraft.crchat.protocol.SenderDef

/**
 * `crchat_relay` 테이블 한 행의 payload. 클라가 보는 `crchat:msg` 프로토콜과는
 * 별개다 — 이건 순전히 서버끼리 주고받는 내부 형식이라 `protocol` 모듈에 안 둔다.
 *
 * [senderDef] 를 같이 실어 보내는 이유: 받는 서버에는 원래 발신자의 `Player` 객체가
 * 없어서(다른 서버에 접속해 있으므로) [kr.teamcraft.crchat.plugin.sender.SenderRegistry]
 * 로 새로 만들 수가 없다 — 보낸 쪽이 만든 걸 그대로 실어 보내야 한다. 공지처럼
 * 발신자가 없는(null) 메시지도 있다.
 */
@Serializable
data class RelayEnvelope(
    val chatMessage: ChatMessage,
    val senderDef: SenderDef?,
)

object RelayCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(envelope: RelayEnvelope): String = json.encodeToString(envelope)

    fun decode(raw: String): RelayEnvelope = json.decodeFromString(raw)
}
