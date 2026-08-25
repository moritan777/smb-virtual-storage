package dev.networkstorage

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.networkstorage.data.IndexRepository
import dev.networkstorage.data.db.AppDao
import dev.networkstorage.data.db.ConnectionEntity
import dev.networkstorage.data.db.ConnectionSummary
import dev.networkstorage.data.settings.SettingsRepository
import dev.networkstorage.data.worker.ScanWorker
import dev.networkstorage.domain.FolderMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

enum class AppScreen { CONNECTIONS, BROWSER, SETTINGS }
enum class LocalFileState { REMOTE_ONLY }
data class BrowserItem(val relativePath: String, val name: String, val isDirectory: Boolean, val size: Long, val lastModified: Long, val mode: FolderMode, val remoteExists: Boolean, val localState: LocalFileState = LocalFileState.REMOTE_ONLY)
data class ScanUiState(val workId: UUID? = null, val state: WorkInfo.State? = null, val count: Long = 0, val path: String = "")

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MainViewModel @Inject constructor(application: Application, private val repository: IndexRepository, private val dao: AppDao, private val settings: SettingsRepository) : AndroidViewModel(application) {
    private val workManager = WorkManager.getInstance(application)
    val screen = MutableStateFlow(AppScreen.CONNECTIONS)
    val connections = dao.observeConnectionSummaries().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val selectedConnection = MutableStateFlow<ConnectionSummary?>(null)
    val currentPath = MutableStateFlow("")
    val cacheLimitBytes = settings.cacheLimitBytes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_CACHE_LIMIT_BYTES)
    val scan = MutableStateFlow(ScanUiState())
    val message = MutableStateFlow<String?>(null)

    val browserItems: Flow<PagingData<BrowserItem>> = combine(selectedConnection, currentPath) { connection, path -> connection?.connection?.id to path }
        .flatMapLatest { (connectionId, path) ->
            if (connectionId == null) flowOf(PagingData.empty()) else Pager(PagingConfig(pageSize = 50, prefetchDistance = 15)) { dao.children(connectionId, path) }.flow
                .map { data -> data.map { BrowserItem(it.relativePath, it.name, it.isDirectory, it.size, it.lastModified, it.mode, it.remoteExists) } }
        }.cachedIn(viewModelScope)

    fun browse(connection: ConnectionSummary) { selectedConnection.value = connection; currentPath.value = ""; screen.value = AppScreen.BROWSER }
    fun openFolder(item: BrowserItem) { if (item.isDirectory) currentPath.value = item.relativePath else message.value = "Download/Open will be added in Step 4" }
    fun browserBack(): Boolean = when {
        currentPath.value.isNotEmpty() -> { currentPath.value = currentPath.value.substringBeforeLast('/', ""); true }
        screen.value == AppScreen.BROWSER -> { screen.value = AppScreen.CONNECTIONS; true }
        else -> false
    }
    fun showConnections() { screen.value = AppScreen.CONNECTIONS }
    fun showSettings() { screen.value = AppScreen.SETTINGS }

    fun add(name: String, host: String, port: String, share: String, basePath: String, username: String, password: String, domain: String, mode: FolderMode) = viewModelScope.launch {
        runCatching { repository.addConnection(name, host, port.toIntOrNull() ?: 445, share, basePath, username, password.toCharArray(), domain, mode) }
            .onSuccess { message.value = "Connection saved" }.onFailure { message.value = "Invalid connection settings" }
    }

    fun deleteConnection(connection: ConnectionSummary) = viewModelScope.launch {
        workManager.cancelUniqueWork("manual-scan-${connection.connection.id}")
        runCatching { repository.deleteConnection(connection.connection.id) }
            .onSuccess { if (selectedConnection.value?.connection?.id == connection.connection.id) selectedConnection.value = null; message.value = "Local connection and index deleted" }
            .onFailure { message.value = "Could not safely delete the local connection" }
    }

    fun deleteRootIndex(connection: ConnectionSummary) = viewModelScope.launch {
        workManager.cancelUniqueWork("manual-scan-${connection.connection.id}")
        runCatching { repository.deleteRootIndex(connection.connection.id) }
            .onSuccess { message.value = "Local index target deleted" }.onFailure { message.value = "Could not delete the local index target" }
    }

    fun setCacheLimitGib(input: String) = viewModelScope.launch {
        SettingsRepository.gibToBytes(input).onSuccess { bytes -> settings.setCacheLimitBytes(bytes); message.value = "Cache limit saved" }
            .onFailure { message.value = "Enter a whole number of at least 1 GB" }
    }

    fun startScan(connection: ConnectionSummary) = startScan(connection.connection)
    private fun startScan(connection: ConnectionEntity) {
        val request = OneTimeWorkRequestBuilder<ScanWorker>().setInputData(workDataOf(ScanWorker.KEY_CONNECTION_ID to connection.id)).build()
        workManager.enqueueUniqueWork("manual-scan-${connection.id}", ExistingWorkPolicy.REPLACE, request)
        scan.value = ScanUiState(workId = request.id, state = WorkInfo.State.ENQUEUED)
        viewModelScope.launch { workManager.getWorkInfoByIdFlow(request.id).collect { info -> if (info != null) scan.value = ScanUiState(request.id, info.state, info.progress.getLong(ScanWorker.KEY_COUNT, 0), info.progress.getString(ScanWorker.KEY_PATH).orEmpty()) } }
    }
    fun cancelScan() { scan.value.workId?.let(workManager::cancelWorkById) }
}
