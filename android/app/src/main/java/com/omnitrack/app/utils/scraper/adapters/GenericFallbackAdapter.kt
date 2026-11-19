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
        return base.copy(
            type = base.type,
            platformHint = platformName,
            unitName = if (base.type == MediaType.ANIME) "Episode" else "Chapter",
            sourceUrl = url
        )
    }
}