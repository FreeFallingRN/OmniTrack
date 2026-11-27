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

        val pubStatus = HtmlMetadataParser.labeledStatus(html).ifBlank {
            Regex(
                """<(?:a|span|div)[^>]*>\s*(ongoing|completed|hiatus|inactive|stub|dropped)\s*</(?:a|span|div)>""",
                RegexOption.IGNORE_CASE
            ).find(html)?.groupValues?.get(1)?.let(HtmlMetadataParser::publishingStatus).orEmpty()
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