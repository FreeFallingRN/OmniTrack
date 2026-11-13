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
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

data class ScrapedCreator(
    val name: String,
    val role: String = "author" // "author", "illustrator", "studio", "director"
)

data class ScrapedMediaDraft(
    val title: String = "",
    val alternativeTitles: List<String> = emptyList(),
    val type: MediaType = MediaType.NOVEL,
    val creators: List<ScrapedCreator> = emptyList(),
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

        val html = fetchHtmlWithFallback(context, url)
        val generic = extractOpenGraph(html, url)

        val cleanTitle = generic.title
            .substringBefore(" - MyAnimeList.net")
            .trim()
            .ifBlank { extractTitleFromSlug(url) }

        val cdnPoster = Regex("""https://cdn\.myanimelist\.net/images/(?:anime|manga)/\d+/\d+\.(?:jpg|webp)""", RegexOption.IGNORE_CASE)
            .find(html)?.value
            ?: generic.coverUrl?.takeUnless { it.contains("mal-logo", ignoreCase = true) || it.contains("icon", ignoreCase = true) }

        val creators = if (isManga) {
            val authorSection = Regex("""Authors?:</span>([\s\S]{0,800}?)</div>""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1).orEmpty()

            val authorMatches = Regex("""<a[^>]*href="/people/\d+/[^"]*"[^>]*>([^<]+)</a>\s*(?:\(([^)]+)\))?""", RegexOption.IGNORE_CASE)
                .findAll(authorSection)

            val authorRolesMap = mutableMapOf<String, MutableSet<String>>()
            for (match in authorMatches) {
                val rawName = match.groupValues.getOrNull(1)?.trim() ?: continue
                val name = formatMalAuthorName(decodeHtmlEntities(rawName))
                if (name.isBlank()) continue

                val roleParen = match.groupValues.getOrNull(2)?.lowercase().orEmpty()
                val roles = authorRolesMap.getOrPut(name) { mutableSetOf() }
                when {
                    roleParen.contains("art") || roleParen.contains("illustrat") || roleParen.contains("design") -> {
                        roles.add("illustrator")
                    }
                    roleParen.contains("story") -> {
                        roles.add("author")
                    }
                    else -> {
                        roles.add("author")
                    }
                }
            }

            buildList {
                authorRolesMap.forEach { (name, roles) ->
                    if (roles.contains("author") && roles.contains("illustrator")) {
                        add(ScrapedCreator(name, "author"))
                        add(ScrapedCreator(name, "illustrator"))
                    } else if (roles.contains("illustrator")) {
                        add(ScrapedCreator(name, "illustrator"))
                    } else {
                        add(ScrapedCreator(name, "author"))
                    }
                }
            }
        } else {
            val studioSection = Regex("""Studios?:</span>([\s\S]{0,600}?)</div>""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1).orEmpty()
            Regex("""<a[^>]*href="/anime/producer/\d+/[^"]*"[^>]*>([^<]+)</a>""", RegexOption.IGNORE_CASE)
                .findAll(studioSection)
                .mapNotNull { match ->
                    val name = match.groupValues.getOrNull(1)?.trim()?.let(::decodeHtmlEntities)
                    name?.takeIf { it.isNotBlank() && !it.equals("None found", ignoreCase = true) }
                        ?.let { ScrapedCreator(it, "studio") }
                }
                .toList()
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
            creators = creators.distinctBy { "${it.name.lowercase()}::${it.role}" },
            platformHint = "MyAnimeList",
            publishingStatus = pubStatus,
            unitName = if (mediaType == MediaType.ANIME) "Episode" else "Chapter",
            coverUrl = cdnPoster,
            sourceUrl = url
        )
        Log.d(TAG, "Scraped MAL HTML draft: $draft")
        return draft
    }

    // 2. AniList Direct URL (anilist.co/anime/{id} or /manga/{id})
    private suspend fun scrapeAniList(context: Context, url: String): ScrapedMediaDraft {
        val isManga = url.contains("/manga/")
        val idMatch = Regex("""/(anime|manga)/(\d+)""").find(url)
        val anilistId = idMatch?.groupValues?.getOrNull(2)?.toIntOrNull()

        if (anilistId != null) {
            val draft = runCatching {
                fetchFromAniList(id = anilistId, isManga = isManga, platformHint = "AniList", sourceUrl = url)
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
                title { english romaji native }
                synonyms
                coverImage { extraLarge large }
                studios(isMain: true) { nodes { name } }
                staff(perPage: 12) {
                  edges {
                    role
                    node { name { full } }
                  }
                }
              }
            }
        """.trimIndent().replace("\n", " ")

        val idField = if (id != null) "\"id\":$id" else "\"idMal\":$idMal"
        val payload = """{"query":${JSONObject.quote(query)},"variables":{$idField,"type":"$typeEnum"}}"""

        val conn = (URL("https://graphql.anilist.co").openConnection() as HttpURLConnection).apply {
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
        val english = titleObj?.get("english")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it != "null" }
        val romaji = titleObj?.get("romaji")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it != "null" }
        val native = titleObj?.get("native")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it != "null" }
        val synonyms = media["synonyms"]?.jsonArray?.mapNotNull {
            it.jsonPrimitive.content.takeIf { s -> s.isNotBlank() && s != "null" }
        }.orEmpty()

        val title = english ?: romaji ?: native.orEmpty()
        val altTitles = (listOfNotNull(english, romaji, native) + synonyms)
            .filter { !it.equals(title, ignoreCase = true) }
            .distinctBy { it.lowercase() }

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

        val creators = buildList {
            if (!isManga) {
                media["studios"]?.jsonObject?.get("nodes")?.jsonArray.orEmpty().forEach { node ->
                    val name = node.jsonObject["name"]?.jsonPrimitive?.content?.trim().orEmpty()
                    if (name.isNotBlank()) add(ScrapedCreator(name = name, role = "studio"))
                }
            }

            val authorRolesMap = mutableMapOf<String, MutableSet<String>>()
            media["staff"]?.jsonObject?.get("edges")?.jsonArray.orEmpty().forEach { edge ->
                val roleRaw = edge.jsonObject["role"]?.jsonPrimitive?.content.orEmpty().lowercase()
                val name = edge.jsonObject["node"]?.jsonObject?.get("name")?.jsonObject
                    ?.get("full")?.jsonPrimitive?.content?.trim().orEmpty()
                if (name.isBlank()) return@forEach

                val roles = authorRolesMap.getOrPut(name) { mutableSetOf() }
                when {
                    roleRaw.contains("story") && (roleRaw.contains("art") || roleRaw.contains("mangaka") || roleRaw.contains("manga")) -> {
                        roles.add("illustrator")
                    }
                    roleRaw.contains("art") || roleRaw.contains("illustrat") || roleRaw.contains("mangaka") || roleRaw.contains("manga") || roleRaw.contains("character design") || roleRaw.contains("storyboard") -> {
                        roles.add("illustrator")
                    }
                    roleRaw.contains("story") || roleRaw.contains("original creator") || roleRaw.contains("original work") -> {
                        roles.add("author")
                    }
                    roleRaw.contains("director") && !isManga -> {
                        roles.add("director")
                    }
                    else -> {
                        roles.add("author")
                    }
                }
            }

            authorRolesMap.forEach { (name, roles) ->
                val role = if (roles.contains("illustrator")) "illustrator" else "author"
                add(ScrapedCreator(name, role))
            }
        }.distinctBy { "${it.name.lowercase()}::${it.role}" }

        val mediaType = when (media["format"]?.jsonPrimitive?.content) {
            "MOVIE" -> MediaType.MOVIE
            else -> if (isManga) MediaType.COMIC else MediaType.ANIME
        }

        ScrapedMediaDraft(
            title = title,
            alternativeTitles = altTitles,
            type = mediaType,
            creators = creators,
            platformHint = platformHint,
            publishingStatus = pubStatus,
            unitName = if (mediaType == MediaType.ANIME) "Episode" else "Chapter",
            coverUrl = coverUrl,
            sourceUrl = sourceUrl
        )
    }

    // 3. MangaDex Public REST API (mangadex.org/title/{uuid}/...)
    private suspend fun scrapeMangaDex(context: Context, url: String): ScrapedMediaDraft {
        val uuidMatch = Regex("""/title/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})""").find(url)
        val mangaId = uuidMatch?.groupValues?.getOrNull(1)

        if (mangaId != null) {
            val draft = runCatching {
                val apiUrl = "https://api.mangadex.org/manga/$mangaId?includes[]=author&includes[]=artist&includes[]=cover_art"
                Log.d(TAG, "Fetching MangaDex API: $apiUrl")
                val responseText = httpGetJson(apiUrl)
                val data = json.parseToJsonElement(responseText).jsonObject["data"]?.jsonObject
                    ?: return@runCatching null
                val attrs = data["attributes"]?.jsonObject ?: return@runCatching null

                val titleObj = attrs["title"]?.jsonObject
                val title = titleObj?.get("en")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: titleObj?.values?.firstOrNull()?.jsonPrimitive?.content.orEmpty()

                val altTitles = buildList {
                    titleObj?.values?.forEach { elem ->
                        val t = elem.jsonPrimitive.content.trim()
                        if (t.isNotBlank() && !t.equals(title, ignoreCase = true)) add(t)
                    }
                    attrs["altTitles"]?.jsonArray.orEmpty().forEach { mapElem ->
                        mapElem.jsonObject.values.forEach { v ->
                            val t = v.jsonPrimitive.content.trim()
                            if (t.isNotBlank() && !t.equals(title, ignoreCase = true)) add(t)
                        }
                    }
                }.distinctBy { it.lowercase() }

                val pubStatus = when (attrs["status"]?.jsonPrimitive?.content?.lowercase()) {
                    "completed" -> "completed"
                    "hiatus" -> "hiatus"
                    "cancelled" -> "discontinued"
                    else -> "releasing"
                }

                val relationships = data["relationships"]?.jsonArray.orEmpty()
                val creators = relationships.mapNotNull { rel ->
                    val relType = rel.jsonObject["type"]?.jsonPrimitive?.content
                    val name = rel.jsonObject["attributes"]?.jsonObject?.get("name")?.jsonPrimitive?.content?.trim()
                    if (name.isNullOrBlank()) null
                    else when (relType) {
                        "author" -> ScrapedCreator(name, "author")
                        "artist" -> ScrapedCreator(name, "illustrator")
                        else -> null
                    }
                }.distinctBy { "${it.name.lowercase()}::${it.role}" }

                val coverFileName = relationships.firstOrNull {
                    it.jsonObject["type"]?.jsonPrimitive?.content == "cover_art"
                }?.jsonObject?.get("attributes")?.jsonObject?.get("fileName")?.jsonPrimitive?.content

                val coverUrl = coverFileName?.let { "https://uploads.mangadex.org/covers/$mangaId/$it" }

                ScrapedMediaDraft(
                    title = title,
                    alternativeTitles = altTitles,
                    type = MediaType.COMIC,
                    creators = creators,
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
                val altTitles = root["associated"]?.jsonArray.orEmpty()
                    .mapNotNull { it.jsonObject["title"]?.jsonPrimitive?.content?.let(::decodeHtmlEntities)?.trim() }
                    .filter { it.isNotBlank() && !it.equals(title, ignoreCase = true) }
                    .distinctBy { it.lowercase() }

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

                val creators = root["authors"]?.jsonArray.orEmpty().mapNotNull { item ->
                    val name = item.jsonObject["name"]?.jsonPrimitive?.content?.trim()
                    val roleType = item.jsonObject["type"]?.jsonPrimitive?.content.orEmpty().lowercase()
                    if (name.isNullOrBlank()) null
                    else ScrapedCreator(
                        name = name,
                        role = if (roleType.contains("artist") || roleType.contains("illustrat")) "illustrator" else "author"
                    )
                }.distinctBy { "${it.name.lowercase()}::${it.role}" }

                val typeRaw = root["type"]?.jsonPrimitive?.content.orEmpty().lowercase()
                val mediaType = if (typeRaw.contains("novel")) MediaType.NOVEL else MediaType.COMIC

                ScrapedMediaDraft(
                    title = title,
                    alternativeTitles = altTitles,
                    type = mediaType,
                    creators = creators,
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

    // 5. Royal Road HTML Parser
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

        val creators = listOfNotNull(
            author?.let { decodeHtmlEntities(it).trim() }?.takeIf { it.isNotBlank() }?.let { ScrapedCreator(it, "author") }
        )

        val draft = ScrapedMediaDraft(
            title = rawTitle,
            type = MediaType.NOVEL,
            creators = creators,
            platformHint = "Royal Road",
            publishingStatus = status,
            unitName = "Chapter",
            coverUrl = generic.coverUrl,
            sourceUrl = url
        )
        Log.d(TAG, "Scraped Royal Road draft: $draft")
        return draft
    }

    // 6. Generic Fallback for Any Other Site
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

    private suspend fun httpGetJson(url: String): String = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
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