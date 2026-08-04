package kr.teamcraft.crchat.plugin

import io.papermc.paper.event.player.AsyncChatEvent
import kr.teamcraft.crchat.api.GroupProvider
import kr.teamcraft.crchat.common.sanitize.TextSanitizer
import kr.teamcraft.crchat.plugin.channel.Channel
import kr.teamcraft.crchat.plugin.channel.ChannelRegistry
import kr.teamcraft.crchat.plugin.channel.ChannelResolver
import kr.teamcraft.crchat.plugin.channel.CooldownTracker
import kr.teamcraft.crchat.plugin.channel.VanillaTeamPartyProvider
import kr.teamcraft.crchat.plugin.moderation.FreezeService
import kr.teamcraft.crchat.plugin.moderation.ModerationCommands
import kr.teamcraft.crchat.plugin.moderation.MuteService
import kr.teamcraft.crchat.plugin.network.ChatRelay
import kr.teamcraft.crchat.plugin.network.ChatRelayDatabase
import kr.teamcraft.crchat.plugin.network.NetworkConfig
import kr.teamcraft.crchat.plugin.network.RelayEvent
import kr.teamcraft.crchat.plugin.sender.SenderRegistry
import kr.teamcraft.crchat.protocol.C2S
import kr.teamcraft.crchat.protocol.CHANNEL_ID
import kr.teamcraft.crchat.protocol.ChatMessage
import kr.teamcraft.crchat.protocol.PROTOCOL_VERSION
import kr.teamcraft.crchat.protocol.PacketCodec
import kr.teamcraft.crchat.protocol.S2C
import kr.teamcraft.crchat.protocol.Segment
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRegisterChannelEvent
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.plugin.messaging.PluginMessageListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Phase 0~1 (기획서 13장) — HELLO 핸드셰이크 + 채널 등록·권한·라우팅 +
 * 바닐라 채팅 취소. 필터 체인·뮤트·저장은 이후 Phase 다.
 *
 * Phase 8(크로스서버)도 여기 붙어 있다 — 기획서는 Redis Pub/Sub 을 상정했지만,
 * 이 서버 환경은 MySQL 을 이미 갖추고 있어([kr.teamcraft.crchat.plugin.network.ChatRelayDatabase])
 * 폴링 기반으로 대체했다. 길드·파티는 아직 서버 로컬 판정만 가능해 [relayedChannels] 에서 빠져 있다.
 */
class CRChatPlugin : JavaPlugin(), Listener, PluginMessageListener, CommandExecutor {

    private val channelRegistry = ChannelRegistry(this)
    private val channelResolver = ChannelResolver(channelRegistry)
    private val cooldowns = CooldownTracker()

    /**
     * 네트워크 설정(server-id, DB 접속 정보). `senders`·`chatRelay` 가 여기 값이
     * 있어야 만들어지므로 생성자가 아니라 [onEnable] 에서 채운다.
     */
    private lateinit var networkConfig: NetworkConfig
    private lateinit var senders: SenderRegistry

    /**
     * 크로스서버 연동(13장 Phase 8)이 꺼져 있거나 DB 접속에 실패하면 전부 null 로
     * 남고, 로컬 전달만 동작한다. 뮤트·채팅 동결은 같은 MySQL 을 그대로 쓴다(7.2) —
     * DB가 없으면 그 두 기능도 같이 못 쓴다([ModerationCommands] 가 안내한다).
     */
    private var relayDb: ChatRelayDatabase? = null
    private var chatRelay: ChatRelay? = null
    private var muteService: MuteService? = null
    private var freezeService: FreezeService? = null

    /** 다른 서버까지 퍼져야 하는 채널. 길드·파티는 아직 서버 로컬 판정만 가능해 제외한다(13장 Phase 8 참고). */
    private val relayedChannels = setOf(Channel.GLOBAL, Channel.GENERAL)

    /** 접속 시 HELLO 를 보내고 응답을 기다리는 유저. 응답이 오면 제거된다. */
    private val awaitingHello = ConcurrentHashMap.newKeySet<UUID>()

    /** 핸드셰이크를 마친 유저만 바닐라 채팅을 가로챈다 — 모드 없는 유저는 그대로 둔다. */
    private val handshakeComplete = ConcurrentHashMap.newKeySet<UUID>()

    private val messageIdCounter = AtomicLong(System.currentTimeMillis())

    override fun onEnable() {
        channelRegistry.load()
        networkConfig = NetworkConfig.load(this)
        setUpNetwork()
        senders = SenderRegistry(this, relayDb)

        server.servicesManager.register(
            GroupProvider::class.java,
            VanillaTeamPartyProvider(channelRegistry),
            this,
            ServicePriority.Normal,
        )

        server.messenger.registerOutgoingPluginChannel(this, CHANNEL_ID)
        server.messenger.registerIncomingPluginChannel(this, CHANNEL_ID, this)
        server.pluginManager.registerEvents(this, this)
        getCommand("공지")?.setExecutor(this)

        val moderationCommands = ModerationCommands(this, muteService, freezeService, chatRelay)
        for (name in listOf("뮤트", "언뮤트", "뮤트목록", "채팅청소", "채팅동결")) {
            getCommand(name)?.setExecutor(moderationCommands)
        }

        logger.info("CRChat 활성화 (protocol v$PROTOCOL_VERSION, server-id=${networkConfig.serverId})")
    }

    /**
     * `network.enabled` 가 꺼져 있거나 MySQL 접속 자체가 안 되면 조용히 로컬 전달만
     * 쓰는 모드로 남는다 — DB 하나 때문에 서버 전체가 못 뜨면 안 된다. 뮤트·채팅 동결도
     * 같은 MySQL 을 쓰므로 이때 같이 못 켜진다 — [ModerationCommands] 가 그 경우
     * 안내 문구를 보여준다.
     */
    private fun setUpNetwork() {
        if (!networkConfig.networkEnabled) return
        // HikariDataSource 생성자 자체가 즉시 접속을 시도한다(fail-fast) — DB가 하나도
        // 안 떠 있으면 여기서부터 예외가 난다. 실제로 이 예외가 try 밖에 있어서
        // onEnable() 전체가 죽고 플러그인이 통째로 안 켜지는 버그가 있었다.
        try {
            val db = ChatRelayDatabase(networkConfig)
            db.ensureSchema()
            relayDb = db
            chatRelay = ChatRelay(this, db, networkConfig, ::onRelayedMessage).also { it.start() }
            muteService = MuteService(db).also { it.ensureSchema() }
            freezeService = FreezeService(db).also { it.ensureSchema() }
            logger.info("크로스서버 채팅 연동 활성화 (MySQL)")
        } catch (e: Exception) {
            logger.warning("크로스서버 채팅 연동 초기화 실패 — 로컬 전달만 사용합니다: ${e.message}")
            relayDb?.close()
            relayDb = null
            chatRelay = null
            muteService = null
            freezeService = null
        }
    }

    override fun onDisable() {
        chatRelay?.stop()
        relayDb?.close()
        server.messenger.unregisterOutgoingPluginChannel(this)
        server.messenger.unregisterIncomingPluginChannel(this)
        logger.info("CRChat 비활성화")
    }

    // ── 핸드셰이크 ────────────────────────────────────────

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        senders.prewarm(player)
        awaitingHello += player.uniqueId

        // 클라가 채널을 이미 등록해 둔 경우(드물게 join 이벤트보다 먼저 옴)만 여기서 바로 보낸다.
        // 보통은 아직 false 이고, onChannelRegister 에서 보낸다.
        if (CHANNEL_ID in player.listeningPluginChannels) {
            sendHello(player)
        }

        // 10초 무응답 -> 모드 없음으로 판정 (3.2 — 오탐 방지를 위해 3초에서 늘림).
        // no-mod-policy(킥/폴백)는 config.yml 이 들어오는 다음 단계에서 적용한다. 지금은 로그만.
        server.scheduler.runTaskLater(this, Runnable {
            if (awaitingHello.remove(player.uniqueId)) {
                logger.info("${player.name}: 10초 무응답 — 모드 미설치로 판정")
            }
        }, 200L)
    }

    /**
     * 클라가 `crchat:msg` 채널을 지원한다고 서버에 알려오는 시점. **`PlayerJoinEvent` 에서
     * 곧바로 HELLO를 보내면 이 등록이 아직 안 끝난 상태라 Paper 가 조용히 드롭한다** —
     * 실제로 재현된 버그: 전송 시점엔 `listeningPluginChannels`에 없다가 10초 뒤엔 있었다.
     * 그래서 등록 이벤트를 기다렸다가 보낸다.
     */
    @EventHandler
    fun onChannelRegister(event: PlayerRegisterChannelEvent) {
        if (event.channel != CHANNEL_ID) return
        val player = event.player
        if (player.uniqueId in awaitingHello) {
            sendHello(player)
        }
    }

    private fun sendHello(player: Player) {
        val bytes = PacketCodec.encode(helloPacket())
        player.sendPluginMessage(this, CHANNEL_ID, bytes)
        logger.info("${player.name}: HELLO 전송 (${bytes.size}바이트)")
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        awaitingHello -= event.player.uniqueId
        handshakeComplete -= event.player.uniqueId
        senders.remove(event.player)
    }

    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (channel != CHANNEL_ID) return
        when (val packet = PacketCodec.decodeC2S(message)) {
            is C2S.Hello -> handleClientHello(player, packet)
            is C2S.Send -> handleSend(player, packet)
            else -> logger.info("아직 처리하지 않는 패킷: $packet")
        }
    }

    private fun handleClientHello(player: Player, hello: C2S.Hello) {
        awaitingHello -= player.uniqueId

        if (hello.protocolVersion != PROTOCOL_VERSION) {
            logger.warning(
                "${player.name}: 프로토콜 버전 불일치 (client=${hello.protocolVersion}, server=$PROTOCOL_VERSION)",
            )
            player.kick(Component.text("클라이언트 버전이 맞지 않습니다. 런처를 업데이트해주세요."))
            return
        }

        handshakeComplete += player.uniqueId
        logger.info("${player.name}: 모드 확인됨 (mod v${hello.modVersion}) — 핸드셰이크 완료")
        val sessionInit = S2C.SessionInit(availableChannels = emptyList(), mutes = emptyList())
        player.sendPluginMessage(this, CHANNEL_ID, PacketCodec.encode(sessionInit))
    }

    // ── 바닐라 채팅 취소 (2.1) ──────────────────────────────

    /**
     * 모드 유저의 바닐라 채팅 패킷을 취소하고 `crchat:msg` 구조화 메시지로 대체한다.
     * 채널 선택 UI(Phase 4)가 아직 없으므로 지금은 전부 [Channel.GLOBAL] 로 보낸다.
     */
    @EventHandler
    fun onVanillaChat(event: AsyncChatEvent) {
        val player = event.player
        if (player.uniqueId !in handshakeComplete) return // 모드 없는 유저는 바닐라 그대로

        event.isCancelled = true
        val plain = PlainTextComponentSerializer.plainText().serialize(event.message())
        route(player, Channel.GLOBAL, plain)
    }

    // ── 채널 SEND 처리 ───────────────────────────────────

    private fun handleSend(player: Player, send: C2S.Send) {
        val channel = Channel.fromId(send.channel)
        if (channel == null) {
            sendError(player, "channel-unavailable")
            return
        }
        val text = send.segments.filterIsInstance<Segment.Text>().joinToString("") { it.value }
        route(player, channel, text)
    }

    private fun route(player: Player, channel: Channel, text: String) {
        val config = channelRegistry.config(channel)

        if (!channelResolver.isAvailable(channel, player)) {
            sendError(player, "channel-unavailable")
            return
        }

        val freeze = freezeService?.state()
        if (freeze?.frozen == true && !player.hasPermission("crchat.admin.freeze.bypass")) {
            sendSystemMessage(player, "채팅이 잠시 동결되었습니다" + (freeze.reason?.let { " · 사유: $it" } ?: ""))
            sendError(player, "frozen")
            return
        }

        val mute = muteService?.activeMute(player.uniqueId, channel.id)
        if (mute != null) {
            val remain = mute.until?.let { formatRemaining(it) } ?: "영구"
            val muteScope = mute.channel?.let { "[$it] " } ?: ""
            sendSystemMessage(player, "${muteScope}뮤트 상태입니다 · 남은 시간 $remain" + (mute.reason?.let { " · 사유: $it" } ?: ""))
            sendError(player, "muted")
            return
        }

        val remaining = cooldowns.remaining(player, channel, config.cooldownSeconds)
        if (remaining > 0.0) {
            sendError(player, "cooldown")
            return
        }

        // 정규화는 금칙어 검사(Phase 7)보다 먼저 돌아야 한다 — 순서가 반대면 제로폭
        // 문자를 끼워 넣는 것만으로 필터가 뚫린다. (7.4)
        val sanitized = TextSanitizer.clean(text)
        if (sanitized == null) {
            sendError(player, "blocked-empty")
            return
        }

        cooldowns.markSent(player, channel)

        val senderDef = senders.defFor(player)
        val chatMessage = ChatMessage(
            id = messageIdCounter.incrementAndGet(),
            channel = channel.id,
            senderId = senderDef.id,
            segments = listOf(Segment.Text(sanitized, color = config.color)),
            timestamp = System.currentTimeMillis(),
        )

        val senderDefBytes = PacketCodec.encode(S2C.SenderDefs(listOf(senderDef)))
        val messageBytes = PacketCodec.encode(S2C.Messages(listOf(chatMessage)))

        for (recipient in channelResolver.recipients(channel, player)) {
            recipient.sendPluginMessage(this, CHANNEL_ID, senderDefBytes)
            recipient.sendPluginMessage(this, CHANNEL_ID, messageBytes)
        }

        if (channel in relayedChannels) {
            chatRelay?.publish(channel.id, RelayEvent.Chat(chatMessage, senderDef))
        }
    }

    /**
     * 다른 서버가 MySQL 릴레이 테이블에 올린 이벤트를 이 서버 온라인 플레이어에게
     * 반영한다. 채팅 메시지([RelayEvent.Chat])는 GLOBAL·GENERAL 이 애초에 서버 전체가
     * 대상이라([ChannelResolver.recipients]) 여기서도 그대로 전체 온라인 플레이어에게
     * 보낸다 — 발신 서버가 아니므로 발신자를 다시 만들 수 없어 [RelayEvent.Chat.senderDef]
     * 를 그대로 실어 보낸다. `/채팅청소`([RelayEvent.ClearChat])는 다른 서버에서 실행됐어도
     * 이 서버 온라인 플레이어에게 똑같이 청소 지시를 보낸다.
     */
    private fun onRelayedMessage(event: RelayEvent) {
        when (event) {
            is RelayEvent.Chat -> {
                val senderDefBytes = event.senderDef?.let { PacketCodec.encode(S2C.SenderDefs(listOf(it))) }
                val messageBytes = PacketCodec.encode(S2C.Messages(listOf(event.chatMessage)))
                for (recipient in server.onlinePlayers) {
                    senderDefBytes?.let { recipient.sendPluginMessage(this, CHANNEL_ID, it) }
                    recipient.sendPluginMessage(this, CHANNEL_ID, messageBytes)
                }
            }

            RelayEvent.ClearChat -> {
                val bytes = PacketCodec.encode(S2C.ClearMessages)
                for (recipient in server.onlinePlayers) {
                    recipient.sendPluginMessage(this, CHANNEL_ID, bytes)
                }
            }
        }
    }

    private fun sendError(player: Player, reasonKey: String) {
        player.sendPluginMessage(this, CHANNEL_ID, PacketCodec.encode(S2C.Error(reasonKey)))
    }

    /** 뮤트·채팅 동결처럼 "왜 안 보내졌는지" 그 자리에서 바로 보여줘야 하는 안내에 쓴다. "system" 채널이라 [!] 접두로 뜬다. */
    private fun sendSystemMessage(player: Player, text: String) {
        val message = ChatMessage(
            id = messageIdCounter.incrementAndGet(),
            channel = "system",
            senderId = null,
            segments = listOf(Segment.Text(text)),
            timestamp = System.currentTimeMillis(),
        )
        player.sendPluginMessage(this, CHANNEL_ID, PacketCodec.encode(S2C.Messages(listOf(message))))
    }

    private fun formatRemaining(untilMillis: Long): String {
        val remainSeconds = ((untilMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        return when {
            remainSeconds < 60 -> "${remainSeconds}초"
            remainSeconds < 3600 -> "${remainSeconds / 60}분"
            remainSeconds < 86400 -> "${remainSeconds / 3600}시간"
            else -> "${remainSeconds / 86400}일"
        }
    }

    private fun helloPacket() = S2C.Hello(protocolVersion = PROTOCOL_VERSION, serverId = networkConfig.serverId)

    // ── 공지 (7.3, 4.1) ─────────────────────────────────

    /**
     * `/공지` 는 일반 [route] 경로를 타지 않는다 — 채널 소속·쿨타임 검사가 필요 없고,
     * 수신자가 "채널 멤버"가 아니라 **전 서버 전원**이기 때문이다.
     */
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (command.name != "공지") return false
        if (args.isEmpty()) {
            sender.sendMessage("사용법: /공지 <내용>")
            return true
        }
        broadcastNotice(args.joinToString(" "))
        return true
    }

    private fun broadcastNotice(rawText: String) {
        val sanitized = TextSanitizer.clean(rawText) ?: return
        val config = channelRegistry.config(Channel.NOTICE)
        val message = ChatMessage(
            id = messageIdCounter.incrementAndGet(),
            channel = Channel.NOTICE.id,
            senderId = null, // null = 시스템/운영자 발행 메시지 (3장)
            segments = listOf(Segment.Text(sanitized, color = config.color)),
            timestamp = System.currentTimeMillis(),
        )
        val bytes = PacketCodec.encode(S2C.Messages(listOf(message)))
        for (recipient in server.onlinePlayers) {
            recipient.sendPluginMessage(this, CHANNEL_ID, bytes)
        }
        chatRelay?.publish(Channel.NOTICE.id, RelayEvent.Chat(message, null))
        logger.info("[공지] $sanitized")
    }
}
