package com.omnitrack.app.utils.scraper

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException

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
        try {
            fetchHtmlFast(url, trace)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            trace?.log("httpFast: exception=${e.message}")
            Log.w(ExternalMediaScraper.TAG, "HTML fetch failed for $url", e)
            ""
        }

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
        val body = response.bodyAsText()
        val botChallenge = isBotChallenge(body)
        val looksLikeStub = body.length < 10_000 ||
            Regex("""<title>\s*Amazon\.[a-z.]+\s*</title>""", RegexOption.IGNORE_CASE).containsMatchIn(body)
        trace?.log(
            "httpFast: status=${response.status.value} ok=${response.status.isSuccess()} len=${body.length} botChallenge=$botChallenge stub=$looksLikeStub"
        )
        if (!response.status.isSuccess() || botChallenge || looksLikeStub) return ""
        return body
    }
}

internal fun isBotChallenge(html: String): Boolean =
    html.contains("<title>Just a moment...</title>", ignoreCase = true) ||
        html.contains("cf-browser-verification", ignoreCase = true) ||
        html.contains("challenge-platform", ignoreCase = true) ||
        html.contains("validateCaptcha", ignoreCase = true) ||
        html.contains("<title>Robot Check</title>", ignoreCase = true)
