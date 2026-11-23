package dev.networkstorage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import dev.networkstorage.data.db.ConnectionSummary

@Composable
internal fun BackupSettingsScreen(viewModel: MainViewModel) {
    val connections by viewModel.connections.collectAsState()
    var selected by remember { mutableStateOf<ConnectionSummary?>(null) }

    val current = selected
    if (current != null) {
        CopyRulesScreen(
            connection = current,
            onBack = { selected = null },
            simpleMode = true,
        )
        return
    }

    Column(Modifier.fillMaxSize().padding(horizontal = ScreenPadding)) {
        Spacer(Modifier.height(8.dp))
        Text("Backup settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "Choose the NAS connection for the backup you want to configure. Existing copy rules are reused; source files are never moved or deleted.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        if (connections.isEmpty()) {
            ElevatedCard(Modifier.fillMaxWidth(), shape = SectionShape) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("No NAS connection", fontWeight = FontWeight.SemiBold)
                    Text("Create a network connection first, then return here to configure automatic backup.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(connections, key = { it.connection.id }) { summary ->
                    ElevatedCard(
                        Modifier.fillMaxWidth(),
                        onClick = { selected = summary },
                        shape = SectionShape,
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(summary.connection.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${summary.connection.host} / ${summary.connection.share}${summary.connection.basePath.takeIf(String::isNotBlank)?.let { " / $it" }.orEmpty()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text("Configure backups →", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}
