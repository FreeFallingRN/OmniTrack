package com.omnitrack.app.ui.components

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Size
import com.omnitrack.app.domain.model.MediaEntry
import com.omnitrack.app.domain.model.toDisplayLabel
import com.omnitrack.app.ui.screens.ShareFormatStyle
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MediaShareDialog(entry: MediaEntry, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val style = remember {
        val prefs = context.getSharedPreferences("omnitrack_prefs", Context.MODE_PRIVATE)
        ShareFormatStyle.fromKey(prefs.getString(ShareFormatStyle.PREFERENCE_KEY, null))
    }
    val coverUrl = entry.coverUrl?.takeIf { it.isNotBlank() }
    val sourceUrl = entry.sourceUrl?.takeIf { it.isNotBlank() }
    val alternativeTitles = entry.alternativeTitles.filter { it.isNotBlank() }
    var includeTitle by remember(entry.id) { mutableStateOf(true) }
    var includeCover by remember(entry.id, coverUrl) { mutableStateOf(coverUrl != null) }
    var includeUrl by remember(entry.id, sourceUrl) { mutableStateOf(sourceUrl != null) }
    var includePublishing by remember(entry.id) { mutableStateOf(true) }
    var includeAlternatives by remember(entry.id) { mutableStateOf(false) }
    var includeProgress by remember(entry.id) { mutableStateOf(false) }
    var coverUri by remember(entry.id, coverUrl) { mutableStateOf<Uri?>(null) }
    var coverLoading by remember(entry.id, coverUrl) { mutableStateOf(coverUrl != null) }
    var coverFailed by remember(entry.id, coverUrl) { mutableStateOf(false) }

    LaunchedEffect(includeCover, coverUrl) {
        if (includeCover && coverUrl != null) {
            coverLoading = true
            coverFailed = false
            coverUri = resolveShareCover(context, coverUrl)
            coverFailed = coverUri == null
            coverLoading = false
        } else {
            coverUri = null
            coverLoading = false
            coverFailed = false
        }
    }

    val text = formatShareText(
        entry = entry,
        style = style,
        includeTitle = includeTitle,
        includeUrl = includeUrl && sourceUrl != null,
        hasCover = includeCover && coverUri != null,
        includePublishing = includePublishing,
        includeAlternatives = includeAlternatives,
        includeProgress = includeProgress
    )
    val canSend = !coverLoading && (text.isNotBlank() || (includeCover && coverUri != null))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share to Discord") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ShareToggle("Title", includeTitle) { includeTitle = it }
                ShareToggle("Cover", includeCover, enabled = coverUrl != null) {
                    includeCover = it
                    coverLoading = it && coverUrl != null
                }
                ShareToggle("URL", includeUrl, enabled = sourceUrl != null) { includeUrl = it }
                ShareToggle("Publishing status", includePublishing) { includePublishing = it }
                ShareToggle("Alternative titles", includeAlternatives, enabled = alternativeTitles.isNotEmpty()) {
                    includeAlternatives = it
                }
                ShareToggle("Progress + user status", includeProgress) { includeProgress = it }

                if (coverLoading) {
                    Text("Preparing cover...", style = MaterialTheme.typography.bodySmall)
                } else if (coverFailed) {
                    Text(
                        "Cover unavailable; sharing text only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Text("Preview · ${style.label}", style = MaterialTheme.typography.labelLarge)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(
                        text = text.ifBlank { if (coverUri != null && includeCover) "Cover image only" else "Nothing selected" },
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = !coverLoading && text.isNotBlank(),
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Discord share", text))
                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                ) { Text("Copy") }
                Button(
                    enabled = canSend,
                    onClick = {
                        val uri = coverUri.takeIf { includeCover }
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = if (uri != null) "image/*" else "text/plain"
                            if (text.isNotBlank()) putExtra(Intent.EXTRA_TEXT, text)
                            if (uri != null) {
                                putExtra(Intent.EXTRA_STREAM, uri)
                                clipData = ClipData.newUri(context.contentResolver, "Cover", uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                        }
                        try {
                            context.startActivity(Intent.createChooser(intent, "Share entry"))
                            onDismiss()
                        } catch (e: ActivityNotFoundException) {
                            Log.w("MediaShare", "No app available to share entry", e)
                            Toast.makeText(context, "No app available to share", Toast.LENGTH_LONG).show()
                        }
                    }
                ) { Text("Share") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ShareToggle(label: String, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onCheckedChange(!checked) },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

private fun formatShareText(
    entry: MediaEntry,
    style: ShareFormatStyle,
    includeTitle: Boolean,
    includeUrl: Boolean,
    hasCover: Boolean,
    includePublishing: Boolean,
    includeAlternatives: Boolean,
    includeProgress: Boolean
): String {
    fun clean(value: String) = value.trim().replace(Regex("\\s+"), " ")
    fun escape(value: String) = clean(value).replace("\\", "\\\\")
        .replace("*", "\\*").replace("_", "\\_").replace("~", "\\~")
        .replace("`", "\\`").replace("[", "\\[").replace("]", "\\]")

    val title = buildString {
        append(escape(entry.title))
        entry.seriesTitle?.takeIf { it.isNotBlank() }?.let { append(" — ${escape(it)}") }
        entry.sequenceNumber?.let { append(" #${formatUnit(it)}") }
    }
    val url = clean(entry.sourceUrl.orEmpty())
    val heading = when {
        includeUrl && hasCover -> {
            val label = if (includeTitle) title else "Link"
            "[$label](${url.replace("(", "%28").replace(")", "%29").replace(" ", "%20")})"
        }
        includeTitle -> "**$title**"
        else -> ""
    }
    val details = buildList {
        if (includePublishing) add("Publishing: ${escape(entry.publishingStatus.toDisplayLabel())}")
        if (includeAlternatives) {
            val alternatives = entry.alternativeTitles.filter { it.isNotBlank() }.joinToString(", ") { escape(it) }
            if (alternatives.isNotEmpty()) add("Also known as: $alternatives")
        }
        if (includeProgress) add("${escape(entry.unitName)} ${formatUnit(entry.currentUnit)} · ${escape(entry.userStatus.label)}")
        if (includeUrl && !hasCover) add(url)
    }
    return when (style) {
        ShareFormatStyle.RICH_MARKDOWN -> (listOf(heading) + details).filter { it.isNotBlank() }.joinToString("\n")
        ShareFormatStyle.COMPACT_CARD -> listOf(heading, details.joinToString(" | "))
            .filter { it.isNotBlank() }.joinToString("\n")
    }
}

private suspend fun resolveShareCover(context: Context, url: String): Uri? = withContext(Dispatchers.IO) {
    try {
        val key = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val directory = File(context.cacheDir, "share_covers")
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Could not create cover cache directory")
        }
        val file = File(directory, "$key.png")
        if (!file.isFile || file.length() == 0L) {
            val request = ImageRequest.Builder(context).data(url).size(Size.ORIGINAL).allowHardware(false).build()
            when (val result = context.imageLoader.execute(request)) {
                is SuccessResult -> {
                    val temporary = File.createTempFile("cover_", ".tmp", directory)
                    try {
                        temporary.outputStream().use { output ->
                            if (!result.drawable.toBitmap().compress(Bitmap.CompressFormat.PNG, 100, output)) {
                                throw IOException("Could not encode cover image")
                            }
                        }
                        if (!temporary.renameTo(file)) throw IOException("Could not save cover image")
                    } finally {
                        temporary.delete()
                    }
                }
                is ErrorResult -> {
                    Log.w("MediaShare", "Could not load cover image", result.throwable)
                    return@withContext null
                }
            }
        }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    } catch (e: IOException) {
        Log.w("MediaShare", "Could not save cover image", e)
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: RuntimeException) {
        Log.w("MediaShare", "Could not prepare cover image", e)
        null
    }
}
