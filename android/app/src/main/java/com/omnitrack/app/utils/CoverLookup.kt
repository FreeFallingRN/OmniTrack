package com.omnitrack.app.utils

import java.net.URL
import java.net.HttpURLConnection
import java.io.IOException

object CoverLookup {
    fun findOpenGraphImage(sourceUrl: String): String? {
        val amazonFallback = amazonCoverFallback(sourceUrl)
        val connection = URL(sourceUrl).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
        )
        connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        val html = try {
            if (connection.responseCode !in 200..399) return amazonFallback
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (_: IOException) {
            return amazonFallback
        } finally {
            connection.disconnect()
        }

        val amazonPatterns = listOf(
            Regex("""["'](?:hiRes|large|landingImage)["']\s*:\s*["'](https?:\\/\\/[^"']+)""", RegexOption.IGNORE_CASE),
            Regex("""data-old-hires=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
        )
        amazonPatterns.firstNotNullOfOrNull { it.find(html)?.groupValues?.get(1) }
            ?.let { return normalizeImageUrl(it) }

        val patterns = listOf(
            Regex("""<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+property=["']og:image["']""", RegexOption.IGNORE_CASE),
            Regex("""<meta[^>]+name=["']twitter:image["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        )
        return patterns.firstNotNullOfOrNull { it.find(html)?.groupValues?.get(1) }
            ?.let(::normalizeImageUrl)
            ?: amazonFallback
    }

    private fun normalizeImageUrl(url: String): String {
        val normalized = url
            .replace("\\/", "/")
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("&amp;", "&")
            .replace("\\\"", "\"")

        val webnovelMatch = Regex(
            """https?://book-pic\.webnovel\.com/bookcover/(\d+)/(?:\d+/)?\d+\.jpg\?updateTime=(\d+)""",
            RegexOption.IGNORE_CASE
        ).find(normalized)
        return webnovelMatch?.let {
            "https://book-pic.webnovel.com/bookcover/${it.groupValues[1]}" +
                "?imageMogr2/thumbnail/600x&imageId=${it.groupValues[2]}"
        } ?: normalized
    }

    private fun amazonCoverFallback(sourceUrl: String): String? {
        if (!sourceUrl.contains("amazon.", ignoreCase = true)) return null
        val asin = Regex("""/(?:dp|gp/product)/([A-Z0-9]{10})""", RegexOption.IGNORE_CASE)
            .find(sourceUrl)
            ?.groupValues
            ?.get(1)
            ?: return null
        return "https://m.media-amazon.com/images/P/$asin.01.LZZZZZZZ.jpg"
    }
}
