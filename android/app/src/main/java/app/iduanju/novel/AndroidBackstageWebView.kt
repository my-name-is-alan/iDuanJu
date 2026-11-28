/* Android port of legado-E / LegadoTeam/legado help/http/BackstageWebView.kt:
 * off-screen WebView, main-thread only, supports GET/POST/HTML, evaluate webJs after
 * 100+delay ms with retry ladder (<=30), sourceRegex sniffing, overrideUrlRegex interception,
 * optional CookieManager sync, private-network guard, TLS errors never ignored. GPL-3.0. */
package app.iduanju.novel

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import app.iduanju.core.novel.BackstageRequest
import app.iduanju.core.novel.LegadoBackstage
import app.iduanju.core.novel.LegadoCookies
import app.iduanju.core.novel.LegadoWebView
import app.iduanju.core.novel.NovelSources
import app.iduanju.core.util.NetGuard
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class AndroidBackstageWebView(context: Context) : LegadoBackstage {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private fun allowedCheap(url: String): Boolean = runCatching {
        val u = URI(url); val host = u.host ?: return false
        val h = host.lowercase()
        u.scheme in listOf("http", "https") && u.userInfo == null && h != "localhost" && !h.endsWith(".localhost") && !h.endsWith(".local") &&
            !(NetGuard.isIPLiteral(host) && NetGuard.isBlockedIP(host.trim('[', ']')))
    }.getOrDefault(false)

    private fun allowed(url: String): Boolean = allowedCheap(url) && runCatching {
        val h = URI(url).host; NetGuard.isIPLiteral(h) || !NetGuard.resolvesToBlocked(h)
    }.getOrDefault(false)

    companion object {
        /** Serialize off-screen WebViews to 1 at a time so multi-source search never freezes Android's Main Looper. */
        private val webViewGate = java.util.concurrent.Semaphore(1, true)
    }

    override fun load(request: BackstageRequest): NovelSources.PageResponse {
        check(Looper.myLooper() != Looper.getMainLooper()) { "WebView解析不能阻塞主线程" }
        val effectiveTimeout = request.timeoutMs.coerceIn(2_000L, 35_000L)
        val acquired = try {
            webViewGate.tryAcquire(minOf(effectiveTimeout, 8_000L), TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        }
        if (!acquired) throw IllegalStateException("WebView 解析繁忙超时")
        val latch = CountDownLatch(1)
        val result = AtomicReference<Result<NovelSources.PageResponse>?>(null)
        val holder = arrayOfNulls<WebView>(1)
        val pendingTask = AtomicReference<Runnable?>(null)
        fun finish(r: Result<NovelSources.PageResponse>) {
            if (!result.compareAndSet(null, r)) return
            pendingTask.getAndSet(null)?.let { main.removeCallbacks(it) }
            if (request.useCookies) {
                runCatching {
                    val live = CookieManager.getInstance().getCookie(request.url)
                    if (!live.isNullOrBlank()) LegadoCookies.setManual(request.url, live)
                }
            }
            latch.countDown()
            main.post {
                pendingTask.getAndSet(null)?.let { main.removeCallbacks(it) }
                holder[0]?.let {
                    runCatching { it.stopLoading() }
                    runCatching { it.loadUrl("about:blank") }
                    runCatching { it.webViewClient = WebViewClient() }
                    runCatching { it.destroy() }
                }
                holder[0] = null
            }
        }
        try {
            main.post {
                try {
                    if (result.get() != null) return@post
                    if (request.useCookies) {
                        runCatching {
                            val cm = CookieManager.getInstance()
                            cm.setAcceptCookie(true)
                            LegadoCookies.cookieHeader(request.url)?.split(';')?.forEach { part ->
                                val p = part.trim()
                                if (p.contains('=')) cm.setCookie(request.url, "$p; Path=/")
                            }
                            cm.flush()
                        }
                    }
                    val web = create(request, ::finish, { result.get() != null }, pendingTask); holder[0] = web
                    if (result.get() == null) {
                        val html = request.html
                        when {
                            !html.isNullOrEmpty() ->
                                web.loadDataWithBaseURL(request.url, html, "text/html", "UTF-8", null)
                            request.method.equals("POST", true) ->
                                web.postUrl(request.url, (request.body ?: "").toByteArray(Charsets.UTF_8))
                            else -> web.loadUrl(request.url, request.headers)
                        }
                    }
                } catch (e: Throwable) { finish(Result.failure(e)) }
            }
            try {
                if (!latch.await(effectiveTimeout, TimeUnit.MILLISECONDS)) finish(Result.failure(IllegalStateException("WebView 解析超时（${effectiveTimeout / 1000}秒）")))
            } catch (e: InterruptedException) {
                finish(Result.failure(e)); Thread.currentThread().interrupt(); throw e
            }
            return result.get()!!.getOrThrow()
        } finally {
            webViewGate.release()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(
        request: BackstageRequest,
        finish: (Result<NovelSources.PageResponse>) -> Unit,
        done: () -> Boolean,
        pendingTask: AtomicReference<Runnable?>,
    ): WebView {
        val web = WebView(app)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            blockNetworkImage = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_DEFAULT
            request.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.let { userAgentString = it.value }
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, request.useCookies)
        if (request.html.isNullOrEmpty()) require(allowedCheap(request.url)) { "WebView 页面地址不允许或指向内网" }
        val regex = request.sourceRegex
        val ovRegex = request.overrideUrlRegex
        web.webViewClient = object : WebViewClient() {
            private var retry = 0
            private var scheduled: Runnable? = null
            override fun shouldOverrideUrlLoading(view: WebView, r: WebResourceRequest): Boolean {
                val u = r.url.toString()
                if (ovRegex != null && LegadoWebView.matches(ovRegex, u)) {
                    finish(Result.success(NovelSources.PageResponse(u, request.url)))
                    return true
                }
                return !allowedCheap(u)
            }
            override fun shouldInterceptRequest(view: WebView, r: WebResourceRequest): WebResourceResponse? {
                val url = r.url.toString()
                if (url.startsWith("http") && !allowed(url)) return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                if (regex != null && url.startsWith("http") && LegadoWebView.matches(regex, url)) {
                    finish(Result.success(NovelSources.PageResponse(url, request.url)))
                    return WebResourceResponse("text/plain", "utf-8", 204, "Captured", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.cancel(); finish(Result.failure(IllegalStateException("WebView TLS 证书错误，未忽略（${error?.primaryError}）")))
            }
            override fun onReceivedHttpError(view: WebView, r: WebResourceRequest, response: WebResourceResponse) {
                if (r.isForMainFrame && response.statusCode >= 400) finish(Result.failure(IllegalStateException("WebView 页面 HTTP ${response.statusCode}")))
            }
            override fun onReceivedError(view: WebView, r: WebResourceRequest, error: WebResourceError) {
                if (r.isForMainFrame) finish(Result.failure(IllegalStateException("WebView 页面加载失败：${error.description}")))
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (done()) return
                pendingTask.getAndSet(null)?.let { main.removeCallbacks(it) }
                val js = request.javaScript
                if (regex != null) {
                    if (!js.isNullOrEmpty()) main.postDelayed({ if (!done()) view.evaluateJavascript(js, null) }, 100L + request.delayMs)
                    val sniff = object : Runnable {
                        override fun run() {
                            if (done()) return
                            view.evaluateJavascript(LegadoWebView.SNIFF_JS) { v ->
                                LegadoWebView.firstSniffed(regex, v)?.let { finish(Result.success(NovelSources.PageResponse(it, request.url))); return@evaluateJavascript }
                                if (!done()) main.postDelayed(this, 500L)
                            }
                        }
                    }
                    pendingTask.set(sniff); main.postDelayed(sniff, 100L + request.delayMs)
                    return
                }
                val code = js?.takeIf { it.isNotEmpty() } ?: LegadoWebView.DEFAULT_JS
                val eval = object : Runnable {
                    override fun run() {
                        if (done()) return
                        view.evaluateJavascript(code) { v ->
                            val text = LegadoWebView.evalResult(v)
                            if (text != null) { finish(Result.success(NovelSources.PageResponse(text, view.url ?: url))); return@evaluateJavascript }
                            if (retry > LegadoWebView.MAX_RETRY) { finish(Result.failure(IllegalStateException("js执行超时"))); return@evaluateJavascript }
                            val next = LegadoWebView.retryIntervals.getOrElse(retry) { LegadoWebView.retryIntervals.last() }
                            retry++; main.postDelayed(this, next)
                        }
                    }
                }
                pendingTask.set(eval); main.postDelayed(eval, 100L + request.delayMs)
            }
        }
        return web
    }
}
