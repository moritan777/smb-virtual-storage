package dev.networkstorage.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.networkstorage.data.mirror.MirrorDiffState
import dev.networkstorage.data.mirror.MirrorRepository

@HiltWorker
class MirrorWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val mirrorRepository: MirrorRepository,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val connectionId = inputData.getString(KEY_CONNECTION_ID) ?: return Result.failure(error("INVALID_CONNECTION"))
        val requestedPath = inputData.getString(KEY_PATH)
        return try {
            val candidates = mirrorRepository.compare(connectionId).filter {
                (requestedPath == null || it.relativePath == requestedPath) &&
                    (it.state == MirrorDiffState.REMOTE_ONLY || it.state == MirrorDiffState.REMOTE_NEWER)
            }
            var completed = 0
            var copiedBytes = 0L
            candidates.forEach { item ->
                val total = item.remoteSize ?: 0L
                mirrorRepository.copyRemoteToLocal(connectionId, item.relativePath) { copied, _ ->
                    setProgress(
                        Data.Builder()
                            .putString(KEY_CURRENT_PATH, item.relativePath)
                            .putLong(KEY_CURRENT_COPIED, copied)
                            .putLong(KEY_CURRENT_TOTAL, total)
                            .putInt(KEY_COMPLETED, completed)
                            .putInt(KEY_TOTAL_FILES, candidates.size)
                            .build()
                    )
                }
                completed += 1
                copiedBytes += total
            }
            Result.success(
                Data.Builder()
                    .putInt(KEY_COMPLETED, completed)
                    .putInt(KEY_TOTAL_FILES, candidates.size)
                    .putLong(KEY_COPIED_BYTES, copiedBytes)
                    .build()
            )
        } catch (_: kotlinx.coroutines.CancellationException) {
            throw kotlinx.coroutines.CancellationException()
        } catch (error: Throwable) {
            Result.failure(error(error.message ?: "MIRROR_COPY_FAILED"))
        }
    }

    private fun error(value: String) = Data.Builder().putString(KEY_ERROR, value).build()

    companion object {
        const val KEY_CONNECTION_ID = "connection_id"
        const val KEY_PATH = "relative_path"
        const val KEY_CURRENT_PATH = "current_path"
        const val KEY_CURRENT_COPIED = "current_copied"
        const val KEY_CURRENT_TOTAL = "current_total"
        const val KEY_COMPLETED = "completed_files"
        const val KEY_TOTAL_FILES = "total_files"
        const val KEY_COPIED_BYTES = "copied_bytes"
        const val KEY_ERROR = "error"
    }
}
