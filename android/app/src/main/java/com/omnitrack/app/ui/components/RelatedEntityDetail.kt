package com.omnitrack.app.ui.components

import com.omnitrack.app.domain.model.MediaEntry

data class RelatedEntityDetail(
    val title: String,
    val type: String,
    val works: List<MediaEntry> = emptyList()
)
