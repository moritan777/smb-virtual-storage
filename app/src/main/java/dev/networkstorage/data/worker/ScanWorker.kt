package dev.networkstorage.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.networkstorage.data.IndexRepository

@HiltWorker
class ScanWorker @AssistedInject constructor(@Assisted context: Context, @Assisted parameters: WorkerParameters, private val repository: IndexRepository) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val connectionId = inputData.getString(KEY_CONNECTION_ID) ?: return Result.failure()
        return try {
            var finalCount = 0L
            repository.scan(connectionId, id.toString()) { count, path ->
                finalCount = count
                setProgress(Data.Builder().putLong(KEY_COUNT, count).putString(KEY_PATH, path).build())
            }
            Result.success(Data.Builder().putLong(KEY_COUNT, finalCount).build())
        } catch (_: kotlinx.coroutines.CancellationException) { throw kotlinx.coroutines.CancellationException() }
        catch (_: Throwable) { Result.failure() }
    }

    companion object { const val KEY_CONNECTION_ID = "connection_id"; const val KEY_COUNT = "entry_count"; const val KEY_PATH = "current_path" }
}
