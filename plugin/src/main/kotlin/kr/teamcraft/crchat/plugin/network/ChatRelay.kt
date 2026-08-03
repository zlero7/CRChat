package kr.teamcraft.crchat.plugin.network

import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask

/**
 * MySQL `crchat_relay` 테이블을 큐 삼아 서버 간 메시지를 주고받는다. (13장 Phase 8)
 *
 * Redis Pub/Sub 대신 **폴링**으로 구현했다 — 이 프로젝트는 이미 MySQL 을 갖추고
 * 있고(docker-compose), 채팅처럼 초 단위 지연이 문제없는 곳에서는 폴링만으로
 * 충분하다. 자기가 방금 쓴 행도 다음 폴링에 보이지만 `server_id` 로 걸러 무시한다 —
 * 자기 메시지는 이미 로컬로 즉시 전달했기 때문이다(발신 서버 UX는 지연이 없어야 한다).
 */
class ChatRelay(
    private val plugin: JavaPlugin,
    private val db: ChatRelayDatabase,
    private val config: NetworkConfig,
    private val onMessageReceived: (RelayEnvelope) -> Unit,
) {
    private var lastSeenId: Long = 0
    private var pollTask: BukkitTask? = null
    private var cleanupTask: BukkitTask? = null

    fun start() {
        // 과거 기록을 처음부터 재생하면 접속 즉시 지난 채팅이 쏟아진다 — 지금 시점의
        // 최댓값부터 커서를 시작해서 "그 이후" 것만 본다.
        lastSeenId = fetchMaxId()

        pollTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
            plugin, Runnable { poll() }, config.pollIntervalTicks, config.pollIntervalTicks,
        )
        cleanupTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
            plugin, Runnable { cleanup() }, 20L * 60, 20L * 60 * 5,
        )
    }

    fun stop() {
        pollTask?.cancel()
        cleanupTask?.cancel()
    }

    /** 반드시 비동기로 쓴다 — 메인 스레드에서 DB 왕복을 기다리면 서버가 순간 멈춘다. */
    fun publish(channel: String, envelope: RelayEnvelope) {
        Bukkit.getScheduler().runTaskAsynchronously(
            plugin,
            Runnable {
                try {
                    db.dataSource.connection.use { conn ->
                        conn.prepareStatement(
                            "INSERT INTO crchat_relay (server_id, channel, payload) VALUES (?, ?, ?)",
                        ).use { ps ->
                            ps.setString(1, config.serverId)
                            ps.setString(2, channel)
                            ps.setString(3, RelayCodec.encode(envelope))
                            ps.executeUpdate()
                        }
                    }
                } catch (e: Exception) {
                    plugin.logger.warning("크로스서버 메시지 발행 실패: ${e.message}")
                }
            },
        )
    }

    private fun fetchMaxId(): Long = try {
        db.dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT MAX(id) FROM crchat_relay").use { rs ->
                    if (rs.next()) rs.getLong(1) else 0L
                }
            }
        }
    } catch (e: Exception) {
        plugin.logger.warning("릴레이 커서 초기화 실패 — 0부터 시작합니다: ${e.message}")
        0L
    }

    private fun poll() {
        val rows = try {
            db.dataSource.connection.use { conn ->
                conn.prepareStatement(
                    "SELECT id, server_id, payload FROM crchat_relay WHERE id > ? ORDER BY id ASC LIMIT 200",
                ).use { ps ->
                    ps.setLong(1, lastSeenId)
                    ps.executeQuery().use { rs ->
                        val list = mutableListOf<Triple<Long, String, String>>()
                        while (rs.next()) list += Triple(rs.getLong(1), rs.getString(2), rs.getString(3))
                        list
                    }
                }
            }
        } catch (e: Exception) {
            plugin.logger.warning("크로스서버 메시지 폴링 실패: ${e.message}")
            return
        }
        if (rows.isEmpty()) return

        lastSeenId = rows.last().first
        val fromOtherServers = rows.filter { (_, serverId, _) -> serverId != config.serverId }
        if (fromOtherServers.isEmpty()) return

        // 플레이어에게 전달하려면 Bukkit API 를 써야 하므로 메인 스레드로 넘긴다.
        Bukkit.getScheduler().runTask(
            plugin,
            Runnable {
                for ((_, _, payload) in fromOtherServers) {
                    try {
                        onMessageReceived(RelayCodec.decode(payload))
                    } catch (e: Exception) {
                        plugin.logger.warning("릴레이 메시지 디코딩 실패: ${e.message}")
                    }
                }
            },
        )
    }

    private fun cleanup() {
        try {
            db.dataSource.connection.use { conn ->
                conn.prepareStatement(
                    "DELETE FROM crchat_relay WHERE created_at < (NOW() - INTERVAL ? HOUR)",
                ).use { ps ->
                    ps.setLong(1, config.retentionHours)
                    ps.executeUpdate()
                }
            }
        } catch (e: Exception) {
            plugin.logger.warning("릴레이 기록 정리 실패: ${e.message}")
        }
    }
}
