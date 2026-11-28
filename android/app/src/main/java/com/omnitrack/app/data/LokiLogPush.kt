package com.omnitrack.app.data

import android.os.SystemClock
import android.util.Base64
import android.util.Log
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
    private const val MAX_BATCH_ENTRIES = 200
    private const val COOLDOWN_MS = 30_000L
    private const val MAX_ATTEMPTS = 2
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
    private data class Entry(val timestamp: String, val level: AppLogLevel, val channel: ReleaseChannel, val line: String, val bytes: Int)
    private val queue = ArrayDeque<Entry>()
    private var queueBytes = 0
    private var config: Config? = null
    private var preferences: OmniTrackPreferences? = null
    private var job: Job? = null
    private var inFlight = false
    private var lastAttempt = -COOLDOWN_MS
    private var sessionBytes = 0
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
            _active.value = true
            _status.value = "Loki upload active"
        }
    }

    fun stop() = synchronized(lock) {
        config = null
        preferences = null
        _active.value = false
        _status.value = "Loki upload stopped"
        job?.cancel()
        job = null
        queue.clear()
        queueBytes = 0
    }

    fun secret(): String? = synchronized(lock) { config?.token }

    fun append(timestamp: Instant, level: AppLogLevel, line: String): Unit = synchronized(lock) {
        val prefs = preferences ?: return
        val current = config ?: return
        if (!prefs.isLiveLoggingActive()) return
        val safeLine = LogRedaction.redact(line, listOf(current.token, current.authorization))
        val entry = Entry("${timestamp.epochSecond * 1_000_000_000L + timestamp.nano}", level,
            prefs.releaseChannel(), safeLine, safeLine.toByteArray(Charsets.UTF_8).size)
        while ((queueBytes + entry.bytes > MAX_QUEUE_BYTES || queue.size >= MAX_QUEUE_ENTRIES) && queue.isNotEmpty()) {
            queueBytes -= queue.removeFirst().bytes
            report("Loki queue limit reached; oldest entries discarded")
        }
        if (entry.bytes > MAX_QUEUE_BYTES) {
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
                val batch = synchronized(lock) { takeBatch(fatal = true) }
                if (batch == null) {
                    if (_active.value) report("Loki fatal upload deferred by cooldown or an active upload")
                    return@withTimeoutOrNull true
                }
                try {
                    withContext(Dispatchers.IO) { send(batch, fatal = true) }
                } finally {
                    synchronized(lock) {
                        inFlight = false
                        schedule()
                    }
                }
                true
            } ?: false
            if (!finished) report("Loki fatal upload timed out")
        }
    }

    private data class Batch(val config: Config, val body: String, val bytes: Int)

    private fun schedule() {
        if (config == null || preferences?.isLiveLoggingActive() != true ||
            queue.isEmpty() || inFlight || job?.isActive == true) return
        job = scope.launch(start = CoroutineStart.LAZY) { drain() }.also { it.start() }
    }

    private fun takeBatch(fatal: Boolean): Batch? {
        val current = config ?: return null
        if ((!fatal && preferences?.isLiveLoggingActive() != true) || inFlight || queue.isEmpty()) return null
        if (SystemClock.elapsedRealtime() - lastAttempt < COOLDOWN_MS) return null
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
        if (sessionBytes + bytes > MAX_SESSION_BYTES) {
            report("Loki session upload limit reached")
            queue.clear()
            queueBytes = 0
            return null
        }
        selected.forEach {
            queue.remove(it)
            queueBytes -= it.bytes
        }
        inFlight = true
        lastAttempt = SystemClock.elapsedRealtime()
        sessionBytes += bytes
        return Batch(current, body, bytes)
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
                    var success = send(batch)
                    var attempts = 1
                    while (!success && attempts < MAX_ATTEMPTS) {
                        delay(COOLDOWN_MS.milliseconds)
                        val allowed = synchronized(lock) {
                            if (config !== batch.config || preferences?.isLiveLoggingActive() != true ||
                                sessionBytes + batch.bytes > MAX_SESSION_BYTES) false
                            else {
                                lastAttempt = SystemClock.elapsedRealtime()
                                sessionBytes += batch.bytes
                                true
                            }
                        }
                        if (!allowed) break
                        success = send(batch)
                        attempts++
                    }
                    if (!success) report("Loki upload failed; batch discarded after bounded retries")
                } finally {
                    synchronized(lock) { inFlight = false }
                }
            }
        } finally {
            val currentJob = currentCoroutineContext()[Job]
            synchronized(lock) {
                if (job === currentJob) job = null
                schedule()
            }
        }
    }

    private suspend fun send(batch: Batch, fatal: Boolean = false): Boolean {
        val allowed = synchronized(lock) {
            config === batch.config && (fatal || preferences?.isLiveLoggingActive() == true)
        }
        if (!allowed) return false
        return try {
            val response = client.post(batch.config.url) {
                header(HttpHeaders.Authorization, batch.config.authorization)
                contentType(ContentType.Application.Json)
                setBody(batch.body)
            }
            if (response.status.value in 200..299) {
                synchronized(lock) {
                    if (config === batch.config) _status.value = "Loki upload succeeded"
                }
                true
            } else {
                report("Loki upload failed (HTTP ${response.status.value})")
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            report("Loki upload failed; check connection and credentials")
            false
        }
    }

    private fun report(message: String) {
        _status.value = message
        Log.w("OmniTrack", message)
    }
}
