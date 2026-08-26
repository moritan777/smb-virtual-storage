package dev.networkstorage.data.mirror

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.networkstorage.data.credential.CredentialStore
import dev.networkstorage.data.db.AppDao
import dev.networkstorage.data.settings.SettingsRepository
import dev.networkstorage.data.smb.SmbClient
import dev.networkstorage.domain.ConnectionConfig
import dev.networkstorage.domain.RemotePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

class MirrorRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: AppDao,
    private val settings: SettingsRepository,
    private val credentials: CredentialStore,
    private val smb: SmbClient,
) {
    suspend fun compare(connectionId: String): List<MirrorDiffItem> = withContext(Dispatchers.IO) {
        val rootValue = settings.mirrorRootUri.first() ?: error("MIRROR_ROOT_UNCONFIGURED")
        val root = requireNotNull(DocumentFile.fromTreeUri(context, Uri.parse(rootValue)))
        val connectionRoot = root.findFile(connectionId)?.takeIf { it.isDirectory }
        val localFiles = if (connectionRoot == null) emptyMap() else collectLocalFiles(connectionRoot)
        val remoteFiles = dao.indexedFiles(connectionId).associateBy { it.relativePath }
        (remoteFiles.keys + localFiles.keys).sortedWith(String.CASE_INSENSITIVE_ORDER).map { path ->
            val remote = remoteFiles[path]
            val local = localFiles[path]
            MirrorDiffItem(
                relativePath = path,
                name = path.substringAfterLast('/'),
                remoteSize = remote?.size,
                remoteLastModified = remote?.lastModified,
                localSize = local?.length(),
                localLastModified = local?.lastModified(),
                state = MirrorDiffPolicy.classify(remote?.size, remote?.lastModified, local?.length(), local?.lastModified()),
            )
        }
    }

    /** Returns the local Mirror URI only when it still matches the indexed NAS metadata. */
    suspend fun mirroredUriIfCurrent(connectionId: String, relativePath: String): Uri? = withContext(Dispatchers.IO) {
        val normalized = RemotePath.normalize(relativePath)
        val remote = dao.indexedEntry(connectionId, normalized) ?: return@withContext null
        if (remote.isDirectory) return@withContext null
        val local = findLocalFile(connectionId, normalized) ?: return@withContext null
        val state = MirrorDiffPolicy.classify(remote.size, remote.lastModified, local.length(), local.lastModified())
        if (state == MirrorDiffState.SAME) local.uri else null
    }

    suspend fun copyRemoteToLocal(connectionId: String, relativePath: String, progress: suspend (Long, Long) -> Unit = { _, _ -> }): Long = withContext(Dispatchers.IO) {
        val normalized = RemotePath.normalize(relativePath)
        val entry = requireNotNull(dao.indexedEntry(connectionId, normalized)) { "REMOTE_ENTRY_NOT_FOUND" }
        require(!entry.isDirectory) { "MIRROR_DIRECTORY_COPY_NOT_SUPPORTED" }
        val connection = requireNotNull(dao.connection(connectionId))
        val credential = requireNotNull(credentials.get(connectionId)) { "CREDENTIAL_UNAVAILABLE" }
        val rootValue = settings.mirrorRootUri.first() ?: error("MIRROR_ROOT_UNCONFIGURED")
        val root = requireNotNull(DocumentFile.fromTreeUri(context, Uri.parse(rootValue)))
        val connectionRoot = root.findFile(connectionId)?.takeIf { it.isDirectory } ?: requireNotNull(root.createDirectory(connectionId))
        val parent = ensureDirectories(connectionRoot, normalized.substringBeforeLast('/', "").split('/').filter(String::isNotBlank))
        val name = normalized.substringAfterLast('/')
        parent.findFile("$name.part")?.delete()
        val part = requireNotNull(parent.createFile("application/octet-stream", "$name.part"))
        try {
            val config = ConnectionConfig(connection.id, connection.name, connection.host, connection.port, connection.share, connection.basePath, connection.username, connection.domain, connection.rootMode)
            var copied = 0L
            smb.openRead(config, credential, normalized).use { remote ->
                requireNotNull(context.contentResolver.openOutputStream(part.uri, "w")).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = remote.input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        progress(copied, entry.size)
                    }
                    output.flush()
                }
            }
            if (copied != entry.size) throw IOException("MIRROR_SIZE_MISMATCH")
            val existing = parent.findFile(name)
            if (existing != null && !existing.delete()) throw IOException("MIRROR_REPLACE_FAILED")
            if (!part.renameTo(name)) throw IOException("MIRROR_PROMOTION_FAILED")
            copied
        } catch (error: Throwable) {
            part.delete()
            throw error
        } finally {
            credential.password.fill('\u0000')
        }
    }

    private suspend fun findLocalFile(connectionId: String, relativePath: String): DocumentFile? {
        val rootValue = settings.mirrorRootUri.first() ?: return null
        val root = DocumentFile.fromTreeUri(context, Uri.parse(rootValue)) ?: return null
        var current = root.findFile(connectionId)?.takeIf { it.isDirectory } ?: return null
        val parts = relativePath.split('/').filter(String::isNotBlank)
        parts.forEachIndexed { index, part ->
            val next = current.findFile(part) ?: return null
            if (index < parts.lastIndex && !next.isDirectory) return null
            current = next
        }
        return current.takeIf { it.isFile && it.exists() }
    }

    private fun collectLocalFiles(root: DocumentFile): Map<String, DocumentFile> {
        val result = linkedMapOf<String, DocumentFile>()
        fun walk(directory: DocumentFile, parent: String) {
            directory.listFiles().forEach { child ->
                val name = child.name ?: return@forEach
                val path = RemotePath.join(parent, name)
                if (child.isDirectory) walk(child, path) else if (!name.endsWith(".part")) result[path] = child
            }
        }
        walk(root, "")
        return result
    }

    private fun ensureDirectories(root: DocumentFile, parts: List<String>): DocumentFile = parts.fold(root) { parent, raw ->
        val name = RemotePath.join("", raw)
        parent.findFile(name)?.takeIf { it.isDirectory } ?: requireNotNull(parent.createDirectory(name))
    }
}
