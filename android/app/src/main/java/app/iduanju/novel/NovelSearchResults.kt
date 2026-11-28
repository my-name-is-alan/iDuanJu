package app.iduanju.novel

import androidx.compose.runtime.*
import app.iduanju.core.novel.NovelHit
import app.iduanju.core.novel.NovelSource
import app.iduanju.core.novel.NovelSources
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal data class SearchEdition(val source: NovelSource, val book: NovelHit)
internal data class BookMatches(val editions: List<SearchEdition>) {
    val first get() = editions.first()
}

/**
 * Bounded, off-main-thread multi-source novel search aggregator:
 * - Fixed worker pool (never spawns thousands of coroutines at once)
 * - Per-source hard timeout + interruptible IO (a hung source cannot starve workers or freeze UI)
 * - O(1) hash-bucket deduplication on Dispatchers.Default (eliminates O(N^2) main-thread stalls)
 * - 250 ms UI snapshot throttling + memory caps (prevents Compose recomposition storms & OOM)
 */
internal class NovelSearchResults {
    var groups by mutableStateOf(emptyList<BookMatches>())
        private set
    var total by mutableIntStateOf(0)
        private set
    var finished by mutableIntStateOf(0)
        private set
    var failures by mutableIntStateOf(0)
        private set
    var running by mutableStateOf(false)
        private set
    private var job: Job? = null

    fun clear() {
        job?.cancel()
        job = null
        groups = emptyList()
        total = 0
        finished = 0
        failures = 0
        running = false
    }

    /** Stops in-flight workers while preserving results already collected. */
    fun stop() {
        job?.cancel()
        job = null
        running = false
    }

    fun submit(scope: CoroutineScope, sources: List<NovelSource>, query: String) {
        val term = query.trim()
        if (term.isEmpty()) return
        clear()
        val searchable = sources.filter {
            it.enabled && (it.isRSS || it.isJsSource || it.json.optString("searchUrl").isNotBlank())
        }
        total = searchable.size
        if (searchable.isEmpty()) return
        running = true

        job = scope.launch(Dispatchers.Default) {
            val mergeLock = Any()
            val buckets = ArrayList<MutableList<SearchEdition>>(128)
            val sourceIds = ArrayList<HashSet<String>>(128)
            val byName = HashMap<String, MutableList<Int>>(128)
            val doneCount = AtomicInteger(0)
            val failCount = AtomicInteger(0)
            val dirty = AtomicBoolean(false)
            val firstHits = AtomicBoolean(false)

            fun addHits(source: NovelSource, hits: List<NovelHit>) {
                if (hits.isEmpty()) return
                synchronized(mergeLock) {
                    var added = 0
                    for (hit in hits) {
                        if (added >= MAX_HITS_PER_SOURCE) break
                        val normName = hit.name.trim()
                        if (normName.isEmpty()) continue
                        val normAuthor = hit.author.trim()
                        val candidates = byName[normName]
                        var targetIdx = -1
                        if (candidates != null) {
                            for (idx in candidates) {
                                val firstAuthor = buckets[idx][0].book.author.trim()
                                if (firstAuthor.isEmpty() || normAuthor.isEmpty() || firstAuthor == normAuthor) {
                                    targetIdx = idx
                                    break
                                }
                            }
                        }
                        if (targetIdx >= 0) {
                            if (sourceIds[targetIdx].add(source.id)) {
                                buckets[targetIdx].add(SearchEdition(source, hit))
                                dirty.set(true)
                            }
                            added++
                        } else if (buckets.size < MAX_GROUPS) {
                            val idx = buckets.size
                            buckets.add(mutableListOf(SearchEdition(source, hit)))
                            sourceIds.add(hashSetOf(source.id))
                            byName.getOrPut(normName) { ArrayList(2) }.add(idx)
                            dirty.set(true)
                            added++
                        }
                    }
                    if (buckets.isNotEmpty()) firstHits.set(true)
                }
            }

            fun buildSnapshot(): List<BookMatches> = synchronized(mergeLock) {
                buckets.map { BookMatches(it.toList()) }.sortedWith(
                    compareBy<BookMatches> {
                        val b = it.first.book
                        when {
                            b.name == term -> 0
                            term in b.name || b.author == term -> 1
                            else -> 2
                        }
                    }.thenByDescending { it.editions.size },
                )
            }

            suspend fun publish(forceSort: Boolean) {
                val snap = if (forceSort || dirty.getAndSet(false)) buildSnapshot() else null
                val done = doneCount.get()
                val fails = failCount.get()
                withContext(Dispatchers.Main.immediate) {
                    if (snap != null) groups = snap
                    finished = done
                    failures = fails
                }
            }

            val flusher = launch {
                var publishedFirst = false
                while (isActive) {
                    delay(if (!publishedFirst) 120L else UI_FLUSH_INTERVAL_MS)
                    if (!publishedFirst && firstHits.get()) publishedFirst = true
                    publish(forceSort = false)
                }
            }

            try {
                val queue = Channel<NovelSource>(Channel.UNLIMITED)
                searchable.forEach { queue.trySend(it) }
                queue.close()

                val workerCount = minOf(WORKER_COUNT, searchable.size)
                val workers = List(workerCount) {
                    launch(Dispatchers.IO) {
                        for (source in queue) {
                            ensureActive()
                            var failed = false
                            val hits = try {
                                withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                                    runInterruptible(Dispatchers.IO) {
                                        NovelSources.search(source, term, batchMode = true)
                                    }
                                } ?: run {
                                    failed = true
                                    emptyList()
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Throwable) {
                                failed = true
                                emptyList()
                            }
                            if (failed) failCount.incrementAndGet()
                            else addHits(source, hits)
                            doneCount.incrementAndGet()
                        }
                    }
                }
                workers.forEach { it.join() }
            } finally {
                flusher.cancel()
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    if (isActive || doneCount.get() > 0) {
                        groups = buildSnapshot()
                        finished = doneCount.get()
                        failures = failCount.get()
                    }
                    running = false
                }
            }
        }
    }

    private companion object {
        const val WORKER_COUNT = 6
        const val SOURCE_TIMEOUT_MS = 12_000L
        const val UI_FLUSH_INTERVAL_MS = 250L
        const val MAX_HITS_PER_SOURCE = 25
        const val MAX_GROUPS = 600
    }
}
