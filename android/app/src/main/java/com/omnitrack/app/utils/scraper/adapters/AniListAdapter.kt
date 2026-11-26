package com.omnitrack.app.utils.scraper.adapters

import com.omnitrack.app.domain.model.MediaType
import com.omnitrack.app.utils.ScrapedCreator
import com.omnitrack.app.utils.ScrapedMediaDraft
import com.omnitrack.app.utils.scraper.HtmlFetchClient
import com.omnitrack.app.utils.scraper.HtmlMetadataParser
import com.omnitrack.app.utils.scraper.MediaSourceAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object AniListAdapter : MediaSourceAdapter {
    override val name: String = "AniList"
    private val json = Json { ignoreUnknownKeys = true }

    override fun canHandle(host: String, url: String): Boolean =
        host.contains("anilist.co", ignoreCase = true)

    override suspend fun scrapeWithoutHtml(
        url: String,
        fetcher: HtmlFetchClient
    ): ScrapedMediaDraft? {
        val isManga = url.contains("/manga/", ignoreCase = true)
        val anilistId = Regex("""/(anime|manga)/(\d+)""").find(url)
            ?.groupValues?.getOrNull(2)?.toIntOrNull() ?: return null

        return queryAniList(
            id = anilistId,
            idMal = null,
            isManga = isManga,
            platformHint = "AniList",
            sourceUrl = url
        )
    }

    internal suspend fun queryAniList(
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
                chapters
                title { english romaji native }
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
        val title = english ?: romaji ?: native.orEmpty()
        val altTitles = emptyList<String>()

        val coverObj = media["coverImage"]?.jsonObject
        val coverUrl = coverObj?.get("extraLarge")?.jsonPrimitive?.content?.takeIf { it != "null" }
            ?: coverObj?.get("large")?.jsonPrimitive?.content?.takeIf { it != "null" }

        val pubStatus = when (media["status"]?.jsonPrimitive?.content) {
            "FINISHED" -> "completed"
            "NOT_YET_RELEASED" -> "announced"
            "HIATUS" -> "hiatus"
            "CANCELLED" -> "discontinued"
            "RELEASING" -> "releasing"
            else -> ""
        }

        val creators = buildList {
            if (!isManga) {
                media["studios"]?.jsonObject?.get("nodes")?.jsonArray.orEmpty().forEach { node ->
                    val creatorName = node.jsonObject["name"]?.jsonPrimitive?.content?.trim().orEmpty()
                    if (creatorName.isNotBlank()) add(ScrapedCreator(name = creatorName, role = "studio"))
                }
            }

            val authorRolesMap = mutableMapOf<String, MutableSet<String>>()
            media["staff"]?.jsonObject?.get("edges")?.jsonArray.orEmpty().forEach { edge ->
                val roleRaw = edge.jsonObject["role"]?.jsonPrimitive?.content.orEmpty().lowercase()
                val creatorName = edge.jsonObject["node"]?.jsonObject?.get("name")?.jsonObject
                    ?.get("full")?.jsonPrimitive?.content?.trim().orEmpty()
                if (creatorName.isBlank()) return@forEach

                val roles = authorRolesMap.getOrPut(creatorName) { mutableSetOf() }
                when {
                    roleRaw.contains("art") || roleRaw.contains("illustrat") ||
                            roleRaw.contains("mangaka") || roleRaw.contains("manga") ||
                            roleRaw.contains("character design") || roleRaw.contains("storyboard") -> {
                        roles.add("illustrator")
                    }
                    roleRaw.contains("director") && !isManga -> roles.add("director")
                    else -> roles.add("author")
                }
            }

            authorRolesMap.forEach { (creatorName, roles) ->
                val role = if (roles.contains("illustrator")) "illustrator" else "author"
                add(ScrapedCreator(creatorName, role))
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
            sourceUrl = sourceUrl,
            totalChapters = media["chapters"]?.jsonPrimitive?.content?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it >= 0 }
        )
    }

    override suspend fun scrapeFromHtml(url: String, html: String): ScrapedMediaDraft =
        HtmlMetadataParser.extractGenericMetadata(html, url).copy(platformHint = "AniList")
}