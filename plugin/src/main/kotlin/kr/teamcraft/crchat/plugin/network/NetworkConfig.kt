package kr.teamcraft.crchat.plugin.network

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

/** `config.yml` 을 읽은 결과. (부록 C.2) */
data class NetworkConfig(
    val serverId: String,
    val dbHost: String,
    val dbPort: Int,
    val dbName: String,
    val dbUser: String,
    val dbPassword: String,
    val networkEnabled: Boolean,
    val pollIntervalTicks: Long,
    val retentionHours: Long,
) {
    companion object {
        fun load(plugin: JavaPlugin): NetworkConfig {
            plugin.saveResource("config.yml", false)
            val yaml = YamlConfiguration.loadConfiguration(File(plugin.dataFolder, "config.yml"))
            return NetworkConfig(
                serverId = yaml.getString("server-id") ?: "lobby",
                dbHost = yaml.getString("database.host") ?: "127.0.0.1",
                dbPort = yaml.getInt("database.port", 3306),
                dbName = yaml.getString("database.name") ?: "crchat",
                dbUser = yaml.getString("database.user") ?: "crchat",
                dbPassword = yaml.getString("database.password") ?: "",
                networkEnabled = yaml.getBoolean("network.enabled", true),
                pollIntervalTicks = yaml.getLong("network.poll-interval-ticks", 10),
                retentionHours = yaml.getLong("network.retention-hours", 24),
            )
        }
    }
}
