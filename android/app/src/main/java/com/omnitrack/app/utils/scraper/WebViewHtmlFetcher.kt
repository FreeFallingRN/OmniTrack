package com.omnitrack.app.utils.scraper

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONTokener
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

internal class WebViewHtmlFetcher {
    private val json = Json { ignoreUnknownKeys = true }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun getHtml(
        context: Context,
        url: String,
        trace: ExternalMediaScraper.ScrapeTrace?
    ): String = withContext(Dispatchers.Main) {
        val webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = settings.userAgentString.replace("; wv", "")
        }

        try {
            withTimeoutOrNull(15_000L.milliseconds) {
                suspendCancellableCoroutine { cont ->
                    var finished = false

                    fun failFetch(message: String) {
                        if (!cont.isActive || finished) return
                        finished = true
                        trace?.log("webView: $message")
                        cont.resume("")
                    }

                    fun pollDom(attempt: Int) {
                        if (!cont.isActive || finished) return
                        webView.evaluateJavascript(
                            "(function(){ return JSON.stringify({title: document.title, html: document.documentElement.outerHTML}); })()"
                        ) { rawJson ->
                            if (!cont.isActive || finished) return@evaluateJavascript
                            val unescaped = runCatching {
                                JSONTokener(rawJson).nextValue() as? String
                            }.getOrNull().orEmpty()
                            val title = runCatching {
                                json.parseToJsonElement(unescaped).jsonObject["title"]?.jsonPrimitive?.content.orEmpty()
                            }.getOrDefault("")
                            val html = runCatching {
                                json.parseToJsonElement(unescaped).jsonObject["html"]?.jsonPrimitive?.content.orEmpty()
                            }.getOrDefault("")

                            val challenged = html.isBlank() ||
                                isBotChallenge(html) ||
                                title.contains("Just a moment", ignoreCase = true)
                            if (!challenged) {
                                finished = true
                                trace?.log("webView: hit attempt=$attempt len=${html.length} title=\"${title.take(60)}\"")
                                cont.resume(html)
                            } else if (attempt < 20) {
                                webView.postDelayed({ pollDom(attempt + 1) }, 600L)
                            } else {
                                finished = true
                                trace?.log("webView: exhausted attempts=20 len=${html.length} title=\"${title.take(60)}\"")
                                cont.resume("")
                            }
                        }
                    }

                    webView.webViewClient = object : WebViewClient() {
                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                            if (request?.isForMainFrame == true) {
                                failFetch("load failed: ${error?.description}")
                            }
                        }

                        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                            if (request?.isForMainFrame == true) {
                                failFetch("HTTP ${errorResponse?.statusCode}")
                            }
                        }

                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            trace?.log("webView: onPageFinished url=$finishedUrl")
                            pollDom(1)
                        }
                    }
                    cont.invokeOnCancellation { finished = true }
                    webView.loadUrl(url)
                }
            }.orEmpty()
        } finally {
            webView.destroy()
        }
    }
}
