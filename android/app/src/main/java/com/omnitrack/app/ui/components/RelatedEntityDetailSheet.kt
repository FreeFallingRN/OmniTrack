package com.omnitrack.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omnitrack.app.domain.model.MediaEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelatedEntityDetailSheet(
    detail: RelatedEntityDetail,
    onDismiss: () -> Unit,
    onSelectWork: (MediaEntry) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            contentPadding = PaddingValues(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(detail.title, style = MaterialTheme.typography.headlineSmall)
                Text(detail.type, color = MaterialTheme.colorScheme.primary)
            }
            if (detail.works.isNotEmpty()) {
                item {
                    Text(
                        "Works",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                items(detail.works.sortedBy { it.sequenceNumber }, key = { it.id }) { work ->
                    MediaWorkRow(
                        work = work,
                        onClick = { onSelectWork(work) },
                        includeUnitName = true
                    )
                }
            }
        }
    }
}
