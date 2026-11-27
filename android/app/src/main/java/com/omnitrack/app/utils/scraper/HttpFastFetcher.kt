package com.omnitrack.app.utils.scraper

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

internal class SiteBlockedException(detail: String, val statusCode: Int? = null) :
    IllegalStateException("Site blocked automated requests / anti-bot protection ($detail)")

internal class HttpFastFetcher(private val client: HttpClient) {
    suspend fun getJson(
        url: String,
        trace: ExternalMediaScraper.ScrapeTrace?
    ): String {
        val response = client.get(url) {
            header("Accept", "application/json")
            header("User-Agent", "OmniTrack-Android/1.0")
        }
        trace?.log("json: status=${response.status.value} url=$url")
        check(response.status.isSuccess()) { "HTTP ${response.status.value}" }
        return response.bodyAsText()
    }

    suspend fun getHtml(url: String, trace: ExternalMediaScraper.ScrapeTrace?): String =
        fetchHtmlFast(url, trace)

    private suspend fun fetchHtmlFast(
        url: String,
        trace: ExternalMediaScraper.ScrapeTrace?
    ): String {
        val response: HttpResponse = client.get(url) {
            header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
            )
            header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            header("Accept-Language", "en-US,en;q=0.9")
        }
        if (response.status.value == 403 || response.status.value == 429) {
            trace?.log("httpFast: blocked HTTP ${response.status.value}")
            throw SiteBlockedException("HTTP ${response.status.value}", response.status.value)
        }
        check(response.status.isSuccess()) { "Could not fetch metadata (HTTP ${response.status.value})" }
        val body = response.bodyAsText()
        check(body.isNotBlank()) { "Could not fetch metadata from this source (empty response)" }
        val botChallenge = isBotChallenge(body)
        val looksLikeStub = (body.length < 10_000 &&
            HtmlMetadataParser.meta(body, "og:title").isNullOrBlank() &&
            !body.contains("application/ld+json", ignoreCase = true)) ||
            Regex("""<title>\s*Amazon\.[a-z.]+\s*</title>""", RegexOption.IGNORE_CASE).containsMatchIn(body)
        trace?.log(
            "httpFast: status=${response.status.value} ok=${response.status.isSuccess()} len=${body.length} botChallenge=$botChallenge stub=$looksLikeStub"
        )
        if (botChallenge) throw SiteBlockedException("challenge page")
        if (looksLikeStub) return ""
        return body
    }
}

internal fun isBotChallenge(html: String): Boolean =
    Regex("""<title[^>]*>\s*(?:just a moment|robot check|access denied|attention required)[^<]*</title>""", RegexOption.IGNORE_CASE)
        .containsMatchIn(html) ||
        html.contains("cf-browser-verification", ignoreCase = true) ||
        html.contains("cf-chl-form", ignoreCase = true) ||
        html.contains("validateCaptcha", ignoreCase = true) ||
        html.contains("cf-chl-widget", ignoreCase = true)
