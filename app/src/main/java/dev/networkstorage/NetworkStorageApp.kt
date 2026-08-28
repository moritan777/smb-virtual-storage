package dev.networkstorage

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.networkstorage.data.copy.CopyToSmbScheduler
import dev.networkstorage.data.mirror.MirrorSyncScheduler
import dev.networkstorage.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class NetworkStorageApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var mirrorSyncScheduler: MirrorSyncScheduler
    @Inject lateinit var copyToSmbScheduler: CopyToSmbScheduler

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            val enabled = settings.automaticMirrorSyncEnabled.first()
            if (enabled) {
                val interval = settings.automaticMirrorSyncIntervalMinutes.first()
                mirrorSyncScheduler.schedule(interval)
            } else {
                mirrorSyncScheduler.cancel()
            }
            copyToSmbScheduler.reconcileAutomaticRules()
        }
    }
}
