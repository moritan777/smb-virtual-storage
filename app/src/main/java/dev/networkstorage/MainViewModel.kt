package dev.networkstorage

import android.app.Application
import android.net.Uri
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
import dev.networkstorage.data.cache.CachePolicy
import dev.networkstorage.data.cache.CacheRepository
import dev.networkstorage.data.credential.CredentialStore
import dev.networkstorage.data.db.AppDao
import dev.networkstorage.data.db.ConnectionEntity
import dev.networkstorage.data.db.ConnectionSummary
import dev.networkstorage.data.mirror.MirrorDiffItem
import dev.networkstorage.data.mirror.MirrorRepository
import dev.networkstorage.data.mirror.MirrorSyncPolicy
import dev.networkstorage.data.mirror.MirrorSyncScheduler
import dev.networkstorage.data.settings.SettingsRepository
import dev.networkstorage.data.settings.StorageRootKind
import dev.networkstorage.data.smb.SmbClient
import dev.networkstorage.data.worker.DownloadWorker
import dev.networkstorage.data.worker.MirrorWorker
import dev.networkstorage.data.worker.ScanWorker
import dev.networkstorage.domain.ConnectionConfig
import dev.networkstorage.domain.Credential
import dev.networkstorage.domain.FolderMode
import dev.networkstorage.domain.FolderNavigation
import dev.networkstorage.domain.NetworkFolderPickerPolicy
import dev.networkstorage.domain.RemotePath
import dev.networkstorage.domain.UserFacingError
import dev.networkstorage.presentation.ExternalOpenService
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

enum class AppScreen { CONNECTIONS, CONNECTION_EDIT, BROWSER, MIRROR, SETTINGS }
enum class LocalFileState { REMOTE_ONLY, DOWNLOADING, CACHED, REMOTE_UPDATED, FAILED }
object BrowserPresentation {
    fun stateIcon(state: LocalFileState) = when (state) {
        LocalFileState.REMOTE_ONLY -> "☁"
        LocalFileState.DOWNLOADING -> "⏳"
        LocalFileState.CACHED -> "✓"
        LocalFileState.REMOTE_UPDATED -> "↓"
        LocalFileState.FAILED -> "!"
    }
    fun hasParent(path: String) = FolderNavigation.hasParent(path)
}

data class BrowserItem(val relativePath: String, val name: String, val isDirectory: Boolean, val size: Long, val lastModified: Long, val mode: FolderMode, val remoteExists: Boolean, val localState: LocalFileState = LocalFileState.REMOTE_ONLY)
data class ScanUiState(val workId: UUID? = null, val state: WorkInfo.State? = null, val count: Long = 0, val path: String = "", val total: Long = 0, val ownerId: String? = null)
data class MirrorUiState(val loading: Boolean = false, val items: List<MirrorDiffItem> = emptyList(), val workId: UUID? = null, val state: WorkInfo.State? = null, val currentPath: String = "", val copied: Long = 0, val currentTotal: Long = 0, val completedFiles: Int = 0, val totalFiles: Int = 0)
data class ConnectionEditorState(val id: String? = null, val name: String = "", val host: String = "", val port: String = "445", val username: String = "", val domain: String = "", val share: String = "", val basePath: String = "", val mode: FolderMode = FolderMode.ON_DEMAND) {
    val networkFolder: String get() = if (share.isBlank()) "Not selected" else "\\\\$host\\$share${basePath.takeIf(String::isNotBlank)?.let { "\\${it.replace('/', '\\')}" }.orEmpty()}"
}
data class RemotePickerState(val visible: Boolean = false, val share: String? = null, val path: String = "", val folders: List<String> = emptyList(), val loading: Boolean = false, val error: String? = null)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MainViewModel @Inject constructor(
    application: Application,
    private val repository: IndexRepository,
    private val dao: AppDao,
    private val settings: SettingsRepository,
    private val smb: SmbClient,
    private val credentials: CredentialStore,
    private val externalOpen: ExternalOpenService,
    private val cacheRepository: CacheRepository,
    private val mirrorRepository: MirrorRepository,
    private val mirrorSyncScheduler: MirrorSyncScheduler,
) : AndroidViewModel(application) {
    private val workManager = WorkManager.getInstance(application)
    val screen = MutableStateFlow(AppScreen.CONNECTIONS)
    val connections = dao.observeConnectionSummaries().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val selectedConnection = MutableStateFlow<ConnectionSummary?>(null)
    val currentPath = MutableStateFlow("")
    val cacheLimitBytes = settings.cacheLimitBytes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_CACHE_LIMIT_BYTES)
    val cacheRootUri = settings.cacheRootUri.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val mirrorRootUri = settings.mirrorRootUri.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val automaticMirrorSyncEnabled = settings.automaticMirrorSyncEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val automaticMirrorSyncIntervalMinutes = settings.automaticMirrorSyncIntervalMinutes.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsRepository.DEFAULT_AUTOMATIC_MIRROR_SYNC_INTERVAL_MINUTES)
    val automaticMirrorSyncLastRunAt = settings.automaticMirrorSyncLastRunAt.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)
    val automaticMirrorSyncLastStatus = settings.automaticMirrorSyncLastStatus.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsRepository.AUTOMATIC_SYNC_STATUS_NEVER)
    val automaticMirrorSyncLastFiles = settings.automaticMirrorSyncLastFiles.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)
    val automaticMirrorSyncLastBytes = settings.automaticMirrorSyncLastBytes.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)
    val cacheUsage = dao.observeCacheUsage().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)
    val download = MutableStateFlow(ScanUiState())
    val mirror = MutableStateFlow(MirrorUiState())
    val remotePicker = MutableStateFlow(RemotePickerState())
    val editor = MutableStateFlow(ConnectionEditorState())
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
    fun openFolder(item: BrowserItem) {
        if (item.isDirectory) { currentPath.value = item.relativePath; return }
        val connectionId = selectedConnection.value?.connection?.id ?: return
        viewModelScope.launch {
            val mirrored = runCatching { mirrorRepository.mirroredUriIfCurrent(connectionId, item.relativePath) }.getOrNull()
            if (mirrored != null) {
                if (!externalOpen.open(mirrored, item.name)) message.value = "No app can open this file"
                return@launch
            }
            val cached = runCatching { cacheRepository.cachedUriIfValid(connectionId, item.relativePath) }.getOrNull()
            if (cached != null) {
                if (!externalOpen.open(cached, item.name)) message.value = "No app can open this file"
            } else enqueueDownload(item)
        }
    }
    fun browserBack(): Boolean = when {
        FolderNavigation.hasParent(currentPath.value) -> { currentPath.value = FolderNavigation.parent(currentPath.value); true }
        screen.value == AppScreen.BROWSER -> { screen.value = AppScreen.CONNECTIONS; true }
        else -> false
    }
    fun showConnections() { screen.value = AppScreen.CONNECTIONS }
    fun showSettings() { screen.value = AppScreen.SETTINGS }
    fun openAddConnection() { editor.value = ConnectionEditorState(); screen.value = AppScreen.CONNECTION_EDIT }
    fun openEditConnection(summary: ConnectionSummary) { val c = summary.connection; editor.value = ConnectionEditorState(c.id, c.name, c.host, c.port.toString(), c.username, c.domain.orEmpty(), c.share, c.basePath, c.rootMode); screen.value = AppScreen.CONNECTION_EDIT }
    fun updateEditor(value: ConnectionEditorState) { editor.value = value }
    fun saveEditor(password: String) = viewModelScope.launch { val value = editor.value; runCatching { if (value.id == null) repository.addConnection(value.name, value.host, value.port.toIntOrNull() ?: 445, value.share, value.basePath, value.username, password.toCharArray(), value.domain, value.mode) else repository.updateConnection(value.id, value.name, value.host, value.port.toIntOrNull() ?: 445, value.share, value.basePath, value.username, password.takeIf(String::isNotEmpty)?.toCharArray(), value.domain, value.mode) }.onSuccess { message.value = "Connection saved"; screen.value = AppScreen.CONNECTIONS }.onFailure { message.value = "Check the connection information and network folder" } }
    fun deleteConnection(connection: ConnectionSummary) = viewModelScope.launch { workManager.cancelUniqueWork("manual-scan-${connection.connection.id}"); runCatching { repository.deleteConnection(connection.connection.id) }.onSuccess { if (selectedConnection.value?.connection?.id == connection.connection.id) selectedConnection.value = null; message.value = "Local connection and index deleted" }.onFailure { message.value = "Could not safely delete the local connection" } }
    fun deleteRootIndex(connection: ConnectionSummary) = viewModelScope.launch { workManager.cancelUniqueWork("manual-scan-${connection.connection.id}"); runCatching { repository.deleteRootIndex(connection.connection.id) }.onSuccess { message.value = "Local index target deleted" }.onFailure { message.value = "Could not delete the local index target" } }
    fun setCacheLimitGib(input: String) = viewModelScope.launch { SettingsRepository.gibToBytes(input).onSuccess { bytes -> settings.setCacheLimitBytes(bytes); message.value = "Cache limit saved" }.onFailure { message.value = "Enter a whole number of at least 1 GB" } }
    fun saveStorageRoot(kind: StorageRootKind, uri: Uri) = viewModelScope.launch { runCatching { settings.setStorageRoot(kind, uri.toString()) }.onSuccess { message.value = "Storage folder saved; existing files were not moved" }.onFailure { message.value = "Storage folder overlaps Cache, Mirror, or an existing Copy source" } }
    fun setAutomaticMirrorSyncEnabled(enabled: Boolean) = viewModelScope.launch {
        if (enabled && mirrorRootUri.value == null) { message.value = "Set the Mirror folder before enabling automatic sync"; return@launch }
        runCatching { settings.setAutomaticMirrorSyncEnabled(enabled); if (enabled) mirrorSyncScheduler.schedule(automaticMirrorSyncIntervalMinutes.value) else mirrorSyncScheduler.cancel() }
            .onSuccess { message.value = if (enabled) "Automatic Mirror sync enabled" else "Automatic Mirror sync disabled" }
            .onFailure { message.value = "Could not update automatic Mirror sync" }
    }

    fun setAutomaticMirrorSyncIntervalMinutes(minutes: Long) = viewModelScope.launch {
        runCatching {
            settings.setAutomaticMirrorSyncIntervalMinutes(minutes)
            if (automaticMirrorSyncEnabled.value) mirrorSyncScheduler.schedule(minutes)
        }.onSuccess { message.value = "Automatic Mirror sync interval saved" }
            .onFailure { message.value = "Could not update automatic Mirror sync interval" }
    }

    fun navigateBack() { screen.value = AppScreen.CONNECTIONS }

    private fun enqueueDownload(item: BrowserItem) {
        val connectionId = selectedConnection.value?.connection?.id ?: return
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf("connectionId" to connectionId, "relativePath" to item.relativePath))
            .build()
        workManager.enqueueUniqueWork("download-$connectionId-${item.relativePath}", ExistingWorkPolicy.KEEP, request)
    }
}
