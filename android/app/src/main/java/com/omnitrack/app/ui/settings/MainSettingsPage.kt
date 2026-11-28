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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun MainSettingsPage(
    currentShareStyle: ShareFormatStyle,
    isIgnoringBatteryOptimizations: Boolean,
    onOpenBackgroundSync: () -> Unit,
    onOpenMangaBaka: () -> Unit,
    onOpenAniList: () -> Unit,
    onOpenMoonReader: () -> Unit,
    onOpenShareStyle: () -> Unit,
    onOpenAppUpdates: () -> Unit,
    onOpenDeveloperLogging: () -> Unit,
    onOpenScrapeReports: () -> Unit,
    onOpenSyncDiagnostics: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SettingsGroup("Sync & Tracking") {
            SettingsNavigationRow(
                "Background sync",
                Icons.Default.BatteryChargingFull,
                if (isIgnoringBatteryOptimizations) "Battery optimization is disabled" else "Allow reliable background sync",
                onOpenBackgroundSync
            )
            SettingsNavigationRow("MangaBaka", Icons.Default.AutoStories, "Manage comic progress sync", onOpenMangaBaka)
            SettingsNavigationRow("AniList", AniListIcon, "Manage AniList tracking", onOpenAniList)
            SettingsNavigationRow("Moon+ Reader", Icons.Default.Nightlight, "Manage Moon+ Reader sync", onOpenMoonReader)
        }

        SettingsGroup("Sharing & Appearance") {
            SettingsNavigationRow("Discord Share Style", Icons.Default.Palette, currentShareStyle.label, onOpenShareStyle)
        }

        SettingsGroup("Codebase") {
            SettingsNavigationRow("App Updates", Icons.Default.SystemUpdate, "Check for the latest app release", onOpenAppUpdates)
            SettingsNavigationRow("Developer & Logging", Icons.Default.Code, "Beta mode, live logging, and Loki", onOpenDeveloperLogging)
        }

        SettingsGroup("Diagnostics") {
            SettingsNavigationRow("Scrape reports", Icons.Default.BugReport, "Review recent scraper issues", onOpenScrapeReports)
            SettingsNavigationRow("Sync diagnostics", Icons.Default.Description, "View or copy recent sync results", onOpenSyncDiagnostics)
        }
    }
}

@Composable
internal fun BackgroundSyncSettingsPage(
    isIgnoringBatteryOptimizations: Boolean,
    onBatteryOptimizationClick: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SettingsPageHeader("Background sync", onBack)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.Sync, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column {
                        Text(
                            if (isIgnoringBatteryOptimizations) "Background sync active" else "Battery optimization is enabled",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Unrestricted background activity helps Moon+ Reader, MangaBaka, and AniList sync reliably.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Button(onClick = onBatteryOptimizationClick) {
                    Text(if (isIgnoringBatteryOptimizations) "Manage battery settings" else "Allow background activity")
                }
            }
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column { content() }
        }
    }
}

@Composable
private fun SettingsNavigationRow(
    title: String,
    icon: ImageVector,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = "Open $title", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SettingsPageHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurface)
        }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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
