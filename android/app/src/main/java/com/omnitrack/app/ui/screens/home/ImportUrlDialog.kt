package com.omnitrack.app.ui.screens.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import com.omnitrack.app.utils.ScrapedMediaDraft
import kotlinx.coroutines.launch

@Composable
fun ImportUrlDialog(
    onDismiss: () -> Unit,
    onImport: suspend (String) -> ScrapedMediaDraft,
    onImported: (ScrapedMediaDraft) -> Unit
) {
    var importUrl by remember { mutableStateOf("") }
    var isImporting by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!isImporting) onDismiss() },
        title = { Text("Import from link") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Paste a URL from MyAnimeList, Royal Road, MangaFire, or any supported site.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = importUrl,
                    onValueChange = {
                        importUrl = it
                        importError = null
                    },
                    label = { Text("Media URL") },
                    singleLine = true,
                    enabled = !isImporting,
                    modifier = Modifier.fillMaxWidth()
                )
                if (importError != null) {
                    Text(
                        text = importError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = importUrl.isNotBlank() && !isImporting,
                onClick = {
                    coroutineScope.launch {
                        isImporting = true
                        importError = null
                        try {
                            onImported(onImport(importUrl))
                        } catch (e: Exception) {
                            importError = e.message ?: "Failed to fetch media details"
                        } finally {
                            isImporting = false
                        }
                    }
                }
            ) {
                Text(if (isImporting) "Importing..." else "Fetch")
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isImporting,
                onClick = onDismiss
            ) {
                Text("Cancel")
            }
        }
    )
}
