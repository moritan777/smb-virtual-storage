package dev.networkstorage.data.copy

import dev.networkstorage.data.credential.CredentialStore
import dev.networkstorage.data.smb.AppOwnedPart
import dev.networkstorage.data.smb.PromotedUpload
import dev.networkstorage.data.smb.SmbClient
import dev.networkstorage.data.smb.SmbCopyClient
import dev.networkstorage.domain.ConnectionConfig
import dev.networkstorage.domain.CopyDestinationPath
import dev.networkstorage.domain.RemotePath
import dev.networkstorage.domain.WindowsConflictNaming
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

enum class CopyConflictPolicy {
    KEEP_BOTH,
    REPLACE_WITH_BACKUP,
}

enum class CopyFileStatus {
    COPIED,
    UNCHANGED,
    REUSED_EXISTING,
}

data class CopySourceFile(
    val relativePath: String,
    val size: Long,
    val openInput: () -> InputStream,
)

data class CopyFileResult(
    val status: CopyFileStatus,
    val destinationRelativePath: String,
    val sourceSize: Long,
    val sha256: String,
    val backupRelativePath: String? = null,
)

class CopyIntegrityException(message: String) : Exception(message)
class CopyRestoreException(cause: Throwable) : Exception("Backup restoration failed", cause)

class CopyToSmbOrchestrator @Inject constructor(
    private val copyClient: SmbCopyClient,
    private val readClient: SmbClient,
    private val credentialStore: CredentialStore,
) {
    suspend fun copyFile(
        connection: ConnectionConfig,
        destinationDirectory: String,
        source: CopySourceFile,
        conflictPolicy: CopyConflictPolicy,
        operationId: String,
        now: Instant = Instant.now(),
    ): CopyFileResult {
        require(source.size >= 0L) { "Source size must be known" }
        val destination = CopyDestinationPath.normalize(destinationDirectory)
        val finalPath = CopyDestinationPath.join(destination, source.relativePath)
        val finalDirectory = RemotePath.parent(finalPath)
        val finalName = finalPath.substringAfterLast('/')

        copyClient.createDirectories(connection, credential(connection), finalDirectory)

        var cleanupPart: AppOwnedPart? = null
        try {
            val writeHandle = copyClient.createPart(
                connection = connection,
                credential = credential(connection),
                destinationDirectory = finalDirectory,
                originalFileName = finalName,
                operationId = operationId,
            )
            val activePart = writeHandle.part
            cleanupPart = activePart

            val sourceDigest = writeHandle.use { handle ->
                source.openInput().use { input ->
                    copyAndHash(input, handle.output, source.size)
                }
            }
            requireExactSize(sourceDigest.bytes, source.size, "Source changed while being copied")

            val partDigest = copyClient.openPartRead(connection, credential(connection), activePart).use { handle ->
                hash(handle.input)
            }
            requireExactSize(partDigest.bytes, source.size, "Uploaded .part size mismatch")
            requireSameHash(sourceDigest, partDigest, "Uploaded .part SHA-256 mismatch")

            return when (conflictPolicy) {
                CopyConflictPolicy.KEEP_BOTH -> copyKeepBoth(connection, activePart, finalPath, sourceDigest, source.size)
                CopyConflictPolicy.REPLACE_WITH_BACKUP -> copyReplaceWithBackup(
                    connection,
                    activePart,
                    finalPath,
                    sourceDigest,
                    source.size,
                    operationId,
                    now,
                )
            }
        } catch (error: Throwable) {
            cleanupPart?.let { activePart ->
                runCatching { copyClient.removePart(connection, credential(connection), activePart) }
                    .onFailure { cleanupError -> error.addSuppressed(cleanupError) }
            }
            throw error
        }
    }

    private suspend fun copyKeepBoth(
        connection: ConnectionConfig,
        part: AppOwnedPart,
        originalPath: String,
        expected: DigestResult,
        sourceSize: Long,
    ): CopyFileResult {
        if (!exists(connection, originalPath)) {
            val promoted = tryPromote(connection, part, originalPath)
            if (promoted != null) {
                verifyPromoted(connection, promoted, expected)
                return copied(originalPath, sourceSize, expected)
            }
        }

        if (exists(connection, originalPath) && remoteDigest(connection, originalPath) == expected) {
            copyClient.removePart(connection, credential(connection), part)
            return unchanged(originalPath, sourceSize, expected)
        }

        val parent = RemotePath.parent(originalPath)
        val originalName = originalPath.substringAfterLast('/')
        for (number in 1..MAX_KEEP_BOTH_ATTEMPTS) {
            coroutineContext.ensureActive()
            val candidateName = WindowsConflictNaming.numbered(originalName, number)
            val candidate = RemotePath.join(parent, candidateName)
            if (exists(connection, candidate)) {
                if (remoteDigest(connection, candidate) == expected) {
                    copyClient.removePart(connection, credential(connection), part)
                    return CopyFileResult(CopyFileStatus.REUSED_EXISTING, candidate, sourceSize, expected.sha256)
                }
                continue
            }

            val promoted = tryPromote(connection, part, candidate)
            if (promoted != null) {
                verifyPromoted(connection, promoted, expected)
                return copied(candidate, sourceSize, expected)
            }
        }
        throw IllegalStateException("Unable to allocate a Keep Both destination")
    }

    private suspend fun copyReplaceWithBackup(
        connection: ConnectionConfig,
        part: AppOwnedPart,
        originalPath: String,
        expected: DigestResult,
        sourceSize: Long,
        operationId: String,
        now: Instant,
    ): CopyFileResult {
        if (!exists(connection, originalPath)) {
            val promoted = tryPromote(connection, part, originalPath)
            if (promoted != null) {
                verifyPromoted(connection, promoted, expected)
                return copied(originalPath, sourceSize, expected)
            }
        }

        if (exists(connection, originalPath) && remoteDigest(connection, originalPath) == expected) {
            copyClient.removePart(connection, credential(connection), part)
            return unchanged(originalPath, sourceSize, expected)
        }

        require(exists(connection, originalPath)) { "Destination disappeared before replacement" }
        val backupPath = backupPath(now, operationId, originalPath)
        copyClient.moveToBackup(connection, credential(connection), originalPath, backupPath)

        val promoted = try {
            copyClient.promotePart(connection, credential(connection), part, originalPath)
        } catch (error: Throwable) {
            restoreBackupAfterPromotionFailure(connection, backupPath, originalPath, error)
            throw error
        }

        try {
            verifyPromoted(connection, promoted, expected)
        } catch (error: Throwable) {
            rollbackPromotedReplacement(connection, promoted, backupPath, originalPath, error)
            throw error
        }

        return CopyFileResult(
            CopyFileStatus.COPIED,
            originalPath,
            sourceSize,
            expected.sha256,
            backupPath,
        )
    }

    private suspend fun tryPromote(connection: ConnectionConfig, part: AppOwnedPart, finalPath: String): PromotedUpload? = try {
        copyClient.promotePart(connection, credential(connection), part, finalPath)
    } catch (error: IllegalArgumentException) {
        if (exists(connection, finalPath)) null else throw error
    }

    private suspend fun verifyPromoted(connection: ConnectionConfig, promoted: PromotedUpload, expected: DigestResult) {
        val actual = remoteDigest(connection, promoted.relativePath)
        requireExactSize(actual.bytes, expected.bytes, "Promoted destination size mismatch")
        requireSameHash(expected, actual, "Promoted destination SHA-256 mismatch")
    }

    private suspend fun restoreBackupAfterPromotionFailure(
        connection: ConnectionConfig,
        backupPath: String,
        originalPath: String,
        originalError: Throwable,
    ) {
        runCatching {
            copyClient.restoreBackup(connection, credential(connection), backupPath, originalPath)
        }.onFailure { restoreError ->
            originalError.addSuppressed(CopyRestoreException(restoreError))
        }
    }

    private suspend fun rollbackPromotedReplacement(
        connection: ConnectionConfig,
        promoted: PromotedUpload,
        backupPath: String,
        originalPath: String,
        originalError: Throwable,
    ) {
        try {
            copyClient.removePromotedForRestore(connection, credential(connection), promoted)
        } catch (removeError: Throwable) {
            originalError.addSuppressed(removeError)
            return
        }
        runCatching {
            copyClient.restoreBackup(connection, credential(connection), backupPath, originalPath)
        }.onFailure { restoreError ->
            originalError.addSuppressed(CopyRestoreException(restoreError))
        }
    }

    private suspend fun exists(connection: ConnectionConfig, path: String): Boolean =
        copyClient.exists(connection, credential(connection), path)

    private suspend fun remoteDigest(connection: ConnectionConfig, path: String): DigestResult =
        readClient.openRead(connection, credential(connection), path).use { handle -> hash(handle.input) }

    private fun credential(connection: ConnectionConfig) =
        requireNotNull(credentialStore.get(connection.id)) { "Credential unavailable" }

    private suspend fun copyAndHash(input: InputStream, output: java.io.OutputStream, expectedSize: Long): DigestResult {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            coroutineContext.ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read.toLong()
            if (total > expectedSize) throw CopyIntegrityException("Source exceeded expected size")
            output.write(buffer, 0, read)
            digest.update(buffer, 0, read)
        }
        output.flush()
        return DigestResult(total, digest.digest().toHex())
    }

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

    private fun requireExactSize(actual: Long, expected: Long, message: String) {
        if (actual != expected) throw CopyIntegrityException(message)
    }

    private fun requireSameHash(expected: DigestResult, actual: DigestResult, message: String) {
        if (expected.sha256 != actual.sha256) throw CopyIntegrityException(message)
    }

    private fun copied(path: String, sourceSize: Long, digest: DigestResult) =
        CopyFileResult(CopyFileStatus.COPIED, path, sourceSize, digest.sha256)

    private fun unchanged(path: String, sourceSize: Long, digest: DigestResult) =
        CopyFileResult(CopyFileStatus.UNCHANGED, path, sourceSize, digest.sha256)

    private fun backupPath(now: Instant, operationId: String, originalPath: String): String =
        "${CopyDestinationPath.RESERVED_BACKUP_DIRECTORY}/${BACKUP_TIMESTAMP.format(now)}_${operationId}/$originalPath"

    private data class DigestResult(val bytes: Long, val sha256: String)

    private fun ByteArray.toHex(): String = joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val MAX_KEEP_BOTH_ATTEMPTS = 10_000
        val BACKUP_TIMESTAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC)
    }
}
