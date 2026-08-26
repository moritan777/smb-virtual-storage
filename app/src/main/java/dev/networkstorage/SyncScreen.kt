package dev.networkstorage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.networkstorage.data.mirror.MirrorDiffItem
import dev.networkstorage.data.mirror.MirrorDiffState
import dev.networkstorage.data.mirror.MirrorSyncPolicy

@Composable
internal fun SyncScreen(viewModel: MainViewModel) {
    val connection by viewModel.selectedConnection.collectAsState(); val mirror by viewModel.mirror.collectAsState(); val root by viewModel.mirrorRootUri.collectAsState()
    val syncable = mirror.items.count { MirrorSyncPolicy.canCopyRemoteToLocal(it.state) }
    Column(Modifier.fillMaxSize().padding(horizontal = ScreenPadding)) {
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Column(Modifier.weight(1f)) { Text("Sync", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold); Text(connection?.connection?.name ?: "No connection", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; TextButton(onClick = viewModel::showConnections) { Text("Close") } }
        Surface(shape = RowShape, color = MaterialTheme.colorScheme.surfaceContainerLow) { Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text("Mirror storage", style = MaterialTheme.typography.labelMedium); Text(friendlyStorageRoot(root), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis); Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { OutlinedButton(onClick = viewModel::compareMirror, enabled = !mirror.loading && mirror.state?.isFinished != false) { Text("↻ Compare") }; Button(onClick = viewModel::syncAllMirror, enabled = syncable > 0 && mirror.state?.isFinished != false) { Text("↓ Sync all ($syncable)") } } } }
        if (mirror.loading) { Spacer(Modifier.height(6.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Comparing NAS index and Mirror folder…", style = MaterialTheme.typography.bodySmall) }
        mirror.state?.takeIf { !it.isFinished }?.let { Spacer(Modifier.height(6.dp)); Surface(shape = RowShape, color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)) { Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text("Syncing ${mirror.currentPath.substringAfterLast('/')}"); LinearProgressIndicator(progress = { usageRatio(mirror.copied, mirror.currentTotal) }, modifier = Modifier.fillMaxWidth()); Text("${mirror.completedFiles}/${mirror.totalFiles} files  ${formatBytes(mirror.copied)}/${formatBytes(mirror.currentTotal)}", style = MaterialTheme.typography.bodySmall); TextButton(onClick = viewModel::cancelMirrorSync) { Text("Cancel") } } } }
        Spacer(Modifier.height(6.dp))
        if (!mirror.loading && mirror.items.isEmpty()) Text("No differences found. Tap Compare after scanning the NAS.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) { items(mirror.items, key = { it.relativePath }) { item -> MirrorRow(item, mirror.state?.isFinished != false) { viewModel.syncMirror(item) } } }
    }
}

@Composable
private fun MirrorRow(item: MirrorDiffItem, idle: Boolean, onSync: () -> Unit) {
    val label = when (item.state) { MirrorDiffState.REMOTE_ONLY -> "NAS only"; MirrorDiffState.LOCAL_ONLY -> "Mirror only"; MirrorDiffState.SAME -> "Same"; MirrorDiffState.REMOTE_NEWER -> "NAS newer"; MirrorDiffState.LOCAL_NEWER -> "Mirror newer" }
    val icon = when (item.state) { MirrorDiffState.REMOTE_ONLY -> "☁"; MirrorDiffState.LOCAL_ONLY -> "▣"; MirrorDiffState.SAME -> "✓"; MirrorDiffState.REMOTE_NEWER -> "↓"; MirrorDiffState.LOCAL_NEWER -> "↑" }
    Surface(shape = RowShape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(icon, style = MaterialTheme.typography.titleMedium); Spacer(Modifier.width(8.dp)); Column(Modifier.weight(1f)) { Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("$label • NAS ${item.remoteSize?.let(::formatBytes) ?: "—"} • Mirror ${item.localSize?.let(::formatBytes) ?: "—"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (MirrorSyncPolicy.canCopyRemoteToLocal(item.state)) TextButton(onClick = onSync, enabled = idle) { Text("Sync") }
        }
    }
}
