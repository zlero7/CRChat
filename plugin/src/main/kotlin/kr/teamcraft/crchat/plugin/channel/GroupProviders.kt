package kr.teamcraft.crchat.plugin.channel

import kr.teamcraft.crchat.api.GroupProvider
import kr.teamcraft.crchat.api.GroupType
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID

/**
 * 파티의 기본 구현. 바닐라 스코어보드 팀을 파티로 취급한다. (4.6)
 *
 * 팀 변경 이벤트가 없으므로 **캐시하지 말고 매번 조회**한다 — 맵 조회라 저렴하다.
 * [registry] 를 직접 참조하는 이유는 `/crchat reload` 로 `team-prefix` 가 바뀌어도
 * 이 provider 를 다시 등록할 필요 없이 즉시 반영되게 하기 위해서다.
 *
 * ⚠ 스코어보드 팀은 서버별로 독립이라 이 구현은 **같은 서버 안에서만** 동작한다.
 * 파티원이 로비/필드에 흩어지면 파티 채팅이 전달되지 않는다 (4.6 멀티서버 한계).
 */
class VanillaTeamPartyProvider(private val registry: ChannelRegistry) : GroupProvider {

    override val type = GroupType.PARTY

    override fun groupId(player: Player): String? =
        Bukkit.getScoreboardManager()
            .mainScoreboard
            .getEntryTeam(player.name)
            ?.name
            ?.takeIf { it.startsWith(registry.teamPrefix) }

    override fun members(groupId: String): Collection<UUID> =
        Bukkit.getScoreboardManager()
            .mainScoreboard
            .getTeam(groupId)
            ?.entries
            ?.map { Bukkit.getOfflinePlayer(it).uniqueId }
            ?: emptyList()

    override fun displayName(groupId: String): String = groupId.removePrefix(registry.teamPrefix)
}

/** 길드는 아직 미구현이므로 provider 부재 상태로 둔다. 등록하지 않으면 탭이 숨겨진다 (4.6) */
object NullGroupProvider : GroupProvider {
    override val type = GroupType.GUILD
    override fun groupId(player: Player): String? = null
    override fun members(groupId: String): Collection<UUID> = emptyList()
    override fun displayName(groupId: String): String = groupId
}
