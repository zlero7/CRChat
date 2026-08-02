package kr.teamcraft.crchat.plugin.sender

import kr.teamcraft.crchat.protocol.SenderDef
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 발신자 참조 ID 발급. (12.2(2))
 *
 * 지금은 **서버 로컬**로만 발급한다. 네트워크 전역 발급(Redis `INCR`, 2.2)은
 * 멀티서버가 붙는 Phase 8 에서 교체한다 — 단일 서버 안에서는 로컬 발급도
 * 이미 일관된 의미를 가지므로 지금 당장 틀린 것은 아니다.
 *
 * "처음 등장할 때 1회만 보낸다"는 캐싱 최적화는 아직 넣지 않는다 — 지금은 메시지마다
 * [SenderDef] 를 동봉한다. 최적화는 Phase 10 이다.
 */
class SenderRegistry {

    private val ids = ConcurrentHashMap<UUID, Int>()
    private val counter = AtomicInteger(1)

    fun idFor(player: Player): Int = ids.computeIfAbsent(player.uniqueId) { counter.getAndIncrement() }

    /** `ChatDecorator` 연동(등급·칭호·색)은 Phase 15/CRRPGCore 통합에서 붙는다. */
    fun defFor(player: Player): SenderDef = SenderDef(
        id = idFor(player),
        uuid = player.uniqueId.toString(),
        name = player.name,
        displayName = player.name,
    )

    fun remove(player: Player) {
        ids.remove(player.uniqueId)
    }
}
