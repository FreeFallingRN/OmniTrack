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
import kotlin.coroutines.resumeWithException
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

                    fun failFetch(message: String, blocked: Boolean = false) {
                        if (!cont.isActive || finished) return
                        finished = true
                        trace?.log("webView: $message")
                        cont.resumeWithException(
                            if (blocked) SiteBlockedException(message)
                            else IllegalStateException("Could not fetch metadata from this source ($message)")
                        )
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

                            if (isBotChallenge(html) || title.contains("Just a moment", ignoreCase = true)) {
                                failFetch("challenge page", blocked = true)
                            } else if (html.isNotBlank() &&
                                !Regex("""<body\b[^>]*>\s*</body>""", RegexOption.IGNORE_CASE).containsMatchIn(html)
                            ) {
                                finished = true
                                trace?.log("webView: hit attempt=$attempt len=${html.length} title=\"${title.take(60)}\"")
                                cont.resume(html)
                            } else if (attempt < 20) {
                                webView.postDelayed({ pollDom(attempt + 1) }, 600L)
                            } else {
                                failFetch("empty response")
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
                                val status = errorResponse?.statusCode
                                failFetch("HTTP $status", blocked = status == 403 || status == 429)
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
            } ?: throw IllegalStateException("Could not fetch metadata from this source (page timed out)")
        } finally {
            webView.destroy()
        }
    }
}
