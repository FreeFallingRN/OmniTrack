package com.omnitrack.app.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.omnitrack.app.data.GitHubRelease
import com.omnitrack.app.data.UpdateChecker
import com.omnitrack.app.data.local.SeriesEntity
import com.omnitrack.app.service.ActionNotificationService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var availableUpdate by mutableStateOf<GitHubRelease?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // Check for updates from GitHub releases on app startup
        lifecycleScope.launch {
            availableUpdate = UpdateChecker.checkForUpdates()
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val seriesList by viewModel.seriesList.collectAsState()

                    SeriesDashboard(
                        seriesList = seriesList,
                        onUpdateProgress = { series, delta -> viewModel.updateProgress(series, delta) },
                        onSetActive = { series ->
                            viewModel.setActiveTarget(series.id)

                            val intent = Intent(this, ActionNotificationService::class.java).apply {
                                putExtra("SERIES_ID", series.id)
                                putExtra("TITLE", series.title)
                                putExtra("CURRENT_UNIT", series.currentCumulativeUnit)
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                startForegroundService(intent)
                            } else {
                                startService(intent)
                            }
                        },
                        onRefresh = { viewModel.refresh() }
                    )

                    // Render Update Available dialog if a newer GitHub release is detected
                    availableUpdate?.let { release ->
                        AlertDialog(
                            onDismissRequest = { availableUpdate = null },
                            title = { Text("Update Available (${release.tagName})") },
                            text = { Text("A new version of OmniTrack is available on GitHub. Would you like to download it?") },
                            confirmButton = {
                                TextButton(onClick = {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(release.htmlUrl))
                                    startActivity(intent)
                                    availableUpdate = null
                                }) {
                                    Text("Download")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { availableUpdate = null }) {
                                    Text("Later")
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SeriesDashboard(
    seriesList: List<SeriesEntity>,
    onUpdateProgress: (SeriesEntity, Double) -> Unit,
    onSetActive: (SeriesEntity) -> Unit,
    onRefresh: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "OmniTrack Library",
                style = MaterialTheme.typography.headlineMedium
            )
            Button(onClick = onRefresh) {
                Text("Sync")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (seriesList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No series found. Tap Sync or share a webnovel link!")
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(seriesList, key = { it.id }) { series ->
                    SeriesItemCard(
                        series = series,
                        onDecrement = { onUpdateProgress(series, -1.0) },
                        onIncrement = { onUpdateProgress(series, 1.0) },
                        onSetActive = { onSetActive(series) }
                    )
                }
            }
        }
    }
}

@Composable
fun SeriesItemCard(
    series: SeriesEntity,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    onSetActive: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (series.isActiveTarget)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = series.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${series.unitName}: ${series.currentCumulativeUnit.toInt()}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = onSetActive) {
                    Text(if (series.isActiveTarget) "Pinned" else "Pin")
                }
                OutlinedButton(onClick = onDecrement) {
                    Text("-1")
                }
                Button(onClick = onIncrement) {
                    Text("+1")
                }
            }
        }
    }
}