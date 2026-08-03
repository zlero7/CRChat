package kr.teamcraft.crchat.plugin.network

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection

/**
 * 크로스서버 연동에 쓰는 MySQL 커넥션 풀 + 스키마. (13장 Phase 8, Redis 대신 MySQL)
 *
 * 테이블 두 개:
 * - `crchat_relay` — 다른 서버로 퍼뜨릴 메시지 큐. 각 서버가 자기 `id` 커서보다 큰
 *   행을 주기적으로 폴링해서 가져간다(2.2 큐잉과 동일한 아이디어를 Pub/Sub 대신
 *   폴링으로 구현). 오래된 행은 [ChatRelay] 가 주기적으로 지운다.
 * - `crchat_sender_ids` — [kr.teamcraft.crchat.plugin.sender.SenderRegistry] 가 쓰는
 *   **네트워크 전역** 발신자 ID. UUID 당 하나씩, AUTO_INCREMENT 로 발급해서 서버가
 *   달라도 같은 사람은 항상 같은 ID를 받는다 — 로컬 발급이면 서버마다 카운터가 따로
 *   돌아서 다른 사람인데 같은 ID를 받는 충돌이 난다.
 */
class ChatRelayDatabase(config: NetworkConfig) : AutoCloseable {

    val dataSource: HikariDataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = "jdbc:mysql://${config.dbHost}:${config.dbPort}/${config.dbName}" +
                "?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=UTC"
            username = config.dbUser
            password = config.dbPassword
            maximumPoolSize = 4
            poolName = "crchat-relay"
            // shadowJar 가 com.mysql.* 를 kr.teamcraft.crchat.libs.mysql.* 로 재배치하면서
            // META-INF/services/java.sql.Driver 안의 클래스 이름은 그대로 남아 ServiceLoader
            // 자동 등록이 깨진다(실제로 이 문제가 있었다) — 재배치된 이름으로 직접 지정한다.
            driverClassName = "kr.teamcraft.crchat.libs.mysql.cj.jdbc.Driver"
        },
    )

    fun ensureSchema() {
        dataSource.connection.use { conn -> createTables(conn) }
    }

    private fun createTables(conn: Connection) {
        conn.createStatement().use { st ->
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS crchat_relay (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    server_id VARCHAR(64) NOT NULL,
                    channel VARCHAR(32) NOT NULL,
                    payload MEDIUMTEXT NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    INDEX idx_crchat_relay_created (created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.trimIndent(),
            )
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS crchat_sender_ids (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    uuid VARCHAR(36) NOT NULL,
                    UNIQUE KEY uk_crchat_sender_uuid (uuid)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.trimIndent(),
            )
        }
    }

    override fun close() {
        dataSource.close()
    }
}
