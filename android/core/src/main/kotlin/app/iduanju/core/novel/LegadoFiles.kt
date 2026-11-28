package app.iduanju.core.novel

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

/**
 * Sandboxed per-source file, script-cache, and ZIP archive helper for Legado JS extensions
 * (`importScript`, `cacheFile`, `downloadFile`, `readFile`, `readTxtFile`, `deleteFile`,
 * `unzipFile`, `getZipStringContent`, `getZipByteArrayContent`, `getTxtInFolder`).
 * Every path is strictly confined inside `<root>/legado-files/<sourceHash>/`.
 */
object LegadoFiles {
    private const val MAX_FILE_BYTES = 16 * 1024 * 1024
    private const val MAX_ZIP_ENTRIES = 512

    @Volatile private var rootDir: File? = null
    private val memCache = ConcurrentHashMap<String, Pair<Long, ByteArray>>()

    fun configure(root: File) {
        rootDir = File(root, "legado-files")
    }

    private fun baseDir(sourceId: String): File {
        val base = rootDir ?: File(System.getProperty("java.io.tmpdir"), "iduanju-legado-files")
        val dir = File(base, sha256(sourceId).take(16))
        dir.mkdirs()
        return dir
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Resolves [relPath] safely inside the source's sandbox directory, rejecting traversal. */
    fun safeFile(sourceId: String, relPath: String): File {
        val base = baseDir(sourceId).canonicalFile
        val cleaned = relPath.trim().removePrefix("/").replace('\\', '/')
        require(cleaned.isNotEmpty()) { "文件路径不能为空" }
        val candidate = File(base, cleaned).canonicalFile
        require(candidate.path == base.path || candidate.path.startsWith(base.path + File.separator)) {
            "路径超出书源沙箱目录"
        }
        return candidate
    }

    /** Downloads [url] with caching (TTL in seconds, 0 = permanent until cleared) and returns raw bytes. */
    fun fetchCached(url: String, source: NovelSource, saveTimeSec: Int = 0): ByteArray {
        val key = "${source.id}|$url"
        val now = System.currentTimeMillis()
        memCache[key]?.let { (exp, bytes) -> if (exp == 0L || exp > now) return bytes }
        val cacheFile = File(baseDir(source.id), "cache_${sha256(url).take(24)}.bin")
        val metaFile = File(baseDir(source.id), "cache_${sha256(url).take(24)}.exp")
        if (cacheFile.isFile) {
            val exp = runCatching { metaFile.readText().trim().toLong() }.getOrDefault(0L)
            if (exp == 0L || exp > now) {
                val bytes = cacheFile.readBytes()
                memCache[key] = exp to bytes
                return bytes
            }
        }
        val resp = NovelSources.fetchBytes(url, "GET", NovelSources.headers(source), byteArrayOf())
        require(resp.status in 200..299) { "文件下载失败：HTTP ${resp.status}" }
        require(resp.bytes.size <= MAX_FILE_BYTES) { "文件过大" }
        val exp = if (saveTimeSec > 0) now + saveTimeSec * 1000L else 0L
        memCache[key] = exp to resp.bytes
        runCatching {
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeBytes(resp.bytes)
            metaFile.writeText(exp.toString())
        }
        return resp.bytes
    }

    fun importScript(pathOrUrl: String, source: NovelSource): String {
        val t = pathOrUrl.trim()
        if (t.isEmpty()) return ""
        return if (t.startsWith("http://", true) || t.startsWith("https://", true)) {
            String(fetchCached(t, source, 0), Charsets.UTF_8)
        } else {
            val f = safeFile(source.id, t)
            if (f.isFile && f.length() <= MAX_FILE_BYTES) f.readText(Charsets.UTF_8) else ""
        }
    }

    fun cacheFile(url: String, source: NovelSource, saveTimeSec: Int = 0): String {
        val t = url.trim()
        if (t.isEmpty()) return ""
        val bytes = fetchCached(t, source, saveTimeSec)
        return String(bytes, Charsets.UTF_8)
    }

    fun downloadFile(url: String, source: NovelSource): String {
        val bytes = fetchCached(url.trim(), source, 0)
        val ext = url.substringBefore('?').substringAfterLast('.', "").take(8).filter { it.isLetterOrDigit() }
        val rel = "dl_${sha256(url).take(20)}" + if (ext.isNotEmpty()) ".$ext" else ".bin"
        val f = safeFile(source.id, rel)
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
        return rel
    }

    fun readFile(sourceId: String, path: String): ByteArray? {
        val f = runCatching { safeFile(sourceId, path) }.getOrNull() ?: return null
        if (!f.isFile || f.length() > MAX_FILE_BYTES) return null
        return f.readBytes()
    }

    fun readTxtFile(sourceId: String, path: String, charsetName: String = "UTF-8"): String {
        val bytes = readFile(sourceId, path) ?: return ""
        val cs = runCatching { Charset.forName(charsetName) }.getOrDefault(Charsets.UTF_8)
        return String(bytes, cs)
    }

    fun deleteFile(sourceId: String, path: String): Boolean {
        val f = runCatching { safeFile(sourceId, path) }.getOrNull() ?: return false
        return f.deleteRecursively()
    }

    fun unzipFile(sourceId: String, zipPath: String): String {
        val bytes = readFile(sourceId, zipPath) ?: error("压缩文件不存在：$zipPath")
        val destRel = "unzip_${sha256(zipPath).take(16)}"
        val destDir = safeFile(sourceId, destRel)
        destDir.mkdirs()
        var total = 0L
        var count = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                count++; require(count <= MAX_ZIP_ENTRIES) { "压缩包条目过多" }
                if (!entry.isDirectory) {
                    val out = safeFile(sourceId, "$destRel/${entry.name}")
                    out.parentFile?.mkdirs()
                    out.outputStream().use { os ->
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = zis.read(buf); if (n < 0) break
                            total += n; require(total <= MAX_FILE_BYTES) { "解压内容过大" }
                            os.write(buf, 0, n)
                        }
                    }
                }
                zis.closeEntry()
            }
        }
        return destRel
    }

    fun getZipEntryBytes(urlOrPath: String, entryPath: String, source: NovelSource): ByteArray? {
        val raw = if (urlOrPath.startsWith("http://", true) || urlOrPath.startsWith("https://", true)) {
            fetchCached(urlOrPath, source, 0)
        } else {
            readFile(source.id, urlOrPath) ?: return null
        }
        val want = entryPath.trim().removePrefix("/")
        ZipInputStream(ByteArrayInputStream(raw)).use { zis ->
            var count = 0
            while (true) {
                val entry = zis.nextEntry ?: break
                count++; if (count > MAX_ZIP_ENTRIES) break
                val name = entry.name.removePrefix("/")
                if (!entry.isDirectory && (name == want || name.endsWith("/$want"))) {
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = zis.read(buf); if (n < 0) break
                        require(out.size() + n <= MAX_FILE_BYTES) { "ZIP 条目过大" }
                        out.write(buf, 0, n)
                    }
                    return out.toByteArray()
                }
                zis.closeEntry()
            }
        }
        return null
    }

    fun getZipStringContent(urlOrPath: String, entryPath: String, source: NovelSource, charsetName: String = "UTF-8"): String {
        val b = getZipEntryBytes(urlOrPath, entryPath, source) ?: return ""
        val cs = runCatching { Charset.forName(charsetName) }.getOrDefault(Charsets.UTF_8)
        return String(b, cs)
    }

    fun getTxtInFolder(sourceId: String, relDir: String): String {
        val dir = runCatching { safeFile(sourceId, relDir) }.getOrNull() ?: return ""
        if (!dir.isDirectory) return ""
        val sb = StringBuilder()
        dir.walkTopDown().filter { it.isFile && it.extension.equals("txt", true) }.sortedBy { it.name }.forEach { f ->
            if (sb.length + f.length() <= MAX_FILE_BYTES) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(f.readText(Charsets.UTF_8))
            }
        }
        return sb.toString()
    }
}
