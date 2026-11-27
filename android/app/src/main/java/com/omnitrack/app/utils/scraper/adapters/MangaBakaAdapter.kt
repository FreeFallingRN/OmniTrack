package com.omnitrack.app.utils.scraper.adapters

import com.omnitrack.app.data.MangaBakaSeriesTitle
import com.omnitrack.app.data.mangaBakaImportTitles
import com.omnitrack.app.domain.model.MediaType
import com.omnitrack.app.utils.ScrapedCreator
import com.omnitrack.app.utils.ScrapedMediaDraft
import com.omnitrack.app.utils.scraper.HtmlFetchClient
import com.omnitrack.app.utils.scraper.MediaSourceAdapter
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

object MangaBakaAdapter : MediaSourceAdapter {
    override val name: String = "MangaBaka"
    private val json = Json { ignoreUnknownKeys = true }
    private val seriesPath = Regex(
        "^/(?:(?:series|manga|manhwa|manhua|novel|oel|other)/)?([0-9]+)(?:/[^/]+)?/?$",
        RegexOption.IGNORE_CASE
    )

    fun seriesId(url: String): Long? {
        val uri = try {
            URI(url)
        } catch (_: java.net.URISyntaxException) {
            return null
        }
        if (uri.scheme?.lowercase() !in listOf("http", "https") ||
            uri.host?.lowercase() !in listOf("mangabaka.org", "www.mangabaka.org", "mangabaka.dev", "dev.mangabaka.dev") ||
            uri.userInfo != null || uri.port != -1) return null
        val id = seriesPath.matchEntire(uri.path.orEmpty())?.groupValues?.get(1) ?: return null
        return id.toLongOrNull()?.takeIf { it in 1..2147483647L }
    }

    override fun canHandle(host: String, url: String): Boolean =
        host.lowercase() in listOf("mangabaka.org", "www.mangabaka.org", "mangabaka.dev", "dev.mangabaka.dev") && seriesId(url) != null

    override suspend fun scrapeWithoutHtml(url: String, fetcher: HtmlFetchClient): ScrapedMediaDraft? =
        scrapeSeries(url, fetcher)

    suspend fun scrapeSeries(
        url: String,
        fetcher: HtmlFetchClient,
        otherType: MediaType? = null
    ): ScrapedMediaDraft? {
        var id = seriesId(url) ?: return null
        fun text(obj: JsonObject, key: String): String? =
            (obj[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

        val visited = mutableSetOf<Long>()
        var data: JsonObject
        while (true) {
            check(visited.size < 10 && visited.add(id)) { "Invalid MangaBaka series merge chain" }
            val response = json.parseToJsonElement(
                fetcher.getMangaBakaJson("https://api.mangabaka.org/v1/series/$id")
            ) as? JsonObject ?: error("Invalid MangaBaka response")
            data = response["data"] as? JsonObject ?: error("Missing MangaBaka series data")
            check(text(data, "id")?.toLongOrNull() == id) { "MangaBaka series ID does not match" }
            when (text(data, "state")) {
                "active" -> break
                "merged" -> id = text(data, "merged_with")?.toLongOrNull()
                    ?.takeIf { it in 1..2147483647L }
                    ?: error("Invalid MangaBaka merge target")
                else -> error("MangaBaka series is unavailable")
            }
        }
        val canonicalUrl = text(data, "canonical_url")?.takeIf {
            it.startsWith("https://mangabaka.org/") && seriesId(it) == id &&
                URI(it).rawQuery == null && URI(it).rawFragment == null
        } ?: "https://mangabaka.org/series/$id"

        val titles = (data["titles"] as? JsonArray).orEmpty().mapNotNull { value ->
            val obj = value as? JsonObject ?: return@mapNotNull null
            MangaBakaSeriesTitle(
                title = text(obj, "title") ?: return@mapNotNull null,
                language = text(obj, "language").orEmpty(),
                primary = (obj["is_primary"] as? JsonPrimitive)?.booleanOrNull
            )
        }
        val defaultTitle = text(data, "title") ?: titles.firstOrNull()?.title
            ?: error("Missing MangaBaka series title")
        val (title, alternativeTitles) = mangaBakaImportTitles(titles, defaultTitle)
        val creators = buildList {
            for ((field, role) in listOf(
                "authors" to "author", "artists" to "illustrator",
                "translators" to "translator", "creators" to "author"
            )) {
                (data[field] as? JsonArray).orEmpty().forEach { value ->
                    val creator = when (value) {
                        is JsonPrimitive -> value.contentOrNull?.trim()
                        is JsonObject -> text(value, "name")
                        else -> null
                    }
                    if (!creator.isNullOrEmpty()) add(ScrapedCreator(creator, role))
                }
            }
        }.distinctBy { "${it.name.lowercase()}::${it.role}" }
        val cover = data["cover"] as? JsonObject
        val coverUrl = (cover?.get("x350") as? JsonObject)?.let { text(it, "x1") }
            ?: (cover?.get("x250") as? JsonObject)?.let { text(it, "x1") }
            ?: (cover?.get("raw") as? JsonObject)?.let { text(it, "url") }
        val status = when (text(data, "status")?.lowercase()) {
            "upcoming" -> "announced"
            "completed" -> "completed"
            "hiatus" -> "hiatus"
            "cancelled" -> "discontinued"
            "releasing" -> "releasing"
            else -> ""
        }
        val totalChapters = text(data, "total_chapters")?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it >= 0 }

        return ScrapedMediaDraft(
            title = title,
            alternativeTitles = alternativeTitles,
            type = when (text(data, "type")?.lowercase()) {
                "novel" -> MediaType.NOVEL
                "manga", "manhwa", "manhua", "oel" -> MediaType.COMIC
                "other" -> otherType ?: error("Choose whether this title is a novel or comic")
                else -> error("Unsupported MangaBaka media type: ${text(data, "type")}")
            },
            creators = creators,
            platformHint = name,
            publishingStatus = status,
            unitName = "Chapter",
            coverUrl = coverUrl,
            sourceUrl = canonicalUrl,
            description = text(data, "description"),
            totalChapters = totalChapters
        )
    }
}
