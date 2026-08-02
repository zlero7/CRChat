package kr.teamcraft.crchat.protocol

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * JSON 으로 시작한다 (12.5 "측정 전 바이너리 전환 금지"). 바이너리 코덱은
 * 트래픽을 측정한 뒤에나 고려한다 (12.3).
 */
object PacketCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(packet: S2C): ByteArray = json.encodeToString(packet).encodeToByteArray()

    fun decodeS2C(bytes: ByteArray): S2C = json.decodeFromString(bytes.decodeToString())

    fun encode(packet: C2S): ByteArray = json.encodeToString(packet).encodeToByteArray()

    fun decodeC2S(bytes: ByteArray): C2S = json.decodeFromString(bytes.decodeToString())
}
