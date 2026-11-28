package app.iduanju.core.novel

import org.json.JSONObject

/**
 * Full Legado `AnalyzeUrl.UrlOption` representation and lenient parser.
 * Supports strict JSON as well as unquoted keys, single quotes, typographic quotes,
 * and trailing options outside `{{ ... }}` templates.
 */
data class LegadoUrlOption(
    val method: String = "GET",
    val charset: String? = null,
    val headers: JSONObject? = null,
    val body: String? = null,
    val origin: String? = null,
    val retry: Int = 0,
    val type: String? = null,
    val webView: Boolean = false,
    val webJs: String? = null,
    val webViewDelayTime: Long = 0L,
    val timeoutMs: Long? = null,
    val followRedirects: Boolean? = null,
    val dnsIp: String? = null,
    val js: String? = null,
    val bodyJs: String? = null,
    val raw: JSONObject = JSONObject(),
)

object LegadoUrlOptions {
    /**
     * Splits `url,{...}` finding the LAST `,{` outside `{{ ... }}` templates.
     * Matches iOS `LegadoURL.optionsStart` and upstream AnalyzeUrl behavior.
     */
    fun split(rule: String): Pair<String, String?> {
        var depth = 0
        var lastComma = -1
        var i = 0
        while (i < rule.length) {
            if (rule.startsWith("{{", i)) { depth++; i += 2; continue }
            if (depth > 0 && rule.startsWith("}}", i)) { depth--; i += 2; continue }
            if (depth == 0 && rule[i] == ',') {
                var j = i + 1
                while (j < rule.length && rule[j].isWhitespace()) j++
                if (j < rule.length && rule[j] == '{') lastComma = i
            }
            i++
        }
        if (lastComma < 0) return rule to null
        return rule.substring(0, lastComma) to rule.substring(lastComma + 1).trim()
    }

    /** Parses lenient JSON / JS object literal options safely without arbitrary class access. */
    fun parseObject(raw: String?): JSONObject {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty() || !t.startsWith("{")) return JSONObject()
        runCatching { return JSONObject(t) }
        val normalized = normalizeLenientJson(t)
        runCatching { return JSONObject(normalized) }
        // Fallback: evaluate pure object literal inside locked-down Rhino (no Java classes).
        runCatching {
            val evaluated = evalPureObjectLiteral(normalized)
            if (evaluated != null) return evaluated
        }
        return JSONObject()
    }

    fun parse(raw: String?): LegadoUrlOption {
        val obj = parseObject(raw)
        if (obj.length() == 0) return LegadoUrlOption()
        val method = obj.optString("method").takeIf { it.isNotBlank() && it != "null" }?.uppercase() ?: "GET"
        val charset = obj.optString("charset").takeIf { it.isNotBlank() && it != "null" }
        val headers: JSONObject? = obj.optJSONObject("headers") ?: obj.optString("headers")
            .takeIf { it.isNotBlank() && it != "null" }
            ?.let { parseObject(it).takeIf { h -> h.length() > 0 } }
        val body: String? = when (val b = obj.opt("body")) {
            null, JSONObject.NULL -> null
            is String -> b
            else -> b.toString()
        }
        val origin = obj.optString("origin").takeIf { it.isNotBlank() && it != "null" }
        val retry = obj.optString("retry").toIntOrNull()?.coerceIn(0, 5)
            ?: obj.optInt("retry", 0).coerceIn(0, 5)
        val type = obj.optString("type").takeIf { it.isNotBlank() && it != "null" }
        val webView = LegadoWebView.useWebView(obj.opt("webView"))
        val webJs = obj.optString("webJs").takeIf { it.isNotBlank() && it != "null" }
        val webViewDelayTime = obj.optString("webViewDelayTime").toLongOrNull()?.coerceIn(0L, 30_000L)
            ?: obj.optLong("webViewDelayTime", 0L).coerceIn(0L, 30_000L)
        val timeoutMs = obj.opt("timeout")?.takeIf { it != JSONObject.NULL }?.toString()?.trim()?.toLongOrNull()
            ?.takeIf { it > 0L }?.coerceIn(1_000L, 120_000L)
        val followRedirects: Boolean? = when (val fr = obj.opt("followRedirects")) {
            null, JSONObject.NULL -> null
            is Boolean -> fr
            else -> when (fr.toString().trim().lowercase()) {
                "false", "0", "no" -> false
                "true", "1", "yes" -> true
                else -> null
            }
        }
        val dnsIp = obj.optString("dnsIp").takeIf { it.isNotBlank() && it != "null" }
        val js = obj.optString("js").takeIf { it.isNotBlank() && it != "null" }
        val bodyJs = obj.optString("bodyJs").takeIf { it.isNotBlank() && it != "null" }
        return LegadoUrlOption(
            method = method,
            charset = charset,
            headers = headers,
            body = body,
            origin = origin,
            retry = retry,
            type = type,
            webView = webView,
            webJs = webJs,
            webViewDelayTime = webViewDelayTime,
            timeoutMs = timeoutMs,
            followRedirects = followRedirects,
            dnsIp = dnsIp,
            js = js,
            bodyJs = bodyJs,
            raw = obj,
        )
    }

    private fun normalizeLenientJson(input: String): String {
        val s = input
            .replace('\u201C', '"')
            .replace('\u201D', '"')
            .replace('\u2018', '\'')
            .replace('\u2019', '\'')
        return NovelSources.escapeRawControls(s)
    }

    private fun evalPureObjectLiteral(literal: String): JSONObject? {
        val factory = object : org.mozilla.javascript.ContextFactory() {
            override fun makeContext(): org.mozilla.javascript.Context = super.makeContext().apply {
                languageVersion = org.mozilla.javascript.Context.VERSION_ES6
                optimizationLevel = -1
                instructionObserverThreshold = 5_000
                setClassShutter { false }
            }
            private val deadline = System.nanoTime() + 1_000_000_000L
            override fun observeInstructionCount(cx: org.mozilla.javascript.Context, count: Int) {
                if (System.nanoTime() > deadline) throw IllegalStateException("选项解析超时")
            }
        }
        return factory.call(org.mozilla.javascript.ContextAction { cx ->
            val scope = cx.initSafeStandardObjects()
            val text = cx.evaluateString(scope, "JSON.stringify(($literal))", "url-options", 1, null)
            JSONObject(org.mozilla.javascript.Context.toString(text))
        })
    }
}
