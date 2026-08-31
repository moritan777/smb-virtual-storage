package dev.networkstorage

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.networkstorage.data.copy.CopyConflictPolicy
import dev.networkstorage.data.copy.CopyDryRunPlanner
import dev.networkstorage.data.copy.CopyDryRunResult
import dev.networkstorage.data.copy.CopyPersistenceRepository
import dev.networkstorage.data.copy.CopyToSmbScheduler
import dev.networkstorage.data.copy.SafTreePermissionStore
import dev.networkstorage.data.db.AppDao
import dev.networkstorage.data.db.CopyHistoryEntity
import dev.networkstorage.data.db.CopyNetworkPolicy
import dev.networkstorage.data.db.CopyRuleEntity
import dev.networkstorage.data.worker.CopyToSmbWorker
import dev.networkstorage.domain.ConnectionConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

data class CopyRuleEditorState(
    val id: String? = null,
    val sourceTreeUri: String = "",
    val destinationPath: String = "",
    val includeSubfolders: Boolean = true,
    val conflictPolicy: CopyConflictPolicy = CopyConflictPolicy.KEEP_BOTH,
    val automaticCopyEnabled: Boolean = false,
    val networkPolicy: CopyNetworkPolicy = CopyNetworkPolicy.ANY_CONNECTED,
    val requiresCharging: Boolean = false,
    val requiresBatteryNotLow: Boolean = true,
    val requiresStorageNotLow: Boolean = true,
    val periodicIntervalMinutes: Long = 60L,
    val createdAt: Long = 0L,
)

data class CopyExecutionUiState(
    val state: WorkInfo.State,
    val automatic: Boolean,
    val copied: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val completed: Int = 0,
    val total: Int = 0,
    val currentSourcePath: String = "",
    val runAttemptCount: Int = 0,
)

internal data class SelectedCopyWork(val info: WorkInfo, val automatic: Boolean)

internal fun selectCopyWorkInfo(manual: List<WorkInfo>, periodic: List<WorkInfo>): SelectedCopyWork? {
    manual.asSequence().filter { it.state in ACTIVE_MANUAL_STATES }
        .maxWithOrNull(compareBy<WorkInfo> { manualStatePriority(it.state) }
            .thenBy { CopyToSmbScheduler.manualEnqueuedAt(it.tags) ?: Long.MIN_VALUE }
            .thenBy { it.id.toString() })
        ?.let { return SelectedCopyWork(it, automatic = false) }
    periodic.asSequence().filter { it.state == WorkInfo.State.RUNNING }
        .minByOrNull { it.id.toString() }?.let { return SelectedCopyWork(it, automatic = true) }
    return manual.maxWithOrNull(compareBy<WorkInfo> { CopyToSmbScheduler.manualEnqueuedAt(it.tags) ?: Long.MIN_VALUE }
        .thenBy { it.id.toString() })?.let { SelectedCopyWork(it, automatic = false) }
}

private val ACTIVE_MANUAL_STATES = setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED, WorkInfo.State.RUNNING)
private fun manualStatePriority(state: WorkInfo.State) = when (state) {
    WorkInfo.State.RUNNING -> 3
    WorkInfo.State.BLOCKED -> 2
    WorkInfo.State.ENQUEUED -> 1
    else -> 0
}

data class CopyPreviewState(
    val ruleId: String? = null,
    val loading: Boolean = false,
    val result: CopyDryRunResult? = null,
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CopyRulesViewModel @Inject constructor(
    application: Application,
    private val dao: AppDao,
    private val persistence: CopyPersistenceRepository,
    private val scheduler: CopyToSmbScheduler,
    private val permissions: SafTreePermissionStore,
    private val dryRunPlanner: CopyDryRunPlanner,
) : AndroidViewModel(application) {
    private val connectionId = MutableStateFlow<String?>(null)
    private val activityRuleId = MutableStateFlow<String?>(null)
    private val workManager = WorkManager.getInstance(application)

    val rules = connectionId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else persistence.observeRules(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val executionStates = rules.flatMapLatest { currentRules ->
        if (currentRules.isEmpty()) flowOf(emptyMap()) else combine(currentRules.map { rule -> observeExecution(rule).map { rule.id to it } }) { pairs -> pairs.toMap() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    val recentActivity = activityRuleId.flatMapLatest { ruleId -> if (ruleId == null) flowOf(emptyList<CopyHistoryEntity>()) else persistence.observeRecentHistory(ruleId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selectedActivityRuleId = activityRuleId
    val editor = MutableStateFlow<CopyRuleEditorState?>(null)
    val message = MutableStateFlow<String?>(null)
    val preview = MutableStateFlow(CopyPreviewState())

    private fun observeExecution(rule: CopyRuleEntity) = combine(
        workManager.getWorkInfosForUniqueWorkLiveData(CopyToSmbScheduler.manualName(rule.id)).asFlow(),
        workManager.getWorkInfosForUniqueWorkLiveData(CopyToSmbScheduler.periodicName(rule.id)).asFlow(),
    ) { manual, periodic ->
        selectCopyWorkInfo(manual, periodic)?.let { selected ->
            val info = selected.info
            val data = if (info.state.isFinished) info.outputData else info.progress
            CopyExecutionUiState(
                state = info.state, automatic = selected.automatic,
                copied = data.getInt(CopyToSmbWorker.KEY_COPIED_COUNT, 0), skipped = data.getInt(CopyToSmbWorker.KEY_SKIPPED_COUNT, 0),
                failed = data.getInt(CopyToSmbWorker.KEY_FAILURE_COUNT, 0), completed = data.getInt(CopyToSmbWorker.KEY_COMPLETED_COUNT, 0),
                total = data.getInt(CopyToSmbWorker.KEY_TOTAL_COUNT, 0), currentSourcePath = data.getString(CopyToSmbWorker.KEY_CURRENT_SOURCE_PATH).orEmpty(),
                runAttemptCount = info.runAttemptCount,
            )
        }
    }

    fun setConnection(id: String) { if (connectionId.value != id) { connectionId.value = id; activityRuleId.value = null; editor.value = null; preview.value = CopyPreviewState() } }
    fun newRule() { activityRuleId.value = null; editor.value = CopyRuleEditorState(); preview.value = CopyPreviewState() }
    fun editRule(rule: CopyRuleEntity) {
        activityRuleId.value = null; preview.value = CopyPreviewState()
        editor.value = CopyRuleEditorState(
            id = rule.id, sourceTreeUri = rule.sourceTreeUri, destinationPath = rule.destinationPath, includeSubfolders = rule.includeSubfolders,
            conflictPolicy = rule.conflictPolicy, automaticCopyEnabled = rule.automaticCopyEnabled, networkPolicy = rule.networkPolicy,
            requiresCharging = rule.requiresCharging, requiresBatteryNotLow = rule.requiresBatteryNotLow, requiresStorageNotLow = rule.requiresStorageNotLow,
            periodicIntervalMinutes = rule.periodicIntervalMinutes, createdAt = rule.createdAt,
        )
    }
    fun showActivity(rule: CopyRuleEntity) { activityRuleId.value = rule.id }
    fun hideActivity() { activityRuleId.value = null }
    fun updateEditor(value: CopyRuleEditorState) { editor.value = value; preview.value = CopyPreviewState() }

    fun selectSourceTree(uri: Uri) {
        val current = editor.value ?: return
        runCatching { permissions.persistReadPermission(uri.toString(), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            .onSuccess { editor.value = current.copy(sourceTreeUri = uri.toString()); preview.value = CopyPreviewState(); message.value = "Source folder selected" }
            .onFailure { message.value = "Could not retain read access to the source folder" }
    }
    fun dismissEditor() { editor.value = null; preview.value = CopyPreviewState() }

    fun previewRule(rule: CopyRuleEntity) {
        val connection = connectionId.value ?: return
        preview.value = CopyPreviewState(ruleId = rule.id, loading = true)
        viewModelScope.launch {
            runCatching {
                val entity = requireNotNull(dao.connection(connection)) { "Connection not found" }
                dryRunPlanner.plan(
                    connection = ConnectionConfig(entity.id, entity.name, entity.host, entity.port, entity.share, entity.basePath, entity.username, entity.domain, entity.rootMode),
                    sourceTreeUri = rule.sourceTreeUri, destinationDirectory = rule.destinationPath,
                    includeSubfolders = rule.includeSubfolders, conflictPolicy = rule.conflictPolicy,
                )
            }.onSuccess { preview.value = CopyPreviewState(rule.id, result = it) }
                .onFailure { preview.value = CopyPreviewState(rule.id, error = it.message?.takeIf(String::isNotBlank) ?: "Could not prepare preview") }
        }
    }
    fun clearPreview() { preview.value = CopyPreviewState() }

    fun saveRule() = viewModelScope.launch {
        val connection = connectionId.value ?: return@launch
        val value = editor.value ?: return@launch
        if (value.sourceTreeUri.isBlank()) { message.value = "Choose a source folder"; return@launch }
        val now = System.currentTimeMillis(); val id = value.id ?: UUID.randomUUID().toString()
        runCatching {
            val saved = persistence.saveRule(id, connection, value.sourceTreeUri, value.destinationPath, value.includeSubfolders, value.conflictPolicy,
                value.automaticCopyEnabled, value.networkPolicy, value.requiresCharging, value.requiresBatteryNotLow, value.requiresStorageNotLow,
                value.periodicIntervalMinutes, value.createdAt.takeIf { it > 0L } ?: now, now)
            scheduler.schedule(saved)
        }.onSuccess { editor.value = null; preview.value = CopyPreviewState(); message.value = "Copy rule saved" }
            .onFailure { message.value = it.message?.takeIf(String::isNotBlank) ?: "Check the source folder and SMB destination path" }
    }
    fun deleteRule(rule: CopyRuleEntity) = viewModelScope.launch {
        scheduler.cancelManual(rule.id); scheduler.cancelPeriodic(rule.id)
        runCatching { persistence.deleteRule(rule.id) }.onSuccess { if (activityRuleId.value == rule.id) activityRuleId.value = null; message.value = "Copy rule deleted; source and SMB files were not changed" }
            .onFailure { message.value = "Could not delete the copy rule" }
    }
    fun copyNow(rule: CopyRuleEntity) { runCatching { scheduler.enqueueManual(rule.id) }.onSuccess { message.value = "Copy queued" }.onFailure { message.value = "Could not queue the copy" } }
    fun cancelManualCopy(rule: CopyRuleEntity) { runCatching { scheduler.cancelManual(rule.id) }.onSuccess { message.value = "Manual copy cancellation requested" }.onFailure { message.value = "Could not cancel the manual copy" } }
}
