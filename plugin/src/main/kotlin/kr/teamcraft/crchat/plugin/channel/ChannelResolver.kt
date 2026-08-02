package kr.teamcraft.crchat.plugin.channel

import kr.teamcraft.crchat.api.GroupProvider
import kr.teamcraft.crchat.api.GroupType
import org.bukkit.Bukkit
import org.bukkit.entity.Player

/**
 * 채널 가용성·수신 대상 계산. (2.4 신뢰 경계)
 *
 * 클라가 보낸 채널명도 여기서 전부 재검증한다 — 클라 필터는 표시 필터일 뿐이고,
 * 서버는 애초에 권한 없는 채널을 보내지 않는다.
 */
class ChannelResolver(private val registry: ChannelRegistry) {

    /** [channel] 이 지금 [player] 에게 열려 있는지 (권한 + 그룹 소속). */
    fun isAvailable(channel: Channel, player: Player): Boolean {
        val config = registry.config(channel)
        if (config.receiveOnly) return false
        if (config.permission != null && !player.hasPermission(config.permission)) return false
        val requires = config.requires ?: return true
        return groupProvider(requires).groupId(player) != null
    }

    /** [channel] 로 보낸 메시지를 받아야 할 온라인 플레이어. */
    fun recipients(channel: Channel, sender: Player): Collection<Player> = when (channel) {
        Channel.GLOBAL -> Bukkit.getOnlinePlayers()
        Channel.NOTICE -> emptyList() // receive-only. 발신은 /공지 명령어 전용 경로로만 (CRChatPlugin)
        Channel.SYSTEM -> emptyList() // receive-only. 발신 자체를 isAvailable 이 막는다
        Channel.GENERAL -> nearbyRecipients(sender)
        Channel.GUILD -> groupRecipients(GroupType.GUILD, sender)
        Channel.PARTY -> groupRecipients(GroupType.PARTY, sender)
    }

    /** 같은 월드에서 반경(radius, 4.1) 안에 있는 온라인 플레이어만 거른다. 거리 판정은 서버 몫이다 (2.4). */
    private fun nearbyRecipients(sender: Player): Collection<Player> {
        val radius = registry.config(Channel.GENERAL).radius ?: return emptyList()
        val origin = sender.location
        val radiusSquared = radius * radius
        return Bukkit.getOnlinePlayers().filter { candidate ->
            candidate.world == origin.world && candidate.location.distanceSquared(origin) <= radiusSquared
        }
    }

    private fun groupRecipients(type: GroupType, sender: Player): Collection<Player> {
        val provider = groupProvider(type)
        val groupId = provider.groupId(sender) ?: return emptyList()
        return provider.members(groupId).mapNotNull(Bukkit::getPlayer)
    }

    /**
     * `ServicesManager` 는 인터페이스 하나에 여러 구현이 등록될 수 있어 [GroupType] 으로
     * 직접 걸러야 한다 — 길드 provider 가 아직 미등록이면 [NullGroupProvider] 로 대체해
     * 호출부가 매번 null 체크를 하지 않게 한다 (4.6). 이 대체품은 ServicesManager 에
     * 등록하지 않는다 — 등록하면 "미등록 시 탭 숨김" 규칙이 깨진다.
     */
    private fun groupProvider(type: GroupType): GroupProvider =
        Bukkit.getServicesManager()
            .getRegistrations(GroupProvider::class.java)
            .map { it.provider }
            .firstOrNull { it.type == type }
            ?: NullGroupProvider
}
