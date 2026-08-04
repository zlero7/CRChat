package kr.teamcraft.crchat.plugin.moderation

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Serializable
private data class StoredMute(
    val id: Long,
    val uuid: String,
    val channel: String?,
    val until: Long?,
    val reason: String?,
    val issuedBy: String,
    val createdAt: Long,
    val revokedAt: Long?,
)

@Serializable
private data class MuteFile(val nextId: Long = 1, val records: List<StoredMute> = emptyList())

/**
 * 크로스서버 연동(MySQL)이 꺼져 있는 **단일 서버**에서 뮤트가 되게 해 달라는 요청으로
 * 추가했다. [file] 하나에 JSON 으로 전부 담는다 — 애초에 이 서버만 쓰는 저장소라
 * 여러 서버가 동시에 같은 파일을 건드릴 일이 없으므로 DB 없이도 안전하다.
 *
 * [MuteStore] 를 그대로 구현해서 [kr.teamcraft.crchat.plugin.CRChatPlugin] 은
 * [MuteService](MySQL)와 이걸 구분할 필요가 없다 — 둘 다 똑같이 쓴다.
 *
 * 파일 I/O 는 메인 스레드에서 부르는 게 전제다(채팅 발신·명령어 실행 둘 다 메인
 * 스레드) — 그래서 별도 비동기 처리 없이 그냥 동기로 읽고 쓴다. [lock] 은 혹시
 * 모를 동시 접근(예: 나중에 비동기 경로가 생기는 경우)에 대비한 방어선이다.
 */
class LocalMuteStore(private val file: File) : MuteStore {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val lock = ReentrantLock()

    private fun load(): MuteFile =
        if (file.exists()) {
            runCatching { json.decodeFromString<MuteFile>(file.readText()) }.getOrElse { MuteFile() }
        } else {
            MuteFile()
        }

    private fun save(data: MuteFile) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(data))
    }

    override fun activeMute(uuid: UUID, channel: String): MuteRecord? = lock.withLock {
        val now = System.currentTimeMillis()
        load().records
            .asSequence()
            .filter { it.uuid == uuid.toString() && it.revokedAt == null && (it.until == null || it.until > now) }
            .filter { it.channel == null || it.channel == channel }
            .maxWithOrNull(compareBy({ it.channel == null }, { it.id }))
            ?.toMuteRecord()
    }

    /**
     * 새로 걸기 전에 기존 활성 뮤트를 전부 해제한다 — **덮어쓰기**. [MuteService.mute]
     * 와 같은 이유(채널이 달라도 두 개가 동시에 활성으로 남는 걸 막는다)다. 여기선
     * 한 번의 load/save 로 처리한다 — [unmute] 를 따로 부르면 파일을 두 번 쓰게 된다.
     */
    override fun mute(uuid: UUID, channel: String?, untilEpochMillis: Long?, reason: String?, issuedBy: String) =
        lock.withLock {
            val data = load()
            val now = System.currentTimeMillis()
            val revoked = data.records.map { record ->
                val isActive = record.uuid == uuid.toString() && record.revokedAt == null &&
                    (record.until == null || record.until > now)
                if (isActive) record.copy(revokedAt = now) else record
            }
            val newRecord = StoredMute(
                id = data.nextId,
                uuid = uuid.toString(),
                channel = channel,
                until = untilEpochMillis,
                reason = reason,
                issuedBy = issuedBy,
                createdAt = now,
                revokedAt = null,
            )
            save(data.copy(nextId = data.nextId + 1, records = revoked + newRecord))
        }

    override fun unmute(uuid: UUID, channel: String?): Int = lock.withLock {
        val data = load()
        val now = System.currentTimeMillis()
        var count = 0
        val updated = data.records.map { record ->
            val isActive = record.uuid == uuid.toString() && record.revokedAt == null &&
                (record.until == null || record.until > now)
            val matchesChannel = channel == null || record.channel == channel
            if (isActive && matchesChannel) {
                count++
                record.copy(revokedAt = now)
            } else {
                record
            }
        }
        if (count > 0) save(data.copy(records = updated))
        count
    }

    override fun history(uuid: UUID, limit: Int): List<MuteRecord> = lock.withLock {
        load().records
            .filter { it.uuid == uuid.toString() }
            .sortedByDescending { it.id }
            .take(limit)
            .map { it.toMuteRecord() }
    }

    private fun StoredMute.toMuteRecord() = MuteRecord(id, channel, until, reason, issuedBy, createdAt, revokedAt)
}
