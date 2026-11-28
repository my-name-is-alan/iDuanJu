package app.iduanju.core.novel

import org.json.JSONArray
import org.json.JSONObject

/**
 * Legado source login engine supporting:
 * 1. Classic `loginUrl` (web login URL or JS functions) + `loginUi` v1 (`text`/`password`/`select`/`toggle`/`button`)
 * 2. `LegadoTeam/legado` Login UI v2 dynamic state protocol (`{"version":2}` marker or JS source `loginUi(state)` + `loginAction(action, state, form)`).
 */
object LegadoLogin {
    data class Field(
        val name: String,
        val type: String,          // text / password / select / toggle / button / label
        val options: List<String> = emptyList(),
        val action: String? = null,
        val defaultValue: String = "",
        val value: String? = null,
        val key: String = name,
    )

    data class V2ActionResult(
        val stateJson: String? = null,
        val errors: Map<String, String> = emptyMap(),
        val loginJson: String? = null,
        val close: Boolean = false,
        val message: String? = null,
    )

    fun hasLogin(source: NovelSource): Boolean =
        source.json.optString("loginUrl").isNotBlank() ||
            source.json.optString("loginUi").isNotBlank() ||
            (source.isJsSource && LegadoJsSource.hasLogin(source))

    fun isLoggedIn(source: NovelSource): Boolean {
        val info = loginInfo(source)
        val header = loginHeader(source)
        val web = NovelScript.getSourceKey(source.id, "webLogin") == "1"
        return info.length() > 0 || header.length() > 0 || web
    }

    fun loginInfo(source: NovelSource): JSONObject =
        runCatching { JSONObject(NovelScript.getSourceKey(source.id, "loginInfo").ifBlank { "{}" }) }.getOrDefault(JSONObject())

    fun setLoginInfo(source: NovelSource, map: Map<String, String>) {
        val obj = JSONObject()
        for ((k, v) in map) obj.put(k, v)
        NovelScript.putSourceKey(source.id, "loginInfo", obj.toString())
    }

    fun loginHeader(source: NovelSource): JSONObject =
        runCatching { JSONObject(NovelScript.getSourceKey(source.id, "loginHeader").ifBlank { "{}" }) }.getOrDefault(JSONObject())

    fun markWebLoggedIn(source: NovelSource) {
        NovelScript.putSourceKey(source.id, "webLogin", "1")
    }

    fun logout(source: NovelSource) {
        NovelScript.putSourceKey(source.id, "loginInfo", "")
        NovelScript.putSourceKey(source.id, "loginHeader", "")
        NovelScript.putSourceKey(source.id, "webLogin", "")
        LegadoCookies.remove(source.id)
    }

    fun loginPageUrl(source: NovelSource): String? {
        if (loginJs(source) != null) return null
        val t = source.json.optString("loginUrl").trim()
        if (t.isEmpty()) return null
        return NovelSources.absolute(t, source.id)
    }

    fun loginJs(source: NovelSource): String? {
        if (source.isJsSource) return source.mainJs
        val t = source.json.optString("loginUrl").trim()
        if (t.isEmpty()) return null
        if (t.startsWith("@js:", true)) return t.substring(4)
        if (t.startsWith("<js>", true)) return t.replace(Regex("(?i)^<js>|</js>$"), "")
        if (t.startsWith("http://", true) || t.startsWith("https://", true) || t.startsWith("/")) return null
        return t
    }

    /** Detects LegadoTeam Login UI v2 (`{"version":2}` marker or JS source defining `loginUi(state)` + `loginAction(...)`). */
    fun isV2(source: NovelSource): Boolean {
        val ui = source.json.optString("loginUi").trim()
        if (ui.startsWith("{")) {
            val o = runCatching { JSONObject(ui) }.getOrNull()
            if (o != null && o.optInt("version", 0) == 2) return true
        }
        return source.isJsSource && LegadoJsSource.hasLoginV2(source)
    }

    /** V1 static / script-generated fields. */
    fun fieldsV1(source: NovelSource): List<Field> {
        val ui = source.json.optString("loginUi").trim()
        if (ui.isEmpty()) return emptyList()
        val rawJson = if (ui.startsWith("@js:", true) || ui.startsWith("<js>", true)) {
            val code = if (ui.startsWith("@js:", true)) ui.substring(4) else ui.replace(Regex("(?i)^<js>|</js>$"), "")
            NovelScript(source, source.id).eval(code)?.toString().orEmpty()
        } else ui
        val arr = runCatching { JSONArray(rawJson) }.getOrNull()
            ?: runCatching {
                val res = NovelScript(source, source.id).eval("JSON.stringify(($rawJson))")?.toString().orEmpty()
                JSONArray(res)
            }.getOrNull() ?: return emptyList()
        return parseRows(arr)
    }

    /** V2 dynamic render: calls `loginUi(state)` returning `{ rows: [...] }`. */
    fun renderV2(source: NovelSource, stateJson: String = "{}"): List<Field> {
        val js = loginJs(source) ?: return emptyList()
        val stateObj = stateJson.ifBlank { "{}" }
        val script = "$js\n;JSON.stringify(typeof loginUi === 'function' ? loginUi($stateObj) : {rows:[]});"
        val out = NovelScript(source, source.id).also { it.interactive = true }.eval(script)?.toString().orEmpty()
        val obj = runCatching { JSONObject(out) }.getOrNull() ?: return emptyList()
        val rows = obj.optJSONArray("rows") ?: return emptyList()
        return parseRows(rows)
    }

    /** V2 action dispatch: calls `loginAction(action, state, form)` returning `{ state, error, login, close }`. */
    fun performActionV2(source: NovelSource, action: String, stateJson: String, form: Map<String, String>): V2ActionResult {
        val js = loginJs(source) ?: error("书源未定义登录脚本")
        val formObj = JSONObject()
        for ((k, v) in form) formObj.put(k, v)
        val stateObj = stateJson.ifBlank { "{}" }
        val script = "$js\n;JSON.stringify(typeof loginAction === 'function' ? loginAction(${JSONObject.quote(action)}, $stateObj, $formObj) : {});"
        val out = NovelScript(source, source.id).also { it.interactive = true }.eval(script)?.toString().orEmpty()
        val obj = runCatching { JSONObject(out) }.getOrNull() ?: return V2ActionResult(message = out)
        val newState = obj.opt("state")?.takeIf { it != JSONObject.NULL }?.toString()
        val errors = LinkedHashMap<String, String>()
        obj.optJSONObject("error")?.let { e -> for (k in e.keys()) errors[k] = e.optString(k) }
        val loginVal = obj.opt("login")?.takeIf { it != JSONObject.NULL }
        var loginStr: String? = null
        if (loginVal != null) {
            loginStr = loginVal.toString()
            if (loginVal is JSONObject) NovelScript.putSourceKey(source.id, "loginInfo", loginVal.toString())
            else if (loginVal is Boolean && loginVal) {
                NovelScript.putSourceKey(source.id, "loginInfo", formObj.toString())
            }
        }
        val close = obj.optBoolean("close", false)
        val msg = obj.optString("message").takeIf { it.isNotBlank() && it != "null" }
        return V2ActionResult(stateJson = newState, errors = errors, loginJson = loginStr, close = close, message = msg)
    }

    /** V1 login / button action execution (`loginJs + call`). */
    fun runActionV1(source: NovelSource, call: String = "typeof login === 'function' ? login() : ''"): String {
        val code = (loginJs(source) ?: "") + "\n" + call
        return NovelScript(source, source.id).also { it.interactive = true }.eval(code)?.toString().orEmpty()
    }

    private fun parseRows(arr: JSONArray): List<Field> {
        val out = ArrayList<Field>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name", o.optString("title", "")).trim()
            val key = o.optString("key", name).ifBlank { name }
            val type = o.optString("type", "text").lowercase()
            val chars = o.optJSONArray("chars")?.let { a -> (0 until a.length()).map { a.optString(it) } }
                ?: o.optJSONArray("options")?.let { a -> (0 until a.length()).map { a.optString(it) } }
                ?: emptyList()
            val defVal = o.opt("default")?.takeIf { it != JSONObject.NULL }?.toString() ?: chars.firstOrNull().orEmpty()
            val value = o.opt("value")?.takeIf { it != JSONObject.NULL }?.toString()
            val action = o.optString("action").takeIf { it.isNotBlank() && it != "null" }
            if (name.isNotEmpty() || key.isNotEmpty()) {
                out += Field(name = name.ifEmpty { key }, type = type, options = chars, action = action, defaultValue = defVal, value = value, key = key)
            }
        }
        return out
    }
}
