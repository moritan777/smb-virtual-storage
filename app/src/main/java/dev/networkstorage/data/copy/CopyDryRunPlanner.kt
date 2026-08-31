package dev.networkstorage.data.copy

import dev.networkstorage.data.credential.CredentialStore
import dev.networkstorage.data.smb.SmbClient
import dev.networkstorage.data.smb.SmbCopyClient
import dev.networkstorage.domain.ConnectionConfig
import dev.networkstorage.domain.CopyDestinationPath
import dev.networkstorage.domain.RemotePath
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

/**
 * Builds a copy plan without creating, modifying, renaming, or deleting any SMB file.
 * Source and existing destination content may be hashed so decisions remain content-based.
 */
class CopyDryRunPlanner @Inject constructor(
    private val sourceTree: CopySourceTree,
    private val copyClient: SmbCopyClient,
    private val readClient: SmbClient,
    private val credentialStore: CredentialStore,
) {
    suspend fun plan(
        connection: ConnectionConfig,
        sourceTreeUri: String,
        destinationDirectory: String,
        includeSubfolders: Boolean,
        conflictPolicy: CopyConflictPolicy,
    ): CopyDryRunResult {
        val sources = sourceTree.listFiles(sourceTreeUri, includeSubfolders)
        val items = ArrayList<CopyDryRunItem>(sources.size)
        for (source in sources) {
            coroutineContext.ensureActive()
            try {
                items += planFile(connection, destinationDirectory, source, conflictPolicy)
            } catch (error: Throwable) {
                items += CopyDryRunItem(
                    sourceRelativePath = source.relativePath,
                    destinationRelativePath = CopyDestinationPath.join(destinationDirectory, source.relativePath),
                    decision = null,
                    sourceSize = source.size,
                    sourceSha256 = null,
                    reason = error.message?.takeIf { it.isNotBlank() } ?: "Unable to determine copy decision",
                )
            }
        }
        return CopyDryRunResult(items)
    }

    private suspend fun planFile(
        connection: ConnectionConfig,
        destinationDirectory: String,
        source: CopySourceFile,
        conflictPolicy: CopyConflictPolicy,
    ): CopyDryRunItem {
        val destination = CopyDestinationPath.normalize(destinationDirectory)
        val originalPath = CopyDestinationPath.join(destination, source.relativePath)
        val sourceDigest = source.openInput().use(::hash)
        require(sourceDigest.bytes == source.size) { "Source size changed while preparing preview" }

        val originalExists = copyClient.exists(connection, credential(connection), originalPath)
        val originalDigest = if (originalExists) remoteDigest(connection, originalPath) else null
        val originalSame = originalDigest?.let { it.bytes == source.size && it.sha256 == sourceDigest.sha256 } == true

        if (originalSame) {
            return item(source, originalPath, CopyDecision.UNCHANGED, sourceDigest.sha256, "Destination already has identical content")
        }

        return when (conflictPolicy) {
            CopyConflictPolicy.REPLACE_WITH_BACKUP -> item(
                source,
                originalPath,
                if (originalExists) CopyDecision.REPLACE else CopyDecision.NEW,
                sourceDigest.sha256,
                if (originalExists) "Destination differs; existing file will be backed up before replacement" else "Destination does not exist",
            )
            CopyConflictPolicy.KEEP_BOTH -> {
                if (!originalExists) {
                    item(source, originalPath, CopyDecision.NEW, sourceDigest.sha256, "Destination does not exist")
                } else {
                    val candidate = findNumberedDestination(connection, originalPath, sourceDigest.sha256)
                    if (candidate.reusable != null) {
                        item(source, candidate.reusable, CopyDecision.REUSE_EXISTING, sourceDigest.sha256, "An identical numbered copy already exists")
                    } else {
                        item(source, requireNotNull(candidate.available), CopyDecision.KEEP_BOTH, sourceDigest.sha256, "Destination differs; a numbered copy will be created")
                    }
                }
            }
        }
    }

    private suspend fun findNumberedDestination(connection: ConnectionConfig, originalPath: String, sourceSha256: String): NumberedDestination {
        val parent = RemotePath.parent(originalPath)
        val originalName = originalPath.substringAfterLast('/')
        for (number in 1..MAX_KEEP_BOTH_ATTEMPTS) {
            coroutineContext.ensureActive()
            val candidate = RemotePath.join(parent, dev.networkstorage.domain.WindowsConflictNaming.numbered(originalName, number))
            if (!copyClient.exists(connection, credential(connection), candidate)) return NumberedDestination(null, candidate)
            val digest = remoteDigest(connection, candidate)
            if (digest.sha256 == sourceSha256) return NumberedDestination(candidate, null)
        }
        error("Unable to allocate a Keep Both destination")
    }

    private suspend fun remoteDigest(connection: ConnectionConfig, path: String): DigestResult =
        readClient.openRead(connection, credential(connection), path).use { hash(it.input) }

    private fun credential(connection: ConnectionConfig) =
        requireNotNull(credentialStore.get(connection.id)) { "Credential unavailable" }

    private suspend fun hash(input: InputStream): DigestResult {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            coroutineContext.ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read.toLong()
            digest.update(buffer, 0, read)
        }
        return DigestResult(total, digest.digest().toHex())
    }

    private fun item(source: CopySourceFile, destination: String, decision: CopyDecision, sha256: String, reason: String) =
        CopyDryRunItem(source.relativePath, destination, decision, source.size, sha256, reason)

    private data class NumberedDestination(val reusable: String?, val available: String?)
    private data class DigestResult(val bytes: Long, val sha256: String)

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    companion object {
        private const val BUFFER_BYTES = 64 * 1024
        private const val MAX_KEEP_BOTH_ATTEMPTS = 10_000
    }
}

data class CopyDryRunItem(
    val sourceRelativePath: String,
    val destinationRelativePath: String,
    val decision: CopyDecision?,
    val sourceSize: Long,
    val sourceSha256: String?,
    val reason: String,
)

data class CopyDryRunResult(val items: List<CopyDryRunItem>) {
    val summary: CopyOperationSummary
        get() = CopyOperationSummary(
            total = items.size,
            newCount = items.count { it.decision == CopyDecision.NEW },
            unchangedCount = items.count { it.decision == CopyDecision.UNCHANGED },
            keepBothCount = items.count { it.decision == CopyDecision.KEEP_BOTH },
            replaceCount = items.count { it.decision == CopyDecision.REPLACE },
            reuseExistingCount = items.count { it.decision == CopyDecision.REUSE_EXISTING },
            failedCount = items.count { it.decision == null },
        )
}
