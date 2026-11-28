package com.omnitrack.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omnitrack.app.domain.model.ReleaseChannel

@Composable
internal fun MainSettingsPage(
    isIgnoringBatteryOptimizations: Boolean,
    onBatteryOptimizationClick: () -> Unit,
    currentShareStyle: ShareFormatStyle,
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
    onOpenMangaBaka: () -> Unit,
    onOpenAniList: () -> Unit,
    onOpenMoonReader: () -> Unit,
    onOpenScrapeReports: () -> Unit,
    onOpenShareStyle: () -> Unit,
    onOpenSyncDiagnostics: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        UpdateSettingsCard()

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Beta channel", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Enable beta features", style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(
                        checked = releaseChannel == ReleaseChannel.BETA,
                        onCheckedChange = { onReleaseChannelChange(if (it) ReleaseChannel.BETA else ReleaseChannel.STABLE) }
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Live logging", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Show app logs in Logcat (beta only)", style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(
                        checked = liveLoggingActive,
                        enabled = releaseChannel == ReleaseChannel.BETA,
                        onCheckedChange = onLiveLoggingChange
                    )
                }
                if (releaseChannel == ReleaseChannel.BETA) {
                    androidx.compose.material3.Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(lokiTokenStatus, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium)
                    }
                    Text(lokiStatus, style = MaterialTheme.typography.bodyMedium)
                    Row {
                        TextButton(onClick = onLokiClick, enabled = liveLoggingActive || lokiActive) {
                            Text(if (lokiActive) "Stop Loki upload" else "Start Loki upload")
                        }
                        TextButton(onClick = onLokiConfigure, enabled = !lokiActive) { Text("Configure") }
                    }
                    TextButton(onClick = onLokiTest, enabled = lokiActive && !lokiTestRunning) { Text("Send test log") }
                    lokiTestStatus?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    Row {
                        TextButton(onClick = { onOpenAppLogs(false) }) { Text("Recent logs") }
                        TextButton(onClick = { onOpenAppLogs(true) }) { Text("Saved fatal log") }
                    }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Background sync", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        if (isIgnoringBatteryOptimizations) "Background sync active — battery optimization disabled"
                        else "Allow unrestricted background activity so Moon+ Reader, MangaBaka and AniList sync reliably",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onBatteryOptimizationClick) {
                    Text(if (isIgnoringBatteryOptimizations) "Manage" else "Allow")
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Tracking", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                SettingsNavigationRow("MangaBaka", Icons.Default.AutoStories, "Open MangaBaka", onOpenMangaBaka)
                SettingsNavigationRow("AniList", AniListIcon, "Open AniList", onOpenAniList)
                SettingsNavigationRow("Moon+ Reader", Icons.Default.Nightlight, "Open Moon+ Reader settings", onOpenMoonReader)
            }
        }
        SettingsNavigationCard("Scrape reports", "Open scrape reports", onOpenScrapeReports)
        SettingsNavigationCard("Discord Share Style", currentShareStyle.label, "Open Discord Share Style", onOpenShareStyle)
        SettingsNavigationCard("Sync diagnostics", "View or copy recent sync results", onOpenSyncDiagnostics)
    }
}

private val AniListIcon = ImageVector.Builder(
    name = "AniList", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(2f, 19f)
        lineTo(8f, 5f)
        lineTo(11f, 5f)
        lineTo(17f, 19f)
        lineTo(13.7f, 19f)
        lineTo(12.5f, 16f)
        lineTo(6.5f, 16f)
        lineTo(5.3f, 19f)
        close()
        moveTo(7.5f, 13.5f)
        lineTo(11.5f, 13.5f)
        lineTo(9.5f, 8.5f)
        close()
        moveTo(17f, 5f)
        lineTo(20f, 5f)
        lineTo(20f, 16.5f)
        lineTo(23f, 16.5f)
        lineTo(23f, 19f)
        lineTo(17f, 19f)
        close()
    }
}.build()

@Composable
private fun SettingsNavigationRow(
    title: String,
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Icon(Icons.Default.ChevronRight, contentDescription = contentDescription, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsNavigationCard(
    title: String,
    subtitle: String,
    contentDescription: String = title,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (subtitle != title) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.Default.ChevronRight, contentDescription = contentDescription, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsNavigationCard(
    title: String,
    contentDescription: String,
    onClick: () -> Unit
) = SettingsNavigationCard(title, title, contentDescription, onClick)
