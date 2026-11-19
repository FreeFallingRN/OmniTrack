package com.omnitrack.app.utils.scraper

import java.net.URI

object CoverNormalizer {

    fun normalize(rawUrl: String?, baseUrl: String? = null): String? {
        if (rawUrl.isNullOrBlank()) return null

        val unescaped = rawUrl.trim()
            .replace("\\/", "/")
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("&amp;", "&")
            .replace("\\\"", "\"")

        if (unescaped.isBlank()) return null

        return if (!baseUrl.isNullOrBlank()) {
            runCatching { URI(baseUrl).resolve(unescaped).toString() }.getOrDefault(unescaped)
        } else {
            unescaped
        }
    }
}