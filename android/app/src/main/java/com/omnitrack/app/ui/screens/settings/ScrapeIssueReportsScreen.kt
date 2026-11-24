package com.omnitrack.app.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.omnitrack.app.data.local.AppDatabase
import com.omnitrack.app.data.local.ScrapeIssueReport
import com.omnitrack.app.worker.ScrapeIssueSyncWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

@Composable
fun ScrapeIssueReportsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.getInstance(context).scrapeIssueReportDao() }
    val reports by remember { dao.observeReports() }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<ScrapeIssueReport?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Scrape reports", style = MaterialTheme.typography.titleLarge)
        }
        if (reports.isEmpty()) {
            Text("No saved reports")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(reports, key = { it.id }) { report ->
                    Card(modifier = Modifier.fillMaxWidth().clickable { selected = report }) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(report.sourceUrl, style = MaterialTheme.typography.titleSmall)
                            Text(report.adapterName)
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = if (report.synced) MaterialTheme.colorScheme.secondaryContainer
                                    else MaterialTheme.colorScheme.tertiaryContainer
                            ) {
                                Text(
                                    if (report.synced) "Synced" else "Pending sync",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            Text(Json.decodeFromString<List<String>>(report.issueTagsJson).joinToString(", "))
                            if (report.note.isNotBlank()) Text(report.note)
                            Text(report.createdAt, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    selected?.let { selectedReport ->
        val report = reports.firstOrNull { it.id == selectedReport.id } ?: selectedReport
        AlertDialog(
            onDismissRequest = { selected = null; confirmingDelete = false },
            title = { Text("Scrape report") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("URL: ${report.sourceUrl}")
                    Text("Normalized: ${report.normalizedUrl}")
                    Text("Adapter: ${report.adapterName}")
                    Text("Tags: ${Json.decodeFromString<List<String>>(report.issueTagsJson).joinToString(", ")}")
                    Text("Note: ${report.note}")
                    Text("Created: ${report.createdAt}")
                    Text(if (report.synced) "Synced" else "Pending sync")
                    Text("Scraped details", style = MaterialTheme.typography.titleSmall)
                    Text(remember(report.scrapedSnapshotJson) {
                        Json { prettyPrint = true }.encodeToString(
                            JsonElement.serializer(),
                            Json.parseToJsonElement(report.scrapedSnapshotJson)
                        )
                    })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(
                        "Scrape report",
                        "URL: ${report.sourceUrl}\nNormalized: ${report.normalizedUrl}\nAdapter: ${report.adapterName}\nTags: ${report.issueTagsJson}\nNote: ${report.note}\nCreated: ${report.createdAt}\nSnapshot: ${report.scrapedSnapshotJson}"
                    ))
                    Toast.makeText(context, "Report copied", Toast.LENGTH_SHORT).show()
                }) { Text("Copy") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmingDelete = true }) { Text("Delete") }
                    TextButton(onClick = { selected = null; confirmingDelete = false }) { Text("Close") }
                }
            }
        )
        if (confirmingDelete) {
            AlertDialog(
                onDismissRequest = { confirmingDelete = false },
                title = { Text("Delete report?") },
                text = { Text("The report will also be deleted from Supabase when online.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmingDelete = false
                        scope.launch {
                            try {
                                dao.markDeleted(report.id)
                                selected = null
                                ScrapeIssueSyncWorker.enqueue(context, report.id)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.e("ScrapeIssueReport", "Failed to delete or schedule report deletion", e)
                                Toast.makeText(context, "Could not finish deletion; retry or restart the app", Toast.LENGTH_LONG).show()
                            }
                        }
                    }) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") }
                }
            )
        }
    }
}
