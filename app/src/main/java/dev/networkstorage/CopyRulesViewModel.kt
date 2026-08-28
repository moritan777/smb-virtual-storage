package dev.networkstorage

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.getWorkInfosForUniqueWorkFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.networkstorage.data.copy.CopyConflictPolicy
import dev.networkstorage.data.copy.CopyPersistenceRepository
import dev.networkstorage.data.copy.CopyToSmbScheduler
import dev.networkstorage.data.copy.SafTreePermissionStore
import dev.networkstorage.data.db.CopyHistoryEntity
import dev.networkstorage.data.db.CopyNetworkPolicy
import dev.networkstorage.data.db.CopyRuleEntity
import dev.networkstorage.data.worker.CopyToSmbWorker
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
    val runAttemptCount: Int = 0,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CopyRulesViewModel @Inject constructor(
    application: Application,
    private val persistence: CopyPersistenceRepository,
    private val scheduler: CopyToSmbScheduler,
    private val permissions: SafTreePermissionStore,
) : AndroidViewModel(application) {
    private val connectionId = MutableStateFlow<String?>(null)
    private val activityRuleId = MutableStateFlow<String?>(null)
    private val workManager = WorkManager.getInstance(application)

    val rules = connectionId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList<CopyRuleEntity>()) else persistence.observeRules(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val executionStates = rules
        .flatMapLatest { currentRules ->
            if (currentRules.isEmpty()) flowOf(emptyMap())
            else combine(currentRules.map { rule -> observeExecution(rule).map { rule.id to it } }) { pairs ->
                pairs.toMap()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val recentActivity = activityRuleId
        .flatMapLatest { ruleId -> if (ruleId == null) flowOf(emptyList<CopyHistoryEntity>()) else persistence.observeRecentHistory(ruleId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selectedActivityRuleId = activityRuleId
    val editor = MutableStateFlow<CopyRuleEditorState?>(null)
    val message = MutableStateFlow<String?>(null)

    private fun observeExecution(rule: CopyRuleEntity) = combine(
        workManager.getWorkInfosForUniqueWorkFlow(CopyToSmbScheduler.manualName(rule.id)),
        workManager.getWorkInfosForUniqueWorkFlow(CopyToSmbScheduler.periodicName(rule.id)),
    ) { manual, periodic ->
        val periodicRunning = periodic.lastOrNull { it.state == WorkInfo.State.RUNNING }
        val selected = periodicRunning ?: manual.lastOrNull()
        selected?.let { info ->
            val data = if (info.state.isFinished) info.outputData else info.progress
            CopyExecutionUiState(
                state = info.state,
                automatic = periodicRunning?.id == info.id,
                copied = data.getInt(CopyToSmbWorker.KEY_COPIED_COUNT, 0),
                skipped = data.getInt(CopyToSmbWorker.KEY_SKIPPED_COUNT, 0),
                failed = data.getInt(CopyToSmbWorker.KEY_FAILURE_COUNT, 0),
                runAttemptCount = info.runAttemptCount,
            )
        }
    }

    fun setConnection(id: String) {
        if (connectionId.value != id) {
            connectionId.value = id
            activityRuleId.value = null
            editor.value = null
        }
    }

    fun newRule() {
        activityRuleId.value = null
        editor.value = CopyRuleEditorState()
    }

    fun editRule(rule: CopyRuleEntity) {
        activityRuleId.value = null
        editor.value = CopyRuleEditorState(
            id = rule.id,
            sourceTreeUri = rule.sourceTreeUri,
            destinationPath = rule.destinationPath,
            includeSubfolders = rule.includeSubfolders,
            conflictPolicy = rule.conflictPolicy,
            automaticCopyEnabled = rule.automaticCopyEnabled,
            networkPolicy = rule.networkPolicy,
            requiresCharging = rule.requiresCharging,
            requiresBatteryNotLow = rule.requiresBatteryNotLow,
            requiresStorageNotLow = rule.requiresStorageNotLow,
            periodicIntervalMinutes = rule.periodicIntervalMinutes,
            createdAt = rule.createdAt,
        )
    }

    fun showActivity(rule: CopyRuleEntity) { activityRuleId.value = rule.id }
    fun hideActivity() { activityRuleId.value = null }
    fun updateEditor(value: CopyRuleEditorState) { editor.value = value }

    fun selectSourceTree(uri: Uri) {
        val current = editor.value ?: return
        runCatching { permissions.persistReadPermission(uri.toString(), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            .onSuccess { editor.value = current.copy(sourceTreeUri = uri.toString()); message.value = "Source folder selected" }
            .onFailure { message.value = "Could not retain read access to the source folder" }
    }

    fun dismissEditor() { editor.value = null }

    fun saveRule() = viewModelScope.launch {
        val connection = connectionId.value ?: return@launch
        val value = editor.value ?: return@launch
        if (value.sourceTreeUri.isBlank()) { message.value = "Choose a source folder"; return@launch }
        val now = System.currentTimeMillis()
        val id = value.id ?: UUID.randomUUID().toString()
        runCatching {
            val saved = persistence.saveRule(
                id = id,
                connectionId = connection,
                sourceTreeUri = value.sourceTreeUri,
                destinationPath = value.destinationPath,
                includeSubfolders = value.includeSubfolders,
                conflictPolicy = value.conflictPolicy,
                automaticCopyEnabled = value.automaticCopyEnabled,
                networkPolicy = value.networkPolicy,
                requiresCharging = value.requiresCharging,
                requiresBatteryNotLow = value.requiresBatteryNotLow,
                requiresStorageNotLow = value.requiresStorageNotLow,
                periodicIntervalMinutes = value.periodicIntervalMinutes,
                createdAt = value.createdAt.takeIf { it > 0L } ?: now,
                updatedAt = now,
            )
            scheduler.schedule(saved)
        }.onSuccess { editor.value = null; message.value = "Copy rule saved" }
            .onFailure { message.value = "Check the source folder and SMB destination path" }
    }

    fun deleteRule(rule: CopyRuleEntity) = viewModelScope.launch {
        scheduler.cancelManual(rule.id)
        scheduler.cancelPeriodic(rule.id)
        runCatching { persistence.deleteRule(rule.id) }
            .onSuccess {
                if (activityRuleId.value == rule.id) activityRuleId.value = null
                message.value = "Copy rule deleted; source and SMB files were not changed"
            }
            .onFailure { message.value = "Could not delete the copy rule" }
    }

    fun copyNow(rule: CopyRuleEntity) {
        runCatching { scheduler.enqueueManual(rule.id) }
            .onSuccess { message.value = "Copy queued" }
            .onFailure { message.value = "Could not queue the copy" }
    }
}
