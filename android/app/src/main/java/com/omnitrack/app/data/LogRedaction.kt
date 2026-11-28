package com.omnitrack.app.data

internal object LogRedaction {
    private val bearer = Regex("""(?i)\bBearer\s+[^\s,;"']+""")
    private val jwt = Regex("""\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b""")
    private val sensitiveHeader = Regex(
        """(?im)^(\s*(?:authorization|proxy-authorization|cookie|set-cookie)\s*:\s*)[^\r\n]*"""
    )
    private val authorization = Regex(
        """(?i)(\bauthorization["']?\s*[:=]\s*["']?(?:bearer|basic)\s+)[^\s,;"']+"""
    )
    private val secret = Regex(
        """(?i)\b(access[_-]?token|refresh[_-]?token|id[_-]?token|token|authorization|proxy[_-]?authorization|api[_-]?key|apikey|client[_-]?secret|secret|password|passwd|cookie|set-cookie|code[_-]?verifier)(["']?\s*[:=]\s*)(?:"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[^\s,;&}]+)"""
    )
    private val querySecret = Regex(
        """(?i)([?&](?:access_token|refresh_token|id_token|token|key|api_key|apikey|client_secret|password|code|code_verifier)=)[^&\s#]+"""
    )

    fun redact(value: String, secrets: Collection<String> = emptyList()): String = secrets
        .filter { it.length >= 4 }
        .fold(value) { message, knownSecret -> message.replace(knownSecret, "[REDACTED]") }
        .replace(sensitiveHeader, "$1[REDACTED]")
        .replace(authorization, "$1[REDACTED]")
        .replace(bearer, "Bearer [REDACTED]")
        .replace(jwt, "[REDACTED]")
        .replace(querySecret, "$1[REDACTED]")
        .replace(secret, "$1$2[REDACTED]")
}
