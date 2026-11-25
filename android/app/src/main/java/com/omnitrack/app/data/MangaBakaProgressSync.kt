package com.omnitrack.app.data

import android.util.Log
import com.omnitrack.app.domain.repository.SeriesRepository
import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MangaBakaProgressSync(
    private val preferences: MangaBakaPreferences,
    private val service: MangaBakaService,
    private val repository: SeriesRepository
) {
    suspend fun syncMangaBakaProgress(): Pair<List<MangaBakaTitle>, Int> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val credentials = preferences.read()
            check(credentials.accessToken.isNotBlank() && credentials.userId.isNotBlank()) {
                "Connect your MangaBaka account first"
            }
            val links = preferences.links(credentials.userId)
            check(links.isNotEmpty()) { "Link a MangaBaka title first" }
            val entries = repository.getMediaEntriesStream().first().associateBy { it.id }
            val titles = service.library(credentials)
            var updated = 0
            for (title in titles) {
                check(title.entry.seriesId == title.series.id) { "MangaBaka series ID does not match" }
                val link = links[title.series.id] ?: continue
                val entry = entries[link.entryId]
                if (entry == null) {
                    Log.w("MangaBakaSync", "Linked entry ${link.entryId} no longer exists")
                    continue
                }
                if (!entry.unitName.trim().equals("chapter", true) &&
                    !entry.unitName.trim().equals("chapters", true)
                ) {
                    Log.w("MangaBakaSync", "${entry.title} no longer tracks chapters")
                    continue
                }
                val progress = title.entry.progress ?: continue
                check(progress.isFinite() && progress >= 0.0 && link.offset.isFinite()) {
                    "Invalid MangaBaka chapter or offset for ${entry.title}"
                }
                val chapter = BigDecimal.valueOf(progress).add(BigDecimal.valueOf(link.offset))
                if (chapter <= BigDecimal.valueOf(entry.currentUnit)) continue
                check(chapter <= BigDecimal("99999.99") && chapter.stripTrailingZeros().scale() <= 2) {
                    "MangaBaka chapter for ${entry.title} cannot be represented in OmniTrack"
                }
                val total = title.series.totalChapters
                check(total == null || (total.isFinite() && total > 0.0)) {
                    "Invalid MangaBaka chapter total for ${entry.title}"
                }
                check(preferences.read() == credentials &&
                    preferences.links(credentials.userId)[title.series.id] == link) {
                    "MangaBaka account or link changed during sync; try again"
                }
                if (repository.updateMangaBakaChapterIfHigher(
                        entry.id,
                        chapter.toDouble(),
                        total != null && progress >= total,
                        title.series.status
                    )) updated++
            }
            titles to updated
        }
    }

    companion object {
        private val mutex = Mutex()
    }
}
