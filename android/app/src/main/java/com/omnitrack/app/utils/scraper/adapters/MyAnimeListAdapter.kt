package com.omnitrack.app.utils.scraper.adapters

import com.omnitrack.app.domain.model.MediaType
import com.omnitrack.app.utils.ScrapedCreator
import com.omnitrack.app.utils.ScrapedMediaDraft
import com.omnitrack.app.utils.scraper.HtmlFetchClient
import com.omnitrack.app.utils.scraper.HtmlMetadataParser
import com.omnitrack.app.utils.scraper.MediaSourceAdapter

object MyAnimeListAdapter : MediaSourceAdapter {
    override val name: String = "MyAnimeList"

    override fun canHandle(host: String, url: String): Boolean =
        host.contains("myanimelist.net", ignoreCase = true)

    override suspend fun scrapeWithoutHtml(
        url: String,
        fetcher: HtmlFetchClient
    ): ScrapedMediaDraft? {
        val isManga = url.contains("/manga/", ignoreCase = true)
        val malId = Regex("""/(anime|manga)/(\d+)""").find(url)
            ?.groupValues?.getOrNull(2)?.toIntOrNull() ?: return null

        return AniListAdapter.queryAniList(
            id = null,
            idMal = malId,
            isManga = isManga,
            platformHint = "MyAnimeList",
            sourceUrl = url
        )
    }

    override suspend fun scrapeFromHtml(url: String, html: String): ScrapedMediaDraft {
        val isManga = url.contains("/manga/", ignoreCase = true)
        val generic = HtmlMetadataParser.extractGenericMetadata(html, url)

        val cleanTitle = generic.title
            .substringBefore(" - MyAnimeList.net")
            .trim()
            .ifBlank { HtmlMetadataParser.extractTitleFromSlug(url) }

        val cdnPoster = Regex(
            """https://cdn\.myanimelist\.net/images/(?:anime|manga)/\d+/\d+\.(?:jpg|webp)""",
            RegexOption.IGNORE_CASE
        ).find(html)?.value
            ?: generic.coverUrl?.takeUnless {
                it.contains("mal-logo", ignoreCase = true) || it.contains("icon", ignoreCase = true)
            }

        val creators = if (isManga) {
            val authorSection = Regex("""Authors?:</span>([\s\S]{0,800}?)</div>""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1).orEmpty()

            val authorMatches = Regex(
                """<a[^>]*href="/people/\d+/[^"]*"[^>]*>([^<]+)</a>\s*(?:\(([^)]+)\))?""",
                RegexOption.IGNORE_CASE
            ).findAll(authorSection)

            val authorRolesMap = mutableMapOf<String, MutableSet<String>>()
            for (match in authorMatches) {
                val rawName = match.groupValues.getOrNull(1)?.trim() ?: continue
                val creatorName = formatMalAuthorName(HtmlMetadataParser.decodeHtmlEntities(rawName))
                if (creatorName.isBlank()) continue

                val roleParen = match.groupValues.getOrNull(2)?.lowercase().orEmpty()
                val roles = authorRolesMap.getOrPut(creatorName) { mutableSetOf() }
                if (roleParen.contains("art") || roleParen.contains("illustrat") || roleParen.contains("design")) {
                    roles.add("illustrator")
                } else {
                    roles.add("author")
                }
            }

            buildList {
                authorRolesMap.forEach { (creatorName, roles) ->
                    if (roles.contains("author") && roles.contains("illustrator")) {
                        add(ScrapedCreator(creatorName, "author"))
                        add(ScrapedCreator(creatorName, "illustrator"))
                    } else if (roles.contains("illustrator")) {
                        add(ScrapedCreator(creatorName, "illustrator"))
                    } else {
                        add(ScrapedCreator(creatorName, "author"))
                    }
                }
            }
        } else {
            val studioSection = Regex("""Studios?:</span>([\s\S]{0,600}?)</div>""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1).orEmpty()
            Regex("""<a[^>]*href="/anime/producer/\d+/[^"]*"[^>]*>([^<]+)</a>""", RegexOption.IGNORE_CASE)
                .findAll(studioSection)
                .mapNotNull { match ->
                    val studioName = match.groupValues.getOrNull(1)?.trim()
                        ?.let(HtmlMetadataParser::decodeHtmlEntities)
                    studioName?.takeIf { it.isNotBlank() && !it.equals("None found", ignoreCase = true) }
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
            statusBlock.contains("currently airing") || statusBlock.contains("currently publishing") ||
                statusBlock.trim() == "publishing" -> "releasing"
            else -> ""
        }

        val mediaType = if (isManga) MediaType.COMIC else MediaType.ANIME
        return generic.copy(
            title = cleanTitle,
            type = mediaType,
            creators = creators.distinctBy { "${it.name.lowercase()}::${it.role}" }.ifEmpty { generic.creators },
            platformHint = "MyAnimeList",
            publishingStatus = pubStatus,
            unitName = if (mediaType == MediaType.ANIME) "Episode" else "Chapter",
            coverUrl = cdnPoster,
            sourceUrl = url
        )
    }

    private fun formatMalAuthorName(raw: String): String {
        return if (raw.contains(",")) {
            val parts = raw.split(",").map { it.trim() }
            if (parts.size == 2) "${parts[1]} ${parts[0]}" else raw
        } else raw
    }
}