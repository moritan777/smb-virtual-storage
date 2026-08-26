package dev.networkstorage

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.AndroidEntryPoint
import dev.networkstorage.data.settings.SettingsRepository
import dev.networkstorage.domain.FolderMode
import dev.networkstorage.domain.FolderNavigation

internal val ScreenPadding = 14.dp
internal val SectionShape = RoundedCornerShape(16.dp)
internal val RowShape = RoundedCornerShape(12.dp)
internal val CompactGap = 4.dp

internal fun displayMode(mode: FolderMode) = when (mode) {
    FolderMode.MIRROR -> "MIRROR"
    FolderMode.ON_DEMAND, FolderMode.INDEX_ONLY -> "ON-DEMAND"
}

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

internal fun friendlyStorageRoot(uri: String?): String = uri?.replace("content://com.android.externalstorage.documents/tree/primary%3A", "Internal storage / ") ?: "Not set"
internal fun formatBytes(bytes: Long): String = when { bytes >= SettingsRepository.BYTES_PER_GIB -> "${bytes / SettingsRepository.BYTES_PER_GIB} GB"; bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"; bytes >= 1024L -> "${bytes / 1024L} KB"; else -> "$bytes B" }
internal fun usageRatio(used: Long, total: Long): Float = if (total <= 0L) 0f else (used.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
