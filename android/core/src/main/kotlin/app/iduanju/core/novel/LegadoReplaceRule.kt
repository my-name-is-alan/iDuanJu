package app.iduanju.core.novel

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Legado-compatible text replacement / purification rules (`ReplaceRule`),
 * supporting JSON import/export, book/source scoping, regex & plain text replacement,
 * and safe live preview.
 */
data class LegadoReplaceRule(
    val id: Long = System.currentTimeMillis(),
    val name: String = "",
    val group: String = "",
    val pattern: String = "",
    val replacement: String = "",
    val scope: String = "",
    val scopeTitle: Boolean = false,
    val scopeContent: Boolean = true,
    val excludeScope: String = "",
    val isEnabled: Boolean = true,
    val isRegex: Boolean = true,
    val timeoutMs: Long = 3000L,
    val order: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("group", group)
        .put("pattern", pattern)
        .put("replacement", replacement)
        .put("scope", scope)
        .put("scopeTitle", scopeTitle)
        .put("scopeContent", scopeContent)
        .put("excludeScope", excludeScope)
        .put("isEnabled", isEnabled)
        .put("isRegex", isRegex)
        .put("timeoutMillisecond", timeoutMs)
        .put("order", order)

    fun matchesScope(bookName: String, sourceUrl: String, sourceName: String = ""): Boolean {
        val targets = listOf(bookName, sourceUrl, sourceName).filter { it.isNotBlank() }
        if (excludeScope.isNotBlank()) {
            val excluded = excludeScope.split(',', '\n', '，').map { it.trim() }.filter { it.isNotEmpty() }
            if (excluded.any { tok -> targets.any { t -> t.equals(tok, ignoreCase = true) || t.contains(tok, ignoreCase = true) } }) {
                return false
            }
        }
        if (scope.isBlank()) return true
        val allowed = scope.split(',', '\n', '，').map { it.trim() }.filter { it.isNotEmpty() }
        if (allowed.isEmpty()) return true
        return allowed.any { tok -> targets.any { t -> t.equals(tok, ignoreCase = true) || t.contains(tok, ignoreCase = true) } }
    }

    fun applyTo(text: String): String {
        if (pattern.isEmpty() || text.isEmpty()) return text
        return if (!isRegex) {
            text.replace(pattern, replacement)
        } else {
            runCatching {
                val regex = Regex(pattern)
                val deadline = System.nanoTime() + timeoutMs.coerceIn(100L, 10_000L) * 1_000_000L
                val guarded = TimedCharSequence(text, deadline)
                regex.replace(guarded, replacement)
            }.getOrDefault(text)
        }
    }

    private class TimedCharSequence(private val inner: CharSequence, private val deadlineNs: Long) : CharSequence {
        private var checks = 0
        override val length: Int get() = inner.length
        override fun get(index: Int): Char {
            if ((++checks and 0x3FF) == 0 && System.nanoTime() > deadlineNs) {
                throw IllegalStateException("替换正则超时")
            }
            return inner[index]
        }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            TimedCharSequence(inner.subSequence(startIndex, endIndex), deadlineNs)
        override fun toString(): String = inner.toString()
    }

    companion object {
        fun fromJson(o: JSONObject, fallbackId: Long = System.nanoTime()): LegadoReplaceRule = LegadoReplaceRule(
            id = o.optLong("id", fallbackId),
            name = o.optString("name", ""),
            group = o.optString("group", "").takeUnless { it == "null" }.orEmpty(),
            pattern = o.optString("pattern", ""),
            replacement = o.optString("replacement", ""),
            scope = o.optString("scope", "").takeUnless { it == "null" }.orEmpty(),
            scopeTitle = o.optBoolean("scopeTitle", false),
            scopeContent = o.optBoolean("scopeContent", true),
            excludeScope = o.optString("excludeScope", "").takeUnless { it == "null" }.orEmpty(),
            isEnabled = o.optBoolean("isEnabled", true),
            isRegex = o.optBoolean("isRegex", true),
            timeoutMs = o.optLong("timeoutMillisecond", 3000L).coerceIn(100L, 10_000L),
            order = o.optInt("order", 0),
        )
    }
}

object LegadoReplaceStore {
    private val rules = CopyOnWriteArrayList<LegadoReplaceRule>()
    private var storageFile: File? = null

    @Synchronized fun configure(root: File) {
        val target = File(root, "novel-replace-rules.json")
        if (storageFile == target) return
        storageFile = target
        rules.clear()
        val raw = runCatching { target.readText() }.getOrNull().orEmpty()
        if (raw.isNotBlank()) rules.addAll(parseJson(raw))
    }

    fun all(): List<LegadoReplaceRule> = rules.sortedBy { it.order }

    @Synchronized fun upsert(rule: LegadoReplaceRule) {
        val idx = rules.indexOfFirst { it.id == rule.id }
        if (idx >= 0) rules[idx] = rule else rules.add(rule)
        save()
    }

    @Synchronized fun setEnabled(id: Long, enabled: Boolean) {
        val idx = rules.indexOfFirst { it.id == id }
        if (idx >= 0) { rules[idx] = rules[idx].copy(isEnabled = enabled); save() }
    }

    @Synchronized fun remove(id: Long) {
        if (rules.removeIf { it.id == id }) save()
    }

    @Synchronized fun importJson(raw: String): Int {
        val parsed = parseJson(raw)
        for (r in parsed) {
            val idx = rules.indexOfFirst { it.id == r.id || (it.name.isNotBlank() && it.name == r.name && it.pattern == r.pattern) }
            if (idx >= 0) rules[idx] = r else rules.add(r)
        }
        if (parsed.isNotEmpty()) save()
        return parsed.size
    }

    fun exportJson(): String = JSONArray(all().map { it.toJson() }).toString(2)

    fun parseJson(raw: String): List<LegadoReplaceRule> {
        val t = raw.trim().removePrefix("\uFEFF")
        if (t.isEmpty()) return emptyList()
        val arr = if (t.startsWith("[")) JSONArray(t) else JSONArray().put(JSONObject(t))
        val out = ArrayList<LegadoReplaceRule>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val r = LegadoReplaceRule.fromJson(o, System.currentTimeMillis() + i)
            if (r.pattern.isNotEmpty()) out += r
        }
        return out
    }

    fun applyContent(text: String, bookName: String = "", sourceUrl: String = "", sourceName: String = ""): String {
        var cur = text
        for (r in all()) {
            if (!r.isEnabled || !r.scopeContent) continue
            if (!r.matchesScope(bookName, sourceUrl, sourceName)) continue
            cur = r.applyTo(cur)
        }
        return cur
    }

    fun applyTitle(title: String, bookName: String = "", sourceUrl: String = "", sourceName: String = ""): String {
        var cur = title
        for (r in all()) {
            if (!r.isEnabled || !r.scopeTitle) continue
            if (!r.matchesScope(bookName, sourceUrl, sourceName)) continue
            cur = r.applyTo(cur)
        }
        return cur
    }

    @Synchronized fun clearForTest() {
        rules.clear()
        storageFile = null
    }

    private fun save() {
        val target = storageFile ?: return
        runCatching {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(exportJson())
            java.nio.file.Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
