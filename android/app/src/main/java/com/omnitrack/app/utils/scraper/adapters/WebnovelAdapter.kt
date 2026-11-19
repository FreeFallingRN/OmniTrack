package com.omnitrack.app.utils.scraper.adapters

import com.omnitrack.app.domain.model.MediaType
import com.omnitrack.app.utils.ScrapedCreator
import com.omnitrack.app.utils.ScrapedMediaDraft
import com.omnitrack.app.utils.scraper.CoverNormalizer
import com.omnitrack.app.utils.scraper.HtmlMetadataParser
import com.omnitrack.app.utils.scraper.MediaSourceAdapter

object WebnovelAdapter : MediaSourceAdapter {
    override val name: String = "Webnovel"

    override fun canHandle(host: String, url: String): Boolean =
        host.contains("webnovel.com", ignoreCase = true)

    override suspend fun scrapeFromHtml(url: String, html: String): ScrapedMediaDraft {
        val generic = HtmlMetadataParser.extractGenericMetadata(html, url)
        val cleanTitle = generic.title
            .substringBefore(" - Webnovel")
            .substringBefore(" | Webnovel")
            .trim()
            .ifBlank { HtmlMetadataParser.extractTitleFromSlug(url) }

        val author = Regex("""Author\s*:?\s*</[^>]+>\s*<[^>]+>([^<]+)</""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)
            ?.let(HtmlMetadataParser::decodeHtmlEntities)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val creators = author?.let { listOf(ScrapedCreator(it, "author")) } ?: generic.creators

        return generic.copy(
            title = cleanTitle,
            type = MediaType.NOVEL,
            creators = creators,
            platformHint = "Webnovel",
            unitName = "Chapter",
            coverUrl = normalizeWebnovelCover(generic.coverUrl, url),
            sourceUrl = url
        )
    }

    override fun normalizeDraft(draft: ScrapedMediaDraft): ScrapedMediaDraft {
        return draft.copy(coverUrl = normalizeWebnovelCover(draft.coverUrl, draft.sourceUrl))
    }

    private fun normalizeWebnovelCover(rawUrl: String?, baseUrl: String): String? {
        val normalized = CoverNormalizer.normalize(rawUrl, baseUrl = baseUrl) ?: return null
        val match = Regex(
            """https?://book-pic\.webnovel\.com/bookcover/(\d+)/(?:\d+/)?\d+\.jpg\?updateTime=(\d+)""",
            RegexOption.IGNORE_CASE
        ).find(normalized)
        return match?.let {
            "https://book-pic.webnovel.com/bookcover/${it.groupValues[1]}" +
                    "?imageMogr2/thumbnail/600x&imageId=${it.groupValues[2]}"
        } ?: normalized
    }
}