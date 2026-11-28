package app.iduanju.core.novel

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Per-source Legado `concurrentRate` limiter (`"ms"` or `"count/ms"`),
 * plus script concurrency helpers (`singleFlight`, `lock`, `tick`).
 */
object LegadoRateLimit {
    internal data class Spec(val count: Int, val windowMs: Long)
    private class Bucket {
        val timestamps = ArrayDeque<Long>()
    }

    private val buckets = ConcurrentHashMap<String, Bucket>()
    private val overrides = ConcurrentHashMap<String, String>()
    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    private val ticks = ConcurrentHashMap<String, AtomicInteger>()

    fun putOverride(sourceKey: String, rate: String?) {
        val trimmed = rate?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == "0") overrides.remove(sourceKey) else overrides[sourceKey] = trimmed
    }

    fun effectiveRate(sourceKey: String, declared: String?): String? =
        overrides[sourceKey] ?: declared?.trim()?.takeIf { it.isNotEmpty() && it != "0" }

    internal fun parse(raw: String?): Spec? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text == "0") return null
        val parts = text.split('/')
        return when (parts.size) {
            1 -> {
                val ms = parts[0].trim().toLongOrNull() ?: return null
                if (ms <= 0L) null else Spec(1, ms.coerceAtMost(60_000L))
            }
            2 -> {
                val count = parts[0].trim().toIntOrNull() ?: return null
                val ms = parts[1].trim().toLongOrNull() ?: return null
                if (count <= 0 || ms <= 0L) null else Spec(count.coerceAtMost(100), ms.coerceAtMost(60_000L))
            }
            else -> null
        }
    }

    /** Blocks until the source's rate budget allows one request. Interrupt-safe. */
    fun acquire(sourceKey: String, declaredRate: String?) {
        val spec = parse(effectiveRate(sourceKey, declaredRate)) ?: return
        val bucket = buckets.computeIfAbsent(sourceKey) { Bucket() }
        while (true) {
            var sleepMs = 0L
            synchronized(bucket) {
                val now = System.currentTimeMillis()
                while (bucket.timestamps.isNotEmpty() && now - bucket.timestamps.first() >= spec.windowMs) {
                    bucket.timestamps.removeFirst()
                }
                if (bucket.timestamps.size < spec.count) {
                    bucket.timestamps.addLast(now)
                    return
                }
                sleepMs = (spec.windowMs - (now - bucket.timestamps.first())).coerceAtLeast(1L)
            }
            Thread.sleep(sleepMs)
        }
    }

    fun <T> withLock(key: String, block: () -> T): T {
        val lock = locks.computeIfAbsent(key) { ReentrantLock() }
        return lock.withLock(block)
    }

    fun tick(sourceKey: String, name: String): Int =
        ticks.computeIfAbsent("$sourceKey|$name") { AtomicInteger(0) }.incrementAndGet()

    fun resetForTest() {
        buckets.clear()
        overrides.clear()
        locks.clear()
        ticks.clear()
    }
}
