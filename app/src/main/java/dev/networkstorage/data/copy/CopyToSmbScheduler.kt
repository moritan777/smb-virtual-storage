package dev.networkstorage.data.copy

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.networkstorage.data.db.CopyNetworkPolicy
import dev.networkstorage.data.db.CopyRuleEntity
import dev.networkstorage.data.worker.CopyToSmbWorker
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
            .addTag(manualRunTag(System.currentTimeMillis()))
            .build()
        workManager.enqueueUniqueWork(manualName(ruleId), ExistingWorkPolicy.KEEP, request)
    }

    fun schedule(rule: CopyRuleEntity) {
        require(rule.periodicIntervalMinutes in CopyPersistenceRepository.SUPPORTED_INTERVALS)
        if (!rule.automaticCopyEnabled) {
            cancelPeriodic(rule.id)
            return
        }
        val request = PeriodicWorkRequestBuilder<CopyToSmbWorker>(rule.periodicIntervalMinutes, TimeUnit.MINUTES)
            .setInputData(input(rule.id, CopyToSmbWorker.TRIGGER_PERIODIC))
            .setConstraints(constraints(rule))
            .build()
        workManager.enqueueUniquePeriodicWork(periodicName(rule.id), ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancelPeriodic(ruleId: String) { require(ruleId.isNotBlank()); workManager.cancelUniqueWork(periodicName(ruleId)) }
    fun cancelManual(ruleId: String) { require(ruleId.isNotBlank()); workManager.cancelUniqueWork(manualName(ruleId)) }

    suspend fun reconcileAutomaticRules() {
        persistence.allRules().forEach { rule ->
            if (rule.automaticCopyEnabled) schedule(rule) else cancelPeriodic(rule.id)
        }
    }

    internal fun constraints(rule: CopyRuleEntity): Constraints = Constraints.Builder()
        .setRequiredNetworkType(when (rule.networkPolicy) {
            CopyNetworkPolicy.ANY_CONNECTED -> NetworkType.CONNECTED
            CopyNetworkPolicy.UNMETERED_ONLY -> NetworkType.UNMETERED
        })
        .setRequiresCharging(rule.requiresCharging)
        .setRequiresBatteryNotLow(rule.requiresBatteryNotLow)
        .setRequiresStorageNotLow(rule.requiresStorageNotLow)
        .build()

    private fun input(ruleId: String, trigger: String) = Data.Builder()
        .putString(CopyToSmbWorker.KEY_RULE_ID, ruleId)
        .putString(CopyToSmbWorker.KEY_TRIGGER, trigger)
        .build()

    companion object {
        private const val MANUAL_RUN_TAG_PREFIX = "copy-to-smb-manual-enqueued-at-"

        fun manualName(ruleId: String) = "copy-to-smb-manual-$ruleId"
        fun periodicName(ruleId: String) = "copy-to-smb-periodic-$ruleId"

        internal fun manualRunTag(enqueuedAt: Long) = "$MANUAL_RUN_TAG_PREFIX$enqueuedAt"

        internal fun manualEnqueuedAt(tags: Set<String>): Long? = tags.asSequence()
            .filter { it.startsWith(MANUAL_RUN_TAG_PREFIX) }
            .mapNotNull { it.removePrefix(MANUAL_RUN_TAG_PREFIX).toLongOrNull() }
            .maxOrNull()
    }
}
