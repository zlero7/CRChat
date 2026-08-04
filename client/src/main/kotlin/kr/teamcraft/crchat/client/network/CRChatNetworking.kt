package kr.teamcraft.crchat.client.network

import kr.teamcraft.crchat.client.state.ChatState
import kr.teamcraft.crchat.protocol.C2S
import kr.teamcraft.crchat.protocol.CHANNEL_ID
import kr.teamcraft.crchat.protocol.PROTOCOL_VERSION
import kr.teamcraft.crchat.protocol.PacketCodec
import kr.teamcraft.crchat.protocol.S2C
import kr.teamcraft.crchat.protocol.Segment
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import org.slf4j.LoggerFactory

/**
 * Walking skeleton (기획서 13장 Phase 0~2) — HELLO 왕복 + [ChatState] 적재.
 * 실제 렌더링은 `ui.CRChatScreen` 이 [ChatState] 를 읽어서 그린다 — 여기서는
 * 패킷을 상태로만 반영하고 화면에 직접 그리지 않는다 (관심사 분리).
 */
object CRChatNetworking {

    private val logger = LoggerFactory.getLogger("CRChat/Net")

    fun register() {
        PayloadTypeRegistry.playC2S().register(CRChatPayload.ID, CRChatPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(CRChatPayload.ID, CRChatPayload.CODEC)
        logger.info("페이로드 타입 등록 완료 (channel={})", CHANNEL_ID)

        ClientPlayNetworking.registerGlobalReceiver(CRChatPayload.ID) { payload, _ ->
            try {
                handle(PacketCodec.decodeS2C(payload.bytes))
            } catch (e: Exception) {
                // 여기서 조용히 삼켜지면 겉으로는 "무반응"으로만 보인다 — 반드시 로그를 남긴다.
                logger.error("페이로드 디코딩 실패: {}", String(payload.bytes), e)
            }
        }

        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> ChatState.reset() }
    }

    /** 현재 탭에서 발신. [channel] 은 [kr.teamcraft.crchat.client.ui.Tab.sendChannelId]. */
    fun send(channel: String, text: String) {
        val send = C2S.Send(channel = channel, segments = listOf(Segment.Text(text)))
        ClientPlayNetworking.send(CRChatPayload(PacketCodec.encode(send)))
    }

    private fun handle(packet: S2C) {
        when (packet) {
            is S2C.Hello -> {
                logger.info("서버 HELLO 수신 (protocol v{}, server={}) — 응답 전송", packet.protocolVersion, packet.serverId)
                // TODO: 모드 버전은 Phase 1 이후 fabric.mod.json 에서 읽어온다.
                val reply = C2S.Hello(protocolVersion = PROTOCOL_VERSION, modVersion = "0.1.0")
                ClientPlayNetworking.send(CRChatPayload(PacketCodec.encode(reply)))
            }

            is S2C.SessionInit ->
                logger.info("SESSION_INIT 수신 — 채널 {}개", packet.availableChannels.size)

            is S2C.SenderDefs -> ChatState.addSenders(packet.senders)

            is S2C.Messages -> for (message in packet.messages) ChatState.addMessage(message)

            is S2C.Error -> logger.info("서버 거부: {}", packet.reasonKey)

            // 관리자의 /채팅청소 — 로컬에 쌓인 채팅 로그를 전부 지운다.
            is S2C.ClearMessages -> ChatState.messages.clear()

            else -> logger.info("아직 처리하지 않는 패킷: {}", packet)
        }
    }
}
