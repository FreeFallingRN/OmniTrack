package com.omnitrack.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omnitrack.app.domain.model.ReleaseChannel

@Composable
internal fun DeveloperLoggingSettingsPage(
    releaseChannel: ReleaseChannel,
    liveLoggingActive: Boolean,
    lokiActive: Boolean,
    lokiStatus: String,
    lokiTestStatus: String?,
    lokiTestRunning: Boolean,
    lokiTokenStatus: String,
    onReleaseChannelChange: (ReleaseChannel) -> Unit,
    onLiveLoggingChange: (Boolean) -> Unit,
    onLokiClick: () -> Unit,
    onLokiConfigure: () -> Unit,
    onLokiTest: () -> Unit,
    onOpenAppLogs: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SettingsPageHeader("Developer & Logging", onBack)
        SettingsGroupCard {
            SettingsToggleRow(
                title = "Beta mode",
                subtitle = "Enable beta-only features; this does not change the installed APK or update channel.",
                checked = releaseChannel == ReleaseChannel.BETA,
                enabled = true,
                onCheckedChange = { onReleaseChannelChange(if (it) ReleaseChannel.BETA else ReleaseChannel.STABLE) }
            )
            SettingsToggleRow(
                title = "Live logging",
                subtitle = "Write app logs to Logcat and allow Loki uploads (beta only).",
                checked = liveLoggingActive,
                enabled = releaseChannel == ReleaseChannel.BETA,
                onCheckedChange = onLiveLoggingChange
            )
        }
        if (releaseChannel == ReleaseChannel.BETA) {
            SettingsGroupCard {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Loki upload", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(lokiStatus, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Token: $lokiTokenStatus", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onLokiClick, enabled = liveLoggingActive || lokiActive) {
                            Text(if (lokiActive) "Stop upload" else "Start upload")
                        }
                        TextButton(onClick = onLokiConfigure, enabled = !lokiActive) { Text("Configure") }
                        TextButton(onClick = onLokiTest, enabled = lokiActive && !lokiTestRunning) { Text("Send test log") }
                    }
                    lokiTestStatus?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
            SettingsGroupCard {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("App logs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Logs may contain sensitive information.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row {
                        TextButton(onClick = { onOpenAppLogs(false) }) { Text("Recent logs") }
                        TextButton(onClick = { onOpenAppLogs(true) }) { Text("Saved fatal log") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsGroupCard(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column { content() }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}
