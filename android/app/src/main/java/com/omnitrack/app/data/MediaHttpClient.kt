package com.omnitrack.app.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout

internal object MediaHttpClient {
    val client = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000L
            requestTimeoutMillis = 20_000L
            socketTimeoutMillis = 15_000L
        }
    }
}
