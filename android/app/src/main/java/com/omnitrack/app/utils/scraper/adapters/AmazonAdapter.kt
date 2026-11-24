package com.omnitrack.app.utils.scraper.adapters

import android.util.Log
import com.omnitrack.app.domain.model.MediaType
import com.omnitrack.app.utils.ScrapedCreator
import com.omnitrack.app.utils.ScrapedMediaDraft
import com.omnitrack.app.utils.scraper.CoverNormalizer
import com.omnitrack.app.utils.scraper.HtmlFetchClient
import com.omnitrack.app.utils.scraper.HtmlMetadataParser
import com.omnitrack.app.utils.scraper.MediaSourceAdapter
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.net.URLEncoder
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale

object AmazonAdapter : MediaSourceAdapter {
    private const val TAG = "AmazonAdapter"
    override val name: String = "Amazon"

    private val publicationDatePattern = Regex(
        """\b(?:\d{1,2}\s+[\p{L}.]+\s+\d{4}|[\p{L}.]+\s+\d{1,2},?\s+\d{4}|\d{4}[-/]\d{1,2}(?:[-/]\d{1,2})?|\d{1,2}[./-]\d{1,2}[./-]\d{4}|\d{4})\b"""
    )

    override fun canHandle(host: String, url: String): Boolean =
        host.contains("amazon.", ignoreCase = true) || url.contains("amazon.", ignoreCase = true)

    override suspend fun scrapeFromHtml(url: String, html: String): ScrapedMediaDraft {
        val normalizedUrl = HtmlMetadataParser.normalizeAmazonUrl(url)
        val generic = HtmlMetadataParser.extractGenericMetadata(html, normalizedUrl)

        val hiResCover = listOf(
            Regex("""["'](?:hiRes|large|landingImage)["']\s*:\s*["'](https?:\\/\\/[^"']+)""", RegexOption.IGNORE_CASE),
            Regex("""data-old-hires=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
        ).firstNotNullOfOrNull { it.find(html)?.groupValues?.getOrNull(1) }
            ?.let { CoverNormalizer.normalize(it, baseUrl = normalizedUrl) }

        val productTitle = Regex("""<span[^>]*id=["'](?:productTitle|ebooksProductTitle)["'][^>]*>([^<]+)</span>""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)
            ?.let(HtmlMetadataParser::decodeHtmlEntities)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val rawPageTitle = HtmlMetadataParser.meta(html, "og:title")
            ?: HtmlMetadataParser.meta(html, "twitter:title")
            ?: Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1)
                ?.let(HtmlMetadataParser::decodeHtmlEntities)
                ?.trim()

        val asin = asinFromUrl(normalizedUrl)
        val title = productTitle
            ?.takeIf { isBookTitle(it, asin) }
            ?: rawPageTitle?.let(::cleanAmazonPageTitle)?.takeIf { isBookTitle(it, asin) }
            ?: generic.title
                .takeIf { isBookTitle(it, asin) }
            ?: ""
        val (cleanTitle, seriesTitle, sequenceNumber) = extractSeriesMetadata(title)

        val creators = extractAmazonCreators(html).ifEmpty {
            rawPageTitle?.let(::extractCreatorsFromPageTitle).orEmpty()
        }.ifEmpty {
            generic.creators.flatMap { creator ->
                splitAmazonCreatorNames(creator.name).map { ScrapedCreator(it, creator.role) }
            }
        }

        val publishingStatus = if (isPreOrderOrFuturePublication(html)) "announced" else "completed"
        return generic.copy(
            title = cleanTitle,
            seriesTitle = seriesTitle ?: generic.seriesTitle,
            sequenceNumber = sequenceNumber ?: generic.sequenceNumber,
            type = MediaType.NOVEL,
            creators = creators,
            platformHint = "Amazon",
            publishingStatus = publishingStatus,
            unitName = "Chapter",
            coverUrl = hiResCover ?: generic.coverUrl ?: asinCoverUrl(normalizedUrl),
            sourceUrl = normalizedUrl
        )
    }

    suspend fun completeMetadata(
        url: String,
        slug: String?,
        draft: ScrapedMediaDraft?,
        fetcher: HtmlFetchClient
    ): ScrapedMediaDraft {
        val asin = asinFromUrl(url)
        var result = draft ?: ScrapedMediaDraft(
            title = "",
            type = MediaType.NOVEL,
            platformHint = "Amazon",
            publishingStatus = "completed",
            unitName = "Chapter",
            sourceUrl = url
        )
        if (!isBookTitle(result.title, asin)) result = result.copy(title = "")

        if (result.title.isBlank() || result.creators.none { it.role.equals("author", ignoreCase = true) }) {
            val isbn = asin?.takeIf { it.matches(Regex("""\d{9}[\dX]""")) }
            val titleQuery = result.title.takeIf { it.isNotBlank() } ?: slug
            val googleQueries = listOfNotNull(isbn?.let { "isbn:$it" }, asin, titleQuery).distinct()
            val openLibraryQueries = listOfNotNull(
                isbn?.let { "https://openlibrary.org/api/books?bibkeys=${encode("ISBN:$it")}&format=json&jscmd=data" },
                asin?.let { "https://openlibrary.org/search.json?q=${encode(it)}&limit=1" },
                titleQuery?.let { "https://openlibrary.org/search.json?title=${encode(it)}&limit=1" }
            )

            for (query in googleQueries) {
                val metadata = fetchBookJson(
                    "https://www.googleapis.com/books/v1/volumes?q=${encode(query)}&maxResults=1",
                    fetcher
                )?.optJSONArray("items")?.optJSONObject(0)?.optJSONObject("volumeInfo")
                if (metadata != null) {
                    val authors = metadata.optJSONArray("authors")
                    val creators = (0 until (authors?.length() ?: 0)).mapNotNull { index ->
                        authors?.optString(index)?.trim()?.takeIf { it.isNotEmpty() }
                            ?.let { ScrapedCreator(it, "author") }
                    }
                    val images = metadata.optJSONObject("imageLinks")
                    result = mergeBookMetadata(
                        result, metadata.optString("title"), creators,
                        images?.optString("thumbnail")?.takeIf { it.isNotBlank() }
                            ?: images?.optString("smallThumbnail"), asin,
                        metadata.optString("publishedDate")
                    )
                }
                if (result.title.isNotBlank() && result.creators.any { it.role.equals("author", true) }) break
            }

            if (result.title.isBlank() || result.creators.none { it.role.equals("author", true) }) {
                for (apiUrl in openLibraryQueries) {
                    val response = fetchBookJson(apiUrl, fetcher) ?: continue
                    val book = if (apiUrl.contains("/api/books?")) {
                        response.optJSONObject("ISBN:$isbn")
                    } else {
                        response.optJSONArray("docs")?.optJSONObject(0)
                    } ?: continue
                    val authors = book.optJSONArray("authors") ?: book.optJSONArray("author_name")
                    val creators = (0 until (authors?.length() ?: 0)).mapNotNull { index ->
                        val name = authors?.optJSONObject(index)?.optString("name")
                            ?: authors?.optString(index)
                        name?.trim()?.takeIf { it.isNotEmpty() }?.let { ScrapedCreator(it, "author") }
                    }
                    val cover = book.optJSONObject("cover")?.let {
                        it.optString("large").ifBlank { it.optString("medium") }
                    }?.takeIf { it.isNotBlank() }
                        ?: book.optString("cover_i").takeIf { it.isNotBlank() }
                            ?.let { "https://covers.openlibrary.org/b/id/$it-L.jpg" }
                    result = mergeBookMetadata(
                        result, book.optString("title"), creators, cover, asin,
                        book.optString("publish_date").ifBlank { book.optString("first_publish_year") }
                    )
                    if (result.title.isNotBlank() && result.creators.any { it.role.equals("author", true) }) break
                }
            }
        }

        val titleToParse = result.title.ifBlank { slug.orEmpty() }
        val (cleanTitle, seriesTitle, sequenceNumber) = extractSeriesMetadata(titleToParse)
        return normalizeDraft(
            result.copy(
                title = cleanTitle,
                seriesTitle = result.seriesTitle ?: seriesTitle,
                sequenceNumber = result.sequenceNumber ?: sequenceNumber
            )
        )
    }

    private fun mergeBookMetadata(
        draft: ScrapedMediaDraft,
        title: String,
        creators: List<ScrapedCreator>,
        cover: String?,
        asin: String?,
        publicationDate: String
    ): ScrapedMediaDraft = draft.copy(
        title = draft.title.ifBlank { title.takeIf { isBookTitle(it, asin) }.orEmpty() },
        creators = if (draft.creators.none { it.role.equals("author", true) }) {
            draft.creators + creators
        } else draft.creators,
        publishingStatus = if (isFuturePublicationDate(publicationDate)) "announced" else draft.publishingStatus,
        coverUrl = if (draft.coverUrl.isNullOrBlank() || draft.coverUrl == asinCoverUrl(draft.sourceUrl)) {
            cover?.replaceFirst("http://", "https://") ?: draft.coverUrl
        } else draft.coverUrl
    )

    private fun isPreOrderOrFuturePublication(html: String): Boolean {
        val decodedHtml = HtmlMetadataParser.decodeHtmlEntities(html)
        val plainText = decodedHtml.replace(Regex("""<[^>]*>"""), " ").replace(Regex("""\s+"""), " ")
        val preOrderMatch = Regex(
            """\bpre[\s-]?order\b|\bvoorbestel\w*|\bauto[\s-]?delivered\b[\s\S]{0,120}\b(?:on|op)\b""",
            RegexOption.IGNORE_CASE
        ).find(plainText) ?: Regex(
            """\bautomatisch\s+geleverd\b[\s\S]{0,120}\bop\b""",
            RegexOption.IGNORE_CASE
        ).find(plainText)
        val futureInHtml = hasFuturePublicationDate(html, "html")
        val futureInText = hasFuturePublicationDate(plainText, "text")
        return preOrderMatch != null || futureInHtml || futureInText
    }

    private fun hasFuturePublicationDate(source: String, sourceName: String): Boolean {
        val labels = Regex(
            """(?:publication\s*date|release\s*date|releaseDate|datePublished|book:release_date|publicatiedatum|verschijningsdatum|verschijnt|available\s+on)\s*[:=]?\s*["']?([\s\S]{0,120})""",
            RegexOption.IGNORE_CASE
        )
        val matches = labels.findAll(source).toList()
        if (matches.isEmpty()) {
            return false
        }
        return matches.any { label ->
            val date = publicationDatePattern.find(label.groupValues[1])?.value
            if (date == null) {
                false
            } else isFuturePublicationDate(date, sourceName)
        }
    }

    private fun isFuturePublicationDate(value: String, sourceName: String = "fallback metadata"): Boolean {
        val parsed = parsePublicationDate(value)
        val now = java.util.Date()
        val isFuture = parsed?.after(now) == true
        return isFuture
    }

    private fun parsePublicationDate(rawDate: String): java.util.Date? {
        val value = rawDate
            .replace(Regex("""(?<=\p{L})\."""), "")
            .replace(",", "")
            .trim()
        val patterns = listOf(
            "d MMM yyyy", "d MMMM yyyy", "MMM d yyyy", "MMMM d yyyy", "MMM yyyy", "MMMM yyyy",
            "yyyy-MM-dd", "yyyy/MM/dd", "yyyy-MM", "yyyy/MM", "yyyy", "d/M/yyyy", "M/d/yyyy",
            "d-M-yyyy", "M-d-yyyy", "d.M.yyyy"
        )
        val locales = listOf(Locale.ENGLISH, Locale("nl"))
        for (locale in locales) {
            for (pattern in patterns) {
                val format = SimpleDateFormat(pattern, locale).apply { isLenient = false }
                val position = ParsePosition(0)
                val parsed = format.parse(value, position)
                if (parsed != null && position.index == value.length) return parsed
            }
        }
        return null
    }

    private suspend fun fetchBookJson(url: String, fetcher: HtmlFetchClient): JSONObject? {
        return try {
            JSONObject(fetcher.getJson(url))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("AmazonAdapter", "Book metadata lookup failed: $url", e)
            null
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun isBookTitle(title: String, asin: String?): Boolean =
        title.isNotBlank() && !title.trim().equals(asin, ignoreCase = true) &&
            !isStorefrontOnlyTitle(title) &&
            !title.contains("robot check", ignoreCase = true) &&
            !title.contains("captcha", ignoreCase = true) &&
            !title.contains("just a moment", ignoreCase = true)

    private fun cleanAmazonPageTitle(raw: String): String {
        return raw
            .replace(Regex("""^Amazon\.[a-z.]+\s*:\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*:\s*[^:]+:\s*Amazon\.[a-z.]+\s*:\s*(?:Kindle Store|Audible\.com|Books)\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*:\s*[^:]+:\s*(?:Kindle Store|Audible\.com|Books)\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*:\s*(?:Kindle Store|Audible\.com|Books)\s*$""", RegexOption.IGNORE_CASE), "")
            // Strip trailing " : Author1, Author2..." suffix from Amazon titles/og:title
            .replace(Regex("""\s+:\s+[^:]+$"""), "")
            .replace(Regex("""\s*[-–|]\s*Amazon\.[a-z.]+\s*$""", RegexOption.IGNORE_CASE), "")
            // Strip format/edition tags like "(English Edition)", "(Kindle Edition)", "eBook", etc.
            .replace(Regex("""\s*\((?:[^()]*(?:Edition|Kindle|eBook|Paperback|Hardcover|Audiobook|Large Print|Box Set|Omnibus)[^()]*)\)\s*""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\s*\b(?:Kindle Edition|eBook|Paperback|Hardcover|Audiobook)\b\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
    }

    private fun extractSeriesMetadata(rawTitle: String): Triple<String, String?, Double?> {
        var title = rawTitle.trim()
        var seriesTitle: String? = null
        var sequenceNumber: Double? = null
        val trailingParenthetical = Regex("""\s*\(([^()]*)\)\s*$""")

        while (true) {
            val match = trailingParenthetical.find(title) ?: break
            val content = match.groupValues[1].trim()
            val parsed = parseSeriesPosition(content)
            if (parsed != null) {
                seriesTitle = seriesTitle ?: parsed.first
                sequenceNumber = sequenceNumber ?: parsed.second
                title = title.substring(0, match.range.first).trimEnd()
            } else if (isGenericBookPosition(content) ||
                Regex(
                    """(?:Edition|Kindle|eBook|Paperback|Hardcover|Audiobook|Large Print|Box Set|Omnibus)""",
                    RegexOption.IGNORE_CASE
                ).containsMatchIn(content)
            ) {
                title = title.substring(0, match.range.first).trimEnd()
            } else {
                break
            }
        }

        val seriesLabel = Regex("""\bSeries\s*:\s*(.+?)\s*$""", RegexOption.IGNORE_CASE).find(title)
        if (seriesLabel != null) {
            val parsed = parseSeriesPosition(seriesLabel.groupValues[1])
            if (parsed != null) {
                seriesTitle = seriesTitle ?: parsed.first
                sequenceNumber = sequenceNumber ?: parsed.second
                title = title.substring(0, seriesLabel.range.first).trimEnd()
            }
        }

        return Triple(title.replace(Regex("""\s{2,}"""), " ").trim(), seriesTitle, sequenceNumber)
    }

    private fun parseSeriesPosition(raw: String): Pair<String, Double>? {
        val value = raw.trim().replace(Regex("""^Series\s*:\s*""", RegexOption.IGNORE_CASE), "")
        val match = Regex("""(?:#\s*|\s+)(\d+(?:\.\d+)?|[IVXLCDM]+)\s*$""", RegexOption.IGNORE_CASE)
            .find(value) ?: return null
        val positionText = match.groupValues[1]
        val position = positionText.toDoubleOrNull()?.takeIf { it.isFinite() }
            ?: romanNumeralValue(positionText)?.toDouble()
            ?: return null
        var name = value.substring(0, match.range.first).trim().trimEnd(',', ':')
        name = name.replace(Regex("""[,]?\s*(?:Book|Vol(?:ume)?|Part)\s*$""", RegexOption.IGNORE_CASE), "").trim()
        if (name.isBlank() || name.equals("book", true) || name.equals("volume", true) ||
            name.equals("vol", true) || name.equals("part", true)
        ) return null
        return name to position
    }

    private fun isGenericBookPosition(raw: String): Boolean =
        Regex(
            """^(?:Book|Vol(?:ume)?|Part|Issue)\s*(?:#\s*)?(?:\d+(?:\.\d+)?|[IVXLCDM]+)$""",
            RegexOption.IGNORE_CASE
        ).matches(raw.trim())

    private fun romanNumeralValue(raw: String): Int? {
        val numeral = raw.uppercase()
        if (!Regex("""^(?=.)M{0,3}(?:CM|CD|D?C{0,3})(?:XC|XL|L?X{0,3})(?:IX|IV|V?I{0,3})$""").matches(numeral)) {
            return null
        }
        val values = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
        var total = 0
        numeral.forEachIndexed { index, char ->
            val value = values.getValue(char)
            total += if (index + 1 < numeral.length && value < values.getValue(numeral[index + 1])) -value else value
        }
        return total
    }

    private fun extractCreatorsFromPageTitle(rawTitle: String): List<ScrapedCreator> {
        val withoutPrefix = rawTitle.replace(Regex("""^Amazon\.[a-z.]+\s*:\s*""", RegexOption.IGNORE_CASE), "")
        val match = Regex(
            """\s*:\s*([^:]+?)(?:\s*:\s*Amazon\.[a-z.]+)?\s*:\s*(?:Kindle Store|Audible\.com|Books)\s*$""",
            RegexOption.IGNORE_CASE
        ).find(withoutPrefix)
            ?: return emptyList()

        val creatorBlob = match.groupValues.getOrNull(1)?.trim().orEmpty()
        if (creatorBlob.isBlank()) return emptyList()

        return splitAmazonCreatorNames(creatorBlob)
            .distinctBy { it.lowercase() }
            .map { ScrapedCreator(it, "author") }
    }

    private fun extractAmazonCreators(html: String): List<ScrapedCreator> {
        val bylineBlock = Regex(
            """id=["']bylineInfo["'][^>]*>([\s\S]*?)</div\s*>""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)

        if (!bylineBlock.isNullOrBlank()) {
            val matches = Regex(
                """<(?:a|span)[^>]*class=["'][^"']*\b(?:contributorNameID|a-link-normal)\b[^"']*["'][^>]*>([^<]+)</(?:a|span)>""",
                RegexOption.IGNORE_CASE
            ).findAll(bylineBlock).toList()
            val entries = matches
                .flatMapIndexed { index, m ->
                    val end = matches.getOrNull(index + 1)?.range?.first ?: bylineBlock.length
                    val trailingContext = bylineBlock.substring(m.range.last + 1, end).take(120)
                    val role = mapAmazonRole(m.groupValues.getOrNull(1).orEmpty() + " " + trailingContext)
                    val names = splitAmazonCreatorNames(m.groupValues.getOrNull(1).orEmpty())
                    names.map { ScrapedCreator(it, role) }
                }
                .distinctBy { "${it.name.lowercase()}|${it.role.lowercase()}" }

            if (entries.isNotEmpty()) return entries
        }

        return Regex(
            """<(?:a|span)[^>]*(?:contributorNameID|a-size-medium)[^>]*>([^<]+)</(?:a|span)>""",
            RegexOption.IGNORE_CASE
        ).findAll(html)
            .mapNotNull { m ->
                splitAmazonCreatorNames(m.groupValues.getOrNull(1).orEmpty())
                    .map { ScrapedCreator(it, "author") }
            }
            .flatten()
            .distinctBy { "${it.name.lowercase()}|${it.role.lowercase()}" }
            .toList()
    }

    private fun splitAmazonCreatorNames(raw: String): List<String> {
        return HtmlMetadataParser.decodeHtmlEntities(raw)
            .replace(Regex("""<[^>]*>"""), " ")
            .split(Regex("""\s+&\s+|\s+and\s+|\s+en\s+|;""", RegexOption.IGNORE_CASE))
            .map {
                it.replace(Regex("""^\s*(?:by|door)\s+""", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("""^\s*(?:author|auteur|illustrator|artist|translator|editor)\s*:\s*""", RegexOption.IGNORE_CASE), "")
                    .replace(
                        Regex(
                            """(?:\s*,?\s*(?:\((?:author|auteur|illustrator|artist|translator|editor|contributor|photographer|foreword|introduction)\)|,\s*(?:author|auteur|illustrator|artist|translator|editor|contributor|photographer|foreword|introduction)))+\s*$""",
                            RegexOption.IGNORE_CASE
                        ),
                        ""
                    )
                    .replace(Regex("""\s+"""), " ")
                    .trim()
                    .trim(',', ':', '|')
            }
            .filter { it.isNotBlank() && !isNonAuthorLabel(it) }
    }

    private fun mapAmazonRole(context: String): String {
        val decoded = HtmlMetadataParser.decodeHtmlEntities(context)
        return when {
            Regex("""\b(?:illustrator|illus\.?|artist)\b""", RegexOption.IGNORE_CASE).containsMatchIn(decoded) -> "illustrator"
            Regex("""\b(?:translator|trans\.?)\b""", RegexOption.IGNORE_CASE).containsMatchIn(decoded) -> "translator"
            else -> "author"
        }
    }

    override fun normalizeDraft(draft: ScrapedMediaDraft): ScrapedMediaDraft {
        val normalizedUrl = HtmlMetadataParser.normalizeAmazonUrl(draft.sourceUrl)
        val normalized = CoverNormalizer.normalize(draft.coverUrl, baseUrl = normalizedUrl)
        val result = draft.copy(
            coverUrl = normalized ?: asinCoverUrl(normalizedUrl),
            sourceUrl = normalizedUrl
        )
        Log.d(TAG, "normalizeDraft returned publishingStatus=${result.publishingStatus}")
        return result
    }

    private fun asinCoverUrl(sourceUrl: String): String? {
        val asin = asinFromUrl(sourceUrl) ?: return null
        return "https://m.media-amazon.com/images/P/$asin.01.LZZZZZZZ.jpg"
    }

    private fun asinFromUrl(sourceUrl: String): String? =
        HtmlMetadataParser.extractAmazonAsin(sourceUrl)

    private fun isStorefrontOnlyTitle(candidate: String): Boolean {
        val normalized = candidate.trim()
        return normalized.isBlank() ||
                normalized.equals("Amazon", ignoreCase = true) ||
                Regex("""^Amazon\.[a-z.]+$""", RegexOption.IGNORE_CASE).matches(normalized)
    }

    private fun isNonAuthorLabel(candidate: String): Boolean {
        val cleaned = candidate.trim()
        if (cleaned.isBlank()) return true

        val lower = cleaned.lowercase()
        val blockedExact = setOf(
            "kindle",
            "kindle edition",
            "ebook",
            "e-book",
            "audible",
            "audible audiobook",
            "audiobook",
            "hardcover",
            "paperback",
            "mass market paperback",
            "amazon",
            "amazon.com",
            "amazon.nl",
            "follow the authors",
            "see all formats and editions"
        )
        if (lower in blockedExact || isStorefrontOnlyTitle(cleaned)) return true

        // Discount badges (-75%, 75% off), prices ($4.99, €3,99), or strings with no letters
        if (cleaned.contains('%') ||
            Regex("""^[+\-]?\s*[$€£¥]?\s*\d+(?:[.,]\d+)?\s*[$€£¥%]?$""").matches(cleaned) ||
            Regex("""\b\d+\s*%\s*off\b""", RegexOption.IGNORE_CASE).containsMatchIn(cleaned) ||
            !cleaned.any { it.isLetter() }
        ) {
            return true
        }

        return lower.startsWith("format:") ||
                lower.startsWith("see all formats") ||
                lower.endsWith(" store") ||
                lower.startsWith("visit ") ||
                lower.endsWith(" author page") ||
                lower == "author page" ||
                lower.startsWith("http://") ||
                lower.startsWith("https://") ||
                lower.contains("author") && lower.contains("page")
    }
}