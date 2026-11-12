package com.omnitrack.app.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import com.omnitrack.app.domain.model.MediaEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelatedEntityDetailSheet(
    detail: RelatedEntityDetail,
    onDismiss: () -> Unit,
    onSelectWork: (MediaEntry) -> Unit
) {
    EntityDetailSheet(
        title = detail.title,
        subtitle = detail.type,
        body = null,
        imageUrl = detail.imageUrl,
        works = detail.works,
        userStatus = detail.userStatus,
        publishingStatus = detail.publishingStatus,
        onDismiss = onDismiss,
        onEdit = null,
        onSelectWork = onSelectWork,
        onDelete = null
    )
}
