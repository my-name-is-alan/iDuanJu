package app.iduanju.core.novel

import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Single-source step-by-step debugger (matching Legado's `Debug.kt`)
 * and concurrent batch validator (matching Legado's `CheckSource.kt`).
 */
object LegadoTools {
    data class DebugEvent(
        val timeMs: Long,
        val stage: String,   // "start" / "http" / "log" / "search" / "info" / "toc" / "content" / "done" / "error"
        val message: String,
        val isError: Boolean = false,
    )

    data class DebugReport(
        val events: List<DebugEvent>,
        val succeeded: Boolean,
        val elapsedMs: Long,
    )

    internal val activeDebugListener = ThreadLocal<((DebugEvent) -> Unit)?>()

    internal fun emitDebug(stage: String, message: String, isError: Boolean = false) {
        activeDebugListener.get()?.invoke(DebugEvent(System.currentTimeMillis(), stage, message, isError))
    }

    /**
     * Runs a full live debug trace on [source]:
     * - If [query] starts with `http://` or `https://`: starts at BookInfo directly
     * - If [query] is blank and source has explore categories: debugs first explore category
     * - Otherwise: runs search([query]) -> details/toc -> first chapter content (or media resolve)
     */
    fun debug(source: NovelSource, query: String = "我的", onEvent: (DebugEvent) -> Unit = {}): DebugReport {
        val events = ArrayList<DebugEvent>()
        val t0 = System.currentTimeMillis()
        fun log(stage: String, msg: String, err: Boolean = false) {
            val ev = DebugEvent(System.currentTimeMillis() - t0, stage, msg, err)
            events += ev
            onEvent(ev)
        }
        val prev = activeDebugListener.get()
        activeDebugListener.set { ev ->
            val relative = ev.copy(timeMs = System.currentTimeMillis() - t0)
            events += relative
            onEvent(relative)
        }
        try {
            val q = query.trim().ifEmpty { source.rules("ruleSearch").optString("checkKeyWord").ifBlank { "我的" } }
            log("start", "开始调试书源：${source.name} (${source.id})，类型=${source.bookSourceType}，JS源=${source.isJsSource}")
            val bookUrl: String
            val bookName: String
            if (q.startsWith("http://", true) || q.startsWith("https://", true)) {
                bookUrl = q; bookName = q
                log("info", "直达详情地址：$bookUrl")
            } else if (source.json.optString("searchUrl").isBlank() && !source.isJsSource) {
                val cats = NovelSources.categories(source).filter { !it.isControl && it.url.isNotBlank() }
                log("search", "发现分类数：${cats.size}")
                val firstCat = cats.firstOrNull() ?: error("书源无搜索地址且无有效发现分类")
                log("search", "正在请求发现分类「${firstCat.title}」: ${firstCat.url}")
                val hits = NovelSources.search(source, "", page = 1, category = firstCat)
                log("search", "发现命中 ${hits.size} 本" + (hits.firstOrNull()?.let { "；首本：《${it.name}》(${it.author}) -> ${it.url}" } ?: ""))
                val first = hits.firstOrNull() ?: error("发现列表为空")
                bookUrl = first.url; bookName = first.name
            } else {
                log("search", "正在搜索关键词：「$q」")
                val hits = NovelSources.search(source, q, page = 1)
                log("search", "搜索命中 ${hits.size} 本" + (hits.firstOrNull()?.let { "；首本：《${it.name}》(${it.author}) -> ${it.url}" } ?: ""))
                val first = hits.firstOrNull() ?: error("搜索结果为空")
                bookUrl = first.url; bookName = first.name
            }

            log("info", "正在获取详情与目录：$bookUrl")
            val details = NovelSources.details(source, bookUrl)
            log("info", "详情解析完成：书名=《${details.name.ifBlank { bookName }}》 作者=${details.author} 简介=${details.intro.length}字 封面=${details.cover.take(60)}")
            log("toc", "目录解析完成：共 ${details.chapters.size} 章" + (details.chapters.firstOrNull()?.let { "；首章：「${it.title}」 -> ${it.url}" } ?: ""))
            val firstCh = details.chapters.firstOrNull() ?: error("章节目录为空")
            val nextCh = details.chapters.getOrNull(1)?.url

            if (source.isMedia) {
                log("content", "正在解析媒体地址：${firstCh.url}")
                val media = LegadoMedia.resolve(source, firstCh, bookUrl, nextCh)
                log("content", "媒体解析成功：${media.url} (headers=${media.headers.keys})")
            } else if (source.bookSourceType == LegadoSourceType.file && details.downloadUrls.isNotEmpty()) {
                log("content", "下载型书源解析成功：下载链接 ${details.downloadUrls.size} 个 -> ${details.downloadUrls.first()}")
            } else {
                log("content", "正在获取首章正文：${firstCh.url}")
                val text = NovelSources.content(source, firstCh, nextCh, bookUrl, details.name.ifBlank { bookName })
                val preview = text.replace('\n', ' ').take(120)
                log("content", "正文解析成功：共 ${text.length} 字；预览：$preview")
            }
            log("done", "调试全部通过，耗时 ${System.currentTimeMillis() - t0} ms")
            return DebugReport(events, true, System.currentTimeMillis() - t0)
        } catch (e: Throwable) {
            log("error", "调试失败：${e.message ?: e.javaClass.simpleName}", err = true)
            return DebugReport(events, false, System.currentTimeMillis() - t0)
        } finally {
            activeDebugListener.set(prev)
        }
    }

    enum class CheckStatus(val label: String) {
        OK("正常"),
        EMPTY("结果为空"),
        TIMEOUT("超时"),
        JS_ERROR("脚本错误"),
        HTTP_ERROR("网络/HTTP异常"),
    }

    data class CheckResult(
        val sourceId: String,
        val sourceName: String,
        val status: CheckStatus,
        val latencyMs: Long,
        val detail: String,
        val checkedAt: Long = System.currentTimeMillis(),
    )

    private val checkHistory = ConcurrentHashMap<String, CheckResult>()
    private var checkFile: File? = null

    @Synchronized fun configureCheckStorage(root: File) {
        val target = File(root, "novel-source-checks.json")
        if (checkFile == target) return
        checkFile = target
        checkHistory.clear()
        val obj = runCatching { JSONObject(target.readText()) }.getOrNull() ?: return
        for (k in obj.keys()) {
            val r = obj.optJSONObject(k) ?: continue
            val st = runCatching { CheckStatus.valueOf(r.optString("status")) }.getOrDefault(CheckStatus.HTTP_ERROR)
            checkHistory[k] = CheckResult(k, r.optString("name", k), st, r.optLong("latencyMs", 0), r.optString("detail"), r.optLong("checkedAt", 0))
        }
    }

    fun lastCheck(sourceId: String): CheckResult? = checkHistory[sourceId]
    fun allChecks(): Map<String, CheckResult> = checkHistory.toMap()

    fun checkOne(source: NovelSource, keyword: String = "我的", checkContent: Boolean = true): CheckResult {
        val t0 = System.currentTimeMillis()
        val key = source.rules("ruleSearch").optString("checkKeyWord").ifBlank { keyword }
        val result = try {
            val hits = if (source.json.optString("searchUrl").isNotBlank() || source.isJsSource) {
                NovelSources.search(source, key, 1)
            } else {
                val cat = NovelSources.categories(source).firstOrNull { !it.isControl && it.url.isNotBlank() }
                    ?: error("无搜索与发现地址")
                NovelSources.search(source, "", 1, cat)
            }
            if (hits.isEmpty()) {
                CheckResult(source.id, source.name, CheckStatus.EMPTY, System.currentTimeMillis() - t0, "搜索/发现结果为空")
            } else if (!checkContent) {
                CheckResult(source.id, source.name, CheckStatus.OK, System.currentTimeMillis() - t0, "命中 ${hits.size} 本")
            } else {
                val details = NovelSources.details(source, hits.first().url)
                val firstCh = details.chapters.firstOrNull()
                if (firstCh == null) {
                    CheckResult(source.id, source.name, CheckStatus.EMPTY, System.currentTimeMillis() - t0, "目录为空")
                } else if (source.isMedia) {
                    val m = LegadoMedia.resolve(source, firstCh, hits.first().url, details.chapters.getOrNull(1)?.url)
                    CheckResult(source.id, source.name, CheckStatus.OK, System.currentTimeMillis() - t0, "目录 ${details.chapters.size} 章 · 媒体可用 (${m.url.take(40)})")
                } else {
                    val body = NovelSources.content(source, firstCh, details.chapters.getOrNull(1)?.url, hits.first().url, details.name)
                    CheckResult(source.id, source.name, CheckStatus.OK, System.currentTimeMillis() - t0, "目录 ${details.chapters.size} 章 · 正文 ${body.length} 字")
                }
            }
        } catch (e: Throwable) {
            val msg = e.message ?: e.javaClass.simpleName
            val status = when {
                msg.contains("超时") || e is java.net.SocketTimeoutException -> CheckStatus.TIMEOUT
                msg.contains("脚本") || msg.contains("JS") || msg.contains("Rhino") || msg.contains("书源宿主") -> CheckStatus.JS_ERROR
                msg.contains("为空") -> CheckStatus.EMPTY
                else -> CheckStatus.HTTP_ERROR
            }
            CheckResult(source.id, source.name, status, System.currentTimeMillis() - t0, msg.take(120))
        }
        checkHistory[source.id] = result
        persistChecks()
        return result
    }

    /** Validates [sources] concurrently (bounded thread pool), calling [onProgress] as each finishes. */
    fun checkBatch(
        sources: List<NovelSource>,
        keyword: String = "我的",
        concurrency: Int = 4,
        checkContent: Boolean = true,
        isCancelled: () -> Boolean = { false },
        onProgress: (done: Int, total: Int, result: CheckResult) -> Unit = { _, _, _ -> },
    ): List<CheckResult> {
        if (sources.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(concurrency.coerceIn(1, 8))
        val results = ArrayList<CheckResult>(sources.size)
        val futures = ArrayList<Future<CheckResult?>>(sources.size)
        try {
            for (s in sources) {
                futures += pool.submit<CheckResult?> {
                    if (isCancelled() || Thread.currentThread().isInterrupted) return@submit null
                    checkOne(s, keyword, checkContent)
                }
            }
            var done = 0
            for (f in futures) {
                if (isCancelled()) { futures.forEach { it.cancel(true) }; break }
                val r = runCatching { f.get() }.getOrNull() ?: continue
                results += r
                done++
                onProgress(done, sources.size, r)
            }
        } finally {
            pool.shutdownNow()
        }
        return results
    }

    @Synchronized private fun persistChecks() {
        val target = checkFile ?: return
        runCatching {
            target.parentFile?.mkdirs()
            val obj = JSONObject()
            for ((k, v) in checkHistory) {
                obj.put(k, JSONObject().put("name", v.sourceName).put("status", v.status.name).put("latencyMs", v.latencyMs).put("detail", v.detail).put("checkedAt", v.checkedAt))
            }
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(obj.toString())
            java.nio.file.Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
