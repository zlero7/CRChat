package kr.teamcraft.crchat.plugin.moderation

import kr.teamcraft.crchat.plugin.network.ChatRelayDatabase
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.util.UUID

/** `crchat_mute` 한 행. (기획서 7.2) */
data class MuteRecord(
    val id: Long,
    /** null 이면 전체 채널 뮤트 */
    val channel: String?,
    /** epoch millis. null 이면 영구 */
    val until: Long?,
    val reason: String?,
    val issuedBy: String,
    val createdAt: Long,
    /** null 이면 아직 해제 안 됨(또는 자연 만료만 됨) */
    val revokedAt: Long?,
)

/**
 * `crchat_mute` 테이블 CRUD. (기획서 7.2)
 *
 * **행을 지우지 않는다** — 해제는 `revoked_at` 을 채우는 것으로 처리한다. 제재 이력이
 * 남아야 재범 판단이 가능하다.
 *
 * 만료는 [activeMute] 호출 시점(=발신 시점)에 검사한다 — 별도 스케줄러로 일괄
 * 해제하지 않는다. 서버가 꺼져 있는 동안 만료된 것도 자연스럽게 처리된다.
 *
 * **네트워크 전역 뮤트가 별도 전파 코드 없이 그냥 된다** — 크로스서버 채팅 릴레이와
 * 같은 MySQL 을 보고, 모든 서버가 발신 시점마다 이 테이블을 직접 조회하기 때문이다.
 * Redis Pub/Sub 로 캐시를 밀어주는 기획서 원안(7.2)보다 단순하다 — 뮤트 변경은
 * 채팅 메시지만큼 잦지 않으니 매번 조회해도 부담이 없다.
 */
class MuteService(private val db: ChatRelayDatabase) {

    fun ensureSchema() {
        db.dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS crchat_mute (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        uuid CHAR(36) NOT NULL,
                        channel VARCHAR(32) NULL,
                        until TIMESTAMP NULL,
                        reason VARCHAR(255),
                        issued_by VARCHAR(32) NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        revoked_at TIMESTAMP NULL,
                        INDEX idx_crchat_mute_uuid (uuid, revoked_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.trimIndent(),
                )
            }
        }
    }

    /**
     * 지금 [channel] 발신을 막는 뮤트가 있으면 돌려준다. 전체 뮤트(channel=NULL)가
     * 채널별 뮤트보다 우선한다 — 둘 다 걸려 있으면 더 강한 쪽(전체)을 보여줘야
     * 사용자가 "채널만 풀면 되는 줄" 착각하지 않는다.
     */
    fun activeMute(uuid: UUID, channel: String): MuteRecord? =
        db.dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT id, channel, UNIX_TIMESTAMP(until), reason, issued_by, UNIX_TIMESTAMP(created_at), UNIX_TIMESTAMP(revoked_at)
                FROM crchat_mute
                WHERE uuid = ? AND revoked_at IS NULL AND (until IS NULL OR until > NOW())
                  AND (channel IS NULL OR channel = ?)
                ORDER BY (channel IS NULL) DESC, id DESC
                LIMIT 1
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, uuid.toString())
                ps.setString(2, channel)
                ps.executeQuery().use { rs -> if (rs.next()) rs.toMuteRecord() else null }
            }
        }

    fun mute(uuid: UUID, channel: String?, untilEpochMillis: Long?, reason: String?, issuedBy: String) {
        db.dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO crchat_mute (uuid, channel, until, reason, issued_by) VALUES (?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setString(1, uuid.toString())
                ps.setString(2, channel)
                if (untilEpochMillis != null) ps.setTimestamp(3, Timestamp(untilEpochMillis)) else ps.setNull(3, Types.TIMESTAMP)
                ps.setString(4, reason)
                ps.setString(5, issuedBy)
                ps.executeUpdate()
            }
        }
    }

    /**
     * @param channel null 이면(명령어에서 채널 생략) 그 사람의 활성 뮤트를 채널 상관없이
     * 전부 해제한다 — "/언뮤트 <닉>" 만으로 전체 해제되는 게 관리자 기대치와 맞다.
     * @return 실제로 해제된 행 수
     */
    fun unmute(uuid: UUID, channel: String?): Int =
        db.dataSource.connection.use { conn ->
            val sql = buildString {
                append("UPDATE crchat_mute SET revoked_at = NOW() ")
                append("WHERE uuid = ? AND revoked_at IS NULL AND (until IS NULL OR until > NOW())")
                if (channel != null) append(" AND channel = ?")
            }
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, uuid.toString())
                if (channel != null) ps.setString(2, channel)
                ps.executeUpdate()
            }
        }

    fun history(uuid: UUID, limit: Int = 15): List<MuteRecord> =
        db.dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT id, channel, UNIX_TIMESTAMP(until), reason, issued_by, UNIX_TIMESTAMP(created_at), UNIX_TIMESTAMP(revoked_at)
                FROM crchat_mute WHERE uuid = ? ORDER BY id DESC LIMIT ?
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, uuid.toString())
                ps.setInt(2, limit)
                ps.executeQuery().use { rs ->
                    val list = mutableListOf<MuteRecord>()
                    while (rs.next()) list += rs.toMuteRecord()
                    list
                }
            }
        }

    private fun ResultSet.toMuteRecord(): MuteRecord {
        val until = getLong(3).takeIf { !wasNull() }?.times(1000)
        val createdAt = getLong(6).takeIf { !wasNull() }?.times(1000) ?: 0L
        val revokedAt = getLong(7).takeIf { !wasNull() }?.times(1000)
        return MuteRecord(
            id = getLong(1),
            channel = getString(2),
            until = until,
            reason = getString(4),
            issuedBy = getString(5),
            createdAt = createdAt,
            revokedAt = revokedAt,
        )
    }
}
