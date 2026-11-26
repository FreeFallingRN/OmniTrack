package com.omnitrack.app.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.omnitrack.app.data.repository.ScrapeIssueRepository
import com.omnitrack.app.data.OnlineMediaSearchProvider
import com.omnitrack.app.feature.MediaLookupService
import com.omnitrack.app.ui.viewmodel.SettingsStateHolder
import com.omnitrack.app.ui.viewmodel.MangaBakaOwner
import com.omnitrack.app.ui.viewmodel.MoonReaderViewModel

data class FeatureServices(
    val scrapeIssues: ScrapeIssueRepository,
    val mediaLookup: MediaLookupService,
    val settings: SettingsStateHolder,
    val mangaBaka: MangaBakaOwner,
    val moonReader: MoonReaderViewModel,
    val onlineSearchProviders: List<OnlineMediaSearchProvider>
)

val LocalFeatureServices = staticCompositionLocalOf<FeatureServices> {
    error("Feature services not provided")
}
