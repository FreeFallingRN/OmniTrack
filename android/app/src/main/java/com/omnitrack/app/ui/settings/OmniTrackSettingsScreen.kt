package com.omnitrack.app.ui.settings

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.omnitrack.app.BuildConfig
import com.omnitrack.app.ui.LocalFeatureServices
import com.omnitrack.app.ui.mangabaka.MangaBakaScreen
import com.omnitrack.app.ui.anilist.AniListScreen
import com.omnitrack.app.ui.moonreader.MoonReaderSettingsScreen
import com.omnitrack.app.ui.moonreader.folderDisplayName
import com.omnitrack.app.ui.scrapeissues.ScrapeIssueReportsScreen
import com.omnitrack.app.ui.viewmodel.SeriesViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.core.net.toUri

enum class SettingsPage {
    MAIN,
    SHARE_STYLE,
    SCRAPE_REPORTS,
    MOON_READER,
    MANGABAKA,
    ANILIST
}

enum class ShareFormatStyle(val label: String, val description: String) {
    RICH_MARKDOWN("Rich Markdown", "Detailed multi-line Discord Markdown"),
    COMPACT_CARD("Compact Card", "Short 2-line recommendation card");

    companion object {
        fun fromKey(key: String?): ShareFormatStyle =
            entries.find { it.name == key } ?: RICH_MARKDOWN
    }
}

@Composable
fun OmniTrackSettingsScreen(viewModel: SeriesViewModel) {
    val context = LocalContext.current
    val settings = LocalFeatureServices.current.settings
    val moonReader = LocalFeatureServices.current.moonReader
    val settingsState by settings.state.collectAsState()
    val lokiActive by settings.lokiActive.collectAsState()
    val lokiStatus by settings.lokiStatus.collectAsState()
    val lokiTokenStatus by settings.lokiTokenStatus.collectAsState(initial = null)
    val entries by viewModel.mediaEntries.collectAsState()
    val needsProgressReauth by moonReader.needsProgressFolderReauth.collectAsState()
    val scope = rememberCoroutineScope()

    var currentRoute by remember { mutableStateOf(SettingsPage.MAIN) }
    var showSyncDiagnostics by remember { mutableStateOf(false) }
    var showLokiConfig by remember { mutableStateOf(false) }
    var syncDiagnostics by remember { mutableStateOf("[]") }
    BackHandler(enabled = currentRoute != SettingsPage.MAIN) {
        currentRoute = SettingsPage.MAIN
    }
    val progressFolder = settingsState.progressFolder
    var resolvedFolderName by remember(progressFolder) { mutableStateOf<String?>(null) }
    val progressFolderName = settingsState.progressFolderName ?: resolvedFolderName
    val linkedCount = remember(entries, settingsState.revision) {
        entries.count { settings.linkedFileUri(it.id) != null }
    }

    LaunchedEffect(progressFolder, currentRoute) {
        if (currentRoute == SettingsPage.MAIN) {
            progressFolder?.let { moonReader.checkMoonReaderFolder(it, books = false) }
        }
    }
    LaunchedEffect(progressFolder) {
        val savedFolder = progressFolder
        if (savedFolder != null && progressFolderName == null) {
            try {
                val name = folderDisplayName(context, savedFolder.toUri())
                resolvedFolderName = name
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("MoonReaderSync", "Could not resolve progress folder name", e)
            }
        }
    }
    DisposableEffect(context, progressFolder, currentRoute) {
        val lifecycle = (context as? LifecycleOwner)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && currentRoute == SettingsPage.MAIN) {
                scope.launch {
                    progressFolder?.let { moonReader.checkMoonReaderFolder(it, books = false) }
                }
            }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

    var isIgnoringBatteryOptimizations by remember {
        mutableStateOf(
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .isIgnoringBatteryOptimizations(context.packageName)
        )
    }
    DisposableEffect(context) {
        val lifecycle = (context as? LifecycleOwner)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isIgnoringBatteryOptimizations =
                    (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                        .isIgnoringBatteryOptimizations(context.packageName)
            }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    val currentShareStyle = ShareFormatStyle.fromKey(settingsState.shareStyle)

    AnimatedContent(
        targetState = currentRoute,
        transitionSpec = {
            if (targetState != SettingsPage.MAIN) {
                (slideInHorizontally { width -> width } + fadeIn()) togetherWith
                    (slideOutHorizontally { width -> -width } + fadeOut())
            } else {
                (slideInHorizontally { width -> -width } + fadeIn()) togetherWith
                    (slideOutHorizontally { width -> width } + fadeOut())
            }
        },
        label = "settings page slide transition",
        modifier = Modifier.fillMaxSize()
    ) { route ->
        when (route) {
            SettingsPage.MAIN -> MainSettingsPage(
                isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizations,
                onBatteryOptimizationClick = {
                    val settingsIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    if (isIgnoringBatteryOptimizations) {
                        runCatching { context.startActivity(settingsIntent) }
                    } else {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        }.onFailure { runCatching { context.startActivity(settingsIntent) } }
                    }
                },
                needsProgressReauth = needsProgressReauth,
                progressFolder = progressFolder,
                progressFolderName = progressFolderName,
                linkedCount = linkedCount,
                currentShareStyle = currentShareStyle,
                releaseChannel = settingsState.releaseChannel,
                liveLoggingActive = settingsState.liveLoggingActive,
                lokiActive = lokiActive,
                lokiStatus = lokiStatus,
                lokiTokenStatus = lokiTokenStatus?.let {
                    it.error ?: if (it.configured) "Token configured" else "Token not configured"
                } ?: "Checking token...",
                onReleaseChannelChange = settings::setReleaseChannel,
                onLiveLoggingChange = settings::setLiveLoggingActive,
                onLokiClick = {
                    if (lokiActive) {
                        settings.stopLokiLogging()
                    } else {
                        scope.launch {
                            try {
                                settings.startLokiLogging()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Toast.makeText(context, e.message ?: "Could not start Loki upload", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                },
                onLokiConfigure = { showLokiConfig = true },
                onLokiTest = {
                    try {
                        settings.sendLokiTestLog()
                        Toast.makeText(context, "Test log queued", Toast.LENGTH_SHORT).show()
                    } catch (e: IllegalStateException) {
                        Toast.makeText(context, e.message ?: "Could not send test log", Toast.LENGTH_LONG).show()
                    }
                },
                onOpenMangaBaka = { currentRoute = SettingsPage.MANGABAKA },
                onOpenAniList = { currentRoute = SettingsPage.ANILIST },
                onOpenMoonReader = { currentRoute = SettingsPage.MOON_READER },
                onOpenScrapeReports = { currentRoute = SettingsPage.SCRAPE_REPORTS },
                onOpenShareStyle = { currentRoute = SettingsPage.SHARE_STYLE },
                onOpenSyncDiagnostics = {
                    syncDiagnostics = settings.recentSyncDiagnostics()
                    showSyncDiagnostics = true
                }
            )

            SettingsPage.SCRAPE_REPORTS -> ScrapeIssueReportsScreen {
                currentRoute = SettingsPage.MAIN
            }
            SettingsPage.MOON_READER -> MoonReaderSettingsScreen(viewModel) {
                currentRoute = SettingsPage.MAIN
            }
            SettingsPage.MANGABAKA -> MangaBakaScreen(viewModel) {
                currentRoute = SettingsPage.MAIN
            }
            SettingsPage.ANILIST -> AniListScreen(viewModel) {
                currentRoute = SettingsPage.MAIN
            }
            SettingsPage.SHARE_STYLE -> ShareFormatSettingsPage(
                currentStyle = currentShareStyle,
                onSelect = { style ->
                    settings.setShareStyle(style.name)
                },
                onBack = { currentRoute = SettingsPage.MAIN }
            )
        }
    }
    if (showLokiConfig) {
        var url by remember { mutableStateOf(BuildConfig.GRAFANA_LOKI_URL) }
        var user by remember { mutableStateOf(BuildConfig.GRAFANA_LOKI_USER) }
        var token by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showLokiConfig = false },
            title = { Text("Grafana Loki upload") },
            text = {
                Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    Text("Logs may contain sensitive information. Leave the token blank to keep the current token.")
                    OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("HTTPS push URL") }, singleLine = true)
                    OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("User") }, singleLine = true)
                    OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("Token override") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    TextButton(onClick = {
                        scope.launch {
                            try {
                                settings.setLokiTokenOverride(token.trim())
                                token = ""
                                error = null
                                Toast.makeText(context, "Token override saved", Toast.LENGTH_SHORT).show()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = e.message ?: "Could not save the token override"
                            }
                        }
                    }, enabled = token.isNotBlank()) { Text("Save token override") }
                    if (lokiTokenStatus?.hasOverride == true) {
                        TextButton(onClick = {
                            scope.launch {
                                try {
                                    settings.setLokiTokenOverride(null)
                                    token = ""
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    error = e.message ?: "Could not clear the token override"
                                }
                            }
                        }) { Text("Clear token override") }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            if (token.isNotBlank()) settings.setLokiTokenOverride(token.trim())
                            settings.startLokiLogging(url, user)
                            token = ""
                            showLokiConfig = false
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.message ?: "Could not start Loki upload"
                        }
                    }
                }) { Text("Start") }
            },
            dismissButton = { TextButton(onClick = { showLokiConfig = false }) { Text("Cancel") } }
        )
    }
    if (showSyncDiagnostics) {
        AlertDialog(
            onDismissRequest = { showSyncDiagnostics = false },
            title = { Text("Recent sync diagnostics") },
            text = {
                Column(
                    Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(end = 8.dp)
                ) {
                    Text(syncDiagnostics, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Sync diagnostics", syncDiagnostics))
                    Toast.makeText(context, "Sync diagnostics copied", Toast.LENGTH_SHORT).show()
                }) { Text("Copy") }
            },
            dismissButton = {
                TextButton(onClick = { showSyncDiagnostics = false }) { Text("Close") }
            }
        )
    }
}
