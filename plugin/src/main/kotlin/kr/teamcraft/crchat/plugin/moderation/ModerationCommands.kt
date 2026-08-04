package kr.teamcraft.crchat.plugin.moderation

import kr.teamcraft.crchat.common.mute.DurationParser
import kr.teamcraft.crchat.common.mute.MuteDuration
import kr.teamcraft.crchat.plugin.channel.Channel
import kr.teamcraft.crchat.plugin.network.ChatRelay
import kr.teamcraft.crchat.plugin.network.RelayEvent
import kr.teamcraft.crchat.protocol.CHANNEL_ID
import kr.teamcraft.crchat.protocol.PacketCodec
import kr.teamcraft.crchat.protocol.S2C
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.plugin.java.JavaPlugin
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 뮤트·채팅 동결·채팅 청소 관리자 명령어. (기획서 7.3 + 사용자 요청 기능들)
 *
 * [muteStore]·[freezeStore] 는 항상 값이 있다 — 크로스서버 연동(MySQL)이 있으면
 * 네트워크 전역([MuteService]/[FreezeService]), 없으면 이 서버 로컬 파일
 * ([LocalMuteStore]/[LocalFreezeStore])이다. 단일 서버에서도 이 명령어들이
 * 그대로 되어야 한다는 요청으로 [MuteStore]/[FreezeStore] 인터페이스로 나눴다 —
 * 어느 쪽인지는 [scopeNote] 로만 사용자에게 알려준다.
 *
 * `/채팅청소`는 [chatRelay] 유무와 무관하게 이 서버 온라인 유저에게는 항상 먹힌다 —
 * [chatRelay] 가 없으면 그냥 다른 서버로는 안 퍼질 뿐이다.
 */
class ModerationCommands(
    private val plugin: JavaPlugin,
    private val muteStore: MuteStore,
    private val freezeStore: FreezeStore,
    private val chatRelay: ChatRelay?,
) : CommandExecutor, TabCompleter {

    private val dateFormat = SimpleDateFormat("MM-dd HH:mm")

    /** 뮤트·동결이 이 서버에만 적용되는지(MySQL 없음) 확인해 명령어 결과에 덧붙인다. */
    private val scopeNote: String =
        if (muteStore is MuteService) "" else " §7(이 서버에만 적용 — 크로스서버 연동 꺼짐)"

    /** NOTICE·SYSTEM 은 수신 전용이라 애초에 뮤트 대상이 아니다 — [handleMute] 의 채널 검증과 맞춘다. */
    private val muteableChannels = listOf(Channel.GLOBAL, Channel.GENERAL, Channel.GUILD, Channel.PARTY).map { it.id }
    private val durationExamples = listOf("10m", "1h", "3d", "perm")
    private val muteReasonExamples = listOf("도배", "욕설", "광고", "부적절한언어")
    private val freezeReasonExamples = listOf("도배공격", "긴급점검", "욕설민원폭주")

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        when (command.name) {
            "뮤트" -> handleMute(sender, args)
            "언뮤트" -> handleUnmute(sender, args)
            "뮤트목록" -> handleMuteList(sender, args)
            "채팅청소" -> handleClear(sender)
            "채팅동결" -> handleFreeze(sender, args)
            else -> return false
        }
        return true
    }

    /**
     * 닉네임은 온라인 플레이어로, 기간·채널·사유는 예시 값으로 제안한다 — 특히 기간
     * (`10m`/`1h`/`3d`/`perm`)과 사유는 어차피 자유 입력이라 "정답"은 없지만, 예시가
     * 안 뜨면 관리자가 매번 형식을 기억해야 해서 실수하기 쉽다.
     */
    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        val current = args.lastOrNull().orEmpty()
        val candidates = when (command.name) {
            "뮤트" -> when (args.size) {
                1 -> onlinePlayerNames()
                2 -> durationExamples
                3 -> muteableChannels
                else -> muteReasonExamples
            }
            "언뮤트" -> when (args.size) {
                1 -> onlinePlayerNames()
                2 -> muteableChannels
                else -> emptyList()
            }
            "뮤트목록" -> if (args.size == 1) onlinePlayerNames() else emptyList()
            "채팅동결" -> if (args.size == 1) freezeReasonExamples else emptyList()
            else -> emptyList()
        }
        return candidates.filter { it.startsWith(current, ignoreCase = true) }
    }

    private fun onlinePlayerNames(): List<String> = Bukkit.getOnlinePlayers().map { it.name }

    /** 접속 기록이 있어야(또는 지금 온라인이어야) 유효한 대상으로 본다 — 오타로 아무 이름이나 뮤트되는 사고 방지. */
    @Suppress("DEPRECATION")
    private fun resolveTarget(sender: CommandSender, name: String) =
        Bukkit.getOfflinePlayer(name).takeIf { it.hasPlayedBefore() || it.isOnline }
            ?: run {
                sender.sendMessage("§c$name: 접속 기록이 없는 닉네임입니다.")
                null
            }

    private fun handleMute(sender: CommandSender, args: Array<out String>) {
        if (args.size < 2) {
            sender.sendMessage("사용법: /뮤트 <닉네임> <기간(10m/1h/3d/perm)> [채널] [사유]")
            return
        }
        val target = resolveTarget(sender, args[0]) ?: return

        val duration = DurationParser.parse(args[1])
        if (duration == null) {
            sender.sendMessage("§c기간 형식이 잘못됐습니다 (예: 10m, 1h, 3d, perm).")
            return
        }

        // 세 번째 인자가 실제 채널 이름이면 채널별 뮤트, 아니면 그 단어부터 전부 사유다.
        var argIndex = 2
        var channel: Channel? = null
        if (args.size > argIndex) {
            val maybeChannel = Channel.fromId(args[argIndex])
            if (maybeChannel != null && maybeChannel != Channel.NOTICE && maybeChannel != Channel.SYSTEM) {
                channel = maybeChannel
                argIndex++
            }
        }
        val reason = args.drop(argIndex).joinToString(" ").ifBlank { null }

        val untilMillis = when (duration) {
            is MuteDuration.Permanent -> null
            is MuteDuration.Temporary -> System.currentTimeMillis() + duration.seconds * 1000
        }

        muteStore.mute(target.uniqueId, channel?.id, untilMillis, reason, sender.name)

        val durationText = if (duration is MuteDuration.Permanent) "영구" else args[1]
        val channelText = channel?.id ?: "전체"
        sender.sendMessage(
            "§a${target.name ?: args[0]} 님을 [$channelText] 채널에서 $durationText 뮤트했습니다" +
                (reason?.let { " · 사유: $it" } ?: "") + scopeNote,
        )
    }

    private fun handleUnmute(sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) {
            sender.sendMessage("사용법: /언뮤트 <닉네임> [채널]")
            return
        }
        val target = resolveTarget(sender, args[0]) ?: return

        val channel = args.getOrNull(1)?.let { Channel.fromId(it) }
        if (args.size > 1 && channel == null) {
            sender.sendMessage("§c알 수 없는 채널입니다: ${args[1]}")
            return
        }

        val count = muteStore.unmute(target.uniqueId, channel?.id)
        if (count == 0) {
            sender.sendMessage("§e${args[0]}: 해제할 활성 뮤트가 없습니다.")
        } else {
            sender.sendMessage("§a${args[0]} 님의 뮤트를 해제했습니다 (${count}건).$scopeNote")
        }
    }

    private fun handleMuteList(sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) {
            sender.sendMessage("사용법: /뮤트목록 <닉네임>")
            return
        }
        val target = resolveTarget(sender, args[0]) ?: return

        val history = muteStore.history(target.uniqueId)
        if (history.isEmpty()) {
            sender.sendMessage("${args[0]}: 뮤트 이력이 없습니다.")
            return
        }

        sender.sendMessage("§7── ${args[0]} 뮤트 이력 (최근 ${history.size}건) ──")
        val now = System.currentTimeMillis()
        for (record in history) {
            val status = when {
                record.revokedAt != null -> "§7[해제됨]"
                record.until != null && record.until <= now -> "§7[만료됨]"
                else -> "§c[활성]"
            }
            val untilText = record.until?.let { dateFormat.format(Date(it)) } ?: "영구"
            val channelText = record.channel ?: "전체"
            sender.sendMessage(
                "  $status §f[$channelText] ~$untilText · ${record.issuedBy} · ${record.reason ?: "사유 없음"}",
            )
        }
    }

    private fun handleClear(sender: CommandSender) {
        val packet = PacketCodec.encode(S2C.ClearMessages)
        for (player in Bukkit.getOnlinePlayers()) {
            player.sendPluginMessage(plugin, CHANNEL_ID, packet)
        }
        chatRelay?.publish("__control__", RelayEvent.ClearChat)
        sender.sendMessage(
            "§a채팅 로그를 정리했습니다" + if (chatRelay != null) " (다른 서버 포함)." else " (이 서버만 — 크로스서버 연동이 꺼져 있습니다).",
        )
    }

    private fun handleFreeze(sender: CommandSender, args: Array<out String>) {
        val newFrozen = !freezeStore.state().frozen
        val reason = args.joinToString(" ").ifBlank { null }
        freezeStore.setFrozen(newFrozen, reason, sender.name)

        if (newFrozen) {
            sender.sendMessage(
                "§c채팅을 동결했습니다" + (reason?.let { " · 사유: $it" } ?: "") + ". 다시 실행하면 해제됩니다.$scopeNote",
            )
        } else {
            sender.sendMessage("§a채팅 동결을 해제했습니다.$scopeNote")
        }
    }
}
