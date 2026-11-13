package com.omnitrack.app.utils

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import com.omnitrack.app.domain.model.MediaType
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONTokener
import java.net.URI
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

data class ScrapedMediaDraft(
    val title: String = "",
    val type: MediaType = MediaType.NOVEL,
    val authorName: String? = null,
    val platformHint: String? = null,
    val publishingStatus: String = "releasing",
    val unitName: String = "Chapter",
    val coverUrl: String? = null,
    val sourceUrl: String = ""
)

object MediaUrlImporter {
    private const val TAG = "MediaUrlImporter"
    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000L
            requestTimeoutMillis = 20_000L
            socketTimeoutMillis = 15_000L
        }
    }
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun importFromUrl(context: Context, rawUrl: String): ScrapedMediaDraft {
        val url = rawUrl.trim().let {
            if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it"
        }
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        Log.d(TAG, "Importing URL: $url (host=$host)")

        return when {
            host.contains("myanimelist.net") -> scrapeMyAnimeList(context, url)
            host.contains("anilist.co") -> scrapeAniList(context, url)
            host.contains("mangadex.org") -> scrapeMangaDex(context, url)
            host.contains("mangaupdates.com") -> scrapeMangaUpdates(context, url)
            host.contains("royalroad.com") -> scrapeRoyalRoad(context, url)
            else -> scrapeGeneric(context, url, host)
        }
    }

    // 1. MyAnimeList via AniList GraphQL (by idMal) + scoped MAL HTML fallback
    private suspend fun scrapeMyAnimeList(context: Context, url: String): ScrapedMediaDraft {
        val isManga = url.contains("/manga/")
        val idMatch = Regex("""/(anime|manga)/(\d+)""").find(url)
        val malId = idMatch?.groupValues?.getOrNull(2)?.toIntOrNull()

        if (malId != null) {
            val anilistDraft = runCatching {
                fetchFromAniList(idMal = malId, isManga = isManga, platformHint = "MyAnimeList", sourceUrl = url)
            }.onFailure { e ->
                Log.w(TAG, "AniList MAL lookup failed (${e.message}), falling back to direct MAL scrape")
            }.getOrNull()

            if (anilistDraft != null) {
                Log.d(TAG, "Scraped AniList-by-MAL draft: $anilistDraft")
                return anilistDraft
            }
        }

        // Direct MAL HTML Scrape Fallback
        val html = fetchHtmlWithFallback(context, url)
        val generic = extractOpenGraph(html, url)

        val cleanTitle = generic.title
            .substringBefore(" - MyAnimeList.net")
            .trim()
            .ifBlank { extractTitleFromSlug(url) }

        val cdnPoster = Regex("""https://cdn\.myanimelist\.net/images/(?:anime|manga)/\d+/\d+\.(?:jpg|webp)""", RegexOption.IGNORE_CASE)
            .find(html)?.value
            ?: generic.coverUrl?.takeUnless { it.contains("mal-logo", ignoreCase = true) || it.contains("icon", ignoreCase = true) }

        val creator = if (isManga) {
            val authorSection = Regex("""Authors?:</span>([\s\S]{0,400}?)</div>""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1).orEmpty()
            Regex("""<a[^>]*href="/people/\d+/[^"]*"[^>]*>([^<]+)</a>""", RegexOption.IGNORE_CASE)
                .find(authorSection)?.groupValues?.getOrNull(1)?.trim()
                ?.let { formatMalAuthorName(decodeHtmlEntities(it)) }
        } else {
            // Scope strictly to the "Studios:" block (ignoring "Producers:" which also use /anime/producer/)
            val studioSection = Regex("""Studios?:</span>([\s\S]{0,400}?)</div>""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1).orEmpty()
            Regex("""<a[^>]*href="/anime/producer/\d+/[^"]*"[^>]*>([^<]+)</a>""", RegexOption.IGNORE_CASE)
                .find(studioSection)?.groupValues?.getOrNull(1)?.trim()
                ?.let { decodeHtmlEntities(it) }
                ?.takeUnless { it.equals("None found", ignoreCase = true) }
        }

        val statusBlock = Regex("""Status:</span>[\s\S]{0,60}?([A-Za-z ]+)""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1).orEmpty().lowercase()
        val pubStatus = when {
            statusBlock.contains("finished") -> "completed"
            statusBlock.contains("not yet") -> "announced"
            statusBlock.contains("hiatus") -> "hiatus"
            statusBlock.contains("discontinued") -> "discontinued"
            else -> "releasing"
        }

        val mediaType = if (isManga) MediaType.COMIC else MediaType.ANIME
        val draft = ScrapedMediaDraft(
            title = cleanTitle,
            type = mediaType,
            authorName = creator,
            platformHint = "MyAnimeList",
            publishingStatus = pubStatus,
            unitName = if (mediaType == MediaType.ANIME) "Episode" else "Chapter",
            coverUrl = cdnPoster,
            sourceUrl = url
        )
        Log.d(TAG, "Scraped MAL HTML draft: $draft")
        return draft
    }

    private suspend fun fetchFromAniList(
        id: Int? = null,
        idMal: Int? = null,
        isManga: Boolean,
        platformHint: String,
        sourceUrl: String
    ): ScrapedMediaDraft? = withContext(Dispatchers.IO) {
        val typeEnum = if (isManga) "MANGA" else "ANIME"
        val argDef = if (id != null) "\$id: Int" else "\$idMal: Int"
        val argUse = if (id != null) "id: \$id" else "idMal: \$idMal"
        val query = """
            query ($argDef, ${'$'}type: MediaType) {
              Media($argUse, type: ${'$'}type) {
                format
                status
                title { english romaji }
                coverImage { extraLarge large }
                studios(isMain: true) { nodes { name } }
                staff(perPage: 5) {
                  edges {
                    role
                    node { name { full } }
                  }
                }
              }
            }
        """.trimIndent().replace("\n", " ")

        val idField = if (id != null) "\"id\":$id" else "\"idMal\":$idMal"
        val payload = """{"query":${org.json.JSONObject.quote(query)},"variables":{$idField,"type":"$typeEnum"}}"""

        val conn = (java.net.URL("https://graphql.anilist.co").openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 6_000
            readTimeout = 6_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }

        val responseText = try {
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) return@withContext null
            conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } finally {
            conn.disconnect()
        }

        val media = json.parseToJsonElement(responseText).jsonObject["data"]
            ?.jsonObject?.get("Media")?.jsonObject ?: return@withContext null

        val titleObj = media["title"]?.jsonObject
        val title = titleObj?.get("english")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it != "null" }
            ?: titleObj?.get("romaji")?.jsonPrimitive?.content.orEmpty()

        val coverObj = media["coverImage"]?.jsonObject
        val coverUrl = coverObj?.get("extraLarge")?.jsonPrimitive?.content?.takeIf { it != "null" }
            ?: coverObj?.get("large")?.jsonPrimitive?.content?.takeIf { it != "null" }

        val pubStatus = when (media["status"]?.jsonPrimitive?.content) {
            "FINISHED" -> "completed"
            "NOT_YET_RELEASED" -> "announced"
            "HIATUS" -> "hiatus"
            "CANCELLED" -> "discontinued"
            else -> "releasing"
        }

        val creatorName = if (isManga) {
            media["staff"]?.jsonObject?.get("edges")?.jsonArray
                ?.firstOrNull { it.jsonObject["role"]?.jsonPrimitive?.content?.contains("Story", ignoreCase = true) == true }
                ?.jsonObject?.get("node")?.jsonObject?.get("name")?.jsonObject?.get("full")?.jsonPrimitive?.content
                ?: media["staff"]?.jsonObject?.get("edges")?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("node")?.jsonObject?.get("name")?.jsonObject?.get("full")?.jsonPrimitive?.content
        } else {
            media["studios"]?.jsonObject?.get("nodes")?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("name")?.jsonPrimitive?.content
        }

        val mediaType = when (media["format"]?.jsonPrimitive?.content) {
            "MOVIE" -> MediaType.MOVIE
            else -> if (isManga) MediaType.COMIC else MediaType.ANIME
        }

        ScrapedMediaDraft(
            title = title,
            type = mediaType,
            authorName = creatorName,
            platformHint = "MyAnimeList",
            publishingStatus = pubStatus,
            unitName = if (mediaType == MediaType.ANIME) "Episode" else "Chapter",
            coverUrl = coverUrl,
            sourceUrl = sourceUrl
        )
    }

    // 2. Royal Road HTML Parser
    private suspend fun scrapeRoyalRoad(context: Context, url: String): ScrapedMediaDraft {
        val html = fetchHtmlWithFallback(context, url)
        val generic = extractOpenGraph(html, url)

        val rawTitle = generic.title
            .substringBefore(" | Royal Road")
            .trim()
            .ifBlank { extractTitleFromSlug(url) }

        val author = Regex("""Author card for ([^<]+)</div>""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.trim()
            ?: Regex("""<a[^>]*href="/profile/\d+"[^>]*>\s*<h4[^>]*>([^<]+)</h4>""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1)?.trim()
            ?: Regex("""<meta[^>]+name=["']author["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1)?.trim()
            ?: Regex(""""author"\s*:\s*\{[^}]*"name"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1)?.trim()

        val status = when {
            html.contains(">COMPLETED<", ignoreCase = true) -> "completed"
            html.contains(">HIATUS<", ignoreCase = true) -> "hiatus"
            html.contains(">STUB<", ignoreCase = true) || html.contains(">DROPPED<", ignoreCase = true) -> "discontinued"
            else -> "releasing"
        }

        val draft = ScrapedMediaDraft(
            title = rawTitle,
            type = MediaType.NOVEL,
            authorName = author?.let { decodeHtmlEntities(it) },
            platformHint = "Royal Road",
            publishingStatus = status,
            unitName = "Chapter",
            coverUrl = generic.coverUrl,
            sourceUrl = url
        )
        Log.d(TAG, "Scraped Royal Road draft: $draft")
        return draft
    }

    // 2. AniList Direct URL (anilist.co/anime/{id} or /manga/{id})
    private suspend fun scrapeAniList(context: Context, url: String): ScrapedMediaDraft {
        val isManga = url.contains("/manga/")
        val idMatch = Regex("""/(anime|manga)/(\d+)""").find(url)
        val anilistId = idMatch?.groupValues?.getOrNull(2)?.toIntOrNull()

        if (anilistId != null) {
            val draft = runCatching {
                fetchFromAniList(id = anilistId, idMal = null, isManga = isManga, platformHint = "AniList", sourceUrl = url)
            }.onFailure { e ->
                Log.w(TAG, "AniList direct lookup failed (${e.message}), falling back to HTML")
            }.getOrNull()

            if (draft != null) {
                Log.d(TAG, "Scraped AniList draft: $draft")
                return draft
            }
        }
        return scrapeGeneric(context, url, "anilist.co")
    }

    // 3. MangaDex Public REST API (mangadex.org/title/{uuid}/...)
    private suspend fun scrapeMangaDex(context: Context, url: String): ScrapedMediaDraft {
        val uuidMatch = Regex("""/title/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})""").find(url)
        val mangaId = uuidMatch?.groupValues?.getOrNull(1)

        if (mangaId != null) {
            val draft = runCatching {
                val apiUrl = "https://api.mangadex.org/manga/$mangaId?includes[]=author&includes[]=cover_art"
                Log.d(TAG, "Fetching MangaDex API: $apiUrl")
                val responseText = httpGetJson(apiUrl)
                val data = json.parseToJsonElement(responseText).jsonObject["data"]?.jsonObject
                    ?: return@runCatching null
                val attrs = data["attributes"]?.jsonObject ?: return@runCatching null

                val titleObj = attrs["title"]?.jsonObject
                val title = titleObj?.get("en")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: titleObj?.values?.firstOrNull()?.jsonPrimitive?.content.orEmpty()

                val pubStatus = when (attrs["status"]?.jsonPrimitive?.content?.lowercase()) {
                    "completed" -> "completed"
                    "hiatus" -> "hiatus"
                    "cancelled" -> "discontinued"
                    else -> "releasing"
                }

                val relationships = data["relationships"]?.jsonArray.orEmpty()
                val authorName = relationships.firstOrNull {
                    it.jsonObject["type"]?.jsonPrimitive?.content == "author"
                }?.jsonObject?.get("attributes")?.jsonObject?.get("name")?.jsonPrimitive?.content

                val coverFileName = relationships.firstOrNull {
                    it.jsonObject["type"]?.jsonPrimitive?.content == "cover_art"
                }?.jsonObject?.get("attributes")?.jsonObject?.get("fileName")?.jsonPrimitive?.content

                val coverUrl = coverFileName?.let { "https://uploads.mangadex.org/covers/$mangaId/$it" }

                ScrapedMediaDraft(
                    title = title,
                    type = MediaType.COMIC,
                    authorName = authorName,
                    platformHint = "MangaDex",
                    publishingStatus = pubStatus,
                    unitName = "Chapter",
                    coverUrl = coverUrl,
                    sourceUrl = url
                )
            }.onFailure { e ->
                Log.w(TAG, "MangaDex API failed (${e.message}), falling back to HTML")
            }.getOrNull()

            if (draft != null) {
                Log.d(TAG, "Scraped MangaDex draft: $draft")
                return draft
            }
        }
        return scrapeGeneric(context, url, "mangadex.org")
    }

    // 4. MangaUpdates Public REST API v1 (/series/{base36Id}/... or /series.html?id={intId})
    private suspend fun scrapeMangaUpdates(context: Context, url: String): ScrapedMediaDraft {
        val slugId = Regex("""/series/([a-zA-Z0-9]+)""").find(url)?.groupValues?.getOrNull(1)
        val queryId = Regex("""[?&]id=(\d+)""").find(url)?.groupValues?.getOrNull(1)?.toLongOrNull()
        val seriesId = queryId ?: slugId?.toLongOrNull(36)

        if (seriesId != null) {
            val draft = runCatching {
                val apiUrl = "https://api.mangaupdates.com/v1/series/$seriesId"
                Log.d(TAG, "Fetching MangaUpdates API: $apiUrl")
                val responseText = httpGetJson(apiUrl)
                val root = json.parseToJsonElement(responseText).jsonObject

                val title = decodeHtmlEntities(root["title"]?.jsonPrimitive?.content.orEmpty())
                val coverUrl = root["image"]?.jsonObject?.get("url")?.jsonObject
                    ?.get("original")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it != "null" }

                val rawStatus = root["status"]?.jsonPrimitive?.content.orEmpty().lowercase()
                val completedFlag = root["completed"]?.jsonPrimitive?.content == "true"
                val pubStatus = when {
                    completedFlag || rawStatus.contains("complete") -> "completed"
                    rawStatus.contains("hiatus") -> "hiatus"
                    rawStatus.contains("discontinued") || rawStatus.contains("cancel") -> "discontinued"
                    else -> "releasing"
                }

                val authorName = root["authors"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("name")?.jsonPrimitive?.content

                val typeRaw = root["type"]?.jsonPrimitive?.content.orEmpty().lowercase()
                val mediaType = if (typeRaw.contains("novel")) MediaType.NOVEL else MediaType.COMIC

                ScrapedMediaDraft(
                    title = title,
                    type = mediaType,
                    authorName = authorName,
                    platformHint = "MangaUpdates",
                    publishingStatus = pubStatus,
                    unitName = "Chapter",
                    coverUrl = coverUrl,
                    sourceUrl = url
                )
            }.onFailure { e ->
                Log.w(TAG, "MangaUpdates API failed (${e.message}), falling back to HTML")
            }.getOrNull()

            if (draft != null) {
                Log.d(TAG, "Scraped MangaUpdates draft: $draft")
                return draft
            }
        }
        return scrapeGeneric(context, url, "mangaupdates.com")
    }

    private suspend fun httpGetJson(url: String): String = withContext(Dispatchers.IO) {
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6_000
            readTimeout = 6_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "OmniTrack-Android/1.0")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code")
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } finally {
            conn.disconnect()
        }
    }

    // 4. Generic Fallback for Any Other Site
    private suspend fun scrapeGeneric(context: Context, url: String, host: String): ScrapedMediaDraft {
        val html = fetchHtmlWithFallback(context, url)
        val generic = extractOpenGraph(html, url)
        val title = generic.title.ifBlank { extractTitleFromSlug(url) }

        return ScrapedMediaDraft(
            title = title,
            type = MediaType.NOVEL,
            platformHint = host.removePrefix("www.").substringBefore(".").replaceFirstChar { it.uppercase() },
            coverUrl = generic.coverUrl,
            sourceUrl = url
        )
    }

    // Tries fast Ktor HTTP first; automatically falls back to hidden WebView if Cloudflare blocks it
    private suspend fun fetchHtmlWithFallback(context: Context, url: String): String {
        val fastHtml = runCatching { fetchHtmlFast(url) }.getOrDefault("")
        if (fastHtml.isNotBlank()) return fastHtml
        return fetchHtmlWithWebView(context, url)
    }

    private suspend fun fetchHtmlFast(url: String): String {
        Log.d(TAG, "Fast HTTP fetch: $url")
        val response: HttpResponse = client.get(url) {
            header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
            header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            header("Accept-Language", "en-US,en;q=0.9")
        }
        val body = response.bodyAsText()
        Log.d(TAG, "Fast HTTP status ${response.status.value} (${body.length} chars)")

        if (!response.status.isSuccess() || isBotChallenge(body)) {
            Log.w(TAG, "Blocked by Cloudflare/bot challenge (HTTP ${response.status.value}). Switching to WebView...")
            return ""
        }
        return body
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun fetchHtmlWithWebView(context: Context, url: String): String = withContext(Dispatchers.Main) {
        Log.d(TAG, "Starting WebView fallback for $url")
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

                            Log.d(TAG, "WebView poll #$attempt -> title='$title', htmlLen=${html.length}")

                            if (html.isNotBlank() && !isBotChallenge(html) && !title.contains("Just a moment", ignoreCase = true)) {
                                finished = true
                                cont.resume(html)
                            } else if (attempt < 20) {
                                webView.postDelayed({ pollDom(attempt + 1) }, 600L)
                            } else {
                                finished = true
                                Log.w(TAG, "WebView timed out on challenge page")
                                cont.resume("")
                            }
                        }
                    }

                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            Log.d(TAG, "WebView onPageFinished: $finishedUrl")
                            pollDom(1)
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) {
                            if (request?.isForMainFrame == true) {
                                Log.e(TAG, "WebView main frame error: ${error?.description}")
                            }
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

    private fun extractOpenGraph(html: String, url: String): ScrapedMediaDraft {
        if (html.isBlank()) return ScrapedMediaDraft(sourceUrl = url)

        fun meta(property: String): String? {
            val patterns = listOf(
                """<meta[^>]+(?:property|name)=["']$property["'][^>]+content=["']([^"']+)["']""",
                """<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["']$property["']"""
            )
            return patterns.firstNotNullOfOrNull { pattern ->
                Regex(pattern, RegexOption.IGNORE_CASE).find(html)?.groupValues?.getOrNull(1)?.trim()
            }
        }

        val title = meta("og:title")
            ?: meta("twitter:title")
            ?: Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1)?.trim()
            ?: ""

        val cover = meta("og:image") ?: meta("twitter:image")
        return ScrapedMediaDraft(
            title = decodeHtmlEntities(title),
            coverUrl = cover,
            sourceUrl = url
        )
    }

    private fun extractTitleFromSlug(url: String): String {
        val pathSegment = runCatching { URI(url).path }
            .getOrDefault("")
            .trimEnd('/')
            .substringAfterLast('/')
            .substringBefore('.')

        return pathSegment
            .replace('-', ' ')
            .replace('_', ' ')
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
    }

    private fun formatMalAuthorName(raw: String): String {
        return if (raw.contains(",")) {
            val parts = raw.split(",").map { it.trim() }
            if (parts.size == 2) "${parts[1]} ${parts[0]}" else raw
        } else raw
    }

    private fun decodeHtmlEntities(input: String): String {
        return input
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }
}