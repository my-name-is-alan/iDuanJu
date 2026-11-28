package app.iduanju.core.novel

import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.javascript.*
import java.nio.charset.Charset
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Source scripts get only explicit JSON host calls, never arbitrary Java classes, files or processes. */
internal class NovelScript(
    private val source: NovelSource,
    private val base: String,
    private val key: String = "",
    private val page: Int = 1,
) {
    companion object {
        @Volatile var storage: MutableMap<String, String> = ConcurrentHashMap()
        internal val bookURL = ThreadLocal<String?>()
        internal val bookInfo = ThreadLocal<JSONObject?>()
        /** Raw body of the page being parsed (Legado's JS `src`). */
        internal val src = ThreadLocal<String?>()
        internal val currentChapter = ThreadLocal<JSONObject?>()
        internal val currentRSSArticle = ThreadLocal<JSONObject?>()
        internal val currentBatch = ThreadLocal<BatchContentContext?>()
        internal val nextChapterURL = ThreadLocal<String?>()

        private val ttfCache = ConcurrentHashMap<String, QueryTTF>()
        private val ttfHandles = ConcurrentHashMap<Int, QueryTTF>()
        private val nextTtfId = java.util.concurrent.atomic.AtomicInteger(1)
        private val namedLocks = ConcurrentHashMap<String, ReentrantLock>()

        internal fun <T> withRSSArticle(article: JSONObject, block: () -> T): T {
            val old = currentRSSArticle.get(); currentRSSArticle.set(article)
            try { return block() } finally { currentRSSArticle.set(old) }
        }

        internal fun <T> withChapter(chapter: NovelChapter, next: String? = null, block: () -> T): T {
            val old = currentChapter.get(); val oldNext = nextChapterURL.get()
            currentChapter.set(
                JSONObject()
                    .put("title", chapter.title)
                    .put("url", chapter.url)
                    .put("index", chapter.index)
                    .put("isVolume", chapter.isVolume)
                    .put("isVip", chapter.isVip)
                    .put("isPay", chapter.isPay)
                    .put("updateTime", chapter.updateTime)
            )
            nextChapterURL.set(next)
            try { return block() } finally { currentChapter.set(old); nextChapterURL.set(oldNext) }
        }

        internal fun <T> withBatch(ctx: BatchContentContext, block: () -> T): T {
            val old = currentBatch.get(); currentBatch.set(ctx)
            try { return block() } finally { currentBatch.set(old) }
        }

        internal fun getSourceKey(sourceId: String, key: String): String = storage["$sourceId|$key"].orEmpty()
        internal fun putSourceKey(sourceId: String, key: String, value: String) { put("$sourceId|$key", value) }

        @Synchronized internal fun updateSourceState(source: String, key: String, transform: (String?) -> String) {
            val scope = "$source|$key"; put(scope, transform(storage[scope]))
        }

        private var stateFile: java.io.File? = null
        @Synchronized fun configure(root: java.io.File) {
            val file = java.io.File(root, "novel-script-state.json")
            if (stateFile == file) return
            stateFile = file
            val saved = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
            storage = ConcurrentHashMap<String, String>().apply { for (k in saved.keys()) put(k, saved.getString(k)) }
        }

        @Synchronized private fun put(k: String, v: String) {
            storage[k] = v
            stateFile?.let { file ->
                file.parentFile?.mkdirs()
                val tmp = java.io.File(file.parentFile, file.name + ".tmp")
                tmp.writeText(JSONObject(storage.toMap()).toString())
                java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }

        internal fun <T> withBook(url: String?, info: JSONObject? = null, block: () -> T): T {
            val prevUrl = bookURL.get(); val prevInfo = bookInfo.get()
            bookURL.set(url)
            if (info != null) bookInfo.set(info)
            try { return block() } finally { bookURL.set(prevUrl); bookInfo.set(prevInfo) }
        }

        internal fun setVariable(source: String, key: String, value: String) {
            val scope = bookURL.get()?.let { "$source|book|$it|$key" } ?: "$source|$key"
            put(scope, value)
        }

        internal fun getVariable(source: String, key: String): String =
            bookURL.get()?.let { storage["$source|book|$it|$key"] } ?: storage["$source|$key"].orEmpty()

        internal fun exploreInfo(source: String): JSONObject =
            runCatching { JSONObject(storage["$source|__exploreInfoMap"] ?: "{}") }.getOrDefault(JSONObject())

        @Synchronized internal fun mergeExploreInfo(source: String, initial: JSONObject, changed: JSONObject) {
            val current = exploreInfo(source); var dirty = false
            (initial.keys().asSequence().toSet() + changed.keys().asSequence().toSet()).forEach { k ->
                if (initial.has(k) != changed.has(k) || initial.opt(k) != changed.opt(k)) {
                    if (changed.has(k)) current.put(k, changed.get(k)) else current.remove(k); dirty = true
                }
            }
            if (dirty) put("$source|__exploreInfoMap", current.toString())
        }

        private val libraries = ConcurrentHashMap<String, String>()
        internal fun clearLibraries() { libraries.clear() }

        /** Legado also accepts jsLib as a JSON map `{"name": "https://…/lib.js"}`: download once, run before the source code. */
        internal fun libraryCode(lib: String): String {
            val t = lib.trim()
            if (!t.startsWith("{") || !t.endsWith("}")) return lib
            val map = runCatching { JSONObject(t) }.getOrNull() ?: return lib
            if (map.length() == 0 || map.keys().asSequence().any { map.opt(it) !is String }) return lib
            return map.keys().asSequence().sorted().joinToString("\n;\n") { k ->
                val v = map.getString(k).trim()
                if (!v.startsWith("http://") && !v.startsWith("https://")) v
                else libraries.getOrPut(v) {
                    val r = NovelSources.fetchBytes(v, "GET", null, byteArrayOf())
                    require(r.status in 200..299) { "jsLib 下载失败：HTTP ${r.status}" }
                    require(r.bytes.size < 4 shl 20) { "jsLib 脚本过大" }
                    String(r.bytes, Charsets.UTF_8)
                }
            }
        }

        private val prelude by lazy { NovelScript::class.java.getResourceAsStream("/novel-prelude.js")!!.bufferedReader().use { it.readText() } }
        private fun jsonValue(value: Any?): String = JSONArray().put(value ?: JSONObject.NULL).toString().let { it.substring(1, it.length - 1) }

        internal fun registerTtf(ttf: QueryTTF): Int {
            if (ttfHandles.size > 64) ttfHandles.clear()
            val id = nextTtfId.getAndIncrement()
            ttfHandles[id] = ttf
            return id
        }
    }

    var interactive = false
    var srcText: String? = null
    private var content = ""
    private val handles = ArrayList<org.jsoup.nodes.Element>()
    private val heldLocks = ArrayList<ReentrantLock>()
    private val deadline = System.nanoTime() + if (NovelSources.inBatchSearch.get() == true) 6_000_000_000L else 20_000_000_000L

    private val factory = object : ContextFactory() {
        override fun makeContext(): Context = super.makeContext().apply {
            languageVersion = Context.VERSION_ES6; optimizationLevel = -1; instructionObserverThreshold = 10_000
            setClassShutter { false }
        }
        override fun observeInstructionCount(cx: Context, count: Int) {
            if (Thread.currentThread().isInterrupted || System.nanoTime() > deadline) throw IllegalStateException("书源脚本超时")
        }
    }

    fun eval(code: String, result: Any? = null, extraBindings: Map<String, Any?> = emptyMap()): Any? {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("书源脚本已取消")
        return try {
        factory.call(ContextAction { cx ->
            val scope = cx.initSafeStandardObjects()
            val nodeList = (result as? List<*>)?.takeIf { l -> l.isNotEmpty() && l.all { it is org.jsoup.nodes.Element } }?.map { it as org.jsoup.nodes.Element }
            content = nodeList?.joinToString("\n") { it.outerHtml() } ?: result?.toString() ?: ""
            val host = object : BaseFunction() {
                override fun call(context: Context, s: Scriptable, thisObj: Scriptable, args: Array<out Any>): Any {
                    val method = Context.toString(args.getOrNull(0))
                    val raw = args.getOrNull(1)?.let { Context.toString(it) } ?: "{}"
                    val a = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
                    try {
                        val value = invoke(method, a)
                        return jsonValue(value)
                    } catch (e: Exception) {
                        throw Context.reportRuntimeError(e.message ?: "书源宿主调用失败")
                    }
                }
            }
            ScriptableObject.putProperty(scope, "__host", host)
            cx.evaluateString(scope, "var globalThis=this;\n" + compatible(prelude), "novel-host", 1, null)

            val initialExploreInfo = exploreInfo(source.id)
            val bookJson = JSONObject().apply {
                bookInfo.get()?.let { bi -> for (k in bi.keys()) put(k, bi.opt(k)) }
                if (!has("bookUrl")) put("bookUrl", bookURL.get().orEmpty())
                if (!has("origin")) put("origin", source.id)
                if (!has("originName")) put("originName", source.name)
            }
            val globals = StringBuilder()
                .append("var source=").append(source.json).append("; __wrapSource(source);")
                .append(" var baseUrl=").append(JSONObject.quote(base)).append(';')
                .append(" var key=").append(JSONObject.quote(key)).append(';')
                .append(" var page=").append(page).append(';')
                .append(" var book=__wrapBook(").append(bookJson).append(");")
                .append(" var result=").append(if (nodeList != null) "__nodeList(" + JSONArray(nodeList.map { handle(it) }) + ")" else jsonValue(result)).append(';')
                .append(" var src=").append(JSONObject.quote(srcText ?: src.get().orEmpty())).append(';')
            if (result is JSONObject || result is JSONArray) {
                globals.append("if(result && typeof result==='object' && !Object.prototype.hasOwnProperty.call(result,'toString')){Object.defineProperty(result,'toString',{value:function(){return JSON.stringify(this)},enumerable:false,configurable:true})};\n")
            }
            val chapterData = currentChapter.get()?.toString() ?: "null"
            val nextData = nextChapterURL.get()?.let { JSONObject.quote(it) } ?: "null"
            globals.append("var chapter=__wrapChapter(").append(chapterData).append(");var title=chapter?chapter.title:null;var nextChapterUrl=").append(nextData).append(';')
            val rssData = currentRSSArticle.get()?.toString() ?: "null"
            globals.append("var rssArticle=").append(rssData).append(";if(rssArticle){Object.keys(rssArticle).forEach(function(k){rssArticle['get'+k.charAt(0).toUpperCase()+k.slice(1)]=function(){return this[k]}});rssArticle.getVariable=function(k){return java.get(k||'__rssArticleVariable')}};")
            globals.append("var infoMap=").append(initialExploreInfo).append(";Object.defineProperties(infoMap,{get:{value:function(k){return Object.prototype.hasOwnProperty.call(this,k)?this[k]:null}},put:{value:function(k,v){var old=this[k];this[k]=String(v);return old==null?null:old}}});")
            for ((ek, ev) in extraBindings) {
                globals.append("var ").append(ek).append('=').append(jsonValue(ev)).append(';')
            }
            cx.evaluateString(scope, globals.toString(), "novel-context", 1, null)

            val lib = libraryCode(source.json.optString("jsLib"))
            if (lib.isNotBlank()) cx.evaluateString(scope, compatible(lib), "source-library", 1, null)
            val value = cx.evaluateString(scope, compatible(code), "source-rule", 1, null)
            ScriptableObject.putProperty(scope, "__result", value)
            val json = cx.evaluateString(scope, "JSON.stringify(__result === undefined ? null : __result)", "source-result", 1, null)
            val info = cx.evaluateString(scope, "JSON.stringify(infoMap)", "source-filter-state", 1, null)
            mergeExploreInfo(source.id, initialExploreInfo, JSONObject(Context.toString(info)))
            val parsed = org.json.JSONTokener(Context.toString(json)).nextValue()
            if (parsed == JSONObject.NULL) null else parsed
        })
    } finally {
        for (l in heldLocks.asReversed()) runCatching { if (l.isHeldByCurrentThread) l.unlock() }
        heldLocks.clear()
    }
    }

    private fun compatible(code: String) = code
        .replace(Regex("(\\b(?:map|forEach|filter)\\s*\\(\\s*)(\\[[A-Za-z_$][A-Za-z0-9_$,\\s]*\\])\\s*=>"), "$1($2)=>")
        .replace(Regex("for\\s*\\(const\\s+"), "for (var ")

    private fun scope(k: String) = source.id + "|" + k
    private fun variableScope(k: String) = bookURL.get()?.let { source.id + "|book|" + it + "|" + k } ?: scope(k)
    private fun bytes(a: Any?): ByteArray = when (a) {
        is JSONArray -> ByteArray(a.length()) { a.optInt(it).toByte() }
        null, JSONObject.NULL -> byteArrayOf()
        else -> a.toString().toByteArray()
    }
    private fun jsonBytes(b: ByteArray) = JSONArray(b.map { it.toInt() })
    private fun handle(e: org.jsoup.nodes.Element): Int { handles += e; return handles.size - 1 }
    private fun elements(h: Any?): List<org.jsoup.nodes.Element> = (h as? JSONArray)?.let { arr ->
        (0 until arr.length()).mapNotNull { handles.getOrNull(arr.optInt(it, -1)) }
    }.orEmpty()

    private fun invoke(name: String, a: JSONObject): Any? {
        fun str(k: String) = a.optString(k).takeUnless { it == "null" } ?: ""
        when (name) {
            "getVar" -> return storage[variableScope(str("k"))] ?: storage[scope(str("k"))] ?: ""
            "putVar" -> { put(variableScope(str("k")), str("v")); return str("v") }
            "sourceGet" -> return storage[scope(str("k"))] ?: ""
            "sourcePut" -> { put(scope(str("k")), str("v")); return str("v") }
            "cacheGet" -> {
                val k = str("k")
                val exp = storage[scope("cacheExp.$k")]?.toLongOrNull() ?: 0L
                if (exp > 0L && System.currentTimeMillis() > exp) { put(scope("cache.$k"), ""); return null }
                return storage[scope("cache.$k")]?.takeIf { it.isNotEmpty() } ?: storage[scope(k)]?.takeIf { it.isNotEmpty() }
            }
            "cachePut" -> {
                val k = str("k"); val v = a.opt("v")?.takeIf { it != JSONObject.NULL }?.toString().orEmpty()
                val sec = a.optLong("sec", 0L)
                put(scope("cache.$k"), v)
                put(scope("cacheExp.$k"), if (sec > 0L && v.isNotEmpty()) (System.currentTimeMillis() + sec * 1000L).toString() else "0")
                return v
            }
            "loginInfo" -> return storage[scope("loginInfo")] ?: "{}"
            "loginHeader" -> return storage[scope("loginHeader")] ?: "{}"
            "putLoginInfo", "putLoginHeader" -> {
                put(scope(if (name == "putLoginInfo") "loginInfo" else "loginHeader"), str("v"))
                return true
            }
            "resolveUrl" -> return NovelSources.absolute(str("url"), str("base").ifBlank { base })
            "ajax" -> {
                val timeout = a.optLong("timeout", 0L).takeIf { it > 0L }
                val rawUrl = str("url")
                val effective = if (timeout != null && !rawUrl.contains(",{")) "$rawUrl,{\"timeout\":$timeout}" else rawUrl
                return NovelSources.fetch(effective, source, key, page)
            }
            "ajaxAll" -> {
                val arr = a.optJSONArray("urls") ?: return JSONArray()
                val urls = (0 until arr.length()).map { arr.optString(it) }
                val timeout = a.optLong("timeout", 0L).takeIf { it > 0L }
                val skipRate = a.optBoolean("skipRateLimit", false)
                val pool = Executors.newFixedThreadPool(minOf(4, urls.size.coerceAtLeast(1)))
                try {
                    val futures = urls.map { u ->
                        pool.submit<JSONObject> {
                            runCatching {
                                val eff = if (timeout != null && !u.contains(",{")) "$u,{\"timeout\":$timeout}" else u
                                val r = NovelSources.fetchResponse(eff, source, key, page, allowHttpError = true, skipRateLimit = skipRate)
                                JSONObject().put("body", r.text).put("url", r.url).put("status", r.status).put("headers", JSONObject(r.headers))
                            }.getOrElse { e ->
                                JSONObject().put("body", "").put("url", u).put("status", 500).put("error", e.message.orEmpty())
                            }
                        }
                    }
                    return JSONArray(futures.map { it.get() })
                } finally { pool.shutdownNow() }
            }
            "http.bytes" -> {
                val response = NovelSources.fetchBytes(str("url"), str("method"), a.optJSONObject("headers"), bytes(a.opt("b")), source = source)
                return JSONObject().put("status", response.status).put("headers", JSONObject(response.headers)).put("b", jsonBytes(response.bytes))
            }
            "connect" -> {
                val opts = JSONObject()
                val method = str("method").ifBlank { "GET" }
                opts.put("method", method)
                val body = str("body")
                if (body.isNotEmpty()) opts.put("body", body)
                a.optJSONObject("headers")?.let { opts.put("headers", it) }
                a.optLong("timeout", 0L).takeIf { it > 0L }?.let { opts.put("timeout", it) }
                val url = str("url")
                val request = if (method == "GET" && body.isEmpty() && !a.has("headers") && a.optLong("timeout", 0L) <= 0L) url else "$url,$opts"
                val response = NovelSources.fetchResponse(request, source, key, page, allowHttpError = true)
                return JSONObject()
                    .put("body", response.text)
                    .put("url", response.url)
                    .put("status", response.status)
                    .put("headers", JSONObject(response.headers))
                    .put("bytes", jsonBytes(response.rawBytes))
            }
            "random.bytes" -> {
                val n = a.optInt("n", -1); require(n in 0..8192) { "随机字节长度无效" }
                return jsonBytes(ByteArray(n).also { java.security.SecureRandom().nextBytes(it) })
            }
            "gzip" -> {
                val input = bytes(a.opt("b")); require(input.size <= 16 * 1024 * 1024) { "压缩输入过大" }
                val output = java.io.ByteArrayOutputStream()
                if (a.optBoolean("encode")) java.util.zip.GZIPOutputStream(output).use { it.write(input) }
                else java.util.zip.GZIPInputStream(input.inputStream()).use { stream ->
                    val buffer = ByteArray(16384)
                    while (true) {
                        val n = stream.read(buffer); if (n < 0) break
                        require(output.size() + n <= 16 * 1024 * 1024) { "GZIP 输出过大" }
                        output.write(buffer, 0, n)
                    }
                }
                return jsonBytes(output.toByteArray())
            }
            "getString", "getStringList" -> {
                val input = a.optString("content").takeUnless { it.isBlank() || it == "null" } ?: content
                val value = NovelSources.ruleValue(input, str("rule"), source, base, key, page)
                return if (name == "getString") value else JSONArray(value.lines().filter { it.isNotEmpty() })
            }
            "setContent" -> { content = str("content"); return null }
            "md5" -> return MessageDigest.getInstance("MD5").digest(str("s").toByteArray()).joinToString("") { "%02x".format(it) }
            "uuid" -> return java.util.UUID.randomUUID().toString()
            "ua" -> return NovelSources.userAgent
            "androidId" -> return MessageDigest.getInstance("SHA-256").digest(source.id.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
            "b64e" -> return Base64.getEncoder().encodeToString(str("s").toByteArray())
            "b64.enc" -> return Base64.getEncoder().encodeToString(bytes(a.opt("b")))
            "b64d", "b64.dec" -> {
                val rawB64 = str("s").replace('-', '+').replace('_', '/').filterNot { it.isWhitespace() }
                val padded = rawB64 + "=".repeat((4 - rawB64.length % 4) % 4)
                val b = Base64.getDecoder().decode(padded)
                return if (name == "b64d") String(b, Charset.forName(str("cs").ifBlank { "UTF-8" })) else jsonBytes(b)
            }
            "bytes.enc" -> return jsonBytes(str("s").toByteArray(Charset.forName(str("cs").ifBlank { "UTF-8" })))
            "bytes.dec" -> return String(bytes(a.opt("b")), Charset.forName(str("cs").ifBlank { "UTF-8" }))
            "hex.dec" -> return jsonBytes(str("s").filterNot { it.isWhitespace() }.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            "hex.enc" -> return bytes(a.opt("b")).joinToString("") { "%02x".format(it) }
            "digest" -> {
                val alg = str("alg").uppercase().replace("SHA256", "SHA-256").replace("SHA512", "SHA-512").replace("SHA1", "SHA-1")
                return jsonBytes(MessageDigest.getInstance(alg).digest(bytes(a.opt("b"))))
            }
            "hmac" -> {
                val rawAlg = str("alg").ifBlank { "HmacSHA256" }
                val alg = when (rawAlg.uppercase().replace("-", "")) {
                    "HMACMD5", "MD5" -> "HmacMD5"
                    "HMACSHA1", "SHA1" -> "HmacSHA1"
                    "HMACSHA256", "SHA256" -> "HmacSHA256"
                    "HMACSHA512", "SHA512" -> "HmacSHA512"
                    else -> rawAlg
                }
                val mac = Mac.getInstance(alg)
                mac.init(SecretKeySpec(bytes(a.opt("key")), alg))
                return jsonBytes(mac.doFinal(bytes(a.opt("b"))))
            }
            "sign.sign" -> {
                val alg = str("alg").ifBlank { "SHA256withRSA" }
                val priv = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes(a.opt("priv"))))
                val sig = Signature.getInstance(alg)
                sig.initSign(priv)
                sig.update(bytes(a.opt("b")))
                return jsonBytes(sig.sign())
            }
            "sign.verify" -> {
                val alg = str("alg").ifBlank { "SHA256withRSA" }
                val pub = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(bytes(a.opt("pub"))))
                val sig = Signature.getInstance(alg)
                sig.initVerify(pub)
                sig.update(bytes(a.opt("b")))
                return sig.verify(bytes(a.opt("sig")))
            }
            "cipher" -> {
                val transformation = str("t").replace(Regex("(?i)pkcs7padding"), "PKCS5Padding")
                val cipher = Cipher.getInstance(transformation)
                val secret = SecretKeySpec(bytes(a.opt("key")), transformation.substringBefore('/'))
                val iv = bytes(a.opt("iv"))
                if (iv.isEmpty()) cipher.init(a.optInt("mode"), secret)
                else if (transformation.contains("/GCM/", ignoreCase = true)) cipher.init(a.optInt("mode"), secret, GCMParameterSpec(128, iv))
                else cipher.init(a.optInt("mode"), secret, IvParameterSpec(iv))
                return jsonBytes(cipher.doFinal(bytes(a.opt("b"))))
            }
            "rsa.dec", "rsa.enc" -> {
                val factory = KeyFactory.getInstance("RSA")
                val privateBytes = bytes(a.opt("priv"))
                val rsaKey = if (privateBytes.isNotEmpty()) factory.generatePrivate(PKCS8EncodedKeySpec(privateBytes))
                else factory.generatePublic(X509EncodedKeySpec(bytes(a.opt("key"))))
                val size = ((rsaKey as java.security.interfaces.RSAKey).modulus.bitLength() + 7) / 8
                val encrypt = name == "rsa.enc"
                val rawPublic = !encrypt && privateBytes.isEmpty()
                val cipher = Cipher.getInstance(if (rawPublic) "RSA/ECB/NoPadding" else "RSA/ECB/PKCS1Padding")
                cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, rsaKey)
                val input = bytes(a.opt("b")); val result = java.io.ByteArrayOutputStream()
                val step = if (encrypt) size - 11 else size
                require(encrypt || input.size % size == 0) { "RSA 数据长度不正确" }
                for (at in input.indices step step) {
                    val block = cipher.doFinal(input, at, minOf(step, input.size - at))
                    if (rawPublic) {
                        val sep = (2 until block.size).firstOrNull { block[it] == 0.toByte() }
                        val start = if (block.size > 2 && block[0] == 0.toByte() && block[1].toInt() in 1..2 && sep != null && sep >= 10) sep + 1
                        else block.indexOfFirst { it != 0.toByte() }.let { if (it < 0) block.size else it }
                        result.write(block, start, block.size - start)
                    } else result.write(block)
                }
                return jsonBytes(result.toByteArray())
            }
            "inflate" -> {
                val inflater = java.util.zip.Inflater(a.optBoolean("nowrap"))
                val out = java.io.ByteArrayOutputStream()
                try {
                    inflater.setInput(bytes(a.opt("b")))
                    val buffer = ByteArray(8192)
                    while (!inflater.finished()) {
                        val n = inflater.inflate(buffer)
                        check(n > 0 || inflater.finished()) { "压缩数据截断或需要字典" }
                        require(out.size() + n <= 16 * 1024 * 1024) { "解压内容过大" }
                        out.write(buffer, 0, n)
                    }
                    return jsonBytes(out.toByteArray())
                } finally { inflater.end() }
            }
            // QueryTTF font anti-scraping
            "ttf.load" -> {
                val useCache = a.optBoolean("cache", true)
                val b64 = str("b64")
                val srcArg = str("src")
                val cacheKey = when {
                    b64.isNotEmpty() -> "b64:${MessageDigest.getInstance("MD5").digest(b64.toByteArray()).joinToString("") { "%02x".format(it) }}"
                    srcArg.isNotEmpty() -> "src:$srcArg"
                    else -> null
                }
                val ttf = if (useCache && cacheKey != null) {
                    ttfCache.getOrPut(cacheKey) { QueryTTF(loadTtfBytes(a, b64, srcArg)) }
                } else {
                    QueryTTF(loadTtfBytes(a, b64, srcArg))
                }
                return registerTtf(ttf)
            }
            "ttf.glyfIdByUni" -> return ttfHandles[a.optInt("id")]?.getGlyfIdByUnicode(a.optInt("u")) ?: 0
            "ttf.uniByGlyfId" -> return ttfHandles[a.optInt("id")]?.getUnicodeByGlyfId(a.optInt("g")) ?: 0
            "ttf.glyfById" -> return ttfHandles[a.optInt("id")]?.getGlyfById(a.optInt("g"))
            "ttf.glyfByUni" -> return ttfHandles[a.optInt("id")]?.getGlyfByUnicode(a.optInt("u"))
            "ttf.uniByGlyf" -> return ttfHandles[a.optInt("id")]?.getUnicodeByGlyf(str("g")) ?: 0
            "ttf.codeByGlyf" -> return ttfHandles[a.optInt("id")]?.getCodeByGlyf(str("g")) ?: ""
            "ttf.replace" -> return QueryTTF.replaceFont(
                str("text"),
                ttfHandles[a.optInt("err")],
                ttfHandles[a.optInt("ok")],
                a.optBoolean("filter", false),
            )
            // Sandboxed files / script cache / ZIP
            "file.importScript" -> return LegadoFiles.importScript(str("path"), source)
            "file.cacheFile" -> return LegadoFiles.cacheFile(str("url"), source, a.optInt("sec", 0))
            "file.downloadFile" -> return LegadoFiles.downloadFile(str("url"), source)
            "file.read" -> return LegadoFiles.readFile(source.id, str("path"))?.let(::jsonBytes)
            "file.readTxt" -> return LegadoFiles.readTxtFile(source.id, str("path"), str("cs").ifBlank { "UTF-8" })
            "file.delete" -> return LegadoFiles.deleteFile(source.id, str("path"))
            "file.exists" -> return runCatching { LegadoFiles.safeFile(source.id, str("path")).exists() }.getOrDefault(false)
            "file.unzip" -> return LegadoFiles.unzipFile(source.id, str("path"))
            "file.zipStr" -> return LegadoFiles.getZipStringContent(str("url"), str("path"), source, str("cs").ifBlank { "UTF-8" })
            "file.zipBytes" -> return LegadoFiles.getZipEntryBytes(str("url"), str("path"), source)?.let(::jsonBytes)
            "file.txtInFolder" -> return LegadoFiles.getTxtInFolder(source.id, str("path"))
            // Jsoup handles
            "jsoup.parse" -> return handle(org.jsoup.Jsoup.parse(str("html"), base))
            "jsoup.select" -> {
                val hits = elements(a.opt("h")).flatMap { it.select(str("q")).toList() }
                return JSONArray((if (a.optBoolean("first")) hits.take(1) else hits).map { handle(it) })
            }
            "jsoup.children" -> return JSONArray(elements(a.opt("h")).flatMap { it.children().toList() }.map { handle(it) })
            "jsoup.rule" -> return JSONArray(NovelSources.nodes(content, str("rule"), source, base, key, page).filterIsInstance<org.jsoup.nodes.Element>().map { handle(it) })
            "jsoup.text" -> return elements(a.opt("h")).map { it.text() }.filter { it.isNotEmpty() }.joinToString(" ")
            "jsoup.ownText" -> return elements(a.opt("h")).joinToString(" ") { it.ownText() }
            "jsoup.textNodes" -> return JSONArray(elements(a.opt("h")).flatMap { e -> e.textNodes().map { it.text().trim() }.filter { it.isNotEmpty() } })
            "jsoup.attr" -> {
                val k = str("k"); val abs = k.startsWith("abs:"); val attrName = k.removePrefix("abs:")
                val v = elements(a.opt("h")).map { it.attr(attrName) }.firstOrNull { it.isNotEmpty() }.orEmpty()
                return if (abs && v.isNotEmpty()) runCatching { NovelSources.absolute(v, base) }.getOrDefault(v) else v
            }
            "jsoup.html" -> return elements(a.opt("h")).joinToString("\n") { it.html() }
            "jsoup.outer" -> return elements(a.opt("h")).joinToString("\n") { it.outerHtml() }
            "encodeURI" -> return java.net.URLEncoder.encode(str("s"), str("cs").ifBlank { "UTF-8" }).replace("+", "%20")
            "htmlFormat" -> return NovelSources.cleanText(str("s"))
            "headers" -> return NovelSources.headers(source)
            // Cookies
            "cookie" -> {
                val targetUrl = NovelSources.absolute(str("url").ifBlank { source.id }, source.id)
                val keyArg = str("k")
                return if (keyArg.isNotEmpty()) LegadoCookies.getKey(targetUrl, keyArg) else LegadoCookies.cookieHeader(targetUrl).orEmpty()
            }
            "cookieSet" -> {
                val targetUrl = NovelSources.absolute(str("url").ifBlank { source.id }, source.id)
                LegadoCookies.setManual(targetUrl, str("c"))
                return null
            }
            "cookieRemove" -> {
                val targetUrl = NovelSources.absolute(str("url").ifBlank { source.id }, source.id)
                LegadoCookies.remove(targetUrl)
                return null
            }
            "convert" -> {
                val s = str("s")
                return if (str("to").equals("Hans-Hant", true)) LegadoChinese.s2t(s) else LegadoChinese.t2s(s)
            }
            "toNumChapter" -> return LegadoChinese.toNumChapter(str("s")).orEmpty()
            "log" -> {
                LegadoTools.emitDebug("log", str("s"))
                return ""
            }
            "lock.acquire" -> {
                val lock = namedLocks.computeIfAbsent("${source.id}|${str("k")}") { ReentrantLock() }
                lock.lock()
                heldLocks += lock
                return true
            }
            "lock.release" -> {
                val lock = namedLocks["${source.id}|${str("k")}"]
                if (lock != null && lock.isHeldByCurrentThread) {
                    lock.unlock()
                    heldLocks.remove(lock)
                }
                return true
            }
            "tick" -> return LegadoRateLimit.tick(source.id, str("name"))
            "source.putConcurrent" -> { LegadoRateLimit.putOverride(source.id, str("v")); return true }
            "refresh" -> {
                if (str("kind") == "jsLib") clearLibraries()
                return true
            }
            "batch.cacheContent" -> {
                val batch = currentBatch.get() ?: return false
                return batch.put(str("chapter"), a.optInt("index", -1), str("content"))
            }
            "webView" -> {
                val loader = LegadoWebView.backstage ?: error("当前运行环境未提供 WebView 引擎")
                val rawUrl = str("url").ifBlank { base }
                val absUrl = NovelSources.absolute(rawUrl, source.id)
                val htmlArg = a.opt("html")?.takeIf { it != JSONObject.NULL }?.toString()
                val jsArg = a.opt("js")?.takeIf { it != JSONObject.NULL }?.toString()
                val srcRegex = a.opt("sourceRegex")?.takeIf { it != JSONObject.NULL }?.toString()?.takeIf { it.isNotBlank() }
                val ovRegex = a.opt("overrideUrlRegex")?.takeIf { it != JSONObject.NULL }?.toString()?.takeIf { it.isNotBlank() }
                val hdrs = NovelSources.headers(source).let { h -> h.keys().asSequence().associateWith { h.get(it).toString() } }
                val resp = loader.load(
                    BackstageRequest(
                        url = absUrl,
                        headers = hdrs,
                        javaScript = jsArg,
                        sourceRegex = srcRegex,
                        delayMs = 0L,
                        timeoutMs = 30_000L,
                        html = htmlArg,
                        overrideUrlRegex = ovRegex,
                        useCookies = source.enabledCookieJar,
                    )
                )
                return resp.text
            }
            "browser" -> {
                require(interactive) { "该操作需要在书源「登录」页内进行" }
                val launcher = LegadoWebView.browser ?: error("当前运行环境未提供内置浏览器")
                val targetUrl = NovelSources.absolute(str("url").ifBlank { source.id }, source.id)
                val htmlArg = a.opt("html")?.takeIf { it != JSONObject.NULL }?.toString()
                val title = str("title").ifBlank { source.name }
                val ua = NovelSources.headers(source).optString("User-Agent").ifBlank { NovelSources.userAgent }
                val wait = a.optBoolean("wait", false)
                val body = launcher.open(targetUrl, htmlArg, title, ua, wait)
                if (wait && a.optBoolean("refetch", true) && body.isEmpty() && targetUrl.startsWith("http")) {
                    return runCatching { NovelSources.fetch(targetUrl, source, key, page) }.getOrDefault("")
                }
                return body
            }
            else -> error("暂不支持的书源功能：$name")
        }
    }

    private fun loadTtfBytes(a: JSONObject, b64: String, srcArg: String): ByteArray {
        if (b64.isNotEmpty()) {
            val cleaned = b64.substringAfter("base64,", b64).filterNot { it.isWhitespace() }
            return Base64.getDecoder().decode(cleaned)
        }
        val rawArr = a.optJSONArray("b")
        if (rawArr != null) return bytes(rawArr)
        if (srcArg.startsWith("http://", true) || srcArg.startsWith("https://", true)) {
            return LegadoFiles.fetchCached(srcArg, source, 3600)
        }
        // Fallback: might be raw base64 passed directly to queryTTF(b64)
        val cleaned = srcArg.substringAfter("base64,", srcArg).filterNot { it.isWhitespace() }
        return Base64.getDecoder().decode(cleaned)
    }
}
