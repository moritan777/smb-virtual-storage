package dev.networkstorage.data.cache

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dev.networkstorage.data.db.AppDao
import dev.networkstorage.data.db.CacheEntryEntity
import dev.networkstorage.data.settings.SettingsRepository
import dev.networkstorage.data.smb.SmbClient
import dev.networkstorage.domain.CacheState
import dev.networkstorage.domain.Credential
import dev.networkstorage.domain.RemotePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import java.io.IOException
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext

data class CacheResult(val uri: Uri, val reused: Boolean, val evictedBytes: Long = 0)

class CacheRepository @Inject constructor(@ApplicationContext private val context: Context, private val dao: AppDao, private val settings: SettingsRepository, private val smb: SmbClient) {
    private val resolver = context.contentResolver

    suspend fun cachedUriIfValid(connectionId: String, relativePath: String): Uri? = withContext(Dispatchers.IO) {
        val normalized = RemotePath.normalize(relativePath)
        val entry = dao.indexedEntry(connectionId, normalized) ?: return@withContext null
        val cached = dao.cacheEntry(connectionId, normalized) ?: return@withContext null
        if (cached.state != CacheState.CACHED) return@withContext null
        if (cached.remoteSize != entry.size || cached.remoteLastModified != entry.lastModified) return@withContext null
        if (!documentExists(cached.localDocumentUri)) return@withContext null
        dao.touchCache(connectionId, normalized, System.currentTimeMillis())
        Uri.parse(cached.localDocumentUri)
    }

    suspend fun obtain(connectionId: String, relativePath: String, credential: Credential, progress: suspend (Long, Long) -> Unit): CacheResult = withContext(Dispatchers.IO) {
        val entry = requireNotNull(dao.indexedEntry(connectionId, RemotePath.normalize(relativePath)))
        val existing = dao.cacheEntry(connectionId, entry.relativePath)
        if (existing?.state == CacheState.CACHED && existing.remoteSize == entry.size && existing.remoteLastModified == entry.lastModified && documentExists(existing.localDocumentUri)) {
            dao.touchCache(connectionId, entry.relativePath, System.currentTimeMillis())
            return@withContext CacheResult(Uri.parse(existing.localDocumentUri), true)
        }
        val rootValue = settings.cacheRootUri.first() ?: error("CACHE_ROOT_UNCONFIGURED")
        val limit = settings.cacheLimitBytes.first()
        if (entry.size > limit) error("FILE_EXCEEDS_CACHE_LIMIT")
        val retainedUsage = (dao.observeCacheUsage().first() - (existing?.size ?: 0L)).coerceAtLeast(0L)
        val evictedBytes = evictFor(retainedUsage, entry.size, limit, connectionId, entry.relativePath)
        val root = requireNotNull(DocumentFile.fromTreeUri(context, Uri.parse(rootValue)))
        val parent = ensureDirectories(root, CachePath.directoryParts(connectionId, entry.relativePath))
        val name = entry.name
        parent.findFile("$name.part")?.delete()
        val part = requireNotNull(parent.createFile("application/octet-stream", "$name.part"))
        val now = System.currentTimeMillis()
        dao.upsertCache(CacheEntryEntity(connectionId, entry.relativePath, existing?.localDocumentUri.orEmpty(), existing?.size ?: 0, existing?.remoteSize ?: 0, existing?.remoteLastModified ?: 0, CacheState.DOWNLOADING, existing?.lastAccessed ?: now, existing?.createdAt ?: now, now))
        try {
            val connection = requireNotNull(dao.connection(connectionId))
            val config = dev.networkstorage.domain.ConnectionConfig(connection.id, connection.name, connection.host, connection.port, connection.share, connection.basePath, connection.username, connection.domain, connection.rootMode)
            var copied = 0L
            smb.openRead(config, credential, entry.relativePath).use { remote ->
                requireNotNull(resolver.openOutputStream(part.uri, "w")).use { output ->
                    copied = DownloadCopier.copy(remote.input, output, entry.size) { progress(it, entry.size) }
                }
            }
            if (!part.renameTo(name)) throw IOException("PROMOTION_FAILED")
            val completedUri = part.uri
            if (existing != null && existing.localDocumentUri.isNotBlank() && existing.localDocumentUri != completedUri.toString()) deleteDocument(existing.localDocumentUri)
            val finished = System.currentTimeMillis()
            dao.upsertCache(CacheEntryEntity(connectionId, entry.relativePath, completedUri.toString(), copied, entry.size, entry.lastModified, CacheState.CACHED, finished, existing?.createdAt ?: finished, finished))
            CacheResult(completedUri, false, evictedBytes)
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                part.delete()
                if (existing != null) dao.upsertCache(existing)
                else if (error is CancellationException) dao.deleteCache(connectionId, entry.relativePath)
                else dao.upsertCache(CacheEntryEntity(connectionId, entry.relativePath, "", 0, entry.size, entry.lastModified, CacheState.FAILED, now, now, System.currentTimeMillis()))
            }
            throw error
        } finally { credential.password.fill('\u0000') }
    }

    suspend fun remove(connectionId: String, relativePath: String): Boolean = withContext(Dispatchers.IO) {
        val cached = dao.cacheEntry(connectionId, RemotePath.normalize(relativePath)) ?: return@withContext false
        if (cached.state == CacheState.DOWNLOADING) return@withContext false
        val deleted = cached.localDocumentUri.isBlank() || deleteDocument(cached.localDocumentUri)
        if (deleted) dao.deleteCache(connectionId, cached.relativePath)
        deleted
    }

    suspend fun clearAll(): Long = withContext(Dispatchers.IO) {
        var freed = 0L
        dao.allCachedEntries().forEach { cached ->
            if (cached.localDocumentUri.isBlank() || deleteDocument(cached.localDocumentUri)) {
                freed += cached.size
                dao.deleteCache(cached.connectionId, cached.relativePath)
            }
        }
        freed
    }

    suspend fun cleanupOrphans(connectionId: String): Long = withContext(Dispatchers.IO) {
        var freed = 0L
        dao.orphanCacheEntries(connectionId).forEach { cached ->
            if (cached.state == CacheState.DOWNLOADING) return@forEach
            val deleted = cached.localDocumentUri.isBlank() || deleteDocument(cached.localDocumentUri)
            if (deleted) {
                freed += cached.size
                dao.deleteCache(cached.connectionId, cached.relativePath)
            }
        }
        freed
    }

    private suspend fun evictFor(usage: Long, incoming: Long, limit: Long, protectedConnectionId: String, protectedPath: String): Long {
        var projected = usage
        var freed = 0L
        if (!CachePolicy.exceedsLimit(projected, incoming, limit)) return 0L
        for (cached in dao.lruCacheEntries(protectedConnectionId, protectedPath)) {
            if (cached.localDocumentUri.isBlank() || deleteDocument(cached.localDocumentUri)) {
                dao.deleteCache(cached.connectionId, cached.relativePath)
                projected = (projected - cached.size).coerceAtLeast(0L)
                freed += cached.size
                if (!CachePolicy.exceedsLimit(projected, incoming, limit)) return freed
            }
        }
        error("CACHE_LIMIT_CANNOT_BE_SATISFIED")
    }

    private fun deleteDocument(value: String): Boolean = runCatching {
        val document = DocumentFile.fromSingleUri(context, Uri.parse(value)) ?: return@runCatching false
        !document.exists() || document.delete()
    }.getOrDefault(false)
    private fun documentExists(value: String) = value.isNotBlank() && DocumentFile.fromSingleUri(context, Uri.parse(value))?.exists() == true
    private fun ensureDirectories(root: DocumentFile, parts: List<String>): DocumentFile = parts.fold(root) { parent, raw ->
        val name = RemotePath.join("", raw)
        parent.findFile(name)?.takeIf { it.isDirectory } ?: requireNotNull(parent.createDirectory(name))
    }
}
