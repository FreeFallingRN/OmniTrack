package com.omnitrack.app.feature.anilist.matching

import com.omnitrack.app.data.AniListLibraryEntry
import com.omnitrack.app.domain.model.MediaEntry
import com.omnitrack.app.domain.model.MediaType
import java.util.Locale

private val punctuation = Regex("""[^\p{L}\p{N}]+""")

private fun normalize(title: String): String =
    title.lowercase(Locale.ROOT).replace(punctuation, " ").trim().replace(Regex("""\s+"""), " ")

internal fun findAniListMatch(title: AniListLibraryEntry, entries: List<MediaEntry>): MediaEntry? {
    val names = (listOfNotNull(title.media.title.english, title.media.title.romaji) + title.media.synonyms)
        .map(::normalize).filter { it.length >= 2 }.toSet()
    if (names.isEmpty()) return null
    val matches = entries.filter { entry ->
        entry.type == MediaType.ANIME &&
            (entry.unitName.trim().equals("episode", true) || entry.unitName.trim().equals("episodes", true)) &&
            (listOf(entry.title) + entry.allAlternativeTitles + listOfNotNull(entry.seriesTitle))
                .any { normalize(it) in names }
    }
    return matches.singleOrNull()
}
