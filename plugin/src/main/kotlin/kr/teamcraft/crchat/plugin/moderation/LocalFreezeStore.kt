package kr.teamcraft.crchat.plugin.moderation

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Serializable
private data class StoredFreeze(val frozen: Boolean = false, val reason: String? = null, val issuedBy: String? = null)

/**
 * 크로스서버 연동(MySQL)이 꺼져 있는 **단일 서버**용 [FreezeStore] 구현. [LocalMuteStore]
 * 와 같은 이유·같은 방식(JSON 파일 하나)이다 — 서버 재시작 중에 발생한 사고 대응이
 * 끊기지 않도록, 동결 상태도 굳이 파일에 남긴다.
 */
class LocalFreezeStore(private val file: File) : FreezeStore {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val lock = ReentrantLock()

    override fun state(): FreezeState = lock.withLock {
        val stored = if (file.exists()) {
            runCatching { json.decodeFromString<StoredFreeze>(file.readText()) }.getOrElse { StoredFreeze() }
        } else {
            StoredFreeze()
        }
        FreezeState(stored.frozen, stored.reason, stored.issuedBy)
    }

    override fun setFrozen(frozen: Boolean, reason: String?, issuedBy: String): FreezeState = lock.withLock {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(StoredFreeze(frozen, reason, issuedBy)))
        FreezeState(frozen, reason, issuedBy)
    }
}
