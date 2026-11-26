package com.omnitrack.app.feature

import android.content.Context
import com.omnitrack.app.domain.model.MediaType
import com.omnitrack.app.utils.scraper.ExternalMediaScraper
import com.omnitrack.app.utils.MediaUrlImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaLookupService(context: Context) {
    private val appContext = context.applicationContext

    suspend fun scrape(url: String, type: MediaType? = null, requireFetchedMetadata: Boolean = false) =
        withContext(Dispatchers.IO) {
            ExternalMediaScraper.scrape(appContext, url, type, requireFetchedMetadata)
        }

    suspend fun findCover(url: String, useWebView: Boolean = true): String? =
        ExternalMediaScraper.findCoverImage(url, if (useWebView) appContext else null)

    suspend fun importFromUrl(url: String, fallbackTotalChapters: Double? = null) =
        MediaUrlImporter.importFromUrl(appContext, url, fallbackTotalChapters)
}
