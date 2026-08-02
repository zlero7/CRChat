package kr.teamcraft.crchat.client.network

import kr.teamcraft.crchat.protocol.CHANNEL_ID
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier

/**
 * `crchat:msg` 채널 전용 페이로드. 안의 바이트는 protocol 모듈의
 * `kr.teamcraft.crchat.protocol.PacketCodec` 가 만든 JSON 바이트다 — 여기서는
 * 그대로 실어 나르기만 한다.
 *
 * **`readByteArray()` 를 쓰면 안 된다.** Bukkit 플러그인 메시지는 길이 접두 없는
 * 순수 바이트로 온다. `readByteArray()`는 VarInt 길이를 먼저 읽으려 해서 깨진다.
 * 남은 바이트 전체를 읽어야 한다. (기획서 3.2)
 */
class CRChatPayload(val bytes: ByteArray) : CustomPayload {
    override fun getId(): CustomPayload.Id<CRChatPayload> = ID

    override fun equals(other: Any?): Boolean =
        this === other || (other is CRChatPayload && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = bytes.contentHashCode()

    companion object {
        val ID: CustomPayload.Id<CRChatPayload> = CustomPayload.Id(Identifier.of(CHANNEL_ID))

        val CODEC: PacketCodec<PacketByteBuf, CRChatPayload> = PacketCodec.of(
            { value, buf -> buf.writeBytes(value.bytes) },
            { buf -> CRChatPayload(ByteArray(buf.readableBytes()).also { buf.readBytes(it) }) },
        )
    }
}
