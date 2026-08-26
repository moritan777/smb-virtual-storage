package dev.networkstorage

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import dagger.hilt.android.AndroidEntryPoint
import dev.networkstorage.data.db.ConnectionSummary
import dev.networkstorage.data.settings.SettingsRepository
import dev.networkstorage.data.settings.StorageRootKind
import dev.networkstorage.domain.FolderMode
import java.text.DateFormat
import java.util.Date

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { MaterialTheme { NetworkStorageScreen() } } }
}

@Composable
private fun NetworkStorageScreen(viewModel: MainViewModel = hiltViewModel()) {
    val screen by viewModel.screen.collectAsState()
    val message by viewModel.message.collectAsState()
    BackHandler(enabled = screen == AppScreen.BROWSER) { viewModel.browserBack() }
    Scaffold(bottomBar = {
        NavigationBar {
            NavigationBarItem(screen == AppScreen.CONNECTIONS, viewModel::showConnections, { Text("Connections") })
            NavigationBarItem(screen == AppScreen.BROWSER, { if (viewModel.selectedConnection.value != null) viewModel.screen.value = AppScreen.BROWSER }, { Text("Browser") })
            NavigationBarItem(screen == AppScreen.SETTINGS, viewModel::showSettings, { Text("Settings") })
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            message?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.primary) }
            when (screen) {
                AppScreen.CONNECTIONS -> ConnectionsScreen(viewModel)
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
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Network Storage", style = MaterialTheme.typography.headlineMedium); ConnectionForm(viewModel); HorizontalDivider() }
        items(connections.size, key = { connections[it].connection.id }) { index ->
            val summary = connections[index]
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(summary.connection.name, style = MaterialTheme.typography.titleMedium)
                Text("${summary.connection.host}/${summary.connection.share}/${summary.connection.basePath} · ${summary.connection.rootMode}")
                Text("Last scan: ${summary.lastScanAt?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "Never"} · ${summary.entryCount} entries")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { viewModel.browse(summary) }) { Text("Browse") }
                    Button(onClick = { viewModel.startScan(summary) }, enabled = summary.hasRootRule) { Text("Scan") }
                    TextButton(onClick = { deleteIndex = summary }, enabled = summary.hasRootRule) { Text("Delete index") }
                    TextButton(onClick = { deleteConnection = summary }) { Text("Delete") }
                }
            }
        }
        scan.state?.let { state -> item { Text("Scan: $state · ${scan.count} entries"); if (!state.isFinished) Button(viewModel::cancelScan) { Text("Cancel scan") } } }
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
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(connection?.connection?.name ?: "Select a connection", style = MaterialTheme.typography.headlineSmall)
        Text(listOfNotNull(connection?.connection?.name, path.takeIf(String::isNotEmpty)).joinToString(" / "))
        if (path.isNotEmpty()) Text("⬆ One level up", Modifier.fillMaxWidth().clickable { viewModel.browserBack() }.padding(vertical = 12.dp))
        download.state?.takeIf { !it.isFinished }?.let { Text("⏳ ${download.path}: ${formatBytes(download.count)} / ${formatBytes(download.total)} (${downloadPercent(download.count, download.total)}%)"); Button(viewModel::cancelDownload) { Text("Cancel download") } }
        LazyColumn(Modifier.fillMaxSize()) {
            items(items.itemCount) { index -> items[index]?.let { BrowserRow(it) { viewModel.openFolder(it) } } }
        }
    }
}

@Composable
private fun BrowserRow(item: BrowserItem, onClick: () -> Unit) {
    val missingColor = if (item.remoteExists) Color.Unspecified else MaterialTheme.colorScheme.error
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)) {
        val icon = if (item.isDirectory) "📁" else when (item.localState) { LocalFileState.REMOTE_ONLY -> "☁"; LocalFileState.DOWNLOADING -> "⏳"; LocalFileState.CACHED -> "✓"; LocalFileState.REMOTE_UPDATED -> "↓"; LocalFileState.FAILED -> "!" }
        Text("$icon ${item.name}", color = missingColor, style = MaterialTheme.typography.titleMedium)
        Text("${if (item.isDirectory) "Folder" else formatBytes(item.size)} · ${item.mode} · ${if (item.remoteExists) item.localState else "REMOTE MISSING"}", color = missingColor)
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
    fun persist(uri: android.net.Uri): Boolean = runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }.isSuccess
    val cachePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { if (persist(it)) viewModel.saveStorageRoot(StorageRootKind.CACHE, it) else viewModel.message.value = "Could not retain folder access" } }
    val mirrorPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { if (persist(it)) viewModel.saveStorageRoot(StorageRootKind.MIRROR, it) else viewModel.message.value = "Could not retain folder access" } }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Text("On-demand cache limit: ${bytes / SettingsRepository.BYTES_PER_GIB} GB")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { SettingsRepository.PRESET_GIB.forEach { gib -> Button(onClick = { viewModel.setCacheLimitGib(gib.toString()) }) { Text("$gib GB") } } }
        OutlinedTextField(custom, { custom = it }, label = { Text("Custom GB (minimum 1)") }, singleLine = true)
        Button(onClick = { viewModel.setCacheLimitGib(custom) }) { Text("Save custom limit") }
        Text("Cache limit applies only to On-demand cache. Mirrored files are not included.")
        Text("Cache usage: ${formatBytes(usage)} / ${formatBytes(bytes)}")
        Text("On-demand Cache folder: ${cacheRoot ?: "Not set"}")
        Button(onClick = { cachePicker.launch(null) }) { Text("📁 Select Cache folder") }
        Text("Mirror folder: ${mirrorRoot ?: "Not set"}")
        Button(onClick = { mirrorPicker.launch(null) }) { Text("📁 Select Mirror folder") }
        Text("The system picker can create folders. Existing files are not moved when a destination changes.")
    }
}

@Composable private fun ConfirmDelete(title: String, body: String, dismiss: () -> Unit, confirm: () -> Unit) = AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(body) }, dismissButton = { TextButton(dismiss) { Text("Cancel") } }, confirmButton = { TextButton(confirm) { Text("Delete") } })

@Composable
private fun ConnectionForm(viewModel: MainViewModel) {
    var name by remember { mutableStateOf("") }; var host by remember { mutableStateOf("") }; var port by remember { mutableStateOf("445") }; var share by remember { mutableStateOf("") }; var basePath by remember { mutableStateOf("") }; var username by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }; var domain by remember { mutableStateOf("") }; var mode by remember { mutableStateOf(FolderMode.INDEX_ONLY) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Add connection", style = MaterialTheme.typography.titleLarge)
        listOf("Name" to name, "Host" to host, "Port" to port, "Share" to share, "Username" to username, "Domain (optional)" to domain).forEach { (label, value) -> OutlinedTextField(value, { new -> when (label) { "Name" -> name=new; "Host" -> host=new; "Port" -> port=new; "Share" -> share=new; "Username" -> username=new; else -> domain=new } }, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
        Row { OutlinedTextField(basePath, { basePath = it }, label = { Text("Base folder") }, modifier = Modifier.weight(1f), singleLine = true); Button(onClick = { viewModel.browseRemoteFolders(host, port, share, username, password, domain, basePath) }) { Text("📁") } }
        OutlinedTextField(password, { password = it }, label = { Text("Password (never shown again)") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { FolderMode.entries.forEach { candidate -> Button({ mode = candidate }, enabled = mode != candidate) { Text(candidate.name) } } }
        Button({ viewModel.add(name, host, port, share, basePath, username, password, domain, mode); password = "" }) { Text("Save") }
    }
    val picker by viewModel.remotePicker.collectAsState()
    if (picker.visible) AlertDialog(onDismissRequest = viewModel::closeRemotePicker, title = { Text("Select Base Folder") }, text = { Column { Text("Current: /${picker.path}"); if (picker.path.isNotEmpty()) Text("⬆ One level up", Modifier.clickable { viewModel.browseRemoteFolders(host, port, share, username, password, domain, picker.path.substringBeforeLast('/', "")) }.padding(8.dp)); if (picker.loading) Text("Loading…"); picker.error?.let { Text("Could not list folders: $it", color=MaterialTheme.colorScheme.error) }; picker.folders.forEach { folder -> Text("📁 ${folder.substringAfterLast('/')}", Modifier.fillMaxWidth().clickable { viewModel.browseRemoteFolders(host, port, share, username, password, domain, folder) }.padding(8.dp)) } } }, dismissButton = { TextButton(viewModel::closeRemotePicker) { Text("Cancel") } }, confirmButton = { TextButton({ basePath = picker.path; viewModel.closeRemotePicker() }) { Text("Select this folder") } })
}

private fun formatBytes(bytes: Long): String = when { bytes >= SettingsRepository.BYTES_PER_GIB -> "${bytes / SettingsRepository.BYTES_PER_GIB} GB"; bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"; bytes >= 1024L -> "${bytes / 1024L} KB"; else -> "$bytes B" }
private fun downloadPercent(copied: Long, total: Long) = if (total <= 0) 100 else ((copied.toDouble() / total.toDouble()) * 100.0).toInt().coerceIn(0, 100)
