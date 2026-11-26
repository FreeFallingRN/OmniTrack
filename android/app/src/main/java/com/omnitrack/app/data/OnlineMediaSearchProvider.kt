package com.omnitrack.app.data

import com.omnitrack.app.domain.model.MediaType
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URI

data class OnlineSearchResult(
    val sourceName: String,
    val title: String,
    val mediaType: MediaType?,
    val status: String?,
    val chapters: Double?,
    val coverUrl: String?,
    val upstreamUrl: String
)

interface OnlineMediaSearchProvider {
    val sourceName: String
    suspend fun search(title: String): List<OnlineSearchResult>
}

private val searchJson = Json { ignoreUnknownKeys = true }

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

class MangaBakaSearchProvider : OnlineMediaSearchProvider {
    override val sourceName = "MangaBaka"

    override suspend fun search(title: String): List<OnlineSearchResult> {
        val response = MediaHttpClient.client.get("https://api.mangabaka.org/v1/series/search") {
            parameter("q", title)
            parameter("limit", 20)
        }
        check(response.status.isSuccess()) { "MangaBaka search failed (HTTP ${response.status.value})" }
        val data = searchJson.parseToJsonElement(response.bodyAsText()).jsonObject["data"]?.jsonArray
            ?: error("MangaBaka returned an invalid search response")
        return data.map { element ->
            val item = element.jsonObject
            val id = item.text("id")?.toLongOrNull()?.takeIf { it in 1..2147483647L }
                ?: error("MangaBaka returned an invalid series ID")
            val titles = (item["titles"] as? JsonArray).orEmpty().mapNotNull { value ->
                val obj = value as? JsonObject ?: return@mapNotNull null
                val name = obj.text("title") ?: return@mapNotNull null
                MangaBakaSeriesTitle(name, obj.text("language").orEmpty(), obj.text("is_primary") == "true")
            }.distinctBy { it.title.lowercase() }
            val defaultTitle = item.text("title") ?: titles.firstOrNull()?.title
                ?: error("MangaBaka returned a series without a title")
            val name = mangaBakaImportTitles(titles, defaultTitle).first
            val cover = item["cover"] as? JsonObject
            OnlineSearchResult(
                sourceName = sourceName,
                title = name,
                mediaType = when (item.text("type")) {
                    "novel" -> MediaType.NOVEL
                    "manga", "manhwa", "manhua", "oel" -> MediaType.COMIC
                    else -> null
                },
                status = item.text("status"),
                chapters = item.text("total_chapters")?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 },
                coverUrl = (cover?.get("x250") as? JsonObject)?.text("x1")
                    ?: (cover?.get("raw") as? JsonObject)?.text("url"),
                upstreamUrl = "https://mangabaka.org/series/$id"
            )
        }
    }
}

class AniListSearchProvider : OnlineMediaSearchProvider {
    override val sourceName = "AniList"

    override suspend fun search(title: String): List<OnlineSearchResult> {
        val query = """
            query (${'$'}search: String) {
              Page(page: 1, perPage: 20) {
                media(search: ${'$'}search) {
                  id type format status chapters siteUrl
                  title { english romaji native }
                  coverImage { large medium }
                }
              }
            }
        """.trimIndent()
        val payload = buildJsonObject {
            put("query", query)
            put("variables", buildJsonObject { put("search", title) })
        }
        val response = MediaHttpClient.client.post("https://graphql.anilist.co") {
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }
        check(response.status.isSuccess()) { "AniList search failed (HTTP ${response.status.value})" }
        val root = searchJson.parseToJsonElement(response.bodyAsText()).jsonObject
        val errors = root["errors"] as? JsonArray
        check(errors.isNullOrEmpty()) { "AniList search returned an error" }
        val data = (root["data"] as? JsonObject)?.get("Page") as? JsonObject
            ?: error("AniList returned an invalid search response")
        val media = data["media"]?.jsonArray ?: error("AniList returned an invalid search response")
        return media.map { element ->
            val item = element.jsonObject
            val id = item.text("id")?.toIntOrNull()?.takeIf { it > 0 }
                ?: error("AniList returned an invalid media ID")
            val type = when {
                item.text("format") == "NOVEL" -> MediaType.NOVEL
                item.text("type") == "MANGA" -> MediaType.COMIC
                item.text("format") == "MOVIE" -> MediaType.MOVIE
                item.text("type") == "ANIME" -> MediaType.ANIME
                else -> error("AniList returned an unsupported media type")
            }
            val titles = item["title"] as? JsonObject ?: error("AniList returned a title without a name")
            val name = titles.text("english") ?: titles.text("romaji") ?: titles.text("native")
                ?: error("AniList returned a title without a name")
            val url = item.text("siteUrl") ?: error("AniList returned a title without a URL")
            val uri = URI(url)
            val pathType = if (item.text("type") == "MANGA") "manga" else "anime"
            val basePath = "/$pathType/$id"
            check(uri.scheme == "https" && uri.host == "anilist.co" && uri.port == -1 &&
                uri.userInfo == null && (uri.path == basePath || uri.path.orEmpty().startsWith("$basePath/"))) {
                "AniList returned an invalid media URL"
            }
            val cover = item["coverImage"] as? JsonObject
            OnlineSearchResult(
                sourceName = sourceName,
                title = name,
                mediaType = type,
                status = item.text("status"),
                chapters = item.text("chapters")?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 },
                coverUrl = cover?.text("large") ?: cover?.text("medium"),
                upstreamUrl = url
            )
        }
    }
}
