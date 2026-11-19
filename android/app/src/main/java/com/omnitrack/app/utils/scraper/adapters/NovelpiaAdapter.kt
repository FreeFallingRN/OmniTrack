package com.omnitrack.app.utils.scraper.adapters

import com.omnitrack.app.domain.model.MediaType
import com.omnitrack.app.utils.ScrapedCreator
import com.omnitrack.app.utils.ScrapedMediaDraft
import com.omnitrack.app.utils.scraper.HtmlMetadataParser
import com.omnitrack.app.utils.scraper.MediaSourceAdapter

object NovelpiaAdapter : MediaSourceAdapter {
    override val name: String = "Novelpia"

    override fun canHandle(host: String, url: String): Boolean =
        host.contains("novelpia.com", ignoreCase = true)

    override suspend fun scrapeFromHtml(url: String, html: String): ScrapedMediaDraft {
        val generic = HtmlMetadataParser.extractGenericMetadata(html, url)

        val title = Regex("""<[^>]*class=["'][^"']*\bnv-tit\b[^"']*["'][^>]*>([^<]+)</""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)
            ?.let(HtmlMetadataParser::decodeHtmlEntities)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: generic.title
                .substringBefore(" - NOVELPIA")
                .substringBefore(" | NOVELPIA")
                .trim()
                .ifBlank { HtmlMetadataParser.extractTitleFromSlug(url) }

        val author = Regex("""<[^>]*class=["'][^"']*\binfo-author\b[^"']*["'][^>]*>([^<]+)</""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)
            ?.let(HtmlMetadataParser::decodeHtmlEntities)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val rawCover = Regex("""<div[^>]*class=["'][^"']*\bcover-box\b[^"']*["'][^>]*>[\s\S]*?<img[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: generic.coverUrl

        val coverUrl = rawCover?.let { src ->
            when {
                src.startsWith("//") -> "https:$src"
                src.startsWith("/") -> "https://global.novelpia.com$src"
                else -> src
            }
        }

        val pubStatus = when {
            Regex("""class=["'][^"']*\bnv-stat-badge\b[^"']*["'][^>]*>[\s\S]*?>\s*(Completed|Comp\.)\s*<""", RegexOption.IGNORE_CASE)
                .containsMatchIn(html) -> "completed"
            else -> "releasing"
        }

        val creators = author
            ?.let { listOf(ScrapedCreator(it, "author")) }
            ?: generic.creators

        return generic.copy(
            title = title,
            coverUrl = coverUrl,
            type = MediaType.NOVEL,
            creators = creators,
            platformHint = "Novelpia",
            publishingStatus = pubStatus,
            unitName = "Chapter",
            sourceUrl = url
        )
    }
}