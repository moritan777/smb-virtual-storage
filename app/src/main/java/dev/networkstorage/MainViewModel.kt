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
    fun stateIcon(state: LocalFileState) = when(state) { LocalFileState.REMOTE_ONLY -> "☁"; LocalFileState.DOWNLOADING -> "⏳"; LocalFileState.CACHED -> "✓"; LocalFileState.REMOTE_UPDATED -> "↓"; LocalFileState.FAILED -> "!" }
    fun hasParent(path: String) = FolderNavigation.hasParent(path)
}
data class BrowserItem(val relativePath: String, val name: String, val isDirectory: Boolean, val size: Long, val lastModified: Long, val mode: FolderMode, val remoteExists: Boolean, val localState: LocalFileState = LocalFileState.REMOTE_ONLY)
data class ScanUiState(val workId: UUID? = null, val state: WorkInfo.State? = null, val count: Long = 0, val path: String = "", val total: Long = 0, val ownerId: String? = null)
data class MirrorUiState(val loading: Boolean = false, val items: List<MirrorDiffItem> = emptyList(), val workId: UUID? = null, val state: WorkInfo.State? = null, val currentPath: String = "", val copied: Long = 0, val currentTotal: Long = 0, val completedFiles: Int = 0, val totalFiles: Int = 0)
data class ConnectionEditorState(val id: String?=null, val name: String="", val host: String="", val port: String="445", val username: String="", val domain: String="", val share: String="", val basePath: String="", val mode: FolderMode=FolderMode.ON_DEMAND) {
    val networkFolder: String get() = if (share.isBlank()) "Not selected" else "\\\\$host\\$share${basePath.takeIf(String::isNotBlank)?.let { "\\${it.replace('/', '\\')}" }.orEmpty()}"
}
data class RemotePickerState(val visible: Boolean=false, val share: String?=null, val path: String="", val folders: List<String> = emptyList(), val loading: Boolean=false, val error: String?=null)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MainViewModel @Inject constructor(application: Application, private val repository: IndexRepository, private val dao: AppDao, private val settings: SettingsRepository, private val smb: SmbClient, private val credentials: CredentialStore, private val externalOpen: ExternalOpenService, private val cacheRepository: CacheRepository, private val mirrorRepository: MirrorRepository) : AndroidViewModel(application) {
    private val workManager = WorkManager.getInstance(application)
    val screen = MutableStateFlow(AppScreen.CONNECTIONS)
    val connections = dao.observeConnectionSummaries().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val selectedConnection = MutableStateFlow<ConnectionSummary?>(null)
    val currentPath = MutableStateFlow("")
    val cacheLimitBytes = settings.cacheLimitBytes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_CACHE_LIMIT_BYTES)
    val cacheRootUri = settings.cacheRootUri.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val mirrorRootUri = settings.mirrorRootUri.stateIn(viewModelScope, SharingStarted.Eagerly, null)
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
        if (item.isDirectory) {
            currentPath.value = item.relativePath
            return
        }
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
            } else {
                enqueueDownload(item)
            }
        }
    }
    fun browserBack(): Boolean = when { FolderNavigation.hasParent(currentPath.value) -> { currentPath.value = FolderNavigation.parent(currentPath.value); true }; screen.value == AppScreen.BROWSER -> { screen.value = AppScreen.CONNECTIONS; true }; else -> false }
    fun showConnections() { screen.value = AppScreen.CONNECTIONS }
    fun showSettings() { screen.value = AppScreen.SETTINGS }
    fun openAddConnection() { editor.value = ConnectionEditorState(); screen.value = AppScreen.CONNECTION_EDIT }
    fun openEditConnection(summary: ConnectionSummary) { val c=summary.connection; editor.value=ConnectionEditorState(c.id,c.name,c.host,c.port.toString(),c.username,c.domain.orEmpty(),c.share,c.basePath,c.rootMode); screen.value=AppScreen.CONNECTION_EDIT }
    fun updateEditor(value: ConnectionEditorState) { editor.value = value }
    fun saveEditor(password: String) = viewModelScope.launch { val value=editor.value; runCatching { if(value.id==null) repository.addConnection(value.name,value.host,value.port.toIntOrNull()?:445,value.share,value.basePath,value.username,password.toCharArray(),value.domain,value.mode) else repository.updateConnection(value.id,value.name,value.host,value.port.toIntOrNull()?:445,value.share,value.basePath,value.username,password.takeIf(String::isNotEmpty)?.toCharArray(),value.domain,value.mode) }.onSuccess { message.value="Connection saved"; screen.value=AppScreen.CONNECTIONS }.onFailure { message.value="Check the connection information and network folder" } }
    fun deleteConnection(connection:ConnectionSummary)=viewModelScope.launch { workManager.cancelUniqueWork("manual-scan-${connection.connection.id}"); runCatching { repository.deleteConnection(connection.connection.id) }.onSuccess { if(selectedConnection.value?.connection?.id==connection.connection.id) selectedConnection.value=null; message.value="Local connection and index deleted" }.onFailure { message.value="Could not safely delete the local connection" } }
    fun deleteRootIndex(connection:ConnectionSummary)=viewModelScope.launch { workManager.cancelUniqueWork("manual-scan-${connection.connection.id}"); runCatching { repository.deleteRootIndex(connection.connection.id) }.onSuccess { message.value="Local index target deleted" }.onFailure { message.value="Could not delete the local index target" } }
    fun setCacheLimitGib(input:String)=viewModelScope.launch { SettingsRepository.gibToBytes(input).onSuccess { bytes->settings.setCacheLimitBytes(bytes); message.value="Cache limit saved" }.onFailure { message.value="Enter a whole number of at least 1 GB" } }
    fun saveStorageRoot(kind:StorageRootKind,uri:Uri)=viewModelScope.launch { runCatching { settings.setStorageRoot(kind,uri.toString()) }.onSuccess { message.value="Storage folder saved; existing files were not moved" }.onFailure { message.value="Cache and Mirror folders must not be the same or nested" } }
    fun removeCache(item: BrowserItem)=viewModelScope.launch { val id=selectedConnection.value?.connection?.id?:return@launch; runCatching { cacheRepository.remove(id,item.relativePath) }.onSuccess { message.value=if(it) "Cached copy removed" else "Cache could not be removed" }.onFailure { message.value="Cache could not be removed" } }
    fun clearCache()=viewModelScope.launch { if(download.value.state?.isFinished==false) { message.value="Wait for the current download to finish or cancel it first"; return@launch }; runCatching { cacheRepository.clearAll() }.onSuccess { message.value="Cache cleared (${it} bytes freed)" }.onFailure { message.value="Some cached files could not be removed" } }

    fun openMirror(connection: ConnectionSummary) {
        selectedConnection.value = connection
        screen.value = AppScreen.MIRROR
        compareMirror()
    }
    fun compareMirror() = viewModelScope.launch {
        val connectionId = selectedConnection.value?.connection?.id ?: return@launch
        if (mirrorRootUri.value == null) { message.value = "Set the Mirror folder in Settings"; screen.value = AppScreen.SETTINGS; return@launch }
        mirror.value = mirror.value.copy(loading = true)
        runCatching { mirrorRepository.compare(connectionId) }
            .onSuccess { mirror.value = mirror.value.copy(loading = false, items = it) }
            .onFailure { mirror.value = mirror.value.copy(loading = false); message.value = "Mirror comparison failed" }
    }
    fun syncMirror(item: MirrorDiffItem) {
        if (!MirrorSyncPolicy.canCopyRemoteToLocal(item.state)) return
        enqueueMirror(item.relativePath)
    }
    fun syncAllMirror() = enqueueMirror(null)
    private fun enqueueMirror(path: String?) {
        val connectionId = selectedConnection.value?.connection?.id ?: return
        if (mirrorRootUri.value == null) { message.value = "Set the Mirror folder in Settings"; screen.value = AppScreen.SETTINGS; return }
        val request = OneTimeWorkRequestBuilder<MirrorWorker>().setInputData(workDataOf(MirrorWorker.KEY_CONNECTION_ID to connectionId, MirrorWorker.KEY_PATH to path)).build()
        workManager.enqueueUniqueWork("mirror-sync-$connectionId", ExistingWorkPolicy.REPLACE, request)
        mirror.value = mirror.value.copy(workId = request.id, state = WorkInfo.State.ENQUEUED, currentPath = path.orEmpty(), copied = 0, currentTotal = 0, completedFiles = 0, totalFiles = 0)
        viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(request.id).collect { info ->
                if (info == null) return@collect
                mirror.value = mirror.value.copy(
                    workId = request.id,
                    state = info.state,
                    currentPath = info.progress.getString(MirrorWorker.KEY_CURRENT_PATH).orEmpty(),
                    copied = info.progress.getLong(MirrorWorker.KEY_CURRENT_COPIED, 0),
                    currentTotal = info.progress.getLong(MirrorWorker.KEY_CURRENT_TOTAL, 0),
                    completedFiles = info.progress.getInt(MirrorWorker.KEY_COMPLETED, 0),
                    totalFiles = info.progress.getInt(MirrorWorker.KEY_TOTAL_FILES, 0),
                )
                if (info.state == WorkInfo.State.SUCCEEDED) {
                    message.value = "Mirror sync completed"
                    compareMirror()
                } else if (info.state == WorkInfo.State.FAILED) {
                    message.value = "Mirror sync failed: ${info.outputData.getString(MirrorWorker.KEY_ERROR).orEmpty()}"
                }
            }
        }
    }
    fun cancelMirrorSync() { mirror.value.workId?.let(workManager::cancelWorkById) }

    fun enqueueDownload(item:BrowserItem) {
        val connectionId=selectedConnection.value?.connection?.id?:return
        if(cacheRootUri.value==null){message.value="Set the On-demand Cache folder in Settings";screen.value=AppScreen.SETTINGS;return}
        val request=OneTimeWorkRequestBuilder<DownloadWorker>().setInputData(workDataOf(DownloadWorker.KEY_CONNECTION to connectionId,DownloadWorker.KEY_PATH to item.relativePath)).build()
        workManager.enqueueUniqueWork("on-demand-download-queue",ExistingWorkPolicy.APPEND_OR_REPLACE,request);download.value=ScanUiState(request.id,WorkInfo.State.ENQUEUED,0,item.name)
        viewModelScope.launch { workManager.getWorkInfoByIdFlow(request.id).collect { info->if(info==null)return@collect;download.value=ScanUiState(request.id,info.state,info.progress.getLong(DownloadWorker.KEY_COPIED,0),item.name,info.progress.getLong(DownloadWorker.KEY_TOTAL,item.size));if(info.state==WorkInfo.State.SUCCEEDED){val evicted=info.outputData.getLong(DownloadWorker.KEY_EVICTED_BYTES,0);if(evicted>0)message.value="Old cache removed automatically to make space";val uri=info.outputData.getString(DownloadWorker.KEY_URI)?.let(Uri::parse);if(uri!=null&&!externalOpen.open(uri,item.name))message.value="No app can open this file"}else if(info.state==WorkInfo.State.FAILED){message.value=when(info.outputData.getString(DownloadWorker.KEY_ERROR)){"CACHE_ROOT_UNCONFIGURED"->"Set the On-demand Cache folder in Settings";"FILE_EXCEEDS_CACHE_LIMIT"->"This file is larger than the cache limit";"CACHE_LIMIT_CANNOT_BE_SATISFIED"->"Not enough removable cache space";else->"Download failed"}} } }
    }
    fun cancelDownload(){download.value.workId?.let(workManager::cancelWorkById)}
    fun openNetworkFolderPicker(password:String)=viewModelScope.launch { val value=editor.value;if(value.host.isBlank()||value.username.isBlank()||value.share.isBlank()||(password.isBlank()&&value.id==null)){message.value="Enter Host, Share, Username and Password first";return@launch};remotePicker.value=RemotePickerState(visible=true,share=value.share,path="",loading=true);loadPickerFolder(value,password,"") }
    fun browsePickerFolder(password:String,path:String)=viewModelScope.launch { loadPickerFolder(editor.value,password,path) }
    private suspend fun loadPickerFolder(value:ConnectionEditorState,password:String,path:String){val normalized=runCatching{RemotePath.normalize(path)}.getOrElse{remotePicker.value=remotePicker.value.copy(error="INVALID_PATH",loading=false);return};remotePicker.value=remotePicker.value.copy(visible=true,share=value.share,path=normalized,loading=true,error=null);withPickerCredential(value,password){config,credential->NetworkFolderPickerPolicy.folders(smb.list(config.copy(share=value.share),credential,normalized))}.onSuccess{remotePicker.value=remotePicker.value.copy(folders=it,loading=false)}.onFailure{remotePicker.value=remotePicker.value.copy(error=safePickerError(it),loading=false)}}
    fun usePickerFolder(){val picker=remotePicker.value;val selection=NetworkFolderPickerPolicy.selection(requireNotNull(picker.share),picker.path);editor.value=editor.value.copy(share=selection.share,basePath=selection.basePath);closeRemotePicker()}
    private suspend fun <T> withPickerCredential(value:ConnectionEditorState,password:String,block:suspend(ConnectionConfig,Credential)->T):Result<T>{val credential=if(password.isNotEmpty())Credential(password.toCharArray())else value.id?.let(credentials::get)?:return Result.failure(IllegalArgumentException("CREDENTIAL_REQUIRED"));val config=ConnectionConfig(value.id?:"picker",value.name,value.host,value.port.toIntOrNull()?:445,value.share,"",value.username,value.domain.takeIf(String::isNotBlank),value.mode);return try{Result.success(block(config,credential))}catch(error:Throwable){Result.failure(error)}finally{credential.password.fill('\u0000')}}
    private fun safePickerError(error:Throwable)=(error as? dev.networkstorage.domain.SmbFailure)?.category?.name?:if(error.message=="CREDENTIAL_REQUIRED")"CREDENTIAL_REQUIRED" else "CONNECTION"
    fun closeRemotePicker(){remotePicker.value=RemotePickerState()}
    fun startScan(connection:ConnectionSummary)=startScan(connection.connection)
    private fun startScan(connection:ConnectionEntity){val request=OneTimeWorkRequestBuilder<ScanWorker>().setInputData(workDataOf(ScanWorker.KEY_CONNECTION_ID to connection.id)).build();workManager.enqueueUniqueWork("manual-scan-${connection.id}",ExistingWorkPolicy.REPLACE,request);scan.value=ScanUiState(workId=request.id,state=WorkInfo.State.ENQUEUED,ownerId=connection.id);viewModelScope.launch{workManager.getWorkInfoByIdFlow(request.id).collect{info->if(info!=null)scan.value=ScanUiState(request.id,info.state,info.progress.getLong(ScanWorker.KEY_COUNT,0),info.progress.getString(ScanWorker.KEY_PATH).orEmpty(),ownerId=connection.id)}}}
    fun cancelScan(){scan.value.workId?.let(workManager::cancelWorkById)}
}
