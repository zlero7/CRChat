package kr.teamcraft.crchat.plugin.sender

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextReplacementConfig
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerAdvancementDoneEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerKickEvent
import org.bukkit.event.player.PlayerQuitEvent

/**
 * 입장/퇴장/사망/발전과제 같은 바닐라 시스템 메시지에 들어가는 기본 닉네임을 디스플레이
 * 닉네임으로 바꿔 준다. 다른 플러그인이 닉네임을 정한 뒤에 보도록 MONITOR 우선순위로 처리하고,
 * 메시지 자체(번역 키·색·다른 플러그인이 바꾼 문구)는 그대로 두고 이름 부분만 치환한다.
 */
class DisplayNameMessages : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        event.joinMessage(event.joinMessage()?.let { swap(it, event.player) })
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        event.quitMessage(event.quitMessage()?.let { swap(it, event.player) })
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onKick(event: PlayerKickEvent) {
        event.leaveMessage(swap(event.leaveMessage(), event.player))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDeath(event: PlayerDeathEvent) {
        event.deathMessage(event.deathMessage()?.let { swap(it, event.player) })
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onAdvancement(event: PlayerAdvancementDoneEvent) {
        event.message(event.message()?.let { swap(it, event.player) })
    }

    private fun swap(message: Component, player: Player): Component {
        val display = PlainTextComponentSerializer.plainText().serialize(player.displayName())
            .replace(Regex("[§&][0-9a-fk-orA-FK-OR]"), "").trim()
        if (display.isEmpty() || display == player.name) return message
        return message.replaceText(
            TextReplacementConfig.builder()
                .matchLiteral(player.name)
                .replacement(display)
                .build(),
        )
    }
}
