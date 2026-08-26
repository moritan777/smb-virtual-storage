package dev.networkstorage

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import dagger.hilt.android.AndroidEntryPoint
import dev.networkstorage.data.db.ConnectionSummary
import dev.networkstorage.data.mirror.MirrorDiffItem
import dev.networkstorage.data.mirror.MirrorDiffState
import dev.networkstorage.data.mirror.MirrorSyncPolicy
import dev.networkstorage.data.settings.SettingsRepository
import dev.networkstorage.data.settings.StorageRootKind
import dev.networkstorage.domain.FolderMode
import dev.networkstorage.domain.FolderNavigation
import java.text.DateFormat
import java.util.Date

private val ScreenPadding = 14.dp
private val SectionShape = RoundedCornerShape(16.dp)
private val RowShape = RoundedCornerShape(12.dp)
private val CompactGap = 4.dp

private fun displayMode(mode: FolderMode) = when (mode) {
    FolderMode.MIRROR -> "MIRROR"
    FolderMode.ON_DEMAND, FolderMode.INDEX_ONLY -> "ON-DEMAND"
}
private fun isMirror(mode: FolderMode) = mode == FolderMode.MIRROR

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { NetworkStorageScreen() } }
    }
}

@Composable
private fun NetworkStorageScreen(viewModel: MainViewModel = hiltViewModel()) {
    val screen by viewModel.screen.collectAsState()
    val message by viewModel.message.collectAsState()
    BackHandler(enabled = screen == AppScreen.BROWSER) { viewModel.browserBack() }
    BackHandler(enabled = screen == AppScreen.MIRROR) { viewModel.showConnections() }
    BackHandler(enabled = screen == AppScreen.CONNECTION_EDIT) { viewModel.showConnections() }

    Scaffold(
        bottomBar = {
            if (screen != AppScreen.CONNECTION_EDIT) {
                NavigationBar {
                    NavigationBarItem(selected = screen == AppScreen.CONNECTIONS, onClick = viewModel::showConnections, icon = { Text("▤") }, label = { Text("Connections") })
                    NavigationBarItem(selected = screen == AppScreen.BROWSER, onClick = { if (viewModel.selectedConnection.value != null) viewModel.screen.value = AppScreen.BROWSER }, icon = { Text("▱") }, label = { Text("Browser") })
                    NavigationBarItem(selected = screen == AppScreen.SETTINGS, onClick = viewModel::showSettings, icon = { Text("⚙") }, label = { Text("Settings") })
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            message?.let { Text(it, Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
            when (screen) {
                AppScreen.CONNECTIONS -> ConnectionsScreen(viewModel)
                AppScreen.CONNECTION_EDIT -> ConnectionEditorScreen(viewModel)
                AppScreen.BROWSER -> BrowserScreen(viewModel)
                AppScreen.MIRROR -> SyncScreen(viewModel)
                AppScreen.SETTINGS -> SettingsScreen(viewModel)
            }
        }
    }
}

@Composable
private fun ConnectionsScreen(viewModel: MainViewModel) {
    val connections by viewModel.connections.collectAsState()
    val scan by viewModel.scan.collectAsState()
    var deleteConnection by remember { mutableStateOf<ConnectionSummary?>(null) }
    var deleteIndex by remember { mutableStateOf<ConnectionSummary?>(null) }

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
                            OutlinedButton(onClick = { viewModel.startScan(summary) }, enabled = summary.hasRootRule) { Text("↻ Scan") }
                            if (isMirror(summary.connection.rootMode)) OutlinedButton(onClick = { viewModel.openMirror(summary) }, enabled = summary.hasRootRule) { Text("⇄ Sync") }
                            TextButton(onClick = { menu = true }) { Text("⋮") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
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

@Composable
private fun SyncScreen(viewModel: MainViewModel) {
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

@Composable
private fun BrowserScreen(viewModel: MainViewModel) {
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
    val missingColor = if (item.remoteExists) Color.Unspecified else MaterialTheme.colorScheme.error; var menu by remember(item.relativePath) { mutableStateOf(false) }; val hasCache = !item.isDirectory && (item.localState == LocalFileState.CACHED || item.localState == LocalFileState.REMOTE_UPDATED)
    Surface(shape = RowShape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp)) {
            val icon = if (item.isDirectory) "📁" else BrowserPresentation.stateIcon(item.localState); Text(icon, style = MaterialTheme.typography.titleMedium); Spacer(Modifier.width(8.dp)); Column(Modifier.weight(1f)) { Text(item.name, color = missingColor, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis); if (!item.isDirectory) Text(formatBytes(item.size), color = if (item.remoteExists) MaterialTheme.colorScheme.onSurfaceVariant else missingColor, style = MaterialTheme.typography.bodySmall) }
            if (item.isDirectory) Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) else if (hasCache) { TextButton(onClick = { menu = true }) { Text("⋮") }; DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) { DropdownMenuItem(text = { Text("Remove cached copy") }, onClick = { menu = false; onRemoveCache() }) } }
        }
    }
}

@Composable
private fun SettingsScreen(viewModel: MainViewModel) {
    val bytes by viewModel.cacheLimitBytes.collectAsState(); var custom by remember { mutableStateOf("") }; var confirmClearCache by remember { mutableStateOf(false) }; val cacheRoot by viewModel.cacheRootUri.collectAsState(); val mirrorRoot by viewModel.mirrorRootUri.collectAsState(); val usage by viewModel.cacheUsage.collectAsState(); val automaticSyncEnabled by viewModel.automaticMirrorSyncEnabled.collectAsState(); val automaticSyncInterval by viewModel.automaticMirrorSyncIntervalMinutes.collectAsState(); val context = LocalContext.current
    fun persist(uri: android.net.Uri): Boolean = runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }.isSuccess
    val cachePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { if (persist(it)) viewModel.saveStorageRoot(StorageRootKind.CACHE, it) else viewModel.message.value = "Could not retain folder access" } }; val mirrorPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { if (persist(it)) viewModel.saveStorageRoot(StorageRootKind.MIRROR, it) else viewModel.message.value = "Could not retain folder access" } }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = ScreenPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Spacer(Modifier.height(8.dp)); Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold); Text("Cache and mirror storage", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { SettingsSectionCard("☁ On-demand cache") { Text("Storage folder", style = MaterialTheme.typography.labelMedium); StoragePathBox(cacheRoot); OutlinedButton(onClick = { cachePicker.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text("▱ Choose folder") }; Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Cache limit", fontWeight = FontWeight.Medium); Text("${bytes / SettingsRepository.BYTES_PER_GIB} GB", color = MaterialTheme.colorScheme.primary) }; Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) { SettingsRepository.PRESET_GIB.forEach { gib -> val modifier = Modifier.weight(1f); if (bytes == gib * SettingsRepository.BYTES_PER_GIB) FilledTonalButton(onClick = { viewModel.setCacheLimitGib(gib.toString()) }, modifier = modifier) { Text("$gib GB") } else OutlinedButton(onClick = { viewModel.setCacheLimitGib(gib.toString()) }, modifier = modifier) { Text("$gib GB") } } }; OutlinedTextField(custom, { custom = it }, label = { Text("Custom limit") }, suffix = { Text("GB") }, modifier = Modifier.fillMaxWidth(), singleLine = true); Button(onClick = { viewModel.setCacheLimitGib(custom) }, modifier = Modifier.fillMaxWidth()) { Text("Save custom limit") }; Text("Usage ${formatBytes(usage)} / ${formatBytes(bytes)}", style = MaterialTheme.typography.bodySmall); LinearProgressIndicator(progress = { usageRatio(usage, bytes) }, modifier = Modifier.fillMaxWidth()); OutlinedButton(onClick = { confirmClearCache = true }, enabled = usage > 0, modifier = Modifier.fillMaxWidth()) { Text("Clear all On-demand cache") } } }
        item { SettingsSectionCard("▣ Mirror") { Text("Storage folder", style = MaterialTheme.typography.labelMedium); StoragePathBox(mirrorRoot); OutlinedButton(onClick = { mirrorPicker.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text("▱ Choose folder") }; Text("Mirror files are persistent and are not removed by cache cleanup.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); HorizontalDivider(); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Column { Text("Automatic sync", fontWeight = FontWeight.Medium); Text("Runs only while a network is connected and battery is not low.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; if (automaticSyncEnabled) FilledTonalButton(onClick = { viewModel.setAutomaticMirrorSyncEnabled(false) }) { Text("On") } else OutlinedButton(onClick = { viewModel.setAutomaticMirrorSyncEnabled(true) }, enabled = mirrorRoot != null) { Text("Off") } }; if (automaticSyncEnabled) { Text("Sync interval", style = MaterialTheme.typography.labelMedium); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) { SettingsRepository.AUTOMATIC_MIRROR_SYNC_INTERVAL_OPTIONS_MINUTES.forEach { minutes -> val label = automaticSyncIntervalLabel(minutes); val modifier = Modifier.weight(1f); if (automaticSyncInterval == minutes) FilledTonalButton(onClick = { viewModel.setAutomaticMirrorSyncIntervalMinutes(minutes) }, modifier = modifier) { Text(label) } else OutlinedButton(onClick = { viewModel.setAutomaticMirrorSyncIntervalMinutes(minutes) }, modifier = modifier) { Text(label) } } }; Text("Automatic sync refreshes the NAS index first, then copies NAS-only and NAS-newer files to Mirror storage. Local Mirror files are not deleted.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
    }
    if (confirmClearCache) AlertDialog(onDismissRequest = { confirmClearCache = false }, title = { Text("Clear On-demand cache?") }, text = { Text("Downloaded cache copies will be removed. NAS and Mirror files will not be changed.") }, dismissButton = { TextButton(onClick = { confirmClearCache = false }) { Text("Cancel") } }, confirmButton = { TextButton(onClick = { confirmClearCache = false; viewModel.clearCache() }) { Text("Clear cache") } })
}

@Composable private fun SettingsSectionCard(title: String, content: @Composable ColumnScope.() -> Unit) { ElevatedCard(Modifier.fillMaxWidth(), shape = SectionShape, colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); content() } } }
@Composable private fun StoragePathBox(uri: String?) { Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainer) { Text(friendlyStorageRoot(uri), Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
@Composable private fun ConfirmDelete(title: String, body: String, dismiss: () -> Unit, confirm: () -> Unit) = AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(body) }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } }, confirmButton = { TextButton(onClick = confirm) { Text("Delete") } })

@Composable
private fun ConnectionEditorScreen(viewModel: MainViewModel) {
    val editor by viewModel.editor.collectAsState(); var password by remember(editor.id) { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = ScreenPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Spacer(Modifier.height(8.dp)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { TextButton(onClick = viewModel::showConnections) { Text("←") }; Column { Text(if (editor.id == null) "Add connection" else "Edit connection", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold); Text("SMB connection settings", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
        item { OutlinedTextField(editor.name, { viewModel.updateEditor(editor.copy(name = it)) }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { OutlinedTextField(editor.host, { viewModel.updateEditor(editor.copy(host = it)) }, label = { Text("Host") }, modifier = Modifier.weight(3f), singleLine = true); OutlinedTextField(editor.port, { viewModel.updateEditor(editor.copy(port = it)) }, label = { Text("Port") }, modifier = Modifier.weight(1f), singleLine = true) } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { OutlinedTextField(editor.username, { viewModel.updateEditor(editor.copy(username = it)) }, label = { Text("Username") }, modifier = Modifier.weight(1f), singleLine = true); OutlinedTextField(password, { password = it }, label = { Text(if (editor.id == null) "Password" else "Password (saved)") }, placeholder = { if (editor.id != null) Text("Leave blank to keep") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(1f), singleLine = true) } }
        item { OutlinedTextField(editor.domain, { viewModel.updateEditor(editor.copy(domain = it)) }, label = { Text("Domain (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
        item { OutlinedTextField(editor.share, { viewModel.updateEditor(editor.copy(share = it, basePath = "")) }, label = { Text("Share") }, placeholder = { Text("e.g. documents") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { OutlinedTextField(editor.networkFolder, {}, readOnly = true, label = { Text("Network folder") }, modifier = Modifier.weight(1f)); Button(onClick = { viewModel.openNetworkFolderPicker(password) }, enabled = editor.share.isNotBlank()) { Text("📁") } } }
        item { Text("Mode", style = MaterialTheme.typography.labelMedium); Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(FolderMode.ON_DEMAND, FolderMode.MIRROR).forEach { candidate -> val selected = editor.mode == candidate || (editor.mode == FolderMode.INDEX_ONLY && candidate == FolderMode.ON_DEMAND); if (selected) FilledTonalButton(onClick = { viewModel.updateEditor(editor.copy(mode = candidate)) }) { Text(displayMode(candidate)) } else OutlinedButton(onClick = { viewModel.updateEditor(editor.copy(mode = candidate)) }) { Text(displayMode(candidate)) } } } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = viewModel::showConnections) { Text("Cancel") }; Button(onClick = { viewModel.saveEditor(password); password = "" }, enabled = editor.share.isNotBlank()) { Text("Save") } } }
    }
    val picker by viewModel.remotePicker.collectAsState(); if (picker.visible) AlertDialog(onDismissRequest = viewModel::closeRemotePicker, title = { Text("Network folder") }, text = { Column { Text("${editor.host} / ${picker.share}${picker.path.takeIf(String::isNotBlank)?.let { " / $it" }.orEmpty()}"); if (picker.loading) Text("Loading…"); picker.error?.let { Text("Could not browse: $it", color = MaterialTheme.colorScheme.error) }; if (picker.path.isNotEmpty()) Text("← One level up", Modifier.fillMaxWidth().clickable { viewModel.browsePickerFolder(password, FolderNavigation.parent(picker.path)) }.padding(8.dp)); picker.folders.forEach { folder -> Text("📁 ${folder.substringAfterLast('/')} ", Modifier.fillMaxWidth().clickable { viewModel.browsePickerFolder(password, folder) }.padding(8.dp)) } } }, dismissButton = { TextButton(onClick = viewModel::closeRemotePicker) { Text("Cancel") } }, confirmButton = { TextButton(onClick = viewModel::usePickerFolder) { Text("Use this folder") } })
}

private fun friendlyStorageRoot(uri: String?): String = uri?.replace("content://com.android.externalstorage.documents/tree/primary%3A", "Internal storage / ") ?: "Not set"
private fun formatBytes(bytes: Long): String = when { bytes >= SettingsRepository.BYTES_PER_GIB -> "${bytes / SettingsRepository.BYTES_PER_GIB} GB"; bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"; bytes >= 1024L -> "${bytes / 1024L} KB"; else -> "$bytes B" }
private fun downloadPercent(copied: Long, total: Long) = if (total <= 0) 100 else ((copied.toDouble() / total.toDouble()) * 100.0).toInt().coerceIn(0, 100)
private fun usageRatio(used: Long, total: Long): Float = if (total <= 0L) 0f else (used.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
private fun automaticSyncIntervalLabel(minutes: Long): String = when (minutes) { 15L -> "15 min"; 60L -> "1 h"; 360L -> "6 h"; 1440L -> "24 h"; else -> "$minutes min" }
