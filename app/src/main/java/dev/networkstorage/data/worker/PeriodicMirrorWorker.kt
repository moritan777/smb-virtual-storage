package dev.networkstorage.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.networkstorage.data.IndexRepository
import dev.networkstorage.data.db.AppDao
import dev.networkstorage.data.mirror.MirrorDiffState
import dev.networkstorage.data.mirror.MirrorRepository
import dev.networkstorage.data.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.UUID

@HiltWorker
class PeriodicMirrorWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val dao: AppDao,
    private val indexRepository: IndexRepository,
    private val mirrorRepository: MirrorRepository,
    private val settings: SettingsRepository,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        if (settings.mirrorRootUri.first() == null) return Result.success(summary(0, 0, 0L))

        return try {
            var completedConnections = 0
            var completedFiles = 0
            var copiedBytes = 0L

            dao.mirrorConnections().forEach { connection ->
                setProgress(
                    Data.Builder()
                        .putString(KEY_CONNECTION_ID, connection.id)
                        .putString(KEY_PHASE, PHASE_SCAN)
                        .putInt(KEY_COMPLETED_CONNECTIONS, completedConnections)
                        .putInt(KEY_COMPLETED_FILES, completedFiles)
                        .build()
                )

                // Refresh the durable Room index first. A failed/offline scan never purges
                // the previous index, so transient network loss cannot erase mirror intent.
                indexRepository.scan(connection.id, UUID.randomUUID().toString()) { _, path ->
                    setProgress(
                        Data.Builder()
                            .putString(KEY_CONNECTION_ID, connection.id)
                            .putString(KEY_PHASE, PHASE_SCAN)
                            .putString(KEY_CURRENT_PATH, path)
                            .putInt(KEY_COMPLETED_CONNECTIONS, completedConnections)
                            .putInt(KEY_COMPLETED_FILES, completedFiles)
                            .build()
                    )
                }

                val candidates = mirrorRepository.compare(connection.id).filter {
                    it.state == MirrorDiffState.REMOTE_ONLY || it.state == MirrorDiffState.REMOTE_NEWER
                }

                candidates.forEach { item ->
                    val size = item.remoteSize ?: 0L
                    mirrorRepository.copyRemoteToLocal(connection.id, item.relativePath) { copied, total ->
                        setProgress(
                            Data.Builder()
                                .putString(KEY_CONNECTION_ID, connection.id)
                                .putString(KEY_PHASE, PHASE_COPY)
                                .putString(KEY_CURRENT_PATH, item.relativePath)
                                .putLong(KEY_CURRENT_COPIED, copied)
                                .putLong(KEY_CURRENT_TOTAL, total)
                                .putInt(KEY_COMPLETED_CONNECTIONS, completedConnections)
                                .putInt(KEY_COMPLETED_FILES, completedFiles)
                                .build()
                        )
                    }
                    completedFiles += 1
                    copiedBytes += size
                }
                completedConnections += 1
            }

            Result.success(summary(completedConnections, completedFiles, copiedBytes))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Network loss and temporarily unreachable NAS devices are retryable.
            Result.retry()
        }
    }

    private fun summary(connections: Int, files: Int, bytes: Long) = Data.Builder()
        .putInt(KEY_COMPLETED_CONNECTIONS, connections)
        .putInt(KEY_COMPLETED_FILES, files)
        .putLong(KEY_COPIED_BYTES, bytes)
        .build()

    companion object {
        const val KEY_CONNECTION_ID = "connection_id"
        const val KEY_PHASE = "phase"
        const val KEY_CURRENT_PATH = "current_path"
        const val KEY_CURRENT_COPIED = "current_copied"
        const val KEY_CURRENT_TOTAL = "current_total"
        const val KEY_COMPLETED_CONNECTIONS = "completed_connections"
        const val KEY_COMPLETED_FILES = "completed_files"
        const val KEY_COPIED_BYTES = "copied_bytes"
        const val PHASE_SCAN = "scan"
        const val PHASE_COPY = "copy"
    }
}
