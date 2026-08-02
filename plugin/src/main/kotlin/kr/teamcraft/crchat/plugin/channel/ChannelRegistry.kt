package kr.teamcraft.crchat.plugin.channel

import kr.teamcraft.crchat.api.GroupType
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

/**
 * `channels.yml` 을 읽어 [Channel] 별 설정을 들고 있는다. (부록 C.3, C.6)
 *
 * 기본 리소스를 데이터 폴더에 복사해 두는 이유는 관리자가 직접 편집하고
 * `/crchat reload` 로 다시 읽을 수 있어야 하기 때문이다 — 잘 편집 못 하면
 * jar 내부 리소스만으로는 영구히 고정된다.
 */
class ChannelRegistry(private val plugin: JavaPlugin) {

    private var channels: Map<Channel, ChannelConfig> = emptyMap()
    var teamPrefix: String = "party_"
        private set

    fun load() {
        plugin.saveResource("channels.yml", false)
        val file = File(plugin.dataFolder, "channels.yml")
        val yaml = YamlConfiguration.loadConfiguration(file)

        channels = Channel.entries.associateWith { channel ->
            val section = yaml.getConfigurationSection(channel.id)
            ChannelConfig(
                channel = channel,
                display = section?.getString("display") ?: channel.id,
                color = section?.getString("color"),
                cooldownSeconds = section?.getDouble("cooldown") ?: 0.0,
                permission = section?.getString("permission"),
                requires = section?.getString("requires")?.let { parseGroupType(it) },
                receiveOnly = section?.getBoolean("receive-only") ?: false,
                radius = section?.takeIf { it.contains("radius") }?.getDouble("radius"),
            )
        }

        teamPrefix = yaml.getConfigurationSection("party-provider")?.getString("team-prefix") ?: "party_"
    }

    fun config(channel: Channel): ChannelConfig =
        channels[channel] ?: error("channels.yml 에 ${channel.id} 정의가 없습니다")

    private fun parseGroupType(value: String): GroupType? = when (value) {
        "guild" -> GroupType.GUILD
        "party" -> GroupType.PARTY
        else -> null
    }
}
