package dev.networkstorage.data.copy

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dev.networkstorage.data.db.CopyNetworkPolicy
import dev.networkstorage.data.db.CopyRuleEntity
import dev.networkstorage.data.worker.CopyToSmbWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CopyToSmbScheduler @Inject constructor(
    @ApplicationContext context: Context,
    private val persistence: CopyPersistenceRepository,
) {
    private val workManager = WorkManager.getInstance(context)

    fun enqueueManual(ruleId: String) {
        require(ruleId.isNotBlank())
        val request = OneTimeWorkRequestBuilder<CopyToSmbWorker>()
            .setInputData(input(ruleId, CopyToSmbWorker.TRIGGER_MANUAL))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(manualRunTag(System.currentTimeMillis())).build()
        workManager.enqueueUniqueWork(manualName(ruleId), ExistingWorkPolicy.KEEP, request)
    }

    fun enqueueRetry(ruleId: String, relativePaths: Collection<String>) {
        require(ruleId.isNotBlank())
        val paths = relativePaths.filter { it.isNotBlank() }.distinct()
        require(paths.isNotEmpty()) { "No failed files to retry" }
        val request = OneTimeWorkRequestBuilder<CopyToSmbWorker>()
            .setInputData(input(ruleId, CopyToSmbWorker.TRIGGER_RETRY, paths))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(retryRunTag(System.currentTimeMillis())).build()
        workManager.enqueueUniqueWork(retryName(ruleId), ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Replaces the existing daily schedule and queues the next local wall-clock occurrence.
     * WorkManager may execute later than the requested time because Android controls background work.
     */
    fun schedule(rule: CopyRuleEntity) {
        require(rule.scheduledTimeMinutesOfDay in 0..1439)
        if (!rule.automaticCopyEnabled) {
            cancelPeriodic(rule.id)
            return
        }
        val request = scheduledRequest(rule, nextScheduledAt(rule.scheduledTimeMinutesOfDay, System.currentTimeMillis()))
        workManager.enqueueUniqueWork(periodicName(rule.id), ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * Queues the next occurrence behind the currently running daily worker. APPEND_OR_REPLACE
     * keeps the chain alive without cancelling the worker that is currently processing the run.
     */
    fun scheduleNext(rule: CopyRuleEntity) {
        if (!rule.automaticCopyEnabled) return
        val request = scheduledRequest(rule, nextScheduledAt(rule.scheduledTimeMinutesOfDay, System.currentTimeMillis()))
        workManager.enqueueUniqueWork(periodicName(rule.id), ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun cancelPeriodic(ruleId: String) {
        require(ruleId.isNotBlank())
        workManager.cancelUniqueWork(periodicName(ruleId))
    }

    fun cancelManual(ruleId: String) {
        require(ruleId.isNotBlank())
        workManager.cancelUniqueWork(manualName(ruleId))
    }

    fun cancelRetry(ruleId: String) {
        require(ruleId.isNotBlank())
        workManager.cancelUniqueWork(retryName(ruleId))
    }

    suspend fun reconcileAutomaticRules() {
        persistence.allRules().forEach { rule ->
            if (!rule.automaticCopyEnabled) {
                cancelPeriodic(rule.id)
            } else {
                val existing = workManager.getWorkInfosForUniqueWork(periodicName(rule.id)).get()
                if (existing.none { !it.state.isFinished }) schedule(rule)
            }
        }
    }

    private fun scheduledRequest(rule: CopyRuleEntity, scheduledAt: Long) =
        OneTimeWorkRequestBuilder<CopyToSmbWorker>()
            .setInitialDelay((scheduledAt - System.currentTimeMillis()).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .setInputData(input(rule.id, CopyToSmbWorker.TRIGGER_SCHEDULED).toBuilder()
                .putLong(CopyToSmbWorker.KEY_SCHEDULED_AT, scheduledAt).build())
            .setConstraints(backgroundConstraints(rule))
            .addTag(scheduleTag(rule.id))
            .build()

    // Charging and Wi-Fi are deliberately checked by the worker at execution time, not as
    // WorkManager constraints, so an unmet condition is recorded as a skipped run immediately.
    private fun backgroundConstraints(rule: CopyRuleEntity) = Constraints.Builder()
        .setRequiresBatteryNotLow(rule.requiresBatteryNotLow)
        .setRequiresStorageNotLow(rule.requiresStorageNotLow)
        .build()

    private fun nextScheduledAt(minutesOfDay: Int, nowMillis: Long): Long {
        val zone = ZoneId.systemDefault()
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val time = LocalTime.of(minutesOfDay / 60, minutesOfDay % 60)
        var candidate = now.toLocalDate().atTime(time).atZone(zone)
        if (!candidate.isAfter(now)) candidate = candidate.plusDays(1)
        return candidate.toInstant().toEpochMilli()
    }

    private fun input(ruleId: String, trigger: String, paths: Collection<String> = emptyList()) = Data.Builder()
        .putString(CopyToSmbWorker.KEY_RULE_ID, ruleId).putString(CopyToSmbWorker.KEY_TRIGGER, trigger)
        .apply { if (paths.isNotEmpty()) putStringArray(CopyToSmbWorker.KEY_ONLY_PATHS, paths.toTypedArray()) }.build()

    companion object {
        private const val MANUAL_RUN_TAG_PREFIX = "copy-to-smb-manual-enqueued-at-"
        private const val RETRY_RUN_TAG_PREFIX = "copy-to-smb-retry-enqueued-at-"
        fun manualName(ruleId: String) = "copy-to-smb-manual-$ruleId"
        fun periodicName(ruleId: String) = "copy-to-smb-daily-$ruleId"
        fun retryName(ruleId: String) = "copy-to-smb-retry-$ruleId"
        fun scheduleTag(ruleId: String) = "copy-to-smb-daily-tag-$ruleId"
        internal fun manualRunTag(enqueuedAt: Long) = "$MANUAL_RUN_TAG_PREFIX$enqueuedAt"
        internal fun retryRunTag(enqueuedAt: Long) = "$RETRY_RUN_TAG_PREFIX$enqueuedAt"
        internal fun manualEnqueuedAt(tags: Set<String>): Long? = tags.asSequence()
            .filter { it.startsWith(MANUAL_RUN_TAG_PREFIX) }
            .mapNotNull { it.removePrefix(MANUAL_RUN_TAG_PREFIX).toLongOrNull() }
            .maxOrNull()
    }
}
