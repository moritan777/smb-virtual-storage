package dev.networkstorage

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.networkstorage.data.db.ConnectionSummary
import dev.networkstorage.domain.FolderMode
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ConnectionsScreen(
    viewModel: MainViewModel,
    onCopyEditorVisibilityChange: (Boolean) -> Unit = {},
) {
    val connections by viewModel.connections.collectAsState()
    val scan by viewModel.scan.collectAsState()
    var deleteConnection by remember { mutableStateOf<ConnectionSummary?>(null) }
    var deleteIndex by remember { mutableStateOf<ConnectionSummary?>(null) }
    var copyConnection by remember { mutableStateOf<ConnectionSummary?>(null) }

    copyConnection?.let { selected ->
        CopyRulesScreen(
            connection = selected,
            onBack = { copyConnection = null },
            onEditorVisibilityChange = onCopyEditorVisibilityChange,
        )
        return
    }

    Column(Modifier.fillMaxSize().padding(horizontal = ScreenPadding)) {
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column { Text("Network Storage", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold); Text("Connections and scans", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            FloatingActionButton(onClick = viewModel::openAddConnection) { Text("+") }
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(connections.size, key = { connections[it].connection.id }) { index ->
                val summary = connections[index]
                var menu by remember { mutableStateOf(false) }
                ElevatedCard(Modifier.fillMaxWidth(), shape = SectionShape, colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(CompactGap)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) { Text(summary.connection.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Text("${summary.connection.share}${summary.connection.basePath.takeIf(String::isNotBlank)?.let { " / $it" }.orEmpty()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.secondaryContainer) { Text(displayMode(summary.connection.rootMode), Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall) }
                        }
                        HorizontalDivider()
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Last scan  ${summary.lastScanAt?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "Never"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${summary.entryCount} entries", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (scan.ownerId == summary.connection.id && scan.state?.isFinished == false) { Text("Scanning… ${scan.count} entries", style = MaterialTheme.typography.bodySmall); LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = viewModel::cancelScan) { Text("Cancel") } }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = { viewModel.browse(summary) }) { Text("▱ Open") }
                            OutlinedButton(onClick = { copyConnection = summary }) { Text("⇧ Copy") }
                            OutlinedButton(onClick = { viewModel.startScan(summary) }, enabled = summary.hasRootRule) { Text("↻ Scan") }
                            TextButton(onClick = { menu = true }) { Text("⋮") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Copy to SMB") }, onClick = { menu = false; copyConnection = summary })
                                DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; viewModel.openEditConnection(summary) })
                                DropdownMenuItem(text = { Text("Scan") }, onClick = { menu = false; viewModel.startScan(summary) }, enabled = summary.hasRootRule)
                                if (isMirror(summary.connection.rootMode)) DropdownMenuItem(text = { Text("Sync / compare") }, onClick = { menu = false; viewModel.openMirror(summary) }, enabled = summary.hasRootRule)
                                DropdownMenuItem(text = { Text("Delete index") }, onClick = { menu = false; deleteIndex = summary }, enabled = summary.hasRootRule)
                                DropdownMenuItem(text = { Text("Delete connection") }, onClick = { menu = false; deleteConnection = summary })
                            }
                        }
                    }
                }
            }
        }
    }
    deleteConnection?.let { target -> ConfirmDelete("Delete \"${target.connection.name}\"?", "This removes connection settings, indexed metadata, and saved credentials. Files on the NAS will NOT be deleted.", { deleteConnection = null }, { viewModel.deleteConnection(target); deleteConnection = null }) }
    deleteIndex?.let { target -> ConfirmDelete("Delete index target?", "This removes the local root rule and indexed metadata only. Nothing on the NAS will be changed.", { deleteIndex = null }, { viewModel.deleteRootIndex(target); deleteIndex = null }) }
}

private fun isMirror(mode: FolderMode) = mode == FolderMode.MIRROR

@Composable
private fun ConfirmDelete(title: String, body: String, dismiss: () -> Unit, confirm: () -> Unit) = AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(body) }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } }, confirmButton = { TextButton(onClick = confirm) { Text("Delete") } })
