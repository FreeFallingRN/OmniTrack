package com.omnitrack.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TrackedMediaDto(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String,
    @SerialName("media_type") val mediaType: String,
    @SerialName("status") val status: String,
    @SerialName("current_progress") val currentProgress: Int,
    @SerialName("total_count") val totalCount: Int? = null,
    @SerialName("rating") val rating: Float? = null,
    @SerialName("synopsis") val synopsis: String = "",
    @SerialName("cover_url") val coverUrl: String = "",
    @SerialName("is_pinned") val isPinned: Boolean = false,
    @SerialName("external_url") val externalUrl: String? = null
)