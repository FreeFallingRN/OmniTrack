package com.omnitrack.app.utils.scraper.adapters

import com.omnitrack.app.domain.model.MediaType
import com.omnitrack.app.utils.ScrapedCreator
import com.omnitrack.app.utils.ScrapedMediaDraft
import com.omnitrack.app.utils.scraper.HtmlFetchClient
import com.omnitrack.app.utils.scraper.MediaSourceAdapter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object MangaDexAdapter : MediaSourceAdapter {
    override val name: String = "MangaDex"
    private val json = Json { ignoreUnknownKeys = true }

    override fun canHandle(host: String, url: String): Boolean =
        host.contains("mangadex.org", ignoreCase = true)

    override suspend fun scrapeWithoutHtml(
        url: String,
        fetcher: HtmlFetchClient
    ): ScrapedMediaDraft? {
        val mangaId = Regex(
            """/title/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})"""
        ).find(url)?.groupValues?.getOrNull(1) ?: return null

        val responseText = fetcher.getJson(
            "https://api.mangadex.org/manga/$mangaId?includes[]=author&includes[]=artist&includes[]=cover_art"
        )
        val data = json.parseToJsonElement(responseText).jsonObject["data"]?.jsonObject ?: return null
        val attrs = data["attributes"]?.jsonObject ?: return null

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
            val creatorName = rel.jsonObject["attributes"]?.jsonObject?.get("name")?.jsonPrimitive?.content?.trim()
            if (creatorName.isNullOrBlank()) null
            else when (relType) {
                "author" -> ScrapedCreator(creatorName, "author")
                "artist" -> ScrapedCreator(creatorName, "illustrator")
                else -> null
            }
        }.distinctBy { "${it.name.lowercase()}::${it.role}" }

        val coverFileName = relationships.firstOrNull {
            it.jsonObject["type"]?.jsonPrimitive?.content == "cover_art"
        }?.jsonObject?.get("attributes")?.jsonObject?.get("fileName")?.jsonPrimitive?.content

        return ScrapedMediaDraft(
            title = title,
            alternativeTitles = altTitles,
            type = MediaType.COMIC,
            creators = creators,
            platformHint = "MangaDex",
            publishingStatus = pubStatus,
            unitName = "Chapter",
            coverUrl = coverFileName?.let { "https://uploads.mangadex.org/covers/$mangaId/$it" },
            sourceUrl = url
        )
    }
}