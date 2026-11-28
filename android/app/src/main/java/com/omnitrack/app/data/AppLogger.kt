package com.omnitrack.app.data

import android.content.Context
import android.os.Process
import android.util.AtomicFile
import android.util.Log
import com.omnitrack.app.BuildConfig
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.util.ArrayDeque
import kotlin.system.exitProcess

enum class AppLogLevel {
    DEBUG, INFO, WARN, ERROR, FATAL
}

object AppLogger {
    private const val TAG = "OmniTrack"
    private const val MAX_BUFFER_BYTES = 1024 * 1024
    private const val MAX_ENTRY_CHARS = 8192
    private val lock = Any()
    private val entries = ArrayDeque<ByteArray>()
    private var bufferBytes = 0
    private lateinit var preferences: OmniTrackPreferences
    private lateinit var fatalFile: AtomicFile
    private var initialized = false

    fun initialize(context: Context) {
        synchronized(lock) {
            if (initialized) return
            preferences = OmniTrackPreferences(context.applicationContext)
            fatalFile = AtomicFile(File(context.noBackupFilesDir, "app-fatal.log"))
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    fatal(crashTrace(throwable))
                } catch (_: Throwable) {
                    Log.e(TAG, "Could not persist fatal logs")
                    try {
                        flush()
                    } catch (_: Throwable) {
                        Log.e(TAG, "Could not flush buffered logs")
                    }
                } finally {
                    if (previousHandler != null) {
                        previousHandler.uncaughtException(thread, throwable)
                    } else {
                        Process.killProcess(Process.myPid())
                        exitProcess(10)
                    }
                }
            }
            initialized = true
        }
    }

    fun debug(message: String, throwable: Throwable? = null, secrets: Collection<String> = emptyList()) =
        log(AppLogLevel.DEBUG, message, throwable, secrets)

    fun info(message: String, throwable: Throwable? = null, secrets: Collection<String> = emptyList()) =
        log(AppLogLevel.INFO, message, throwable, secrets)

    fun warn(message: String, throwable: Throwable? = null, secrets: Collection<String> = emptyList()) =
        log(AppLogLevel.WARN, message, throwable, secrets)

    fun error(message: String, throwable: Throwable? = null, secrets: Collection<String> = emptyList()) =
        log(AppLogLevel.ERROR, message, throwable, secrets)

    fun fatal(message: String, throwable: Throwable? = null, secrets: Collection<String> = emptyList()) {
        log(AppLogLevel.FATAL, message, throwable, secrets)
        try {
            flush()
        } catch (_: IOException) {
            Log.e(TAG, "Could not persist fatal logs")
        }
        LokiLogPush.flushFatal()
    }

    fun snapshot(): String = synchronized(lock) {
        entries.joinToString(separator = "") { it.toString(Charsets.UTF_8) }
    }

    @Throws(IOException::class)
    fun fatalSnapshot(): String? = synchronized(lock) {
        check(initialized) { "AppLogger has not been initialized" }
        try {
            fatalFile.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: FileNotFoundException) {
            if (fatalFile.baseFile.exists() || File("${fatalFile.baseFile.path}.bak").exists()) throw e
            null
        }
    }

    private fun crashTrace(throwable: Throwable): String {
        val seen = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<Throwable, Boolean>()
        )
        val result = StringBuilder("Uncaught exception")
        var current: Throwable? = throwable
        while (current != null && seen.size < 16 && seen.add(current)) {
            result.append("\n").append(current.javaClass.name)
            current.message?.let { result.append(": ").append(it) }
            current.stackTrace.take(256).forEach { result.append("\n  at ").append(it) }
            current = current.cause
        }
        return result.toString()
    }

    @Throws(IOException::class)
    fun flush() {
        synchronized(lock) {
            check(initialized) { "AppLogger has not been initialized" }
            val stream = fatalFile.startWrite()
            try {
                entries.forEach { stream.write(it) }
                fatalFile.finishWrite(stream)
            } catch (e: IOException) {
                fatalFile.failWrite(stream)
                throw e
            }
        }
    }

    private fun log(level: AppLogLevel, message: String, throwable: Throwable?, secrets: Collection<String>) {
        val stackTrace = throwable?.let {
            StringWriter().also { writer -> it.printStackTrace(PrintWriter(writer)) }.toString()
        }
        val text = LogRedaction.redact(
            listOfNotNull(message, stackTrace).joinToString("\n"),
            secrets + listOfNotNull(BuildConfig.SUPABASE_ANON_KEY, LokiLogPush.secret())
        ).take(MAX_ENTRY_CHARS)
        val timestamp = Instant.now()
        val line = "$timestamp ${level.name} $text\n"
        val bytes = line.toByteArray(Charsets.UTF_8)
        synchronized(lock) {
            check(initialized) { "AppLogger has not been initialized" }
            while (bufferBytes + bytes.size > MAX_BUFFER_BYTES) {
                bufferBytes -= entries.removeFirst().size
            }
            entries.addLast(bytes)
            bufferBytes += bytes.size
            LokiLogPush.append(timestamp, level, line)
            if (preferences.isLiveLoggingActive()) {
                val priority = when (level) {
                    AppLogLevel.DEBUG -> Log.DEBUG
                    AppLogLevel.INFO -> Log.INFO
                    AppLogLevel.WARN -> Log.WARN
                    AppLogLevel.ERROR -> Log.ERROR
                    AppLogLevel.FATAL -> Log.ASSERT
                }
                Log.println(priority, TAG, text)
            }
        }
    }
}
