package com.omnitrack.app.ui.scraping

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.omnitrack.app.data.OnlineMediaSearchProvider
import com.omnitrack.app.data.OnlineSearchResult
import com.omnitrack.app.ui.LocalFeatureServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

@Composable
fun OnlineSearchDialog(onDismiss: () -> Unit, onSelect: (OnlineSearchResult) -> Unit) {
    val providers = LocalFeatureServices.current.onlineSearchProviders
    var query by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("All") }
    var loading by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<OnlineSearchResult>>(emptyList()) }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(query, source) {
        results = emptyList()
        errors = emptyList()
        searched = false
        loading = query.isNotBlank()
        if (query.isBlank()) return@LaunchedEffect
        delay(400)
        val selected: List<OnlineMediaSearchProvider> = providers.filter {
            source == "All" || it.sourceName == source
        }
        val responses = coroutineScope {
            selected.map { provider ->
                async {
                    try {
                        provider.search(query.trim()) to null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyList<OnlineSearchResult>() to
                            "${provider.sourceName}: ${e.message ?: "Search failed"}"
                    }
                }
            }.awaitAll()
        }
        results = responses.flatMap { it.first }
        errors = responses.mapNotNull { it.second }
        searched = true
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search online") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (listOf("All") + providers.map { it.sourceName }).forEach { name ->
                        FilterChip(
                            selected = source == name,
                            onClick = { source = name },
                            label = { Text(name) }
                        )
                    }
                }
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                }
                errors.forEach { error ->
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (searched && results.isEmpty() && errors.isEmpty()) {
                    Text("No results found.", style = MaterialTheme.typography.bodyMedium)
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(results, key = { "${it.sourceName}:${it.upstreamUrl}" }) { result ->
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth().clickable { onSelect(result) }
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AsyncImage(
                                    model = result.coverUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.width(48.dp).height(68.dp)
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(result.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(result.sourceName, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                                    Text(
                                        listOfNotNull(
                                            result.mediaType?.label ?: "Other",
                                            result.status?.replace('_', ' ')?.lowercase(),
                                            result.chapters?.let { "${it.toBigDecimal().stripTrailingZeros().toPlainString()} chapters" }
                                        ).joinToString(" | "),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
