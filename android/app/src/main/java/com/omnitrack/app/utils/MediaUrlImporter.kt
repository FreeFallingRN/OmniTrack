package com.omnitrack.app.utils

import android.content.Context
import com.omnitrack.app.data.AppLogger
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
    val publishingStatus: String = "",
    val unitName: String = "Chapter",
    val coverUrl: String? = null,
    val sourceUrl: String = "",
    val seriesTitle: String? = null,
    val sequenceNumber: Double? = null,
    val description: String? = null,
    val totalChapters: Double? = null,
    val userStatus: String? = null,
    val currentUnit: Double? = null
)

data class ImportedMediaDraft(
    val sourceUrl: String,
    val normalizedUrl: String,
    val adapterName: String,
    val draft: ScrapedMediaDraft,
    val warning: String? = null
)

object MediaUrlImporter {
    suspend fun importFromUrl(
        context: Context,
        rawUrl: String,
        fallbackTotalChapters: Double? = null
    ): ImportedMediaDraft {
        AppLogger.debug("[MediaUrlImporter] Import started")
        val imported = ExternalMediaScraper.scrape(context, rawUrl)
        val fallbackTotal = fallbackTotalChapters?.takeIf { it.isFinite() && it >= 0 }
        val result = if (imported.draft.totalChapters != null || fallbackTotal == null) imported
        else imported.copy(draft = imported.draft.copy(totalChapters = fallbackTotal))
        AppLogger.info("[MediaUrlImporter] Import completed using ${result.adapterName}")
        return result
    }
}