package dev.networkstorage

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
    viewModel: CopyRulesViewModel = hiltViewModel(),
) {
    val rules by viewModel.rules.collectAsState()
    val executionStates by viewModel.executionStates.collectAsState()
    val editor by viewModel.editor.collectAsState()
    val selectedActivityRuleId by viewModel.selectedActivityRuleId.collectAsState()
    val recentActivity by viewModel.recentActivity.collectAsState()
    val message by viewModel.message.collectAsState()
    LaunchedEffect(connection.connection.id) { viewModel.setConnection(connection.connection.id) }
    LaunchedEffect(editor != null) { onEditorVisibilityChange(editor != null) }
    DisposableEffect(Unit) { onDispose { onEditorVisibilityChange(false) } }

    if (editor != null) {
        CopyRuleEditorScreen(viewModel, connection.connection.name, editor!!)
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
                    Text("Copy to SMB", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(connection.connection.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Button(onClick = viewModel::newRule) { Text("+ Rule") }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(8.dp))
        if (rules.isEmpty()) {
            ElevatedCard(Modifier.fillMaxWidth(), shape = SectionShape) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("No Copy to SMB rules", fontWeight = FontWeight.SemiBold)
                    Text("Choose an Android folder and copy its files into this SMB connection. Source files are never deleted or moved.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = viewModel::newRule) { Text("Create rule") }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rules, key = { it.id }) { rule -> CopyRuleCard(rule, executionStates[rule.id], viewModel) }
            }
        }
    }
}

@Composable
private fun CopyRuleCard(rule: CopyRuleEntity, execution: CopyExecutionUiState?, viewModel: CopyRulesViewModel) {
    val busy = execution?.state in setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED, WorkInfo.State.RUNNING)
    ElevatedCard(
        Modifier.fillMaxWidth(),
        shape = SectionShape,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(friendlyCopySource(rule.sourceTreeUri), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("→ ${rule.destinationPath.ifBlank { "SMB root" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (rule.conflictPolicy == CopyConflictPolicy.KEEP_BOTH) "Keep Both" else "Replace + backup", style = MaterialTheme.typography.labelMedium)
                Text(if (rule.automaticCopyEnabled) "Auto • ${copyIntervalLabel(rule.periodicIntervalMinutes)}" else "Manual", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            execution?.let { CopyExecutionStatus(it) }
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { viewModel.copyNow(rule) }, enabled = !busy) { Text(if (busy) "Working…" else "Copy now") }
                OutlinedButton(onClick = { viewModel.showActivity(rule) }) { Text("Activity") }
                OutlinedButton(onClick = { viewModel.editRule(rule) }, enabled = !busy) { Text("Edit") }
                TextButton(onClick = { viewModel.deleteRule(rule) }, enabled = !busy) { Text("Delete") }
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
            Text("$source copy running$retry", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        WorkInfo.State.SUCCEEDED -> Text(
            "Last copy: ${value.copied} copied • ${value.skipped} skipped • ${value.failed} failed",
            style = MaterialTheme.typography.bodySmall,
            color = if (value.failed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        WorkInfo.State.FAILED -> Text("Last copy failed • see Activity", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        WorkInfo.State.CANCELLED -> Text("Last copy cancelled", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CopyRecentActivityScreen(
    rule: CopyRuleEntity?,
    entries: List<CopyHistoryEntity>,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = ScreenPadding)) {
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = onBack) { Text("←") }
            Column {
                Text("Recent activity", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(rule?.let { friendlyCopySource(it.sourceTreeUri) } ?: "Copy rule", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text("Newest 20 file results", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        if (entries.isEmpty()) {
            ElevatedCard(Modifier.fillMaxWidth(), shape = SectionShape) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("No activity yet", fontWeight = FontWeight.SemiBold)
                    Text("Run Copy now or wait for an automatic copy. File-level results will appear here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(entries, key = { it.id }) { entry -> CopyActivityCard(entry) }
            }
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
            Text(entry.sourceRelativePath, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("→ ${entry.destinationRelativePath}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(formatBytes(entry.sourceSize), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            entry.errorCode?.let { Text("Error: ${it.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            entry.backupRelativePath?.let { Text("Backup: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun copyHistoryStatusColor(status: CopyHistoryStatus) = when (status) {
    CopyHistoryStatus.SUCCEEDED -> MaterialTheme.colorScheme.primary
    CopyHistoryStatus.SKIPPED_IDENTICAL -> MaterialTheme.colorScheme.secondary
    CopyHistoryStatus.FAILED -> MaterialTheme.colorScheme.error
    CopyHistoryStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun copyHistoryStatusLabel(status: CopyHistoryStatus) = when (status) {
    CopyHistoryStatus.SUCCEEDED -> "Copied"
    CopyHistoryStatus.SKIPPED_IDENTICAL -> "Identical • skipped"
    CopyHistoryStatus.FAILED -> "Failed"
    CopyHistoryStatus.CANCELLED -> "Cancelled"
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
                Text("Interval", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(15L, 60L, 360L, 1440L).forEach { minutes -> ChoiceButton(copyIntervalLabel(minutes), state.periodicIntervalMinutes == minutes, Modifier.weight(1f)) { viewModel.updateEditor(state.copy(periodicIntervalMinutes = minutes)) } }
                }
            }
            item {
                Text("Network", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChoiceButton("Any network", state.networkPolicy == CopyNetworkPolicy.ANY_CONNECTED, Modifier.weight(1f)) { viewModel.updateEditor(state.copy(networkPolicy = CopyNetworkPolicy.ANY_CONNECTED)) }
                    ChoiceButton("Unmetered", state.networkPolicy == CopyNetworkPolicy.UNMETERED_ONLY, Modifier.weight(1f)) { viewModel.updateEditor(state.copy(networkPolicy = CopyNetworkPolicy.UNMETERED_ONLY)) }
                }
            }
            item { ToggleRow("Require charging", state.requiresCharging) { viewModel.updateEditor(state.copy(requiresCharging = it)) } }
            item { ToggleRow("Battery not low", state.requiresBatteryNotLow) { viewModel.updateEditor(state.copy(requiresBatteryNotLow = it)) } }
            item { ToggleRow("Storage not low", state.requiresStorageNotLow) { viewModel.updateEditor(state.copy(requiresStorageNotLow = it)) } }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = viewModel::dismissEditor) { Text("Cancel") }
                Button(onClick = viewModel::saveRule, enabled = state.sourceTreeUri.isNotBlank()) { Text("Save") }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ChoiceButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) FilledTonalButton(onClick = onClick, modifier = modifier) { Text(label) }
    else OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
}

private fun friendlyCopySource(value: String): String {
    val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return value
    return Uri.decode(uri.lastPathSegment ?: value).removePrefix("primary:").ifBlank { "Selected folder" }
}

private fun copyIntervalLabel(minutes: Long): String = when (minutes) {
    15L -> "15 min"
    60L -> "1 h"
    360L -> "6 h"
    1440L -> "24 h"
    else -> "$minutes min"
}
