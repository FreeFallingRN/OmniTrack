package com.omnitrack.app.utils.scraper.adapters

import com.omnitrack.app.domain.model.MediaType
import com.omnitrack.app.utils.ScrapedMediaDraft
import com.omnitrack.app.utils.scraper.HtmlMetadataParser
import com.omnitrack.app.utils.scraper.MediaSourceAdapter
import java.net.URI

object GenericFallbackAdapter : MediaSourceAdapter {
    override val name: String = "GenericFallback"

    override fun canHandle(host: String, url: String): Boolean = true

    override suspend fun scrapeFromHtml(url: String, html: String): ScrapedMediaDraft {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        val platformName = host
            .removePrefix("www.")
            .substringBefore(".")
            .takeIf { it.isNotBlank() }
            ?.replaceFirstChar { it.uppercase() }

        val base = HtmlMetadataParser.extractGenericMetadata(html, url)
        val isReLibrary = host == "re-library.com" || host == "www.re-library.com"
        val status = if (isReLibrary) {
            HtmlMetadataParser.labeledStatus(html).ifBlank {
                Regex(
                    """<(?:span|div|a)[^>]*class=["'][^"']*(?:status|badge)[^"']*["'][^>]*>\s*(ongoing|completed|complete|completely translated|hiatus|dropped)\s*</(?:span|div|a)>""",
                    RegexOption.IGNORE_CASE
                ).find(html)?.groupValues?.get(1)?.let(HtmlMetadataParser::publishingStatus).orEmpty()
            }
        } else ""
        val description = if (isReLibrary) {
            Regex(
                """id=["']synopsis["'][^>]*>[\s\S]*?<div[^>]*class=["'][^"']*\bsu-box-content\b[^"']*["'][^>]*>([\s\S]*?)</div>""",
                RegexOption.IGNORE_CASE
            ).find(html)?.groupValues?.get(1)
                ?.replace(Regex("<[^>]+>"), " ")
                ?.let(HtmlMetadataParser::decodeHtmlEntities)
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        } else null
        return base.copy(
            type = base.type,
            platformHint = platformName,
            publishingStatus = status,
            unitName = if (base.type == MediaType.ANIME) "Episode" else "Chapter",
            sourceUrl = url,
            description = description ?: base.description
        )
    }
}