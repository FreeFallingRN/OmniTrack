package com.omnitrack.app.ui.components

import android.content.Context
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omnitrack.app.utils.UpdateCheckResult
import com.omnitrack.app.utils.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun UpdateSettingsCard() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var currentVersion by remember { mutableStateOf("1.0.0") }
    var updateStatusText by remember { mutableStateOf("You are on the latest version") }
    var availableUpdateInfo by remember { mutableStateOf<Triple<String, String, String>?>(null) } // (version, title, url)
    var isChecking by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var checkFailed by remember { mutableStateOf(false) }

    fun runCheck() {
        isChecking = true
        checkFailed = false
        updateStatusText = "Checking for updates..."
        coroutineScope.launch {
            when (val result = withContext(Dispatchers.IO) {
                UpdateChecker.checkForUpdate(currentVersion)
            }) {
                is UpdateCheckResult.Available -> {
                    availableUpdateInfo = Triple(result.version, result.title, result.url)
                    updateStatusText = "New version v${result.version} available!"
                }
                UpdateCheckResult.UpToDate -> updateStatusText = "You are up to date."
                is UpdateCheckResult.Error -> {
                    checkFailed = true
                    updateStatusText = "Update check failed: ${result.message}"
                }
            }
            isChecking = false
        }
    }

    // Fetch app's actual version name on launch
    LaunchedEffect(Unit) {
        try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            currentVersion = pInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "App Updates",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Current version: v$currentVersion",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = { runCheck() },
                    enabled = !isChecking && !isDownloading
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Check for Updates",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                text = updateStatusText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (checkFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (checkFailed) {
                TextButton(onClick = { runCheck() }, enabled = !isChecking) {
                    Text("Retry")
                }
            }

            if (isDownloading) {
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Downloading update... ${(downloadProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Show download & install button if a release update is found
            availableUpdateInfo?.let { (version, releaseName, url) ->
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = {
                        isDownloading = true
                        coroutineScope.launch {
                            val success = withContext(Dispatchers.IO) {
                                UpdateChecker.downloadAndInstallApk(context, url) { progress ->
                                    downloadProgress = progress
                                }
                            }
                            isDownloading = false
                            if (!success) {
                                updateStatusText = "Download failed. Check connection."
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Text(
                        text = "Download & Install v$version",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}