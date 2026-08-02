package kr.teamcraft.crchat.plugin.channel

import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 채널별 쿨타임 최소 뼈대. (7장)
 * 유사도·금칙어·광고 필터 체인은 Phase 7 에서 붙는다 — 지금은 도배 방지만 있다.
 */
class CooldownTracker {

    private val lastSent = ConcurrentHashMap<Pair<UUID, Channel>, Long>()

    /** 남은 대기 시간(초). 0 이하면 지금 보낼 수 있다. */
    fun remaining(player: Player, channel: Channel, cooldownSeconds: Double): Double {
        if (cooldownSeconds <= 0.0) return 0.0
        val last = lastSent[player.uniqueId to channel] ?: return 0.0
        val elapsed = (System.currentTimeMillis() - last) / 1000.0
        return (cooldownSeconds - elapsed).coerceAtLeast(0.0)
    }

    fun markSent(player: Player, channel: Channel) {
        lastSent[player.uniqueId to channel] = System.currentTimeMillis()
    }
}
