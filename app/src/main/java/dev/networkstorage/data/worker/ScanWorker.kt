package dev.networkstorage.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.networkstorage.data.IndexRepository
import dev.networkstorage.data.cache.CacheRepository
import dev.networkstorage.domain.UserFacingError

@HiltWorker
class ScanWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val repository: IndexRepository,
    private val cacheRepository: CacheRepository,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val connectionId = inputData.getString(KEY_CONNECTION_ID)
            ?: return Result.failure(error(UserFacingError.UNKNOWN))
        return try {
            var finalCount = 0L
            repository.scan(connectionId, id.toString()) { count, path ->
                finalCount = count
                setProgress(Data.Builder().putLong(KEY_COUNT, count).putString(KEY_PATH, path).build())
            }
            // The scan is already durably successful here. Orphan cache cleanup is deliberately
            // best-effort and must never downgrade a successful SMB scan to failure.
            val cleanedBytes = runCatching { cacheRepository.cleanupOrphans(connectionId) }.getOrDefault(0L)
            Result.success(
                Data.Builder()
                    .putLong(KEY_COUNT, finalCount)
                    .putLong(KEY_CLEANED_CACHE_BYTES, cleanedBytes)
                    .build()
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            Result.failure(error(UserFacingError.code(throwable)))
        }
    }

    private fun error(code: String) = Data.Builder()
        .putString(KEY_ERROR_CODE, code)
        .putString(KEY_ERROR_MESSAGE, UserFacingError.message(code))
        .build()

    companion object {
        const val KEY_CONNECTION_ID = "connection_id"
        const val KEY_COUNT = "entry_count"
        const val KEY_PATH = "current_path"
        const val KEY_CLEANED_CACHE_BYTES = "cleaned_cache_bytes"
        const val KEY_ERROR_CODE = "error_code"
        const val KEY_ERROR_MESSAGE = "error_message"
    }
}
