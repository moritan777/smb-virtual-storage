package dev.networkstorage.data.worker

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.hilt.work.HiltWorker
import androidx.work.Data
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.networkstorage.data.copy.CopyPersistenceRepository
import dev.networkstorage.data.copy.CopyRuleExecutionGate
import dev.networkstorage.data.copy.CopyToSmbTreeExecutor
import dev.networkstorage.data.copy.CopyToSmbScheduler
import dev.networkstorage.data.copy.TreeCopyFileOutcome
import dev.networkstorage.data.copy.TreeCopyProgress
import dev.networkstorage.data.db.AppDao
import dev.networkstorage.data.db.ConnectionEntity
import dev.networkstorage.data.db.CopyErrorCode
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
    private val scheduler: CopyToSmbScheduler,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val ruleId = inputData.getString(KEY_RULE_ID)?.takeIf { it.isNotBlank() } ?: return Result.failure()
        val trigger = inputData.getString(KEY_TRIGGER) ?: TRIGGER_MANUAL
        val onlyPaths = inputData.getStringArray(KEY_ONLY_PATHS)?.toSet()?.filter { it.isNotBlank() }?.toSet()
        return gate.withRuleLock(ruleId) {
            val rule = persistence.rule(ruleId) ?: return@withRuleLock Result.success()
            if (trigger == TRIGGER_SCHEDULED && !rule.automaticCopyEnabled) return@withRuleLock Result.success()
            if (trigger == TRIGGER_SCHEDULED) scheduler.scheduleNext(rule)
            val connection = dao.connection(rule.connectionId)?.config() ?: return@withRuleLock Result.failure()
            val operationId = UUID.randomUUID().toString()
            if (trigger == TRIGGER_SCHEDULED) {
                val skippedReasons = buildList {
                    if (rule.requiresCharging && !isCharging()) add(CopyErrorCode.NOT_CHARGING)
                    when (rule.networkPolicy) {
                        dev.networkstorage.data.db.CopyNetworkPolicy.ANY_CONNECTED ->
                            if (!hasActiveNetwork()) add(CopyErrorCode.NETWORK_NOT_CONNECTED)
                        dev.networkstorage.data.db.CopyNetworkPolicy.UNMETERED_ONLY ->
                            if (!isUnmeteredNetwork()) add(CopyErrorCode.METERED_NETWORK)
                        dev.networkstorage.data.db.CopyNetworkPolicy.WIFI_ONLY ->
                            if (!isWifiConnected()) add(CopyErrorCode.WIFI_NOT_CONNECTED)
                    }
                }
                if (skippedReasons.isNotEmpty()) {
                    persistence.recordScheduleSkipped(rule.id, rule.connectionId, skippedReasons, System.currentTimeMillis())
                    return@withRuleLock Result.success(
                        Data.Builder().putString(KEY_OPERATION_ID, operationId)
                            .putInt(KEY_FAILURE_COUNT, 0).putInt(KEY_COPIED_COUNT, 0)
                            .putInt(KEY_SKIPPED_COUNT, skippedReasons.size).build()
                    )
                }
            }
            setForeground(CopyToSmbForeground.info(applicationContext, "Preparing ${connection.name}…"))
            try {
                val result = executor.execute(
                    ruleId = rule.id, connection = connection, sourceTreeUri = rule.sourceTreeUri,
                    destinationDirectory = rule.destinationPath, includeSubfolders = rule.includeSubfolders,
                    conflictPolicy = rule.conflictPolicy, operationId = operationId,
                    onlyRelativePaths = onlyPaths,
                    onProgress = { progress -> setProgress(progressData(operationId, progress)) },
                )
                val counts = Data.Builder()
                    .putInt(KEY_SUCCESS_COUNT, result.successCount).putInt(KEY_COPIED_COUNT, result.copiedCount)
                    .putInt(KEY_SKIPPED_COUNT, result.skippedCount).putInt(KEY_FAILURE_COUNT, result.failureCount)
                    .putInt(KEY_COMPLETED_COUNT, result.files.size).putInt(KEY_TOTAL_COUNT, result.files.size)
                    .putString(KEY_OPERATION_ID, operationId).build()
                setProgress(counts)
                val retryablePaths = result.files.filterIsInstance<TreeCopyFileOutcome.Failed>()
                    .filter { isRetryable(it.error) }.map { it.sourceRelativePath }
                if (retryablePaths.isNotEmpty() && result.successCount == 0) Result.retry()
                else Result.success(counts)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (isRetryable(error)) Result.retry() else Result.failure()
            }
        }
    }

    private fun isCharging(): Boolean {
        val battery = applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return false
        return when (battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
            else -> false
        }
    }

    private fun connectivityManager() =
        applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private fun activeCapabilities(): NetworkCapabilities? {
        val manager = connectivityManager()
        val network = manager.activeNetwork ?: return null
        return manager.getNetworkCapabilities(network)
    }

    private fun hasActiveNetwork(): Boolean = activeCapabilities() != null

    private fun isWifiConnected(): Boolean =
        activeCapabilities()?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

    private fun isUnmeteredNetwork(): Boolean =
        hasActiveNetwork() && !connectivityManager().isActiveNetworkMetered

    private fun progressData(operationId: String, progress: TreeCopyProgress) = Data.Builder()
        .putString(KEY_OPERATION_ID, operationId).putInt(KEY_COPIED_COUNT, progress.copiedCount)
        .putInt(KEY_SKIPPED_COUNT, progress.skippedCount).putInt(KEY_FAILURE_COUNT, progress.failureCount)
        .putInt(KEY_COMPLETED_COUNT, progress.completedCount).putInt(KEY_TOTAL_COUNT, progress.totalCount)
        .putString(KEY_CURRENT_SOURCE_PATH, progress.currentSourceRelativePath).build()

    private fun isRetryable(error: Throwable): Boolean {
        if (error is SmbFailure) return error.category in setOf(NetworkError.HOST_NOT_FOUND, NetworkError.CONNECTION, NetworkError.TIMEOUT, NetworkError.REMOTE_NOT_FOUND)
        return error.cause?.let(::isRetryable) == true || error.suppressed.any(::isRetryable)
    }

    private fun ConnectionEntity.config() = ConnectionConfig(id, name, host, port, share, basePath, username, domain, rootMode)

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
        const val KEY_ONLY_PATHS = "copy_only_relative_paths"
        const val TRIGGER_MANUAL = "manual"
        const val TRIGGER_SCHEDULED = "scheduled"
        const val KEY_SCHEDULED_AT = "copy_scheduled_at"
        const val TRIGGER_RETRY = "retry"
    }
}