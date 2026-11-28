package app.iduanju.core.novel

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Batch chapter content context matching LegadoTeam's `BatchContentContext`.
 * Allows JS/JSON `contentBatch` rules to store multiple chapter bodies via `java.cacheContent(chapter, text)`.
 */
class BatchContentContext(val chapters: List<NovelChapter>) {
    private val saved = ConcurrentHashMap<String, String>()
    private val byIndex = ConcurrentHashMap<Int, String>()

    fun put(chapterUrl: String, index: Int, content: String): Boolean {
        if (content.isBlank()) return false
        if (chapterUrl.isNotBlank()) saved[chapterUrl] = content
        if (index >= 0) byIndex[index] = content
        return true
    }

    fun get(chapter: NovelChapter): String? = saved[chapter.url] ?: byIndex[chapter.index]
    fun savedCount(): Int = chapters.count { get(it) != null }
    fun asMap(): Map<String, String> = chapters.mapNotNull { ch -> get(ch)?.let { ch.url to it } }.toMap()
}

/**
 * LegadoTeam single-file JavaScript Book Source (`mainJs` / `.js` import) engine
 * plus cover / content image byte decryption (`coverDecodeJs`, `ruleContent.imageDecode`).
 */
object LegadoJsSource {
    /** Detects whether raw text is a single-file Legado JS book source (not JSON). */
    fun looksLikeJsSource(raw: String): Boolean {
        val t = raw.trim().removePrefix("\uFEFF")
        if (t.isEmpty() || t.startsWith("{") || t.startsWith("[")) return false
        return t.contains("var config") || t.contains("const config") || t.contains("let config") ||
            t.contains("function search") || t.contains("function getChapters")
    }

    /** Parses a single-file `.js` book source into a standard `NovelSource` carrying `mainJs`. */
    fun extract(jsText: String): NovelSource {
        val probe = NovelSource(JSONObject().put("bookSourceUrl", "js-source://init").put("mainJs", jsText))
        val inspectScript = """
            $jsText
            ;JSON.stringify({
                config: (typeof config === 'object' && config) ? config : {},
                hasSearch: typeof search === 'function',
                hasExplore: typeof explore === 'function',
                hasBookInfo: typeof getBookInfo === 'function',
                hasChapters: typeof getChapters === 'function',
                hasContent: typeof getContent === 'function',
                hasContentBatch: typeof getContentBatch === 'function',
                hasLogin: typeof login === 'function',
                hasLoginV2: typeof loginUi === 'function' && typeof loginAction === 'function',
                hasReview: typeof getReviewSummary === 'function' && typeof getReviewDetail === 'function',
                hasReviewReplies: typeof getReviewReplies === 'function'
            });
        """.trimIndent()
        val raw = NovelScript(probe, probe.id).eval(inspectScript)?.toString().orEmpty()
        val meta = runCatching { JSONObject(raw) }.getOrNull() ?: error("JS 书源配置解析失败")
        val cfg = meta.optJSONObject("config") ?: error("JS 书源缺少 config 对象")
        val url = cfg.optString("bookSourceUrl").trim()
        require(url.isNotBlank()) { "JS 书源缺少 config.bookSourceUrl" }
        require(meta.optBoolean("hasSearch") || meta.optBoolean("hasExplore")) { "JS 书源缺少 search 或 explore 函数" }
        require(meta.optBoolean("hasChapters") && meta.optBoolean("hasContent")) { "JS 书源缺少 getChapters 或 getContent 函数" }

        val out = JSONObject(cfg.toString())
        out.put("bookSourceUrl", url)
        if (out.optString("bookSourceName").isBlank()) out.put("bookSourceName", url)
        out.put("mainJs", jsText)
        if (meta.optBoolean("hasLoginV2")) out.put("loginUi", """{"version":2}""")
        out.put("_jsCaps", meta)
        // Normalize exploreUrl array to JSON string if provided as array
        cfg.optJSONArray("exploreUrl")?.let { out.put("exploreUrl", it.toString()) }
        cfg.optJSONArray("loginUi")?.let { out.put("loginUi", it.toString()) }
        if (meta.optBoolean("hasReview") && !out.has("ruleReview")) {
            out.put("ruleReview", JSONObject().put("enabled", true))
        }
        return NovelSource(out)
    }

    fun hasLogin(source: NovelSource): Boolean {
        val caps = source.json.optJSONObject("_jsCaps")
        if (caps != null) return caps.optBoolean("hasLogin") || caps.optBoolean("hasLoginV2")
        val js = source.mainJs
        return js.contains("function login(") || hasLoginV2(source)
    }

    fun hasLoginV2(source: NovelSource): Boolean {
        val caps = source.json.optJSONObject("_jsCaps")
        if (caps != null) return caps.optBoolean("hasLoginV2")
        val js = source.mainJs
        return js.contains("function loginUi(") && js.contains("function loginAction(")
    }

    fun hasReview(source: NovelSource): Boolean {
        val caps = source.json.optJSONObject("_jsCaps")
        if (caps != null) return caps.optBoolean("hasReview")
        val js = source.mainJs
        return js.contains("function getReviewSummary(") && js.contains("function getReviewDetail(")
    }

    fun search(source: NovelSource, key: String, page: Int = 1, category: NovelCategory? = null): List<NovelHit> {
        val callExpr = if (category != null) {
            "typeof explore === 'function' ? explore(${JSONObject.quote(category.url)}, $page) : []"
        } else {
            "typeof search === 'function' ? search(${JSONObject.quote(key)}, $page) : []"
        }
        val raw = run(source, callExpr, key = key, page = page)
        val arr = asArray(raw)
        val out = ArrayList<NovelHit>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = NovelSources.stripInvisible(o.optString("name")).trim()
            val url = o.optString("bookUrl").trim()
            if (name.isEmpty() || url.isEmpty()) continue
            out += NovelHit(
                name = name,
                author = o.optString("author").trim(),
                url = NovelSources.absolute(url, source.id),
                cover = o.optString("coverUrl").takeIf { it.isNotBlank() }?.let { runCatching { NovelSources.absolute(it, source.id) }.getOrDefault(it) }.orEmpty(),
                kind = o.optString("kind").trim(),
                latest = o.optString("latestChapterTitle", o.optString("lastChapter")).trim(),
                intro = o.optString("intro").trim(),
            )
        }
        return out.distinctBy { it.url }
    }

    fun details(source: NovelSource, bookURL: String, seed: NovelHit? = null): NovelBookDetails = NovelScript.withBook(bookURL) {
        val bookObj = JSONObject()
            .put("bookUrl", bookURL)
            .put("tocUrl", bookURL)
            .put("origin", source.id)
            .put("originName", source.name)
        if (seed != null) {
            bookObj.put("name", seed.name).put("author", seed.author).put("intro", seed.intro)
                .put("coverUrl", seed.cover).put("kind", seed.kind).put("latestChapterTitle", seed.latest)
        }
        val infoRaw = run(source, "typeof getBookInfo === 'function' ? getBookInfo($bookObj) : null", base = bookURL)
        val info = asObject(infoRaw)
        val name = info?.optString("name")?.takeIf { it.isNotBlank() } ?: seed?.name.orEmpty()
        val author = info?.optString("author")?.takeIf { it.isNotBlank() } ?: seed?.author.orEmpty()
        val intro = info?.optString("intro")?.takeIf { it.isNotBlank() } ?: seed?.intro.orEmpty()
        val cover = info?.optString("coverUrl")?.takeIf { it.isNotBlank() } ?: seed?.cover.orEmpty()
        val kind = info?.optString("kind")?.takeIf { it.isNotBlank() } ?: seed?.kind.orEmpty()
        val latest = info?.optString("latestChapterTitle", info.optString("lastChapter"))?.takeIf { it.isNotBlank() } ?: seed?.latest.orEmpty()
        val tocUrl = info?.optString("tocUrl")?.takeIf { it.isNotBlank() }?.let { NovelSources.absolute(it, bookURL) } ?: bookURL
        val downloadUrls = info?.opt("downloadUrls")?.let { d ->
            when (d) {
                is JSONArray -> (0 until d.length()).map { d.optString(it) }.filter { it.isNotBlank() }
                is String -> d.lines().map { it.trim() }.filter { it.isNotBlank() }
                else -> emptyList()
            }
        }.orEmpty()

        bookObj.put("name", name).put("author", author).put("tocUrl", tocUrl)
        val chRaw = run(source, "getChapters($bookObj)", base = tocUrl)
        val arr = asArray(chRaw)
        val chapters = ArrayList<NovelChapter>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title", o.optString("name")).trim()
            val url = o.optString("url").trim()
            val isVol = o.optBoolean("isVolume", false)
            if (title.isEmpty() || (isVol && url.isEmpty())) continue
            chapters += NovelChapter(
                title = title,
                url = if (url.isEmpty()) tocUrl else NovelSources.absolute(url, tocUrl),
                index = chapters.size,
                isVolume = isVol,
                isVip = o.optBoolean("isVip", false),
                isPay = o.optBoolean("isPay", false),
                updateTime = o.optString("updateTime").takeIf { it.isNotBlank() && it != "null" }.orEmpty(),
            )
        }
        NovelBookDetails(
            name = name,
            author = author,
            intro = intro,
            cover = if (cover.isBlank()) "" else runCatching { NovelSources.absolute(cover, bookURL) }.getOrDefault(cover),
            kind = kind,
            latest = latest,
            chapters = chapters.distinctBy { it.url }.mapIndexed { idx, ch -> ch.copy(index = idx) },
            wordCount = info?.optString("wordCount").orEmpty(),
            updateTime = info?.optString("updateTime").orEmpty(),
            downloadUrls = downloadUrls,
        )
    }

    fun content(source: NovelSource, chapter: NovelChapter, nextChapter: String? = null, bookURL: String? = null, bookName: String = ""): String =
        NovelScript.withBook(bookURL) {
            NovelScript.withChapter(chapter, nextChapter) {
                val chObj = JSONObject().put("title", chapter.title).put("url", chapter.url).put("index", chapter.index)
                val bookObj = JSONObject().put("bookUrl", bookURL.orEmpty()).put("name", bookName)
                val nextLit = nextChapter?.let { JSONObject.quote(it) } ?: "null"
                val raw = run(source, "getContent($chObj, $bookObj, $nextLit)", base = chapter.url)?.toString().orEmpty()
                val text = NovelSources.cleanContentPreservingImages(raw, chapter.url, keepImages = true)
                val purified = LegadoReplaceStore.applyContent(text, bookName, source.id, source.name)
                require(purified.isNotBlank()) { "JS 书源返回空正文" }
                purified
            }
        }

    /** Calls `getContentBatch(chapters, book)` on a JS source and returns url -> cleaned content map. */
    fun contentBatch(source: NovelSource, chapters: List<NovelChapter>, bookURL: String? = null, bookName: String = ""): Map<String, String> =
        NovelScript.withBook(bookURL) {
            val batchCtx = BatchContentContext(chapters)
            val arr = JSONArray()
            for (ch in chapters) arr.put(JSONObject().put("title", ch.title).put("url", ch.url).put("index", ch.index))
            val bookObj = JSONObject().put("bookUrl", bookURL.orEmpty()).put("name", bookName)
            val script = "${source.mainJs}\n;(typeof getContentBatch === 'function' ? getContentBatch($arr, $bookObj) : null);"
            val result = NovelScript.withBatch(batchCtx) {
                NovelScript(source, bookURL ?: source.id).eval(script)
            }
            val merged = LinkedHashMap<String, String>()
            for ((u, raw) in batchCtx.asMap()) {
                val cleaned = LegadoReplaceStore.applyContent(NovelSources.cleanContentPreservingImages(raw, u, keepImages = true), bookName, source.id, source.name)
                if (cleaned.isNotBlank()) merged[u] = cleaned
            }
            if (result is JSONObject) {
                for (k in result.keys()) {
                    val v = result.optString(k)
                    if (v.isNotBlank()) merged[k] = LegadoReplaceStore.applyContent(NovelSources.cleanContentPreservingImages(v, k, keepImages = true), bookName, source.id, source.name)
                }
            } else if (result is JSONArray) {
                for (i in 0 until minOf(result.length(), chapters.size)) {
                    val v = result.optString(i)
                    if (v.isNotBlank()) merged[chapters[i].url] = LegadoReplaceStore.applyContent(NovelSources.cleanContentPreservingImages(v, chapters[i].url, keepImages = true), bookName, source.id, source.name)
                }
            }
            merged
        }

    /** Decodes obfuscated image or cover bytes via `ruleContent.imageDecode` or `coverDecodeJs`. */
    fun decodeImageBytes(source: NovelSource, imageUrl: String, bytes: ByteArray, isCover: Boolean = false): ByteArray {
        val rule = if (isCover) source.json.optString("coverDecodeJs") else source.rules("ruleContent").optString("imageDecode")
        val trimmed = rule.trim()
        if (trimmed.isEmpty() || trimmed == "null") return bytes
        val code = trimmed.removePrefix("@js:").removePrefix("<js>").removeSuffix("</js>")
        val signed = JSONArray(bytes.map { it.toInt() })
        val out = NovelScript(source, imageUrl).eval(code, signed)
        return when (out) {
            is JSONArray -> ByteArray(out.length()) { out.optInt(it).toByte() }
            is String -> runCatching { java.util.Base64.getDecoder().decode(out.trim()) }.getOrDefault(bytes)
            else -> bytes
        }
    }

    internal fun run(source: NovelSource, expr: String, base: String = source.id, key: String = "", page: Int = 1): Any? {
        val code = "${source.mainJs}\n;($expr);"
        return NovelScript(source, base, key, page).eval(code)
    }

    private fun asArray(v: Any?): JSONArray = when (v) {
        is JSONArray -> v
        is String -> runCatching { JSONArray(v) }.getOrDefault(JSONArray())
        else -> JSONArray()
    }

    private fun asObject(v: Any?): JSONObject? = when (v) {
        is JSONObject -> v
        is String -> runCatching { JSONObject(v) }.getOrNull()
        else -> null
    }
}
