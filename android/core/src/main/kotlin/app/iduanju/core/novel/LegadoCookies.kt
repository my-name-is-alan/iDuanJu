package app.iduanju.core.novel

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Persistent per-domain/path CookieStore for Android Legado book sources,
 * matching iOS `LegadoCookies` and upstream `io.legado.app.help.http.CookieStore`.
 */
object LegadoCookies {
    data class Stored(
        val name: String,
        val value: String,
        val domain: String,
        val path: String = "/",
        val secure: Boolean = false,
        val expiresMs: Long? = null,
    )

    /** Optional Android WebView CookieManager bridge injected by IdjApp. */
    interface PlatformBridge {
        fun getCookies(url: String): String?
        fun setCookie(url: String, cookieHeader: String)
        fun removeCookiesForDomain(url: String)
    }

    @Volatile var bridge: PlatformBridge? = null
    private var file: File? = null
    private val cookies = LinkedHashMap<String, Stored>()

    @Synchronized fun configure(root: File) {
        val target = File(root, "novel-cookies.json")
        if (file == target) return
        file = target
        cookies.clear()
        val arr = runCatching { JSONArray(target.readText()) }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val exp = if (o.has("expiresMs") && !o.isNull("expiresMs")) o.optLong("expiresMs") else null
            if (exp != null && exp < now) continue
            val c = Stored(
                name = o.optString("name"),
                value = o.optString("value"),
                domain = o.optString("domain").lowercase(Locale.ROOT),
                path = o.optString("path").ifBlank { "/" },
                secure = o.optBoolean("secure", false),
                expiresMs = exp,
            )
            if (c.name.isNotBlank() && c.domain.isNotBlank()) cookies[keyOf(c)] = c
        }
    }

    private fun keyOf(c: Stored) = "${c.domain.lowercase(Locale.ROOT)}|${c.path}|${c.name}"

    private fun domainMatches(cookieDomain: String, host: String): Boolean {
        val cd = cookieDomain.lowercase(Locale.ROOT).trimStart('.')
        val h = host.lowercase(Locale.ROOT)
        if (cd.isEmpty() || h.isEmpty()) return false
        return h == cd || h.endsWith(".$cd")
    }

    private fun pathMatches(cookiePath: String, requestPath: String): Boolean {
        val cp = cookiePath.ifEmpty { "/" }
        val rp = requestPath.ifEmpty { "/" }
        if (cp == "/") return true
        return rp == cp || rp.startsWith(if (cp.endsWith("/")) cp else "$cp/")
    }

    @Synchronized fun cookieHeader(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        val isHttps = uri.scheme.equals("https", ignoreCase = true)
        val path = uri.rawPath?.ifEmpty { "/" } ?: "/"
        val now = System.currentTimeMillis()
        val pairs = LinkedHashMap<String, String>()

        val it = cookies.entries.iterator()
        var dirty = false
        while (it.hasNext()) {
            val c = it.next().value
            if (c.expiresMs != null && c.expiresMs < now) { it.remove(); dirty = true; continue }
            if (c.secure && !isHttps) continue
            if (domainMatches(c.domain, host) && pathMatches(c.path, path)) {
                pairs[c.name] = c.value
            }
        }
        if (dirty) persistLocked()

        // Merge live WebView CookieManager entries when available on Android.
        bridge?.getCookies(url)?.let { raw ->
            for (part in raw.split(';')) {
                val eq = part.indexOf('=')
                if (eq > 0) {
                    val k = part.substring(0, eq).trim()
                    val v = part.substring(eq + 1).trim()
                    if (k.isNotEmpty() && k !in pairs) pairs[k] = v
                }
            }
        }
        if (pairs.isEmpty()) return null
        return pairs.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun getKey(url: String, key: String): String {
        val raw = cookieHeader(url).orEmpty()
        for (part in raw.split(';')) {
            val eq = part.indexOf('=')
            if (eq > 0 && part.substring(0, eq).trim() == key) {
                return part.substring(eq + 1).trim()
            }
        }
        return ""
    }

    /** Captures HTTP `Set-Cookie` response headers into the persistent store. */
    @Synchronized fun captureSetCookies(url: String, setCookieHeaders: List<String>) {
        if (setCookieHeaders.isEmpty()) return
        val uri = runCatching { URI(url) }.getOrNull() ?: return
        val host = uri.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return
        val now = System.currentTimeMillis()
        var changed = false
        for (header in setCookieHeaders) {
            val segments = header.split(';')
            val first = segments.firstOrNull()?.trim().orEmpty()
            val eq = first.indexOf('=')
            if (eq <= 0) continue
            val name = first.substring(0, eq).trim()
            val value = first.substring(eq + 1).trim()
            if (name.isEmpty()) continue
            var domain = host
            var path = "/"
            var secure = false
            var expiresMs: Long? = null
            for (attr in segments.drop(1)) {
                val p = attr.trim()
                val idx = p.indexOf('=')
                val ak = (if (idx >= 0) p.substring(0, idx) else p).trim().lowercase(Locale.ROOT)
                val av = if (idx >= 0) p.substring(idx + 1).trim() else ""
                when (ak) {
                    "domain" -> {
                        val candidate = av.trimStart('.').lowercase(Locale.ROOT)
                        if (candidate.isNotEmpty() && domainMatches(candidate, host)) domain = candidate
                    }
                    "path" -> if (av.isNotEmpty()) path = av
                    "secure" -> secure = true
                    "max-age" -> av.toLongOrNull()?.let { sec -> expiresMs = if (sec <= 0L) 0L else now + sec * 1000L }
                    "expires" -> if (expiresMs == null) parseHttpDate(av)?.let { expiresMs = it }
                }
            }
            val item = Stored(name, value, domain, path, secure, expiresMs)
            if (expiresMs != null && expiresMs <= now) {
                if (cookies.remove(keyOf(item)) != null) changed = true
            } else {
                cookies[keyOf(item)] = item
                changed = true
            }
        }
        if (changed) persistLocked()
    }

    /** Parses `a=1; b=2` manual cookie string (e.g. from `cookie.setCookie` or WebView sync). */
    @Synchronized fun setManual(url: String, cookieString: String) {
        val uri = runCatching { URI(url) }.getOrNull() ?: return
        val host = uri.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return
        val exp = System.currentTimeMillis() + 30L * 24 * 3600 * 1000L
        var changed = false
        for (part in cookieString.split(';')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val name = part.substring(0, eq).trim()
            val value = part.substring(eq + 1).trim()
            if (name.isEmpty()) continue
            val item = Stored(name, value, host, "/", false, exp)
            cookies[keyOf(item)] = item
            changed = true
            bridge?.setCookie(url, "$name=$value; Path=/")
        }
        if (changed) persistLocked()
    }

    @Synchronized fun remove(url: String) {
        val uri = runCatching { URI(url) }.getOrNull() ?: return
        val host = uri.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return
        val it = cookies.entries.iterator()
        var changed = false
        while (it.hasNext()) {
            if (domainMatches(it.next().value.domain, host)) { it.remove(); changed = true }
        }
        if (changed) persistLocked()
        bridge?.removeCookiesForDomain(url)
    }

    @Synchronized fun clearForTest() {
        cookies.clear()
        file = null
        bridge = null
    }

    private fun persistLocked() {
        val target = file ?: return
        runCatching {
            target.parentFile?.mkdirs()
            val arr = JSONArray()
            for (c in cookies.values) {
                val o = JSONObject()
                    .put("name", c.name)
                    .put("value", c.value)
                    .put("domain", c.domain)
                    .put("path", c.path)
                    .put("secure", c.secure)
                c.expiresMs?.let { o.put("expiresMs", it) }
                arr.put(o)
            }
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(arr.toString())
            java.nio.file.Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun parseHttpDate(raw: String): Long? {
        val formats = arrayOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, dd-MMM-yyyy HH:mm:ss zzz",
            "EEE, dd-MMM-yy HH:mm:ss zzz",
            "EEE MMM dd HH:mm:ss yyyy",
        )
        for (fmt in formats) {
            runCatching {
                val sdf = SimpleDateFormat(fmt, Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("GMT")
                sdf.parse(raw)?.time
            }.getOrNull()?.let { return it }
        }
        return null
    }
}
