package app.iduanju.novel

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import app.iduanju.ui.components.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iduanju.core.novel.*
import app.iduanju.ui.theme.IdjThemeColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Imported sources and reading progress are private to this installation. */
@Composable
fun NovelScreen(onBack: () -> Unit, embedded: Boolean = false, onFullScreenChange: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("novel", 0) }
    // Small collections are parsed in place; a big one (megabytes of JSON) off the main thread, see [awaitSources].
    val rawSources = remember { prefs.getString("sources", "[]")!! }
    val knownSources = remember { SourceMemo.peek(rawSources) ?: if (rawSources.length < 64_000) SourceMemo.parse(rawSources) else null }
    val sources = remember { mutableStateListOf<NovelSource>().apply { knownSources?.let(::addAll) } }
    var sourcesReady by remember { mutableStateOf(knownSources != null) }
    val savedRank = remember { prefs.getString("rankSource", null) }
    fun pickSource() = savedRank?.takeIf { id -> sources.any { it.id == id } } ?: sources.firstOrNull()?.id ?: ""
    var selected by remember { mutableStateOf(pickSource()) }
    LaunchedEffect(Unit) {
        if (!sourcesReady) {
            val parsed = withContext(Dispatchers.Default) { SourceMemo.parse(rawSources) }
            sources.addAll(parsed); selected = pickSource(); sourcesReady = true
        }
    }
    suspend fun awaitSources() { if (!sourcesReady) snapshotFlow { sourcesReady }.first { it } }
    var continuingFromShelf by remember { mutableStateOf(false) }
    var bookSource by remember { mutableStateOf("") }
    val searchResults = remember { NovelSearchResults() }
    val searchList = rememberLazyListState()
    val rankList = rememberLazyListState()
    var query by remember { mutableStateOf("") }
    val importText = remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(app.iduanju.BuildConfig.DEBUG && app.iduanju.ui.UiEvidence.page=="import") }
    var hits by remember { mutableStateOf<List<NovelHit>>(emptyList()) }
    var categories by remember { mutableStateOf<List<NovelCategory>>(emptyList()) }
    var category by remember { mutableStateOf<NovelCategory?>(null) }
    var page by remember { mutableIntStateOf(1) }
    var more by remember { mutableStateOf(false) }
    var chapters by remember { mutableStateOf<List<NovelChapter>>(emptyList()) }
    var bookDetails by remember { mutableStateOf<NovelBookDetails?>(null) }
    var shelfRevision by remember { mutableIntStateOf(0) }
    var currentBook by remember { mutableStateOf<NovelHit?>(null) }
    var chapterIndex by remember { mutableIntStateOf(-1) }
    var text by remember { mutableStateOf("") }
    var localText by remember { mutableStateOf("") }
    var localRanges by remember { mutableStateOf<List<NovelParsed.Chapter>>(emptyList()) }
    var localTitle by remember { mutableStateOf("本地小说") }
    var localAuthor by remember { mutableStateOf("") }
    var localBook by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var downloadBusy by remember { mutableStateOf(false) }
    var downloadDone by remember { mutableIntStateOf(0) }
    var downloadTotal by remember { mutableIntStateOf(0) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var cachedCount by remember { mutableIntStateOf(0) }
    var prefetch by remember { mutableIntStateOf(prefs.getInt("prefetch",3)) }
    var showDownload by remember { mutableStateOf(false) }
    var showSwitch by remember { mutableStateOf(false) }
    var alternatives by remember { mutableStateOf<List<Pair<NovelSource,NovelHit>>>(emptyList()) }
    var switchBusy by remember { mutableStateOf(false) }
    var switchJob by remember { mutableStateOf<Job?>(null) }
    var readGeneration by remember { mutableIntStateOf(0) }
    var readJob by remember { mutableStateOf<Job?>(null) }
    var readerOpen by remember { mutableStateOf(false) }
    var rankGeneration by remember { mutableIntStateOf(0) }
    var rankJob by remember { mutableStateOf<Job?>(null) }
    var prefetchJob by remember { mutableStateOf<Job?>(null) }
    var selectedGroup by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(app.iduanju.BuildConfig.DEBUG && app.iduanju.ui.UiEvidence.page=="search") }
    var shelf by remember { mutableStateOf(!(app.iduanju.BuildConfig.DEBUG && app.iduanju.ui.UiEvidence.page=="rank")) }
    var sourcePage by remember { mutableStateOf(app.iduanju.BuildConfig.DEBUG && app.iduanju.ui.UiEvidence.page=="sources") }
    var loginSource by remember { mutableStateOf<NovelSource?>(null) }
    var debugSource by remember { mutableStateOf<NovelSource?>(null) }
    var editSource by remember { mutableStateOf<NovelSource?>(null) }
    var showBatchCheck by remember { mutableStateOf(false) }
    var showReplaceRules by remember { mutableStateOf(false) }
    var importResult by remember { mutableStateOf<String?>(null) }
    var requestFilePicker by remember { mutableStateOf(false) }
    val cacheRoot = remember { File(context.filesDir,"novel-cache-v2") }
    val cache = remember { NovelCache(cacheRoot) }
    var font by remember { mutableFloatStateOf(prefs.getFloat("font", 20f)) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val readerState = rememberLazyListState()
    val sourceState = remember { derivedStateOf { val id = if (currentBook == null) selected else bookSource; sources.firstOrNull { it.id == id } } }
    val source = sourceState.value
    val enabledCount by remember { derivedStateOf { sources.count { it.enabled } } }
    fun loadShelf(ranges: List<NovelParsed.Chapter>): List<ShelfEntry> {
        val last = runCatching { JSONObject(prefs.getString("last", "{}")!!) }.getOrDefault(JSONObject())
        val lastSrc = last.optString("source")
        val lastUrl = last.optString("url")
        val lastCh = last.optInt("chapter", -1)
        val out = mutableListOf<ShelfEntry>()
        if (File(context.filesDir, "novel-local.txt").exists()) {
            val name = prefs.getString("localName", "本地小说") ?: "本地小说"
            val author = prefs.getString("localAuthor", "") ?: ""
            val ch = if (lastSrc == "local") lastCh else -1
            out += ShelfEntry("local", NovelHit(name, author, "local", kind = "TXT 文件"), ranges.size, ch, ranges.getOrNull(ch)?.title.orEmpty(), isLocal = true)
        }
        ShelfMemo.books(cache, cacheRoot).sortedByDescending { it.addedAt }.forEach { saved ->
            val isLast = saved.source == lastSrc && saved.book.url == lastUrl
            val ch = if (isLast && lastCh in saved.chapters.indices) lastCh else -1
            val mergedBook = saved.details?.let { d ->
                saved.book.copy(
                    cover = d.cover.ifBlank { saved.book.cover },
                    author = d.author.ifBlank { saved.book.author },
                    kind = d.kind.ifBlank { saved.book.kind },
                    latest = d.latest.ifBlank { saved.book.latest },
                )
            } ?: saved.book
            out += ShelfEntry(saved.source, mergedBook, saved.chapters.size, ch, saved.chapters.getOrNull(ch)?.title.orEmpty(), isLocal = false, addedAt = saved.addedAt)
        }
        return out.sortedByDescending { it.currentChapter >= 0 }
    }
    // Disk and JSON: the first frame only for a small shelf, every later refresh on IO while the old shelf stays up.
    val shelfHeavy = remember { ShelfMemo.heavy(cacheRoot) }
    var shelfEntries by remember { mutableStateOf(if (shelfHeavy) emptyList() else loadShelf(localRanges)) }
    var shelfLoading by remember { mutableStateOf(shelfHeavy) }
    val shelfVisible = shelf && !searching && !sourcePage && currentBook == null && text.isEmpty()
    LaunchedEffect(shelfVisible, shelfRevision, localRanges) {
        val ranges = localRanges
        shelfEntries = withContext(Dispatchers.IO) { loadShelf(ranges) }
        shelfLoading = false
    }
    fun submitSearch() {
        keyboard?.hide()
        focus.clearFocus()
        searchList.requestScrollToItem(0)
        searchResults.submit(scope, sources.toList(), query)
    }
    val fullScreen = searching || !shelf || sourcePage || currentBook != null || readerOpen || text.isNotEmpty()
    LaunchedEffect(fullScreen) { if (embedded) onFullScreenChange(fullScreen) }
    DisposableEffect(Unit) { onDispose { if (embedded) onFullScreenChange(false) } }
    fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "操作失败" }
            finally { busy = false }
        }
    }
    // Chapter jumps always preempt any slow in-flight chapter load instead of silently dropping clicks.
    fun launchRead(block: suspend () -> Unit) {
        readJob?.cancel()
        val token = ++readGeneration
        busy = true; error = null
        readJob = scope.launch {
            try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { if (token == readGeneration) error = e.message ?: "章节读取失败" }
            finally { if (token == readGeneration) busy = false }
        }
    }
    // Rank/search jobs are cancellable and generation-scoped. An old source response (including
    // A → B → A switches) must not replace the newest filter selection or clear its busy state.
    fun rankWork(block:suspend (Int)->Unit) {
        rankJob?.cancel();val token=++rankGeneration;busy=true;error=null
        rankJob=scope.launch {
            try{block(token)}catch(e:CancellationException){throw e}catch(e:Exception){if(token==rankGeneration)error=e.message?:"操作失败"}
            finally{if(token==rankGeneration)busy=false}
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            rankGeneration++
            readGeneration++
            readJob?.cancel()
            rankJob?.cancel()
            switchJob?.cancel()
            prefetchJob?.cancel()
            searchResults.clear()
        }
    }
    fun saveSources(): Job? = if (sourcesReady) SourceMemo.save(prefs, sources.toList()) else null
    suspend fun addSources(raw: String) {
        awaitSources()
        val report = withContext(Dispatchers.Default) { NovelSources.importReport(raw) }
        val parsed = report.sources
        require(parsed.isNotEmpty()) { if(report.unsupported>0)report.message else "没有可用的 Legado 文本/媒体/RSS 源" }
        val replaced = parsed.mapTo(HashSet()) { it.id }
        sources.removeAll { it.id in replaced }; sources.addAll(parsed)
        saveSources()?.join(); selected = parsed.first().id; importResult = report.message
    }
    suspend fun openLocal(bytes: ByteArray, fileName: String) {
        val parsed = withContext(Dispatchers.IO) { NovelImport.parse(bytes, fileName) }
        withContext(Dispatchers.IO) { File(context.filesDir, "novel-local.txt").writeText(parsed.text) }
        localText = parsed.text; localRanges = parsed.chapters; localTitle = parsed.title; localAuthor = parsed.author.orEmpty()
        prefs.edit().putString("localName", localTitle).putString("localAuthor", localAuthor).apply()
        chapters = parsed.chapters.mapIndexed { i, c -> NovelChapter(c.title, "local:$i", i) }
        chapterIndex = 0
        text = parsed.text.substring(parsed.chapters.first().start, parsed.chapters.first().end).ifBlank { "${chapters.firstOrNull()?.title.orEmpty()}\n（本章暂无内容）" }
        localBook = true; currentBook = null; readerOpen = true
        error = null; importResult = "已导入《${parsed.title}》· ${chapters.size} 章"
    }
    suspend fun read(index: Int, selectedSource: NovelSource? = source, bookToSave: NovelHit? = currentBook) {
        if (localBook) {
            val range = localRanges.getOrNull(index) ?: return
            chapterIndex = index
            text = localText.substring(range.start, range.end).ifBlank { "${chapters.getOrNull(index)?.title.orEmpty()}\n（本章暂无内容）" }
            readerOpen = true
            readerState.requestScrollToItem(0)
            return
        }
        awaitSources()
        val s = selectedSource ?: sourceState.value ?: return
        val chapter = chapters.getOrNull(index) ?: return
        val book = bookToSave ?: return
        val token = readGeneration
        prefetchJob?.cancel()
        if(s.isMedia){
            val toc=chapters.toList()
            val resolve={i:Int->LegadoMedia.resolve(s,toc[i].copy(index=i),book.url,toc.getOrNull(i+1)?.url)}
            val resolved=kotlinx.coroutines.runInterruptible(Dispatchers.IO){resolve(index)}
            // NovelPlayback drives ExoPlayer, which only accepts calls on the main thread.
            withContext(Dispatchers.Main.immediate){if(token==readGeneration){
                NovelPlayback.start(context,NovelPlayback.Queue("${s.id}|${book.url}",book.name,s.json.optInt("bookSourceType"),toc.map{it.title},book.cover,resolve),index,resolved)
                chapterIndex=index;localBook=false
            }}
            return
        }
        val job=currentCoroutineContext()
        val toc=chapters.toList()
        val body=withContext(Dispatchers.IO){
            cache.load(s.id,book.url,chapter.url,checkActive={job.ensureActive();if(token!=readGeneration)throw CancellationException("Reading session changed")}) {
                NovelSources.content(s,chapter,toc.getOrNull(index+1)?.url,book.url)
            }
        }
        if (token != readGeneration) return
        text = body.ifBlank { "${chapter.title}\n（本章暂无内容）" }; chapterIndex = index; localBook = false; readerOpen = true
        bookToSave?.let { book ->
            prefs.edit().putString("last", JSONObject().put("source", s.id).put("name", book.name).put("author", book.author).put("url", book.url).put("chapter", index).toString()).apply()
        }
        // Chapter replacement and scroll reset must share the NEXT layout pass.
        // scrollToItem forces an immediate remeasure of a changing reader tree.
        readerState.requestScrollToItem(0)
        cachedCount = withContext(Dispatchers.IO) { cache.count(s.id,book.url,toc) }
        if (prefetch > 0) prefetchJob = scope.launch(Dispatchers.IO) {
            val speculative=currentCoroutineContext()
            for(i in NovelPrefetchPlan.indices(index,toc.size,prefetch)) {
                speculative.ensureActive()
                try {
                    cache.load(s.id,book.url,toc[i].url,checkActive={speculative.ensureActive()}) {
                        NovelSources.content(s,toc[i],toc.getOrNull(i+1)?.url,book.url)
                    }
                } catch(e: CancellationException){throw e} catch(_:Exception){ /* one failed neighbor must not starve the rest */ }
            }
        }
    }
    fun search(reset: Boolean, cat: NovelCategory? = null) {
        if(searching){keyboard?.hide();focus.clearFocus()}
        rankWork { token ->
        val s = source ?: error("请先导入并选择书源")
        val nextPage = if (reset) 1 else page
        val chosen = if (reset) cat else category
        if (reset) { rankList.requestScrollToItem(0); hits = emptyList(); more = false; category = chosen }
        val result = withContext(Dispatchers.IO) { NovelSources.search(s, query, nextPage, chosen) }
        if (selected != s.id || token != rankGeneration) return@rankWork
        val existing = if (reset) emptyList() else hits
        val seen = existing.mapTo(HashSet()) { it.url }
        val fresh = result.filter { seen.add(it.url) }
        hits = existing + fresh; page = nextPage + 1; more = fresh.isNotEmpty(); category = chosen
    }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) work {
            val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { stream ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val n = stream.read(buffer); if (n < 0) break; require(out.size() + n <= 20 * 1024 * 1024) { "文件超过 20 MB" }; out.write(buffer, 0, n) }
                out.toByteArray()
            } ?: error("无法读取文件") }
            val name = withContext(Dispatchers.IO) { context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else "本地小说.txt" } ?: "本地小说.txt" }
            val probe = bytes.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
            if (probe.startsWith('[') || probe.startsWith('{') || name.endsWith(".js", true) || LegadoJsSource.looksLikeJsSource(probe)) addSources(NovelImport.decode(bytes)) else openLocal(bytes, name)
        }
    }
    LaunchedEffect(importing, requestFilePicker) {
        if (!importing && requestFilePicker) {
            kotlinx.coroutines.delay(350)
            requestFilePicker = false
            filePicker.launch(arrayOf("*/*"))
        }
    }
    fun filterAction(control:NovelCategory,value:String?) {
        rankWork { token ->
            val sc=source?:return@rankWork
            val previous=category
            hits=emptyList();more=false;page=1
            val refreshed=withContext(Dispatchers.IO){NovelSources.categoryAction(sc,control,value);NovelSources.categories(sc)}
            if(selected!=sc.id || token!=rankGeneration)return@rankWork
            categories=refreshed
            val chosen=NovelSources.refreshedCategory(previous,refreshed,selectedGroup)
            category=chosen
            if(chosen!=null){
                selectedGroup=chosen.group
                val result=withContext(Dispatchers.IO){NovelSources.search(sc,"",1,chosen)}
                if(selected!=sc.id || token!=rankGeneration)return@rankWork
                hits=result.distinctBy{it.url};page=2;more=result.isNotEmpty()
            }
        }
    }
    LaunchedEffect(selected,shelf,sourcesReady) {
        if(!sourcesReady)return@LaunchedEffect
        rankJob?.cancel();val token=++rankGeneration
        prefs.edit().putString("rankSource", selected).apply()
        hits=emptyList();categories=emptyList();category=null;more=false;page=1;error=null;busy=false
        val sc=sources.firstOrNull { it.id == selected }
        // Match iOS: entering discovery and changing source opens its first real URL category.
        // Previously Android stopped after loading category buttons and left a blank results panel.
        if(sc!=null&&!shelf){
            busy=true
            try{
                val loaded=withContext(Dispatchers.IO){NovelSources.categories(sc)}
                if(token!=rankGeneration||selected!=sc.id)return@LaunchedEffect
                categories=loaded
                val first=loaded.firstOrNull{!it.isControl&&it.url.isNotBlank()}
                selectedGroup=first?.group?:loaded.firstOrNull()?.group.orEmpty();category=first
                if(first!=null){
                    val result=withContext(Dispatchers.IO){NovelSources.search(sc,"",1,first)}
                    if(token!=rankGeneration||selected!=sc.id)return@LaunchedEffect
                    hits=result.distinctBy{it.url};page=2;more=result.isNotEmpty()
                }
            }catch(e:CancellationException){throw e}catch(e:Exception){if(token==rankGeneration)error=e.message}
            finally{if(token==rankGeneration)busy=false}
        }
    }
    fun download(count: Int?) {
        val book = currentBook ?: return; val s = sourceState.value ?: return; val toc = chapters.toList();val metadata=bookDetails
        if(downloadBusy)return
        val start=if(count==null)0 else chapterIndex.coerceAtLeast(0)
        val end=if(count==null)toc.size else minOf(toc.size,start+count)
        downloadBusy=true;downloadDone=0;downloadTotal=end-start
        downloadJob=scope.launch {
            try {
                withContext(Dispatchers.IO) { cache.saveBook(s.id,book,toc,metadata) }
                val batchSize = if (s.supportsContentBatch) s.maxBatchSize.coerceAtLeast(2) else 1
                var i = start
                while (i < end) {
                    if (batchSize > 1) {
                        val chunkEnd = minOf(end, i + batchSize)
                        val chunk = toc.subList(i, chunkEnd)
                        val batchMap = runCatching { withContext(Dispatchers.IO) { NovelSources.contentBatch(s, chunk, book.url, book.name) } }.getOrDefault(emptyMap())
                        for (j in i until chunkEnd) {
                            withContext(Dispatchers.IO) {
                                val active = currentCoroutineContext()
                                cache.load(s.id, book.url, toc[j].url, checkActive = { active.ensureActive() }) {
                                    batchMap[toc[j].url] ?: NovelSources.content(s, toc[j], toc.getOrNull(j + 1)?.url, book.url, book.name)
                                }
                            }
                            downloadDone++
                        }
                        if (currentBook?.url == book.url && bookSource == s.id) cachedCount = withContext(Dispatchers.IO) { cache.count(s.id, book.url, toc) }
                        i = chunkEnd
                    } else {
                        withContext(Dispatchers.IO) {
                            val active = currentCoroutineContext()
                            cache.load(s.id, book.url, toc[i].url, checkActive = { active.ensureActive() }) { NovelSources.content(s, toc[i], toc.getOrNull(i + 1)?.url, book.url, book.name) }
                        }
                        downloadDone++
                        if (currentBook?.url == book.url && bookSource == s.id) cachedCount = withContext(Dispatchers.IO) { cache.count(s.id, book.url, toc) }
                        i++
                    }
                }
            } catch(e:CancellationException){error="下载已暂停，可继续";throw e} catch(e:Exception){error="下载中断：${e.message}，已完成章节保留"}
            finally { downloadBusy=false }
        }
    }
    fun findSources() {
        val target=currentBook ?: return
        val selectedSource = bookSource
        val known = searchResults.groups.firstOrNull { g -> g.editions.any { it.source.id == selectedSource && it.book.url == target.url } }?.editions.orEmpty().filter { it.source.id != selectedSource }
        val candidates = sources.filter { it.enabled && it.id != selectedSource && (it.isJsSource || it.json.optString("searchUrl").isNotBlank()) }.toList()
        showSwitch=true
        if(switchBusy)return
        switchBusy=true;alternatives=known.map { it.source to it.book }
        switchJob=scope.launch(Dispatchers.Default) {
            try {
                val accLock = Any()
                val acc = LinkedHashMap<String, Pair<NovelSource, NovelHit>>()
                known.forEach { acc[it.source.id + "|" + it.book.url] = it.source to it.book }
                val queue = kotlinx.coroutines.channels.Channel<NovelSource>(kotlinx.coroutines.channels.Channel.UNLIMITED)
                candidates.forEach { queue.trySend(it) }
                queue.close()
                val dirty = java.util.concurrent.atomic.AtomicBoolean(false)
                val flusher = launch {
                    while (isActive) {
                        kotlinx.coroutines.delay(250L)
                        if (dirty.getAndSet(false)) {
                            val snap = synchronized(accLock) { acc.values.toList() }
                            withContext(Dispatchers.Main.immediate) { alternatives = snap }
                        }
                    }
                }
                try {
                    val workers = List(minOf(4, candidates.size.coerceAtLeast(1))) {
                        launch(Dispatchers.IO) {
                            for (s in queue) {
                                ensureActive()
                                val found = try {
                                    withTimeoutOrNull(10_000L) {
                                        runInterruptible(Dispatchers.IO) { NovelSources.search(s, target.name, batchMode = true) }
                                    }.orEmpty()
                                } catch (e: CancellationException) { throw e } catch (_: Throwable) { emptyList() }
                                val matches = found.filter { it.name == target.name && (it.author == target.author || it.author.isBlank() || target.author.isBlank()) }
                                if (matches.isNotEmpty()) {
                                    synchronized(accLock) {
                                        matches.forEach { acc.putIfAbsent(s.id + "|" + it.url, s to it) }
                                        dirty.set(true)
                                    }
                                }
                            }
                        }
                    }
                    workers.forEach { it.join() }
                } finally {
                    flusher.cancel()
                    val finalSnap = synchronized(accLock) { acc.values.toList() }
                    withContext(NonCancellable + Dispatchers.Main.immediate) { alternatives = finalSnap }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) { switchBusy = false }
            }
        }
    }
    fun openResult(id: String, book: NovelHit) {
        continuingFromShelf = false
        bookSource = id
        currentBook = book
        val saved = ShelfMemo.books(cache, cacheRoot).firstOrNull { it.source == id && it.book.url == book.url }
        val toc = saved?.chapters.orEmpty()
        bookDetails = saved?.details
        chapters = toc
        cachedCount = 0
        if (toc.isNotEmpty()) scope.launch {
            val n = withContext(Dispatchers.IO) { cache.count(id, book.url, toc) }
            if (bookSource == id && currentBook?.url == book.url) cachedCount = n
        }
        error = null
    }
    fun back() {
        readGeneration++
        readJob?.cancel()
        prefetchJob?.cancel()
        when {
            readerOpen || text.isNotEmpty() -> {
                readerOpen = false
                text = ""
                if (continuingFromShelf) { continuingFromShelf = false; currentBook = null }
            }
            currentBook != null -> { currentBook = null; chapters = emptyList(); error = null }
            sourcePage -> sourcePage = false
            searching -> { searching = false; searchResults.clear(); query = "" }
            !shelf -> shelf = true
            else -> onBack()
        }
    }
    androidx.activity.compose.BackHandler(enabled=!embedded || !shelf || sourcePage || searching || currentBook!=null || readerOpen || text.isNotEmpty()) { back() }
    NovelPlayback.state?.let { active ->
        NovelMediaPlayer(active) {
            if (active.queue.key == "${source?.id}|${currentBook?.url}") chapterIndex = active.index
            readGeneration++; NovelPlayback.stop()
            if (continuingFromShelf) { continuingFromShelf = false; currentBook = null }
        }
        return
    }
    val c=IdjThemeColors
    if (!sourcePage && (readerOpen || text.isNotEmpty())) {
        val book=currentBook
        val id=if(localBook)"local|$localTitle|${localText.length}|${localText.hashCode()}" else source?.id.orEmpty()+"|"+(book?.url?:"local")
        NovelReaderScreen(id,if(localBook)localTitle else book?.name?:"本地小说",if(localBook)localAuthor else book?.author.orEmpty(),book?.cover.orEmpty(),if (localBook) "本地文件" else source?.name.orEmpty(),chapters,chapterIndex,text,busy,error,cachedCount,
            addedAt=remember(id,shelfEntries){shelfEntries.firstOrNull{!it.isLocal&&it.sourceId==source?.id&&it.book.url==book?.url}?.addedAt?:0},
            initialOffset=prefs.getInt("reader.offset.$id.${chapterIndex.coerceAtLeast(0)}",0),
            source=if(localBook) null else source,
            bookUrl=book?.url.orEmpty(),
            onBack={back()},onChapter={i->launchRead{read(i)}},
            onDownload={i->work{if(i!=chapterIndex)read(i);showDownload=true}},onSwitch={i->work{if(i!=chapterIndex)read(i);findSources()}},
            onPosition={i,offset->prefs.edit().putInt("reader.offset.$id.$i",offset).apply()
                if(localBook)prefs.edit().putString("last",JSONObject().put("source","local").put("name",localTitle).put("url","local").put("chapter",i).toString()).apply()
                else if(book!=null&&source!=null)prefs.edit().putString("last",JSONObject().put("source",source.id).put("name",book.name).put("author",book.author).put("url",book.url).put("chapter",i).toString()).apply()},
            onSearch={q->if(localBook){val query=q.trim();val ranges=localRanges;val whole=localText;if(query.isEmpty())emptyList() else withContext(Dispatchers.Default){ranges.mapIndexedNotNull{index,range->val content=whole.substring(range.start,range.end);val at=content.indexOf(query,ignoreCase=true);if(at<0)null else NovelCache.SearchHit(index,at,content.substring((at-16).coerceAtLeast(0)).take(60).replace('\n',' '))}}}else{val sc=source?:error("书源不存在");val b=book?:error("书籍不存在");val toc=chapters.toList();withContext(Dispatchers.IO){cache.search(sc.id,b.url,toc,q)}}},
            onLoadChapter={i->
                if (localBook) {
                    val range=localRanges.getOrNull(i)?:error("章节不存在")
                    localText.substring(range.start,range.end)
                } else {
                    val sc=source?:error("书源不存在");val b=book?:error("书籍不存在");val toc=chapters.toList();val ch=toc.getOrNull(i)?:error("章节不存在");val active=currentCoroutineContext()
                    withContext(Dispatchers.IO){cache.load(sc.id,b.url,ch.url,{active.ensureActive()}){NovelSources.content(sc,ch,toc.getOrNull(i+1)?.url,b.url)}}
                }
            },
            onPrefetch={i->val sc=source;val b=book;val toc=chapters.toList();prefetchJob?.cancel()
                if(!localBook&&sc!=null&&b!=null&&prefetch>0)prefetchJob=scope.launch(Dispatchers.IO){val active=currentCoroutineContext();for(n in NovelPrefetchPlan.indices(i,toc.size,prefetch)){active.ensureActive();try{cache.load(sc.id,b.url,toc[n].url,{active.ensureActive()}){NovelSources.content(sc,toc[n],toc.getOrNull(n+1)?.url,b.url)}}catch(e:CancellationException){throw e}catch(_:Exception){}}}}
        )
    }
    if (!sourcePage && currentBook != null && !readerOpen && text.isEmpty()) {
        val book = currentBook!!
        var attempt by remember(book.url, bookSource) { mutableIntStateOf(0) }
        var fetching by remember(book.url, bookSource) { mutableStateOf(bookDetails == null) }
        LaunchedEffect(book.url, bookSource, attempt) {
            val id = bookSource
            fetching = true
            error = null
            try {
                awaitSources()
                val sc = sources.firstOrNull { it.id == id } ?: error("书源已删除")
                val details = withContext(Dispatchers.IO) { NovelSources.details(sc, book.url) }
                bookDetails = details
                if (details.chapters.isNotEmpty()) {
                    chapters = details.chapters
                    withContext(Dispatchers.IO) {
                        if (ShelfMemo.books(cache, cacheRoot).any { it.source == id && it.book.url == book.url }) cache.saveBook(id, book, details.chapters, details)
                    }
                } else if (chapters.isEmpty()) error = "这本书没有可读目录，可切换书源后重试。"
                cachedCount = withContext(Dispatchers.IO) { cache.count(id, book.url, chapters) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "详情加载失败" }
            finally { fetching = false }
        }
        val knownSourceCount = remember(searchResults.groups, alternatives, book.url, bookSource) {
            val fromSearch = searchResults.groups.firstOrNull { g ->
                g.editions.any { it.source.id == bookSource && it.book.url == book.url }
            }?.editions?.size ?: 1
            maxOf(fromSearch, alternatives.size + 1, 1)
        }
        NovelBookDetail(
            book = book,
            info = bookDetails,
            sourceName = source?.name.orEmpty(),
            chapters = chapters,
            loading = fetching || busy,
            error = error,
            onShelf = remember(shelfEntries, book.url, bookSource) { shelfEntries.any { !it.isLocal && it.sourceId == bookSource && it.book.url == book.url } },
            downloading = downloadBusy,
            downloadProgress = if (downloadTotal == 0) 0f else downloadDone.toFloat() / downloadTotal,
            cachedCount = cachedCount,
            sourceCount = knownSourceCount,
            onBack = { back() },
            onSwitch = { findSources() },
            onAdd = { scope.launch { withContext(Dispatchers.IO) { cache.saveBook(bookSource, book, chapters, bookDetails) }; shelfRevision++ } },
            onDownload = {
                if (source?.isMedia == true) error = "媒体离线下载尚未实现，请使用原生播放"
                else scope.launch { withContext(Dispatchers.IO) { cache.saveBook(bookSource, book, chapters, bookDetails) }; shelfRevision++; showDownload = true }
            },
            onRead = { requestedChapter ->
                launchRead {
                    withContext(Dispatchers.IO) { cache.saveBook(bookSource, book, chapters, bookDetails) }; shelfRevision++
                    val last = runCatching { JSONObject(prefs.getString("last", "{}")!!) }.getOrDefault(JSONObject())
                    val index = if (last.optString("source") == bookSource && last.optString("url") == book.url) last.optInt("chapter", 0).coerceIn(0, chapters.lastIndex) else 0
                    read(requestedChapter ?: index)
                }
            },
            onRetry = { attempt++ },
            mediaType = source?.json?.optInt("bookSourceType") ?: 0,
        )
    }
    if (!sourcePage && currentBook == null && !readerOpen && text.isEmpty()) Column(
        Modifier
            .fillMaxSize()
            .then(if (embedded && shelf && !searching) Modifier else Modifier.dsAmbient(cool = !shelf || searching))
            .then(if (!embedded && shelf && !searching) Modifier.statusBarsPadding() else Modifier)
    ) {
        val list = if (searching) searchList else rankList
        val pinned by remember(list) { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0 } }
        if (shelf && !searching) {
            error?.let { Text(it, color = c.danger, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            fun openLocalFile() = work {
                val bytes = withContext(Dispatchers.IO) { File(context.filesDir, "novel-local.txt").readBytes() }
                openLocal(bytes, (prefs.getString("localName", "本地小说") ?: "本地小说") + ".txt")
            }
            NovelShelf(
                entries = shelfEntries,
                loading = shelfLoading,
                onContinue = { entry ->
                    if (entry.isLocal) {
                        openLocalFile()
                    } else {
                        openResult(entry.sourceId, entry.book)
                        if (chapters.isNotEmpty()) launchRead {
                            awaitSources()
                            val sc = sources.firstOrNull { it.id == entry.sourceId } ?: return@launchRead
                            val last = runCatching { JSONObject(prefs.getString("last", "{}")!!) }.getOrDefault(JSONObject())
                            val index = if (last.optString("source") == entry.sourceId && last.optString("url") == entry.book.url) last.optInt("chapter") else 0
                            read(index.coerceIn(chapters.indices), sc, entry.book)
                            continuingFromShelf = true
                        }
                    }
                },
                onBook = { entry -> if (entry.isLocal) openLocalFile() else openResult(entry.sourceId, entry.book) },
                onDelete = { targets ->
                    val gone = targets.mapTo(HashSet()) { it.key }
                    shelfEntries = shelfEntries.filterNot { it.key in gone }
                    scope.launch {
                        // A cached book is one file per chapter.
                        withContext(NonCancellable + Dispatchers.IO) {
                            val last = runCatching { JSONObject(prefs.getString("last", "{}")!!) }.getOrDefault(JSONObject())
                            val lastSrc = last.optString("source")
                            val lastUrl = last.optString("url")
                            var clearLast = false
                            for (entry in targets) {
                                if (entry.isLocal) {
                                    File(context.filesDir, "novel-local.txt").delete()
                                    prefs.edit().remove("localName").remove("localAuthor").apply()
                                    if (lastSrc == "local") clearLast = true
                                } else {
                                    cache.removeBook(entry.sourceId, entry.book.url)
                                    if (lastSrc == entry.sourceId && lastUrl == entry.book.url) clearLast = true
                                }
                            }
                            if (clearLast) prefs.edit().remove("last").apply()
                        }
                        shelfRevision++
                    }
                },
                onImport = { importResult = null; importing = true },
                onSearch = { searching = true },
                onRank = { shelf = false; searching = false; hits = emptyList() },
                onSources = { sourcePage = true },
                embedded = embedded,
            )
        } else if (searching) {
            NovelSearchPage(
                query = { query },
                onQuery = { query = it },
                results = searchResults,
                list = searchList,
                pinned = pinned,
                enabledSources = enabledCount,
                error = error,
                onSubmit = ::submitSearch,
                onBack = { back() },
                onSources = { sourcePage = true },
                onOpen = ::openResult,
            )
        } else {
            var expanded by remember { mutableStateOf(false) }
            val groups = remember(categories) { categories.map { it.group }.distinct() }
            val groupCats = remember(categories, selectedGroup) { categories.filter { it.group == selectedGroup && !it.isControl } }
            val groupControls = remember(categories, selectedGroup) { categories.filter { it.group == selectedGroup && it.isControl } }
            val rest = remember(hits) { if (hits.size >= 3) hits.drop(3) else hits }

            // Compact single-row rank header: Back + 小说榜单 + Source picker capsule + Search
            Row(
                Modifier.fillMaxWidth().dsPinned(pinned).statusBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BackButton { back() }
                Text("小说榜单", color = c.text, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                Spacer(Modifier.weight(1f))
                Box {
                    CapsuleButton(source?.name ?: "选择书源", trailing = Icons.Rounded.ExpandMore) { expanded = true }
                    SourcePicker(expanded, sources, enabledCount, selected, { expanded = false }) { selected = it; expanded = false }
                }
                RoundIconButton(Icons.Rounded.Search, "搜书") { searching = true }
            }
            error?.let { Text(it, color = c.danger, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }

            // Full-width 2-tier horizontal navigation (no cramped 86dp left rail)
            if (groups.size > 1) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(top = 6.dp, bottom = 4.dp)) {
                    UnderlineTabs(groups, selectedGroup, { it }, small = true, onSelect = { group ->
                        if (!busy) {
                            selectedGroup = group
                            val first = categories.firstOrNull { it.group == group && !it.isControl && it.url.isNotBlank() }
                            if (first != null) search(true, first) else { hits = emptyList(); category = null; more = false }
                        }
                    })
                }
                if (groupCats.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        groupCats.forEach { cat ->
                            ToneChip(cat.title, cat == category, small = true, enabled = !busy) { search(true, cat) }
                        }
                    }
                }
            } else if (groupCats.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(top = 6.dp, bottom = 8.dp)) {
                    UnderlineTabs(groupCats, category ?: groupCats.first(), { it.title }, small = true, onSelect = { cat ->
                        if (!busy) search(true, cat)
                    })
                }
            }

            NovelExploreControls(groupControls, !busy, ::filterAction)

            // 0-card full-width rankings: Top 3 visual podium + #4+ open hairline list
            LazyColumn(state = rankList, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 36.dp)) {
                if (error != null && category != null && hits.isEmpty()) {
                    item(key = "retry") {
                        Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                            MiniButton("重试加载榜单") { if (!busy) search(true, category) }
                        }
                    }
                }
                if (hits.isEmpty() && !busy && sourcesReady) {
                    item(key = "empty") {
                        EmptyNovel(
                            "书源推荐与榜单",
                            if (source == null) "添加书源后，即可搜索和浏览实时榜单。" else "选择上方榜单分类，查看书源实时推荐。",
                        )
                    }
                } else if (hits.isEmpty()) {
                    items(7, key = { "skeleton$it" }, contentType = { "skeleton" }) { RowSkeleton(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                } else {
                    if (hits.size >= 3) item(key = "podium", contentType = "podium") {
                        NovelRankPodium(hits) { book -> source?.let { openResult(it.id, book) } }
                        Hairline(Modifier.padding(horizontal = 16.dp))
                    }
                    val first = if (hits.size >= 3) 4 else 1
                    itemsIndexed(rest, key = { _, b -> b.url }, contentType = { _, _ -> "book" }) { idx, book ->
                        if (idx > 0) Hairline(Modifier.padding(start = 52.dp, end = 16.dp))
                        NovelRankRow(book, idx + first) { source?.let { openResult(it.id, book) } }
                    }
                }
                if (more) {
                    item(key = "more") {
                        if (error != null) {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                MiniButton("重试加载榜单") { if (!busy) search(false) }
                            }
                        } else {
                            if (busy) GlassLoadingBar(Modifier.padding(horizontal = 16.dp, vertical = 20.dp))
                            LaunchedEffect(page, category?.id, busy) { if (!busy) search(false) }
                        }
                    }
                }
            }
        }
    }
    if(sourcePage)NovelSourcesPage(
        sources.toList(),
        onBack = { sourcePage = false },
        onAdd = { importResult = null; importing = true },
        onToggle = { sc, on ->
            val i = sources.indexOfFirst { it.id == sc.id }
            if (i >= 0) sources[i] = NovelSource(JSONObject(sc.json.toString()).put("enabled", on))
            saveSources()
        },
        onDelete = { sc ->
            sources.removeAll { it.id == sc.id }; saveSources()
            if (selected == sc.id) selected = sources.firstOrNull()?.id.orEmpty()
        },
        loading = !sourcesReady,
        onLogin = { loginSource = it },
        onDebug = { debugSource = it },
        onEdit = { editSource = it },
        onCheckAll = { showBatchCheck = true },
        onReplaceRules = { showReplaceRules = true },
    )
    loginSource?.let { sc -> NovelSourceLoginSheet(sc) { loginSource = null } }
    debugSource?.let { sc -> NovelSourceDebugSheet(sc) { debugSource = null } }
    editSource?.let { sc ->
        NovelSourceEditorSheet(sc, onSave = { updated ->
            val i = sources.indexOfFirst { it.id == sc.id }
            if (i >= 0) sources[i] = updated else sources.add(updated)
            saveSources()
        }) { editSource = null }
    }
    if (showBatchCheck) NovelSourceCheckSheet(
        sources = sources.filter { it.enabled },
        onDisableInvalid = { badIds ->
            for (i in sources.indices) {
                if (sources[i].id in badIds) sources[i] = NovelSource(JSONObject(sources[i].json.toString()).put("enabled", false))
            }
            saveSources()
        },
        onDeleteInvalid = { badIds ->
            sources.removeAll { it.id in badIds }
            saveSources()
            if (selected in badIds) selected = sources.firstOrNull()?.id.orEmpty()
        },
        onDismiss = { showBatchCheck = false },
    )
    if (showReplaceRules) NovelReplaceRulesSheet { showReplaceRules = false }

    if(showDownload) NovelPage("缓存与下载",{showDownload=false}) {
        NovelPanel {
            Text(currentBook?.name.orEmpty(),color=c.text,fontSize=17.sp,fontWeight=FontWeight.SemiBold)
            Text("已缓存 $cachedCount / ${chapters.size} 章",fontSize=13.sp,color=c.secondary)
            if(downloadBusy){
                GlassProgressBar({if(downloadTotal==0)0f else downloadDone.toFloat()/downloadTotal})
                Text("本次 $downloadDone / $downloadTotal",color=c.text,fontSize=13.sp)
                GlassButton("暂停下载",tone=ButtonTone.Glass){downloadJob?.cancel()}
            }
            error?.let {Text(it,fontSize=13.sp,color=c.danger)}
        }
        NovelPanel {
            Text("离线下载",color=c.text,fontSize=17.sp,fontWeight=FontWeight.SemiBold)
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                GlassButton("缓存后 50 章",Modifier.weight(1f),ButtonTone.Tinted,enabled=!downloadBusy){download(50)}
                GlassButton("缓存后 200 章",Modifier.weight(1f),ButtonTone.Tinted,enabled=!downloadBusy){download(200)}
            }
            GlassButton("下载全本 / 继续未完成下载",Modifier.fillMaxWidth(),size=ButtonSize.Large,enabled=!downloadBusy){download(null)}
        }
        NovelPanel {
            Text("提前缓存",color=c.text,fontSize=17.sp,fontWeight=FontWeight.SemiBold)
            GlassSegments(listOf(0,3,10,20).map{n->(if(n==0)"关闭" else "$n 章") to n},prefetch,{n->prefetch=n;prefs.edit().putInt("prefetch",n).apply()})
            Text("阅读时自动预取后续章节，减少翻页等待。",fontSize=12.sp,color=c.secondary)
        }
        Text("保持应用前台下载。已缓存章节可断网阅读，暂停或中断后可继续，已完成章节不会重复请求。",fontSize=12.sp,lineHeight=18.sp,color=c.secondary)
    }
    if(showSwitch)GlassDialog({switchJob?.cancel();showSwitch=false},title="切换书籍来源") {
        Text("切换书籍来源",color=c.text,fontSize=18.sp,lineHeight=24.sp,fontWeight=FontWeight.Bold)
        if(switchBusy||busy)GlassLoadingBar(Modifier.padding(top=12.dp))
        LazyColumn(Modifier.weight(1f,fill=false).fillMaxWidth().padding(top=10.dp)) {
            if(switchBusy)item(key="busy"){Text("正在搜索同名书籍…",color=c.secondary,fontSize=13.sp,modifier=Modifier.padding(horizontal=12.dp,vertical=8.dp))}
            items(alternatives,key={it.first.id+it.second.url},contentType={"source"}){(s,book)->SelectRow(s.name,selected=false){work{
                val oldTitle=chapters.getOrNull(chapterIndex)?.title
                val details=withContext(Dispatchers.IO){NovelSources.details(s,book.url)}
                val toc=details.chapters
                require(toc.isNotEmpty()){"该源目录为空"}
                val match=withContext(Dispatchers.Default){val plain=oldTitle?.replace(Blanks,"");toc.indexOfFirst{it.title.replace(Blanks,"")==plain}}.takeIf{it>=0} ?: chapterIndex.coerceIn(0,toc.lastIndex)
                withContext(Dispatchers.IO){cache.saveBook(s.id,book,toc,details)}
                switchJob?.cancel();bookSource=s.id;currentBook=book;bookDetails=details;chapters=toc;showSwitch=false;if(text.isNotEmpty())read(match,s,book)
            }}}
            if(!switchBusy&&alternatives.isEmpty())item(key="none"){Text("未找到同名同作者书籍，可启用更多书源后重试",color=c.secondary,fontSize=14.sp,lineHeight=20.sp,modifier=Modifier.padding(vertical=8.dp))}
        }
        GlassButton("关闭",Modifier.padding(top=16.dp).fillMaxWidth(),ButtonTone.Glass,shape=RoundedCornerShape(14.dp)){switchJob?.cancel();showSwitch=false}
    }
    if(importing)NovelImportSheet(
        importText,busy,importResult,error,
        onFile={requestFilePicker=true;importing=false},
        onDismiss={importing=false},
    ){work{
        val raw=importText.value.trim()
        val data=if(raw.startsWith("https://")||raw.startsWith("http://"))withContext(Dispatchers.IO){NovelSources.fetch(raw,NovelSource(JSONObject().put("bookSourceUrl",raw)))}else raw
        addSources(data)
    }}
}

private val Blanks=Regex("\\s+")

/** 书源 picker of the rank page: a menu, or a searchable sheet once a collection would make the menu thousands of rows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourcePicker(open: Boolean, sources: List<NovelSource>, enabledCount: Int, selected: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    if (enabledCount <= 24) {
        GlassMenu(open, onDismiss) { sources.filter { it.enabled }.forEach { sc -> GlassMenuItem(sc.name, selected = sc.id == selected) { onPick(sc.id) } } }
        return
    }
    if (!open) return
    val enabled = remember(enabledCount) { sources.filter { it.enabled } }
    var filter by remember { mutableStateOf("") }
    val shown = remember(enabled, filter) { val f = filter.trim(); if (f.isEmpty()) enabled else enabled.filter { it.name.contains(f, ignoreCase = true) } }
    val state = rememberLazyListState((enabled.indexOfFirst { it.id == selected } - 2).coerceAtLeast(0))
    GlassBottomSheet(onDismiss, title = "选择书源", contentPadding = PaddingValues(horizontal = 16.dp)) {
        GlassSearchField(filter, { filter = it }, Modifier.padding(bottom = 8.dp), placeholder = "搜索书源")
        LazyColumn(Modifier.fillMaxWidth().height(androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * 0.58f), state = state) {
            items(shown, key = { it.id }, contentType = { "source" }) { sc -> SelectRow(sc.name, sc.id == selected) { onPick(sc.id) } }
        }
    }
}

/** Own scope: typing recomposes the search page only, never the whole [NovelScreen]. */
@Composable
private fun ColumnScope.NovelSearchPage(
    query: () -> String,
    onQuery: (String) -> Unit,
    results: NovelSearchResults,
    list: LazyListState,
    pinned: Boolean,
    enabledSources: Int,
    error: String?,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    onSources: () -> Unit,
    onOpen: (String, NovelHit) -> Unit,
) {
    val c = IdjThemeColors
    val inFlight = results.running && results.finished < results.total
    Box(Modifier.fillMaxWidth().dsPinned(pinned).statusBarsPadding()) {
        NovelSearchTopBar(
            value = query(),
            enabledSources = enabledSources,
            onValue = onQuery,
            onSubmit = onSubmit,
            onBack = onBack,
            onSources = onSources,
        )
    }
    error?.let { Text(it, color = c.danger, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) }
    if (results.total > 0) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(top = 4.dp, bottom = 2.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (inFlight) Box(Modifier.size(6.dp).clip(CircleShape).background(c.accent))
                Text(
                    if (inFlight) "正在搜索 ${results.finished} / ${results.total} 个书源 · ${results.groups.size} 本"
                    else "共 ${results.groups.size} 本 · 已搜索 ${results.finished}/${results.total} 个书源",
                    color = c.secondary,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.weight(1f))
                if (results.failures > 0) Text("${results.failures} 个失败", color = c.accentWarm, fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                if (inFlight) {
                    Text(
                        "停止",
                        color = c.danger,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(CircleShape).clickable { results.stop() }.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            if (inFlight) {
                Spacer(Modifier.height(7.dp))
                DSProgressBar(results.finished.toFloat() / results.total)
            }
        }
    }
    LazyColumn(state = list, modifier = Modifier.weight(1f).imePadding(), contentPadding = PaddingValues(top = 4.dp, bottom = 36.dp)) {
        if (results.groups.isEmpty()) {
            if (inFlight) items(6, key = { "skeleton$it" }, contentType = { "skeleton" }) { RowSkeleton(Modifier.padding(horizontal = 18.dp, vertical = 5.dp)) }
            else item(key = "empty") {
                EmptyNovel(
                    if (results.total > 0) "没有找到书籍" else "搜索你的下一本书",
                    "输入书名或作者，同时搜索已启用的书源，相同书籍会自动合并。",
                )
            }
        }
        itemsIndexed(results.groups, key = { _, m -> m.first.source.id + "|" + m.first.book.url }, contentType = { i, _ -> if (i == 0) "lead" else "book" }) { index, match ->
            val edition = match.first
            if (index > 0) Hairline(Modifier.padding(start = if (index == 1) 18.dp else 89.dp, end = 18.dp))
            NovelSearchRow(
                book = edition.book,
                query = query(),
                sourceName = edition.source.name,
                sourceCount = match.editions.size,
                lead = index == 0,
            ) { onOpen(edition.source.id, edition.book) }
        }
    }
}

/** One sheet for every import path; results stay in the sheet as a banner (iOS NovelImportSheet). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NovelImportSheet(
    text: MutableState<String>,
    busy: Boolean,
    result: String?,
    error: String?,
    onFile: () -> Unit,
    onDismiss: () -> Unit,
    onImport: () -> Unit,
) {
    val c = IdjThemeColors
    val context = LocalContext.current
    fun paste() {
        val clip = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        text.value = clip.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
    }
    GlassBottomSheet(onDismiss, Modifier.testTag("novel.import.sheet"), contentPadding = PaddingValues(0.dp)) {
        // Inside the sheet's own window: the keyboard of this field, not of the page behind.
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = LocalFocusManager.current
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("导入书籍与书源", color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            RoundIconButton(Icons.Rounded.Close, "关闭", onClick = onDismiss)
        }
        Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            ImportLabel("书籍")
            ImportGroup { ImportRow(Icons.Rounded.MenuBook, "导入本地书籍", "TXT · UTF-8 / GB18030 / UTF-16", onFile) }
            ImportLabel("书源")
            GlassTextField(
                text.value, { text.value = it }, placeholder = "粘贴书源网址或 JSON", singleLine = false, maxLines = 3, minHeight = 48.dp,
                trailing = { MiniButton("粘贴", ::paste) },
            )
            GlassButton(
                if (busy) "正在导入…" else "导入书源", Modifier.padding(top = 10.dp).fillMaxWidth(), size = ButtonSize.Large, icon = Icons.Rounded.Add,
                enabled = busy || text.value.isNotBlank(), loading = busy,
            ) {
                focus.clearFocus()
                keyboard?.hide()
                onImport()
            }
            Spacer(Modifier.height(10.dp))
            ImportGroup {
                ImportRow(Icons.Rounded.ContentPaste, "从剪贴板导入", onClick = ::paste)
                Hairline()
                ImportRow(Icons.Rounded.FolderOpen, "从文件导入书源", onClick = onFile)
            }
            result?.let { ImportBanner(it, ok = true) }
            error?.let { ImportBanner(it, ok = false) }
            Text(
                "支持 Legado 文本、有声、漫画/图文、下载、视频书源、单文件 .js 书源与 RSS 订阅。书源与阅读记录仅保存在当前设备。",
                color = c.tertiary, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun ImportLabel(text: String) =
    Text(text, color = IdjThemeColors.secondary, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp))

@Composable
private fun ImportGroup(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(IdjThemeColors.glass).padding(horizontal = 14.dp), content = content)

@Composable
private fun ImportRow(icon: ImageVector, title: String, note: String? = null, onClick: () -> Unit) {
    val c = IdjThemeColors
    Row(
        Modifier.fillMaxWidth().pressClick(0.98f, onClick = onClick).heightIn(min = 62.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconWell(icon, 40.dp, 12.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (note != null) Text(note, color = c.secondary, fontSize = 12.sp)
        }
        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = c.tertiary)
    }
}

@Composable
private fun ImportBanner(text: String, ok: Boolean) {
    val c = IdjThemeColors
    val ink = if (ok) c.ok else c.accentWarm
    Row(
        Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (ok) c.okSoft else c.warmSoft).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, null, Modifier.size(18.dp), tint = ink)
        Text(text, color = ink, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
    }
}

/**
 * Parsed 书源 of the last stored JSON. A collection can be megabytes and the home pager recreates the novel page on every
 * visit, so it is parsed once, and written back off the main thread in order.
 */
private object SourceMemo {
    private val saver = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private var raw: String? = null
    private var list: List<NovelSource> = emptyList()
    private var pending: List<NovelSource>? = null

    @Synchronized fun peek(text: String): List<NovelSource>? = pending ?: if (text == raw) list else null
    @Synchronized private fun put(text: String, sources: List<NovelSource>) { raw = text; list = sources }
    @Synchronized private fun pend(sources: List<NovelSource>) { pending = sources }
    @Synchronized private fun settle(sources: List<NovelSource>) { if (pending === sources) pending = null }

    fun parse(text: String): List<NovelSource> =
        peek(text) ?: runCatching { NovelSources.import(text) }.getOrDefault(emptyList()).also { put(text, it) }

    fun save(prefs: android.content.SharedPreferences, sources: List<NovelSource>): Job {
        pend(sources)
        return saver.launch {
            try {
                val text = JSONArray(sources.map { it.json }).toString()
                put(text, sources)
                prefs.edit().putString("sources", text).apply()
            } finally { settle(sources) }
        }
    }
}

/** [NovelCache.books] parses every saved 目录 (thousands of rows per book): reuse the result until a book.json changes. */
private object ShelfMemo {
    private var stamp: String? = null
    private var list = java.lang.ref.SoftReference<List<NovelCache.Saved>>(null)

    @Synchronized fun books(cache: NovelCache, root: File): List<NovelCache.Saved> {
        val now = root.listFiles().orEmpty().mapNotNull { dir ->
            File(dir, "book.json").let { f -> if (f.isFile) "${dir.name}:${f.lastModified()}:${f.length()}" else null }
        }.sorted().joinToString("|")
        list.get()?.let { if (now == stamp) return it }
        return cache.books().also { list = java.lang.ref.SoftReference(it); stamp = now }
    }

    /** Small shelves load with the first frame; a big one would freeze it. */
    fun heavy(root: File): Boolean = root.listFiles().orEmpty().sumOf { File(it, "book.json").length() } > 1_500_000L
}
