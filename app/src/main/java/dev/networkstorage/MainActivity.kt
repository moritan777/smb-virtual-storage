package dev.networkstorage

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.AndroidEntryPoint
import dev.networkstorage.data.db.ConnectionEntity
import dev.networkstorage.domain.FolderMode

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { MaterialTheme { NetworkStorageScreen() } } }
}

@Composable
private fun NetworkStorageScreen(viewModel: MainViewModel = hiltViewModel()) {
    val connections by viewModel.connections.collectAsState()
    val entries by viewModel.entries.collectAsState()
    val scan by viewModel.scan.collectAsState()
    val message by viewModel.message.collectAsState()
    Scaffold { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("Network Storage", style = MaterialTheme.typography.headlineMedium); Text("Steps 0–2 · read-only SMB index") }
            item { ConnectionForm(viewModel) }
            message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
            item { HorizontalDivider(); Text("Connections", style = MaterialTheme.typography.titleLarge) }
            items(connections, key = { it.id }) { connection -> ConnectionRow(connection, viewModel) }
            scan.state?.let { state -> item { Text("Scan: $state · ${scan.count} entries${scan.path.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}"); if (!state.isFinished) Button(onClick = viewModel::cancelScan) { Text("Cancel scan") } } }
            item { HorizontalDivider(); Text("Indexed preview (${entries.size}, first 200)", style = MaterialTheme.typography.titleLarge) }
            items(entries, key = { "${it.connectionId}:${it.relativePath}" }) { entry -> Text("${if (entry.isDirectory) "📁" else "📄"} ${entry.relativePath} · ${entry.size} B · ${entry.mode}${if (!entry.remoteExists) " · remote missing" else ""}") }
        }
    }
}

@Composable
private fun ConnectionForm(viewModel: MainViewModel) {
    var name by remember { mutableStateOf("") }; var host by remember { mutableStateOf("") }; var port by remember { mutableStateOf("445") }
    var share by remember { mutableStateOf("") }; var basePath by remember { mutableStateOf("") }; var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }; var domain by remember { mutableStateOf("") }; var mode by remember { mutableStateOf(FolderMode.INDEX_ONLY) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Add connection", style = MaterialTheme.typography.titleLarge)
        listOf("Name" to name, "Host" to host, "Port" to port, "Share" to share, "Base folder" to basePath, "Username" to username, "Domain (optional)" to domain).forEach { (label, value) ->
            OutlinedTextField(value, { new -> when (label) { "Name" -> name=new; "Host" -> host=new; "Port" -> port=new; "Share" -> share=new; "Base folder" -> basePath=new; "Username" -> username=new; else -> domain=new } }, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
        OutlinedTextField(password, { password = it }, label = { Text("Password (never shown again)") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { FolderMode.entries.forEach { candidate -> Button(onClick = { mode = candidate }, enabled = mode != candidate) { Text(candidate.name) } } }
        Button(onClick = { viewModel.add(name, host, port, share, basePath, username, password, domain, mode); password = "" }) { Text("Save") }
    }
}

@Composable
private fun ConnectionRow(connection: ConnectionEntity, viewModel: MainViewModel) {
    Column(Modifier.fillMaxWidth()) { Text(connection.name, style = MaterialTheme.typography.titleMedium); Text("${connection.host}:${connection.port}/${connection.share}/${connection.basePath} · ${connection.rootMode}"); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { viewModel.select(connection) }) { Text("Results") }; Button(onClick = { viewModel.startScan(connection) }) { Text("Scan") } } }
}
