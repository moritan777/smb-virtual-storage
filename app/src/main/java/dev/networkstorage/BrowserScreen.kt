package dev.networkstorage

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.compose.collectAsLazyPagingItems

@Composable
internal fun BrowserScreen(viewModel: MainViewModel) {
    val connection by viewModel.selectedConnection.collectAsState(); val path by viewModel.currentPath.collectAsState(); val items = viewModel.browserItems.collectAsLazyPagingItems(); val download by viewModel.download.collectAsState()
    Column(Modifier.fillMaxSize().padding(horizontal = ScreenPadding)) {
        Spacer(Modifier.height(8.dp)); val root = connection?.connection?.let { listOf(it.share, it.basePath).filter(String::isNotBlank).joinToString(" / ") }.orEmpty(); Text(path.substringAfterLast('/', root.substringAfterLast('/', connection?.connection?.name ?: "Select a connection")), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold); Text(listOfNotNull(connection?.connection?.name, root.takeIf(String::isNotEmpty), path.takeIf(String::isNotEmpty)).joinToString(" / "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis); Spacer(Modifier.height(6.dp))
        if (path.isNotEmpty()) Surface(shape = RowShape, color = MaterialTheme.colorScheme.surfaceContainer) { Text("← One level up", Modifier.fillMaxWidth().clickable { viewModel.browserBack() }.padding(horizontal = 10.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium) }
        download.state?.takeIf { !it.isFinished }?.let { Spacer(Modifier.height(6.dp)); Surface(shape = RowShape, color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)) { Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text("Downloading ${download.path}", style = MaterialTheme.typography.bodyMedium); LinearProgressIndicator(progress = { usageRatio(download.count, download.total) }, modifier = Modifier.fillMaxWidth()); Text("${formatBytes(download.count)} / ${formatBytes(download.total)} (${downloadPercent(download.count, download.total)}%)", style = MaterialTheme.typography.bodySmall); TextButton(onClick = viewModel::cancelDownload) { Text("Cancel") } } } }
        Spacer(Modifier.height(6.dp)); LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) { items(items.itemCount) { index -> items[index]?.let { item -> BrowserRow(item, onClick = { viewModel.openFolder(item) }, onRemoveCache = { viewModel.removeCache(item) }) } } }
    }
}

@Composable
private fun BrowserRow(item: BrowserItem, onClick: () -> Unit, onRemoveCache: () -> Unit) {
    val missingColor = if (item.remoteExists) Color.Unspecified else MaterialTheme.colorScheme.error; var menu by remember(item.relativePath) { mutableStateOf(false) }; val hasCache = !item.isDirectory && (item.localState == LocalFileState.CACHED || item.localState == LocalFileState.REMOTE_UPDATED); val hasCachedDescendant = item.isDirectory && item.localState == LocalFileState.CACHED
    Surface(shape = RowShape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp)) {
            val icon = if (item.isDirectory) "📁" else BrowserPresentation.stateIcon(item.localState); Text(icon, style = MaterialTheme.typography.titleMedium); Spacer(Modifier.width(8.dp)); Column(Modifier.weight(1f)) { Text(item.name, color = missingColor, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis); if (!item.isDirectory) Text(formatBytes(item.size), color = if (item.remoteExists) MaterialTheme.colorScheme.onSurfaceVariant else missingColor, style = MaterialTheme.typography.bodySmall) }
            if (item.isDirectory) { if (hasCachedDescendant) Text("●", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary); Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } else if (hasCache) { TextButton(onClick = { menu = true }) { Text("⋮") }; DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) { DropdownMenuItem(text = { Text("Remove cached copy") }, onClick = { menu = false; onRemoveCache() }) } }
        }
    }
}

private fun downloadPercent(copied: Long, total: Long) = if (total <= 0) 100 else ((copied.toDouble() / total.toDouble()) * 100.0).toInt().coerceIn(0, 100)
