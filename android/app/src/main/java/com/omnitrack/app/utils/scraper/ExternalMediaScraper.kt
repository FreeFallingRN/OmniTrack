package com.omnitrack.app.utils.scraper

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import com.omnitrack.app.utils.ScrapedMediaDraft
import com.omnitrack.app.utils.scraper.adapters.AmazonAdapter
import com.omnitrack.app.utils.scraper.adapters.AniListAdapter
import com.omnitrack.app.utils.scraper.adapters.GenericFallbackAdapter
import com.omnitrack.app.utils.scraper.adapters.MangaDexAdapter
import com.omnitrack.app.utils.scraper.adapters.MyAnimeListAdapter
import com.omnitrack.app.utils.scraper.adapters.RoyalRoadAdapter
import com.omnitrack.app.utils.scraper.adapters.WebnovelAdapter
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

object ExternalMediaScraper {
    private const val TAG = "ExternalMediaScraper"

    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000L
            requestTimeoutMillis = 20_000L
            socketTimeoutMillis = 15_000L
        }
    }
    private val json = Json { ignoreUnknownKeys = true }

    // Register new site adapters here; GenericFallbackAdapter always runs last.
    private val adapters: List<MediaSourceAdapter> = listOf(
        MyAnimeListAdapter,
        AniListAdapter,
        MangaDexAdapter,
        RoyalRoadAdapter,
        AmazonAdapter,
        WebnovelAdapter
    )

    private class ScrapeTrace(
        val url: String,
        val adapterName: String
    ) {
        private val events = mutableListOf<String>()

        fun log(message: String) {
            events += message
        }

        fun flush(finalTitle: String?, finalCover: String?) {
            val summary = "title=\"${finalTitle.orEmpty()}\" cover=${if (!finalCover.isNullOrBlank()) "yes" else "no"}"
            Log.d(
                TAG,
                buildString {
                    appendLine("[$adapterName] $url -> $summary")
                    events.forEach { appendLine("  - $it") }
                }.trimEnd()
            )
        }
    }

    suspend fun scrape(context: Context, rawUrl: String): ScrapedMediaDraft {
        val url = normalizeInputUrl(rawUrl)
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        val matchedAdapter = adapters.firstOrNull { it.canHandle(host, url) }
        val trace = ScrapeTrace(url = url, adapterName = matchedAdapter?.name ?: "None")
        val fetcher = createFetchClient(context, trace)

        var resultTitle: String? = null
        var resultCover: String? = null

        try {
            trace.log("host=$host matchedAdapter=${matchedAdapter?.name ?: "none"}")

            // 1. Try API / fast-path extraction without HTML first
            if (matchedAdapter != null) {
                trace.log("fastPath: start")
                val fastDraft = runCatching {
                    matchedAdapter.scrapeWithoutHtml(url, fetcher)
                }.onFailure { e ->
                    trace.log("fastPath: error=${e.message}")
                    Log.w(TAG, "${matchedAdapter.name} fast-path failed: ${e.message}")
                }.getOrNull()

                if (fastDraft != null && fastDraft.title.isNotBlank()) {
                    val normalized = matchedAdapter.normalizeDraft(fastDraft.copy(sourceUrl = url))
                    val finalDraft = normalized.copy(
                        coverUrl = CoverNormalizer.normalize(normalized.coverUrl, baseUrl = url),
                        sourceUrl = url
                    )
                    trace.log(
                        "fastPath: hit title=\"${finalDraft.title}\" creators=${finalDraft.creators.size} cover=${finalDraft.coverUrl != null}"
                    )
                    resultTitle = finalDraft.title
                    resultCover = finalDraft.coverUrl
                    return finalDraft
                } else {
                    trace.log("fastPath: miss (null=${fastDraft == null}, blankTitle=${fastDraft?.title.isNullOrBlank()})")
                }
            }

            // 2. Fetch HTML (HTTP first, WebView fallback on bot challenge)
            val html = runCatching { fetcher.getHtml(url) }
                .onFailure { e -> trace.log("getHtml: error=${e.message}") }
                .getOrDefault("")
            trace.log("html: finalLen=${html.length} blank=${html.isBlank()}")

            // 3. Let matched adapter parse HTML, then enrich missing fields with GenericFallbackAdapter
            val adapterDraft = if (matchedAdapter != null && html.isNotBlank()) {
                val rawDraft = runCatching {
                    matchedAdapter.scrapeFromHtml(url, html)
                }.onFailure { e ->
                    trace.log("adapterParse: error=${e.message}")
                    Log.w(TAG, "${matchedAdapter.name} HTML parse failed: ${e.message}")
                }.getOrNull()

                if (rawDraft != null) {
                    trace.log(
                        "adapterParse: raw title=\"${rawDraft.title}\" creators=${rawDraft.creators.map { "${it.name}(${it.role})" }} cover=${rawDraft.coverUrl != null}"
                    )
                    matchedAdapter.normalizeDraft(rawDraft)
                } else {
                    trace.log("adapterParse: null")
                    null
                }
            } else {
                trace.log("adapterParse: skipped (adapter=${matchedAdapter != null}, htmlNonBlank=${html.isNotBlank()})")
                null
            }

            val fallbackDraft = GenericFallbackAdapter.scrapeFromHtml(url, html)
                ?: ScrapedMediaDraft(
                    title = HtmlMetadataParser.extractTitleFromSlug(url),
                    sourceUrl = url
                )
            trace.log(
                "fallbackParse: title=\"${fallbackDraft.title}\" creators=${fallbackDraft.creators.map { "${it.name}(${it.role})" }} cover=${fallbackDraft.coverUrl != null}"
            )

            val merged = mergeDrafts(
                primary = adapterDraft,
                fallback = fallbackDraft,
                host = host,
                url = url,
                trace = trace
            )
            resultTitle = merged.title
            resultCover = merged.coverUrl
            return merged
        } finally {
            trace.flush(finalTitle = resultTitle, finalCover = resultCover)
        }
    }

    /**
     * Lightweight cover-only lookup (replaces CoverLookup.findOpenGraphImage).
     */
    suspend fun findCoverImage(rawUrl: String, context: Context? = null): String? = withContext(Dispatchers.IO) {
        val url = normalizeInputUrl(rawUrl)
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        val matchedAdapter = adapters.firstOrNull { it.canHandle(host, url) }
        val trace = ScrapeTrace(url = url, adapterName = "${matchedAdapter?.name ?: "None"}:CoverOnly")
        val fetcher = if (context != null) createFetchClient(context, trace) else createFastFetchClient(trace)

        var resolvedCover: String? = null
        try {
            val fastDraft = matchedAdapter?.let { adapter ->
                runCatching {
                    adapter.scrapeWithoutHtml(url, fetcher)
                }.onFailure { e ->
                    trace.log("coverFastPath: error=${e.message}")
                }.getOrNull()?.let(adapter::normalizeDraft)
            }
            fastDraft?.coverUrl?.let { rawCover ->
                val normalized = CoverNormalizer.normalize(rawCover, baseUrl = url)
                trace.log("coverFastPath: hit -> $normalized")
                resolvedCover = normalized
                return@withContext normalized
            }

            val html = runCatching { fetcher.getHtml(url) }
                .onFailure { e -> trace.log("coverHtml: error=${e.message}") }
                .getOrDefault("")
            trace.log("coverHtml: len=${html.length}")

            val adapterCover = if (matchedAdapter != null && html.isNotBlank()) {
                runCatching {
                    matchedAdapter.scrapeFromHtml(url, html)?.let(matchedAdapter::normalizeDraft)?.coverUrl
                }.onFailure { e ->
                    trace.log("coverAdapter: error=${e.message}")
                }.getOrNull()
            } else null

            val genericCover = if (html.isNotBlank()) {
                HtmlMetadataParser.extractGenericMetadata(html, url).coverUrl
            } else null

            val finalCover = CoverNormalizer.normalize(adapterCover ?: genericCover, baseUrl = url)
            trace.log(
                "coverResolved: adapterCover=${adapterCover != null} genericCover=${genericCover != null} final=$finalCover"
            )
            resolvedCover = finalCover
            finalCover
        } finally {
            trace.flush(finalTitle = null, finalCover = resolvedCover)
        }
    }

    private fun createFastFetchClient(trace: ScrapeTrace? = null): HtmlFetchClient = object : HtmlFetchClient {
        override suspend fun getJson(url: String): String = withContext(Dispatchers.IO) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6_000
                readTimeout = 6_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "OmniTrack-Android/1.0")
            }
            try {
                val code = conn.responseCode
                trace?.log("json: status=$code url=$url")
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            } finally {
                conn.disconnect()
            }
        }

        override suspend fun getHtml(url: String): String {
            return runCatching { fetchHtmlFast(url, trace) }
                .onFailure { e -> trace?.log("httpFast: exception=${e.message}") }
                .getOrDefault("")
        }
    }

    private fun mergeDrafts(
        primary: ScrapedMediaDraft?,
        fallback: ScrapedMediaDraft,
        host: String,
        url: String,
        trace: ScrapeTrace? = null
    ): ScrapedMediaDraft {
        if (primary == null) {
            trace?.log("merge: primary=null -> using fallback")
            return fallback.copy(
                coverUrl = CoverNormalizer.normalize(fallback.coverUrl),
                sourceUrl = url
            )
        }

        val resolvedPlatform = primary.platformHint?.takeIf { it.isNotBlank() }
            ?: fallback.platformHint?.takeIf { it.isNotBlank() }
            ?: host.removePrefix("www.").substringBefore(".").replaceFirstChar { it.uppercase() }

        val titleFromFallback = primary.title.isBlank()
        val creatorsFromFallback = primary.creators.isEmpty() && fallback.creators.isNotEmpty()
        val coverFromFallback = primary.coverUrl.isNullOrBlank() && !fallback.coverUrl.isNullOrBlank()

        trace?.log(
            "merge: titleFromFallback=$titleFromFallback creatorsFromFallback=$creatorsFromFallback coverFromFallback=$coverFromFallback platform=$resolvedPlatform"
        )

        return primary.copy(
            title = primary.title.ifBlank { fallback.title },
            alternativeTitles = primary.alternativeTitles.ifEmpty { fallback.alternativeTitles },
            creators = primary.creators.ifEmpty { fallback.creators },
            platformHint = resolvedPlatform,
            coverUrl = CoverNormalizer.normalize(primary.coverUrl ?: fallback.coverUrl, baseUrl = url),
            sourceUrl = url
        )
    }

    private fun normalizeInputUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
        else "https://$trimmed"
    }

    private fun createFetchClient(context: Context, trace: ScrapeTrace): HtmlFetchClient = object : HtmlFetchClient {
        override suspend fun getJson(url: String): String = withContext(Dispatchers.IO) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6_000
                readTimeout = 6_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "OmniTrack-Android/1.0")
            }
            try {
                val code = conn.responseCode
                trace.log("json: status=$code url=$url")
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            } finally {
                conn.disconnect()
            }
        }

        override suspend fun getHtml(url: String): String {
            val fastHtml = runCatching { fetchHtmlFast(url, trace) }
                .onFailure { e -> trace.log("httpFast: exception=${e.message}") }
                .getOrDefault("")
            if (fastHtml.isNotBlank()) return fastHtml
            trace.log("httpFast: empty -> starting WebView fallback")
            return fetchHtmlWithWebView(context, url, trace)
        }
    }

    private suspend fun fetchHtmlFast(url: String, trace: ScrapeTrace? = null): String {
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

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun fetchHtmlWithWebView(
        context: Context,
        url: String,
        trace: ScrapeTrace? = null
    ): String = withContext(Dispatchers.Main) {
        val webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = settings.userAgentString.replace("; wv", "")
        }

        try {
            withTimeoutOrNull(15_000L.milliseconds) {
                suspendCancellableCoroutine { cont ->
                    var finished = false

                    fun pollDom(attempt: Int) {
                        if (!cont.isActive || finished) return
                        webView.evaluateJavascript(
                            "(function(){ return JSON.stringify({title: document.title, html: document.documentElement.outerHTML}); })()"
                        ) { rawJson ->
                            if (!cont.isActive || finished) return@evaluateJavascript
                            val unescaped = runCatching {
                                JSONTokener(rawJson).nextValue() as? String
                            }.getOrNull().orEmpty()

                            val title = runCatching {
                                json.parseToJsonElement(unescaped).jsonObject["title"]?.jsonPrimitive?.content.orEmpty()
                            }.getOrDefault("")
                            val html = runCatching {
                                json.parseToJsonElement(unescaped).jsonObject["html"]?.jsonPrimitive?.content.orEmpty()
                            }.getOrDefault("")

                            val challenged = html.isBlank() ||
                                    isBotChallenge(html) ||
                                    title.contains("Just a moment", ignoreCase = true)

                            if (!challenged) {
                                finished = true
                                trace?.log("webView: hit attempt=$attempt len=${html.length} title=\"${title.take(60)}\"")
                                cont.resume(html)
                            } else if (attempt < 20) {
                                webView.postDelayed({ pollDom(attempt + 1) }, 600L)
                            } else {
                                finished = true
                                trace?.log("webView: exhausted attempts=20 len=${html.length} title=\"${title.take(60)}\"")
                                cont.resume("")
                            }
                        }
                    }

                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            trace?.log("webView: onPageFinished url=$finishedUrl")
                            pollDom(1)
                        }
                    }

                    cont.invokeOnCancellation { finished = true }
                    webView.loadUrl(url)
                }
            }.orEmpty()
        } finally {
            webView.destroy()
        }
    }

    private fun isBotChallenge(html: String): Boolean {
        return html.contains("<title>Just a moment...</title>", ignoreCase = true) ||
                html.contains("cf-browser-verification", ignoreCase = true) ||
                html.contains("challenge-platform", ignoreCase = true)
    }
}