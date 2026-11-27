package com.omnitrack.app.utils.scraper

import android.text.Html
import com.omnitrack.app.domain.model.MediaType
import com.omnitrack.app.utils.ScrapedCreator
import com.omnitrack.app.utils.ScrapedMediaDraft
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

object HtmlMetadataParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun extractGenericMetadata(html: String, url: String): ScrapedMediaDraft {
        require(html.isNotBlank()) { "Could not fetch metadata from this source" }

        val jsonLd = extractJsonLd(html)
        val ogTitle = meta(html, "og:title")
            ?: meta(html, "twitter:title")
            ?: Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1)?.trim()

        val title = decodeHtmlEntities(
            jsonLd?.title?.takeIf { it.isNotBlank() }
                ?: ogTitle.orEmpty()
        ).ifBlank { extractTitleFromSlug(url) }

        val coverUrl = CoverNormalizer.normalize(
            (jsonLd?.coverUrl
                ?: meta(html, "og:image")
                ?: meta(html, "twitter:image")
                ?: extractCoverImageHeuristic(html, url))?.let(::decodeHtmlEntities)
        )

        val creators = jsonLd?.creators.orEmpty().ifEmpty {
            meta(html, "author")
                ?.let(::decodeHtmlEntities)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { listOf(ScrapedCreator(it, "author")) }
                .orEmpty()
        }

        return ScrapedMediaDraft(
            title = title,
            alternativeTitles = jsonLd?.alternativeTitles.orEmpty().map(::decodeHtmlEntities),
            type = jsonLd?.type ?: MediaType.NOVEL,
            creators = creators,
            coverUrl = coverUrl,
            sourceUrl = url,
            description = (meta(html, "og:description") ?: meta(html, "description"))
                ?.takeIf { it.isNotBlank() }
        )
    }

    fun publishingStatus(value: String?): String = when (value?.trim()?.lowercase()) {
        "ongoing", "releasing", "ongoing translation", "in progress" -> "releasing"
        "completed", "complete", "completely translated", "fully translated" -> "completed"
        "hiatus", "on hiatus", "inactive" -> "hiatus"
        "dropped", "cancelled", "canceled", "discontinued" -> "discontinued"
        "upcoming", "announced" -> "announced"
        else -> ""
    }

    fun labeledStatus(html: String): String {
        val patterns = listOf(
            """<(?:td|th|dt)[^>]*>\s*(?:<[^>]+>\s*)*status\s*:?\s*(?:</[^>]+>\s*)*</(?:td|th|dt)>\s*<(?:td|dd)[^>]*>([\s\S]*?)</(?:td|dd)>""",
            """<(?:span|div|p)[^>]*>\s*(?:<[^>]+>\s*)*status\s*:?\s*(?:</[^>]+>\s*)*</(?:span|div|p)>\s*<(?:span|div|p)[^>]*>([\s\S]*?)</(?:span|div|p)>""",
            """\bstatus\s*:\s*(?:<[^>]+>\s*)*([^<\r\n]+)"""
        )
        return patterns.firstNotNullOfOrNull { pattern ->
            Regex(pattern, RegexOption.IGNORE_CASE).findAll(html).firstNotNullOfOrNull { match ->
                val value = decodeHtmlEntities(match.groupValues[1].replace(Regex("<[^>]+>"), " ")).trim()
                publishingStatus(value).takeIf { it.isNotBlank() }
            }
        }.orEmpty()
    }

    fun meta(html: String, property: String): String? {
        val escaped = Regex.escape(property)
        val patterns = listOf(
            """<meta[^>]+(?:property|name)=["']$escaped["'][^>]+content=["']([^"']+)["']""",
            """<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["']$escaped["']"""
        )
        return patterns.firstNotNullOfOrNull { pattern ->
            Regex(pattern, RegexOption.IGNORE_CASE).find(html)?.groupValues?.getOrNull(1)
                ?.let(::decodeHtmlEntities)?.trim()
        }
    }

    fun extractTitleFromSlug(url: String): String {
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

    fun normalizeAmazonUrl(sourceUrl: String): String {
        val trimmed = sourceUrl.trim()
        val input = if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
            trimmed
        } else {
            "https://$trimmed"
        }
        val uri = runCatching { URI(input) }.getOrNull() ?: return trimmed
        if (!uri.host.orEmpty().contains("amazon.", ignoreCase = true)) return trimmed

        val asin = extractAmazonAsin(input)
        if (asin != null) return "${uri.scheme ?: "https"}://${uri.rawAuthority}/dp/$asin"

        return input.substringBefore('#').substringBefore('?')
    }

    fun extractAmazonAsin(url: String): String? =
        Regex("""(?:^|/)(?:dp|gp/product|gp/aw/d|product|exec/obidos/ASIN)/([A-Z0-9]{10})(?:/|$)""", RegexOption.IGNORE_CASE)
            .find(runCatching { URI(url).rawPath.orEmpty() }.getOrDefault(""))
            ?.groupValues
            ?.getOrNull(1)
            ?.uppercase()

    fun extractAmazonTitleSlug(url: String): String? {
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
        val match = Regex("""/(?:dp|gp)/""", RegexOption.IGNORE_CASE).find(path) ?: return null
        val slug = path.substring(0, match.range.first).trimEnd('/').substringAfterLast('/')
        if (slug.isBlank() || slug.equals("product", ignoreCase = true) ||
            slug.equals(extractAmazonAsin(url), ignoreCase = true)
        ) return null
        return slug.replace('-', ' ').replace('_', ' ').trim().takeIf { it.isNotBlank() }
    }

    fun decodeHtmlEntities(input: String): String {
        val entities = Regex("""&(?:#(?:[xX][0-9a-fA-F]+|\d+)|[a-zA-Z][a-zA-Z0-9]+);""")
        var decoded = input
        while (true) {
            val next = entities.replace(decoded) { match ->
                val entity = match.value
                if (entity.startsWith("&#")) {
                    val number = entity.substring(2, entity.length - 1)
                    val codePoint = if (number.startsWith("x", ignoreCase = true)) {
                        number.substring(1).toIntOrNull(16)
                    } else {
                        number.toIntOrNull()
                    }
                    if (codePoint != null && codePoint in 1..0x10FFFF && codePoint !in 0xD800..0xDFFF) {
                        String(Character.toChars(codePoint))
                    } else {
                        entity
                    }
                } else if (entity == "&apos;") {
                    "'"
                } else {
                    Html.fromHtml(entity, Html.FROM_HTML_MODE_LEGACY).toString()
                }
            }
            if (next == decoded) return decoded
            decoded = next
        }
    }

    private fun extractCoverImageHeuristic(html: String, baseUrl: String): String? {
        val imgRegex = Regex(
            """<img[^>]+(?:class|id|alt)=["'][^"']*(?:cover|poster|book-img|thumbnail)[^"']*["'][^>]+src=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        val rawSrc = imgRegex.find(html)?.groupValues?.getOrNull(1)?.trim() ?: return null
        return resolveRelativeUrl(baseUrl, rawSrc)
    }

    private fun resolveRelativeUrl(baseUrl: String, candidate: String): String {
        return runCatching { URI(baseUrl).resolve(candidate).toString() }.getOrDefault(candidate)
    }

    private data class JsonLdResult(
        val title: String?,
        val alternativeTitles: List<String>,
        val type: MediaType?,
        val creators: List<ScrapedCreator>,
        val coverUrl: String?
    )

    private fun extractJsonLd(html: String): JsonLdResult? {
        val scriptRegex = Regex(
            """<script[^>]+type=["']application/ld\+json["'][^>]*>([\s\S]*?)</script>""",
            RegexOption.IGNORE_CASE
        )
        for (match in scriptRegex.findAll(html)) {
            val rawJson = match.groupValues.getOrNull(1)?.trim() ?: continue
            val root = runCatching { json.parseToJsonElement(rawJson) }.getOrNull() ?: continue
            val candidateObj = findMediaJsonObject(root) ?: continue

            val name = candidateObj["name"]?.let(::extractString)
            val altNames = extractStringList(candidateObj["alternateName"])
            val schemaType = candidateObj["@type"]?.let(::extractString)?.lowercase().orEmpty()
            val mappedType = when {
                schemaType.contains("comic") || schemaType.contains("manga") -> MediaType.COMIC
                schemaType.contains("movie") -> MediaType.MOVIE
                schemaType.contains("tv") || schemaType.contains("video") -> MediaType.ANIME
                schemaType.contains("book") || schemaType.contains("novel") -> MediaType.NOVEL
                else -> null
            }

            val creators = extractAuthorsFromJsonLd(candidateObj["author"])
            val imageUrl = extractImageFromJsonLd(candidateObj["image"])

            if (!name.isNullOrBlank() || !imageUrl.isNullOrBlank()) {
                return JsonLdResult(
                    title = name,
                    alternativeTitles = altNames,
                    type = mappedType,
                    creators = creators,
                    coverUrl = imageUrl
                )
            }
        }
        return null
    }

    private fun findMediaJsonObject(element: JsonElement): JsonObject? {
        when (element) {
            is JsonObject -> {
                val typeStr = element["@type"]?.let(::extractString)?.lowercase().orEmpty()
                if (typeStr.contains("book") || typeStr.contains("comic") ||
                    typeStr.contains("series") || typeStr.contains("movie") ||
                    typeStr.contains("creativework")
                ) {
                    return element
                }
                element["@graph"]?.let { graph ->
                    findMediaJsonObject(graph)?.let { return it }
                }
            }
            is JsonArray -> {
                for (item in element) {
                    findMediaJsonObject(item)?.let { return it }
                }
            }
            else -> Unit
        }
        return null
    }

    private fun extractAuthorsFromJsonLd(element: JsonElement?): List<ScrapedCreator> {
        return when (element) {
            is JsonPrimitive -> element.content.trim()
                .takeIf { it.isNotBlank() }
                ?.let { listOf(ScrapedCreator(decodeHtmlEntities(it), "author")) }
                .orEmpty()
            is JsonObject -> element["name"]?.let(::extractString)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { listOf(ScrapedCreator(decodeHtmlEntities(it), "author")) }
                .orEmpty()
            is JsonArray -> element.flatMap { extractAuthorsFromJsonLd(it) }
            else -> emptyList()
        }
    }

    private fun extractImageFromJsonLd(element: JsonElement?): String? {
        return when (element) {
            is JsonPrimitive -> element.content.trim().takeIf { it.isNotBlank() }
            is JsonObject -> element["url"]?.let(::extractString)
            is JsonArray -> element.firstNotNullOfOrNull { extractImageFromJsonLd(it) }
            else -> null
        }
    }

    private fun extractString(element: JsonElement): String? {
        return when (element) {
            is JsonPrimitive -> element.content
            is JsonArray -> element.firstOrNull()?.let(::extractString)
            else -> null
        }
    }

    private fun extractStringList(element: JsonElement?): List<String> {
        return when (element) {
            is JsonPrimitive -> listOfNotNull(element.content.trim().takeIf { it.isNotBlank() })
            is JsonArray -> element.mapNotNull { extractString(it)?.trim()?.takeIf { s -> s.isNotBlank() } }
            else -> emptyList()
        }
    }
}