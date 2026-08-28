package dev.networkstorage.data.copy

import dev.networkstorage.domain.ConnectionConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
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
 */
class CopyToSmbTreeExecutor @Inject constructor(
    private val sourceTree: CopySourceTree,
    private val orchestrator: CopyToSmbOrchestrator,
) {
    suspend fun execute(
        connection: ConnectionConfig,
        sourceTreeUri: String,
        destinationDirectory: String,
        includeSubfolders: Boolean,
        conflictPolicy: CopyConflictPolicy,
        operationId: String,
    ): TreeCopyResult {
        val sources = sourceTree.listFiles(sourceTreeUri, includeSubfolders)
        val outcomes = ArrayList<TreeCopyFileOutcome>(sources.size)

        for ((index, source) in sources.withIndex()) {
            coroutineContext.ensureActive()
            val fileOperationId = "$operationId-$index"
            try {
                val result = orchestrator.copyFile(
                    connection = connection,
                    destinationDirectory = destinationDirectory,
                    source = source,
                    conflictPolicy = conflictPolicy,
                    operationId = fileOperationId,
                )
                outcomes += TreeCopyFileOutcome.Success(source.relativePath, result)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                outcomes += TreeCopyFileOutcome.Failed(source.relativePath, error)
            }
        }

        return TreeCopyResult(outcomes)
    }
}
