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
import dev.networkstorage.data.settings.StorageRootKind
import dev.networkstorage.data.worker.ScanWorker
import dev.networkstorage.data.worker.DownloadWorker
import dev.networkstorage.data.cache.CachePolicy
import dev.networkstorage.data.smb.SmbClient
import dev.networkstorage.domain.ConnectionConfig
import dev.networkstorage.domain.Credential
import dev.networkstorage.domain.RemotePath
import dev.networkstorage.domain.FolderNavigation
import dev.networkstorage.presentation.ExternalOpenService
import android.net.Uri
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
enum class LocalFileState { REMOTE_ONLY, DOWNLOADING, CACHED, REMOTE_UPDATED, FAILED }
data class BrowserItem(val relativePath: String, val name: String, val isDirectory: Boolean, val size: Long, val lastModified: Long, val mode: FolderMode, val remoteExists: Boolean, val localState: LocalFileState = LocalFileState.REMOTE_ONLY)
data class ScanUiState(val workId: UUID? = null, val state: WorkInfo.State? = null, val count: Long = 0, val path: String = "", val total: Long = 0)
data class RemotePickerState(val visible: Boolean=false, val path: String="", val folders: List<String> = emptyList(), val loading: Boolean=false, val error: String?=null)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MainViewModel @Inject constructor(application: Application, private val repository: IndexRepository, private val dao: AppDao, private val settings: SettingsRepository, private val smb: SmbClient, private val externalOpen: ExternalOpenService) : AndroidViewModel(application) {
    private val workManager = WorkManager.getInstance(application)
    val screen = MutableStateFlow(AppScreen.CONNECTIONS)
    val connections = dao.observeConnectionSummaries().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val selectedConnection = MutableStateFlow<ConnectionSummary?>(null)
    val currentPath = MutableStateFlow("")
    val cacheLimitBytes = settings.cacheLimitBytes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_CACHE_LIMIT_BYTES)
    val cacheRootUri = settings.cacheRootUri.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val mirrorRootUri = settings.mirrorRootUri.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val cacheUsage = dao.observeCacheUsage().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)
    val download = MutableStateFlow<ScanUiState>()
    val remotePicker = MutableStateFlow(RemotePickerState())
    val scan = MutableStateFlow(ScanUiState())
    val message = MutableStateFlow<String?>(null)

    val browserItems: Flow<PagingData<BrowserItem>> = combine(selectedConnection, currentPath) { connection, path -> connection?.connection?.id to path }
        .flatMapLatest { (connectionId, path) ->
            if (connectionId == null) flowOf(PagingData.empty()) else Pager(PagingConfig(pageSize = 50, prefetchDistance = 15)) { dao.children(connectionId, path) }.flow
                .map { data -> data.map { row ->
                    val entry = row.entry
                    val state = when (row.cacheState) {
                        dev.networkstorage.domain.CacheState.DOWNLOADING -> LocalFileState.DOWNLOADING
                        dev.networkstorage.domain.CacheState.FAILED -> LocalFileState.FAILED
                        dev.networkstorage.domain.CacheState.CACHED -> if (CachePolicy.isValid(row.cacheRemoteSize ?: -1, row.cacheRemoteLastModified ?: -1, entry.size, entry.lastModified)) LocalFileState.CACHED else LocalFileState.REMOTE_UPDATED
                        null -> LocalFileState.REMOTE_ONLY
                    }
                    BrowserItem(entry.relativePath, entry.name, entry.isDirectory, entry.size, entry.lastModified, entry.mode, entry.remoteExists, state)
                } }
        }.cachedIn(viewModelScope)

    fun browse(connection: ConnectionSummary) { selectedConnection.value = connection; currentPath.value = ""; screen.value = AppScreen.BROWSER }
    fun openFolder(item: BrowserItem) { if (item.isDirectory) currentPath.value = item.relativePath else enqueueDownload(item) }
    fun browserBack(): Boolean = when {
        FolderNavigation.hasParent(currentPath.value) -> { currentPath.value = FolderNavigation.parent(currentPath.value); true }
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

    fun saveStorageRoot(kind: StorageRootKind, uri: Uri) = viewModelScope.launch {
        runCatching { settings.setStorageRoot(kind, uri.toString()) }.onSuccess { message.value = "Storage folder saved; existing files were not moved" }.onFailure { message.value = "Cache and Mirror folders must not be the same or nested" }
    }

    fun enqueueDownload(item: BrowserItem) {
        val connectionId = selectedConnection.value?.connection?.id ?: return
        if (cacheRootUri.value == null) { message.value = "Set the On-demand Cache folder in Settings"; screen.value = AppScreen.SETTINGS; return }
        val request = OneTimeWorkRequestBuilder<DownloadWorker>().setInputData(workDataOf(DownloadWorker.KEY_CONNECTION to connectionId, DownloadWorker.KEY_PATH to item.relativePath)).build()
        workManager.enqueueUniqueWork("on-demand-download-queue", ExistingWorkPolicy.APPEND, request)
        download.value = ScanUiState(request.id, WorkInfo.State.ENQUEUED, 0, item.name)
        viewModelScope.launch { workManager.getWorkInfoByIdFlow(request.id).collect { info ->
            if (info == null) return@collect
            download.value = ScanUiState(request.id, info.state, info.progress.getLong(DownloadWorker.KEY_COPIED, 0), item.name, info.progress.getLong(DownloadWorker.KEY_TOTAL, item.size))
            if (info.state == WorkInfo.State.SUCCEEDED) {
                if (info.outputData.getBoolean(DownloadWorker.KEY_OVER_LIMIT, false)) message.value = "Cache limit exceeded; no files were automatically deleted"
                val uri = info.outputData.getString(DownloadWorker.KEY_URI)?.let(Uri::parse)
                if (uri != null && !externalOpen.open(uri, item.name)) message.value = "No app can open this file"
            } else if (info.state == WorkInfo.State.FAILED) message.value = if (info.outputData.getString(DownloadWorker.KEY_ERROR) == "CACHE_ROOT_UNCONFIGURED") "Set the On-demand Cache folder in Settings" else "Download failed"
        } }
    }
    fun cancelDownload() { download.value.workId?.let(workManager::cancelWorkById) }

    fun browseRemoteFolders(host: String, port: String, share: String, username: String, password: String, domain: String, path: String = remotePicker.value.path) = viewModelScope.launch {
        val normalized = runCatching { RemotePath.normalize(path) }.getOrElse { remotePicker.value = RemotePickerState(true, error="INVALID_PATH"); return@launch }
        remotePicker.value = RemotePickerState(true, normalized, loading=true)
        val chars = password.toCharArray()
        val config = ConnectionConfig("picker", "picker", host, port.toIntOrNull() ?: 445, share, "", username, domain.takeIf(String::isNotBlank), FolderMode.INDEX_ONLY)
        try {
            runCatching { smb.list(config, Credential(chars), normalized).filter { it.isDirectory }.map { it.relativePath } }.onSuccess { remotePicker.value = RemotePickerState(true, normalized, it) }.onFailure { remotePicker.value = RemotePickerState(true, normalized, error=(it as? dev.networkstorage.domain.SmbFailure)?.category?.name ?: "CONNECTION") }
        } finally { chars.fill('\u0000') }
    }
    fun closeRemotePicker() { remotePicker.value = RemotePickerState() }

    fun startScan(connection: ConnectionSummary) = startScan(connection.connection)
    private fun startScan(connection: ConnectionEntity) {
        val request = OneTimeWorkRequestBuilder<ScanWorker>().setInputData(workDataOf(ScanWorker.KEY_CONNECTION_ID to connection.id)).build()
        workManager.enqueueUniqueWork("manual-scan-${connection.id}", ExistingWorkPolicy.REPLACE, request)
        scan.value = ScanUiState(workId = request.id, state = WorkInfo.State.ENQUEUED)
        viewModelScope.launch { workManager.getWorkInfoByIdFlow(request.id).collect { info -> if (info != null) scan.value = ScanUiState(request.id, info.state, info.progress.getLong(ScanWorker.KEY_COUNT, 0), info.progress.getString(ScanWorker.KEY_PATH).orEmpty()) } }
    }
    fun cancelScan() { scan.value.workId?.let(workManager::cancelWorkById) }
}
