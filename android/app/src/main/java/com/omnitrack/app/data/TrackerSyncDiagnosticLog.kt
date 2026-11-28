package com.omnitrack.app.data

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class TrackerSyncDiagnosticSource(
    val outcome: String,
    val updated: Int = 0,
    val skipped: Int = 0,
    val errors: Int = 0,
    val details: List<String> = emptyList()
)

@Serializable
data class TrackerSyncDiagnosticEntry(
    val timestamp: Long,
    val trigger: String,
    val durationMs: Long,
    val moonReader: TrackerSyncDiagnosticSource,
    val moonFilesScanned: Int = 0,
    val moonFilesSkipped: Int = 0,
    val moonFilesMatched: Int = 0,
    val mangaBaka: TrackerSyncDiagnosticSource,
    val aniList: TrackerSyncDiagnosticSource,
    val unmatchedCount: Int = 0,
    val reviewCount: Int = 0,
    val unmatchedItems: List<String> = emptyList(),
    val reviewItems: List<String> = emptyList(),
    val errors: List<String> = emptyList()
)

object TrackerSyncDiagnosticLog {
    private const val PREFERENCES = "tracker_sync_diagnostics"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 30
    private val json = Json { prettyPrint = true }

    fun append(context: Context, entry: TrackerSyncDiagnosticEntry, secrets: Collection<String>) {
        try {
            val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            val entries = preferences.getString(KEY_ENTRIES, null)
                ?.let { json.decodeFromString<List<TrackerSyncDiagnosticEntry>>(it) }
                .orEmpty()
            val sanitized = entry.copy(
                moonReader = entry.moonReader.sanitized(secrets),
                mangaBaka = entry.mangaBaka.sanitized(secrets),
                aniList = entry.aniList.sanitized(secrets),
                unmatchedItems = entry.unmatchedItems.take(20).map { redact(it, secrets) },
                reviewItems = entry.reviewItems.take(20).map { redact(it, secrets) },
                errors = entry.errors.take(10).map { redact(it, secrets) }
            )
            check(preferences.edit().putString(
                KEY_ENTRIES,
                json.encodeToString((entries + sanitized).takeLast(MAX_ENTRIES))
            ).commit()) { "Could not persist sync diagnostics" }
        } catch (e: Exception) {
            Log.e("TrackerSyncDiagnostics", "Could not persist sync diagnostics (${e.javaClass.simpleName})")
        }
    }

    fun recent(context: Context): String {
        val saved = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(KEY_ENTRIES, null) ?: return "[]"
        return json.encodeToString(json.decodeFromString<List<TrackerSyncDiagnosticEntry>>(saved))
    }

    private fun TrackerSyncDiagnosticSource.sanitized(secrets: Collection<String>) =
        copy(details = details.take(12).map { redact(it, secrets) })

    private fun redact(value: String, secrets: Collection<String>): String =
        LogRedaction.redact(value, secrets).take(200)
}
