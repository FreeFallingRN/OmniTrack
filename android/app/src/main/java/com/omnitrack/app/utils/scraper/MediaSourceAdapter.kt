package com.omnitrack.app.utils.scraper

import com.omnitrack.app.utils.ScrapedMediaDraft

interface MediaSourceAdapter {
    val name: String

    fun canHandle(host: String, url: String): Boolean

    /**
     * Optional fast-path extraction (e.g., public API or deterministic URL construction)
     * that runs before fetching HTML. Return null to fall through to HTML scraping.
     */
    suspend fun scrapeWithoutHtml(
        url: String,
        fetcher: HtmlFetchClient
    ): ScrapedMediaDraft? = null

    /**
     * Parses fetched HTML into a draft. Return null if extraction fails.
     */
    suspend fun scrapeFromHtml(
        url: String,
        html: String
    ): ScrapedMediaDraft? = null

    fun normalizeDraft(draft: ScrapedMediaDraft): ScrapedMediaDraft = draft
}

interface HtmlFetchClient {
    suspend fun getJson(url: String): String
    suspend fun getHtml(url: String): String
}