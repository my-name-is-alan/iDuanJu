package app.iduanju.core.novel

import app.iduanju.core.util.NetGuard
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class NovelSource(val json: JSONObject) {
    val id: String get() = json.optString("bookSourceUrl", json.optString("sourceUrl"))
    val isRSS: Boolean get() = json.optString("_idjSourceKind") == "rss" || (json.has("sourceUrl") && (!json.has("bookSourceUrl") || json.has("ruleArticles") || json.has("sortUrl")))
    val bookSourceType: Int get() = json.optInt("bookSourceType", 0)
    val isMedia: Boolean get() = !isRSS && bookSourceType in listOf(LegadoSourceType.audio, LegadoSourceType.video)
    val isImage: Boolean get() = !isRSS && bookSourceType == LegadoSourceType.image
    val isFile: Boolean get() = !isRSS && bookSourceType == LegadoSourceType.file
    val mainJs: String get() = json.optString("mainJs").takeUnless { it == "null" }.orEmpty()
    val isJsSource: Boolean get() = mainJs.isNotBlank()
    val enabledCookieJar: Boolean get() = json.optBoolean("enabledCookieJar", true)
    val concurrentRate: String get() = json.optString("concurrentRate").takeUnless { it == "null" }.orEmpty()
    val bookUrlPattern: String get() = json.optString("bookUrlPattern").takeUnless { it == "null" }.orEmpty()
    val group: String get() = json.optString("bookSourceGroup").takeUnless { it == "null" }.orEmpty()
    val hasLogin: Boolean get() = LegadoLogin.hasLogin(this)
    val supportsContentBatch: Boolean get() = isJsSource || (rules("ruleContent").optString("contentBatch").isNotBlank() && maxBatchSize > 1)
    val maxBatchSize: Int get() = rules("ruleContent").optInt("maxBatchSize", if (isJsSource) 10 else 0).coerceIn(0, 50)
    val name: String get() = json.optString("bookSourceName", json.optString("sourceName", id))
    val enabled: Boolean get() = json.optBoolean("enabled", true)
    fun rules(name: String): JSONObject = json.optJSONObject(name)
        ?: runCatching { JSONObject(json.optString(name)) }.getOrDefault(JSONObject())
}

data class NovelHit(
    val name: String,
    val author: String,
    val url: String,
    val cover: String = "",
    val kind: String = "",
    val latest: String = "",
    val intro: String = "",
    val wordCount: String = "",
    val updateTime: String = "",
)

data class NovelFlexStyle(val basis: Double = -1.0, val grow: Double = 0.0, val wrapBefore: Boolean = false)

data class NovelCategory(
    val title: String,
    val url: String,
    val group: String = "推荐",
    val action: String? = null,
    val type: String = "url",
    val chars: List<String> = emptyList(),
    val defaultValue: String = "",
    val viewName: String = "",
    val style: NovelFlexStyle = NovelFlexStyle(),
    val value: String = "",
    val displayTitle: String = title,
) {
    val selectionKey get() = "$group|$title|$type"
    val id get() = "$selectionKey|$url|${action.orEmpty()}"
    val isControl get() = type != "url"
}

data class NovelChapter(
    val title: String,
    val url: String,
    val index: Int = 0,
    val isVolume: Boolean = false,
    val isVip: Boolean = false,
    val isPay: Boolean = false,
    val updateTime: String = "",
)

/** Regex AllInOne match groups (`:regex` mode): `$0` = whole match, `$1..$n` = capture groups. */
internal data class LegadoRegexGroups(val groups: List<String>)

/** Guarded Legado source runtime: full rule engine, URL options, rate limit, cookies, WebView, JS sources, RSS & media. */
object NovelSources {
    fun configureStorage(root: java.io.File) {
        NovelScript.configure(root)
        LegadoCookies.configure(root)
        LegadoFiles.configure(root)
        LegadoReplaceStore.configure(root)
        LegadoTools.configureCheckStorage(root)
    }

    const val userAgent = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()
    private val batchHttp = http.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()
    internal val inBatchSearch = ThreadLocal<Boolean>()
    @Volatile var fixture: ((String) -> String)? = null
    @Volatile var requestObserver: ((method: String, url: String, body: String?) -> Unit)? = null
    internal var fixturePage: ((String) -> PageResponse)? = null

    data class ImportReport(val sources: List<NovelSource>, val unsupported: Int, val invalid: Int) {
        val message: String get() = if (sources.isEmpty() && unsupported > 0) "暂不支持该书源类型；不是坏源或网站失效" else
            "已导入 ${sources.size} 个书源" + (if (unsupported > 0) "；跳过 $unsupported 个不支持的源类型（不是坏源）" else "") + (if (invalid > 0) "；忽略 $invalid 个无效条目" else "")
    }

    fun import(text: String): List<NovelSource> = importReport(text).sources

    fun importReport(text: String): ImportReport {
        val trimmed = text.trim().removePrefix("\uFEFF")
        if (LegadoJsSource.looksLikeJsSource(trimmed)) {
            val jsSource = LegadoJsSource.extract(trimmed)
            return ImportReport(listOf(jsSource), 0, 0)
        }
        val array = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONArray().put(JSONObject(trimmed))
        val rows = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        require(rows.none { it.has("sites") || it.has("spider") || it.has("lives") }) { "这是 TVBox 配置，小说导入器不支持；不是坏源" }
        rows.filter { NovelSource(it).isRSS }.forEach {
            it.put("_idjSourceKind", "rss").put("bookSourceUrl", it.optString("sourceUrl")).put("bookSourceName", it.optString("sourceName", it.optString("sourceUrl")))
        }
        fun isUnsupported(row: JSONObject): Boolean = row.optInt("bookSourceType", 0) !in listOf(0, 1, 2, 3, 4)
        val unsupported = rows.count { isUnsupported(it) }
        val valid = rows.filter { !isUnsupported(it) && it.optString("bookSourceUrl").isNotBlank() }.map { NovelSource(it) }.distinctBy { it.id }
        return ImportReport(valid, unsupported, array.length() - rows.count { isUnsupported(it) || it.optString("bookSourceUrl").isNotBlank() })
    }

    fun absolute(raw: String, base: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        val (urlPart, optPart) = LegadoUrlOptions.split(trimmed)
        val baseURL = LegadoUrlOptions.split(base).first.substringBefore('#')
        val cleanPart = urlPart.trim()
        val resolved = if (cleanPart.startsWith("data:", ignoreCase = true)) cleanPart
        else URI(baseURL).resolve(cleanPart.replace(" ", "%20")).toString()
        return if (optPart != null) "$resolved,$optPart" else resolved
    }

    internal fun script(code: String): String {
        val t = code.trim()
        return when {
            t.startsWith("@js:", true) -> t.substring(4)
            t.startsWith("<js>", true) -> t.replace(Regex("(?i)^<js>|</js>$"), "")
            else -> t
        }
    }

    private fun template(raw: String, source: NovelSource, key: String, page: Int, charset: String, encodeKey: Boolean = true): String {
        var text = raw
        var at = 0
        while (true) {
            val start = text.indexOf("{{", at); if (start < 0) break
            var i = start + 2; var depth = 0; var quote: Char? = null
            while (i < text.length - 1) {
                val ch = text[i]
                if (quote != null) { if (ch == '\\') i++ else if (ch == quote) quote = null }
                else if (ch in "\"'`") quote = ch
                else if (ch == '{') depth++
                else if (ch == '}') { if (depth == 0 && text[i + 1] == '}') break; depth-- }
                i++
            }
            require(i < text.length - 1) { "网址模板没有结束" }
            val expr = text.substring(start + 2, i).trim()
            val value = when (expr) {
                "key" -> if (encodeKey) URLEncoder.encode(key, charset) else key
                "page" -> page.toString()
                else -> NovelScript(source, source.id, key, page).eval(expr)?.toString().orEmpty()
            }
            text = text.substring(0, start) + value + text.substring(i + 2); at = start + value.length
        }
        // Page lists `<a,b,c>` (keeps empty slots like `/list<,_{{page}}>.html`)
        text = Regex("<([^<>]*,[^<>]*)>").replace(text) { m ->
            val items = m.groupValues[1].split(',')
            items[minOf(page - 1, items.lastIndex).coerceAtLeast(0)].trim()
        }
        // Legacy `searchKey` / `searchPage` placeholders
        if (text.contains("searchKey") || text.contains("searchPage")) {
            val encodedKey = if (encodeKey) URLEncoder.encode(key, charset) else key
            text = text.replace("searchKey", encodedKey).replace("searchPage", page.toString())
        }
        return text
    }

    data class PageResponse(
        val text: String,
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val status: Int = 200,
        val rawBytes: ByteArray = ByteArray(0),
    )
    data class ByteResponse(val bytes: ByteArray, val status: Int, val headers: Map<String, String>)

    private fun blockedHost(host: String) = if (NetGuard.isIPLiteral(host)) NetGuard.isBlockedIP(host.trim('[', ']')) else NetGuard.resolvesToBlocked(host)

    fun fetchBytes(url: String, method: String, headers: JSONObject?, body: ByteArray, source: NovelSource? = null): ByteResponse {
        var address = url; var verb = method.uppercase()
        require(verb in listOf("GET", "POST", "HEAD")) { "书源 HTTP 方法不支持" }
        require(body.size <= 16 * 1024 * 1024) { "书源请求过大" }
        if (source != null) LegadoRateLimit.acquire(source.id, source.concurrentRate)
        val originalHost = URI(address).host
        val useCookies = source?.enabledCookieJar != false
        repeat(6) {
            val uri = URI(address)
            require(uri.scheme in listOf("https", "http") && !uri.host.isNullOrEmpty() && !blockedHost(uri.host)) { "书源地址无效或指向内网" }
            val req = Request.Builder().url(address).header("User-Agent", userAgent)
            var hasCookie = false
            if (uri.host == originalHost) headers?.let {
                for (k in it.keys()) {
                    if (k.equals("Cookie", true)) hasCookie = true
                    req.header(k, it.get(k).toString())
                }
            }
            if (!hasCookie && useCookies) LegadoCookies.cookieHeader(address)?.let { req.header("Cookie", it) }
            when (verb) {
                "POST" -> req.post(body.toRequestBody((headers?.optString("Content-Type")?.takeIf { it.isNotBlank() } ?: "application/octet-stream").toMediaType()))
                "HEAD" -> req.head()
            }
            val built = req.build()
            requestObserver?.let { observe ->
                val b = if (built.body != null) { val buffer = okio.Buffer(); built.body!!.writeTo(buffer); buffer.readUtf8() } else null
                observe(built.method, built.url.toString(), b)
            }
            http.newCall(built).execute().use { response ->
                if (useCookies) LegadoCookies.captureSetCookies(address, response.headers("Set-Cookie"))
                if (response.isRedirect) {
                    address = absolute(response.header("Location") ?: error("书源跳转缺少地址"), address)
                    if (response.code in listOf(301, 302, 303)) verb = "GET"
                } else {
                    val out = java.io.ByteArrayOutputStream()
                    response.body?.byteStream()?.use { stream ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = stream.read(buffer); if (n < 0) break
                            require(out.size() + n <= 8 * 1024 * 1024) { "书源响应过大" }
                            out.write(buffer, 0, n)
                        }
                    }
                    return ByteResponse(out.toByteArray(), response.code, response.headers.names().associateWith { response.header(it).orEmpty() })
                }
            }
        }
        error("书源跳转次数过多")
    }

    fun fetch(raw: String, source: NovelSource, key: String = "", page: Int = 1): String = fetchResponse(raw, source, key, page).text

    fun fetchResponse(
        raw: String,
        source: NovelSource,
        key: String = "",
        page: Int = 1,
        allowHttpError: Boolean = false,
        skipRateLimit: Boolean = false,
        contentWebJs: String? = null,
        contentSourceRegex: String? = null,
    ): PageResponse {
        var rule = raw.trim()
        val segs = jsSegments(rule)
        if (segs.any { it.first }) {
            var cur: Any? = ""
            for ((isJs, part) in segs) {
                cur = if (isJs) NovelScript(source, source.id, key, page).eval(part, cur) else part
            }
            rule = cur?.toString().orEmpty().trim()
        }
        // First parse options outside `{{...}}` so `charset` applies to template encoding.
        var (urlPart, optText) = LegadoUrlOptions.split(rule)
        var opt = LegadoUrlOptions.parse(optText)
        val charset = opt.charset ?: "UTF-8"
        urlPart = template(urlPart, source, key, page, charset, encodeKey = true)
        // A template (e.g. `{{fn(page)}}`) can itself return `url,{options}`.
        val (url2, optText2) = LegadoUrlOptions.split(urlPart)
        if (optText2 != null) {
            val extraOpt = LegadoUrlOptions.parse(optText2)
            urlPart = url2
            opt = mergeOptions(opt, extraOpt)
        }
        // `UrlOption.js`: transform URL before requesting
        opt.js?.let { jsCode ->
            val transformed = NovelScript(source, source.id, key, page).eval(script(jsCode), urlPart)?.toString()?.trim()
            if (!transformed.isNullOrEmpty()) urlPart = transformed
        }
        val effectiveOrigin = opt.origin ?: source.id
        var address = absolute(urlPart, effectiveOrigin)
        if (address.startsWith("data:")) {
            val data = address.substringAfter(',')
            val bytes = if (address.substringBefore(',').endsWith(";base64")) java.util.Base64.getDecoder().decode(data)
            else java.net.URLDecoder.decode(data.replace("+", "%2B"), "UTF-8").toByteArray()
            val text = if (!opt.type.isNullOrBlank()) bytes.joinToString("") { "%02x".format(it) } else String(bytes)
            return applyBodyJs(PageResponse(text, address, emptyMap(), 200, bytes), opt, source, key, page)
        }

        // Full-pipeline Backstage WebView when UrlOption.webView is enabled
        val batch = inBatchSearch.get() == true
        if (opt.webView) {
            val loader = LegadoWebView.backstage ?: error("此平台未提供 WebView 解析运行时")
            val uri = URI(address)
            require(uri.scheme in listOf("https", "http") && !uri.host.isNullOrEmpty() && !blockedHost(uri.host)) { "WebView 页面地址无效或指向内网" }
            val reqHeaders = buildHeadersMap(source, opt)
            val effectiveBody = opt.body?.let { template(it, source, key, page, charset, encodeKey = !it.trimStart().startsWith("{")) }
            val webTimeout = (opt.timeoutMs ?: if (batch) 8_000L else 30_000L).coerceIn(2_000L, if (batch) 8_000L else 35_000L)
            val resp = loader.load(
                BackstageRequest(
                    url = address,
                    headers = reqHeaders,
                    javaScript = opt.webJs ?: contentWebJs,
                    sourceRegex = contentSourceRegex,
                    delayMs = opt.webViewDelayTime.coerceAtMost(if (batch) 2_500L else 10_000L),
                    timeoutMs = webTimeout,
                    method = opt.method,
                    body = effectiveBody,
                    useCookies = source.enabledCookieJar,
                )
            )
            LegadoTools.emitDebug("http", "[WebView ${opt.method}] $address -> ${resp.text.length} 字符")
            return applyBodyJs(resp, opt, source, key, page)
        }

        fixturePage?.let { return applyBodyJs(it(address), opt, source, key, page) }
        fixture?.let { return applyBodyJs(PageResponse(it(address), address), opt, source, key, page) }

        val headersJson = headers(source)
        val baseHttp = if (batch) batchHttp else http
        val capMs = if (batch) 10_000L else 30_000L
        val client = opt.timeoutMs?.let { rawT ->
            val t = rawT.coerceIn(2_000L, capMs)
            baseHttp.newBuilder().callTimeout(t, TimeUnit.MILLISECONDS).readTimeout(t, TimeUnit.MILLISECONDS).build()
        } ?: baseHttp
        val followRedirects = opt.followRedirects != false
        val maxAttempts = if (batch) 1 else (1 + opt.retry.coerceIn(0, 3))
        var lastError: Throwable? = null

        for (attempt in 0 until maxAttempts) {
            try {
                if (!skipRateLimit) LegadoRateLimit.acquire(source.id, source.concurrentRate)
                var curAddress = address
                var verb = opt.method
                val originalHost = URI(curAddress).host
                val t0 = System.currentTimeMillis()
                repeat(6) { hop ->
                    val uri = URI(curAddress)
                    require(uri.scheme in listOf("https", "http") && !uri.host.isNullOrEmpty() && !blockedHost(uri.host)) { "书源地址无效或指向内网" }
                    val req = Request.Builder().url(curAddress).header("User-Agent", userAgent)
                    var hasCookie = false
                    if (uri.host == originalHost) {
                        for (name in headersJson.keys()) {
                            if (name.equals("Cookie", true)) hasCookie = true
                            req.header(name, headersJson.get(name).toString())
                        }
                        opt.headers?.let { extra ->
                            for (name in extra.keys()) {
                                if (name.equals("Cookie", true)) hasCookie = true
                                req.header(name, extra.get(name).toString())
                            }
                        }
                    }
                    if (!hasCookie && source.enabledCookieJar) {
                        LegadoCookies.cookieHeader(curAddress)?.let { req.header("Cookie", it) }
                    }
                    when {
                        hop == 0 && verb == "POST" -> {
                            val rawBody = opt.body.orEmpty()
                            val body = template(rawBody, source, key, page, charset, encodeKey = !rawBody.trimStart().startsWith("{"))
                            val ct = if (body.trimStart().startsWith('{') || body.trimStart().startsWith('[')) "application/json; charset=utf-8"
                            else "application/x-www-form-urlencoded; charset=$charset"
                            req.post(body.toRequestBody(ct.toMediaType()))
                        }
                        verb == "HEAD" -> req.head()
                    }
                    val built = req.build()
                    requestObserver?.let { observe ->
                        val body = if (built.body != null) { val buffer = okio.Buffer(); built.body!!.writeTo(buffer); buffer.readUtf8() } else null
                        observe(built.method, built.url.toString(), body)
                    }
                    client.newCall(built).execute().use { response ->
                        if (source.enabledCookieJar) {
                            LegadoCookies.captureSetCookies(curAddress, response.headers("Set-Cookie"))
                        }
                        if (response.isRedirect && followRedirects) {
                            curAddress = absolute(response.header("Location") ?: error("书源跳转缺少地址"), curAddress)
                            if (response.code in listOf(301, 302, 303)) verb = "GET"
                        } else {
                            if (!allowHttpError && !(response.isSuccessful || (!followRedirects && response.isRedirect))) {
                                error("书源 HTTP ${response.code}")
                            }
                            val bytes = if (verb == "HEAD") ByteArray(0) else response.body?.byteStream()?.use { stream ->
                                val out = java.io.ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val n = stream.read(buffer); if (n < 0) break
                                    require(out.size() + n <= 8 * 1024 * 1024) { "书源页面过大" }
                                    out.write(buffer, 0, n)
                                }
                                out.toByteArray()
                            } ?: ByteArray(0)
                            val text = if (!opt.type.isNullOrBlank()) bytes.joinToString("") { "%02x".format(it) }
                            else decode(bytes, opt.charset, response.header("Content-Type"))
                            val respHeaders = response.headers.names().associateWith { response.header(it).orEmpty() }
                            LegadoTools.emitDebug("http", "[${built.method} ${response.code}] $curAddress (${System.currentTimeMillis() - t0}ms, ${bytes.size}B)")
                            return applyBodyJs(PageResponse(text, curAddress, respHeaders, response.code, bytes), opt, source, key, page)
                        }
                    }
                }
                error("书源跳转次数过多")
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (e: VirtualMachineError) {
                throw e
            } catch (e: Throwable) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("书源请求已取消")
                lastError = e
                if (attempt + 1 < maxAttempts) Thread.sleep(minOf(1000L * (attempt + 1), 3000L))
            }
        }
        throw lastError ?: IllegalStateException("书源请求失败")
    }

    private fun mergeOptions(base: LegadoUrlOption, extra: LegadoUrlOption): LegadoUrlOption {
        val mergedHeaders = JSONObject().apply {
            base.headers?.let { h -> for (k in h.keys()) put(k, h.get(k)) }
            extra.headers?.let { h -> for (k in h.keys()) put(k, h.get(k)) }
        }.takeIf { it.length() > 0 }
        return LegadoUrlOption(
            method = if (extra.method != "GET") extra.method else base.method,
            charset = extra.charset ?: base.charset,
            headers = mergedHeaders,
            body = extra.body ?: base.body,
            origin = extra.origin ?: base.origin,
            retry = maxOf(base.retry, extra.retry),
            type = extra.type ?: base.type,
            webView = base.webView || extra.webView,
            webJs = extra.webJs ?: base.webJs,
            webViewDelayTime = if (extra.webViewDelayTime > 0) extra.webViewDelayTime else base.webViewDelayTime,
            timeoutMs = extra.timeoutMs ?: base.timeoutMs,
            followRedirects = extra.followRedirects ?: base.followRedirects,
            dnsIp = extra.dnsIp ?: base.dnsIp,
            js = extra.js ?: base.js,
            bodyJs = extra.bodyJs ?: base.bodyJs,
        )
    }

    private fun buildHeadersMap(source: NovelSource, opt: LegadoUrlOption): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val h = headers(source)
        for (k in h.keys()) out[k] = h.get(k).toString()
        opt.headers?.let { e -> for (k in e.keys()) out[k] = e.get(k).toString() }
        return out
    }

    private fun applyBodyJs(resp: PageResponse, opt: LegadoUrlOption, source: NovelSource, key: String, page: Int): PageResponse {
        val bjs = opt.bodyJs ?: return resp
        val transformed = NovelScript(source, resp.url, key, page).eval(script(bjs), resp.text)?.toString()
        return if (transformed != null) resp.copy(text = transformed) else resp
    }

    internal fun decode(bytes: ByteArray, explicit: String?, contentType: String?): String {
        fun cs(name: String?) = name?.trim()?.trim('"', '\'')?.takeIf { it.isNotEmpty() }?.let { runCatching { java.nio.charset.Charset.forName(it) }.getOrNull() }
        cs(explicit)?.let { return String(bytes, it) }
        cs(contentType?.let { Regex("(?i)charset=([\\w-]+)").find(it)?.groupValues?.get(1) })?.let { return String(bytes, it) }
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        cs(Regex("(?i)<meta[^>]+charset\\s*=\\s*[\"']?([\\w-]+)").find(head)?.groupValues?.get(1))?.let { return String(bytes, it) }
        val decoder = Charsets.UTF_8.newDecoder()
        return try { decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString() } catch (_: java.nio.charset.CharacterCodingException) { String(bytes, java.nio.charset.Charset.forName("GB18030")) }
    }

    fun categories(source: NovelSource): List<NovelCategory> = if (source.isRSS) LegadoRss.categories(source) else categories(source, 0)
    private fun categories(source: NovelSource, repairPass: Int): List<NovelCategory> {
        var raw = source.json.optString("exploreUrl").trim()
        if (raw.isEmpty()) return emptyList()
        if (raw.startsWith("@js:", true) || raw.startsWith("<js>", true)) {
            raw = NovelScript(source, source.id).eval(script(raw))?.toString().orEmpty()
        }
        val parsed = parseCategories(raw)
        val initial = NovelScript.exploreInfo(source.id)
        val info = JSONObject(initial.toString())
        var changed = false
        var repairedDependentValue = false
        parsed.filter { it.isControl }.forEach { c ->
            val invalid = c.type in listOf("select", "toggle") && c.chars.isNotEmpty() && info.has(c.title) && info.optString(c.title) !in c.chars
            if (!info.has(c.title) || invalid) {
                val fallback = c.defaultValue.ifEmpty { c.chars.firstOrNull().orEmpty() }
                info.put(c.title, if (c.type in listOf("select", "toggle") && c.chars.isNotEmpty() && fallback !in c.chars) c.chars.first() else fallback)
                changed = true; repairedDependentValue = repairedDependentValue || invalid
            }
        }
        if (changed) NovelScript.mergeExploreInfo(source.id, initial, info)
        if (repairedDependentValue && repairPass < 3) return categories(source, repairPass + 1)
        return parsed.map { c ->
            val display = if (c.viewName.isBlank()) c.title else runCatching { NovelScript(source, source.id).eval(script(c.viewName))?.toString() }.getOrNull() ?: c.title
            c.copy(value = info.optString(c.title), displayTitle = display)
        }
    }

    fun categoryAction(source: NovelSource, category: NovelCategory, value: String? = null) {
        if (category.isControl) {
            val initial = NovelScript.exploreInfo(source.id)
            val info = JSONObject(initial.toString())
            val next = value ?: if (category.type == "toggle" && category.chars.isNotEmpty()) category.chars[(category.chars.indexOf(info.optString(category.title)) + 1).mod(category.chars.size)] else null
            if (next != null) { info.put(category.title, next); NovelScript.mergeExploreInfo(source.id, initial, info) }
        }
        category.action?.takeIf { it.isNotBlank() }?.let { NovelScript(source, source.id).eval(script(it)) }
    }

    fun refreshedCategory(previous: NovelCategory?, categories: List<NovelCategory>, group: String): NovelCategory? =
        categories.firstOrNull { !it.isControl && it.selectionKey == previous?.selectionKey }
            ?: categories.firstOrNull { !it.isControl && it.url == previous?.url }
            ?: categories.firstOrNull { !it.isControl && it.group == group }
            ?: categories.firstOrNull { !it.isControl }

    internal fun escapeRawControls(raw: String): String {
        val out = StringBuilder(); var inString = false; var escaped = false
        for (c in raw) {
            if (inString) {
                if (escaped) { escaped = false; out.append(c); continue }
                when (c) {
                    '\\' -> { escaped = true; out.append(c) }
                    '"' -> { inString = false; out.append(c) }
                    '\n' -> out.append("\\n"); '\r' -> out.append("\\r"); '\t' -> out.append("\\t")
                    else -> out.append(c)
                }
            } else { if (c == '"') inString = true; out.append(c) }
        }
        return out.toString()
    }

    fun parseCategories(raw: String): List<NovelCategory> {
        var group = "推荐"
        val out = arrayListOf<NovelCategory>()
        fun add(title: String, url: String, action: String? = null) {
            val t = stripInvisible(title).trim(); val u = url.trim(); if (t.isEmpty()) return
            if (!action.isNullOrBlank()) out += NovelCategory(t, "", group, action)
            else if (u.isBlank()) group = t
            else out += NovelCategory(t, u, group)
        }
        if (raw.startsWith("[")) {
            val a = runCatching { JSONArray(raw) }.getOrElse { JSONArray(escapeRawControls(raw)) }
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { o ->
                val type = o.optString("type").ifEmpty { "url" }
                if (type in listOf("button", "text", "toggle", "select")) {
                    val title = stripInvisible(o.optString("title")).trim()
                    val chars = o.optJSONArray("chars")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }.orEmpty()
                    val style = o.optJSONObject("style")
                    if (title.isNotEmpty()) out += NovelCategory(title, "", group, o.optString("action").takeIf { it.isNotBlank() }, type, chars, o.optString("default"), o.optString("viewName"), NovelFlexStyle(style?.optDouble("layout_flexBasisPercent", -1.0) ?: -1.0, style?.optDouble("layout_flexGrow", 0.0) ?: 0.0, style?.optBoolean("layout_wrapBefore", false) ?: false))
                } else add(o.optString("title"), o.optString("url").takeUnless { it == "null" }.orEmpty())
            }
        } else for (line in raw.split("&&", "\n").filter { it.isNotBlank() }) {
            val p = line.trim().split("::", limit = 2)
            if (p.size == 2) add(p[0], p[1])
            else if (p[0].startsWith("http") || p[0].startsWith('/')) add("推荐", p[0]) else group = p[0]
        }
        return out.distinctBy { it.id }
    }

    /** Full JSONPath subset with filter predicates `[?(@.k == 'v' && @.n > 1)]`, `||`, `=~`, recursive `..`, slices, and wildcards. */
    internal fun jsonNodes(value: Any, raw: String): List<Any> {
        var path = raw.trim()
        if (path.startsWith("@json:", ignoreCase = true)) path = path.substring(6).trim()
        if (path.startsWith("$")) path = path.substring(1)
        fun children(n: Any): List<Any> = when (n) {
            is JSONObject -> n.keys().asSequence().sorted().map { n.get(it) }.toList()
            is JSONArray -> (0 until n.length()).map { n.get(it) }
            else -> emptyList()
        }
        fun deep(n: Any): List<Any> = listOf(n) + children(n).flatMap { deep(it) }
        fun member(n: Any, name: String): List<Any> = when {
            name == "*" -> children(n)
            n is JSONObject -> n.opt(name)?.takeIf { it != JSONObject.NULL }?.let { listOf(it) }.orEmpty()
            else -> emptyList()
        }
        fun name(at: Int): Pair<String, Int> { var j = at; while (j < path.length && path[j] != '.' && path[j] != '[') j++; return path.substring(at, j).trim() to j }
        fun bracketEnd(openIdx: Int): Int {
            var j = openIdx + 1; var depth = 0; var quote: Char? = null
            while (j < path.length) {
                val c = path[j]
                if (quote != null) { if (c == quote) quote = null }
                else if (c == '\'' || c == '"') quote = c
                else if (c == '(') depth++
                else if (c == ')') depth--
                else if (c == ']' && depth == 0) return j
                j++
            }
            return path.length
        }
        var current = listOf(value); var i = 0
        while (i < path.length) {
            when {
                path.startsWith("..", i) -> {
                    if (i + 2 < path.length && path[i + 2] == '[') {
                        val close = bracketEnd(i + 2)
                        val inner = path.substring(i + 3, close).trim(); i = (close + 1).coerceAtMost(path.length)
                        current = current.flatMap { deep(it) }.flatMap { applyJsonBracket(it, inner, ::children, ::member) }
                    } else {
                        val (n, next) = name(i + 2); i = next
                        current = current.flatMap { deep(it) }.let { all -> if (n.isEmpty()) all else all.flatMap { member(it, n) } }
                    }
                }
                path[i] == '.' -> {
                    if (i + 1 < path.length && path[i + 1] == '[') { i++; continue }
                    val (n, next) = name(i + 1); i = next
                    if (n.isNotEmpty()) current = current.flatMap { member(it, n) }
                }
                path[i] == '[' -> {
                    val close = bracketEnd(i)
                    val inner = path.substring(i + 1, close).trim(); i = (close + 1).coerceAtMost(path.length)
                    current = current.flatMap { applyJsonBracket(it, inner, ::children, ::member) }
                }
                else -> { val (n, next) = name(i); i = next; if (n.isNotEmpty()) current = current.flatMap { member(it, n) } }
            }
        }
        return current.filter { it != JSONObject.NULL }
    }

    private fun applyJsonBracket(n: Any, inner: String, children: (Any) -> List<Any>, member: (Any, String) -> List<Any>): List<Any> = when {
        inner == "*" -> children(n)
        inner.startsWith("?(") && inner.endsWith(")") -> {
            val expr = inner.substring(2, inner.length - 1).trim()
            children(n).filter { evalJsonFilter(it, expr) }
        }
        inner.startsWith("'") || inner.startsWith("\"") -> inner.split(',').flatMap { member(n, it.trim().trim('\'', '"')) }
        n is JSONArray -> pick(children(n), inner)
        n is JSONObject -> inner.split(',').flatMap { member(n, it.trim().trim('\'', '"')) }
        else -> emptyList()
    }

    private fun evalJsonFilter(item: Any, expr: String): Boolean {
        // Support top-level `||` then `&&`
        for (orBranch in expr.split("||")) {
            val allTrue = orBranch.split("&&").all { clause ->
                val e = clause.trim()
                val op = listOf("==", "!=", "<=", ">=", "<", ">", "=~").firstOrNull { e.contains(it) }
                if (op == null) {
                    val v = jsonNodes(item, "$" + e.removePrefix("@")).firstOrNull()
                    v != null && v != false && v != JSONObject.NULL && v.toString().isNotEmpty()
                } else {
                    val idx = e.indexOf(op)
                    val lhsPath = "$" + e.substring(0, idx).trim().removePrefix("@")
                    val rhsRaw = e.substring(idx + op.length).trim().trim('\'', '"')
                    val l = jsonNodes(item, lhsPath).firstOrNull()?.toString().orEmpty()
                    val ln = l.toDoubleOrNull(); val rn = rhsRaw.toDoubleOrNull()
                    when (op) {
                        "==" -> l == rhsRaw
                        "!=" -> l != rhsRaw
                        "=~" -> runCatching { Regex(rhsRaw.trim('/')).containsMatchIn(l) }.getOrDefault(false)
                        "<" -> (ln ?: 0.0) < (rn ?: 0.0)
                        ">" -> (ln ?: 0.0) > (rn ?: 0.0)
                        "<=" -> (ln ?: 0.0) <= (rn ?: 0.0)
                        else -> (ln ?: 0.0) >= (rn ?: 0.0)
                    }
                }
            }
            if (allTrue) return true
        }
        return false
    }

    private fun jsonTemplate(content: Any, rule: String) = Regex("\\{(\\$\\.[^{}]+)\\}").replace(rule) { m -> jsonNodes(content, m.groupValues[1]).joinToString(",") }

    internal fun splitIndex(seg: String): Pair<String, String?> {
        val body = seg.trim()
        if (body.endsWith("]")) {
            val open = body.lastIndexOf('[')
            val inner = if (open >= 0) body.substring(open + 1, body.length - 1) else ""
            if (inner.isNotBlank() && inner.all { it in "0123456789-:,! " }) return body.substring(0, open) to inner.trim()
        }
        var i = body.length
        while (i > 0 && body[i - 1] in "0123456789-:, ") i--
        if (i == body.length || i <= 1 || body.substring(i).none { it.isDigit() }) return body to null
        val mark = body[i - 1]
        if (mark != '.' && mark != '!') return body to null
        return body.substring(0, i - 1) to ((if (mark == '!') "!" else "") + body.substring(i).trim())
    }

    internal fun <T> pick(items: List<T>, spec: String?): List<T> {
        var s = spec?.trim().orEmpty(); if (s.isEmpty()) return items
        val exclude = s.startsWith("!"); if (exclude) s = s.substring(1)
        val n = items.size; val idx = arrayListOf<Int>()
        for (part in s.split(',')) {
            val p = part.split(':').map { it.trim().toIntOrNull() }
            if (p.size >= 2) {
                var a = p[0] ?: 0; var b = p[1] ?: (n - 1)
                if (a < 0) a += n; if (b < 0) b += n
                val lo = maxOf(0, minOf(a, b)); val hi = minOf(n - 1, maxOf(a, b))
                if (lo <= hi) idx += if (a <= b) (lo..hi).toList() else (hi downTo lo).toList()
            } else p[0]?.let { idx += if (it < 0) n + it else it }
        }
        val valid = idx.filter { it in 0 until n }
        return if (exclude) items.filterIndexed { i, _ -> i !in valid.toSet() } else valid.map { items[it] }
    }

    private fun select(root: Element, rule: String): List<Element> {
        if (rule.isBlank()) return listOf(root)
        var current = listOf(root)
        val cleanRule = rule.removePrefix("@@").removePrefix("@css:").removePrefix("@CSS:")
        for (part in splitAt(cleanRule)) {
            val (selector, index) = splitIndex(part)
            current = current.flatMap { el ->
                val found: List<Element> = when {
                    selector == "children" -> el.children().toList()
                    selector.startsWith("class.") -> selector.removePrefix("class.").trim().split(Regex("\\s+")).let { want -> el.allElements.filter { e -> want.all { e.hasClass(it) } } }
                    selector.startsWith("id.") -> el.allElements.filter { it.id() == selector.removePrefix("id.") }
                    selector.startsWith("tag.") -> el.getElementsByTag(selector.removePrefix("tag.")).toList()
                    selector.startsWith("text.") -> el.getElementsContainingOwnText(selector.removePrefix("text.")).toList()
                    else -> el.select(selector).toList()
                }
                pick(found, index)
            }
        }
        return current
    }

    private fun splitAt(rule: String): List<String> {
        val out = arrayListOf<String>(); val cur = StringBuilder(); var depth = 0; var quote: Char? = null
        for (c in rule) {
            if (quote != null) { cur.append(c); if (c == quote) quote = null; continue }
            if (c == '\'' || c == '"') { quote = c; cur.append(c); continue }
            if (c == '[' || c == '(') depth++ else if (c == ']' || c == ')') depth--
            if (c == '@' && depth == 0) { out += cur.toString().trim(); cur.clear() } else cur.append(c)
        }
        out += cur.toString().trim()
        return out.filter { it.isNotEmpty() }
    }

    private fun splitTop(rule: String): Pair<List<String>, String> {
        for (op in listOf("&&", "||", "%%")) {
            val parts = arrayListOf<String>(); val cur = StringBuilder(); var depth = 0; var quote: Char? = null; var i = 0
            while (i < rule.length) {
                val c = rule[i]
                if (quote != null) { cur.append(c); if (c == quote) quote = null; i++; continue }
                if (c == '\'' || c == '"') quote = c
                if (c in "[({") depth++ else if (c in "])}") depth--
                if (depth == 0 && rule.startsWith(op, i)) { parts += cur.toString(); cur.clear(); i += 2; continue }
                cur.append(c); i++
            }
            parts += cur.toString()
            if (parts.size > 1) return parts.map { it.trim() }.filter { it.isNotEmpty() } to op
        }
        return listOf(rule) to ""
    }

    internal fun jsSegments(rule: String): List<Pair<Boolean, String>> {
        val out = arrayListOf<Pair<Boolean, String>>(); var rest = rule
        while (rest.isNotEmpty()) {
            val tag = rest.indexOf("<js>", ignoreCase = true); val at = rest.indexOf("@js:", ignoreCase = true)
            val i = listOf(tag, at).filter { it >= 0 }.minOrNull()
            if (i == null) { rest.trim().takeIf { it.isNotEmpty() }?.let { out += false to it }; break }
            rest.substring(0, i).trim().takeIf { it.isNotEmpty() }?.let { out += false to it }
            if (i == tag) {
                val close = rest.indexOf("</js>", i, ignoreCase = true)
                out += true to (if (close < 0) rest.substring(i + 4) else rest.substring(i + 4, close))
                rest = if (close < 0) "" else rest.substring(close + 5)
            } else { out += true to rest.substring(i + 4); rest = "" }
        }
        return out
    }

    internal fun nodes(content: Any, raw0: String, source: NovelSource, base: String, key: String = "", page: Int = 1): List<Any> {
        val raw = applyGetVars(raw0.trim().removePrefix("+"), source)
        val segments = jsSegments(raw.removePrefix("-"))
        if (segments.any { it.first }) {
            var cur: Any? = content
            for ((js, part) in segments) {
                cur = if (js) {
                    val input: Any? = if (cur is List<*> && cur.all { it is Element }) cur else if (cur is List<*>) JSONArray(cur.map { if (it is Element) it.outerHtml() else it }) else cur?.toString()
                    val value = NovelScript(source, base, key, page).also { it.srcText = srcOf(content) }.eval(part, input)
                    if (value is String && value.trimStart().startsWith('[')) runCatching { JSONArray(value) }.getOrDefault(value) else value
                } else when (val c = cur) {
                    is List<*> -> c.filterNotNull().flatMap { nodes(it, part, source, base, key, page) }
                    is JSONArray -> (0 until c.length()).flatMap { val v = c.get(it); nodes(if (v is String) document(v) else v, part, source, base, key, page) }
                    is String -> nodes(document(c), part, source, base, key, page)
                    null -> emptyList()
                    else -> nodes(c, part, source, base, key, page)
                }
            }
            val list: List<Any> = when (val c = cur) {
                is List<*> -> c.filterNotNull()
                is JSONArray -> (0 until c.length()).map { val v = c.get(it); if (v is String) item(v) else v }
                null -> emptyList()
                else -> listOf(c)
            }
            return if (raw.startsWith('-')) list.reversed() else list
        }
        val reverse = raw.startsWith('-'); val rule = raw.removePrefix("-")
        fun single(r0: String): List<Any> {
            val r = r0.trim()
            if (r.startsWith(":")) {
                val pattern = r.substring(1)
                val regex = Regex(pattern)
                val text = if (content is Element) content.outerHtml() else content.toString()
                return regex.findAll(text).map { m -> LegadoRegexGroups(m.groupValues) }.toList()
            }
            if (r.startsWith("@@")) {
                val root = content as? Element ?: Jsoup.parse(content.toString())
                return select(root, r.substring(2))
            }
            if (r.startsWith("@css:", true)) {
                val root = content as? Element ?: Jsoup.parse(content.toString())
                val parts = splitAt(r.substring(5))
                val sel = if (parts.size > 1 && parts.last().matches(Regex("^[A-Za-z][\\w\\-:]*$"))) parts.dropLast(1).joinToString("@") else r.substring(5)
                return root.select(sel).toList()
            }
            if (r.startsWith('$') || r.startsWith("@json:", true) || (content is JSONObject || content is JSONArray)) {
                return jsonNodes(if (content is String) document(content) else content, r).flatMap { if (it is JSONArray) (0 until it.length()).map { n -> it.get(n) } else listOf(it) }
            }
            val root = content as? Element ?: Jsoup.parse(content.toString())
            if (r.startsWith("//") || r.startsWith("/") || r.startsWith("@xpath:", true)) {
                return root.selectXpath(scopedXPath(content, r.replace(Regex("^@xpath:", RegexOption.IGNORE_CASE), ""))).toList()
            }
            return select(root, r)
        }
        val result = splitTop(rule).let { (parts, op) ->
            when (op) {
                "||" -> parts.asSequence().map { single(it) }.firstOrNull { it.isNotEmpty() } ?: emptyList()
                "&&" -> parts.flatMap { single(it) }
                "%%" -> parts.map { single(it) }.let { lists -> (0 until (lists.maxOfOrNull { it.size } ?: 0)).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } } }
                else -> single(rule)
            }
        }
        return if (reverse) result.reversed() else result
    }

    private fun applyGetVars(raw: String, source: NovelSource): String {
        if (!raw.contains("@get:{")) return raw
        return Regex("@get:\\{([^}]+)\\}").replace(raw) { m -> NovelScript.getVariable(source.id, m.groupValues[1].trim()) }
    }

    internal fun ruleValue(content: Any, raw0: String, source: NovelSource, base: String, key: String = "", page: Int = 1): String {
        if (raw0.isEmpty()) return ""
        val pureGet = Regex("^@get:\\{([^}]+)\\}$").matchEntire(raw0.trim())
        if (pureGet != null) return NovelScript.getVariable(source.id, pureGet.groupValues[1].trim())
        val put = Regex("@put:(\\{[^}]*\\})").find(raw0)
        if (put != null) {
            val vars = JSONObject(put.groupValues[1])
            for (k in vars.keys()) NovelScript.setVariable(source.id, k, ruleValue(content, vars.getString(k), source, base, key, page))
            return ruleValue(content, raw0.removeRange(put.range), source, base, key, page)
        }
        val raw = applyGetVars(raw0, source)
        val jsAt = raw.indexOf("@js:", ignoreCase = true).takeIf { it >= 0 } ?: raw.indexOf("<js>", ignoreCase = true)
        if (jsAt >= 0) {
            val prefix = raw.substring(0, jsAt)
            val input: Any = if (prefix.isBlank()) (if (content is JSONObject || content is JSONArray) content else if (content is LegadoRegexGroups) JSONArray(content.groups) else content.toString())
            else ruleValue(content, prefix, source, base, key, page)
            val code = Regex("\\{\\{(.*?)\\}\\}", RegexOption.DOT_MATCHES_ALL).replace(script(raw.substring(jsAt))) { m ->
                ruleValue(content, m.groupValues[1], source, base, key, page).replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"").replace("\n", "\\n")
            }
            return NovelScript(source, base, key, page).also { it.srcText = srcOf(content) }.eval(code, input)?.toString().orEmpty()
        }
        if (raw.contains("{{")) return Regex("\\{\\{(.*?)\\}\\}", RegexOption.DOT_MATCHES_ALL).replace(raw) { m ->
            val expr = m.groupValues[1]
            if (expr.startsWith('$') || expr.startsWith('@') || expr.startsWith("//")) ruleValue(content, expr, source, base, key, page)
            else NovelScript(source, base, key, page).also { it.srcText = srcOf(content) }.eval(expr, content.toString())?.toString().orEmpty()
        }
        val (topParts, topOp) = splitTop(raw)
        if (topOp == "||") return topParts.asSequence().map { ruleValue(content, it, source, base, key, page) }.firstOrNull { it.isNotBlank() }.orEmpty()
        if (topOp == "&&") return topParts.map { ruleValue(content, it, source, base, key, page) }.filter { it.isNotBlank() }.joinToString("\n")
        if (topOp == "%%") {
            val lists = topParts.map { ruleValue(content, it, source, base, key, page).lines().filter { l -> l.isNotEmpty() } }
            return (0 until (lists.maxOfOrNull { it.size } ?: 0)).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }.joinToString("\n")
        }

        val parts = raw.split("##")
        val rule = parts[0].trim()
        var text = when {
            content is LegadoRegexGroups -> {
                var out = rule
                for (i in content.groups.indices.reversed()) out = out.replace("$$i", content.groups[i])
                out
            }
            rule.startsWith(":") -> {
                val regex = Regex(rule.substring(1))
                val s = if (content is Element) content.outerHtml() else content.toString()
                regex.findAll(s).map { it.value }.joinToString("\n")
            }
            rule.contains("{$.") && (content is JSONObject || content is JSONArray || content is String) ->
                jsonTemplate(if (content is String) document(content) else content, rule)
            rule.startsWith('$') || rule.startsWith("@json:", true) || content is JSONObject || content is JSONArray ->
                jsonNodes(if (content is String) document(content) else content, rule).joinToString("\n")
            rule.startsWith("http://") || rule.startsWith("https://") -> rule
            rule.startsWith('/') || rule.startsWith("@xpath:", true) -> {
                val root = content as? Element ?: Jsoup.parse(content.toString())
                val w3c = org.jsoup.helper.W3CDom().namespaceAware(false); val doc = w3c.fromJsoup(root)
                val xpath = javax.xml.xpath.XPathFactory.newInstance().newXPath()
                val item = root !is org.jsoup.nodes.Document
                val expr = scopedXPath(root, rule.replace(Regex("^@xpath:", RegexOption.IGNORE_CASE), ""))
                runCatching {
                    val ns = xpath.evaluate(expr, if (item) w3c.contextNode(doc) else doc, javax.xml.xpath.XPathConstants.NODESET) as org.w3c.dom.NodeList
                    (0 until ns.length).joinToString("\n") { ns.item(it).textContent }
                }.getOrElse {
                    xpath.evaluate(expr, if (item) w3c.contextNode(doc) else doc, javax.xml.xpath.XPathConstants.STRING)?.toString().orEmpty()
                }
            }
            rule.startsWith("@css:", true) -> {
                val body = rule.substring(5).trim()
                val segs = splitAt(body)
                val attr = if (segs.size > 1 && segs.last().matches(Regex("^[A-Za-z][\\w\\-:]*$"))) segs.last() else "text"
                val sel = if (segs.size > 1 && attr == segs.last()) segs.dropLast(1).joinToString("@") else body
                val root = content as? Element ?: Jsoup.parse(content.toString())
                extractAttr(root.select(sel).toList(), attr)
            }
            rule in listOf("text", "ownText", "textNodes", "html", "all", "href", "src", "value", "content", "title", "alt") -> {
                val root = content as? Element ?: Jsoup.parse(content.toString())
                extractAttr(listOf(root), rule)
            }
            else -> {
                val cleaned = rule.removePrefix("@@")
                val segs = splitAt(cleaned)
                val root = content as? Element ?: Jsoup.parse(content.toString())
                if (cleaned.startsWith("@") && segs.size == 1) {
                    extractAttr(listOf(root), segs[0])
                } else if (segs.size > 1) {
                    val attr = segs.last()
                    val sel = segs.dropLast(1).joinToString("@")
                    extractAttr(select(root, sel), attr)
                } else {
                    extractAttr(select(root, cleaned), "text")
                }
            }
        }
        if (parts.size >= 2) {
            val regex = Regex(parts[1]); val replacement = parts.getOrElse(2) { "" }
            text = if (raw.endsWith("###")) regex.find(text)?.let { regex.replace(it.value, replacement) }.orEmpty() else regex.replace(text, replacement)
        }
        return text.trim()
    }

    private fun extractAttr(elements: List<Element>, attr0: String): String {
        val attr = attr0.removePrefix("abs:")
        return elements.mapNotNull { el ->
            when (attr) {
                "text" -> el.text()
                "ownText" -> el.ownText()
                "textNodes" -> el.textNodes().map { it.text().trim() }.filter { it.isNotEmpty() }.joinToString("\n")
                "html" -> el.html().replace(Regex("(?is)<(script|style)[\\s\\S]*?</\\1>"), "")
                "all" -> el.outerHtml()
                else -> el.attr(attr)
            }.takeIf { it.isNotEmpty() }
        }.joinToString("\n")
    }

    internal fun document(text: String): Any = when {
        text.trimStart().startsWith('{') -> runCatching { JSONObject(text) }.getOrDefault(text)
        text.trimStart().startsWith('[') -> runCatching { JSONArray(text) }.getOrDefault(text)
        Regex("<[A-Za-z!/]").containsMatchIn(text) -> Jsoup.parse(text)
        else -> text
    }

    private fun scopedXPath(content: Any, rule: String) = if (content is Element && content !is org.jsoup.nodes.Document && rule.startsWith("/")) ".$rule" else rule

    internal fun stripInvisible(text: String) = text.replace(Regex("[\\u00AD\\u200B-\\u200F\\u2060\\uFEFF]"), "")

    internal fun page(raw: String, source: NovelSource, key: String = "", page: Int = 1, contentWebJs: String? = null, contentSourceRegex: String? = null): PageResponse {
        val response = fetchResponse(raw, source, key, page, contentWebJs = contentWebJs, contentSourceRegex = contentSourceRegex)
        NovelScript.src.set(response.text)
        val check = source.json.optString("loginCheckJs").trim().takeIf { it.isNotEmpty() && it != "null" } ?: return response
        val code = "result = __strResponse(result);\n" + script(check)
        val value = NovelScript(source, response.url, key, page).eval(code, JSONObject().put("url", response.url).put("body", response.text).put("code", response.status))
        val body = (value as? JSONObject)?.optString("__body")?.takeIf { (value as JSONObject).has("__body") } ?: (value as? String)?.takeIf { it.isNotEmpty() }
        return (if (body == null) response else response.copy(text = body)).also { NovelScript.src.set(it.text) }
    }

    fun headers(source: NovelSource): JSONObject {
        val out = JSONObject()
        val rawObj = source.json.optJSONObject("header")
        if (rawObj != null) {
            for (k in rawObj.keys()) out.put(k, rawObj.get(k))
        } else {
            val raw = source.json.optString("header").trim()
            if (raw.isNotEmpty() && raw != "null") {
                val text = if (raw.startsWith("@js:", true) || raw.startsWith("<js>", true)) {
                    runCatching { NovelScript(source, source.id).eval(script(raw))?.toString().orEmpty() }.getOrDefault("")
                } else raw
                val parsed = LegadoUrlOptions.parseObject(text)
                for (k in parsed.keys()) out.put(k, parsed.get(k))
            }
        }
        // Merge persisted loginHeader (matching iOS & upstream BaseSource.getHeaderMap)
        val lh = LegadoLogin.loginHeader(source)
        for (k in lh.keys()) out.put(k, lh.get(k))
        return out
    }

    internal fun srcOf(content: Any?): String? = when (content) {
        is org.jsoup.nodes.Document -> null
        is Element -> content.outerHtml()
        is LegadoRegexGroups -> content.groups.firstOrNull()
        is JSONObject, is JSONArray, is String -> content.toString()
        else -> null
    }

    private fun item(v: String): Any {
        val t = v.trim()
        if (t.startsWith("<") && !t.startsWith("<html", true) && !t.startsWith("<!", true)) Jsoup.parseBodyFragment(t).body().children().singleOrNull()?.let { return it }
        return document(v)
    }

    internal fun firstRequestURL(raw: String): String {
        val text = raw.trim(); val options = Regex(",\\s*\\{").find(text)
        return if (options != null && !text.substring(0, options.range.first).contains('\n') && !text.substring(0, options.range.first).contains('\r')) text else firstLine(text)
    }
    internal fun firstLine(text: String) = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()

    fun search(source: NovelSource, key: String, page: Int = 1, category: NovelCategory? = null, batchMode: Boolean = false): List<NovelHit> {
        val prevBatch = inBatchSearch.get()
        if (batchMode) inBatchSearch.set(true)
        try {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("搜索已取消")
            if (source.isRSS) return LegadoRss.list(source, key, page, category)
            if (source.isJsSource) return LegadoJsSource.search(source, key, page, category)
            val raw = category?.url ?: source.json.optString("searchUrl")
            require(raw.isNotBlank()) { "书源没有搜索地址" }
            val response = page(raw, source, key, page)
            if (Thread.currentThread().isInterrupted) throw InterruptedException("搜索已取消")
            val rules = JSONObject(source.rules("ruleSearch").toString())
            if (category != null) source.rules("ruleExplore").let { extra -> for (k in extra.keys()) if (extra.optString(k).isNotBlank()) rules.put(k, extra.get(k)) }
            val base = response.url

            // Check `bookUrlPattern` direct-to-detail redirect (matching upstream BookList.kt)
            val pattern = source.bookUrlPattern
            if (category == null && pattern.isNotBlank() && runCatching { Regex(pattern).containsMatchIn(base) }.getOrDefault(false)) {
                val infoHit = runCatching { detailToHit(source, response.text, base) }.getOrNull()
                if (infoHit != null) return listOf(infoHit)
            }

            require(listOf("bookList", "name", "bookUrl").all { rules.optString(it).isNotBlank() }) { "书源缺少列表、书名或详情地址规则" }
            val nodeLimit = if (batchMode) 40 else 120
            val hits = nodes(document(response.text), rules.optString("bookList"), source, base, key, page).take(nodeLimit).mapNotNull { node ->
                if (Thread.currentThread().isInterrupted) throw InterruptedException("搜索已取消")
                fun v(k: String) = ruleValue(node, rules.optString(k), source, base, key, page)
                fun optional(k: String) = runCatching { v(k) }.getOrDefault("")
                val name = stripInvisible(v("name")).trim(); val url = firstRequestURL(v("bookUrl"))
                val intro = optional("intro").let { if (it.isBlank()) "" else cleanText(it).replace('\n', ' ').trim() }
                if (name.isBlank() || url.isBlank()) null else NovelHit(
                    name = name,
                    author = optional("author").replace(Regex("^作\\s*者[：:]\\s*"), ""),
                    url = absolute(url, base),
                    cover = runCatching { absolute(firstLine(optional("coverUrl")), base) }.getOrDefault(""),
                    kind = optional("kind"),
                    latest = optional("lastChapter"),
                    intro = intro,
                    wordCount = optional("wordCount"),
                    updateTime = optional("updateTime"),
                )
            }.distinctBy { it.url }

            // Fallback when search jumped straight to the book page (matching iOS `allowDetailFallback`)
            if (hits.isEmpty() && category == null) {
                val fallback = runCatching { detailToHit(source, response.text, base) }.getOrNull()
                if (fallback != null) return listOf(fallback)
            }
            return hits
        } finally {
            NovelScript.src.remove()
            if (prevBatch == null) inBatchSearch.remove() else inBatchSearch.set(prevBatch)
        }
    }

    private fun detailToHit(source: NovelSource, html: String, url: String): NovelHit? {
        val info = source.rules("ruleBookInfo")
        if (info.length() == 0) return null
        var detail: Any = document(html)
        if (info.optString("init").isNotBlank()) detail = nodes(detail, info.getString("init"), source, url).firstOrNull() ?: detail
        fun v(k: String) = runCatching { ruleValue(detail, info.optString(k), source, url) }.getOrDefault("")
        val name = stripInvisible(v("name")).trim()
        if (name.isBlank()) return null
        return NovelHit(
            name = name,
            author = v("author").replace(Regex("^作\\s*者[：:]\\s*"), ""),
            url = url,
            cover = runCatching { absolute(firstLine(v("coverUrl")), url) }.getOrDefault(""),
            kind = v("kind"),
            latest = v("lastChapter"),
            intro = v("intro"),
            wordCount = v("wordCount"),
            updateTime = v("updateTime"),
        )
    }

    fun details(source: NovelSource, bookURL: String): NovelBookDetails = NovelScript.withBook(bookURL) {
        if (source.isRSS) return@withBook LegadoRss.details(source, bookURL)
        if (source.isJsSource) return@withBook LegadoJsSource.details(source, bookURL)
        var detail: Any = document(page(bookURL, source).text)
        val info = source.rules("ruleBookInfo")
        if (info.optString("init").isNotBlank()) detail = nodes(detail, info.getString("init"), source, bookURL).firstOrNull() ?: detail
        fun v(key: String) = runCatching { ruleValue(detail, info.optString(key), source, bookURL) }.getOrDefault("")
        val name = stripInvisible(v("name"))
        val author = v("author").replace(Regex("^作\\s*者[：:]\\s*"), "")
        val intro = v("intro")
        val cover = firstLine(v("coverUrl"))
        val kind = v("kind")
        val latest = v("lastChapter")
        val wordCount = v("wordCount")
        val updateTime = v("updateTime")
        val downloadUrls = v("downloadUrls").lines().map { it.trim() }.filter { it.isNotBlank() }.map { absolute(it, bookURL) }
        val toc = ruleValue(detail, info.optString("tocUrl"), source, bookURL)
        var url = if (toc.isBlank()) bookURL else absolute(toc, bookURL)

        val bookObj = JSONObject()
            .put("bookUrl", bookURL).put("tocUrl", url).put("name", name).put("author", author)
            .put("intro", intro).put("kind", kind).put("coverUrl", cover).put("latestChapterTitle", latest)
            .put("wordCount", wordCount).put("origin", source.id).put("originName", source.name)

        val rules = source.rules("ruleToc")
        // Upstream `TocRule.preUpdateJs`: runs before fetching/parsing chapter list
        val preUpdateJs = rules.optString("preUpdateJs").takeIf { it.isNotBlank() && it != "null" }
        if (preUpdateJs != null) {
            runCatching { NovelScript.withBook(bookURL, bookObj) { NovelScript(source, url).eval(script(preUpdateJs)) } }
        }

        val formatJs = rules.optString("formatJs").takeIf { it.isNotBlank() && it != "null" }
        val out = arrayListOf<NovelChapter>(); val seen = hashSetOf<String>(); val queue = ArrayDeque(listOf(url))
        NovelScript.withBook(bookURL, bookObj) {
            while (seen.size < 100 && queue.isNotEmpty()) {
                url = queue.removeFirst(); if (!seen.add(url)) continue
                val doc = if (url == bookURL) detail else document(page(url, source).text)
                for (n in nodes(doc, rules.optString("chapterList"), source, url)) {
                    var title = ruleValue(n, rules.optString("chapterName"), source, url)
                    val address = ruleValue(n, rules.optString("chapterUrl"), source, url)
                    val volumeRule = rules.optString("isVolume")
                    val isVolume = volumeRule.isNotBlank() && ruleValue(n, volumeRule, source, url).lowercase() in setOf("true", "1")
                    val vipRule = rules.optString("isVip")
                    val isVip = vipRule.isNotBlank() && ruleValue(n, vipRule, source, url).lowercase() in setOf("true", "1")
                    val payRule = rules.optString("isPay")
                    val isPay = payRule.isNotBlank() && ruleValue(n, payRule, source, url).lowercase() in setOf("true", "1")
                    val chTime = rules.optString("updateTime").takeIf { it.isNotBlank() }?.let { runCatching { ruleValue(n, it, source, url) }.getOrDefault("") }.orEmpty()
                    if (title.isNotBlank() && (address.isNotBlank() || !isVolume)) {
                        if (formatJs != null) {
                            runCatching {
                                NovelScript(source, url).eval(script(formatJs), title, mapOf("index" to out.size, "title" to title))?.toString()
                            }.getOrNull()?.takeIf { it.isNotBlank() }?.let { title = it }
                        }
                        title = LegadoReplaceStore.applyTitle(title, name, source.id, source.name)
                        out += NovelChapter(title, absolute(address.ifBlank { url }, url), out.size, isVolume, isVip, isPay, chTime)
                    }
                }
                val next = ruleValue(doc, rules.optString("nextTocUrl"), source, url)
                for (link in next.lines().filter { it.isNotBlank() }) {
                    val target = absolute(link, url)
                    if (target !in seen && target !in queue) queue.add(target)
                }
            }
        }
        NovelBookDetails(
            name = name,
            author = author,
            intro = intro,
            cover = if (cover.isBlank()) "" else runCatching { absolute(cover, bookURL) }.getOrDefault(""),
            kind = kind,
            latest = latest,
            chapters = out.distinctBy { it.url }.mapIndexed { i, ch -> ch.copy(index = i) },
            wordCount = wordCount,
            updateTime = updateTime,
            downloadUrls = downloadUrls,
        )
    }

    fun chapters(source: NovelSource, bookURL: String): List<NovelChapter> = details(source, bookURL).chapters

    fun content(source: NovelSource, chapter: NovelChapter, nextChapter: String? = null, bookURL: String? = null, bookName: String = ""): String =
        NovelScript.withBook(bookURL) {
            NovelScript.withChapter(chapter, nextChapter) {
                require(!source.isMedia) { "媒体源应进入原生播放，不得当作文本缓存" }
                if (source.isRSS) return@withChapter LegadoRss.content(source, chapter.url)
                if (source.isJsSource) return@withChapter LegadoJsSource.content(source, chapter, nextChapter, bookURL, bookName)
                val rules = source.rules("ruleContent")
                val webJs = rules.optString("webJs").takeIf { it.isNotBlank() && it != "null" }
                val sourceRegex = rules.optString("sourceRegex").takeIf { it.isNotBlank() && it != "null" }
                var url = chapter.url; val seen = hashSetOf<String>(); val parts = arrayListOf<String>()
                var extractedTitle = ""
                var subText = ""
                while (seen.size < 30 && seen.add(url)) {
                    val resp = page(url, source, contentWebJs = webJs, contentSourceRegex = sourceRegex)
                    val doc = document(resp.text)
                    val raw = ruleValue(doc, rules.optString("content"), source, resp.url)
                    parts += cleanContentPreservingImages(raw, resp.url, keepImages = true)
                    if (extractedTitle.isEmpty()) {
                        rules.optString("title").takeIf { it.isNotBlank() && it != "null" }?.let { tRule ->
                            extractedTitle = runCatching { ruleValue(doc, tRule, source, resp.url) }.getOrDefault("")
                        }
                    }
                    if (subText.isEmpty()) {
                        rules.optString("subContent").takeIf { it.isNotBlank() && it != "null" }?.let { sRule ->
                            subText = runCatching { cleanText(ruleValue(doc, sRule, source, resp.url)) }.getOrDefault("")
                        }
                    }
                    val next = ruleValue(doc, rules.optString("nextContentUrl"), source, resp.url); if (next.isBlank()) break
                    url = absolute(firstLine(next), resp.url); if (url == nextChapter) break
                }
                var text = parts.filter { it.isNotBlank() }.joinToString("\n\n")
                if (subText.isNotBlank()) text = if (text.isBlank()) subText else "$text\n\n$subText"

                // Upstream `ContentRule.replaceRegex`: supports `@js:` / `<js>` OR line-by-line `##regex##replacement`
                val rr = rules.optString("replaceRegex").trim()
                if (rr.isNotEmpty() && rr != "null") {
                    text = if (rr.startsWith("@js:", true) || rr.startsWith("<js>", true) || rr.contains("<js>", true) || rr.contains("@js:", true)) {
                        ruleValue(text, rr, source, chapter.url)
                    } else {
                        var cur = text
                        for (line in rr.lines().filter { it.isNotBlank() }) {
                            val firstOnly = line.endsWith("###")
                            val p = line.removePrefix("##").split("##")
                            val regex = Regex(p[0])
                            val repl = p.getOrElse(1) { "" }
                            cur = if (firstOnly) regex.find(cur)?.let { regex.replace(it.value, repl) }.orEmpty() else cur.replace(regex, repl)
                        }
                        cur
                    }
                }
                text = LegadoReplaceStore.applyContent(text, bookName, source.id, source.name)
                if (extractedTitle.isNotBlank() && !text.startsWith(extractedTitle)) {
                    text = "$extractedTitle\n$text"
                }
                require(text.isNotBlank()) { "章节正文为空或书源规则不兼容" }
                text
            }
        }

    /** Batch chapter fetch supporting both JS sources (`getContentBatch`) and JSON `ruleContent.contentBatch`. */
    fun contentBatch(source: NovelSource, chapters: List<NovelChapter>, bookURL: String? = null, bookName: String = ""): Map<String, String> {
        if (chapters.isEmpty() || source.isMedia || source.isRSS) return emptyMap()
        if (source.isJsSource) return LegadoJsSource.contentBatch(source, chapters, bookURL, bookName)
        val batchRule = source.rules("ruleContent").optString("contentBatch").trim()
        if (batchRule.isEmpty() || batchRule == "null") return emptyMap()
        val batchCtx = BatchContentContext(chapters)
        val arr = JSONArray(chapters.map { JSONObject().put("title", it.title).put("url", it.url).put("index", it.index) })
        NovelScript.withBook(bookURL) {
            NovelScript.withBatch(batchCtx) {
                NovelScript(source, bookURL ?: source.id).eval(script(batchRule), arr, mapOf("chapters" to arr))
            }
        }
        return batchCtx.asMap().mapValues { (u, raw) ->
            LegadoReplaceStore.applyContent(cleanContentPreservingImages(raw, u, keepImages = true), bookName, source.id, source.name)
        }.filterValues { it.isNotBlank() }
    }

    /**
     * Converts HTML content into clean paragraphs while preserving inline `<img src="resolvedUrl"/>`
     * lines for illustrated novels and manga (`bookSourceType == 2`).
     */
    fun cleanContentPreservingImages(raw: String, baseUrl: String, keepImages: Boolean = true): String {
        if (raw.isBlank()) return ""
        val hasImg = keepImages && Regex("<img\\b", RegexOption.IGNORE_CASE).containsMatchIn(raw)
        if (!hasImg) return cleanText(raw)
        val doc = Jsoup.parseBodyFragment(raw, baseUrl)
        doc.select("script,style").remove()
        val imgs = ArrayList<String>()
        for (img in doc.select("img")) {
            val src = listOf("data-src", "data-original", "src").firstNotNullOfOrNull { k -> img.attr(k).trim().takeIf { it.isNotEmpty() && !it.startsWith("data:image/gif;base64,R0lGOD") } }
                ?: img.attr("src").trim()
            if (src.isNotEmpty()) {
                val resolved = runCatching { absolute(src, baseUrl) }.getOrDefault(src)
                val marker = "__IDJ_IMG_${imgs.size}__"
                imgs += "<img src=\"$resolved\"/>"
                img.replaceWith(org.jsoup.nodes.TextNode("\n$marker\n"))
            } else {
                img.remove()
            }
        }
        var cleaned = cleanText(doc.body().html())
        imgs.forEachIndexed { idx, tag -> cleaned = cleaned.replace("__IDJ_IMG_${idx}__", tag) }
        return cleaned
    }

    fun cleanText(raw: String): String = Jsoup.parse(raw.replace(Regex("(?i)<br\\s*/?>|</p>|</div>"), "\n")).wholeText()
        .lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
}

data class NovelBookDetails(
    val name: String,
    val author: String,
    val intro: String,
    val cover: String,
    val kind: String,
    val latest: String,
    val chapters: List<NovelChapter>,
    val wordCount: String = "",
    val updateTime: String = "",
    val downloadUrls: List<String> = emptyList(),
)
