package com.omnitrack.app.utils.scraper.adapters

import com.omnitrack.app.domain.model.MediaType
import com.omnitrack.app.utils.ScrapedCreator
import com.omnitrack.app.utils.ScrapedMediaDraft
import com.omnitrack.app.utils.scraper.CoverNormalizer
import com.omnitrack.app.utils.scraper.HtmlMetadataParser
import com.omnitrack.app.utils.scraper.MediaSourceAdapter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

object WebnovelAdapter : MediaSourceAdapter {
    override val name: String = "Webnovel"
    private val json = Json { ignoreUnknownKeys = true }

    override fun canHandle(host: String, url: String): Boolean =
        host.contains("webnovel.com", ignoreCase = true)

    override suspend fun scrapeFromHtml(url: String, html: String): ScrapedMediaDraft {
        val generic = HtmlMetadataParser.extractGenericMetadata(html, url)
        val cleanTitle = generic.title
            .substringBefore(" - Webnovel")
            .substringBefore(" | Webnovel")
            .trim()
            .ifBlank { HtmlMetadataParser.extractTitleFromSlug(url) }

        val author = Regex("""Author\s*:?\s*</[^>]+>\s*<[^>]+>([^<]+)</""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)
            ?.let(HtmlMetadataParser::decodeHtmlEntities)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val creators = author?.let { listOf(ScrapedCreator(it, "author")) }
            ?: generic.creators.ifEmpty {
                HtmlMetadataParser.meta(html, "og:author")?.takeIf { it.isNotBlank() }
                    ?.let { listOf(ScrapedCreator(it, "author")) }.orEmpty()
            }

        val bookId = Regex("""/book/(?:[^/?]*_)?(\d+)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)
        val state = Regex(
            """<script\b[^>]*\bid=["']__NEXT_DATA__["'][^>]*>([\s\S]*?)</script>""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1)?.let { raw ->
            runCatching { json.parseToJsonElement(raw) }.getOrNull()
        }
        val book = state?.let { findBook(it, bookId) }
        fun field(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
            (book?.get(name) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        }
        val bodyStart = Regex("""<body\b[^>]*>""", RegexOption.IGNORE_CASE).find(html)?.range?.first ?: 0
        val body = html.substring(bodyStart)
        val reviewsStart = Regex(""">\s*Reviews\s*</""", RegexOption.IGNORE_CASE).find(body)?.range?.first
        val pageBody = if (reviewsStart != null) body.substring(0, reviewsStart) else body
        val chapterMatch = Regex(
            """([\d,]+)\s*(?:<[^>]+>\s*)*chapters?\s*(?:<[^>]+>\s*)*updated""",
            RegexOption.IGNORE_CASE
        ).find(pageBody)
        val nearbyStatus = chapterMatch?.let {
            val afterChapters = pageBody.substring(it.range.last + 1).take(1000)
            Regex(
                """<(?:span|div|p|em|strong)[^>]*>\s*(ongoing|completed|hiatus|dropped)\s*</(?:span|div|p|em|strong)>""",
                RegexOption.IGNORE_CASE
            ).find(afterChapters)?.groupValues?.get(1)
        }
        val stateStatus = field("publishingStatus", "bookStatus", "statusName", "status")
            ?.let(HtmlMetadataParser::publishingStatus).orEmpty().ifBlank {
                when ((book?.get("isCompleted") as? JsonPrimitive)?.booleanOrNull) {
                    true -> "completed"
                    false -> "releasing"
                    null -> if (field("actionStatus") == "50") "completed" else ""
                }
            }

        return generic.copy(
            title = cleanTitle,
            type = MediaType.NOVEL,
            creators = creators,
            platformHint = "Webnovel",
            publishingStatus = HtmlMetadataParser.publishingStatus(nearbyStatus)
                .ifBlank { HtmlMetadataParser.labeledStatus(pageBody) }
                .ifBlank { stateStatus },
            unitName = "Chapter",
            coverUrl = normalizeWebnovelCover(generic.coverUrl, url),
            sourceUrl = url,
            description = field("description", "synopsis", "introduction") ?: generic.description,
            totalChapters = chapterMatch?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
                ?: field("totalChapterNum", "chapterNum", "chapterCount", "totalChapters")
                    ?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
        )
    }

    private fun findBook(element: JsonElement, bookId: String?): JsonObject? {
        when (element) {
            is JsonObject -> {
                val id = listOf("bookId", "book_id", "id").firstNotNullOfOrNull {
                    (element[it] as? JsonPrimitive)?.contentOrNull
                }
                if (bookId != null && id == bookId) return element
                for ((key, value) in element) {
                    if (key in listOf("bookInfo", "bookDetail") && value is JsonObject) {
                        val candidateId = listOf("bookId", "book_id", "id").firstNotNullOfOrNull {
                            (value[it] as? JsonPrimitive)?.contentOrNull
                        }
                        if (candidateId == null || candidateId == bookId) return value
                    }
                    findBook(value, bookId)?.let { return it }
                }
            }
            is JsonArray -> element.forEach { findBook(it, bookId)?.let { book -> return book } }
            else -> Unit
        }
        return null
    }

    override fun normalizeDraft(draft: ScrapedMediaDraft): ScrapedMediaDraft {
        return draft.copy(coverUrl = normalizeWebnovelCover(draft.coverUrl, draft.sourceUrl))
    }

    private fun normalizeWebnovelCover(rawUrl: String?, baseUrl: String): String? {
        val normalized = CoverNormalizer.normalize(rawUrl, baseUrl = baseUrl) ?: return null
        val match = Regex(
            """https?://book-pic\.webnovel\.com/bookcover/(\d+)/(?:\d+/)?\d+\.jpg\?updateTime=(\d+)""",
            RegexOption.IGNORE_CASE
        ).find(normalized)
        return match?.let {
            "https://book-pic.webnovel.com/bookcover/${it.groupValues[1]}" +
                    "?imageMogr2/thumbnail/600x&imageId=${it.groupValues[2]}"
        } ?: normalized
    }
}