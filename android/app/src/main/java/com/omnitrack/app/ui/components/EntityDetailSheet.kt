package com.omnitrack.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.omnitrack.app.domain.model.MediaEntry
import com.omnitrack.app.domain.model.toDisplayLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntityDetailSheet(
    title: String,
    subtitle: String?,
    body: String?,
    imageUrl: String?,
    works: List<MediaEntry>,
    userStatus: String?,
    publishingStatus: String?,
    onDismiss: () -> Unit,
    onEdit: (() -> Unit)?,
    onSelectWork: (MediaEntry) -> Unit,
    onDelete: (() -> Unit)?
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                if (!imageUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = "$title cover",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().height(180.dp)
                    )
                }
                Text(title, style = MaterialTheme.typography.headlineSmall)
                subtitle?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary)
                }
                if (userStatus != null || publishingStatus != null) {
                    androidx.compose.foundation.layout.Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        userStatus?.let { StatusPill(it) }
                        publishingStatus?.let { StatusPill(it.toDisplayLabel()) }
                    }
                }
                body?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (works.isNotEmpty()) {
                item {
                    Text("Works", style = MaterialTheme.typography.titleMedium)
                }
                items(works.sortedBy { it.sequenceNumber }, key = { it.id }) { work ->
                    MediaWorkRow(
                        work = work,
                        onClick = { onSelectWork(work) },
                        includeUnitName = true
                    )
                }
            }

            item {
                onEdit?.let {
                    Button(onClick = it, modifier = Modifier.fillMaxWidth()) {
                        Text("Edit")
                    }
                }
                onDelete?.let {
                    OutlinedButton(onClick = it, modifier = Modifier.fillMaxWidth()) {
                        Text("Delete")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(status: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(50)
    ) {
        Text(
            status,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}
