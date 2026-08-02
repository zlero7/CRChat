package kr.teamcraft.crchat.api

import org.bukkit.entity.Player
import java.util.UUID

/** (기획서 4.6) */
enum class GroupType { PARTY, GUILD }

/**
 * `guild` / `party` 채널이 "이 플레이어가 어느 그룹 소속인가"를 조회하는 확장점.
 * CRChat 은 그룹 시스템을 직접 구현하지 않고 조회만 한다. `ServicesManager` 에
 * 등록하는 방식으로 외부에서 갈아끼운다. (4.6)
 *
 * 파티는 기본 구현(`VanillaTeamPartyProvider`)이 내장되어 미등록 상태가 없다.
 * 길드는 별도 시스템이 붙기 전까지 `NullGroupProvider` 로 비워 둔다.
 */
interface GroupProvider {
    val type: GroupType

    /** 소속 그룹 ID. 미소속이면 null */
    fun groupId(player: Player): String?

    /** 그룹원 전체 (오프라인 포함 가능, 발신 시 온라인만 필터) */
    fun members(groupId: String): Collection<UUID>

    /** 탭·표시용 이름 */
    fun displayName(groupId: String): String
}
