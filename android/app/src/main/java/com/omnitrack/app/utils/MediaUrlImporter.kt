package com.omnitrack.app.utils

import android.content.Context
import com.omnitrack.app.domain.model.MediaType
import com.omnitrack.app.utils.scraper.ExternalMediaScraper

data class ScrapedCreator(
    val name: String,
    val role: String = "author"
)

data class ScrapedMediaDraft(
    val title: String = "",
    val alternativeTitles: List<String> = emptyList(),
    val type: MediaType = MediaType.NOVEL,
    val creators: List<ScrapedCreator> = emptyList(),
    val platformHint: String? = null,
    val publishingStatus: String = "releasing",
    val unitName: String = "Chapter",
    val coverUrl: String? = null,
    val sourceUrl: String = "",
    val seriesTitle: String? = null,
    val sequenceNumber: Double? = null
)

object MediaUrlImporter {
    suspend fun importFromUrl(context: Context, rawUrl: String): ScrapedMediaDraft {
        return ExternalMediaScraper.scrape(context, rawUrl)
    }
}