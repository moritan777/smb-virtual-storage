package dev.networkstorage

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.work.WorkInfo
import dev.networkstorage.data.copy.CopyConflictPolicy
import dev.networkstorage.data.copy.CopyDecision
import dev.networkstorage.data.copy.CopyDryRunResult
import dev.networkstorage.data.db.ConnectionSummary
import dev.networkstorage.data.db.CopyHistoryEntity
import dev.networkstorage.data.db.CopyHistoryStatus
import dev.networkstorage.data.db.CopyNetworkPolicy
import dev.networkstorage.data.db.CopyRuleEntity
import java.text.DateFormat
import java.util.Date

@Composable
internal fun CopyRulesScreen(
    connection: ConnectionSummary,
    onBack: () -> Unit,
    onEditorVisibilityChange: (Boolean) -> Unit = {},
    simpleMode: Boolean = false,
    viewModel: CopyRulesViewModel = hiltViewModel(),
    mainViewModel: MainViewModel? = null,
) {
    val rules by viewModel.rules.collectAsState()
    val executionStates by viewModel.executionStates.collectAsState()
    val editor by viewModel.editor.collectAsState()
    val selectedActivityRuleId by viewModel.selectedActivityRuleId.collectAsState()
    val recentActivity by viewModel.recentActivity.collectAsState()
    val preview by viewModel.preview.collectAsState()
    val message by viewModel.message.collectAsState()
    LaunchedEffect(connection.connection.id) { viewModel.setConnection(connection.connection.id) }
    LaunchedEffect(editor != null) { onEditorVisibilityChange(editor != null) }
    DisposableEffect(Unit) { onDispose { onEditorVisibilityChange(false) } }

    if (editor != null) {
        if (simpleMode) BackupRuleEditorScreen(viewModel, editor!!, mainViewModel, connection) else CopyRuleEditorScreen(viewModel, connection.connection.name, editor!!)
        return
    }
    if (preview.ruleId != null) {
        val rule = rules.firstOrNull { it.id == preview.ruleId }
        CopyPreviewScreen(rule, preview, viewModel, simpleMode)
        return
    }
    if (selectedActivityRuleId != null) {
        val rule = rules.firstOrNull { it.id == selectedActivityRuleId }
        CopyRecentActivityScreen(rule = rule, entries = recentActivity, onBack = viewModel::hideActivity)
        return
    }

    Column(Modifier.fillMaxSize().padding(horizontal = ScreenPadding)) {
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onBack) { Text("←") }
                Column {
                    Text(if (simpleMode) "Backups" else "Copy to SMB", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(connection.connection.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Button(onClick = viewModel::newRule) { Text(if (simpleMode) "+ Backup" else "+ Rule") }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(8.dp))
        if (rules.isEmpty()) {
            ElevatedCard(Modifier.fillMaxWidth(), shape = SectionShape) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (simpleMode) "No backups configured" else "No Copy to SMB rules", fontWeight = FontWeight.SemiBold)
                    Text(if (simpleMode) "Choose a phone folder and set when it should be backed up to this NAS. The original files stay on the phone." else "Choose an Android folder and copy its files into this SMB connection. Source files are never deleted or moved.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = viewModel::newRule) { Text(if (simpleMode) "Create backup" else "Create rule") }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rules, key = { it.id }) { rule -> CopyRuleCard(rule, executionStates[rule.id], viewModel, simpleMode) }
            }
        }
    }
}

@Composable
private fun CopyRuleCard(rule: CopyRuleEntity, execution: CopyExecutionUiState?, viewModel: CopyRulesViewModel, simpleMode: Boolean) {
    val busy = execution?.state in setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED, WorkInfo.State.RUNNING)
    val manualBusy = busy && execution?.automatic == false
    ElevatedCard(Modifier.fillMaxWidth(), shape = SectionShape, colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(friendlyCopySource(rule.sourceTreeUri), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("NAS folder: ${rule.destinationPath.ifBlank { "Connection root" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (simpleMode) {
                Text(
                    if (rule.automaticCopyEnabled) "Daily at ${copyTimeLabel(rule.scheduledTimeMinutesOfDay)}" else "Automatic backup off",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "${if (rule.requiresCharging) "Charging required" else "Charging optional"} • ${when (rule.networkPolicy) { CopyNetworkPolicy.WIFI_ONLY -> "Wi-Fi"; CopyNetworkPolicy.UNMETERED_ONLY -> "Unmetered network"; else -> "Any network" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                execution?.let { CopyExecutionStatus(it) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { viewModel.showActivity(rule) }) { Text("History") }
                    OutlinedButton(onClick = { viewModel.previewRule(rule) }, enabled = !busy) { Text("Test") }
                    TextButton(onClick = { viewModel.deleteRule(rule) }, enabled = !busy) { Text("Delete") }
                    Button(onClick = { viewModel.editRule(rule) }, enabled = !busy) { Text("Edit backup") }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (rule.conflictPolicy == CopyConflictPolicy.KEEP_BOTH) "Keep Both" else "Replace + backup", style = MaterialTheme.typography.labelMedium)
                    Text(if (rule.automaticCopyEnabled) "Auto • ${copyTimeLabel(rule.scheduledTimeMinutesOfDay)}" else "Manual", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                execution?.let { CopyExecutionStatus(it) }
                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (manualBusy) {
                        OutlinedButton(onClick = { viewModel.cancelManualCopy(rule) }) { Text("Cancel") }
                    } else {
                        Button(onClick = { viewModel.copyNow(rule) }, enabled = !busy) { Text(if (busy) "Working…" else "Copy now") }
                    }
                    OutlinedButton(onClick = { viewModel.previewRule(rule) }, enabled = !busy) { Text("Preview") }
                    OutlinedButton(onClick = { viewModel.showActivity(rule) }) { Text("History") }
                    OutlinedButton(onClick = { viewModel.editRule(rule) }, enabled = !busy) { Text("Edit") }
                    TextButton(onClick = { viewModel.deleteRule(rule) }, enabled = !busy) { Text("Delete") }
                }
            }
        }
    }
}
@Composable
private fun CopyExecutionStatus(value: CopyExecutionUiState) {
    val source = if (value.automatic) "Automatic" else "Manual"
    when (value.state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
            Text("$source copy queued", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        WorkInfo.State.RUNNING -> {
            val retry = if (value.runAttemptCount > 0) " • retry ${value.runAttemptCount}" else ""
            val fileProgress = if (value.total > 0) " • ${value.completed}/${value.total} files" else ""
            Text("$source copy running$fileProgress$retry", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (value.currentSourcePath.isNotBlank()) Text(value.currentSourcePath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${value.copied} copied • ${value.skipped} skipped • ${value.failed} failed", style = MaterialTheme.typography.labelSmall, color = if (value.failed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        WorkInfo.State.SUCCEEDED -> Text("Last copy: ${value.copied} copied • ${value.skipped} skipped • ${value.failed} failed", style = MaterialTheme.typography.bodySmall, color = if (value.failed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        WorkInfo.State.FAILED -> Text("Backup failed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        WorkInfo.State.CANCELLED -> Text("Last copy cancelled", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CopyPreviewScreen(
    rule: CopyRuleEntity?,
    state: CopyPreviewState,
    viewModel: CopyRulesViewModel,
    simpleMode: Boolean,
) {
    val result = state.result
    Column(Modifier.fillMaxSize().padding(horizontal = ScreenPadding)) {
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = viewModel::clearPreview) { Text("←") }
            Column(Modifier.weight(1f)) {
                Text(if (simpleMode) "Backup test" else "Copy preview", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(rule?.let { friendlyCopySource(it.sourceTreeUri) } ?: "Copy rule", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (result != null && !simpleMode) FilledTonalButton(onClick = { viewModel.copyNow(rule!!) }) { Text("Copy now") }
        }
        if (state.loading) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Inspecting source and SMB destinations…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return
        }
        state.error?.let {
            Spacer(Modifier.height(8.dp))
            ElevatedCard(Modifier.fillMaxWidth(), shape = SectionShape) { Text(it, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.error) }
            return
        }
        result?.let { CopyPreviewContent(it) }
    }
}

@Composable
private fun CopyPreviewContent(result: CopyDryRunResult) {
    val s = result.summary
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            ElevatedCard(Modifier.fillMaxWidth(), shape = SectionShape) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Planned operation", fontWeight = FontWeight.SemiBold)
                    Text("${s.total} files • ${s.plannedWrites} writes • ${s.unchangedCount + s.reuseExistingCount} reused/skipped • ${s.failedCount} unable to determine", style = MaterialTheme.typography.bodyMedium)
                    Text("New ${s.newCount} • Keep both ${s.keepBothCount} • Replace ${s.replaceCount} • Unchanged ${s.unchangedCount} • Reuse existing ${s.reuseExistingCount}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Preview performs no SMB writes or deletions. A concurrent SMB change can still alter the final result.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(result.items, key = { it.sourceRelativePath }) { item ->
            ElevatedCard(Modifier.fillMaxWidth(), shape = SectionShape, colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            text = item.decision?.let(::copyDecisionLabel) ?: "Unable to determine",
                            fontWeight = FontWeight.SemiBold,
                            color = if (item.decision != null) copyDecisionColor(item.decision) else MaterialTheme.colorScheme.error
                        )
                        Text(formatBytes(item.sourceSize), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(item.sourceRelativePath, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("→ ${item.destinationRelativePath}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(item.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun copyDecisionColor(decision: CopyDecision) = when (decision) {
    CopyDecision.NEW -> MaterialTheme.colorScheme.primary
    CopyDecision.UNCHANGED, CopyDecision.REUSE_EXISTING -> MaterialTheme.colorScheme.secondary
    CopyDecision.KEEP_BOTH, CopyDecision.REPLACE -> MaterialTheme.colorScheme.tertiary
}

private fun copyDecisionLabel(decision: CopyDecision) = when (decision) {
    CopyDecision.NEW -> "New"
    CopyDecision.UNCHANGED -> "Unchanged • skip"
    CopyDecision.KEEP_BOTH -> "Keep both"
    CopyDecision.REPLACE -> "Replace + backup"
    CopyDecision.REUSE_EXISTING -> "Reuse existing"
}

@Composable
private fun CopyRecentActivityScreen(rule: CopyRuleEntity?, entries: List<CopyHistoryEntity>, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = ScreenPadding)) {
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = onBack) { Text("←") }
            Column {
                Text("Backup History", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(rule?.let { friendlyCopySource(it.sourceTreeUri) } ?: "Copy rule", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text("Latest 20 file results", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        if (entries.isEmpty()) {
            ElevatedCard(Modifier.fillMaxWidth(), shape = SectionShape) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("No activity yet", fontWeight = FontWeight.SemiBold)
                    Text("Run Copy now or wait for an automatic copy. File-level results will appear here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) { items(entries, key = { it.id }) { entry -> CopyActivityCard(entry) } }
        }
    }
}

@Composable
private fun CopyActivityCard(entry: CopyHistoryEntity) {
    ElevatedCard(Modifier.fillMaxWidth(), shape = SectionShape, colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(copyHistoryStatusLabel(entry.status), fontWeight = FontWeight.SemiBold, color = copyHistoryStatusColor(entry.status))
                Text(DateFormat.getDateTimeInstance().format(Date(entry.completedAt)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (entry.status == CopyHistoryStatus.SKIPPED_CONDITION) {
                Text("Automatic backup", style = MaterialTheme.typography.bodyMedium)
                Text("Reason: ${entry.errorCode?.let(::copyScheduleSkipReason) ?: "Condition not met"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(entry.sourceRelativePath, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("→ ${entry.destinationRelativePath}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(formatBytes(entry.sourceSize), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                entry.errorCode?.let { Text("Error: ${it.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
            entry.backupRelativePath?.let { Text("Backup: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun copyHistoryStatusColor(status: CopyHistoryStatus) = when (status) {
    CopyHistoryStatus.SUCCEEDED -> MaterialTheme.colorScheme.primary
    CopyHistoryStatus.SKIPPED_IDENTICAL, CopyHistoryStatus.SKIPPED_CONDITION -> MaterialTheme.colorScheme.secondary
    CopyHistoryStatus.FAILED -> MaterialTheme.colorScheme.error
    CopyHistoryStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun copyScheduleSkipReason(code: dev.networkstorage.data.db.CopyErrorCode) = when (code) {
    dev.networkstorage.data.db.CopyErrorCode.NOT_CHARGING -> "Device was not charging"
    dev.networkstorage.data.db.CopyErrorCode.WIFI_NOT_CONNECTED -> "Wi-Fi was not connected"
    dev.networkstorage.data.db.CopyErrorCode.NETWORK_NOT_CONNECTED -> "No network connection"
    dev.networkstorage.data.db.CopyErrorCode.METERED_NETWORK -> "Network was metered"
    else -> code.name
}

private fun copyHistoryStatusLabel(status: CopyHistoryStatus) = when (status) {
    CopyHistoryStatus.SUCCEEDED -> "Copied"
    CopyHistoryStatus.SKIPPED_IDENTICAL -> "Identical • skipped"
    CopyHistoryStatus.SKIPPED_CONDITION -> "Automatic run • skipped"
    CopyHistoryStatus.FAILED -> "Failed"
    CopyHistoryStatus.CANCELLED -> "Cancelled"
}

@Composable
private fun BackupRuleEditorScreen(viewModel: CopyRulesViewModel, state: CopyRuleEditorState, mainViewModel: MainViewModel?, connection: ConnectionSummary) {
    val remotePicker by (mainViewModel?.remotePicker ?: kotlinx.coroutines.flow.MutableStateFlow(RemotePickerState())).collectAsState()
    var showCreateFolder by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var newFolderName by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(viewModel::selectSourceTree) }
    val displayedPath = listOf(connection.connection.basePath, remotePicker.path)
        .filter(String::isNotBlank)
        .joinToString("/")
    if (remotePicker.visible) {
        AlertDialog(
            onDismissRequest = { mainViewModel?.closeRemotePicker() },
            title = { Text("NAS folders") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${connection.connection.share} /${displayedPath.takeIf(String::isNotBlank)?.let { " $it" }.orEmpty()}")
                    if (remotePicker.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    remotePicker.error?.let { Text("Could not browse: $it", color = MaterialTheme.colorScheme.error) }
                    if (remotePicker.path.isNotBlank()) {
                        TextButton(onClick = { mainViewModel?.browseBackupFolder(connection, FolderNavigation.parent(remotePicker.path)) }) { Text("← Up one level") }
                    }
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.height(240.dp)) {
                        items(remotePicker.folders) { folder ->
                            Row(
                                Modifier.fillMaxWidth().clickable { mainViewModel?.browseBackupFolder(connection, folder) }.padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("📁")
                                Text(folder.substringAfterLast('/'))
                            }
                        }
                    }
                    TextButton(onClick = { showCreateFolder = true }) { Text("+ New folder") }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.updateEditor(state.copy(destinationPath = remotePicker.path))
                        mainViewModel?.closeRemotePicker()
                    },
                    enabled = !remotePicker.loading && remotePicker.error == null,
                ) { Text("Use this folder") }
            },
            dismissButton = { TextButton(onClick = { mainViewModel?.closeRemotePicker() }) { Text("Cancel") } },
        )
    }
    if (showCreateFolder) {
        AlertDialog(
            onDismissRequest = { showCreateFolder = false; newFolderName = "" },
            title = { Text("Create NAS folder") },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text("Folder name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        mainViewModel?.createBackupFolder(connection, newFolderName)
                        showCreateFolder = false
                        newFolderName = ""
                    },
                    enabled = newFolderName.isNotBlank() && !remotePicker.loading,
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreateFolder = false; newFolderName = "" }) { Text("Cancel") } },
        )
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = ScreenPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            Text(if (state.id == null) "Create backup" else "Edit backup", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text("Choose what to back up and when. Advanced copy behavior keeps its safe defaults.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Text("Phone folder", style = MaterialTheme.typography.labelMedium)
            Text(if (state.sourceTreeUri.isBlank()) "Not selected" else friendlyCopySource(state.sourceTreeUri), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            OutlinedButton(onClick = { picker.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text("Choose folder") }
        }
        item {
            Text("NAS destination", style = MaterialTheme.typography.labelMedium)
            Text(
                if (state.destinationPath.isBlank()) "NAS shared folder root" else state.destinationPath,
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
                onClick = { mainViewModel?.openBackupFolderPicker(connection) },
                modifier = Modifier.fillMaxWidth(),
                enabled = mainViewModel != null,
            ) { Text("Browse NAS folders") }
            Text("Choose a folder from the NAS hierarchy or create a new one.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { ToggleRow("Automatic daily backup", state.automaticCopyEnabled) { viewModel.updateEditor(state.copy(automaticCopyEnabled = it)) } }
        if (state.automaticCopyEnabled) {
            item {
                OutlinedTextField(
                    value = state.scheduledTime,
                    onValueChange = { viewModel.updateEditor(state.copy(scheduledTime = it.take(5))) },
                    label = { Text("Backup time (24-hour HH:mm)") },
                    placeholder = { Text("04:00") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            item {
                Text("Run only when connected to", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceButton("Any network", state.networkPolicy != CopyNetworkPolicy.WIFI_ONLY, Modifier.weight(1f)) {
                        viewModel.updateEditor(state.copy(networkPolicy = CopyNetworkPolicy.ANY_CONNECTED))
                    }
                    ChoiceButton("Wi-Fi", state.networkPolicy == CopyNetworkPolicy.WIFI_ONLY, Modifier.weight(1f)) {
                        viewModel.updateEditor(state.copy(networkPolicy = CopyNetworkPolicy.WIFI_ONLY))
                    }
                }
            }
            item { ToggleRow("Only while charging", state.requiresCharging) { viewModel.updateEditor(state.copy(requiresCharging = it)) } }
            item {
                Text("If a condition is not met at the scheduled time, that day's backup is skipped and the reason is saved in history.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = viewModel::dismissEditor) { Text("Cancel") }
                Button(onClick = viewModel::saveRule, enabled = state.sourceTreeUri.isNotBlank()) { Text("Save backup") }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun CopyRuleEditorScreen(viewModel: CopyRulesViewModel, connectionName: String, state: CopyRuleEditorState) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(viewModel::selectSourceTree) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = ScreenPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = viewModel::dismissEditor) { Text("←") }
                Column {
                    Text(if (state.id == null) "New copy rule" else "Edit copy rule", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(connectionName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Text("Android source folder", style = MaterialTheme.typography.labelMedium)
            Text(if (state.sourceTreeUri.isBlank()) "Not selected" else friendlyCopySource(state.sourceTreeUri), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            OutlinedButton(onClick = { picker.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text("▱ Choose source folder") }
            Text("Read-only source access. Copy to SMB never deletes, renames, or moves source files.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { OutlinedTextField(value = state.destinationPath, onValueChange = { viewModel.updateEditor(state.copy(destinationPath = it)) }, label = { Text("SMB destination path") }, placeholder = { Text("Blank = connection root") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
        item { ToggleRow("Include subfolders", state.includeSubfolders) { viewModel.updateEditor(state.copy(includeSubfolders = it)) } }
        item {
            Text("Conflict policy", style = MaterialTheme.typography.labelMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChoiceButton("Keep Both", state.conflictPolicy == CopyConflictPolicy.KEEP_BOTH, Modifier.weight(1f)) { viewModel.updateEditor(state.copy(conflictPolicy = CopyConflictPolicy.KEEP_BOTH)) }
                ChoiceButton("Replace + backup", state.conflictPolicy == CopyConflictPolicy.REPLACE_WITH_BACKUP, Modifier.weight(1f)) { viewModel.updateEditor(state.copy(conflictPolicy = CopyConflictPolicy.REPLACE_WITH_BACKUP)) }
            }
            Text("Keep Both is the default. Replace always moves the existing SMB file into .network-storage-backup first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { ToggleRow("Automatic copy", state.automaticCopyEnabled) { viewModel.updateEditor(state.copy(automaticCopyEnabled = it)) } }
        if (state.automaticCopyEnabled) {
            item {
                Text("Daily backup time", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = state.scheduledTime,
                    onValueChange = { viewModel.updateEditor(state.copy(scheduledTime = it.take(5))) },
                    label = { Text("Time (24-hour HH:mm)") },
                    placeholder = { Text("04:00") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Text("Runs once each day at the selected local time. Android may delay background execution; if charging or network conditions are not met when it starts, that run is skipped and recorded.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Text("Network", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChoiceButton("Any network", state.networkPolicy == CopyNetworkPolicy.ANY_CONNECTED, Modifier.weight(1f)) { viewModel.updateEditor(state.copy(networkPolicy = CopyNetworkPolicy.ANY_CONNECTED)) }
                    ChoiceButton("Wi-Fi", state.networkPolicy == CopyNetworkPolicy.WIFI_ONLY, Modifier.weight(1f)) { viewModel.updateEditor(state.copy(networkPolicy = CopyNetworkPolicy.WIFI_ONLY)) }
                    ChoiceButton("Unmetered", state.networkPolicy == CopyNetworkPolicy.UNMETERED_ONLY, Modifier.weight(1f)) { viewModel.updateEditor(state.copy(networkPolicy = CopyNetworkPolicy.UNMETERED_ONLY)) }
                }
            }
            item { ToggleRow("Require charging", state.requiresCharging) { viewModel.updateEditor(state.copy(requiresCharging = it)) } }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = viewModel::dismissEditor) { Text("Cancel") }; Button(onClick = viewModel::saveRule, enabled = state.sourceTreeUri.isNotBlank()) { Text("Save") } }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, style = MaterialTheme.typography.bodyMedium); Switch(checked = checked, onCheckedChange = onCheckedChange) }
}

@Composable
private fun ChoiceButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) FilledTonalButton(onClick = onClick, modifier = modifier) { Text(label) } else OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
}

private fun friendlyCopySource(value: String): String {
    val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return value
    return Uri.decode(uri.lastPathSegment ?: value).removePrefix("primary:").ifBlank { "Selected folder" }
}

private fun copyTimeLabel(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)