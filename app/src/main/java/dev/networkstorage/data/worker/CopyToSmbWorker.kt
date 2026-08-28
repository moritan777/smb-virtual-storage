package dev.networkstorage.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.networkstorage.data.copy.CopyPersistenceRepository
import dev.networkstorage.data.copy.CopyRuleExecutionGate
import dev.networkstorage.data.copy.CopyToSmbTreeExecutor
import dev.networkstorage.data.copy.TreeCopyFileOutcome
import dev.networkstorage.data.copy.TreeCopyProgress
import dev.networkstorage.data.db.AppDao
import dev.networkstorage.data.db.ConnectionEntity
import dev.networkstorage.domain.ConnectionConfig
import dev.networkstorage.domain.NetworkError
import dev.networkstorage.domain.SmbFailure
import kotlinx.coroutines.CancellationException
import java.util.UUID

@HiltWorker
class CopyToSmbWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val dao: AppDao,
    private val persistence: CopyPersistenceRepository,
    private val executor: CopyToSmbTreeExecutor,
    private val gate: CopyRuleExecutionGate,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val ruleId = inputData.getString(KEY_RULE_ID)?.takeIf { it.isNotBlank() } ?: return Result.failure()
        val trigger = inputData.getString(KEY_TRIGGER) ?: TRIGGER_MANUAL

        return gate.withRuleLock(ruleId) {
            val rule = persistence.rule(ruleId) ?: return@withRuleLock Result.success()
            if (trigger == TRIGGER_PERIODIC && !rule.automaticCopyEnabled) return@withRuleLock Result.success()
            val connection = dao.connection(rule.connectionId)?.config() ?: return@withRuleLock Result.failure()
            val operationId = UUID.randomUUID().toString()

            setForeground(CopyToSmbForeground.info(applicationContext, "Preparing ${connection.name}…"))
            try {
                val result = executor.execute(
                    ruleId = rule.id,
                    connection = connection,
                    sourceTreeUri = rule.sourceTreeUri,
                    destinationDirectory = rule.destinationPath,
                    includeSubfolders = rule.includeSubfolders,
                    conflictPolicy = rule.conflictPolicy,
                    operationId = operationId,
                    onProgress = { progress ->
                        setProgress(progressData(operationId, progress))
                    },
                )
                val counts = Data.Builder()
                    .putInt(KEY_SUCCESS_COUNT, result.successCount)
                    .putInt(KEY_COPIED_COUNT, result.copiedCount)
                    .putInt(KEY_SKIPPED_COUNT, result.skippedCount)
                    .putInt(KEY_FAILURE_COUNT, result.failureCount)
                    .putInt(KEY_COMPLETED_COUNT, result.files.size)
                    .putInt(KEY_TOTAL_COUNT, result.files.size)
                    .putString(KEY_OPERATION_ID, operationId)
                    .build()
                setProgress(counts)
                val retryable = result.files.filterIsInstance<TreeCopyFileOutcome.Failed>()
                    .any { isRetryable(it.error) }
                if (result.successCount == 0 && retryable) Result.retry()
                else Result.success(counts)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (isRetryable(error)) Result.retry() else Result.failure()
            }
        }
    }

    private fun progressData(operationId: String, progress: TreeCopyProgress) = Data.Builder()
        .putString(KEY_OPERATION_ID, operationId)
        .putInt(KEY_COPIED_COUNT, progress.copiedCount)
        .putInt(KEY_SKIPPED_COUNT, progress.skippedCount)
        .putInt(KEY_FAILURE_COUNT, progress.failureCount)
        .putInt(KEY_COMPLETED_COUNT, progress.completedCount)
        .putInt(KEY_TOTAL_COUNT, progress.totalCount)
        .putString(KEY_CURRENT_SOURCE_PATH, progress.currentSourceRelativePath)
        .build()

    private fun isRetryable(error: Throwable): Boolean {
        if (error is SmbFailure) {
            return error.category in setOf(
                NetworkError.HOST_NOT_FOUND,
                NetworkError.CONNECTION,
                NetworkError.TIMEOUT,
                NetworkError.REMOTE_NOT_FOUND,
            )
        }
        return error.cause?.let(::isRetryable) == true || error.suppressed.any(::isRetryable)
    }

    private fun ConnectionEntity.config() = ConnectionConfig(
        id = id,
        name = name,
        host = host,
        port = port,
        share = share,
        basePath = basePath,
        username = username,
        domain = domain,
        mode = rootMode,
    )

    companion object {
        const val KEY_RULE_ID = "copy_rule_id"
        const val KEY_TRIGGER = "copy_trigger"
        const val KEY_OPERATION_ID = "copy_operation_id"
        const val KEY_SUCCESS_COUNT = "copy_success_count"
        const val KEY_COPIED_COUNT = "copy_copied_count"
        const val KEY_SKIPPED_COUNT = "copy_skipped_count"
        const val KEY_FAILURE_COUNT = "copy_failure_count"
        const val KEY_COMPLETED_COUNT = "copy_completed_count"
        const val KEY_TOTAL_COUNT = "copy_total_count"
        const val KEY_CURRENT_SOURCE_PATH = "copy_current_source_path"
        const val TRIGGER_MANUAL = "manual"
        const val TRIGGER_PERIODIC = "periodic"
    }
}
