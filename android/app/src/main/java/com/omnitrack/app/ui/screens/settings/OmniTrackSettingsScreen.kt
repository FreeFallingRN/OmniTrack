package com.omnitrack.app.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.omnitrack.app.data.OmniTrackPreferenceKeys
import com.omnitrack.app.data.OmniTrackPreferences
import com.omnitrack.app.data.PreferenceChange
import com.omnitrack.app.ui.viewmodel.SeriesViewModel
import com.omnitrack.app.utils.LibrarySortOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

enum class SettingsPage {
    MAIN,
    SORT_ORDER,
    SHARE_STYLE,
    SCRAPE_REPORTS,
    MOON_READER,
    MANGABAKA
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
    val preferences = remember { OmniTrackPreferences(context) }
    val entries by viewModel.mediaEntries.collectAsState()
    val needsProgressReauth by viewModel.needsProgressFolderReauth.collectAsState()
    val scope = rememberCoroutineScope()

    var currentRoute by remember { mutableStateOf(SettingsPage.MAIN) }
    var progressFolder by remember {
        mutableStateOf(preferences.getString(OmniTrackPreferenceKeys.MoonReaderFolder))
    }
    var progressFolderName by remember {
        mutableStateOf(preferences.getString(OmniTrackPreferenceKeys.MoonReaderFolderName))
    }
    var linksVersion by remember { mutableStateOf(0) }
    val linkedCount = remember(entries, linksVersion) {
        entries.count { preferences.getString(OmniTrackPreferenceKeys.moonReaderFileUri(it.id)) != null }
    }

    DisposableEffect(preferences) {
        val listener = preferences.addListener { change ->
            if (change == PreferenceChange.ALL || change == PreferenceChange.MOON_READER_FOLDER) {
                progressFolder = preferences.getString(OmniTrackPreferenceKeys.MoonReaderFolder)
            }
            if (change == PreferenceChange.ALL || change == PreferenceChange.MOON_READER_FOLDER_NAME) {
                progressFolderName = preferences.getString(OmniTrackPreferenceKeys.MoonReaderFolderName)
            }
            if (change == PreferenceChange.ALL || change == PreferenceChange.MOON_READER_FILE_URI) linksVersion++
        }
        onDispose { preferences.removeListener(listener) }
    }

    LaunchedEffect(progressFolder, currentRoute) {
        if (currentRoute == SettingsPage.MAIN) {
            progressFolder?.let { viewModel.checkMoonReaderFolder(it, books = false) }
        }
    }
    LaunchedEffect(progressFolder) {
        val savedFolder = progressFolder
        if (savedFolder != null && progressFolderName == null) {
            try {
                val name = folderDisplayName(context, Uri.parse(savedFolder))
                if (progressFolder == savedFolder && progressFolderName == null) progressFolderName = name
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
                    progressFolder?.let { viewModel.checkMoonReaderFolder(it, books = false) }
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
    var currentSort by remember {
        mutableStateOf(
            LibrarySortOption.fromKey(preferences.getString(OmniTrackPreferenceKeys.SortOption, "last_updated"))
        )
    }
    var currentShareStyle by remember {
        mutableStateOf(
            ShareFormatStyle.fromKey(preferences.getString(OmniTrackPreferenceKeys.ShareFormatStyle))
        )
    }

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
                currentSort = currentSort,
                currentShareStyle = currentShareStyle,
                onOpenMangaBaka = { currentRoute = SettingsPage.MANGABAKA },
                onOpenMoonReader = { currentRoute = SettingsPage.MOON_READER },
                onOpenScrapeReports = { currentRoute = SettingsPage.SCRAPE_REPORTS },
                onOpenSortOrder = { currentRoute = SettingsPage.SORT_ORDER },
                onOpenShareStyle = { currentRoute = SettingsPage.SHARE_STYLE }
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
            SettingsPage.SHARE_STYLE -> ShareFormatSettingsPage(
                currentStyle = currentShareStyle,
                onSelect = { style ->
                    currentShareStyle = style
                    preferences.edit()
                        .putString(OmniTrackPreferenceKeys.ShareFormatStyle, style.name)
                        .apply()
                },
                onBack = { currentRoute = SettingsPage.MAIN }
            )
            SettingsPage.SORT_ORDER -> LibrarySortOrderSettingsPage(
                currentSort = currentSort,
                onSelect = { option ->
                    currentSort = option
                    preferences.edit().putString(OmniTrackPreferenceKeys.SortOption, option.key).apply()
                },
                onBack = { currentRoute = SettingsPage.MAIN }
            )
        }
    }
}
