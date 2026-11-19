package com.omnitrack.app.utils.scraper.adapters

import com.omnitrack.app.domain.model.MediaType
import com.omnitrack.app.utils.ScrapedCreator
import com.omnitrack.app.utils.ScrapedMediaDraft
import com.omnitrack.app.utils.scraper.HtmlMetadataParser
import com.omnitrack.app.utils.scraper.MediaSourceAdapter

object RoyalRoadAdapter : MediaSourceAdapter {
    override val name: String = "RoyalRoad"

    override fun canHandle(host: String, url: String): Boolean =
        host.contains("royalroad.com", ignoreCase = true)

    override suspend fun scrapeFromHtml(url: String, html: String): ScrapedMediaDraft {
        val generic = HtmlMetadataParser.extractGenericMetadata(html, url)
        val title = generic.title
            .substringBefore(" | Royal Road")
            .trim()
            .ifBlank { HtmlMetadataParser.extractTitleFromSlug(url) }

        val author = Regex("""Author card for ([^<]+)</div>""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.trim()
            ?: Regex("""<a[^>]*href="/profile/\d+"[^>]*>\s*<h4[^>]*>([^<]+)</h4>""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1)?.trim()
            ?: Regex("""<meta[^>]+name=["']author["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1)?.trim()
            ?: Regex(""""author"\s*:\s*\{[^}]*"name"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1)?.trim()

        val pubStatus = when {
            html.contains(">COMPLETED<", ignoreCase = true) -> "completed"
            html.contains(">HIATUS<", ignoreCase = true) -> "hiatus"
            html.contains(">STUB<", ignoreCase = true) || html.contains(">DROPPED<", ignoreCase = true) -> "discontinued"
            else -> "releasing"
        }

        val creators = author
            ?.let(HtmlMetadataParser::decodeHtmlEntities)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { listOf(ScrapedCreator(it, "author")) }
            ?: generic.creators

        return generic.copy(
            title = title,
            type = MediaType.NOVEL,
            creators = creators,
            platformHint = "Royal Road",
            publishingStatus = pubStatus,
            unitName = "Chapter",
            sourceUrl = url
        )
    }
}