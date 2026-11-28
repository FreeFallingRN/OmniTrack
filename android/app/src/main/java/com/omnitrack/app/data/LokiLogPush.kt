package com.omnitrack.app.data

import android.os.SystemClock
import android.util.Base64
import com.omnitrack.app.domain.model.ReleaseChannel
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URISyntaxException
import java.time.Instant
import java.util.ArrayDeque
import kotlin.time.Duration.Companion.milliseconds

internal object LokiLogPush {
    private const val MAX_BATCH_BYTES = 64 * 1024
    private const val MAX_SESSION_BYTES = 512 * 1024
    private const val MAX_QUEUE_BYTES = 256 * 1024
    private const val MAX_QUEUE_ENTRIES = 1_000
    private const val FATAL_RESERVE_BYTES = 40 * 1024
    private const val MAX_BATCH_ENTRIES = 200
    private const val COOLDOWN_MS = 30_000L
    private const val MAX_BACKOFF_MS = 5 * 60_000L
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = HttpClient(CIO) {
        followRedirects = false
        install(HttpTimeout) {
            connectTimeoutMillis = 3_000
            requestTimeoutMillis = 5_000
            socketTimeoutMillis = 3_000
        }
    }
    private data class Config(val url: String, val authorization: String, val token: String)
    private class Entry(val timestamp: String, val level: AppLogLevel, val channel: ReleaseChannel, val line: String, val bytes: Int)
    private val queue = ArrayDeque<Entry>()
    private val protectedEntries = mutableMapOf<Entry, Int>()
    private var queueBytes = 0
    private var config: Config? = null
    private var preferences: OmniTrackPreferences? = null
    private var job: Job? = null
    private var inFlight = false
    private var lastAttempt = -COOLDOWN_MS
    private var sessionBytes = 0
    private var sessionLimitReached = false
    private var retryDelay = COOLDOWN_MS
    private val _status = MutableStateFlow("Loki upload stopped")
    val status = _status.asStateFlow()
    private val _active = MutableStateFlow(false)
    val active = _active.asStateFlow()

    fun start(preferences: OmniTrackPreferences, url: String, user: String, token: String) {
        val uri = try {
            URI(url.trim())
        } catch (e: URISyntaxException) {
            throw IllegalArgumentException("Enter a valid HTTPS Loki push URL", e)
        }
        require(url.length <= 2048 && uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath == "/loki/api/v1/push") { "Enter an HTTPS Loki push URL without credentials or query parameters" }
        require(user.isNotBlank() && user.length <= 128 && ':' !in user && user.none { it.isISOControl() }) { "Enter a valid Loki user" }
        require(token.length in 4..4096 && token.none { it.isISOControl() }) { "Enter a valid Loki token" }
        require(preferences.isLiveLoggingActive()) { "Enable Beta live logging first" }
        val basic = Base64.encodeToString("$user:$token".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        synchronized(lock) {
            check(config == null) { "Loki upload is already running" }
            this.preferences = preferences
            config = Config(uri.toASCIIString(), "Basic $basic", token)
            sessionBytes = 0
            sessionLimitReached = false
            retryDelay = COOLDOWN_MS
            lastAttempt = -COOLDOWN_MS
            _active.value = true
            _status.value = "Loki upload active"
            schedule()
        }
    }

    fun stop() = synchronized(lock) {
        config = null
        preferences = null
        _active.value = false
        _status.value = "Loki upload stopped; pending entries retained until restart"
        job?.cancel()
        job = null
    }

    fun secret(): String? = synchronized(lock) { config?.token }

    fun append(timestamp: Instant, level: AppLogLevel, line: String): Unit = synchronized(lock) {
        val prefs = preferences ?: return
        val current = config ?: return
        if (!prefs.isLiveLoggingActive()) return
        val safeLine = LogRedaction.redact(line, listOf(current.token, current.authorization))
        val entry = Entry("${timestamp.epochSecond * 1_000_000_000L + timestamp.nano}", level,
            prefs.releaseChannel(), safeLine, safeLine.toByteArray(Charsets.UTF_8).size)
        val byteLimit = if (level == AppLogLevel.FATAL) MAX_QUEUE_BYTES else MAX_QUEUE_BYTES - FATAL_RESERVE_BYTES
        val entryLimit = if (level == AppLogLevel.FATAL) MAX_QUEUE_ENTRIES else MAX_QUEUE_ENTRIES - 1
        while ((queueBytes + entry.bytes > byteLimit || queue.size >= entryLimit) && queue.isNotEmpty()) {
            val oldest = queue.firstOrNull { it !in protectedEntries && it.level != AppLogLevel.FATAL }
                ?: if (level == AppLogLevel.FATAL) queue.firstOrNull { it !in protectedEntries } else null
            if (oldest == null) {
                report("Loki queue limit reached; new entry discarded while uploads are in progress")
                return
            }
            queue.remove(oldest)
            queueBytes -= oldest.bytes
            report("Loki queue limit reached; oldest entries discarded")
        }
        if (entry.bytes > byteLimit) {
            report("Loki entry exceeded the queue limit")
            return
        }
        queue.addLast(entry)
        queueBytes += entry.bytes
        schedule()
    }

    fun flushFatal() {
        runBlocking {
            val finished = withTimeoutOrNull(3_000.milliseconds) {
                val batch = synchronized(lock) { takeBatch(fatal = true) } ?: return@withTimeoutOrNull true
                try {
                    val error = withContext(Dispatchers.IO) { send(batch) }
                    synchronized(lock) { complete(batch, error) }
                } finally {
                    synchronized(lock) {
                        unprotect(batch.entries)
                        schedule()
                    }
                }
                true
            } ?: false
            if (!finished) report("Loki fatal upload timed out")
        }
    }

    private data class Batch(val config: Config, val body: String, val entries: List<Entry>)

    suspend fun sendTest(): String {
        val batch = synchronized(lock) {
            val current = checkNotNull(config) { "Start Loki upload first" }
            val prefs = checkNotNull(preferences) { "Start Loki upload first" }
            check(prefs.isLiveLoggingActive()) { "Enable Beta live logging first" }
            val timestamp = Instant.now()
            val line = "$timestamp INFO Loki test log\n"
            val entry = Entry("${timestamp.epochSecond * 1_000_000_000L + timestamp.nano}", AppLogLevel.INFO,
                prefs.releaseChannel(), line, line.toByteArray(Charsets.UTF_8).size)
            val body = payload(listOf(entry))
            val bytes = body.toByteArray(Charsets.UTF_8).size
            check(sessionBytes + bytes <= MAX_SESSION_BYTES - MAX_BATCH_BYTES) { "Loki session upload limit reached; test not sent" }
            sessionBytes += bytes
            Batch(current, body, emptyList())
        }
        val error = withContext(Dispatchers.IO) { send(batch) }
        return if (error == null) "Test log sent successfully" else "Test log failed: $error"
    }

    private fun protect(entries: List<Entry>) {
        entries.forEach { protectedEntries[it] = (protectedEntries[it] ?: 0) + 1 }
    }

    private fun unprotect(entries: List<Entry>) {
        entries.forEach {
            val count = protectedEntries[it] ?: return@forEach
            if (count == 1) protectedEntries.remove(it) else protectedEntries[it] = count - 1
        }
    }

    private fun complete(batch: Batch, error: String?) {
        if (config !== batch.config) return
        if (error == null) {
            batch.entries.forEach { if (queue.remove(it)) queueBytes -= it.bytes }
            _status.value = "Loki upload succeeded"
        } else {
            val detail = if (batch.entries.any { it in queue }) "entries retained for retry"
                else "entries already sent by another upload"
            report("Loki upload failed: $error; $detail")
        }
    }

    private fun schedule() {
        if (config == null || preferences?.isLiveLoggingActive() != true ||
            queue.isEmpty() || inFlight || sessionLimitReached || job?.isActive == true) return
        job = scope.launch(start = CoroutineStart.LAZY) { drain() }.also { it.start() }
    }

    private fun takeBatch(fatal: Boolean): Batch? {
        val current = config ?: return null
        if (preferences?.isLiveLoggingActive() != true || (!fatal && inFlight) || queue.isEmpty()) return null
        if (!fatal && SystemClock.elapsedRealtime() - lastAttempt < COOLDOWN_MS) return null
        val selected = mutableListOf<Entry>()
        var body = ""
        val candidates = if (fatal) listOfNotNull(queue.lastOrNull { it.level == AppLogLevel.FATAL }) else queue.toList()
        for (entry in candidates) {
            if (selected.size == MAX_BATCH_ENTRIES) break
            val candidate = payload(selected + entry)
            if (candidate.toByteArray(Charsets.UTF_8).size > MAX_BATCH_BYTES) break
            selected.add(entry)
            body = candidate
        }
        if (selected.isEmpty()) {
            if (candidates.isNotEmpty()) {
                queue.remove(candidates.first())
                queueBytes -= candidates.first().bytes
                report("Loki entry exceeded the batch limit")
            }
            return null
        }
        val bytes = body.toByteArray(Charsets.UTF_8).size
        val limit = if (fatal) MAX_SESSION_BYTES else MAX_SESSION_BYTES - MAX_BATCH_BYTES
        if (sessionBytes + bytes > limit) {
            if (!fatal) sessionLimitReached = true
            report("Loki session upload limit reached; entries retained until upload is restarted")
            return null
        }
        protect(selected)
        if (!fatal) {
            inFlight = true
            lastAttempt = SystemClock.elapsedRealtime()
        }
        sessionBytes += bytes
        return Batch(current, body, selected)
    }

    private fun payload(entries: List<Entry>): String = buildJsonObject {
        put("streams", buildJsonArray {
            entries.groupBy { it.channel to it.level }.forEach { (labels, values) ->
                add(buildJsonObject {
                    put("stream", buildJsonObject {
                        put("app", "omnitrack")
                        put("channel", labels.first.name.lowercase())
                        put("level", labels.second.name.lowercase())
                    })
                    put("values", buildJsonArray {
                        values.sortedBy { it.timestamp.toLong() }.forEach { entry ->
                            add(buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(entry.timestamp)); add(kotlinx.serialization.json.JsonPrimitive(entry.line)) })
                        }
                    })
                })
            }
        })
    }.toString()

    private suspend fun drain() {
        try {
            delay(1_000.milliseconds)
            while (true) {
                val wait = synchronized(lock) {
                    if (config == null || preferences?.isLiveLoggingActive() != true || queue.isEmpty()) return
                    (COOLDOWN_MS - (SystemClock.elapsedRealtime() - lastAttempt)).coerceAtLeast(0)
                }
                if (wait > 0) delay(wait.milliseconds)
                val batch = synchronized(lock) { takeBatch(fatal = false) } ?: return
                try {
                    val error = send(batch)
                    synchronized(lock) {
                        complete(batch, error)
                        if (error == null) retryDelay = COOLDOWN_MS
                        else retryDelay = (retryDelay * 2).coerceAtMost(MAX_BACKOFF_MS)
                    }
                } finally {
                    synchronized(lock) {
                        unprotect(batch.entries)
                        inFlight = false
                    }
                }
                val retryWait = synchronized(lock) { (retryDelay - (SystemClock.elapsedRealtime() - lastAttempt)).coerceAtLeast(0) }
                if (retryWait > 0) delay(retryWait.milliseconds)
            }
        } finally {
            val currentJob = currentCoroutineContext()[Job]
            synchronized(lock) {
                if (job === currentJob) job = null
                schedule()
            }
        }
    }

    private suspend fun send(batch: Batch): String? {
        val allowed = synchronized(lock) {
            config === batch.config && preferences?.isLiveLoggingActive() == true
        }
        if (!allowed) return "upload stopped"
        return try {
            val response = client.post(batch.config.url) {
                header(HttpHeaders.Authorization, batch.config.authorization)
                contentType(ContentType.Application.Json)
                setBody(batch.body)
            }
            if (response.status.value in 200..299) {
                null
            } else {
                "HTTP ${response.status.value}"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            "check connection and credentials"
        }
    }

    private fun report(message: String) {
        _status.value = message
    }
}
