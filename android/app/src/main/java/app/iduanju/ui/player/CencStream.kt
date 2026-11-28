@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.iduanju.ui.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import app.iduanju.core.cenc.CENCDecryptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.util.TreeMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal const val CENC_SCHEME = "idjcenc"

/** Half-open byte ranges, merged. */
internal class RangeSet {
    private val m = TreeMap<Long, Long>()

    fun add(from: Long, to: Long) {
        if (to <= from) return
        var a = from; var b = to
        m.floorEntry(a)?.let { if (it.value >= a) { a = it.key; b = maxOf(b, it.value) } }
        var e = m.ceilingEntry(a)
        while (e != null && e.key <= b) { b = maxOf(b, e.value); m.remove(e.key); e = m.ceilingEntry(a) }
        m[a] = b
    }

    /** Bytes available from [pos] on without a gap. */
    fun available(pos: Long): Long = m.floorEntry(pos)?.let { (it.value - pos).coerceAtLeast(0L) } ?: 0L

    /** First position ≥ [from] that is not covered. */
    fun firstMissing(from: Long): Long = m.floorEntry(from)?.value?.takeIf { it > from } ?: from

    fun covers(total: Long): Boolean = total > 0 && m.floorEntry(0L)?.value?.let { it >= total } == true
}

/**
 * One encrypted episode, downloaded and decrypted on the fly into a sparse local file, so playback starts as soon as
 * the `moov` box and the first samples are here instead of after the whole file. A single worker follows the reader
 * (seeks reopen the connection there), then fills the gaps; the finished file becomes the episode's offline cache.
 */
internal class CencFile(
    val id: String,
    @Volatile private var urls: List<String>,
    private val headers: Map<String, String>,
    private val keyHex: String,
    val target: File,
    private val onComplete: (File) -> Unit,
) {
    private class Http(val code: Int) : IOException("媒体 HTTP $code")
    private class Conn(val response: Response, val stream: InputStream, var pos: Long) {
        fun close() = runCatching { response.close() }
    }

    private val part = File(target.parentFile, "${target.name}.$id.part")
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val have = RangeSet()
    private var total = -1L
    private var ready = false
    private var fatal: IOException? = null
    private var readers = 0
    private var lastDetach = 0L
    private var wanted = 0L
    private var prefetchLimit = 0L
    private var worker: Job? = null
    private var closed = false

    private val io = Any()
    private var raf: RandomAccessFile? = null

    // Worker only.
    private var layout: CencLayout? = null
    @Volatile private var urlIndex = 0
    @Volatile private var activeCall: okhttp3.Call? = null
    private var ranged = false

    @Volatile var complete = false
        private set

    val isFailed: Boolean get() = lock.withLock { fatal != null && !complete }
    val isClosed: Boolean get() = lock.withLock { closed }

    /** Download the first [bytes] even while nobody reads (next-episode warm-up; Long.MAX_VALUE = all of it). */
    fun prefetch(bytes: Long) = lock.withLock {
        prefetchLimit = maxOf(prefetchLimit, bytes)
        startWorker()
    }

    /** A freshly resolved address of the same file: what is already here stays valid. */
    fun renew(fresh: List<String>) {
        var cancelHung = false
        lock.withLock {
            if (complete || closed || fresh.isEmpty()) return
            val urlsChanged = fresh != urls
            if (!urlsChanged && fatal == null) return
            if (urlsChanged) urls = fresh
            urlIndex = 0
            fatal = null
            if (urlsChanged && !ready && worker?.isActive == true) {
                cancelHung = true
                worker?.cancel()
                worker = null
            }
            changed.signalAll()
        }
        if (cancelHung) {
            runCatching { activeCall?.cancel() }
            lock.withLock { if (!closed && (readers > 0 || prefetchLimit > 0L)) startWorker() }
        }
    }

    /** Stop background downloading; a reader that comes back restarts it. */
    fun relax() = lock.withLock { prefetchLimit = 0L }

    fun attach(position: Long) = lock.withLock {
        readers++
        wanted = position
        if (fatal != null) {
            fatal = null
            urlIndex = 0
        }
        startWorker()
        changed.signalAll()
    }

    fun detach() = lock.withLock {
        readers = (readers - 1).coerceAtLeast(0)
        lastDetach = System.currentTimeMillis()
    }

    val idle: Boolean get() = lock.withLock { readers == 0 }

    fun shutdown() {
        lock.withLock { closed = true; changed.signalAll() }
        runCatching { activeCall?.cancel() }
        worker?.cancel()
        synchronized(io) { runCatching { raf?.close() }; raf = null }
        if (!complete) part.delete()
    }

    private fun startWorker() {
        if (complete || closed || worker?.isActive == true) return
        worker = scope.launch { run() }
    }

    /** Blocks until the layout is known; the plain file's length. */
    fun length(): Long = lock.withLock {
        await { ready || complete }
        total
    }

    fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        val n = lock.withLock {
            if (wanted != pos) { wanted = pos; changed.signalAll() }
            if (total in 0..pos) return C.RESULT_END_OF_INPUT
            await { have.available(pos) > 0 || (complete && pos >= total) }
            minOf(len.toLong(), have.available(pos)).toInt()
        }
        if (n <= 0) return C.RESULT_END_OF_INPUT
        synchronized(io) {
            val f = raf ?: throw IOException("缓存已关闭")
            f.seek(pos)
            return f.read(buf, off, n)
        }
    }

    private inline fun await(done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(READ_TIMEOUT_S)
        while (!done()) {
            fatal?.let { throw IOException(it.message, it) }
            if (closed) throw IOException("播放缓存已释放")
            startWorker()
            val left = deadline - System.nanoTime()
            if (left <= 0) throw IOException("媒体下载超时")
            // Short waits: a worker that is just going idle is restarted on the next turn.
            try { changed.awaitNanos(minOf(left, TimeUnit.MILLISECONDS.toNanos(400))) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw InterruptedIOException() }
        }
    }

    // ── worker ──────────────────────────────────────────────────────────────────────────────────────────

    private suspend fun run() {
        var failures = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                val conn = if (lock.withLock { ready }) null else head()
                pump(conn) { failures = 0 }
                return
            } catch (e: CancellationException) { throw e
            } catch (e: CencLayout.Unsupported) {
                android.util.Log.i("IDJ", "cenc: streaming unsupported (${e.message}), decrypting the whole file")
                try { whole() } catch (x: CancellationException) { throw x } catch (x: Exception) { fail(x as? IOException ?: IOException(x.message, x)) }
                return
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                failures++
                val refused = e is Http && e.code in 400..499 && e.code != 408 && e.code != 429
                if (refused || failures > 2) {
                    if (urlIndex + 1 < urls.size) { urlIndex++; failures = 0 } else { fail(e as? IOException ?: IOException(e.message, e)); return }
                }
                delay(250L * failures)
            }
        }
    }

    private fun fail(e: IOException) = lock.withLock {
        android.util.Log.w("IDJ", "cenc download failed", e)
        fatal = e
        changed.signalAll()
    }

    private fun get(from: Long, length: Long = -1L): Response {
        val range = if (length > 0) "bytes=$from-${from + length - 1}" else "bytes=$from-"
        val all = urls
        val req = Request.Builder().url(all[urlIndex.coerceIn(0, all.lastIndex)]).header("Range", range).header("Accept-Encoding", "identity")
            .apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        val call = PlayerHttp.client.newCall(req)
        activeCall = call
        val resp = try { call.execute() } finally { if (activeCall === call) activeCall = null }
        if (!resp.isSuccessful) { resp.close(); throw Http(resp.code) }
        return resp
    }

    /** First request: finds moov, builds the layout, writes what was read. Returns the still open connection. */
    private fun head(): Conn {
        val resp = get(0)
        try {
            ranged = resp.code == 206
            val body = resp.body ?: throw IOException("媒体空包")
            val size = resp.header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull() ?: body.contentLength()
            val input = body.byteStream()
            var buf = ByteArray(256 * 1024)
            var filled = 0
            fun need(n: Long) {
                if (n > MAX_HEAD) throw CencLayout.Unsupported("文件头过大")
                if (n > buf.size) buf = buf.copyOf(maxOf(n.toInt(), minOf(buf.size * 2, MAX_HEAD)))
                while (filled < n) {
                    val r = input.read(buf, filled, buf.size - filled)
                    if (r < 0) throw IOException("媒体数据不完整")
                    filled += r
                }
            }
            if (size <= 0) throw CencLayout.Unsupported("未知长度")
            var p = 0L
            var moov: ByteArray? = null
            var moovAt = 0L
            while (moov == null) {
                need(p + 16)
                val i = p.toInt()
                var boxSize = be32(buf, i)
                val type = String(buf, i + 4, 4, Charsets.ISO_8859_1)
                if (boxSize == 1L) boxSize = (be32(buf, i + 8) shl 32) or be32(buf, i + 12)
                when {
                    type == "moov" -> { need(p + boxSize); moov = buf.copyOfRange(i, (p + boxSize).toInt()); moovAt = p }
                    type == "moof" || boxSize == 0L -> throw CencLayout.Unsupported("分片 MP4")
                    boxSize < 8 -> throw IOException("媒体格式异常")
                    type == "mdat" -> {
                        if (!ranged) throw CencLayout.Unsupported("moov 在文件尾且不支持分段")
                        val (at, bytes) = tail(p + boxSize, size)
                        moov = bytes; moovAt = at
                    }
                    else -> { need(p + boxSize + 16); p += boxSize }
                }
            }
            val l = CencLayout.parse(moov, moovAt, keyHex) { at, len ->
                get(at, len.toLong()).use { r -> if (r.code != 206) throw CencLayout.Unsupported("不支持分段"); r.body!!.bytes().also { if (it.size < len) throw IOException("辅助信息不完整") } }
            }
            layout = l
            l?.apply(0L, buf, 0, filled)
            if (moovAt >= filled) l?.apply(moovAt, moov, 0, moov.size)
            synchronized(io) {
                if (raf == null) { part.parentFile?.mkdirs(); raf = RandomAccessFile(part, "rw").apply { setLength(size) } }
                raf!!.run { seek(0); write(buf, 0, filled); if (moovAt >= filled) { seek(moovAt); write(moov, 0, moov.size) } }
            }
            lock.withLock {
                total = size
                have.add(0, filled.toLong())
                if (moovAt >= filled) have.add(moovAt, moovAt + moov.size)
                ready = true
                changed.signalAll()
            }
            return Conn(resp, input, filled.toLong())
        } catch (t: Throwable) { resp.close(); throw t }
    }

    /** moov behind mdat. */
    private fun tail(from: Long, size: Long): Pair<Long, ByteArray> {
        if (from >= size || size - from > MAX_HEAD) throw CencLayout.Unsupported("moov 位置异常")
        val bytes = get(from).use { r -> if (r.code != 206) throw CencLayout.Unsupported("不支持分段"); r.body!!.bytes() }
        var i = 0
        while (i + 8 <= bytes.size) {
            val s = be32(bytes, i)
            if (String(bytes, i + 4, 4, Charsets.ISO_8859_1) == "moov") {
                if (s < 8 || i + s > bytes.size) break
                return from + i to bytes.copyOfRange(i, (i + s).toInt())
            }
            if (s < 8) break
            i += s.toInt()
        }
        throw CencLayout.Unsupported("缺少 moov")
    }

    private suspend fun pump(first: Conn?, progressed: () -> Unit) {
        var conn = first
        val chunk = ByteArray(64 * 1024)
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val next = lock.withLock { nextPosition() }
                if (next == DONE) { finish(); return }
                if (next == IDLE) {
                    // Nobody needs more right now: keep the connection a moment (seeks detach and re-attach), then let go.
                    val again = lock.withLock { changed.awaitNanos(TimeUnit.MILLISECONDS.toNanos(1500)); nextPosition() }
                    if (again == IDLE) return
                    continue
                }
                val c = conn
                val open = if (c != null && next >= c.pos && next - c.pos <= SKIP_BYTES) c else {
                    c?.close(); conn = null
                    if (!ranged && next > 0) throw CencLayout.Unsupported("不支持分段")
                    val r = get(next)
                    if (next > 0 && r.code != 206) { r.close(); throw CencLayout.Unsupported("不支持分段") }
                    Conn(r, (r.body ?: throw IOException("媒体空包")).byteStream(), next).also { conn = it }
                }
                val n = open.stream.read(chunk)
                if (n < 0) {
                    open.close(); conn = null
                    if (open.pos < total) throw IOException("媒体数据提前结束")
                    continue
                }
                layout?.apply(open.pos, chunk, 0, n)
                synchronized(io) { (raf ?: throw IOException("缓存已关闭")).run { seek(open.pos); write(chunk, 0, n) } }
                lock.withLock { have.add(open.pos, open.pos + n); changed.signalAll() }
                open.pos += n
                progressed()
            }
        } finally { conn?.close() }
    }

    /** Where to read next; call with the lock held. */
    private fun nextPosition(): Long {
        if (have.covers(total)) return DONE
        val reading = readers > 0 || (lastDetach > 0 && System.currentTimeMillis() - lastDetach < 1500)
        if (reading) {
            // Without range requests the one connection only goes forward.
            val ahead = have.firstMissing(if (ranged) wanted.coerceIn(0L, total) else 0L)
            return if (ahead < total) ahead else have.firstMissing(0L)
        }
        val gap = have.firstMissing(0L)
        return if (gap < minOf(prefetchLimit, total)) gap else IDLE
    }

    private fun finish() {
        synchronized(io) { runCatching { raf?.fd?.sync() } }
        if (part.renameTo(target)) onComplete(target)
        lock.withLock { complete = true; changed.signalAll() }
    }

    /** Layouts the streaming path cannot handle (fragmented, scattered aux info, no range support). */
    private fun whole() {
        val enc = get(0).use { it.body?.bytes() ?: ByteArray(0) }
        if (enc.isEmpty()) throw IOException("媒体空包")
        val dec = try { CENCDecryptor.decryptCopy(enc, keyHex) } catch (e: CENCDecryptor.Failure) { throw IOException(e.message, e) }
        synchronized(io) {
            val f = raf ?: RandomAccessFile(part, "rw").also { raf = it }
            f.setLength(dec.size.toLong()); f.seek(0); f.write(dec)
        }
        lock.withLock { total = dec.size.toLong(); have.add(0, total); ready = true; changed.signalAll() }
        finish()
    }

    private fun be32(b: ByteArray, o: Int): Long = ((b[o].toLong() and 0xff) shl 24) or ((b[o + 1].toLong() and 0xff) shl 16) or ((b[o + 2].toLong() and 0xff) shl 8) or (b[o + 3].toLong() and 0xff)

    private companion object {
        const val DONE = -1L
        const val IDLE = -2L
        const val MAX_HEAD = 24 shl 20
        const val SKIP_BYTES = 512 * 1024L
        const val READ_TIMEOUT_S = 20L
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

/** Open CENC episodes, most recently used last. */
internal object CencStore {
    private const val MAX_OPEN = 8
    private val files = LinkedHashMap<String, CencFile>(12, 0.75f, true)

    @Synchronized fun get(id: String): CencFile? = files[id]

    @Synchronized fun remove(id: String) {
        files.remove(id)?.shutdown()
    }

    @Synchronized fun open(id: String, urls: List<String>, create: () -> CencFile): CencFile {
        files[id]?.let { existing ->
            if (!existing.isClosed) {
                existing.renew(urls)
                return existing
            }
            files.remove(id)
        }
        val f = create()
        files[id] = f
        val it = files.entries.iterator()
        while (files.size > MAX_OPEN && it.hasNext()) {
            val e = it.next()
            if (e.value !== f && e.value.idle) { e.value.shutdown(); it.remove() }
        }
        return f
    }

    /** Only [id] keeps downloading in the background (the episode on screen, or none). */
    @Synchronized fun focus(id: String?) { files.forEach { (k, f) -> if (k != id) f.relax() } }
}

/** Reads `idjcenc://<id>` through [CencStore]. */
internal class CencDataSource : BaseDataSource(/* isNetwork = */ true) {
    private var file: CencFile? = null
    private var uri: Uri? = null
    private var pos = 0L
    private var left = 0L
    private var started = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val f = CencStore.get(dataSpec.uri.host.orEmpty()) ?: throw DataSourceException(IOException("播放缓存已释放"), PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        f.attach(dataSpec.position)
        file = f
        val total = f.length()
        pos = dataSpec.position
        if (pos > total) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        left = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, total - pos) else total - pos
        started = true
        transferStarted(dataSpec)
        return left
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (left <= 0L) return C.RESULT_END_OF_INPUT
        val n = (file ?: throw IOException("未打开")).read(pos, buffer, offset, minOf(length.toLong(), left).toInt())
        if (n < 0) return C.RESULT_END_OF_INPUT
        pos += n
        left -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        val f = file ?: return
        file = null
        f.detach()
        if (started) { started = false; transferEnded() }
    }
}
