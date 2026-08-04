package kr.teamcraft.crchat.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 직렬화 왕복(encode -> decode -> 원본과 동일) 스모크 테스트. (부록 B.2)
 * 직렬화 버그는 런타임에 잡기 가장 괴로우므로 여기서 먼저 잡는다.
 */
class PacketCodecTest {

    @Test
    fun `채팅 메시지가 아이템 링크·멘션을 포함해 왕복한다`() {
        val message = ChatMessage(
            id = 123456789L,
            channel = "whisper:11111111-2222-3333-4444-555555555555",
            senderId = 17,
            segments = listOf(
                Segment.Text("안녕하세요 "),
                Segment.Mention(uuid = "aaaa", name = "제로"),
                Segment.Text(" 이거 "),
                Segment.Item(linkId = "xyz789", display = "천군투구", rarity = "epic"),
                Segment.Link(url = "https://example.com", display = "링크"),
            ),
            timestamp = 1_700_000_000_000L,
        )
        val packet = S2C.Messages(listOf(message))

        val decoded = PacketCodec.decodeS2C(PacketCodec.encode(packet))

        assertEquals(packet, decoded)
    }

    @Test
    fun `핸드셰이크 패킷이 왕복한다`() {
        val hello = S2C.Hello(protocolVersion = PROTOCOL_VERSION, serverId = "lobby")
        assertEquals(hello, PacketCodec.decodeS2C(PacketCodec.encode(hello)))
    }

    @Test
    fun `클라 발신 패킷이 왕복한다`() {
        val send = C2S.Send(channel = "global", segments = listOf(Segment.Text("보스 가자")))
        assertEquals(send, PacketCodec.decodeC2S(PacketCodec.encode(send)))
    }

    @Test
    fun `타이머 패킷이 왕복한다`() {
        val timer = S2C.Timer(key = "potion_cooldown", seconds = 12.0, label = "회복 물약 재사용 대기")
        assertEquals(timer, PacketCodec.decodeS2C(PacketCodec.encode(timer)))
    }

    @Test
    fun `세션 초기화 패킷이 채널 정의·뮤트 상태와 함께 왕복한다`() {
        val session = S2C.SessionInit(
            availableChannels = listOf(
                ChannelDef(id = "global", display = "전체", color = "#FFFFFF", available = true),
                ChannelDef(id = "system", display = "시스템", color = null, available = true),
            ),
            mutes = listOf(MuteInfo(channel = "global", until = 1_700_000_000_000L, reason = "욕설")),
        )
        assertEquals(session, PacketCodec.decodeS2C(PacketCodec.encode(session)))
    }

    @Test
    fun `발신자 정의 패킷이 왕복한다`() {
        val defs = S2C.SenderDefs(
            listOf(SenderDef(id = 17, uuid = "aaaa", name = "제로", displayName = "[일류] 제로", prefix = "[일류]")),
        )
        assertEquals(defs, PacketCodec.decodeS2C(PacketCodec.encode(defs)))
    }

    @Test
    fun `뮤트 갱신 패킷이 영구 뮤트를 포함해 왕복한다`() {
        val muteUpdate = S2C.MuteUpdate(
            mutes = listOf(MuteInfo(channel = null, until = null, reason = "도배")),
        )
        assertEquals(muteUpdate, PacketCodec.decodeS2C(PacketCodec.encode(muteUpdate)))
    }

    @Test
    fun `채널 가용성 갱신 패킷이 왕복한다`() {
        val update = S2C.ChannelUpdate(
            availableChannels = listOf(ChannelDef(id = "guild", display = "길드", color = "#FFE066", available = true)),
        )
        assertEquals(update, PacketCodec.decodeS2C(PacketCodec.encode(update)))
    }

    @Test
    fun `에러 패킷이 왕복한다`() {
        val error = S2C.Error(reasonKey = "cooldown")
        assertEquals(error, PacketCodec.decodeS2C(PacketCodec.encode(error)))
    }

    @Test
    fun `아이템 링크 요청 패킷이 슬롯 번호만 담아 왕복한다`() {
        val linkItem = C2S.LinkItem(slot = 8)
        assertEquals(linkItem, PacketCodec.decodeC2S(PacketCodec.encode(linkItem)))
    }

    @Test
    fun `발신자 조회 요청 패킷이 왕복한다`() {
        val request = C2S.RequestSender(senderId = 42)
        assertEquals(request, PacketCodec.decodeC2S(PacketCodec.encode(request)))
    }

    @Test
    fun `아이템 조회 요청 패킷이 왕복한다`() {
        val request = C2S.RequestItem(linkId = "xyz789")
        assertEquals(request, PacketCodec.decodeC2S(PacketCodec.encode(request)))
    }

    @Test
    fun `클라 핸드셰이크 패킷이 왕복한다`() {
        val hello = C2S.Hello(protocolVersion = PROTOCOL_VERSION, modVersion = "0.1.0")
        assertEquals(hello, PacketCodec.decodeC2S(PacketCodec.encode(hello)))
    }

    @Test
    fun `채팅청소 패킷이 왕복한다`() {
        val clear = S2C.ClearMessages
        assertEquals(clear, PacketCodec.decodeS2C(PacketCodec.encode(clear)))
    }
}
