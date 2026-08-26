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
import androidx.compose.runtime.ColumnScope
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
import dev.networkstorage.data.settings.SettingsRepository
import dev.networkstorage.data.settings.StorageRootKind
import dev.networkstorage.domain.FolderMode
import dev.networkstorage.domain.FolderNavigation
import java.text.DateFormat
import java.util.Date

private val ScreenPadding = 20.dp
private val SectionShape = RoundedCornerShape(20.dp)
private val RowShape = RoundedCornerShape(16.dp)

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
    BackHandler(enabled = screen == AppScreen.CONNECTION_EDIT) { viewModel.showConnections() }
    Scaffold(bottomBar = {
        if (screen != AppScreen.CONNECTION_EDIT) NavigationBar {
            NavigationBarItem(screen == AppScreen.CONNECTIONS, viewModel::showConnections, { Text("Connections") }, icon = { Text("▤") })
            NavigationBarItem(screen == AppScreen.BROWSER, { if (viewModel.selectedConnection.value != null) viewModel.screen.value = AppScreen.BROWSER }, { Text("Browser") }, icon = { Text("▱") })
            NavigationBarItem(screen == AppScreen.SETTINGS, viewModel::showSettings, { Text("Settings") }, icon = { Text("⚙") })
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            message?.let { Text(it, Modifier.padding(horizontal = ScreenPadding, vertical = 6.dp), color = MaterialTheme.colorScheme.primary) }
            when (screen) {
                AppScreen.CONNECTIONS -> ConnectionsScreen(viewModel)
                AppScreen.CONNECTION_EDIT -> ConnectionEditorScreen(viewModel)
                AppScreen.BROWSER -> BrowserScreen(viewModel)
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
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Network Storage", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text("Connections and scans", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FloatingActionButton(viewModel::openAddConnection) { Text("+") }
        }
        Spacer(Modifier.height(18.dp))
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(connections.size, key = { connections[it].connection.id }) { index ->
                val summary = connections[index]
                var menu by remember { mutableStateOf(false) }
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = SectionShape,
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text(summary.connection.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${summary.connection.share}${summary.connection.basePath.takeIf(String::isNotBlank)?.let { " / $it" }.orEmpty()}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                                Text("SMB", Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        HorizontalDivider()
                        Text("Last scan", style = MaterialTheme.typography.labelLarge)
                        Text(summary.lastScanAt?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "Never")
                        Text("${summary.entryCount} entries", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (scan.ownerId == summary.connection.id && scan.state?.isFinished == false) {
                            Text("Scanning… ${scan.count} entries")
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            TextButton(viewModel::cancelScan) { Text("Cancel") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewModel.browse(summary) }) { Text("▱  Open") }
                            OutlinedButton(onClick = { viewModel.startScan(summary) }, enabled = summary.hasRootRule) { Text("↻  Scan") }
                            TextButton(onClick = { menu = true }) { Text("⋮") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem({ Text("Edit") }, { menu = false; viewModel.openEditConnection(summary) })
                                DropdownMenuItem({ Text("Scan") }, { menu = false; viewModel.startScan(summary) }, enabled = summary.hasRootRule)
                                DropdownMenuItem({ Text("Delete index") }, { menu = false; deleteIndex = summary }, enabled = summary.hasRootRule)
                                DropdownMenuItem({ Text("Delete connection") }, { menu = false; deleteConnection = summary })
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
private fun BrowserScreen(viewModel: MainViewModel) {
    val connection by viewModel.selectedConnection.collectAsState()
    val path by viewModel.currentPath.collectAsState()
    val items = viewModel.browserItems.collectAsLazyPagingItems()
    val download by viewModel.download.collectAsState()
    Column(Modifier.fillMaxSize().padding(horizontal = ScreenPadding)) {
        Spacer(Modifier.height(14.dp))
        val root = connection?.connection?.let { listOf(it.share, it.basePath).filter(String::isNotBlank).joinToString(" / ") }.orEmpty()
        Text(path.substringAfterLast('/', root.substringAfterLast('/', connection?.connection?.name ?: "Select a connection")), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(listOfNotNull(connection?.connection?.name, root.takeIf(String::isNotEmpty), path.takeIf(String::isNotEmpty)).joinToString(" / "), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        if (path.isNotEmpty()) Surface(shape = RowShape, color = MaterialTheme.colorScheme.surfaceContainer) {
            Text("←  One level up", Modifier.fillMaxWidth().clickable { viewModel.browserBack() }.padding(14.dp), fontWeight = FontWeight.Medium)
        }
        download.state?.takeIf { !it.isFinished }?.let {
            Spacer(Modifier.height(10.dp))
            Surface(shape = RowShape, color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Downloading ${download.path}")
                    LinearProgressIndicator(progress = { usageRatio(download.count, download.total) }, modifier = Modifier.fillMaxWidth())
                    Text("${formatBytes(download.count)} / ${formatBytes(download.total)} (${downloadPercent(download.count, download.total)}%)", style = MaterialTheme.typography.bodySmall)
                    TextButton(viewModel::cancelDownload) { Text("Cancel") }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items.itemCount) { index -> items[index]?.let { BrowserRow(it) { viewModel.openFolder(it) } } }
        }
    }
}

@Composable
private fun BrowserRow(item: BrowserItem, onClick: () -> Unit) {
    val missingColor = if (item.remoteExists) Color.Unspecified else MaterialTheme.colorScheme.error
    Surface(shape = RowShape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp)) {
            val icon = if (item.isDirectory) "📁" else BrowserPresentation.stateIcon(item.localState)
            Text(icon, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, color = missingColor, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!item.isDirectory) Text(formatBytes(item.size), color = if (item.remoteExists) MaterialTheme.colorScheme.onSurfaceVariant else missingColor, style = MaterialTheme.typography.bodySmall)
            }
            if (item.isDirectory) Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsScreen(viewModel: MainViewModel) {
    val bytes by viewModel.cacheLimitBytes.collectAsState()
    var custom by remember { mutableStateOf("") }
    val cacheRoot by viewModel.cacheRootUri.collectAsState()
    val mirrorRoot by viewModel.mirrorRootUri.collectAsState()
    val usage by viewModel.cacheUsage.collectAsState()
    val context = LocalContext.current

    fun persist(uri: android.net.Uri): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }.isSuccess

    val cachePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { if (persist(it)) viewModel.saveStorageRoot(StorageRootKind.CACHE, it) else viewModel.message.value = "Could not retain folder access" }
    }
    val mirrorPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { if (persist(it)) viewModel.saveStorageRoot(StorageRootKind.MIRROR, it) else viewModel.message.value = "Could not retain folder access" }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Spacer(Modifier.height(18.dp))
            Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text("Cache and mirror storage", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item {
            SettingsSectionCard(title = "☁  On-demand cache") {
                Text("Storage folder", style = MaterialTheme.typography.labelLarge)
                StoragePathBox(cacheRoot)
                OutlinedButton(onClick = { cachePicker.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text("▱  Choose folder") }

                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Cache limit", fontWeight = FontWeight.Medium)
                    Text("${bytes / SettingsRepository.BYTES_PER_GIB} GB", color = MaterialTheme.colorScheme.primary)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsRepository.PRESET_GIB.forEach { gib ->
                        if (bytes == gib * SettingsRepository.BYTES_PER_GIB) {
                            FilledTonalButton(onClick = { viewModel.setCacheLimitGib(gib.toString()) }) { Text("$gib GB") }
                        } else {
                            OutlinedButton(onClick = { viewModel.setCacheLimitGib(gib.toString()) }) { Text("$gib GB") }
                        }
                    }
                }

                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    label = { Text("Custom limit") },
                    suffix = { Text("GB") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Button(onClick = { viewModel.setCacheLimitGib(custom) }, modifier = Modifier.fillMaxWidth()) { Text("Save custom limit") }

                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)) {
                    Text(
                        "Cache limits apply only to On-demand cache. Mirrored files are excluded.",
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Text("Usage  ${formatBytes(usage)} / ${formatBytes(bytes)}", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { usageRatio(usage, bytes) }, modifier = Modifier.fillMaxWidth())
            }
        }

        item {
            SettingsSectionCard(title = "▣  Mirror") {
                Text("Storage folder", style = MaterialTheme.typography.labelLarge)
                StoragePathBox(mirrorRoot)
                OutlinedButton(onClick = { mirrorPicker.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text("▱  Choose folder") }
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Text(
                        "The system picker can create folders. Changing the destination does not move existing files.",
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item { Spacer(Modifier.height(14.dp)) }
    }
}

@Composable
private fun SettingsSectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = SectionShape,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun StoragePathBox(uri: String?) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Text(
            friendlyStorageRoot(uri),
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ConnectionEditorScreen(viewModel: MainViewModel) {
    val editor by viewModel.editor.collectAsState()
    var password by remember(editor.id) { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(ScreenPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth()) { TextButton(viewModel::showConnections) { Text("←") }; Text(if (editor.id == null) "Add connection" else "Edit connection", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold) }
        OutlinedTextField(editor.name, { viewModel.updateEditor(editor.copy(name = it)) }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(editor.host, { viewModel.updateEditor(editor.copy(host = it)) }, label = { Text("Host") }, modifier = Modifier.weight(3f), singleLine = true)
            OutlinedTextField(editor.port, { viewModel.updateEditor(editor.copy(port = it)) }, label = { Text("Port") }, modifier = Modifier.weight(1f), singleLine = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(editor.username, { viewModel.updateEditor(editor.copy(username = it)) }, label = { Text("Username") }, modifier = Modifier.weight(1f), singleLine = true)
            OutlinedTextField(password, { password = it }, label = { Text(if (editor.id == null) "Password" else "Password (saved)") }, placeholder = { if (editor.id != null) Text("Leave blank to keep") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(1f), singleLine = true)
        }
        OutlinedTextField(editor.domain, { viewModel.updateEditor(editor.copy(domain = it)) }, label = { Text("Domain (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(editor.share, { viewModel.updateEditor(editor.copy(share = it, basePath = "")) }, label = { Text("Share") }, placeholder = { Text("e.g. documents") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row { OutlinedTextField(editor.networkFolder, {}, readOnly = true, label = { Text("Network folder") }, modifier = Modifier.weight(1f)); Spacer(Modifier.width(8.dp)); Button({ viewModel.openNetworkFolderPicker(password) }, enabled = editor.share.isNotBlank()) { Text("📁") } }
        Text("Enter the SMB share name, then use the folder button to choose any folder inside that share.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Mode", fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { FolderMode.entries.forEach { candidate -> Button({ viewModel.updateEditor(editor.copy(mode = candidate)) }, enabled = editor.mode != candidate) { Text(candidate.name.replace('_', ' ').lowercase()) } } }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(viewModel::showConnections) { Text("Cancel") }; Button({ viewModel.saveEditor(password); password = "" }, enabled = editor.share.isNotBlank()) { Text("Save") } }
    }
    val picker by viewModel.remotePicker.collectAsState()
    if (picker.visible) AlertDialog(onDismissRequest = viewModel::closeRemotePicker, title = { Text("Network folder") }, text = { Column {
        Text("${editor.host} / ${picker.share}${picker.path.takeIf(String::isNotBlank)?.let { " / $it" }.orEmpty()}")
        if (picker.loading) Text("Loading…")
        picker.error?.let { Text("Could not browse: $it", color = MaterialTheme.colorScheme.error) }
        if (picker.path.isNotEmpty()) Text("↑ One level up", Modifier.fillMaxWidth().clickable { viewModel.browsePickerFolder(password, FolderNavigation.parent(picker.path)) }.padding(10.dp))
        picker.folders.forEach { folder -> Text("📁 ${folder.substringAfterLast('/')}", Modifier.fillMaxWidth().clickable { viewModel.browsePickerFolder(password, folder) }.padding(10.dp)) }
    } }, dismissButton = { TextButton(viewModel::closeRemotePicker) { Text("Cancel") } }, confirmButton = { TextButton(viewModel::usePickerFolder) { Text("Use this folder") } })
}

@Composable
private fun ConfirmDelete(title: String, body: String, dismiss: () -> Unit, confirm: () -> Unit) = AlertDialog(
    onDismissRequest = dismiss,
    title = { Text(title) },
    text = { Text(body) },
    dismissButton = { TextButton(dismiss) { Text("Cancel") } },
    confirmButton = { TextButton(confirm) { Text("Delete") } },
)

private fun friendlyStorageRoot(uri: String?): String {
    if (uri == null) return "Not set"
    val decoded = runCatching { android.net.Uri.decode(uri) }.getOrDefault(uri)
    return decoded.substringAfter("primary:", decoded)
}

private fun usageRatio(value: Long, total: Long): Float = if (total <= 0L) 0f else (value.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()

private fun formatBytes(bytes: Long): String = when {
    bytes >= SettingsRepository.BYTES_PER_GIB -> "${bytes / SettingsRepository.BYTES_PER_GIB} GB"
    bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
    bytes >= 1024L -> "${bytes / 1024L} KB"
    else -> "$bytes B"
}

private fun downloadPercent(copied: Long, total: Long) = if (total <= 0) 100 else ((copied.toDouble() / total.toDouble()) * 100.0).toInt().coerceIn(0, 100)
