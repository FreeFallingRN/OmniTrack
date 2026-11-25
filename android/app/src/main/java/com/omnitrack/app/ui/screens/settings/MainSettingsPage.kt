package com.omnitrack.app.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omnitrack.app.ui.components.UpdateSettingsCard
import com.omnitrack.app.utils.LibrarySortOption

@Composable
internal fun MainSettingsPage(
    isIgnoringBatteryOptimizations: Boolean,
    onBatteryOptimizationClick: () -> Unit,
    needsProgressReauth: Boolean,
    progressFolder: String?,
    progressFolderName: String?,
    linkedCount: Int,
    currentSort: LibrarySortOption,
    currentShareStyle: ShareFormatStyle,
    onOpenMangaBaka: () -> Unit,
    onOpenMoonReader: () -> Unit,
    onOpenScrapeReports: () -> Unit,
    onOpenSortOrder: () -> Unit,
    onOpenShareStyle: () -> Unit
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
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Background sync", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        if (isIgnoringBatteryOptimizations) "Background sync active — battery optimization disabled"
                        else "Allow unrestricted background activity so Moon+ Reader and MangaBaka sync reliably",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onBatteryOptimizationClick) {
                    Text(if (isIgnoringBatteryOptimizations) "Manage" else "Allow")
                }
            }
        }

        SettingsNavigationCard("MangaBaka", "Open MangaBaka", onOpenMangaBaka)
        SettingsNavigationCard(
            title = "Moon+ Reader",
            subtitle = if (needsProgressReauth) "Folder access needed"
            else "${if (progressFolder == null) "No progress folder" else progressFolderName ?: "Selected folder"} • " +
                "$linkedCount linked ${if (linkedCount == 1) "book" else "books"}",
            contentDescription = "Open Moon+ Reader settings",
            onClick = onOpenMoonReader
        )
        SettingsNavigationCard("Scrape reports", "Open scrape reports", onOpenScrapeReports)
        SettingsNavigationCard("Library Sort Order", currentSort.label, "Open Sort Order", onOpenSortOrder)
        SettingsNavigationCard("Discord Share Style", currentShareStyle.label, "Open Discord Share Style", onOpenShareStyle)
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
