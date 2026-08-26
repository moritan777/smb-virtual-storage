package dev.networkstorage

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.networkstorage.data.settings.SettingsRepository
import dev.networkstorage.data.settings.StorageRootKind
import java.text.DateFormat
import java.util.Date

@Composable
internal fun SettingsScreen(viewModel: MainViewModel) {
    val bytes by viewModel.cacheLimitBytes.collectAsState(); var custom by remember { mutableStateOf("") }; var confirmClearCache by remember { mutableStateOf(false) }; val cacheRoot by viewModel.cacheRootUri.collectAsState(); val mirrorRoot by viewModel.mirrorRootUri.collectAsState(); val usage by viewModel.cacheUsage.collectAsState(); val automaticSyncEnabled by viewModel.automaticMirrorSyncEnabled.collectAsState(); val automaticSyncInterval by viewModel.automaticMirrorSyncIntervalMinutes.collectAsState(); val context = LocalContext.current
    val statusSettings = remember(context) { SettingsRepository(context.applicationContext) }
    val lastAutomaticSyncAt by statusSettings.automaticMirrorSyncLastRunAt.collectAsState(initial = 0L)
    val lastAutomaticSyncStatus by statusSettings.automaticMirrorSyncLastStatus.collectAsState(initial = SettingsRepository.AUTOMATIC_SYNC_STATUS_NEVER)
    val lastAutomaticSyncFiles by statusSettings.automaticMirrorSyncLastFiles.collectAsState(initial = 0L)
    val lastAutomaticSyncBytes by statusSettings.automaticMirrorSyncLastBytes.collectAsState(initial = 0L)
    fun persist(uri: android.net.Uri): Boolean = runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }.isSuccess
    val cachePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { if (persist(it)) viewModel.saveStorageRoot(StorageRootKind.CACHE, it) else viewModel.message.value = "Could not retain folder access" } }; val mirrorPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { if (persist(it)) viewModel.saveStorageRoot(StorageRootKind.MIRROR, it) else viewModel.message.value = "Could not retain folder access" } }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = ScreenPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Spacer(Modifier.height(8.dp)); Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold); Text("Cache and mirror storage", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { SettingsSectionCard("☁ On-demand cache") { Text("Storage folder", style = MaterialTheme.typography.labelMedium); StoragePathBox(cacheRoot); OutlinedButton(onClick = { cachePicker.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text("▱ Choose folder") }; Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Cache limit", fontWeight = FontWeight.Medium); Text("${bytes / SettingsRepository.BYTES_PER_GIB} GB", color = MaterialTheme.colorScheme.primary) }; Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) { SettingsRepository.PRESET_GIB.forEach { gib -> val modifier = Modifier.weight(1f); if (bytes == gib * SettingsRepository.BYTES_PER_GIB) FilledTonalButton(onClick = { viewModel.setCacheLimitGib(gib.toString()) }, modifier = modifier) { Text("$gib GB") } else OutlinedButton(onClick = { viewModel.setCacheLimitGib(gib.toString()) }, modifier = modifier) { Text("$gib GB") } } }; OutlinedTextField(custom, { custom = it }, label = { Text("Custom limit") }, suffix = { Text("GB") }, modifier = Modifier.fillMaxWidth(), singleLine = true); Button(onClick = { viewModel.setCacheLimitGib(custom) }, modifier = Modifier.fillMaxWidth()) { Text("Save custom limit") }; Text("Usage ${formatBytes(usage)} / ${formatBytes(bytes)}", style = MaterialTheme.typography.bodySmall); LinearProgressIndicator(progress = { usageRatio(usage, bytes) }, modifier = Modifier.fillMaxWidth()); OutlinedButton(onClick = { confirmClearCache = true }, enabled = usage > 0, modifier = Modifier.fillMaxWidth()) { Text("Clear all On-demand cache") } } }
        item {
            SettingsSectionCard("▣ Mirror") {
                Text("Storage folder", style = MaterialTheme.typography.labelMedium)
                StoragePathBox(mirrorRoot)
                OutlinedButton(onClick = { mirrorPicker.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text("▱ Choose folder") }
                Text("Mirror files are persistent and are not removed by cache cleanup.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider()
                Text("Automatic sync", fontWeight = FontWeight.Medium)
                Text("Runs only while a network is connected and battery is not low.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (automaticSyncEnabled) {
                    FilledTonalButton(onClick = { viewModel.setAutomaticMirrorSyncEnabled(false) }, modifier = Modifier.fillMaxWidth()) { Text("Automatic sync: On") }
                } else {
                    OutlinedButton(onClick = { viewModel.setAutomaticMirrorSyncEnabled(true) }, enabled = mirrorRoot != null, modifier = Modifier.fillMaxWidth()) { Text("Automatic sync: Off") }
                }
                if (automaticSyncEnabled) {
                    Text("Sync interval", style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        SettingsRepository.AUTOMATIC_MIRROR_SYNC_INTERVAL_OPTIONS_MINUTES.forEach { minutes ->
                            val label = automaticSyncIntervalLabel(minutes)
                            val modifier = Modifier.weight(1f)
                            if (automaticSyncInterval == minutes) FilledTonalButton(onClick = { viewModel.setAutomaticMirrorSyncIntervalMinutes(minutes) }, modifier = modifier) { Text(label) }
                            else OutlinedButton(onClick = { viewModel.setAutomaticMirrorSyncIntervalMinutes(minutes) }, modifier = modifier) { Text(label) }
                        }
                    }
                    Text("Automatic sync refreshes the NAS index first, then copies NAS-only and NAS-newer files to Mirror storage. Local Mirror files are not deleted.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
                Text("Last automatic sync", style = MaterialTheme.typography.labelMedium)
                if (lastAutomaticSyncAt == 0L) {
                    Text("Never run yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val statusLabel = if (lastAutomaticSyncStatus == SettingsRepository.AUTOMATIC_SYNC_STATUS_SUCCESS) "Success" else "Retrying"
                    Text("${DateFormat.getDateTimeInstance().format(Date(lastAutomaticSyncAt))} • $statusLabel", style = MaterialTheme.typography.bodySmall)
                    Text("$lastAutomaticSyncFiles files • ${formatBytes(lastAutomaticSyncBytes)} copied", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (confirmClearCache) AlertDialog(onDismissRequest = { confirmClearCache = false }, title = { Text("Clear On-demand cache?") }, text = { Text("Downloaded cache copies will be removed. NAS and Mirror files will not be changed.") }, dismissButton = { TextButton(onClick = { confirmClearCache = false }) { Text("Cancel") } }, confirmButton = { TextButton(onClick = { confirmClearCache = false; viewModel.clearCache() }) { Text("Clear cache") } })
}

@Composable private fun SettingsSectionCard(title: String, content: @Composable ColumnScope.() -> Unit) { ElevatedCard(Modifier.fillMaxWidth(), shape = SectionShape, colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); content() } } }
@Composable private fun StoragePathBox(uri: String?) { Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainer) { Text(friendlyStorageRoot(uri), Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
private fun automaticSyncIntervalLabel(minutes: Long): String = when (minutes) { 15L -> "15 min"; 60L -> "1 h"; 360L -> "6 h"; 1440L -> "24 h"; else -> "$minutes min" }
