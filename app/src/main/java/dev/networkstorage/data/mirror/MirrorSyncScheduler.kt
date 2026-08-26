package dev.networkstorage.data.mirror

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.networkstorage.data.worker.PeriodicMirrorWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MirrorSyncScheduler @Inject constructor(@ApplicationContext context: Context) {
    private val workManager = WorkManager.getInstance(context)

    fun schedule(intervalMinutes: Long) {
        require(intervalMinutes >= MIN_INTERVAL_MINUTES)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<PeriodicMirrorWorker>(intervalMinutes, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel() {
        workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    companion object {
        const val UNIQUE_WORK_NAME = "periodic-mirror-sync"
        const val MIN_INTERVAL_MINUTES = 15L
    }
}
