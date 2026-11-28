package app.iduanju.core.novel

import org.json.JSONArray
import org.json.JSONObject

/**
 * Paragraph review (段评) engine supporting both `LegadoTeam/legado`'s `ruleReview`
 * and single-file JS source `getReviewSummary` / `getReviewDetail` / `getReviewReplies`.
 */
object LegadoReview {
    data class Summary(
        val counts: Map<Int, Int>,          // paraIndex -> comment count (-1 = chapter title)
        val paraData: Map<Int, String>,     // paraIndex -> opaque token
    )

    data class Comment(
        val id: String,
        val name: String,
        val avatar: String = "",
        val replyToName: String = "",
        val badges: List<String> = emptyList(),
        val content: String = "",
        val imageUrl: String = "",
        val time: String = "",
        val likeCount: Int = 0,
        val replyCount: Int = 0,
        val replies: List<Comment> = emptyList(),
    )

    data class DetailPage(
        val items: List<Comment>,
        val nextPageUrl: String? = null,
    )

    fun isEnabled(source: NovelSource): Boolean {
        if (source.isJsSource) return LegadoJsSource.hasReview(source)
        val r = source.rules("ruleReview")
        if (r.length() == 0) return false
        return r.optBoolean("enabled", true) &&
            (r.optString("reviewSummaryUrl").isNotBlank() || r.optString("reviewUrl").isNotBlank())
    }

    fun summary(source: NovelSource, chapter: NovelChapter, bookUrl: String = "", bookName: String = ""): Summary {
        if (source.isJsSource) return summaryJs(source, chapter, bookUrl, bookName)
        val r = source.rules("ruleReview")
        val urlRule = r.optString("reviewSummaryUrl").ifBlank { r.optString("reviewUrl") }
        if (urlRule.isBlank()) return Summary(emptyMap(), emptyMap())
        val counts = LinkedHashMap<Int, Int>()
        val tokens = LinkedHashMap<Int, String>()
        NovelScript.withBook(bookUrl) {
            NovelScript.withChapter(chapter) {
                val resp = NovelSources.page(urlRule, source)
                val doc = NovelSources.document(resp.text)
                val listRule = r.optString("summaryListRule")
                if (listRule.isBlank()) return@withChapter
                val items = NovelSources.nodes(doc, listRule, source, resp.url)
                val idxRule = r.optString("summaryParagraphIndexRule")
                val cntRule = r.optString("summaryCountRule")
                val dataRule = r.optString("summaryParagraphDataRule")
                items.forEachIndexed { i, node ->
                    val paraIdx = if (idxRule.isNotBlank()) {
                        NovelSources.ruleValue(node, idxRule, source, resp.url).toIntOrNull() ?: (i + 1)
                    } else i + 1
                    val cnt = if (cntRule.isNotBlank()) {
                        NovelSources.ruleValue(node, cntRule, source, resp.url).toIntOrNull() ?: 1
                    } else 1
                    if (cnt > 0) counts[paraIdx] = cnt
                    if (dataRule.isNotBlank()) {
                        val tok = NovelSources.ruleValue(node, dataRule, source, resp.url)
                        if (tok.isNotBlank()) tokens[paraIdx] = tok
                    }
                }
            }
        }
        return Summary(counts, tokens)
    }

    fun detail(source: NovelSource, chapter: NovelChapter, paraIndex: Int, paraData: String = "", page: Int = 1, bookUrl: String = "", bookName: String = ""): DetailPage {
        if (source.isJsSource) return detailJs(source, chapter, paraIndex, paraData, page, bookUrl, bookName)
        val r = source.rules("ruleReview")
        val urlRule = r.optString("reviewDetailUrl").ifBlank { r.optString("reviewUrl") }
        if (urlRule.isBlank()) return DetailPage(emptyList())
        return NovelScript.withBook(bookUrl) {
            NovelScript.withChapter(chapter) {
                NovelScript.setVariable(source.id, "paraIndex", paraIndex.toString())
                NovelScript.setVariable(source.id, "paraData", paraData)
                val resp = NovelSources.page(urlRule, source, key = paraData, page = page)
                val doc = NovelSources.document(resp.text)
                val listRule = r.optString("detailListRule")
                val nodes = if (listRule.isNotBlank()) NovelSources.nodes(doc, listRule, source, resp.url) else emptyList()
                val items = nodes.mapIndexedNotNull { idx, node ->
                    fun v(k: String) = r.optString(k).takeIf { it.isNotBlank() }?.let { runCatching { NovelSources.ruleValue(node, it, source, resp.url) }.getOrDefault("") }.orEmpty()
                    val content = v("detailContentRule").ifBlank { v("contentRule") }
                    if (content.isBlank()) return@mapIndexedNotNull null
                    Comment(
                        id = v("detailIdRule").ifBlank { "c_$page-$idx" },
                        name = v("detailNameRule").ifBlank { "书友" },
                        avatar = v("detailAvatarRule").ifBlank { v("avatarRule") },
                        badges = v("detailBadgeRule").lines().map { it.trim() }.filter { it.isNotEmpty() },
                        content = content,
                        time = v("postTimeRule"),
                    )
                }
                val nextUrl = r.optString("reviewDetailNextPageUrl").takeIf { it.isNotBlank() }
                    ?.let { runCatching { NovelSources.ruleValue(doc, it, source, resp.url) }.getOrNull() }
                    ?.takeIf { it.isNotBlank() }
                DetailPage(items, nextUrl)
            }
        }
    }

    fun replies(source: NovelSource, chapter: NovelChapter, paraIndex: Int, paraData: String, reviewId: String, page: Int = 1, bookUrl: String = "", bookName: String = ""): DetailPage {
        if (!source.isJsSource) return DetailPage(emptyList())
        val chObj = JSONObject().put("title", chapter.title).put("url", chapter.url).put("index", chapter.index)
        val bookObj = JSONObject().put("bookUrl", bookUrl).put("name", bookName)
        val raw = LegadoJsSource.run(
            source,
            "typeof getReviewReplies === 'function' ? getReviewReplies($chObj, $bookObj, $paraIndex, ${JSONObject.quote(paraData)}, ${JSONObject.quote(reviewId)}, $page) : {items:[]}",
            base = chapter.url,
        )
        return parseJsDetailPage(raw)
    }

    private fun summaryJs(source: NovelSource, chapter: NovelChapter, bookUrl: String, bookName: String): Summary {
        val chObj = JSONObject().put("title", chapter.title).put("url", chapter.url).put("index", chapter.index)
        val bookObj = JSONObject().put("bookUrl", bookUrl).put("name", bookName)
        val raw = LegadoJsSource.run(source, "typeof getReviewSummary === 'function' ? getReviewSummary($chObj, $bookObj) : []", base = chapter.url)
        val arr = raw as? JSONArray ?: runCatching { JSONArray(raw?.toString().orEmpty()) }.getOrDefault(JSONArray())
        val counts = LinkedHashMap<Int, Int>()
        val data = LinkedHashMap<Int, String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val idx = o.optInt("paraIndex", i + 1)
            val cnt = o.optInt("count", 0)
            if (cnt > 0) counts[idx] = cnt
            val pd = o.optString("paraData").takeIf { it.isNotBlank() && it != "null" }
            if (pd != null) data[idx] = pd
        }
        return Summary(counts, data)
    }

    private fun detailJs(source: NovelSource, chapter: NovelChapter, paraIndex: Int, paraData: String, page: Int, bookUrl: String, bookName: String): DetailPage {
        val chObj = JSONObject().put("title", chapter.title).put("url", chapter.url).put("index", chapter.index)
        val bookObj = JSONObject().put("bookUrl", bookUrl).put("name", bookName)
        val raw = LegadoJsSource.run(
            source,
            "typeof getReviewDetail === 'function' ? getReviewDetail($chObj, $bookObj, $paraIndex, ${JSONObject.quote(paraData)}, $page) : {items:[]}",
            base = chapter.url,
        )
        return parseJsDetailPage(raw)
    }

    private fun parseJsDetailPage(raw: Any?): DetailPage {
        val obj = raw as? JSONObject ?: runCatching { JSONObject(raw?.toString().orEmpty()) }.getOrNull() ?: return DetailPage(emptyList())
        val arr = obj.optJSONArray("items") ?: JSONArray()
        val items = ArrayList<Comment>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val cField = o.opt("content")
            val cObj = cField as? JSONObject
            val text = cObj?.optString("text") ?: (cField?.toString().orEmpty())
            val badges = o.optJSONArray("badges")?.let { b -> (0 until b.length()).map { b.optString(it) } }.orEmpty()
            items += Comment(
                id = o.optString("id", "r_$i"),
                name = o.optString("name", "书友"),
                avatar = o.optString("avatar"),
                replyToName = cObj?.optString("replyToName").orEmpty(),
                badges = badges,
                content = text,
                imageUrl = cObj?.optString("img").orEmpty(),
                time = cObj?.optString("time", o.optString("time")).orEmpty(),
                likeCount = cObj?.optInt("likeCount", o.optInt("likeCount", 0)) ?: 0,
                replyCount = cObj?.optInt("replyCount", o.optInt("replyCount", 0)) ?: 0,
            )
        }
        val next = obj.optString("nextPageUrl").takeIf { it.isNotBlank() && it != "null" }
        return DetailPage(items, next)
    }
}
