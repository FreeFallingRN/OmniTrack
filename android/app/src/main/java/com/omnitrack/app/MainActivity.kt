package com.omnitrack.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.omnitrack.app.data.MangaBakaPreferences
import com.omnitrack.app.data.MangaBakaSearchProvider
import com.omnitrack.app.data.AniListSearchProvider
import com.omnitrack.app.data.local.AppDatabase
import com.omnitrack.app.data.repository.SeriesRepositoryImpl
import com.omnitrack.app.domain.repository.SeriesRepository
import com.omnitrack.app.ui.home.OmniTrackHomeScreen
import com.omnitrack.app.ui.theme.OmniTrackTheme
import com.omnitrack.app.utils.UpdateCheckResult
import com.omnitrack.app.utils.UpdateChecker
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import com.omnitrack.app.data.repository.ScrapeIssueRepository
import com.omnitrack.app.feature.MediaLookupService
import com.omnitrack.app.ui.FeatureServices
import com.omnitrack.app.ui.LocalFeatureServices
import com.omnitrack.app.ui.viewmodel.SeriesViewModel
import com.omnitrack.app.ui.viewmodel.SettingsStateHolder
import com.omnitrack.app.ui.viewmodel.MangaBakaOwner
import com.omnitrack.app.ui.viewmodel.MoonReaderViewModel

class MainActivity : ComponentActivity() {

    var availableUpdate by mutableStateOf<Triple<String, String, String>?>(null)
    var pendingSharedUrl by mutableStateOf<String?>(null)
    private lateinit var seriesViewModel: SeriesViewModel
    private lateinit var settings: SettingsStateHolder
    private lateinit var mangaBaka: MangaBakaOwner
    private lateinit var moonReader: MoonReaderViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        pendingSharedUrl = extractSharedUrl(intent)

        val database = AppDatabase.getInstance(applicationContext)
        settings = SettingsStateHolder(applicationContext)
        val scrapeIssues = ScrapeIssueRepository(applicationContext, database.scrapeIssueReportDao())
        lifecycleScope.launch { scrapeIssues.enqueuePending() }
        val repository: SeriesRepository = SeriesRepositoryImpl(
            database = database,
            mediaEntryDao = database.mediaEntryDao(),
            progressHistoryDao = database.progressHistoryDao(),
            mangaBakaPreferences = MangaBakaPreferences(applicationContext)
        )

        // Custom ViewModel Factory for supplying the SeriesRepository
        val viewModelFactory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SeriesViewModel(repository, applicationContext) as T
            }
        }
        seriesViewModel = ViewModelProvider(this, viewModelFactory)[SeriesViewModel::class.java]
        val mediaLookup = MediaLookupService(applicationContext)
        val featureFactory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return when (modelClass) {
                    MangaBakaOwner::class.java -> MangaBakaOwner(applicationContext, repository, seriesViewModel.isRefreshing, mediaLookup)
                    MoonReaderViewModel::class.java -> MoonReaderViewModel(applicationContext, repository, seriesViewModel.isRefreshing)
                    else -> throw IllegalArgumentException("Unknown feature ViewModel: $modelClass")
                } as T
            }
        }
        mangaBaka = ViewModelProvider(this, featureFactory)[MangaBakaOwner::class.java]
        moonReader = ViewModelProvider(this, featureFactory)[MoonReaderViewModel::class.java]
        val featureServices = FeatureServices(
            scrapeIssues, mediaLookup, settings, mangaBaka, moonReader,
            listOf(MangaBakaSearchProvider(), AniListSearchProvider())
        )
        handleMangaBakaIntent(intent)
        seriesViewModel.refreshData()
        lifecycleScope.launch {
            moonReader.moonReaderMessages.collect { message ->
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            }
        }

        // Check for updates from GitHub releases on app startup
        lifecycleScope.launch {
            try {
                val pInfo = packageManager.getPackageInfo(packageName, 0)
                val currentVersion = pInfo.versionName ?: "1.0.0"
                val result = UpdateChecker.checkForUpdate(currentVersion)
                availableUpdate = (result as? UpdateCheckResult.Available)
                    ?.let { Triple(it.version, it.title, it.url) }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        setContent {
            OmniTrackTheme {
                val coroutineScope = rememberCoroutineScope()
                var isDownloadingUpdate by remember { mutableStateOf(false) }

                CompositionLocalProvider(LocalFeatureServices provides featureServices) {
                    OmniTrackHomeScreen(
                        viewModel = seriesViewModel,
                        initialSharedUrl = pendingSharedUrl,
                        onSharedUrlConsumed = { pendingSharedUrl = null }
                    )
                }

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
                                        val success = UpdateChecker.downloadAndInstallApk(this@MainActivity, downloadUrl) { progress -> }
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingSharedUrl = extractSharedUrl(intent)
        handleMangaBakaIntent(intent)
    }

    override fun onDestroy() {
        if (::settings.isInitialized) settings.close()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (::seriesViewModel.isInitialized) {
            moonReader.syncMoonReader()
            mangaBaka.syncMangaBakaProgress()
        }
    }

    private fun extractSharedUrl(intent: Intent?): String? {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            return intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                Regex("""https?://[^\s]+""").find(text)?.value ?: text.trim()
            }
        }
        return null
    }

    private fun handleMangaBakaIntent(intent: Intent?) {
        val uri = intent?.data
        if (intent?.action == Intent.ACTION_VIEW && uri?.scheme == "com.omnitrack.app" &&
            uri.path == "/mangabaka/oauth") {
            mangaBaka.handleMangaBakaCallback(uri)
            intent.data = null
        }
    }
}