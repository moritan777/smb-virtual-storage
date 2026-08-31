package dev.networkstorage.data.copy

import dev.networkstorage.data.credential.CredentialStore
import dev.networkstorage.data.smb.SmbClient
import dev.networkstorage.data.smb.SmbCopyClient
import dev.networkstorage.domain.ConnectionConfig
import dev.networkstorage.domain.CopyDestinationPath
import dev.networkstorage.domain.RemotePath
import kotlinx.coroutines.CancellationException
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
            } catch (error: CancellationException) {
                throw error
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

        return when (conflictPolicy) {
            CopyConflictPolicy.REPLACE_WITH_BACKUP -> {
                val decision = CopyDecisionEngine.decide(
                    CopyDecisionInput(
                        originalExists = originalExists,
                        originalSha256 = originalDigest?.sha256,
                        sourceSha256 = sourceDigest.sha256,
                        conflictPolicy = conflictPolicy,
                    ),
                    originalPath,
                )
                item(source, decision, sourceDigest.sha256)
            }
            CopyConflictPolicy.KEEP_BOTH -> {
                if (!originalExists) {
                    val decision = CopyDecisionEngine.decide(
                        CopyDecisionInput(false, null, sourceDigest.sha256, conflictPolicy), originalPath,
                    )
                    item(source, decision, sourceDigest.sha256)
                } else if (originalDigest?.bytes == source.size && originalDigest.sha256 == sourceDigest.sha256) {
                    val decision = CopyDecisionEngine.decide(
                        CopyDecisionInput(true, originalDigest.sha256, sourceDigest.sha256, conflictPolicy), originalPath,
                    )
                    item(source, decision, sourceDigest.sha256)
                } else {
                    val candidate = findNumberedDestination(connection, originalPath, sourceDigest.sha256)
                    val decision = CopyDecisionEngine.decide(
                        CopyDecisionInput(
                            originalExists = true,
                            originalSha256 = originalDigest?.sha256,
                            sourceSha256 = sourceDigest.sha256,
                            conflictPolicy = conflictPolicy,
                            reusableNumberedDestination = candidate.reusable,
                            availableNumberedDestination = candidate.available,
                        ),
                        originalPath,
                    )
                    item(source, decision, sourceDigest.sha256)
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

    private fun item(source: CopySourceFile, decision: CopyDecisionResult, sha256: String) =
        CopyDryRunItem(source.relativePath, decision.destinationRelativePath, decision.decision, source.size, sha256, decision.reason)

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
