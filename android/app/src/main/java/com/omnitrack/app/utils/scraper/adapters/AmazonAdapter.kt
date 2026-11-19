package com.omnitrack.app.utils.scraper.adapters

import com.omnitrack.app.domain.model.MediaType
import com.omnitrack.app.utils.ScrapedCreator
import com.omnitrack.app.utils.ScrapedMediaDraft
import com.omnitrack.app.utils.scraper.CoverNormalizer
import com.omnitrack.app.utils.scraper.HtmlFetchClient
import com.omnitrack.app.utils.scraper.HtmlMetadataParser
import com.omnitrack.app.utils.scraper.MediaSourceAdapter

object AmazonAdapter : MediaSourceAdapter {
    override val name: String = "Amazon"

    override fun canHandle(host: String, url: String): Boolean =
        host.contains("amazon.", ignoreCase = true) || url.contains("amazon.", ignoreCase = true)

    override suspend fun scrapeFromHtml(url: String, html: String): ScrapedMediaDraft {
        val generic = HtmlMetadataParser.extractGenericMetadata(html, url)

        val hiResCover = listOf(
            Regex("""["'](?:hiRes|large|landingImage)["']\s*:\s*["'](https?:\\/\\/[^"']+)""", RegexOption.IGNORE_CASE),
            Regex("""data-old-hires=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
        ).firstNotNullOfOrNull { it.find(html)?.groupValues?.getOrNull(1) }
            ?.let { CoverNormalizer.normalize(it, baseUrl = url) }

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

        val title = productTitle
            ?.takeUnless(::isStorefrontOnlyTitle)
            ?: rawPageTitle?.let(::cleanAmazonPageTitle)?.takeIf { it.isNotBlank() && !isStorefrontOnlyTitle(it) }
            ?: generic.title
                .takeUnless { it.equals(asinFromUrl(url), ignoreCase = true) || isStorefrontOnlyTitle(it) }
            ?: HtmlMetadataParser.extractTitleFromSlug(url)
                .takeUnless { it.equals(asinFromUrl(url), ignoreCase = true) }
            ?: ""

        val creators = extractAmazonCreators(html).ifEmpty {
            rawPageTitle?.let(::extractCreatorsFromPageTitle).orEmpty()
        }.ifEmpty { generic.creators }

        return generic.copy(
            title = title,
            type = MediaType.NOVEL,
            creators = creators,
            platformHint = "Amazon",
            unitName = "Chapter",
            coverUrl = hiResCover ?: generic.coverUrl ?: asinCoverUrl(url),
            sourceUrl = url
        )
    }

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
            // Strip trailing series/book parentheticals like "(Demon Princess Magical Chaos Book 1)" or "(Series #1)"
            .replace(Regex("""\s*\([^()]*(?:\bBook\s+\d+|\bVol\.?\s*\d+|\bVolume\s+\d+|#\s*\d+)[^()]*\)\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
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

        val rawSegments = creatorBlob
            .split(Regex("""\s+and\s+|,\s*""", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotBlank() && !isNonAuthorLabel(it) }

        val initialsOrSuffixPattern = Regex(
            """^(?:[A-Z]\.){1,4}(?:\s+[A-Z]\.?)*$|^(?:Jr\.?|Sr\.?|II|III|IV)$""",
            RegexOption.IGNORE_CASE
        )

        val mergedNames = mutableListOf<String>()
        for (segment in rawSegments) {
            if (initialsOrSuffixPattern.matches(segment) && mergedNames.isNotEmpty()) {
                val last = mergedNames.removeAt(mergedNames.lastIndex)
                mergedNames.add("$segment $last".trim())
            } else {
                mergedNames.add(segment)
            }
        }

        return mergedNames
            .distinctBy { it.lowercase() }
            .map { ScrapedCreator(it, "author") }
    }

    private fun extractAmazonCreators(html: String): List<ScrapedCreator> {
        val bylineBlock = Regex(
            """id=["']bylineInfo["'][^>]*>([\s\S]*?)</div\s*>""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)

        if (!bylineBlock.isNullOrBlank()) {
            val entries = Regex(
                """<(?:a|span)[^>]*class=["'][^"']*\b(?:contributorNameID|a-link-normal)\b[^"']*["'][^>]*>([^<]+)</(?:a|span)>([\s\S]{0,120})?""",
                RegexOption.IGNORE_CASE
            ).findAll(bylineBlock)
                .mapNotNull { m ->
                    val name = m.groupValues.getOrNull(1)
                        ?.let(HtmlMetadataParser::decodeHtmlEntities)
                        ?.trim()
                        ?.takeUnless(::isNonAuthorLabel)
                        ?: return@mapNotNull null

                    val trailingContext = m.groupValues.getOrNull(2).orEmpty()
                    val role = mapAmazonRole(trailingContext)
                    ScrapedCreator(name, role)
                }
                .distinctBy { "${it.name.lowercase()}|${it.role.lowercase()}" }
                .toList()

            if (entries.isNotEmpty()) return entries
        }

        return Regex(
            """<(?:a|span)[^>]*(?:contributorNameID|a-size-medium)[^>]*>([^<]+)</(?:a|span)>""",
            RegexOption.IGNORE_CASE
        ).findAll(html)
            .mapNotNull { m ->
                m.groupValues.getOrNull(1)
                    ?.let(HtmlMetadataParser::decodeHtmlEntities)
                    ?.trim()
                    ?.takeUnless(::isNonAuthorLabel)
                    ?.let { ScrapedCreator(it, "author") }
            }
            .distinctBy { "${it.name.lowercase()}|${it.role.lowercase()}" }
            .toList()
    }

    private fun mapAmazonRole(context: String): String {
        val decoded = HtmlMetadataParser.decodeHtmlEntities(context)
        return when {
            Regex("""\b(?:illustrator|illus\.?|artist)\b""", RegexOption.IGNORE_CASE).containsMatchIn(decoded) -> "illustrator"
            Regex("""\b(?:translator|trans\.?)\b""", RegexOption.IGNORE_CASE).containsMatchIn(decoded) -> "translator"
            else -> "author"
        }
    }

    private fun asinFromUrl(sourceUrl: String): String? =
        Regex("""/(?:dp|gp/product)/([A-Z0-9]{10})""", RegexOption.IGNORE_CASE)
            .find(sourceUrl)
            ?.groupValues
            ?.getOrNull(1)

    override fun normalizeDraft(draft: ScrapedMediaDraft): ScrapedMediaDraft {
        val normalized = CoverNormalizer.normalize(draft.coverUrl, baseUrl = draft.sourceUrl)
        return draft.copy(coverUrl = normalized ?: asinCoverUrl(draft.sourceUrl))
    }

    private fun asinCoverUrl(sourceUrl: String): String? {
        val asin = Regex("""/(?:dp|gp/product)/([A-Z0-9]{10})""", RegexOption.IGNORE_CASE)
            .find(sourceUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?: return null
        return "https://m.media-amazon.com/images/P/$asin.01.LZZZZZZZ.jpg"
    }

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
                lower.endsWith(" store")
    }
}