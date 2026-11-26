package app.iduanju.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iduanju.IdjApp
import app.iduanju.core.plugin.PluginException
import app.iduanju.core.plugin.PluginManifest
import app.iduanju.plugin.PluginStore
import app.iduanju.tvbox.ImportResult
import app.iduanju.ui.components.CheckRow
import app.iduanju.ui.components.rowClick
import app.iduanju.ui.components.GlassConfirm
import app.iduanju.ui.components.GlassMenu
import app.iduanju.ui.components.GlassMenuItem
import app.iduanju.ui.components.GlassSpinner
import app.iduanju.ui.components.GlassSwitch
import app.iduanju.ui.components.GlassTextField
import app.iduanju.ui.components.GlassToast
import app.iduanju.ui.components.Hairline
import app.iduanju.ui.components.Motion
import app.iduanju.ui.components.PlainIconButton
import app.iduanju.ui.components.glassSurface
import app.iduanju.ui.theme.IdjThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * iOS PluginsView (我的 → 插件): 已安装 (toggle, ⋯ remove), 添加插件 (网址 / 文件 / 粘贴 / 检查更新 — each opens
     * its own dialog), 订阅, 推荐插件（内置）, 采集站（苹果 CMS）. Adult entries only appear once the gate is open.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PluginsScreen(onBack: () -> Unit) {
    val c = IdjThemeColors
    val ia = IdjApp.instance
    val store = ia.plugins
    val cms = ia.cms
    val adult = cms.adultUnlocked
    val scope = rememberCoroutineScope()
    // Unpacks every bundled plugin manifest: never on the main thread.
    val bundled by produceState(emptyList<Pair<String, PluginManifest>>()) { value = withContext(Dispatchers.IO) { store.bundledManifests() } }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }   // url | paste | sub | cms
    var removing by remember { mutableStateOf<Pair<String, String>?>(null) }  // kind:id to name

    fun report(ok: String?, err: String?) { message = ok ?: err; error = ok == null; message?.let { GlassToast.show(it, long = true) } }
    fun run(work: suspend () -> String) {
        busy = true; message = null
        scope.launch {
            try { report(withContext(Dispatchers.IO) { work() }, null) }
            catch (e: PluginException) { report(null, e.friendly) } catch (e: Exception) { report(null, e.message ?: "失败") }
            busy = false
        }
    }
    suspend fun describe(r: PluginStore.Pasted) = when (r) {
        is PluginStore.Pasted.Plugin -> "已安装「${r.rec.name}」${r.rec.version}"
        is PluginStore.Pasted.Subscription -> "已添加订阅（${r.sub.plugins.size} 个插件 / ${r.sub.cms.size} 个采集站），在下方按需安装"
        is PluginStore.Pasted.TVBox -> when (val t = withContext(Dispatchers.Main) { IdjApp.instance.tvbox.importText(r.source) }) {
            is ImportResult.Imported -> "已导入 TVBox 配置「${t.repo.name}」（${t.repo.sites.size} 个站点 / ${t.repo.lives.size} 个直播），在 TVBox 页使用"
            is ImportResult.Depot -> "已添加多仓「${t.depot.name}」（${t.depot.entries.size} 个配置），在 TVBox 页选择"
            is ImportResult.Attachment -> "已保存「${t.name}」，供本地 TVBox 配置引用"
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) run {
            if (uris.size == 1) {
                val uri = uris.first()
                val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("读取文件失败")
                val name = ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                describe(store.interpret(text, "file", name?.substringBeforeLast('.')))
            } else {
                var ok = 0
                var failed = 0
                for (uri in uris) {
                    runCatching {
                        val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("读取文件失败")
                        val name = ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                        describe(store.interpret(text, "file", name?.substringBeforeLast('.')))
                    }.onSuccess { ok++ }.onFailure { failed++ }
                }
                "批量导入完成：成功 $ok 个" + if (failed > 0) "，失败 $failed 个" else ""
            }
        }
    }

    val installed = store.plugins.filter { !it.adult || adult }
    val visibleBundled = bundled.filter { !it.second.adult || adult }
    val missingBundled = visibleBundled.filter { (_, m) -> store.plugins.none { it.id == m.id } }
    Box(Modifier.fillMaxSize()) {
        SettingsListPage("插件", onBack) {
            item("installed", "group") {
                val hidden = store.plugins.size - installed.size
                Group("已安装（${installed.size}）", footer = if (hidden > 0) "另有 $hidden 个成人插件已隐藏：在「片源管理」解锁后显示。" else null) {
                    if (installed.isEmpty()) Text("还没有插件。可以从下面的网址 / 文件 / 订阅添加，或安装内置推荐。", color = c.secondary, fontSize = 14.sp, modifier = Modifier.padding(14.dp))
                    installed.forEachIndexed { i, p ->
                        if (i > 0) RowDivider()
                        key(p.id) {
                            SourceRow(if (p.type == "comic") ProfileIcons.manga else ProfileIcons.plugin, p.name, listOfNotNull(typeLabel(p.type), if (p.adult) "18+" else null),
                                "v${p.version} · ${originLabel(p.origin)} · ${if (p.enabled) "已启用" else "已停用"}", p.enabled,
                                onToggle = { store.setEnabled(p.id, it) }) { removing = "plugin:${p.id}" to p.name }
                        }
                    }
                }
            }
            if (message != null) item("message", "note") { Note(message, error) }
            if (bundled.isNotEmpty()) item("bundled", "group") {
                Group("推荐插件（App 内置）",
                    footer = if (visibleBundled.isEmpty()) "App 内置了 ${bundled.size} 个成人视频与漫画插件（成人内容），在「片源管理」打开成人内容后显示。" else null) {
                    if (missingBundled.size > 1) {
                        LinkRow("一键安装全部推荐（${missingBundled.size}）", ProfileIcons.add) {
                            if (!busy) run {
                                var ok = 0
                                for ((file, _) in missingBundled) {
                                    runCatching { store.install(store.bundledText(file), "内置") }.onSuccess { ok++ }
                                }
                                "已安装 $ok 个推荐插件"
                            }
                        }
                        RowDivider()
                    }
                    visibleBundled.forEachIndexed { i, (file, m) ->
                        if (i > 0) Hairline(Modifier.padding(start = 14.dp))
                        val inst = store.plugins.firstOrNull { it.id == m.id }
                        val newer = inst != null && PluginManifest.compare(m.version, inst.version) > 0
                        EntryRow(m.name, listOfNotNull(m.type?.label, if (m.adult) "18+" else null), when { inst == null -> "安装"; newer -> "更新"; else -> null },
                            sub = m.description, padded = true) {
                            run { val r = store.install(store.bundledText(file), "内置"); "已安装「${r.name}」${r.version}" }
                        }
                    }
                }
            }
            item("add", "group") {
                Group("添加插件", footer = "插件运行在 QuickJS 沙箱里，只能访问 manifest 声明的域名；未成年相关内容一律过滤。") {
                    LinkRow("从网址添加", ProfileIcons.link) { dialog = "url" }
                    RowDivider()
                    LinkRow("从文件导入（.idjplugin / .m3u）", ProfileIcons.file, sub = "支持多选 · 插件 / M3U / TVBox JSON") { picker.launch(arrayOf("*/*")) }
                    RowDivider()
                    LinkRow("粘贴插件内容", ProfileIcons.paste) { dialog = "paste" }
                    RowDivider()
                    LinkRow(if (busy) "正在检查更新…" else "检查更新", ProfileIcons.sync) {
                        if (!busy) run { store.refreshSubscriptions(); "已检查订阅中的插件更新" }
                    }
                }
            }
            item("subscriptions", "group") {
                Group("订阅") {
                    store.subscriptions.forEachIndexed { i, s ->
                        if (i > 0) RowDivider()
                        Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(s.name.ifEmpty { s.url }, color = c.text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(s.error ?: s.url, color = if (s.error == null) c.secondary else c.accentWarm, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                PlainIconButton(Icons.Outlined.Delete, "删除订阅", tint = c.danger, iconSize = 20.dp) { removing = "sub:${s.url}" to (s.name.ifEmpty { s.url }) }
                            }
                            val missing = s.plugins.filter { e -> (e.adult != true || adult) && store.plugins.none { p -> p.id == e.id } }
                            if (missing.size > 1) {
                                Row(Modifier.fillMaxWidth().padding(end = 10.dp), horizontalArrangement = Arrangement.End) {
                                    TextAction("全部安装（${missing.size}）", enabled = !busy) {
                                        run {
                                            var ok = 0
                                            var failed = 0
                                            for (e in missing) runCatching { store.interpret(e.manifest, "订阅：" + s.url) }.onSuccess { ok++ }.onFailure { failed++ }
                                            "已安装 $ok 个插件" + if (failed > 0) "，失败 $failed 个" else ""
                                        }
                                    }
                                }
                            }
                            Column(Modifier.padding(end = 10.dp)) {
                                s.plugins.filter { it.adult != true || adult }.forEach { e ->
                                    val inst = store.plugins.firstOrNull { it.id == e.id }
                                    val newer = inst != null && e.version != null && PluginManifest.compare(e.version!!, inst.version) > 0
                                    EntryRow(e.name, listOfNotNull(if (e.adult != false) "18+" else null), when { inst == null -> "安装"; newer -> "更新"; else -> null }) {
                                        run { describe(store.interpret(e.manifest, "订阅：" + s.url)) }
                                    }
                                }
                                s.cms.filter { it.adult != true || adult }.forEach { e ->
                                    val added = cms.sites.any { st -> runCatching { app.iduanju.core.cms.MacCMS.normalize(e.api) == st.api }.getOrDefault(false) }
                                    EntryRow("采集站 · " + e.name, emptyList(), if (added) null else "添加") {
                                        run { val st = cms.add(e.name, e.api, e.adult ?: false, "订阅：" + s.url); "已添加采集站 ${st.name}" }
                                    }
                                }
                            }
                        }
                    }
                    if (store.subscriptions.isNotEmpty()) RowDivider()
                    LinkRow("添加订阅地址", ProfileIcons.add) { dialog = "sub" }
                }
            }
            item("cms", "group") {
                Group("采集站（苹果 CMS）", footer = "采集站使用内置的苹果 CMS v10 适配器（?ac=list / ?ac=detail），只播放直链 m3u8 / mp4 线路，仅 https；未成年相关内容一律过滤。") {
                    cms.sites.filter { !it.adult || adult }.forEachIndexed { i, s ->
                        if (i > 0) RowDivider()
                        key(s.key) {
                            SourceRow(ProfileIcons.server, s.name, if (s.adult) listOf("18+") else emptyList(),
                                "${runCatching { java.net.URI(s.api).host }.getOrNull() ?: ""} · ${s.categories.size} 个分类", s.enabled,
                                onToggle = { cms.setEnabled(s.key, it) }) { removing = "cms:${s.key}" to s.name }
                        }
                    }
                    if (cms.sites.any { !it.adult || adult }) RowDivider()
                    LinkRow("添加采集站", ProfileIcons.server) { dialog = "cms" }
                }
            }
        }

        AnimatedVisibility(busy, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 18.dp), enter = Motion.riseEnter, exit = Motion.riseExit) {
            Row(Modifier.glassSurface(RoundedCornerShape(50), shadowRadius = 16.dp, fallbackAlpha = 2.2f).padding(horizontal = 18.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassSpinner(size = 18.dp, stroke = 2.dp)
                Text(message ?: "正在处理…", color = c.text, fontSize = 14.sp)
            }
        }
    }

    when (dialog) {
        "url", "sub" -> InputDialog(if (dialog == "url") "从网址添加" else "添加订阅地址",
            if (dialog == "url") "插件 / manifest / 订阅列表的 https 链接" else "订阅列表 https 链接", onDismiss = { dialog = null }) { text ->
            dialog = null; run { describe(store.interpret(text)) }
        }
        "paste" -> PasteDialog(onDismiss = { dialog = null }) { text -> dialog = null; run { describe(store.interpret(text, "paste")) } }
        "cms" -> CMSDialog(onDismiss = { dialog = null }) { name, api, isAdult ->
            dialog = null; run { val s = cms.add(name, api, isAdult); "已添加「${s.name}」· ${s.categories.size} 个分类" }
        }
    }
    removing?.let { (key, name) ->
        GlassConfirm(
            onDismissRequest = { removing = null },
            title = "删除「$name」？",
            message = if (key.startsWith("sub:")) "只删除订阅，已安装的插件保留。" else "删除后可以重新添加。",
            confirmText = "删除",
            destructive = true,
            onConfirm = {
                val id = key.substringAfter(':')
                when { key.startsWith("plugin:") -> store.remove(id); key.startsWith("cms:") -> cms.remove(id); else -> store.removeSubscription(id) }
                removing = null; report("已删除「$name」", null)
            },
        )
    }
}

/** Installed plugin / 采集站 row: icon · name + tags · caption · switch · ⋯ (remove). */
@Composable
private fun SourceRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, tags: List<String>, sub: String, on: Boolean,
    onToggle: (Boolean) -> Unit, onRemove: () -> Unit) {
    val c = IdjThemeColors
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (on) c.accent else c.secondary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, color = c.text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                tags.forEach { Tag(it) }
            }
            Text(sub, color = c.secondary, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(8.dp))
        GlassSwitch(on, onToggle, label = title)
        Box {
            PlainIconButton(Icons.Rounded.MoreHoriz, "更多：$title", tint = c.secondary) { menu = true }
            GlassMenu(menu, { menu = false }) {
                GlassMenuItem("删除", icon = Icons.Outlined.Delete, destructive = true) { menu = false; onRemove() }
            }
        }
    }
}

@Composable
private fun EntryRow(title: String, tags: List<String>, action: String?, sub: String? = null, padded: Boolean = false, onAction: () -> Unit) {
    val c = IdjThemeColors
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = if (padded) 14.dp else 0.dp, end = if (padded) 6.dp else 0.dp, top = if (padded) 6.dp else 0.dp, bottom = if (padded) 6.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = c.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                tags.forEach { Tag(it) }
            }
            sub?.takeIf { it.isNotBlank() }?.let { Text(it, color = c.secondary, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        if (action != null) TextAction(action, onClick = onAction) else Text("已安装", color = c.secondary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp))
    }
}

private val UrlKeyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false)

@Composable
private fun InputDialog(title: String, hint: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val ready = text.trim().startsWith("https://")
    GlassConfirm(onDismiss, title, "添加", { onDone(text.trim()) }, message = hint, confirmEnabled = ready) {
        GlassTextField(text, { text = it }, placeholder = "https://…", keyboardOptions = UrlKeyboard, keyboardActions = KeyboardActions(onDone = { if (ready) onDone(text.trim()) }))
    }
}

@Composable
private fun PasteDialog(onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val clip = LocalClipboardManager.current
    var text by remember { mutableStateOf("") }
    GlassConfirm(onDismiss, "粘贴插件内容", "安装", { onDone(text) }, confirmEnabled = text.isNotBlank()) {
        GlassTextField(text, { text = it }, placeholder = ".idjplugin、M3U 频道列表或 TVBox JSON 配置", singleLine = false, minLines = 5, maxLines = 8, fontSize = 13.sp)
        TextAction("从剪贴板粘贴") { clip.getText()?.text?.let { text = it } }
    }
}

@Composable
private fun CMSDialog(onDismiss: () -> Unit, onDone: (String, String, Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var api by remember { mutableStateOf("") }
    var adult by remember { mutableStateOf(false) }
    GlassConfirm(onDismiss, "添加采集站", "添加", { onDone(name.trim(), api.trim(), adult) }, confirmEnabled = api.trim().startsWith("https://")) {
        GlassTextField(name, { name = it }, label = "名称（可空）")
        Spacer(Modifier.height(10.dp))
        GlassTextField(api, { api = it }, label = "采集接口", placeholder = "https://…/api.php/provide/vod/", keyboardOptions = UrlKeyboard)
        CheckRow("成人站（解锁成人内容后才显示）", adult, { adult = it })
    }
}

private fun typeLabel(t: String) = when (t) { "video" -> "长视频"; "shortdrama" -> "短剧"; "comic" -> "漫画"; "live" -> "直播"; else -> t }
private fun originLabel(o: String) = when {
    o == "内置" -> "i短剧"; o == "paste" -> "粘贴"; o == "file" -> "文件"; o.startsWith("订阅：") -> "订阅"
    o.startsWith("https://") -> runCatching { java.net.URI(o).host }.getOrNull() ?: "网址"; else -> o
}
