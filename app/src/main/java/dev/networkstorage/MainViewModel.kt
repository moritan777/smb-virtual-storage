package dev.networkstorage

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.networkstorage.data.IndexRepository
import dev.networkstorage.data.db.AppDao
import dev.networkstorage.data.db.ConnectionEntity
import dev.networkstorage.data.db.IndexedEntryEntity
import dev.networkstorage.data.worker.ScanWorker
import dev.networkstorage.domain.FolderMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class ScanUiState(val workId: UUID? = null, val state: WorkInfo.State? = null, val count: Long = 0, val path: String = "")

@HiltViewModel
class MainViewModel @Inject constructor(application: Application, private val repository: IndexRepository, private val dao: AppDao) : AndroidViewModel(application) {
    private val workManager = WorkManager.getInstance(application)
    val connections = dao.observeConnections().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val selectedId = MutableStateFlow<String?>(null)
    val entries: StateFlow<List<IndexedEntryEntity>> = selectedId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else dao.observePreview(id) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val scan = MutableStateFlow(ScanUiState())
    val message = MutableStateFlow<String?>(null)

    fun select(connection: ConnectionEntity) { selectedId.value = connection.id }

    fun add(name: String, host: String, port: String, share: String, basePath: String, username: String, password: String, domain: String, mode: FolderMode) = viewModelScope.launch {
        runCatching { repository.addConnection(name, host, port.toIntOrNull() ?: 445, share, basePath, username, password.toCharArray(), domain, mode) }
            .onSuccess { message.value = "Connection saved" }.onFailure { message.value = "Invalid connection settings" }
    }

    fun startScan(connection: ConnectionEntity) {
        selectedId.value = connection.id
        val request = OneTimeWorkRequestBuilder<ScanWorker>().setInputData(workDataOf(ScanWorker.KEY_CONNECTION_ID to connection.id)).build()
        workManager.enqueueUniqueWork("manual-scan-${connection.id}", ExistingWorkPolicy.REPLACE, request)
        scan.value = ScanUiState(workId = request.id, state = WorkInfo.State.ENQUEUED)
        viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(request.id).collect { info ->
                if (info != null) scan.value = ScanUiState(request.id, info.state, info.progress.getLong(ScanWorker.KEY_COUNT, 0), info.progress.getString(ScanWorker.KEY_PATH).orEmpty())
            }
        }
    }

    fun cancelScan() { scan.value.workId?.let(workManager::cancelWorkById) }
}
