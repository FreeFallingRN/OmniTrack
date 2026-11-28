package com.omnitrack.app.utils.scraper

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.omnitrack.app.data.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONTokener
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class WebViewHtmlFetcher {
    private val json = Json { ignoreUnknownKeys = true }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun getHtml(
        context: Context,
        url: String,
        trace: ExternalMediaScraper.ScrapeTrace?
    ): String = withContext(Dispatchers.Main) {
        val cookieManager = CookieManager.getInstance().apply { setAcceptCookie(true) }
        val webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
        }
        var mainFrameStatus: Int? = null
        // This WebView is never attached, so View.postDelayed may never run.
        val handler = Handler(Looper.getMainLooper())

        try {
            suspendCancellableCoroutine { cont ->
                var finished = false
                var pageGeneration = 0
                var domScheduled = false
                var extractionStarted = false

                fun failFetch(message: String, blocked: Boolean = false) {
                    if (!cont.isActive || finished) return
                    finished = true
                    trace?.log("webView: $message")
                    if (blocked) {
                        AppLogger.warn("[WebViewHtmlFetcher] Fetch blocked: $message")
                    } else {
                        AppLogger.error("[WebViewHtmlFetcher] Fetch failed: $message")
                    }
                    cont.resumeWithException(
                        if (blocked) SiteBlockedException(message, mainFrameStatus)
                        else IllegalStateException("Could not fetch metadata from this source ($message)")
                    )
                }

                val timeout = Runnable {
                    if (mainFrameStatus == 403) {
                        failFetch("HTTP 403 (page timed out)", blocked = true)
                    } else {
                        failFetch("page timed out")
                    }
                }

                fun pollDom(attempt: Int, generation: Int) {
                    if (!cont.isActive || finished || generation != pageGeneration) return
                    AppLogger.debug("[WebViewHtmlFetcher] DOM poll attempt=$attempt generation=$generation")
                    webView.evaluateJavascript(
                        "(function(){ return JSON.stringify({title: document.title, html: document.documentElement.outerHTML}); })()"
                    ) { rawJson ->
                        if (!cont.isActive || finished || generation != pageGeneration) return@evaluateJavascript
                        val unescaped = runCatching {
                            JSONTokener(rawJson).nextValue() as? String
                        }.getOrNull().orEmpty()
                        val title = runCatching {
                            json.parseToJsonElement(unescaped).jsonObject["title"]?.jsonPrimitive?.content.orEmpty()
                        }.getOrDefault("")
                        val html = runCatching {
                            json.parseToJsonElement(unescaped).jsonObject["html"]?.jsonPrimitive?.content.orEmpty()
                        }.getOrDefault("")
                        val metadataTitle = HtmlMetadataParser.meta(html, "og:title")
                            ?: HtmlMetadataParser.meta(html, "twitter:title")
                        val errorTitle = Regex(
                            """^\s*(?:403\b|forbidden\b|access denied\b|attention required\b|just a moment\b)""",
                            RegexOption.IGNORE_CASE
                        )

                        if (isBotChallenge(html) || title.contains("Just a moment", ignoreCase = true)) {
                            failFetch(if (mainFrameStatus == 403) "HTTP 403" else "challenge page", blocked = true)
                        } else if (mainFrameStatus == 403 &&
                            (metadataTitle.isNullOrBlank() || errorTitle.containsMatchIn(metadataTitle) ||
                                errorTitle.containsMatchIn(title))
                        ) {
                            failFetch("HTTP 403", blocked = true)
                        } else if (html.isNotBlank() &&
                            (!metadataTitle.isNullOrBlank() || title.isNotBlank() ||
                                !Regex("""<body\b[^>]*>\s*</body>""", RegexOption.IGNORE_CASE).containsMatchIn(html))
                        ) {
                            finished = true
                            trace?.log("webView: hit attempt=$attempt len=${html.length} title=\"${title.take(60)}\"")
                            cont.resume(html)
                        } else if (attempt < 20) {
                            handler.postDelayed({ pollDom(attempt + 1, generation) }, 600L)
                        } else {
                            failFetch("empty response")
                        }
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, startedUrl: String?, favicon: Bitmap?) {
                        pageGeneration++
                        mainFrameStatus = null
                        domScheduled = false
                    }

                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        if (request?.isForMainFrame == true) {
                            failFetch("load failed: ${error?.description}")
                        }
                    }

                    override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                        if (request?.isForMainFrame == true) {
                            val status = errorResponse?.statusCode
                            mainFrameStatus = status
                            if (status == 403) {
                                trace?.log("webView: main-frame HTTP 403; waiting for DOM")
                                AppLogger.warn("[WebViewHtmlFetcher] Main-frame HTTP 403; waiting for DOM")
                            } else {
                                failFetch("HTTP $status", blocked = status == 429)
                            }
                        }
                    }

                    override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                        if (domScheduled) return
                        domScheduled = true
                        trace?.log("webView: onPageFinished url=$finishedUrl")
                        val generation = pageGeneration
                        if (!extractionStarted) {
                            extractionStarted = true
                            handler.removeCallbacks(timeout)
                            handler.postDelayed(timeout, 15_000L)
                        }
                        pollDom(1, generation)
                    }
                }
                cont.invokeOnCancellation { finished = true }
                handler.postDelayed(timeout, 15_000L)
                webView.loadUrl(url)
            }
        } finally {
            handler.removeCallbacksAndMessages(null)
            webView.stopLoading()
            webView.webViewClient = WebViewClient()
            webView.destroy()
            cookieManager.flush()
        }
    }
}
