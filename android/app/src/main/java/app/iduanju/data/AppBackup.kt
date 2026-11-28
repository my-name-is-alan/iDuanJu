package app.iduanju.data

import android.content.Context
import android.util.Base64
import app.iduanju.IdjApp
import app.iduanju.core.cms.CMSCategory
import app.iduanju.core.cms.CMSSite
import app.iduanju.core.hls.HLSAdFilter
import app.iduanju.core.manga.MangaItem
import app.iduanju.core.plugin.PluginBundle
import app.iduanju.core.plugin.PluginSubscription
import app.iduanju.core.tg.TGChannel
import app.iduanju.core.tvbox.LenientJson
import app.iduanju.core.tvbox.TVBoxState
import app.iduanju.manga.MangaStore
import app.iduanju.plugin.PluginStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Full-fidelity local backup & smart-merge restore (`.idjbackup`).
 *
 * Binary framing (`IDJB\x01` + raw deflate RFC 1951) and JSON schema are 100% compatible with iOS `AppBackup.swift`,
 * including the `portable` cross-platform payload so iOS <-> Android WebDAV sync merges watch progress, favorites,
 * installed plugins, subscriptions, MacCMS sites, TVBox configs, Legado book sources, manga & TG channels seamlessly.
 */
object AppBackup {
    const val FILE_EXTENSION = "idjbackup"
    const val MAGIC_STRING = "idjbackup-v1"
    private val BINARY_HEADER = byteArrayOf(0x49, 0x44, 0x4A, 0x42, 0x01) // "IDJB" + v1

    data class RestoreSummary(
        val plugins: Int = 0,
        val cmsSites: Int = 0,
        val favorites: Int = 0,
        val history: Int = 0,
        val bookSources: Int = 0,
    ) {
        val toastText: String
            get() {
                val parts = ArrayList<String>()
                if (plugins > 0) parts += "插件 $plugins 个"
                if (cmsSites > 0) parts += "采集站 $cmsSites 个"
                if (favorites > 0) parts += "收藏 $favorites 部"
                if (history > 0) parts += "历史 $history 条"
                if (bookSources > 0) parts += "书源 $bookSources 个"
                return if (parts.isEmpty()) "已恢复备份设置" else "已恢复：" + parts.joinToString(" · ")
            }
    }

    class BackupException(message: String) : Exception(message)

    fun isBackupData(data: ByteArray): Boolean {
        if (data.size >= BINARY_HEADER.size && BINARY_HEADER.indices.all { data[it] == BINARY_HEADER[it] }) return true
        if (data.isNotEmpty() && data[0] == '{'.code.toByte()) {
            val head = String(data, 0, minOf(data.size, 320), Charsets.UTF_8)
            return head.contains("\"$MAGIC_STRING\"")
        }
        return false
    }

    // ── Export ──────────────────────────────────────────────────────────────────────────────────────────

    suspend fun createBackupData(ctx: Context): ByteArray {
        val app = IdjApp.instance
        withContext(Dispatchers.Main.immediate) { app.library.flush() }
        app.tvbox.flushForBackup()

        val portable = withContext(Dispatchers.Main.immediate) { buildPortable(ctx, app) }
        val ver = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "1.6"

        return withContext(Dispatchers.IO) {
            val root = JSONObject()
            root.put("magic", MAGIC_STRING)
            root.put("version", 1)
            // Swift JSONDecoder default Date decoding expects seconds since 2001-01-01 (Apple reference date).
            root.put("createdAt", System.currentTimeMillis() / 1000.0 - 978307200.0)
            root.put("appVersion", ver)
            root.put("portable", portable)

            // 1. Android SharedPreferences lossless snapshot (strictly excluding WebDAV & Hongguo web credentials).
            val prefsObj = exportAndroidPrefs(ctx)
            root.put("androidPrefsBase64", b64(prefsObj.toString().toByteArray(Charsets.UTF_8)))

            // 2. Installed plugin files (bundle.idjplugin, storage.json, plus manifest.json + entry.js for cross-platform)
            val pluginsRoot = File(ctx.filesDir, "plugins")
            val pluginFiles = JSONObject()
            pluginsRoot.listFiles()?.filter { it.isDirectory && isSafeSingleName(it.name) }?.forEach { dir ->
                val filesMap = JSONObject()
                dir.listFiles()?.filter { it.isFile && it.name != "plugin.log" && isSafeSingleName(it.name) && it.length() <= (4 shl 20) }?.forEach { f ->
                    runCatching { filesMap.put(f.name, b64(f.readBytes())) }
                }
                if (filesMap.length() > 0) pluginFiles.put(dir.name, filesMap)
            }
            root.put("pluginFiles", pluginFiles)

            // 3. TVBox configs & local attachments (exact same layout keys as iOS: tvbox_store_v2.json, repos/*, local/*)
            val tvboxFiles = JSONObject()
            var tvboxBytes = 0
            val stateFile = File(ctx.filesDir, "tvbox_store_v2.json")
            if (stateFile.isFile && stateFile.length() <= (4 shl 20)) {
                runCatching {
                    val bytes = stateFile.readBytes()
                    tvboxFiles.put("tvbox_store_v2.json", b64(bytes))
                    tvboxBytes += bytes.size
                }
            }
            val tvboxRoot = File(ctx.filesDir, "tvbox")
            for (sub in listOf("repos", "local")) {
                val subDir = File(tvboxRoot, sub)
                subDir.listFiles()?.filter { it.isFile && isSafeSingleName(it.name) }?.forEach { f ->
                    val sz = f.length().toInt()
                    if (sz <= (8 shl 20) && tvboxBytes + sz <= (24 shl 20)) {
                        runCatching {
                            val bytes = f.readBytes()
                            tvboxFiles.put("$sub/${f.name}", b64(bytes))
                            tvboxBytes += bytes.size
                        }
                    }
                }
            }
            root.put("tvboxFiles", tvboxFiles)

            // 4. Legado book sources (stored as JSON array in SharedPreferences "novel" -> "sources")
            val novelSp = ctx.getSharedPreferences("novel", Context.MODE_PRIVATE)
            val rawSources = novelSp.getString("sources", null)
            if (!rawSources.isNullOrBlank() && rawSources != "[]") {
                val bytes = rawSources.toByteArray(Charsets.UTF_8)
                if (bytes.size <= (32 shl 20)) {
                    root.put("legadoSourcesBase64", b64(bytes))
                }
            }

            // 5. Local novel file (if present)
            val novelFiles = JSONObject()
            val localTxt = File(ctx.filesDir, "novel-local.txt")
            if (localTxt.isFile && localTxt.length() <= (12 shl 20)) {
                runCatching { novelFiles.put("novel-local.txt", b64(localTxt.readBytes())) }
            }
            root.put("novelFiles", novelFiles)

            encodeArchive(root.toString().toByteArray(Charsets.UTF_8))
        }
    }

    private fun buildPortable(ctx: Context, app: IdjApp): JSONObject {
        val out = JSONObject()

        // Progress
        val progArr = JSONArray()
        app.library.history.forEach { p ->
            progArr.put(JSONObject()
                .put("sid", p.seriesId)
                .put("title", p.title)
                .put("cover", p.cover)
                .put("epIndex", p.episodeIndex)
                .put("ep", p.episodeNumber)
                .put("sec", p.seconds)
                .put("dur", p.duration)
                .put("updatedAtMs", p.updatedAt)
                .put("epTitle", p.episodeTitle)
                .put("epGroup", p.episodeGroup))
        }
        out.put("progress", progArr)

        // Favorites
        val favArr = JSONArray()
        app.library.favorites.forEach { (sid, v) ->
            favArr.put(JSONObject()
                .put("sid", sid)
                .put("title", v.first)
                .put("cover", v.second)
                .put("updatedAtMs", v.third))
        }
        out.put("favorites", favArr)

        // Search history + LWW deletion tombstones
        out.put("searchHistory", JSONArray(app.library.searchHistory.toList()))
        out.put("searchHistoryMeta", app.library.searchHistoryMeta().toJSON())

        // MacCMS sites
        out.put("cmsSites", JSONArray(app.cms.sites.map { it.toJSON() }))

        // Plugin subscriptions
        out.put("pluginSubscriptions", JSONArray(app.plugins.subscriptions.map { it.toJSON() }))

        // Portable plugin bundles (so iOS & Android can both install plugins seamlessly!)
        val pluginsRoot = File(ctx.filesDir, "plugins")
        val bundles = JSONArray()
        app.plugins.plugins.forEach { p ->
            val bundleFile = File(pluginsRoot, "${p.id}/bundle.idjplugin")
            val text = bundleFile.takeIf { it.isFile && it.length() <= (2 shl 20) }?.let { runCatching { it.readText() }.getOrNull() }
            if (!text.isNullOrBlank()) {
                val storageFile = File(pluginsRoot, "${p.id}/storage.json")
                val storage = storageFile.takeIf { it.isFile && it.length() <= (512 shl 10) }?.let { runCatching { it.readText() }.getOrNull() }
                bundles.put(JSONObject()
                    .put("id", p.id)
                    .put("enabled", p.enabled)
                    .put("origin", p.origin)
                    .put("bundleText", text)
                    .apply { if (storage != null) put("storageJson", storage) })
            }
        }
        out.put("pluginBundles", bundles)

        // Telegram channels
        out.put("tgChannels", JSONArray(app.tg.channels.map { it.toJSON() }))

        // Manga favorites & progress
        out.put("mangaFavorites", JSONArray(app.manga.favorites.map { it.json() }))
        out.put("mangaProgress", JSONArray(app.manga.recent.take(100).map { p ->
            JSONObject()
                .put("item", p.item.json())
                .put("chapterId", p.chapterId)
                .put("chapterTitle", p.chapterTitle)
                .put("page", p.page)
                .put("pages", p.pages)
                .put("updatedAtMs", p.at)
        }))

        out.put("adultUnlocked", app.cms.adultUnlocked)
        out.put("adStripEnabled", app.settings.adStripEnabled)
        return out
    }

    // ── Restore (Smart Merge) ───────────────────────────────────────────────────────────────────────────

    suspend fun restore(ctx: Context, rawData: ByteArray): RestoreSummary {
        if (!isBackupData(rawData)) throw BackupException("不是有效的 i短剧 备份文件（.idjbackup）")
        val app = IdjApp.instance
        withContext(Dispatchers.Main.immediate) { app.library.flush() }
        app.tvbox.flushForBackup()

        val root = withContext(Dispatchers.IO) {
            val jsonBytes = decodeArchive(rawData)
            val obj = runCatching { JSONObject(String(jsonBytes, Charsets.UTF_8)) }.getOrNull()
                ?: throw BackupException("备份文件已损坏或版本不受支持")
            if (obj.optString("magic") != MAGIC_STRING) throw BackupException("不是有效的 i短剧 备份文件（.idjbackup）")

            // 1. Restore Android native SharedPreferences (smart-merged!)
            val androidPrefsB64 = obj.optString("androidPrefsBase64", "")
            if (androidPrefsB64.isNotEmpty()) {
                runCatching {
                    val prefsJson = JSONObject(String(unb64(androidPrefsB64), Charsets.UTF_8))
                    mergeAndroidPrefs(ctx, prefsJson)
                }
            }

            // 2. Restore plugin directories (native Android bundle.idjplugin OR reconstructed from iOS manifest.json + entry.js)
            val pluginsRoot = File(ctx.filesDir, "plugins")
            val pluginFiles = obj.optJSONObject("pluginFiles")
            val reconstructedBundles = ArrayList<Triple<String, String, String>>() // id, bundleText, storageJson?
            if (pluginFiles != null) {
                val keys = pluginFiles.keys()
                while (keys.hasNext()) {
                    val dirName = keys.next()
                    if (!isSafeSingleName(dirName)) continue
                    val filesMap = pluginFiles.optJSONObject(dirName) ?: continue
                    val targetDir = File(pluginsRoot, dirName)
                    targetDir.mkdirs()
                    var hasBundle = false
                    var manifestText: String? = null
                    val jsFiles = HashMap<String, String>()
                    val fKeys = filesMap.keys()
                    while (fKeys.hasNext()) {
                        val fileName = fKeys.next()
                        if (!isSafeSingleName(fileName)) continue
                        val bytes = runCatching { unb64(filesMap.optString(fileName)) }.getOrNull() ?: continue
                        writeAtomic(File(targetDir, fileName), bytes)
                        if (fileName == "bundle.idjplugin") hasBundle = true
                        else if (fileName == "manifest.json") manifestText = String(bytes, Charsets.UTF_8)
                        else if (fileName.endsWith(".js")) jsFiles[fileName] = String(bytes, Charsets.UTF_8)
                    }
                    // Cross-platform bridge: if archive came from iOS (manifest.json + entry.js), synthesize bundle.idjplugin!
                    if (!hasBundle && manifestText != null) {
                        val mObj = runCatching { JSONObject(manifestText) }.getOrNull()
                        val entry = mObj?.optString("entry").orEmpty()
                        val js = jsFiles[entry] ?: jsFiles.values.firstOrNull()
                        if (js != null) {
                            runCatching {
                                val bundled = PluginSubscription.bundleFromManifest(manifestText, js)
                                writeAtomic(File(targetDir, "bundle.idjplugin"), bundled.toByteArray(Charsets.UTF_8))
                                reconstructedBundles += Triple(mObj?.optString("id").orEmpty().ifEmpty { dirName }, bundled, "")
                            }
                        }
                    }
                }
            }

            // 3. Restore TVBox files & smart-merge tvbox_store_v2.json
            val tvboxFiles = obj.optJSONObject("tvboxFiles")
            if (tvboxFiles != null && tvboxFiles.length() > 0) {
                val tvboxRoot = File(ctx.filesDir, "tvbox")
                tvboxRoot.mkdirs()
                val keys = tvboxFiles.keys()
                while (keys.hasNext()) {
                    val rel = keys.next()
                    if (rel == "tvbox_store_v2.json" || !isSafeRelativePath(rel)) continue
                    val bytes = runCatching { unb64(tvboxFiles.optString(rel)) }.getOrNull() ?: continue
                    val dest = File(tvboxRoot, rel)
                    dest.parentFile?.mkdirs()
                    writeAtomic(dest, bytes)
                }
                val stateB64 = tvboxFiles.optString("tvbox_store_v2.json", "")
                if (stateB64.isNotEmpty()) {
                    runCatching {
                        val incBytes = unb64(stateB64)
                        val stateFile = File(ctx.filesDir, "tvbox_store_v2.json")
                        val incObj = LenientJson.objectOrNull(String(incBytes, Charsets.UTF_8))
                        val curObj = stateFile.takeIf { it.isFile }?.let { f -> LenientJson.objectOrNull(f.readText()) }
                        if (curObj != null && incObj != null) {
                            val curState = TVBoxState.fromJSON(curObj)
                            val incState = TVBoxState.fromJSON(incObj)
                            val incRepoIds = incState.repos.map { it.id }.toHashSet()
                            val mergedRepos = incState.repos + curState.repos.filterNot { it.id in incRepoIds }
                            val incDepotIds = incState.depots.map { it.id }.toHashSet()
                            val mergedDepots = incState.depots + curState.depots.filterNot { it.id in incDepotIds }
                            val merged = incState.copy(
                                active = incState.active.ifEmpty { curState.active },
                                repos = mergedRepos,
                                depots = mergedDepots,
                            )
                            writeAtomic(stateFile, merged.toJSON().toString().toByteArray(Charsets.UTF_8))
                        } else {
                            writeAtomic(stateFile, incBytes)
                        }
                    }
                }
            }

            // 4. Restore Legado book sources (smart-merged by bookSourceUrl / sourceUrl)
            val legadoB64 = obj.optString("legadoSourcesBase64", "")
            if (legadoB64.isNotEmpty()) {
                runCatching {
                    val incText = String(unb64(legadoB64), Charsets.UTF_8)
                    mergeLegadoSources(ctx, incText)
                }
            }

            // 5. Restore local novel file if missing locally
            val novelFiles = obj.optJSONObject("novelFiles")
            val localB64 = novelFiles?.optString("novel-local.txt", "").orEmpty()
            if (localB64.isNotEmpty()) {
                val dest = File(ctx.filesDir, "novel-local.txt")
                if (!dest.exists()) {
                    runCatching { writeAtomic(dest, unb64(localB64)) }
                }
            }

            // 6. Install any reconstructed or portable plugin bundles onto disk + index
            val portable = obj.optJSONObject("portable")
            val portableBundles = portable?.optJSONArray("pluginBundles")
            if (portableBundles != null) {
                for (i in 0 until portableBundles.length()) {
                    val bObj = portableBundles.optJSONObject(i) ?: continue
                    val bundleText = bObj.optString("bundleText")
                    if (bundleText.isBlank()) continue
                    val id = bObj.optString("id")
                    val enabled = bObj.optBoolean("enabled", true)
                    val origin = bObj.optString("origin", "同步")
                    val storageJson = bObj.optString("storageJson", "")
                    runCatching {
                        val parsed = PluginBundle.parse(bundleText)
                        val pid = id.ifEmpty { parsed.manifest.id }
                        val dir = File(pluginsRoot, pid).apply { mkdirs() }
                        writeAtomic(File(dir, "bundle.idjplugin"), bundleText.toByteArray(Charsets.UTF_8))
                        if (storageJson.isNotEmpty()) {
                            writeAtomic(File(dir, "storage.json"), storageJson.toByteArray(Charsets.UTF_8))
                        }
                        upsertPluginIndex(ctx, parsed, enabled, origin)
                    }
                }
            }
            for ((_, bundleText, _) in reconstructedBundles) {
                runCatching {
                    val parsed = PluginBundle.parse(bundleText)
                    upsertPluginIndex(ctx, parsed, true, "同步")
                }
            }

            obj
        }

        // 7. Reload all live stores on Main thread and merge portable payload (handles iOS -> Android sync!).
        withContext(Dispatchers.Main.immediate) {
            app.plugins.reloadFromDisk()
            app.cms.reloadFromPrefs()
            app.library.reloadFromPrefs()
            app.manga.reloadFromPrefs()
            app.tg.reloadFromPrefs()
            app.settings.reloadFromPrefs()
            app.musicStore.reloadFromPrefs()
            root.optJSONObject("portable")?.let { mergePortable(app, it) }
        }
        app.tvbox.reloadFromDisk()

        val bookSourcesCount = runCatching {
            JSONArray(ctx.getSharedPreferences("novel", Context.MODE_PRIVATE).getString("sources", "[]")).length()
        }.getOrDefault(0)

        return withContext(Dispatchers.Main.immediate) {
            RestoreSummary(
                plugins = app.plugins.plugins.size,
                cmsSites = app.cms.sites.size,
                favorites = app.library.favorites.size,
                history = app.library.history.size,
                bookSources = bookSourcesCount,
            )
        }
    }

    private fun upsertPluginIndex(ctx: Context, bundle: PluginBundle, enabled: Boolean, origin: String) {
        val sp = ctx.getSharedPreferences("idj", Context.MODE_PRIVATE)
        val arr = runCatching { JSONArray(sp.getString(PluginStore.KEY, "[]")) }.getOrDefault(JSONArray())
        val list = ArrayList<PluginStore.Installed>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { list += PluginStore.Installed.of(it) }
        val m = bundle.manifest
        val rec = PluginStore.Installed(m.id, m.name, m.typeRaw, m.adult, m.version, enabled, origin)
        val idx = list.indexOfFirst { it.id == m.id }
        if (idx >= 0) list[idx] = rec else list += rec
        sp.edit().putString(PluginStore.KEY, JSONArray(list.map { it.toJSON() }).toString()).commit()
    }

    private fun mergePortable(app: IdjApp, portable: JSONObject) {
        // Watch progress
        val progList = ArrayList<WatchProgress>()
        portable.optJSONArray("progress")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val sid = o.optString("sid")
                if (sid.isEmpty()) continue
                progList += WatchProgress(
                    seriesId = sid,
                    title = o.optString("title"),
                    cover = o.optString("cover"),
                    episodeIndex = o.optInt("epIndex", 0),
                    episodeNumber = o.optInt("ep", 1),
                    seconds = o.optDouble("sec", 0.0),
                    duration = o.optDouble("dur", 0.0),
                    updatedAt = o.optLong("updatedAtMs", System.currentTimeMillis()),
                    episodeTitle = o.optString("epTitle"),
                    episodeGroup = o.optString("epGroup"),
                )
            }
        }
        // Favorites
        val favList = ArrayList<Triple<String, Pair<String, String>, Long>>()
        portable.optJSONArray("favorites")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val sid = o.optString("sid")
                if (sid.isEmpty()) continue
                favList += Triple(sid, o.optString("title") to o.optString("cover"), o.optLong("updatedAtMs", System.currentTimeMillis()))
            }
        }
        // Search history + tombstones
        val searchList = ArrayList<String>()
        portable.optJSONArray("searchHistory")?.let { arr ->
            for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { searchList += it }
        }
        val searchMeta = SearchHistoryMeta.fromJSON(portable.optJSONObject("searchHistoryMeta"))
        if (progList.isNotEmpty() || favList.isNotEmpty() || searchList.isNotEmpty() || searchMeta.clearedAt > 0L || searchMeta.deleted.isNotEmpty()) {
            app.library.mergePortable(progList, favList, searchList, searchMeta)
        }

        // MacCMS sites
        portable.optJSONArray("cmsSites")?.let { arr ->
            val sites = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(CMSSite::fromJSON) }.filter { it.key.isNotEmpty() && it.api.isNotEmpty() }
            if (sites.isNotEmpty()) app.cms.mergeSites(sites)
        }

        // Plugin subscriptions
        portable.optJSONArray("pluginSubscriptions")?.let { arr ->
            val curUrls = app.plugins.subscriptions.map { it.url }.toHashSet()
            var changed = false
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                runCatching { PluginSubscription.fromJSON(o) }.getOrNull()?.let { sub ->
                    if (sub.url.isNotEmpty() && curUrls.add(sub.url)) {
                        app.plugins.subscriptions.add(sub)
                        changed = true
                    }
                }
            }
            if (changed) {
                app.getSharedPreferences("idj", Context.MODE_PRIVATE).edit()
                    .putString(PluginStore.SUBS, JSONArray(app.plugins.subscriptions.map { it.toJSON() }).toString()).apply()
            }
        }

        // TG channels
        portable.optJSONArray("tgChannels")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val u = o.optString("username").ifEmpty { o.optString("id") }
                if (u.isNotBlank()) app.tg.add(u, o.optBoolean("isAdult", false))
            }
        }

        // Manga favorites & progress
        portable.optJSONArray("mangaFavorites")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                runCatching { MangaItem.from(o) }.getOrNull()?.let { item ->
                    if (!app.manga.isFavorite(item)) app.manga.toggleFavorite(item)
                }
            }
        }
        portable.optJSONArray("mangaProgress")?.let { arr ->
            var changed = false
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val itemObj = o.optJSONObject("item") ?: continue
                val item = runCatching { MangaItem.from(itemObj) }.getOrNull() ?: continue
                val at = o.optLong("updatedAtMs", 0L)
                val cur = app.manga.progress[item.id]
                if (cur == null || at >= cur.at) {
                    app.manga.progress[item.id] = MangaStore.Progress(
                        item, o.optString("chapterId"), o.optString("chapterTitle"),
                        o.optInt("page"), o.optInt("pages"), at,
                    )
                    changed = true
                }
            }
            if (changed) {
                val sp = app.getSharedPreferences("idj", Context.MODE_PRIVATE)
                sp.edit().putString("stv.manga.progress", JSONArray(app.manga.recent.take(100).map { p ->
                    JSONObject().put("item", p.item.json()).put("chapterId", p.chapterId).put("chapterTitle", p.chapterTitle)
                        .put("page", p.page).put("pages", p.pages).put("at", p.at)
                }).toString()).apply()
            }
        }

        if (portable.optBoolean("adultUnlocked", false)) app.cms.adultUnlocked = true
        if (portable.has("adStripEnabled")) {
            val ad = portable.optBoolean("adStripEnabled", true)
            app.settings.adStripEnabled = ad
            HLSAdFilter.enabled = ad
        }
    }

    // ── SharedPreferences Export / Smart Merge ──────────────────────────────────────────────────────────

    private val BACKUP_PREFS = listOf("idj", "library", "novel", "danmaku", "player")

    private fun shouldBackupPrefKey(prefName: String, key: String): Boolean {
        if (key.startsWith("stv.hg.web.") || key.startsWith("hg.web.") || key.startsWith("stv.webdav.") || key.startsWith("webdav.")) return false
        // Legado sources are exported separately in `legadoSourcesBase64` to avoid duplicating multi-MB blobs.
        if (prefName == "novel" && key == "sources") return false
        return true
    }

    private fun exportAndroidPrefs(ctx: Context): JSONObject {
        val out = JSONObject()
        for (name in BACKUP_PREFS) {
            val sp = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
            val entries = JSONObject()
            for ((k, v) in sp.all) {
                if (!shouldBackupPrefKey(name, k) || v == null) continue
                val entry = JSONObject()
                when (v) {
                    is Boolean -> entry.put("t", "b").put("v", v)
                    is Int -> entry.put("t", "i").put("v", v)
                    is Long -> entry.put("t", "l").put("v", v)
                    is Float -> entry.put("t", "f").put("v", v.toDouble())
                    is String -> entry.put("t", "s").put("v", v)
                    is Set<*> -> entry.put("t", "ss").put("v", JSONArray(v.filterIsInstance<String>()))
                    else -> continue
                }
                entries.put(k, entry)
            }
            if (entries.length() > 0) out.put(name, entries)
        }
        return out
    }

    private fun mergeAndroidPrefs(ctx: Context, allPrefs: JSONObject) {
        for (name in BACKUP_PREFS) {
            val incoming = allPrefs.optJSONObject(name) ?: continue
            val sp = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
            val ed = sp.edit()
            val keys = incoming.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (!shouldBackupPrefKey(name, k)) continue
                val entry = incoming.optJSONObject(k) ?: continue
                val type = entry.optString("t")
                when {
                    name == "library" && k == "history" && type == "s" -> {
                        val merged = mergeHistoryJson(sp.getString(k, "[]") ?: "[]", entry.optString("v", "[]"))
                        ed.putString(k, merged)
                    }
                    name == "library" && k == "fav" && type == "s" -> {
                        val merged = mergeFavJson(sp.getString(k, "{}") ?: "{}", entry.optString("v", "{}"))
                        ed.putString(k, merged)
                    }
                    name == "library" && (k == "search" || k == "searchMeta") -> {
                        // Handled atomically after the key loop so search items and tombstones always merge together.
                    }
                    name == "idj" && k == PluginStore.KEY && type == "s" -> {
                        val merged = mergeJsonArrayByKey(entry.optString("v", "[]"), sp.getString(k, "[]") ?: "[]", "id")
                        ed.putString(k, merged)
                    }
                    name == "idj" && k == PluginStore.SUBS && type == "s" -> {
                        val merged = mergeJsonArrayByKey(entry.optString("v", "[]"), sp.getString(k, "[]") ?: "[]", "url")
                        ed.putString(k, merged)
                    }
                    name == "idj" && k == CMSStore.KEY && type == "s" -> {
                        val merged = mergeJsonArrayByKey(entry.optString("v", "[]"), sp.getString(k, "[]") ?: "[]", "key")
                        ed.putString(k, merged)
                    }
                    name == "idj" && k == "stv.tg.channels" && type == "s" -> {
                        val merged = mergeJsonArrayByKey(entry.optString("v", "[]"), sp.getString(k, "[]") ?: "[]", "username")
                        ed.putString(k, merged)
                    }
                    name == "idj" && k == "stv.manga.favorites" && type == "s" -> {
                        val merged = mergeJsonArrayByKey(entry.optString("v", "[]"), sp.getString(k, "[]") ?: "[]", "id")
                        ed.putString(k, merged)
                    }
                    name == "idj" && k == "gate.adult" && type == "b" -> {
                        ed.putBoolean(k, sp.getBoolean(k, false) || entry.optBoolean("v", false))
                    }
                    else -> when (type) {
                        "b" -> ed.putBoolean(k, entry.optBoolean("v"))
                        "i" -> ed.putInt(k, entry.optInt("v"))
                        "l" -> ed.putLong(k, entry.optLong("v"))
                        "f" -> ed.putFloat(k, entry.optDouble("v").toFloat())
                        "s" -> ed.putString(k, entry.optString("v"))
                        "ss" -> {
                            val a = entry.optJSONArray("v")
                            if (a != null) {
                                val s = LinkedHashSet<String>()
                                for (i in 0 until a.length()) s += a.optString(i)
                                ed.putStringSet(k, s)
                            }
                        }
                    }
                }
            }
            if (name == "library" && (incoming.has("search") || incoming.has("searchMeta"))) {
                val localItems = parseStringArrayJson(sp.getString("search", "[]") ?: "[]")
                val localMeta = SearchHistoryMeta.fromJSON(sp.getString("searchMeta", null))
                val incItems = parseStringArrayJson(incoming.optJSONObject("search")?.optString("v", "[]") ?: "[]")
                val incMeta = SearchHistoryMeta.fromJSON(incoming.optJSONObject("searchMeta")?.optString("v", null))
                val (mergedItems, mergedMeta) = LibraryStore.mergeSearchHistoryState(
                    localItems = localItems,
                    localMeta = localMeta,
                    incomingItems = incItems,
                    incomingMeta = incMeta,
                    limit = LibraryStore.SEARCH_HISTORY_LIMIT,
                )
                ed.putString("search", JSONArray(mergedItems).toString())
                ed.putString("searchMeta", mergedMeta.toJSON().toString())
            }
            ed.commit()
        }
    }

    private fun parseStringArrayJson(raw: String): List<String> {
        val out = ArrayList<String>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { out += it }
        }
        return out
    }

    private fun mergeHistoryJson(curRaw: String, incRaw: String): String {
        val bySid = LinkedHashMap<String, JSONObject>()
        fun read(raw: String) {
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val sid = o.optString("sid")
                    if (sid.isEmpty()) continue
                    val prev = bySid[sid]
                    if (prev == null || o.optLong("at") >= prev.optLong("at")) bySid[sid] = o
                }
            }
        }
        read(curRaw); read(incRaw)
        val sorted = bySid.values.sortedByDescending { it.optLong("at") }.take(200)
        return JSONArray(sorted).toString()
    }

    private fun mergeFavJson(curRaw: String, incRaw: String): String {
        val out = runCatching { JSONObject(curRaw) }.getOrDefault(JSONObject())
        runCatching {
            val inc = JSONObject(incRaw)
            val keys = inc.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = inc.optJSONObject(k) ?: continue
                val prev = out.optJSONObject(k)
                if (prev == null || v.optLong("at") >= prev.optLong("at")) out.put(k, v)
            }
        }
        return out.toString()
    }

    private fun mergeStringArrayJson(primary: String, secondary: String, limit: Int): String {
        val seen = LinkedHashSet<String>()
        for (raw in listOf(primary, secondary)) {
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i)
                    if (s.isNotBlank() && seen.size < limit) seen += s
                }
            }
        }
        return JSONArray(seen.toList()).toString()
    }

    private fun mergeJsonArrayByKey(primary: String, secondary: String, idKey: String): String {
        val seen = HashSet<String>()
        val out = JSONArray()
        for (raw in listOf(primary, secondary)) {
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString(idKey)
                    if (id.isNotEmpty() && seen.add(id)) out.put(o)
                }
            }
        }
        return out.toString()
    }

    private fun mergeLegadoSources(ctx: Context, incRaw: String) {
        val sp = ctx.getSharedPreferences("novel", Context.MODE_PRIVATE)
        val curRaw = sp.getString("sources", "[]") ?: "[]"
        val seen = HashSet<String>()
        val out = JSONArray()
        fun addAll(raw: String) {
            runCatching {
                val trimmed = raw.trim().removePrefix("\uFEFF")
                val arr = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONArray().put(JSONObject(trimmed))
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("bookSourceUrl").ifEmpty { o.optString("sourceUrl") }
                    if (id.isNotBlank() && seen.add(id)) out.put(o)
                }
            }
        }
        addAll(incRaw)
        addAll(curRaw)
        if (out.length() > 0) sp.edit().putString("sources", out.toString()).commit()
    }

    // ── Compression & Wire Framing (100% compatible with Apple NSData .zlib = raw deflate) ─────────────

    internal fun encodeArchive(jsonBytes: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true) // nowrap = true -> RFC 1951 raw deflate (matches iOS)
        return try {
            deflater.setInput(jsonBytes)
            deflater.finish()
            val out = ByteArrayOutputStream(jsonBytes.size / 2 + 16)
            out.write(BINARY_HEADER)
            val buf = ByteArray(8192)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } catch (_: Exception) {
            jsonBytes
        } finally {
            deflater.end()
        }
    }

    internal fun decodeArchive(raw: ByteArray): ByteArray {
        if (raw.size >= BINARY_HEADER.size && BINARY_HEADER.indices.all { raw[it] == BINARY_HEADER[it] }) {
            val payload = raw.copyOfRange(BINARY_HEADER.size, raw.size)
            // First try raw deflate (nowrap = true, iOS NSData .zlib), then fallback to standard zlib header.
            return inflate(payload, nowrap = true) ?: inflate(payload, nowrap = false)
                ?: throw BackupException("备份文件已损坏或版本不受支持")
        }
        return raw
    }

    private fun inflate(payload: ByteArray, nowrap: Boolean): ByteArray? {
        val inflater = Inflater(nowrap)
        return try {
            inflater.setInput(payload)
            val out = ByteArrayOutputStream(payload.size * 2)
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n <= 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                } else {
                    out.write(buf, 0, n)
                }
                if (out.size() > (96 shl 20)) return null
            }
            val bytes = out.toByteArray()
            bytes.takeIf { it.isNotEmpty() && it[0] == '{'.code.toByte() }
        } catch (_: Exception) {
            null
        } finally {
            inflater.end()
        }
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(s: String): ByteArray = Base64.decode(s, Base64.DEFAULT)

    private fun writeAtomic(dest: File, bytes: ByteArray) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.path + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(dest)) { dest.delete(); tmp.renameTo(dest) }
    }

    private fun isSafeSingleName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." && !name.contains('/') && !name.contains('\\') && !name.contains('\u0000')

    private fun isSafeRelativePath(rel: String): Boolean {
        val parts = rel.split('/')
        return parts.isNotEmpty() && parts.all { isSafeSingleName(it) }
    }
}
