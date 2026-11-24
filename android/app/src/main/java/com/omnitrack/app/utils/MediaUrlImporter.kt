package com.omnitrack.app.utils

import android.content.Context
import com.omnitrack.app.domain.model.MediaType
import com.omnitrack.app.utils.scraper.ExternalMediaScraper
import kotlinx.serialization.Serializable

@Serializable
data class ScrapedCreator(
    val name: String,
    val role: String = "author"
)

@Serializable
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

data class ImportedMediaDraft(
    val sourceUrl: String,
    val normalizedUrl: String,
    val adapterName: String,
    val draft: ScrapedMediaDraft
)

object MediaUrlImporter {
    suspend fun importFromUrl(context: Context, rawUrl: String): ImportedMediaDraft {
        return ExternalMediaScraper.scrape(context, rawUrl)
    }
}