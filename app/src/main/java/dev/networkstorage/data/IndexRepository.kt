package dev.networkstorage.data

import dev.networkstorage.data.credential.CredentialStore
import dev.networkstorage.data.db.AppDao
import dev.networkstorage.data.db.ConnectionEntity
import dev.networkstorage.data.db.FolderRuleEntity
import dev.networkstorage.data.db.IndexedEntryEntity
import dev.networkstorage.data.db.ScanRunEntity
import dev.networkstorage.data.smb.SmbClient
import dev.networkstorage.domain.ConnectionConfig
import dev.networkstorage.domain.FolderMode
import dev.networkstorage.domain.RemotePath
import dev.networkstorage.domain.ScanStatus
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.net.URLConnection
import java.util.ArrayDeque
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

class IndexRepository @Inject constructor(private val dao: AppDao, private val credentials: CredentialStore, private val smbClient: SmbClient) {
    suspend fun updateConnection(id: String, name: String, host: String, port: Int, share: String, basePath: String, username: String, replacementPassword: CharArray?, domain: String?, mode: FolderMode) {
        try {
            val old = requireNotNull(dao.connection(id))
            require(name.isNotBlank() && host.isNotBlank() && share.isNotBlank() && username.isNotBlank() && port in 1..65535)
            val updated = old.copy(name=name.trim(), host=host.trim(), port=port, share=share, basePath=RemotePath.normalize(basePath), username=username.trim(), domain=domain?.takeIf(String::isNotBlank), rootMode=mode)
            dao.updateConnection(updated)
            dao.saveRootRule(FolderRuleEntity(id, "", mode))
            replacementPassword?.takeIf { it.isNotEmpty() }?.let { credentials.put(id, it) }
        } finally { replacementPassword?.fill('\u0000') }
    }
    suspend fun deleteConnection(connectionId: String) {
        dao.deleteConnection(connectionId)
        credentials.remove(connectionId)
    }

    suspend fun deleteRootIndex(connectionId: String) = dao.deleteRootIndex(connectionId)

    suspend fun addConnection(name: String, host: String, port: Int, share: String, basePath: String, username: String, password: CharArray, domain: String?, mode: FolderMode): String {
        val id = UUID.randomUUID().toString()
        try {
            require(name.isNotBlank() && host.isNotBlank() && share.isNotBlank() && username.isNotBlank())
            require(port in 1..65535)
            val normalizedBase = RemotePath.normalize(basePath)
            val entity = ConnectionEntity(id, name.trim(), host.trim(), port, share.trim(), normalizedBase, username, domain?.takeIf { it.isNotBlank() }, mode, System.currentTimeMillis())
            credentials.put(id, password)
            dao.saveConnection(entity)
            dao.saveRootRule(FolderRuleEntity(id, "", mode))
            return id
        } catch (error: Throwable) {
            credentials.remove(id)
            throw error
        } finally { password.fill('\u0000') }
    }

    suspend fun scan(connectionId: String, scanId: String, progress: suspend (Long, String) -> Unit) {
        val entity = requireNotNull(dao.connection(connectionId)) { "Connection not found" }
        val credential = requireNotNull(credentials.get(connectionId)) { "Credential unavailable" }
        val startedAt = System.currentTimeMillis()
        dao.saveScan(ScanRunEntity(scanId, connectionId, ScanStatus.RUNNING, 0, startedAt, null, null))
        val queue = ArrayDeque<String>().apply { add("") }
        var count = 0L
        try {
            while (queue.isNotEmpty()) {
                coroutineContext.ensureActive()
                val directory = queue.removeFirst()
                smbClient.list(entity.config(), credential, directory).forEach { remote ->
                    coroutineContext.ensureActive()
                    val now = System.currentTimeMillis()
                    val name = remote.relativePath.substringAfterLast('/')
                    dao.upsertEntry(IndexedEntryEntity(connectionId, remote.relativePath, RemotePath.parent(remote.relativePath), name, remote.isDirectory, remote.size, remote.lastModified, if (remote.isDirectory) null else URLConnection.guessContentTypeFromName(name), entity.rootMode, true, now, scanId))
                    if (remote.isDirectory) queue.addLast(remote.relativePath)
                    count++
                    if (count % 25L == 0L) { dao.updateScanCount(scanId, count); progress(count, remote.relativePath) }
                }
            }
            dao.completeScan(connectionId, scanId, count, System.currentTimeMillis())
            progress(count, "")
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            withContext(NonCancellable) { dao.finishScan(scanId, ScanStatus.CANCELLED, System.currentTimeMillis(), "CANCELLED") }
            throw cancelled
        } catch (error: Throwable) {
            val safeError = (error as? dev.networkstorage.domain.SmbFailure)?.category?.name ?: "UNKNOWN"
            dao.finishScan(scanId, ScanStatus.FAILED, System.currentTimeMillis(), safeError)
            throw error
        } finally { credential.password.fill('\u0000') }
    }

    private fun ConnectionEntity.config() = ConnectionConfig(id, name, host, port, share, basePath, username, domain, rootMode)
}
