package com.omnitrack.app.utils.scraper

import android.content.Context
import android.util.Log
import com.omnitrack.app.data.MangaBakaPreferences
import com.omnitrack.app.data.MediaHttpClient
import com.omnitrack.app.data.mangaBakaAuthorization
import com.omnitrack.app.domain.model.MediaType
import com.omnitrack.app.utils.ScrapedMediaDraft
import com.omnitrack.app.utils.ImportedMediaDraft
import com.omnitrack.app.utils.scraper.adapters.AmazonAdapter
import com.omnitrack.app.utils.scraper.adapters.AniListAdapter
import com.omnitrack.app.utils.scraper.adapters.GenericFallbackAdapter
import com.omnitrack.app.utils.scraper.adapters.MangaBakaAdapter
import com.omnitrack.app.utils.scraper.adapters.MangaDexAdapter
import com.omnitrack.app.utils.scraper.adapters.MyAnimeListAdapter
import com.omnitrack.app.utils.scraper.adapters.NovelpiaAdapter
import com.omnitrack.app.utils.scraper.adapters.RoyalRoadAdapter
import com.omnitrack.app.utils.scraper.adapters.WebnovelAdapter
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI

object ExternalMediaScraper {
    internal const val TAG = "ExternalMediaScraper"

    internal val client = MediaHttpClient.client
    private val mangaBakaClient = client.config { followRedirects = false }
    private val httpFastFetcher = HttpFastFetcher(client)
    private val webViewHtmlFetcher = WebViewHtmlFetcher()

    // Register new site adapters here; GenericFallbackAdapter always runs last.
    private val adapters: List<MediaSourceAdapter> = listOf(
        MyAnimeListAdapter,
        AniListAdapter,
        MangaBakaAdapter,
        MangaDexAdapter,
        RoyalRoadAdapter,
        AmazonAdapter,
        WebnovelAdapter,
        NovelpiaAdapter
    )

    internal class ScrapeTrace(
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

    suspend fun scrape(
        context: Context,
        rawUrl: String,
        mangaBakaOtherType: MediaType? = null,
        requireFetchedMetadata: Boolean = false
    ): ImportedMediaDraft {
        val url = normalizeInputUrl(rawUrl)
        if (requireFetchedMetadata) {
            val uri = URI(url)
            require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null) {
                "Enter a valid HTTP or HTTPS source URL"
            }
        }
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        val adapter = adapters.firstOrNull { it.canHandle(host, url) }
        if (host in listOf("mangabaka.org", "www.mangabaka.org", "mangabaka.dev", "dev.mangabaka.dev")) {
            require(adapter === MangaBakaAdapter) { "Invalid MangaBaka series URL" }
        }
        val draft = scrapeDraft(context, rawUrl, url, host, adapter, mangaBakaOtherType)
        return ImportedMediaDraft(
            sourceUrl = rawUrl.trim(),
            normalizedUrl = if (adapter === MangaBakaAdapter) draft.sourceUrl else url,
            adapterName = adapter?.name ?: GenericFallbackAdapter.name,
            draft = draft
        )
    }

    private suspend fun scrapeDraft(
        context: Context,
        rawUrl: String,
        url: String,
        host: String,
        matchedAdapter: MediaSourceAdapter?,
        mangaBakaOtherType: MediaType?
    ): ScrapedMediaDraft {
        val originalUrl = rawUrl.trim().let {
            if (it.startsWith("http://", true) || it.startsWith("https://", true)) it else "https://$it"
        }
        val trace = ScrapeTrace(url = url, adapterName = matchedAdapter?.name ?: "None")
        val fetcher = createFetchClient(context, trace)

        var resultTitle: String? = null
        var resultCover: String? = null

        try {
            trace.log("host=$host matchedAdapter=${matchedAdapter?.name ?: "none"}")

            if (matchedAdapter === MangaBakaAdapter) {
                val draft = requireNotNull(MangaBakaAdapter.scrapeSeries(url, fetcher, mangaBakaOtherType)) {
                    "Could not load MangaBaka series"
                }
                resultTitle = draft.title
                resultCover = draft.coverUrl
                return draft.copy(coverUrl = CoverNormalizer.normalize(draft.coverUrl, baseUrl = draft.sourceUrl))
            }

            // 1. Try API / fast-path extraction without HTML first
            if (matchedAdapter != null) {
                trace.log("fastPath: start")
                val fastDraft = runCatching {
                    matchedAdapter.scrapeWithoutHtml(url, fetcher)
                }.onFailure { e ->
                    if (e is CancellationException) throw e
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
                        "fastPath: hit title=\"${finalDraft.title}\" creators=${finalDraft.creators.size} cover=${finalDraft.coverUrl != null} publishingStatus=${finalDraft.publishingStatus}"
                    )
                    resultTitle = finalDraft.title
                    resultCover = finalDraft.coverUrl
                    return finalDraft
                } else {
                    trace.log("fastPath: miss (null=${fastDraft == null}, blankTitle=${fastDraft?.title.isNullOrBlank()})")
                }
            }

            // 2. Fetch HTML (HTTP first, WebView fallback on bot challenge)
            val html = try {
                fetcher.getHtml(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                trace.log("getHtml: error=${e.message}")
                Log.w(TAG, "HTML fetch failed for $url", e)
                ""
            }
            trace.log("html: finalLen=${html.length} blank=${html.isBlank()}")
            check(html.isNotBlank()) { "Could not fetch metadata from this source" }

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
                        "adapterParse: raw title=\"${rawDraft.title}\" creators=${rawDraft.creators.map { "${it.name}(${it.role})" }} cover=${rawDraft.coverUrl != null} publishingStatus=${rawDraft.publishingStatus}"
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

            if (matchedAdapter === AmazonAdapter) {
                val finalDraft = AmazonAdapter.completeMetadata(
                    url, HtmlMetadataParser.extractAmazonTitleSlug(originalUrl), adapterDraft, fetcher
                )
                resultTitle = finalDraft.title
                resultCover = finalDraft.coverUrl
                return finalDraft
            }

            val fallbackDraft = GenericFallbackAdapter.scrapeFromHtml(url, html)
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

            if (matchedAdapter === MangaBakaAdapter) return@withContext null

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
        override suspend fun getJson(url: String): String = httpFastFetcher.getJson(url, trace)

        override suspend fun getHtml(url: String): String = httpFastFetcher.getHtml(url, trace)
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
        val url = if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) trimmed
        else "https://$trimmed"
        return HtmlMetadataParser.normalizeAmazonUrl(url)
    }

    private fun createFetchClient(context: Context, trace: ScrapeTrace): HtmlFetchClient = object : HtmlFetchClient {
        override suspend fun getJson(url: String): String = httpFastFetcher.getJson(url, trace)

        override suspend fun getMangaBakaJson(url: String): String {
            val uri = URI(url)
            require(uri.scheme == "https" && uri.host == "api.mangabaka.org" &&
                uri.port == -1 && uri.userInfo == null &&
                Regex("^/v1/series/[0-9]+$").matches(uri.path) &&
                uri.rawQuery == null && uri.rawFragment == null) { "Invalid MangaBaka API URL" }
            val credentials = try {
                MangaBakaPreferences(context.applicationContext).read()
            } catch (e: Exception) {
                Log.w(TAG, "Could not read MangaBaka credentials", e)
                null
            }
            val response = mangaBakaClient.get(url) {
                header("Accept", "application/json")
                header("User-Agent", "OmniTrack-Android/1.0")
                if (credentials != null && credentials.accessToken.isNotBlank()) mangaBakaAuthorization(credentials)
            }
            trace.log("json: status=${response.status.value} url=$url")
            if (response.status.value in listOf(401, 403) && credentials?.accessToken?.isNotBlank() == true) {
                val publicResponse = mangaBakaClient.get(url) {
                    header("Accept", "application/json")
                    header("User-Agent", "OmniTrack-Android/1.0")
                }
                check(publicResponse.status.isSuccess()) { "MangaBaka metadata request failed (HTTP ${publicResponse.status.value})" }
                return publicResponse.bodyAsText()
            }
            check(response.status.isSuccess()) { "HTTP ${response.status.value}" }
            return response.bodyAsText()
        }

        override suspend fun getHtml(url: String): String {
            val fastHtml = httpFastFetcher.getHtml(url, trace)
            if (fastHtml.isNotBlank()) return fastHtml
            trace.log("httpFast: empty -> starting WebView fallback")
            return webViewHtmlFetcher.getHtml(context, url, trace)
        }
    }

}