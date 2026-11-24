package dev.networkstorage

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import dev.networkstorage.data.db.ConnectionSummary

@Composable
internal fun BackupSettingsScreen(viewModel: MainViewModel, onEditorVisibilityChange: (Boolean) -> Unit = {}) {
    val connections by viewModel.connections.collectAsState()
    var selected by remember { mutableStateOf<ConnectionSummary?>(null) }
    var showNewNas by remember { mutableStateOf(connections.isEmpty()) }
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("445") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }
    var share by remember { mutableStateOf("") }

    val current = selected
    BackHandler(enabled = current != null || showNewNas) {
        if (current != null) selected = null else showNewNas = false
    }
    if (current != null) {
        CopyRulesScreen(
            connection = current,
            onBack = { selected = null },
            simpleMode = true,
            onEditorVisibilityChange = onEditorVisibilityChange,
        )
        return
    }

    if (showNewNas) {
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                Text("Create backup", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Enter the NAS connection here. You do not need to register it on another screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Text("NAS connection", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(name, { name = it }, label = { Text("Connection name (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(host, { host = it }, label = { Text("IP address / Hostname") }, placeholder = { Text("192.168.1.100") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(port, { port = it }, label = { Text("Port") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(username, { username = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(password, { password = it }, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(domain, { domain = it }, label = { Text("Domain (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
            item {
                Text("Shared folder", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(share, { share = it }, label = { Text("Share name") }, placeholder = { Text("e.g. backup") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Text(
                    "Enter the SMB share name, not a folder path. The connection will be checked before continuing. You can choose a folder inside the share in the destination setting.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Button(
                    onClick = {
                        viewModel.createBackupConnection(name, host, port, username, password, domain, share) { result ->
                            if (result != null) {
                                selected = result
                                showNewNas = false
                                password = ""
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = host.isNotBlank() && username.isNotBlank() && password.isNotBlank() && share.isNotBlank(),
                ) { Text("Connect and continue") }
                if (connections.isNotEmpty()) {
                    TextButton(onClick = { showNewNas = false }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(horizontal = ScreenPadding)) {
        Spacer(Modifier.height(8.dp))
        Text("Backup settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "Choose an existing NAS or enter a new connection. Existing copy rules and the same copy engine are reused.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(connections, key = { it.connection.id }) { summary ->
                ElevatedCard(
                    onClick = { selected = summary },
                    modifier = Modifier.fillMaxWidth(),
                    shape = SectionShape,
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(summary.connection.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${summary.connection.host}:${summary.connection.port} / ${summary.connection.share}${summary.connection.basePath.takeIf(String::isNotBlank)?.let { " / $it" }.orEmpty()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text("Configure backups →", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            item {
                OutlinedButton(onClick = { showNewNas = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("＋ Add a new NAS connection")
                }
            }
        }
    }
}
