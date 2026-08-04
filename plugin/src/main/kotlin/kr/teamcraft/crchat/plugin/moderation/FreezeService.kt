package kr.teamcraft.crchat.plugin.moderation

import kr.teamcraft.crchat.plugin.network.ChatRelayDatabase

data class FreezeState(val frozen: Boolean, val reason: String?, val issuedBy: String?)

/**
 * 동결 저장소. [FreezeService](MySQL, 네트워크 전역)와 [LocalFreezeStore](로컬 파일,
 * 이 서버만) 둘 다 구현한다 — [MuteStore] 와 같은 이유로 인터페이스로 뺐다.
 */
interface FreezeStore {
    fun state(): FreezeState

    /** @return 적용된 뒤의 새 상태. */
    fun setFrozen(frozen: Boolean, reason: String?, issuedBy: String): FreezeState
}

/**
 * `/채팅동결` 이 쓰는 네트워크 전역 스위치. 기획서엔 없던 기능이다(사용자 요청).
 *
 * `crchat_freeze` 는 항상 딱 한 행(`id=1`)만 있는 싱글턴 테이블이다. [MuteService] 와
 * 같은 방식으로 동작한다 — 별도 전파 코드 없이, 모든 서버가 같은 MySQL 을 보고
 * 발신 시점마다 이 테이블을 직접 조회하기 때문에 그것만으로 전 서버에 즉시 반영된다.
 */
class FreezeService(private val db: ChatRelayDatabase) : FreezeStore {

    fun ensureSchema() {
        db.dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS crchat_freeze (
                        id INT PRIMARY KEY,
                        frozen BOOLEAN NOT NULL DEFAULT FALSE,
                        reason VARCHAR(255),
                        issued_by VARCHAR(32),
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.trimIndent(),
                )
                st.execute("INSERT IGNORE INTO crchat_freeze (id, frozen) VALUES (1, FALSE)")
            }
        }
    }

    override fun state(): FreezeState =
        db.dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT frozen, reason, issued_by FROM crchat_freeze WHERE id = 1").use { rs ->
                    if (!rs.next()) FreezeState(frozen = false, reason = null, issuedBy = null)
                    else FreezeState(frozen = rs.getBoolean(1), reason = rs.getString(2), issuedBy = rs.getString(3))
                }
            }
        }

    override fun setFrozen(frozen: Boolean, reason: String?, issuedBy: String): FreezeState {
        db.dataSource.connection.use { conn ->
            conn.prepareStatement(
                "UPDATE crchat_freeze SET frozen = ?, reason = ?, issued_by = ?, updated_at = NOW() WHERE id = 1",
            ).use { ps ->
                ps.setBoolean(1, frozen)
                ps.setString(2, reason)
                ps.setString(3, issuedBy)
                ps.executeUpdate()
            }
        }
        return FreezeState(frozen, reason, issuedBy)
    }
}
