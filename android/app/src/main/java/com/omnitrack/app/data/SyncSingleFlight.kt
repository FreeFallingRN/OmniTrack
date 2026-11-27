package com.omnitrack.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class SyncSingleFlight<T> {
    private var running: CompletableDeferred<Result<T>>? = null

    suspend fun run(block: suspend () -> T): T {
        while (true) {
            val (result, owner) = synchronized(this) {
                val existing = running
                if (existing != null) existing to false
                else CompletableDeferred<Result<T>>().also { running = it } to true
            }
            if (owner) {
                val outcome = runCatching { block() }
                synchronized(this) {
                    result.complete(outcome)
                    running = null
                }
                return outcome.getOrThrow()
            }
            val outcome = result.await()
            if (outcome.exceptionOrNull() is CancellationException) {
                currentCoroutineContext().ensureActive()
                continue
            }
            return outcome.getOrThrow()
        }
    }
}
