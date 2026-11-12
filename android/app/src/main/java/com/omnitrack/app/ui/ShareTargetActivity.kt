package com.omnitrack.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.omnitrack.app.data.SupabaseService
import com.omnitrack.app.data.model.CreateEntryRequest
import com.omnitrack.app.data.model.CreateSeriesRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

class ShareTargetActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedUrl = if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        } else ""

        setContent {
            MaterialTheme {
                ShareSheetUI(sharedUrl = sharedUrl, onDismiss = { finish() })
            }
        }
    }
}

@Composable
fun ShareSheetUI(sharedUrl: String, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("Fetching metadata...") }
    var unitName by remember { mutableStateOf("Chapter") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(sharedUrl) {
        if (sharedUrl.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                try {
                    val html = URL(sharedUrl).readText()
                    val ogTitle = Regex("<meta property=\"og:title\" content=\"(.*?)\"").find(html)?.groupValues?.get(1)
                    title = ogTitle ?: "New Shared Novel"
                } catch (e: Exception) {
                    title = "New Webnovel"
                }
            }
        }
    }

    Surface(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Add to OmniTrack", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") })
            
            Button(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        val series = SupabaseService.createSeries(
                            CreateSeriesRequest(
                                title = title,
                                userStatus = "in_progress",
                                publishingStatus = "releasing",
                                unitName = unitName
                            )
                        )
                        SupabaseService.createEntry(
                            CreateEntryRequest(
                                seriesId = series.id,
                                title = title,
                                type = "novel",
                                sequenceNumber = 1.0,
                                userStatus = "in_progress",
                                publishingStatus = "releasing",
                                unitName = unitName,
                                sourceUrl = sharedUrl
                            )
                        )
                        withContext(Dispatchers.Main) { onDismiss() }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Confirm & Track")
            }
        }
    }
}