package com.omnitrack.app.utils.scraper

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

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

    fun canonicalizeForComparison(rawUrl: String?): String? =
        canonicalizeForComparison(rawUrl, mutableSetOf())

    private fun canonicalizeForComparison(rawUrl: String?, seen: MutableSet<String>): String? {
        val normalized = normalize(rawUrl) ?: return null
        if (!seen.add(normalized)) return normalized
        val uri = runCatching { URI(normalized).normalize() }.getOrNull() ?: return normalized
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return normalized
        val host = uri.host?.lowercase(Locale.ROOT) ?: return normalized

        val proxyUrl = uri.rawQuery
            ?.split('&')
            ?.firstNotNullOfOrNull(::proxyUrlFromParameter)
        if (proxyUrl != null && !proxyUrl.equals(normalized, ignoreCase = true)) {
            return canonicalizeForComparison(proxyUrl, seen)
        }

        val path = uri.rawPath.orEmpty()
            .split('/')
            .filterNot(sizingSegmentPattern::matches)
            .map { sizedFilenamePattern.replace(it, "") }
            .joinToString("/")
            .trimEnd('/')
        val query = uri.rawQuery
            ?.split('&')
            ?.filterNot(::isNonIdentityQueryParameter)
            ?.filterNot(::isImageTransformDirective)
            ?.sorted()
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString("&")
        val port = uri.port.takeUnless {
            (scheme == "http" && it == 80) || (scheme == "https" && it == 443)
        }
        val authority = buildString {
            uri.rawUserInfo?.let { append(it).append('@') }
            append(if (host.contains(':')) "[$host]" else host)
            port?.let { append(':').append(it) }
        }
        return buildString {
            append(scheme).append("://").append(authority).append(path)
            query?.let { append('?').append(it) }
        }
    }

    private fun isNonIdentityQueryParameter(parameter: String): Boolean {
        val key = parameter.substringBefore('=')
            .let(::decodeQueryComponent)
            .lowercase(Locale.ROOT)
        return key.startsWith("utm_") || key in nonIdentityQueryParameters ||
            key in sizingQueryParameters
    }

    private fun proxyUrlFromParameter(parameter: String): String? {
        val key = decodeQueryComponent(parameter.substringBefore('=')).lowercase(Locale.ROOT)
        if (key !in proxyUrlParameters) return null
        val value = decodeQueryComponent(parameter.substringAfter('=', ""))
        return value.takeIf {
            it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
        }
    }

    private fun decodeQueryComponent(value: String): String =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)

    private fun isImageTransformDirective(parameter: String): Boolean {
        val directive = parameter.substringBefore('=').lowercase(Locale.ROOT)
        return imageTransformDirectives.any(directive::startsWith)
    }

    private val sizingSegmentPattern = Regex("""(?:x|w|h)\d{2,4}|\d{2,4}x\d{2,4}""", RegexOption.IGNORE_CASE)
    private val sizedFilenamePattern = Regex("""[-_]\d{2,4}x\d{2,4}(?=\.[^.]+$)""", RegexOption.IGNORE_CASE)
    private val proxyUrlParameters = setOf("url", "image", "image_url", "src", "source")
    private val nonIdentityQueryParameters = setOf(
        "fbclid", "gclid", "ref", "signature", "sig", "token", "expires"
    )
    private val sizingQueryParameters = setOf(
        "w", "width", "h", "height", "fit", "resize", "quality", "q", "format", "auto", "dpr"
    )
    private val imageTransformDirectives = listOf(
        "imagemogr", "imageview", "thumbnail", "resize", "fit", "quality"
    )
}