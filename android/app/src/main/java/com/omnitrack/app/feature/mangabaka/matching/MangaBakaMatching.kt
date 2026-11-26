package com.omnitrack.app.feature.mangabaka.matching

import com.omnitrack.app.data.MangaBakaTitle
import com.omnitrack.app.domain.model.MediaEntry
import com.omnitrack.app.domain.model.MediaType
import java.util.Locale

private val subtitleSeparator = Regex("""\s*[:：]\s*|\s+[–—-]\s+""")
private val titlePunctuation = Regex("""[^\p{L}\p{N}]+""")

internal fun mangaBakaMatchesType(title: MangaBakaTitle, type: MediaType): Boolean =
    when (title.series.type) {
        "novel" -> type == MediaType.NOVEL
        "manga", "manhwa", "manhua", "oel" -> type == MediaType.COMIC
        "other" -> type == MediaType.NOVEL || type == MediaType.COMIC
        else -> false
    }

private fun normalizedTitle(title: String): String =
    title.lowercase(Locale.ROOT).replace(titlePunctuation, " ").trim().replace(Regex("""\s+"""), " ")

private fun titleVariants(title: String): Pair<String, String> =
    normalizedTitle(title) to normalizedTitle(title.split(subtitleSeparator, limit = 2).first())

private fun titleSimilarity(a: String, b: String): Double {
    if (a == b) return 1.0
    if (minOf(a.length, b.length) < 8) return 0.0
    val aTokens = a.split(" ").toSet()
    val bTokens = b.split(" ").toSet()
    if (aTokens.filter { it.any(Char::isDigit) }.toSet() !=
        bTokens.filter { it.any(Char::isDigit) }.toSet()
    ) return 0.0
    val overlap = 2.0 * aTokens.intersect(bTokens).size / (aTokens.size + bTokens.size)
    if (overlap >= 0.9) return overlap
    if (minOf(a.length, b.length).toDouble() / maxOf(a.length, b.length) < 0.9) return 0.0

    var previous = IntArray(b.length + 1) { it }
    for (i in a.indices) {
        val current = IntArray(b.length + 1)
        current[0] = i + 1
        for (j in b.indices) {
            current[j + 1] = minOf(
                current[j] + 1,
                previous[j + 1] + 1,
                previous[j] + if (a[i] == b[j]) 0 else 1
            )
        }
        previous = current
    }
    return 1.0 - previous[b.length].toDouble() / maxOf(a.length, b.length)
}

internal fun findMangaBakaMatch(title: MangaBakaTitle, entries: List<MediaEntry>): MediaEntry? {
    val type = when (title.series.type) {
        "novel" -> MediaType.NOVEL
        "manga", "manhwa", "manhua", "oel" -> MediaType.COMIC
        else -> return null
    }
    val mangaTitles = (listOf(title.title) + title.series.titles.orEmpty().map { it.title })
        .map(::titleVariants).filter { it.first.length >= 2 }.distinct()
    val scores = entries.filter { it.type == type }.mapNotNull { entry ->
        val entryTitles = (listOf(entry.title) + entry.allAlternativeTitles + listOfNotNull(entry.seriesTitle))
            .map(::titleVariants).filter { it.first.length >= 2 }.distinct()
        val score = mangaTitles.maxOfOrNull { (mangaFull, mangaBase) ->
            entryTitles.maxOfOrNull { (entryFull, entryBase) ->
                val fullScore = titleSimilarity(mangaFull, entryFull)
                when {
                    mangaBase != mangaFull && entryBase == entryFull && mangaBase.length >= 4 ->
                        maxOf(fullScore, titleSimilarity(mangaBase, entryFull))
                    entryBase != entryFull && mangaBase == mangaFull && entryBase.length >= 4 ->
                        maxOf(fullScore, titleSimilarity(mangaFull, entryBase))
                    else -> fullScore
                }
            } ?: 0.0
        } ?: 0.0
        if (score >= 0.9) entry to score else null
    }.sortedByDescending { it.second }
    if (scores.size > 1 && scores[0].second - scores[1].second < 0.05) return null
    return scores.firstOrNull()?.first
}
