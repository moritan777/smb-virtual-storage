package dev.networkstorage.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.ForegroundInfo
import androidx.core.app.NotificationCompat
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.content.pm.ServiceInfo
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.networkstorage.data.cache.CacheRepository
import dev.networkstorage.data.credential.CredentialStore

@HiltWorker
class DownloadWorker @AssistedInject constructor(@Assisted context: Context, @Assisted params: WorkerParameters, private val cache: CacheRepository, private val credentials: CredentialStore) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val connectionId = inputData.getString(KEY_CONNECTION) ?: return Result.failure()
        val path = inputData.getString(KEY_PATH) ?: return Result.failure()
        setForeground(downloadForeground(path))
        val credential = credentials.get(connectionId) ?: return Result.failure(message("CREDENTIAL_UNAVAILABLE"))
        return try {
            val result = cache.obtain(connectionId, path, credential) { copied, total -> setProgress(Data.Builder().putLong(KEY_COPIED, copied).putLong(KEY_TOTAL, total).build()) }
            Result.success(Data.Builder().putString(KEY_URI, result.uri.toString()).putBoolean(KEY_REUSED, result.reused).putBoolean(KEY_OVER_LIMIT, result.overLimit).build())
        } catch (_: kotlinx.coroutines.CancellationException) { throw kotlinx.coroutines.CancellationException() }
        catch (error: Throwable) { Result.failure(message(if (error.message == "CACHE_ROOT_UNCONFIGURED") "CACHE_ROOT_UNCONFIGURED" else "DOWNLOAD_FAILED")) }
        finally { credential.password.fill('\u0000') }
    }
    private fun message(value: String) = Data.Builder().putString(KEY_ERROR, value).build()
    private fun downloadForeground(path: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "File downloads", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Downloading file").setContentText(path.substringAfterLast('/')).setOngoing(true).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(NOTIFICATION_ID, notification)
    }
    companion object { const val KEY_CONNECTION="connection"; const val KEY_PATH="path"; const val KEY_COPIED="copied"; const val KEY_TOTAL="total"; const val KEY_URI="uri"; const val KEY_REUSED="reused"; const val KEY_OVER_LIMIT="over_limit"; const val KEY_ERROR="error"; const val CHANNEL="on-demand-downloads"; const val NOTIFICATION_ID=4104 }
}
