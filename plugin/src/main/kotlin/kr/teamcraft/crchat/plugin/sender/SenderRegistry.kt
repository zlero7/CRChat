package kr.teamcraft.crchat.plugin.sender

import kr.teamcraft.crchat.plugin.network.ChatRelayDatabase
import kr.teamcraft.crchat.protocol.SenderDef
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 발신자 참조 ID 발급. (12.2(2))
 *
 * [db] 가 있으면(크로스서버 연동 켜짐, 13장 Phase 8) `crchat_sender_ids` 테이블에서
 * UUID 당 하나씩 **네트워크 전역** ID를 받아온다 — 다른 서버에 있는 사람이 보낸
 * 메시지를 릴레이로 받았을 때도 같은 사람은 항상 같은 ID를 갖는다. 서버마다 로컬로
 * 카운터를 돌리면 서버 A 의 1번과 서버 B 의 1번이 완전히 다른 사람일 수 있어서,
 * 클라가 갖고 있는 "ID -> 발신자" 캐시가 서로 다른 서버 메시지를 섞어 받는 순간
 * 뒤섞여 버린다.
 *
 * [db] 가 없으면(단일 서버) 기존처럼 로컬 카운터만 쓴다 — 단일 서버 안에서는
 * 로컬 발급도 이미 일관된 의미를 가지므로 문제 없다.
 */
class SenderRegistry(
    private val plugin: JavaPlugin,
    private val db: ChatRelayDatabase? = null,
) {

    private val ids = ConcurrentHashMap<UUID, Int>()
    private val localCounter = AtomicInteger(1)

    /**
     * 접속 시 비동기로 미리 채워 둔다 — 실제 채팅을 보내는 시점([idFor])엔 캐시만
     * 읽도록 해서, 메인 스레드가 DB 왕복 때문에 멈추는 일을 최대한 피한다.
     */
    fun prewarm(player: Player) {
        val database = db ?: return
        val uuid = player.uniqueId
        Bukkit.getScheduler().runTaskAsynchronously(
            plugin,
            Runnable {
                fetchOrCreateGlobalId(database, uuid)?.let { ids[uuid] = it }
            },
        )
    }

    /** [prewarm] 이 아직 못 끝났으면(드문 경쟁 상황) 여기서 동기로라도 구한다. */
    fun idFor(player: Player): Int = ids.getOrPut(player.uniqueId) {
        db?.let { fetchOrCreateGlobalId(it, player.uniqueId) } ?: localCounter.getAndIncrement()
    }

    private fun fetchOrCreateGlobalId(database: ChatRelayDatabase, uuid: UUID): Int? = try {
        database.dataSource.connection.use { conn ->
            conn.prepareStatement("INSERT IGNORE INTO crchat_sender_ids (uuid) VALUES (?)").use { ps ->
                ps.setString(1, uuid.toString())
                ps.executeUpdate()
            }
            conn.prepareStatement("SELECT id FROM crchat_sender_ids WHERE uuid = ?").use { ps ->
                ps.setString(1, uuid.toString())
                ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else null }
            }
        }
    } catch (e: Exception) {
        plugin.logger.warning("${uuid}: 전역 발신자 ID 조회 실패 — 로컬 ID로 대체합니다: ${e.message}")
        null
    }

    /** `ChatDecorator` 연동(등급·칭호·색)은 Phase 15/CRRPGCore 통합에서 붙는다. */
    fun defFor(player: Player): SenderDef = SenderDef(
        id = idFor(player),
        uuid = player.uniqueId.toString(),
        name = player.name,
        displayName = plainDisplayName(player),
    )

    /**
     * 다른 플러그인이 `setDisplayName`/`displayName(...)` 으로 지정한 닉네임을 색상 코드를
     * 뗀 순수 텍스트로 가져온다. 비어 있으면 기본 닉네임으로 대체.
     */
    private fun plainDisplayName(player: Player): String {
        val plain = PlainTextComponentSerializer.plainText().serialize(player.displayName())
        val stripped = plain.replace(Regex("[§&][0-9a-fk-orA-FK-OR]"), "").trim()
        return stripped.ifEmpty { player.name }
    }

    fun remove(player: Player) {
        ids.remove(player.uniqueId)
    }
}
