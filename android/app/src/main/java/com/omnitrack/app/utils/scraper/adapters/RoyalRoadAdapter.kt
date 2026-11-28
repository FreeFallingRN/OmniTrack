package com.omnitrack.app.utils.scraper.adapters

import com.omnitrack.app.data.AppLogger
import com.omnitrack.app.domain.model.MediaType
import com.omnitrack.app.utils.ScrapedCreator
import com.omnitrack.app.utils.ScrapedMediaDraft
import com.omnitrack.app.utils.scraper.HtmlMetadataParser
import com.omnitrack.app.utils.scraper.MediaSourceAdapter
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object RoyalRoadAdapter : MediaSourceAdapter {
    override val name: String = "RoyalRoad"
    private val json = Json { ignoreUnknownKeys = true }
    private val profileLink = Regex(
        """<a\b[^>]*\bhref\s*=\s*["'](?:https?://(?:www\.)?royalroad\.com)?/(?:user|profile)/[^"']+["'][^>]*>([\s\S]*?)</a\s*>""",
        RegexOption.IGNORE_CASE
    )

    override fun canHandle(host: String, url: String): Boolean =
        host.contains("royalroad.com", ignoreCase = true)

    override suspend fun scrapeFromHtml(url: String, html: String): ScrapedMediaDraft {
        val generic = HtmlMetadataParser.extractGenericMetadata(html, url)
        val title = generic.title
            .substringBefore(" | Royal Road")
            .trim()
            .ifBlank { HtmlMetadataParser.extractTitleFromSlug(url) }

        val header = Regex(
            """class\s*=\s*["'][^"']*\bfic-header\b[^"']*["'][^>]*>[\s\S]*?<h4\b[^>]*>([\s\S]*?)</h4\s*>""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1)
        val byline = Regex(
            """<h[2-6]\b[^>]*>\s*(?:<[^>]+>\s*)*by\s+([\s\S]*?)</h[2-6]\s*>""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1)
        val author = listOfNotNull(header, byline).firstNotNullOfOrNull { section ->
            profileLink.find(section)?.groupValues?.get(1)?.let(::cleanAuthor)
        } ?: header?.let(::cleanAuthor)?.let { text ->
            Regex("""^by\s+(.+)$""", RegexOption.IGNORE_CASE).matchEntire(text)?.groupValues?.get(1)
                ?.let(::cleanAuthor)
        } ?: byline?.let(::cleanAuthor)
            ?: Regex("""Author card for ([^<]+)</div\s*>""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.let(::cleanAuthor)
            ?: Regex(
                """<a\b[^>]*\bhref\s*=\s*["'](?:https?://(?:www\.)?royalroad\.com)?/(?:user|profile)/[^"']+["'][^>]*>\s*<h4\b[^>]*>([\s\S]*?)</h4\s*>""",
                RegexOption.IGNORE_CASE
            ).find(html)?.groupValues?.get(1)?.let(::cleanAuthor)

        val pubStatus = HtmlMetadataParser.labeledStatus(html).ifBlank {
            Regex(
                """>\s*(ongoing|completed|hiatus|inactive|stub|dropped)\s*<""",
                RegexOption.IGNORE_CASE
            ).findAll(html).firstNotNullOfOrNull { match ->
                val status = match.groupValues[1]
                if (status.equals("stub", ignoreCase = true)) null
                else HtmlMetadataParser.publishingStatus(status).takeIf { it.isNotBlank() }
            }.orEmpty()
        }

        val creators = author?.let { listOf(ScrapedCreator(it, "author")) }
            ?: extractJsonLdCreators(html).ifEmpty { generic.creators }
                .mapNotNull { creator -> cleanAuthor(creator.name)?.let { ScrapedCreator(it, "author") } }
                .distinctBy { it.name.lowercase() }

        return generic.copy(
            title = title,
            type = MediaType.NOVEL,
            creators = creators,
            platformHint = "Royal Road",
            publishingStatus = pubStatus,
            unitName = "Chapter",
            sourceUrl = url
        )
    }

    private fun cleanAuthor(value: String): String? =
        HtmlMetadataParser.decodeHtmlEntities(value.replace(Regex("<[^>]+>"), " "))
            .replace(Regex("\\s+"), " ")
            .trim()
            .takeIf { it.isNotBlank() }

    private fun extractJsonLdCreators(html: String): List<ScrapedCreator> {
        val scripts = Regex(
            """<script\b[^>]*\btype\s*=\s*["']application/ld\+json["'][^>]*>([\s\S]*?)</script\s*>""",
            RegexOption.IGNORE_CASE
        )
        return scripts.findAll(html).firstNotNullOfOrNull { match ->
            val root = try {
                json.parseToJsonElement(match.groupValues[1].trim())
            } catch (e: SerializationException) {
                AppLogger.warn("Could not parse Royal Road JSON-LD", e)
                return@firstNotNullOfOrNull null
            }
            findJsonLdCreators(root).takeIf { it.isNotEmpty() }
        }.orEmpty()
    }

    private fun findJsonLdCreators(element: JsonElement): List<ScrapedCreator> = when (element) {
        is JsonArray -> element.firstNotNullOfOrNull { findJsonLdCreators(it).takeIf { creators -> creators.isNotEmpty() } }
            .orEmpty()
        is JsonObject -> {
            val nested = listOfNotNull(element["@graph"], element["mainEntity"])
                .firstNotNullOfOrNull { findJsonLdCreators(it).takeIf { creators -> creators.isNotEmpty() } }
            val types = when (val type = element["@type"]) {
                is JsonPrimitive -> listOf(type.content)
                is JsonArray -> type.filterIsInstance<JsonPrimitive>().map { it.content }
                else -> emptyList()
            }
            nested ?: if (types.any {
                    it.substringAfterLast('/').lowercase() in
                        setOf("book", "bookseries", "novel", "creativework", "series", "webpage", "article")
                }) {
                listOfNotNull(element["author"], element["creator"])
                    .flatMap(::jsonLdNames)
                    .mapNotNull { cleanAuthor(it)?.let { name -> ScrapedCreator(name, "author") } }
                    .distinctBy { it.name.lowercase() }
            } else emptyList()
        }
        else -> emptyList()
    }

    private fun jsonLdNames(element: JsonElement): List<String> = when (element) {
        is JsonPrimitive -> if (element.isString) listOf(element.content) else emptyList()
        is JsonObject -> element["name"]?.let(::jsonLdNames).orEmpty()
        is JsonArray -> element.flatMap(::jsonLdNames)
    }
}