package com.omnitrack.app.ui.components

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.omnitrack.app.data.local.AppDatabase
import com.omnitrack.app.data.local.ScrapeIssueReport
import com.omnitrack.app.utils.ImportedMediaDraft
import com.omnitrack.app.worker.ScrapeIssueSyncWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScrapeIssueReportDialog(imported: ImportedMediaDraft, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tags by remember { mutableStateOf(setOf<String>()) }
    var note by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Report scrape issue") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The URL, scraped details, tags and note sync to Supabase. Anyone with the project anon key can access them.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Title", "Authors", "Status", "Series", "Cover").forEach { tag ->
                        FilterChip(
                            selected = tag in tags,
                            onClick = { tags = if (tag in tags) tags - tag else tags + tag },
                            label = { Text(tag) },
                            enabled = !saving
                        )
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(240) },
                    label = { Text("Note (optional)") },
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let { Text(it) }
            }
        },
        confirmButton = {
            Button(enabled = !saving && (tags.isNotEmpty() || note.isNotBlank()), onClick = {
                saving = true
                scope.launch {
                    val json = Json { encodeDefaults = true }
                    val report = ScrapeIssueReport(
                        id = UUID.randomUUID().toString(),
                        sourceUrl = imported.sourceUrl,
                        normalizedUrl = imported.normalizedUrl,
                        adapterName = imported.adapterName,
                        scrapedSnapshotJson = json.encodeToString(imported.draft),
                        issueTagsJson = json.encodeToString(tags.toList()),
                        note = note.trim(),
                        createdAt = Instant.now().toString()
                    )
                    try {
                        AppDatabase.getInstance(context).scrapeIssueReportDao().insert(report)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("ScrapeIssueReport", "Failed to save report", e)
                        error = "Could not save report: ${e.message}"
                        saving = false
                        return@launch
                    }
                    try {
                        ScrapeIssueSyncWorker.enqueue(context, report.id)
                        Toast.makeText(context, "Report saved", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Log.e("ScrapeIssueReport", "Failed to schedule report sync", e)
                        Toast.makeText(context, "Report saved; sync will retry on startup", Toast.LENGTH_LONG).show()
                    }
                    onDismiss()
                }
            }) { Text("Save report") }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") }
        }
    )
}
