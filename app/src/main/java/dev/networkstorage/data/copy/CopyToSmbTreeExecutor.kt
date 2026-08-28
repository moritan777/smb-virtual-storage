package dev.networkstorage.data.copy

import dev.networkstorage.data.db.CopyErrorCode
import dev.networkstorage.data.db.CopyHistoryStatus
import dev.networkstorage.domain.ConnectionConfig
import dev.networkstorage.domain.CopyDestinationPath
import dev.networkstorage.domain.NetworkError
import dev.networkstorage.domain.SmbFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

sealed interface TreeCopyFileOutcome {
    val sourceRelativePath: String

    data class Success(
        override val sourceRelativePath: String,
        val result: CopyFileResult,
    ) : TreeCopyFileOutcome

    data class Failed(
        override val sourceRelativePath: String,
        val error: Throwable,
    ) : TreeCopyFileOutcome
}

data class TreeCopyResult(
    val files: List<TreeCopyFileOutcome>,
) {
    val successCount: Int get() = files.count { it is TreeCopyFileOutcome.Success }
    val failureCount: Int get() = files.count { it is TreeCopyFileOutcome.Failed }
}

/**
 * Enumerates one persisted SAF tree and feeds each source file into the verified transfer core.
 * Independent file failures do not stop the rest of the tree; coroutine cancellation always does.
 * File-level outcomes are persisted with safe statuses/error codes only.
 */
class CopyToSmbTreeExecutor @Inject constructor(
    private val sourceTree: CopySourceTree,
    private val orchestrator: CopyToSmbOrchestrator,
    private val persistence: CopyPersistenceRepository,
) {
    suspend fun execute(
        ruleId: String,
        connection: ConnectionConfig,
        sourceTreeUri: String,
        destinationDirectory: String,
        includeSubfolders: Boolean,
        conflictPolicy: CopyConflictPolicy,
        operationId: String,
        completedAt: () -> Long = System::currentTimeMillis,
    ): TreeCopyResult {
        require(ruleId.isNotBlank()) { "Rule ID must not be blank" }
        require(operationId.isNotBlank()) { "Operation ID must not be blank" }
        val sources = sourceTree.listFiles(sourceTreeUri, includeSubfolders)
        val outcomes = ArrayList<TreeCopyFileOutcome>(sources.size)

        for ((index, source) in sources.withIndex()) {
            coroutineContext.ensureActive()
            val fileOperationId = "$operationId-$index"
            val expectedDestination = CopyDestinationPath.join(destinationDirectory, source.relativePath)
            try {
                val result = orchestrator.copyFile(
                    connection = connection,
                    destinationDirectory = destinationDirectory,
                    source = source,
                    conflictPolicy = conflictPolicy,
                    operationId = fileOperationId,
                )
                persistence.recordResult(
                    operationId = operationId,
                    ruleId = ruleId,
                    connectionId = connection.id,
                    sourceRelativePath = source.relativePath,
                    result = result,
                    completedAt = completedAt(),
                )
                outcomes += TreeCopyFileOutcome.Success(source.relativePath, result)
            } catch (error: CancellationException) {
                withContext(NonCancellable) {
                    runCatching {
                        persistence.recordFailure(
                            operationId = operationId,
                            ruleId = ruleId,
                            connectionId = connection.id,
                            sourceRelativePath = source.relativePath,
                            destinationRelativePath = expectedDestination,
                            sourceSize = source.size,
                            status = CopyHistoryStatus.CANCELLED,
                            errorCode = CopyErrorCode.CANCELLED,
                            completedAt = completedAt(),
                        )
                    }.onFailure { historyError -> error.addSuppressed(historyError) }
                }
                throw error
            } catch (error: Throwable) {
                runCatching {
                    persistence.recordFailure(
                        operationId = operationId,
                        ruleId = ruleId,
                        connectionId = connection.id,
                        sourceRelativePath = source.relativePath,
                        destinationRelativePath = expectedDestination,
                        sourceSize = source.size,
                        status = CopyHistoryStatus.FAILED,
                        errorCode = safeErrorCode(error),
                        completedAt = completedAt(),
                    )
                }.onFailure { historyError -> error.addSuppressed(historyError) }
                outcomes += TreeCopyFileOutcome.Failed(source.relativePath, error)
            }
        }

        return TreeCopyResult(outcomes)
    }

    private fun safeErrorCode(error: Throwable): CopyErrorCode = when (error) {
        is CancellationException -> CopyErrorCode.CANCELLED
        is FileNotFoundException, is SecurityException -> CopyErrorCode.SOURCE_UNAVAILABLE
        is CopyIntegrityException -> if (error.message?.contains("SHA-256") == true) {
            CopyErrorCode.HASH_MISMATCH
        } else {
            CopyErrorCode.SIZE_MISMATCH
        }
        is SmbFailure -> when (error.category) {
            NetworkError.AUTHENTICATION -> CopyErrorCode.AUTHENTICATION
            NetworkError.HOST_NOT_FOUND -> CopyErrorCode.HOST_NOT_FOUND
            NetworkError.SHARE_NOT_FOUND -> CopyErrorCode.SHARE_NOT_FOUND
            NetworkError.CONNECTION -> CopyErrorCode.CONNECTION
            NetworkError.TIMEOUT -> CopyErrorCode.TIMEOUT
            NetworkError.REMOTE_NOT_FOUND -> CopyErrorCode.REMOTE_NOT_FOUND
            NetworkError.CANCELLED -> CopyErrorCode.CANCELLED
            NetworkError.UNKNOWN -> CopyErrorCode.UNKNOWN
        }
        is IllegalArgumentException -> CopyErrorCode.DESTINATION_CONFLICT
        else -> CopyErrorCode.UNKNOWN
    }
}
