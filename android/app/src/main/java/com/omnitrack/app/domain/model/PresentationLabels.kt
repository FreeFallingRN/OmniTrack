package com.omnitrack.app.domain.model

fun String.toDisplayLabel(): String =
    replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

object PublishingStatus {
    val values = listOf("announced", "releasing", "hiatus", "completed", "discontinued")
}
