package com.omnitrack.app.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.omnitrack.app.BuildConfig
import com.omnitrack.app.data.local.AppDatabase
import com.omnitrack.app.data.repository.SeriesRepositoryImpl
import com.omnitrack.app.domain.model.Series
import com.omnitrack.app.domain.repository.SeriesRepository
import com.omnitrack.app.service.ActionNotificationService
import com.omnitrack.app.ui.screens.OmniTrackHomeScreen
import com.omnitrack.app.ui.theme.OmniTrackTheme
import com.omnitrack.app.utils.GitHubRelease
import com.omnitrack.app.utils.UpdateChecker
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember

class MainActivity : ComponentActivity() {

    var availableUpdate by mutableStateOf<Triple<String, String, String>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 1. Initialize Ktor HTTP Client with Content Negotiation
        val httpClient = HttpClient(Android) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
        }

        // 2. Initialize Room Database & Series Repository
        val database = AppDatabase.getInstance(applicationContext)
        val repository: SeriesRepository = SeriesRepositoryImpl(
            seriesDao = database.seriesDao(),
            httpClient = httpClient,
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY
        )

        // 3. Trigger initial background sync from Supabase to local Room cache
        lifecycleScope.launch {
            try {
                repository.fetchFromSupabase()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 4. Custom ViewModel Factory for supplying the SeriesRepository
        val viewModelFactory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SeriesViewModel(repository) as T
            }
        }

        // Notification permission request for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // Check for updates from GitHub releases on app startup
        lifecycleScope.launch {
            try {
                val pInfo = packageManager.getPackageInfo(packageName, 0)
                val currentVersion = pInfo.versionName ?: "1.0.0"

                // Pass current version to checkForUpdate
                availableUpdate = UpdateChecker.checkForUpdate(currentVersion)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        setContent {
            OmniTrackTheme {
                val seriesViewModel: SeriesViewModel = viewModel(factory = viewModelFactory)
                val coroutineScope = rememberCoroutineScope()
                var isDownloadingUpdate by remember { mutableStateOf(false) }

                OmniTrackHomeScreen(
                    viewModel = seriesViewModel,
                    onTogglePinNotification = { series ->
                        updatePinnedNotification(series)
                    }
                )

                // Render Update Available dialog if a newer GitHub release is detected
                availableUpdate?.let { (version, releaseName, downloadUrl) ->
                    AlertDialog(
                        onDismissRequest = { if (!isDownloadingUpdate) availableUpdate = null },
                        title = { Text("Update Available (v$version)") },
                        text = {
                            Text(if (isDownloadingUpdate) "Downloading update..." else "A new version of OmniTrack ($releaseName) is available. Would you like to download and install it?")
                        },
                        confirmButton = {
                            if (!isDownloadingUpdate) {
                                TextButton(onClick = {
                                    isDownloadingUpdate = true
                                    coroutineScope.launch {
                                        val success = UpdateChecker.downloadAndInstallApk(this@MainActivity, downloadUrl) { progress ->
                                            // Optional: track progress if you want a progress bar in the dialog
                                        }
                                        isDownloadingUpdate = false
                                        if (success) {
                                            availableUpdate = null
                                        }
                                    }
                                }) {
                                    Text("Download & Install")
                                }
                            }
                        },
                        dismissButton = {
                            if (!isDownloadingUpdate) {
                                TextButton(onClick = { availableUpdate = null }) {
                                    Text("Later")
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    private fun updatePinnedNotification(series: Series) {
        val intent = Intent(this, ActionNotificationService::class.java).apply {
            putExtra("SERIES_ID", series.id)
            putExtra("TITLE", series.title)
            putExtra("CURRENT_UNIT", series.currentCumulativeUnit)
        }
        startForegroundService(intent)
    }
}