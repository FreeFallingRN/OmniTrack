package com.omnitrack.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal enum class RescrapeField { TITLE, COVER, STATUS, CREATORS, ALIASES }

internal data class RescrapeChange(
    val field: RescrapeField,
    val label: String,
    val before: String,
    val after: String
)

@Composable
internal fun RescrapePreviewDialog(
    changes: List<RescrapeChange>,
    totalChapters: Double?,
    totalUnitName: String,
    onDismiss: () -> Unit,
    onApply: (Set<RescrapeField>) -> Unit
) {
    var selected by remember { mutableStateOf(emptySet<RescrapeField>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review rescraped details") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                item {
                    Text(
                        "Select changes to copy into the editor, then save the entry. Missing details are not removed.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (changes.isEmpty()) {
                    item { Text("No applicable changes found.", modifier = Modifier.padding(vertical = 8.dp)) }
                }
                items(changes, key = { it.field }) { change ->
                    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                        Checkbox(
                            checked = change.field in selected,
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + change.field else selected - change.field
                            }
                        )
                        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(change.label, style = MaterialTheme.typography.titleSmall)
                            Text("Current: ${change.before.ifBlank { "(empty)" }}")
                            Text("Fetched: ${change.after}")
                        }
                    }
                }
                if (totalChapters != null) {
                    item {
                        val unit = totalUnitName.trim().ifBlank { "chapter" }.lowercase()
                        val pluralUnit = if (unit.endsWith("s", ignoreCase = true)) unit else "${unit}s"
                        Text(
                            "Source total $pluralUnit: $totalChapters (informational; not stored on entries)",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(selected) }, enabled = selected.isNotEmpty()) { Text("Apply selected") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
