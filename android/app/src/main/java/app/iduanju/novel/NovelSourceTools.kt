package app.iduanju.novel

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.iduanju.core.novel.*
import app.iduanju.ui.components.*
import app.iduanju.ui.theme.IdjThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

// MARK: - 1. Source Login Sheet (Login UI v1 + Login UI v2 + Built-in WebView Browser)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NovelSourceLoginSheet(
    source: NovelSource,
    onDismiss: () -> Unit,
) {
    val c = IdjThemeColors
    val scope = rememberCoroutineScope()
    val isV2 = remember(source.id) { LegadoLogin.isV2(source) }
    val loginPageUrl = remember(source.id) { LegadoLogin.loginPageUrl(source) }
    val hasLoginJs = remember(source.id) { LegadoLogin.loginJs(source) != null }

    var loggedIn by remember(source.id) { mutableStateOf(LegadoLogin.isLoggedIn(source)) }
    var v2StateJson by remember(source.id) { mutableStateOf("{}") }
    var fields by remember(source.id) { mutableStateOf<List<LegadoLogin.Field>>(emptyList()) }
    val values = remember(source.id) { mutableStateMapOf<String, String>() }
    val fieldErrors = remember(source.id) { mutableStateMapOf<String, String>() }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var openBrowserUrl by remember { mutableStateOf<String?>(null) }

    fun loadForm(stateJson: String = v2StateJson) {
        scope.launch {
            busy = true
            try {
                val loaded = withContext(Dispatchers.IO) {
                    if (isV2) LegadoLogin.renderV2(source, stateJson) else LegadoLogin.fieldsV1(source)
                }
                val saved = withContext(Dispatchers.IO) { LegadoLogin.loginInfo(source) }
                fields = loaded
                for (f in loaded) {
                    val existing = f.value ?: saved.optString(f.key).takeIf { it.isNotEmpty() } ?: f.defaultValue
                    if (f.key !in values || f.value != null) values[f.key] = existing
                }
            } catch (e: Exception) {
                message = e.message ?: "登录表单加载失败"
            } finally {
                busy = false
                loggedIn = LegadoLogin.isLoggedIn(source)
            }
        }
    }

    LaunchedEffect(source.id) { loadForm("{}") }

    GlassBottomSheet(onDismiss, title = "书源登录 · ${source.name}", contentPadding = PaddingValues(horizontal = 16.dp)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Status row
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.glass).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (loggedIn) c.ok else c.tertiary))
                Text(
                    if (loggedIn) "已保存登录态 / Cookie" else "未登录",
                    color = if (loggedIn) c.ok else c.secondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                if (isV2) DSStatus("Login UI v2", StatusTone.Accent)
                if (loggedIn) {
                    MiniButton("退出登录") {
                        LegadoLogin.logout(source)
                        values.clear(); fieldErrors.clear()
                        loggedIn = false; message = "已清除登录信息与 Cookie"
                    }
                }
            }

            if (loginPageUrl != null) {
                GlassButton("在内置浏览器里登录（自动同步 Cookie）", Modifier.fillMaxWidth(), ButtonTone.Tinted, icon = Icons.Rounded.Language) {
                    openBrowserUrl = loginPageUrl
                }
            }

            // Dynamic form rows
            val inputs = fields.filter { it.type != "button" }
            val buttons = fields.filter { it.type == "button" }
            for (f in inputs) {
                when (f.type) {
                    "label" -> Text(f.value ?: f.name, color = c.secondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp))
                    "select", "toggle" -> {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(f.name, color = c.secondary, fontSize = 12.sp)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (opt in f.options) {
                                    ToneChip(opt, values[f.key] == opt, small = true) { values[f.key] = opt }
                                }
                            }
                        }
                    }
                    else -> {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            GlassTextField(
                                value = values[f.key].orEmpty(),
                                onValueChange = { values[f.key] = it; fieldErrors.remove(f.key) },
                                placeholder = f.name,
                                singleLine = true,
                            )
                            fieldErrors[f.key]?.let { err -> Text(err, color = c.danger, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp)) }
                        }
                    }
                }
            }

            if (inputs.isNotEmpty() && !isV2) {
                GlassButton("保存表单", Modifier.fillMaxWidth(), ButtonTone.Glass) {
                    LegadoLogin.setLoginInfo(source, values.toMap())
                    loggedIn = LegadoLogin.isLoggedIn(source)
                    message = "已保存登录信息"
                }
            }

            // Action buttons (v1 or v2)
            for (b in buttons) {
                GlassButton(b.name, Modifier.fillMaxWidth(), ButtonTone.Tinted, enabled = !busy) {
                    scope.launch {
                        busy = true; message = null; fieldErrors.clear()
                        try {
                            if (isV2) {
                                val res = withContext(Dispatchers.IO) {
                                    LegadoLogin.performActionV2(source, b.action ?: b.key, v2StateJson, values.toMap())
                                }
                                fieldErrors.putAll(res.errors)
                                res.stateJson?.let { v2StateJson = it }
                                res.message?.let { message = it }
                                loggedIn = LegadoLogin.isLoggedIn(source)
                                if (res.close) { onDismiss(); return@launch }
                                val nextState = res.stateJson
                                if (nextState != null) loadForm(nextState)
                            } else {
                                LegadoLogin.setLoginInfo(source, values.toMap())
                                val out = withContext(Dispatchers.IO) { LegadoLogin.runActionV1(source, b.action.orEmpty()) }
                                message = out.ifBlank { "已执行" }
                                loggedIn = LegadoLogin.isLoggedIn(source)
                            }
                        } catch (e: Exception) {
                            message = e.message ?: "执行失败"
                        } finally { busy = false }
                    }
                }
            }

            if (!isV2 && hasLoginJs) {
                GlassButton(if (busy) "登录中…" else "登录", Modifier.fillMaxWidth(), size = ButtonSize.Large, enabled = !busy, loading = busy) {
                    scope.launch {
                        busy = true; message = null
                        try {
                            LegadoLogin.setLoginInfo(source, values.toMap())
                            val out = withContext(Dispatchers.IO) { LegadoLogin.runActionV1(source) }
                            message = out.ifBlank { "登录完成" }
                            loggedIn = LegadoLogin.isLoggedIn(source)
                        } catch (e: Exception) {
                            message = e.message ?: "登录失败"
                        } finally { busy = false }
                    }
                }
            }

            message?.let { Text(it, color = c.accent, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(vertical = 4.dp)) }
            Spacer(Modifier.height(16.dp))
        }
    }

    openBrowserUrl?.let { url ->
        NovelWebLoginDialog(
            url = url,
            title = "登录 · ${source.name}",
            userAgent = NovelSources.headers(source).optString("User-Agent").ifBlank { NovelSources.userAgent },
            onDone = { liveCookies ->
                if (!liveCookies.isNullOrBlank()) LegadoCookies.setManual(url, liveCookies)
                LegadoLogin.markWebLoggedIn(source)
                loggedIn = true
                message = "已从内置浏览器同步 Cookie"
                openBrowserUrl = null
            },
            onCancel = { openBrowserUrl = null },
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun NovelWebLoginDialog(
    url: String,
    title: String,
    userAgent: String,
    onDone: (String?) -> Unit,
    onCancel: () -> Unit,
) {
    val c = IdjThemeColors
    var webRef by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf(url) }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PlainIconButton(Icons.Rounded.Close, "取消", tint = c.text) { onCancel() }
                Text(title, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                PlainIconButton(Icons.Rounded.Refresh, "刷新", tint = c.secondary) { webRef?.reload() }
                MiniButton("完成") {
                    val cm = CookieManager.getInstance()
                    cm.flush()
                    val cookies = cm.getCookie(currentUrl) ?: cm.getCookie(url)
                    onDone(cookies)
                }
            }
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = userAgent
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, u: String?) {
                                if (!u.isNullOrBlank()) currentUrl = u
                            }
                        }
                        loadUrl(url)
                        webRef = this
                    }
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}

// MARK: - 2. Single-Source Step-by-Step Debugger Sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NovelSourceDebugSheet(
    source: NovelSource,
    onDismiss: () -> Unit,
) {
    val c = IdjThemeColors
    val scope = rememberCoroutineScope()
    var query by remember(source.id) { mutableStateOf(source.rules("ruleSearch").optString("checkKeyWord").ifBlank { "我的" }) }
    var busy by remember { mutableStateOf(false) }
    val events = remember { mutableStateListOf<LegadoTools.DebugEvent>() }
    var succeeded by remember { mutableStateOf<Boolean?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun runDebug() {
        job?.cancel()
        events.clear(); succeeded = null; busy = true
        job = scope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    LegadoTools.debug(source, query) { ev ->
                        scope.launch(Dispatchers.Main) { events.add(ev) }
                    }
                }
                succeeded = report.succeeded
            } finally { busy = false }
        }
    }

    LaunchedEffect(source.id) { runDebug() }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }

    GlassBottomSheet(onDismiss, title = "调试 · ${source.name}", contentPadding = PaddingValues(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassTextField(query, { query = it }, Modifier.weight(1f), placeholder = "关键词或详情网址", singleLine = true)
            MiniButton(if (busy) "停止" else "调试") {
                if (busy) { job?.cancel(); busy = false } else runDebug()
            }
        }
        succeeded?.let { ok ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (ok) c.okSoft else c.warmSoft).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, null, Modifier.size(16.dp), tint = if (ok) c.ok else c.accentWarm)
                Text(if (ok) "调试通过（搜索/发现 → 详情 → 目录 → 正文）" else "调试失败，请查看下方错误定位", color = if (ok) c.ok else c.accentWarm, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 460.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(events) { ev ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.glass).padding(horizontal = 10.dp, vertical = 7.dp)) {
                    Text("[+${ev.timeMs}ms] ${ev.stage.uppercase()}", color = if (ev.isError) c.danger else c.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                    Text(ev.message, color = if (ev.isError) c.danger else c.text, fontSize = 12.sp, lineHeight = 17.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

// MARK: - 3. Batch Source Validator Sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NovelSourceCheckSheet(
    sources: List<NovelSource>,
    onDisableInvalid: (Set<String>) -> Unit,
    onDeleteInvalid: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = IdjThemeColors
    val scope = rememberCoroutineScope()
    var keyword by remember { mutableStateOf("我的") }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(sources.size) }
    val results = remember { mutableStateListOf<LegadoTools.CheckResult>() }
    var filter by remember { mutableStateOf("全部") }
    var job by remember { mutableStateOf<Job?>(null) }

    fun startCheck() {
        job?.cancel()
        results.clear(); done = 0; total = sources.size; busy = true
        job = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    LegadoTools.checkBatch(
                        sources = sources,
                        keyword = keyword,
                        concurrency = 4,
                        checkContent = true,
                        isCancelled = { !isActive },
                    ) { d, t, r ->
                        scope.launch(Dispatchers.Main) { done = d; total = t; results.add(r) }
                    }
                }
            } finally { busy = false }
        }
    }

    DisposableEffect(Unit) { onDispose { job?.cancel() } }

    val okCount = results.count { it.status == LegadoTools.CheckStatus.OK }
    val badIds = results.filter { it.status != LegadoTools.CheckStatus.OK }.mapTo(HashSet()) { it.sourceId }
    val shown = when (filter) {
        "正常" -> results.filter { it.status == LegadoTools.CheckStatus.OK }
        "失效" -> results.filter { it.status != LegadoTools.CheckStatus.OK }
        else -> results
    }

    GlassBottomSheet(onDismiss, title = "批量校验书源", contentPadding = PaddingValues(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassTextField(keyword, { keyword = it }, Modifier.weight(1f), placeholder = "校验关键词（默认：我的）", singleLine = true)
            MiniButton(if (busy) "停止" else "开始") {
                if (busy) { job?.cancel(); busy = false } else startCheck()
            }
        }
        if (busy || results.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            if (total > 0) DSProgressBar(done.toFloat() / total.coerceAtLeast(1))
            Text("进度 $done / $total · 正常 $okCount · 失效 ${badIds.size}", color = c.secondary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("全部", "正常", "失效").forEach { f -> ToneChip(f, filter == f, small = true) { filter = f } }
            }
        }
        if (!busy && badIds.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton("禁用 ${badIds.size} 个失效源", Modifier.weight(1f), ButtonTone.Tinted) { onDisableInvalid(badIds) }
                GlassButton("删除失效源", Modifier.weight(1f), ButtonTone.Danger) { onDeleteInvalid(badIds); onDismiss() }
            }
        }
        LazyColumn(Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 180.dp, max = 380.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(shown, key = { it.sourceId }) { r ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.glass).padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(r.sourceName, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${r.latencyMs}ms · ${r.detail}", color = c.secondary, fontSize = 11.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    DSStatus(r.status.label, if (r.status == LegadoTools.CheckStatus.OK) StatusTone.Ok else StatusTone.Warn)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

// MARK: - 4. Replace / Purify Rules Sheet (with Live Preview + Legado JSON Import/Export)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NovelReplaceRulesSheet(onDismiss: () -> Unit) {
    val c = IdjThemeColors
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    val rules = remember(revision) { LegadoReplaceStore.all() }
    var editing by remember { mutableStateOf<LegadoReplaceRule?>(null) }
    var toastMsg by remember { mutableStateOf<String?>(null) }

    GlassBottomSheet(onDismiss, title = "替换净化规则", contentPadding = PaddingValues(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton("新建规则", Modifier.weight(1f), ButtonTone.Tinted, icon = Icons.Rounded.Add) {
                editing = LegadoReplaceRule(id = System.currentTimeMillis(), name = "新净化规则")
            }
            GlassButton("粘贴导入", Modifier.weight(1f), ButtonTone.Glass) {
                val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                toastMsg = runCatching { "已导入 ${LegadoReplaceStore.importJson(clip)} 条规则" }.getOrElse { "导入失败：${it.message}" }
                revision++
            }
            GlassButton("复制导出", Modifier.weight(1f), ButtonTone.Glass) {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("replace-rules", LegadoReplaceStore.exportJson()))
                toastMsg = "已复制 ${rules.size} 条规则 JSON"
            }
        }
        toastMsg?.let { Text(it, color = c.accent, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }

        LazyColumn(Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 180.dp, max = 400.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (rules.isEmpty()) {
                item { Text("暂无替换净化规则。支持直接粘贴导入 Legado 替换规则 JSON。", color = c.secondary, fontSize = 13.sp, modifier = Modifier.padding(16.dp)) }
            }
            items(rules, key = { it.id }) { r ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.glass)
                        .clickable { editing = r }.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name.ifBlank { r.pattern }, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${if (r.isRegex) "正则" else "文本"} · ${r.pattern} → ${r.replacement.ifEmpty { "(删除)" }}", color = c.secondary, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    GlassSwitch(r.isEnabled, { LegadoReplaceStore.setEnabled(r.id, it); revision++ }, label = r.name)
                    PlainIconButton(Icons.Rounded.DeleteOutline, "删除", tint = c.danger) { LegadoReplaceStore.remove(r.id); revision++ }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    editing?.let { initial ->
        var name by remember(initial.id) { mutableStateOf(initial.name) }
        var pattern by remember(initial.id) { mutableStateOf(initial.pattern) }
        var replacement by remember(initial.id) { mutableStateOf(initial.replacement) }
        var scopeText by remember(initial.id) { mutableStateOf(initial.scope) }
        var isRegex by remember(initial.id) { mutableStateOf(initial.isRegex) }
        var scopeTitle by remember(initial.id) { mutableStateOf(initial.scopeTitle) }
        var scopeContent by remember(initial.id) { mutableStateOf(initial.scopeContent) }
        var sample by remember(initial.id) { mutableStateOf("第一章 试读\n正文内容（请记住本站域名 www.example.com）") }

        val draft = initial.copy(name = name, pattern = pattern, replacement = replacement, scope = scopeText, isRegex = isRegex, scopeTitle = scopeTitle, scopeContent = scopeContent)
        val preview = remember(draft, sample) { draft.applyTo(sample) }

        GlassBottomSheet({ editing = null }, title = "编辑净化规则", contentPadding = PaddingValues(horizontal = 16.dp)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassTextField(name, { name = it }, placeholder = "规则名称", singleLine = true)
                GlassTextField(pattern, { pattern = it }, placeholder = "匹配内容（正则或纯文本）", singleLine = false, maxLines = 3)
                GlassTextField(replacement, { replacement = it }, placeholder = "替换为（留空表示删除）", singleLine = false, maxLines = 2)
                GlassTextField(scopeText, { scopeText = it }, placeholder = "作用范围（书名或书源 URL，逗号分隔，留空全局生效）", singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToneChip("正则", isRegex, small = true) { isRegex = !isRegex }
                    ToneChip("作用于正文", scopeContent, small = true) { scopeContent = !scopeContent }
                    ToneChip("作用于标题", scopeTitle, small = true) { scopeTitle = !scopeTitle }
                }
                Text("实时预览样本", color = c.secondary, fontSize = 12.sp)
                GlassTextField(sample, { sample = it }, placeholder = "输入测试文本", singleLine = false, maxLines = 3)
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.glass).padding(10.dp)) {
                    Text("预览结果", color = c.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text(preview, color = c.text, fontSize = 13.sp, lineHeight = 18.sp)
                }
                GlassButton("保存规则", Modifier.fillMaxWidth(), size = ButtonSize.Large, enabled = pattern.isNotEmpty()) {
                    LegadoReplaceStore.upsert(draft)
                    revision++
                    editing = null
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

// MARK: - 5. Source Code Editor Sheet (JSON or Single-File mainJs)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NovelSourceEditorSheet(
    source: NovelSource,
    onSave: (NovelSource) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = IdjThemeColors
    val isJs = source.isJsSource
    var text by remember(source.id) { mutableStateOf(if (isJs) source.mainJs else source.json.toString(2)) }
    var error by remember { mutableStateOf<String?>(null) }

    GlassBottomSheet(onDismiss, title = if (isJs) "编辑 JS 书源 · ${source.name}" else "编辑书源 JSON · ${source.name}", contentPadding = PaddingValues(horizontal = 16.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassTextField(
                value = text,
                onValueChange = { text = it; error = null },
                modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp, max = 420.dp),
                placeholder = if (isJs) "单文件 JS 书源脚本" else "Legado 书源 JSON",
                singleLine = false,
                maxLines = 24,
            )
            error?.let { Text(it, color = c.danger, fontSize = 12.sp) }
            GlassButton("校验并保存", Modifier.fillMaxWidth(), size = ButtonSize.Large) {
                runCatching {
                    val updated = if (isJs) LegadoJsSource.extract(text) else NovelSource(JSONObject(text))
                    onSave(updated)
                    onDismiss()
                }.onFailure { error = it.message ?: "格式错误" }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

// MARK: - 6. Paragraph Review Sheet (段评)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NovelReviewSheet(
    source: NovelSource,
    chapter: NovelChapter,
    bookUrl: String,
    bookName: String,
    onDismiss: () -> Unit,
) {
    val c = IdjThemeColors
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf<LegadoReview.Summary?>(null) }
    var selectedPara by remember { mutableStateOf<Int?>(null) }
    var detail by remember { mutableStateOf<LegadoReview.DetailPage?>(null) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(source.id, chapter.url) {
        busy = true; error = null
        try {
            val s = withContext(Dispatchers.IO) { LegadoReview.summary(source, chapter, bookUrl, bookName) }
            summary = s
            val firstPara = s.counts.keys.sorted().firstOrNull()
            selectedPara = firstPara
            if (firstPara != null) {
                detail = withContext(Dispatchers.IO) { LegadoReview.detail(source, chapter, firstPara, s.paraData[firstPara].orEmpty(), 1, bookUrl, bookName) }
            }
        } catch (e: Exception) {
            error = e.message ?: "段评加载失败"
        } finally { busy = false }
    }

    GlassBottomSheet(onDismiss, title = "本章段评 · ${chapter.title}", contentPadding = PaddingValues(horizontal = 16.dp)) {
        if (busy) GlassLoadingBar(Modifier.padding(vertical = 12.dp))
        error?.let { Text(it, color = c.danger, fontSize = 13.sp) }
        val paras = summary?.counts?.entries?.sortedBy { it.key }.orEmpty()
        if (paras.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((idx, cnt) in paras) {
                    val label = if (idx < 0) "标题 ($cnt)" else "第${idx}段 ($cnt)"
                    ToneChip(label, selectedPara == idx, small = true) {
                        selectedPara = idx
                        scope.launch {
                            busy = true
                            try {
                                detail = withContext(Dispatchers.IO) { LegadoReview.detail(source, chapter, idx, summary?.paraData?.get(idx).orEmpty(), 1, bookUrl, bookName) }
                            } catch (e: Exception) { error = e.message } finally { busy = false }
                        }
                    }
                }
            }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val itemsList = detail?.items.orEmpty()
            if (!busy && itemsList.isEmpty()) item { Text("暂无段评", color = c.secondary, fontSize = 13.sp, modifier = Modifier.padding(16.dp)) }
            items(itemsList, key = { it.id }) { cm ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.glass).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(cm.name, color = c.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        if (cm.time.isNotBlank()) Text(cm.time, color = c.tertiary, fontSize = 11.sp)
                        Spacer(Modifier.weight(1f))
                        if (cm.likeCount > 0) Text("赞 ${cm.likeCount}", color = c.secondary, fontSize = 11.sp)
                    }
                    Text(cm.content, color = c.text, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

// MARK: - 7. Inline Decoded Chapter Image Renderer (`<img src="..."/>` & Manga type=2)

internal sealed class ChapterSegment {
    data class TextBlock(val text: String) : ChapterSegment()
    data class ImageBlock(val url: String) : ChapterSegment()
}

private val imgTagRegex = Regex("""<img\s+src="([^"]+)"\s*/?>""", RegexOption.IGNORE_CASE)

internal fun splitChapterSegments(body: String): List<ChapterSegment> {
    if (!body.contains("<img", ignoreCase = true)) return listOf(ChapterSegment.TextBlock(body))
    val out = ArrayList<ChapterSegment>()
    var cursor = 0
    for (m in imgTagRegex.findAll(body)) {
        val before = body.substring(cursor, m.range.first).trim()
        if (before.isNotEmpty()) out += ChapterSegment.TextBlock(before)
        out += ChapterSegment.ImageBlock(m.groupValues[1])
        cursor = m.range.last + 1
    }
    val tail = body.substring(cursor).trim()
    if (tail.isNotEmpty()) out += ChapterSegment.TextBlock(tail)
    return out
}

@Composable
internal fun NovelInlineImage(
    url: String,
    source: NovelSource?,
    modifier: Modifier = Modifier,
) {
    val c = IdjThemeColors
    var bitmap by remember(url, source?.id) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(url, source?.id) { mutableStateOf(false) }

    LaunchedEffect(url, source?.id) {
        failed = false; bitmap = null
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val headers = source?.let { NovelSources.headers(it) }
                val resp = NovelSources.fetchBytes(url, "GET", headers, byteArrayOf(), source = source)
                require(resp.status in 200..299 && resp.bytes.isNotEmpty()) { "HTTP ${resp.status}" }
                val bytes = if (source != null) LegadoJsSource.decodeImageBytes(source, url, resp.bytes, isCover = false) else resp.bytes
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }
        if (loaded != null) bitmap = loaded else failed = true
    }

    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            modifier = modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(8.dp)),
        )
    } else if (failed) {
        Box(modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(8.dp)).background(c.glass).padding(16.dp), contentAlignment = Alignment.Center) {
            Text("图片加载失败", color = c.secondary, fontSize = 12.sp)
        }
    } else {
        Box(modifier.fillMaxWidth().height(180.dp).padding(vertical = 6.dp).clip(RoundedCornerShape(8.dp)).background(c.glass), contentAlignment = Alignment.Center) {
            GlassSpinner(size = 20.dp)
        }
    }
}
