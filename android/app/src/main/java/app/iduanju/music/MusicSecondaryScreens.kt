package app.iduanju.music

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Message
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.iduanju.IdjApp
import app.iduanju.ui.LocalNavigate
import app.iduanju.ui.components.LocalLayoutMetrics
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Navigation route builders for Music secondary pages. */
object MusicRoutes {
    fun artist(name: String, artistId: String?, sourceId: String, cover: String = ""): String =
        "music/artist?name=${Uri.encode(name)}&id=${Uri.encode(artistId.orEmpty())}&src=${Uri.encode(sourceId)}&cover=${Uri.encode(cover)}"

    fun album(name: String, albumId: String?, artist: String, artistId: String?, sourceId: String, cover: String = ""): String =
        "music/album?name=${Uri.encode(name)}&id=${Uri.encode(albumId.orEmpty())}&artist=${Uri.encode(artist)}&aid=${Uri.encode(artistId.orEmpty())}&src=${Uri.encode(sourceId)}&cover=${Uri.encode(cover)}"

    fun chart(chartId: String): String = "music/chart/${Uri.encode(chartId)}"
    fun playlist(playlistId: String): String = "music/playlist/${Uri.encode(playlistId)}"
}

// ── 1. Dedicated Music & Sources Settings Screen (Mounted in 我的 -> 音乐与音源设置) ─────────────────

@Composable
fun MusicSettingsScreen(onBack: () -> Unit) {
    val store = IdjApp.instance.musicStore
    val player = IdjApp.instance.musicPlayer
    val metrics = LocalLayoutMetrics.current
    val scope = rememberCoroutineScope()

    var loginPref by remember { mutableStateOf<MusicSourcePref?>(null) }
    var showAddNAS by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    fun notify(msg: String) {
        toast = msg
        scope.launch {
            delay(2200)
            if (toast == msg) toast = null
        }
    }

    val bottomPad = if (player.shouldShowGlobalMiniBar) 96.dp else 32.dp

    Box(Modifier.fillMaxSize().background(YMColors.base)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            MusicSecondaryTopBar(title = "音乐与音源设置", onBack = onBack)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = metrics.pagePadding, end = metrics.pagePadding, top = 8.dp, bottom = bottomPad),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item("intro") {
                    YMDashedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("100% 单软件无服务端架构", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = YMColors.fg)
                            Text("支持网易云（手机号 / 邮箱 / 扫码 / 桌面网页）、QQ 音乐（QQ 与微信扫码 / 手机号 / 自动续期）、酷狗（手机号 / 酷狗与微信扫码 / 无损直链）、汽水音乐（抖音扫码 / 榜单直连与歌词）与个人 NAS 原流直连，所有凭证与歌单仅保存于本机。", fontSize = 12.sp, color = YMColors.secondary, lineHeight = 17.sp)
                        }
                    }
                }

                // 1. Source Account Logins
                item("login_hdr") { Text("音源账号登录（解锁无损 / 完整直链）", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = YMColors.secondary) }
                itemsIndexed(
                    store.sourcePrefs.filter { it.kind in setOf(MusicSourceKind.NETEASE, MusicSourceKind.QQ, MusicSourceKind.KUGOU, MusicSourceKind.QISHUI) },
                    key = { _, p -> "login-${p.id}" },
                ) { _, pref ->
                    val loggedIn = !pref.customCookie.isNullOrBlank()
                    YMDashedCard(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (!pref.accountAvatarURL.isNullOrBlank()) {
                                YMCoverImage(pref.accountAvatarURL, Modifier.size(40.dp), isCircle = true)
                            } else {
                                Box(
                                    Modifier.size(40.dp).clip(CircleShape)
                                        .background(if (loggedIn) YMColors.Lime.copy(alpha = 0.2f) else YMColors.fill),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Filled.Person, null, tint = if (loggedIn) YMColors.accent else YMColors.secondary, modifier = Modifier.size(20.dp))
                                }
                            }
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(pref.kind.fullName, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = YMColors.fg)
                                    if (loggedIn) YMQualityPill("已登录", isLossless = true)
                                }
                                Text(
                                    if (loggedIn) pref.accountNickname ?: "已保存本机登录凭证"
                                    else when (pref.kind) {
                                        MusicSourceKind.NETEASE -> "手机号 / 邮箱 / 扫码 / 桌面网页"
                                        MusicSourceKind.QQ -> "QQ与微信扫码 / 手机号 / 桌面网页"
                                        MusicSourceKind.KUGOU -> "手机号 / 酷狗与微信扫码 / 桌面网页"
                                        MusicSourceKind.QISHUI -> "抖音扫码 / 桌面网页（免登录也可播）"
                                        else -> "支持内置网页登录"
                                    },
                                    fontSize = 11.5.sp,
                                    color = YMColors.secondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (loggedIn) {
                                TextButton(onClick = {
                                    store.logoutSource(pref.id)
                                    notify("已退出 ${pref.kind.fullName}")
                                }) { Text("退出", color = YMColors.secondary, fontSize = 12.sp) }
                            }
                            Box(
                                Modifier
                                    .clip(CircleShape)
                                    .background(if (YMColors.dark) Color.White else Color.Black)
                                    .clickable { loginPref = pref }
                                    .padding(horizontal = 14.dp, vertical = 7.dp),
                            ) {
                                Text(if (loggedIn) "重登" else "登录", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (YMColors.dark) Color.Black else Color.White)
                            }
                        }
                    }
                }

                // 1.5 Preferred Audio Quality
                item("quality_hdr") { Text("默认播放音质（支持播放中实时切换）", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = YMColors.secondary) }
                item("quality_card") {
                    YMDashedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            MusicAudioQuality.entries.forEach { q ->
                                val on = store.settings.preferredQuality == q
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (on) YMColors.Lime.copy(alpha = 0.14f) else Color.Transparent)
                                        .clickable {
                                            IdjApp.instance.musicPlayer.switchQuality(q)
                                            notify("默认音质已设为 ${q.label}")
                                        }
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(q.label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (on) YMColors.accent else YMColors.fg)
                                            if (q == MusicAudioQuality.HIRES || q == MusicAudioQuality.LOSSLESS) {
                                                YMQualityPill(if (q == MusicAudioQuality.HIRES) "HR" else "SQ", isLossless = true)
                                            }
                                        }
                                        Text(q.detail, fontSize = 11.5.sp, color = YMColors.secondary, lineHeight = 16.sp)
                                    }
                                    if (on) Text("当前", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = YMColors.accent)
                                }
                            }
                        }
                    }
                }

                // 2. Source Priority
                item("prio_hdr") { Text("在线音源优先级（主源受限时自动换源）", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = YMColors.secondary) }
                itemsIndexed(store.sourcePrefs, key = { _, p -> p.id }) { idx, pref ->
                    YMDashedCard(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("${idx + 1}", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = YMColors.accent, modifier = Modifier.width(18.dp))
                            Column(Modifier.weight(1f)) {
                                Text(pref.kind.fullName, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = YMColors.fg)
                                Text(if (!pref.customCookie.isNullOrBlank()) "账号直连" else "零配置直连", fontSize = 11.5.sp, color = YMColors.secondary)
                            }
                            IconButton(onClick = { store.moveSource(pref.id, up = true) }, enabled = idx > 0, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Filled.ArrowUpward, "上移", tint = if (idx > 0) YMColors.fg else YMColors.tertiary, modifier = Modifier.size(16.dp))
                            }
                            IconButton(onClick = { store.moveSource(pref.id, up = false) }, enabled = idx < store.sourcePrefs.lastIndex, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Filled.ArrowDownward, "下移", tint = if (idx < store.sourcePrefs.lastIndex) YMColors.fg else YMColors.tertiary, modifier = Modifier.size(16.dp))
                            }
                            Switch(
                                checked = pref.enabled,
                                onCheckedChange = { store.setSourceEnabled(pref.id, it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = YMColors.Lime),
                            )
                        }
                    }
                }

                // 3. Resume Policy
                item("resume_hdr") { Text("看视频 / 听书打断后的续播策略", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = YMColors.secondary) }
                item("resume_card") {
                    YMDashedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            MusicResumePolicy.entries.forEach { policy ->
                                val on = AppAudioArbiter.resumePolicy == policy
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (on) YMColors.Lime.copy(alpha = 0.14f) else Color.Transparent)
                                        .clickable { AppAudioArbiter.updatePolicy(policy) }
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(policy.label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (on) YMColors.accent else YMColors.fg)
                                        Text(policy.detail, fontSize = 11.5.sp, color = YMColors.secondary, lineHeight = 16.sp)
                                    }
                                    if (on) Text("当前", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = YMColors.accent)
                                }
                            }
                        }
                    }
                }

                // 4. Personal NAS
                item("nas_hdr") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("个人音乐服务器（直连 NAS）", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = YMColors.secondary)
                        Text("添加 NAS", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = YMColors.accent, modifier = Modifier.clickable { showAddNAS = true })
                    }
                }
                itemsIndexed(store.nasConfigs, key = { _, n -> n.id }) { _, cfg ->
                    YMDashedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Filled.Storage, null, tint = YMColors.accent)
                            Column(Modifier.weight(1f)) {
                                Text("${cfg.name} · ${cfg.kind.label}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = YMColors.fg)
                                Text(cfg.serverURL, fontSize = 11.5.sp, color = YMColors.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text("同步", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = YMColors.accent, modifier = Modifier.clickable {
                                scope.launch {
                                    notify("正在直连 ${cfg.name}…")
                                    val t = MusicProviderHub.fetchNASTracks(cfg)
                                    store.updateNASTracks(t)
                                    notify(if (t.isNotEmpty()) "已直连 ${cfg.name}：加载 ${t.size} 首" else "连接失败，请核对地址与凭证")
                                }
                            })
                            IconButton(onClick = { store.deleteNAS(cfg.id) }, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Filled.DeleteOutline, "删除", tint = YMColors.tertiary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                // 5. Fallback + LRCLIB + Cache
                item("cache_card") {
                    YMDashedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("失败自动换源", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = YMColors.fg)
                                    Text("优先 NAS 无损，再跨源补全完整音轨", fontSize = 11.5.sp, color = YMColors.secondary)
                                }
                                Switch(
                                    checked = store.settings.autoFallback,
                                    onCheckedChange = { v -> store.updateSettings { it.copy(autoFallback = v) } },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = YMColors.Lime),
                                )
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("LRCLIB 社区逐行歌词补全", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = YMColors.fg)
                                    Text("外语 / 独立歌曲缺词时直连补齐", fontSize = 11.5.sp, color = YMColors.secondary)
                                }
                                Switch(
                                    checked = store.settings.lrclibEnabled,
                                    onCheckedChange = { v -> store.updateSettings { it.copy(lrclibEnabled = v) } },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = YMColors.Lime),
                                )
                            }
                            HorizontalDivider(color = YMColors.border)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column {
                                    Text("本机离线音频缓存", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = YMColors.fg)
                                    Text("已缓存 ${store.cachedCount} 首 · ${store.formattedCacheSize}", fontSize = 11.5.sp, color = YMColors.secondary)
                                }
                                Text("清空", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = YMColors.Pink, modifier = Modifier.clickable {
                                    store.clearCache()
                                    notify("已清空离线音乐缓存")
                                })
                            }
                        }
                    }
                }
            }
        }

        toast?.let { msg ->
            Text(
                msg,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (YMColors.dark) Color.Black else Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 56.dp)
                    .clip(CircleShape)
                    .background(if (YMColors.dark) YMColors.Lime else Color.Black)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }

    loginPref?.let { pref ->
        MusicSourceLoginDialog(
            pref = pref,
            onDismiss = { loginPref = null },
            onSuccess = { msg ->
                loginPref = null
                notify(msg)
            },
        )
    }

    if (showAddNAS) {
        MusicAddNASDialog(
            onDismiss = { showAddNAS = false },
            onSave = { cfg ->
                store.saveNAS(cfg)
                showAddNAS = false
                notify("已保存 ${cfg.kind.label} 直连配置")
                scope.launch {
                    val tracks = MusicProviderHub.fetchNASTracks(cfg)
                    store.updateNASTracks(tracks)
                    if (tracks.isNotEmpty()) notify("已直连 ${cfg.name}：加载 ${tracks.size} 首")
                }
            },
        )
    }
}

// ── 2. Real Serverless Source Login Dialog (Phone SMS/Pwd + Native QR + Anti-H5 Desktop WebView + Manual) ──

internal object MusicWebLoginGuard {
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val allowedSchemes = setOf("http", "https", "about", "data", "blob", "javascript")

    private val blockedHosts = setOf(
        "a.app.qq.com",
        "apps.apple.com",
        "itunes.apple.com",
        "android.myapp.com",
        "sj.qq.com",
        "durl.kugou.com",
        "dl.kugou.com",
        "down.kugou.com",
        "download.kugou.com",
        "t.kugou.com",
        "m.kugou.com",
        "lf-luna.douyin.com",
        "d.douyin.com",
        "v.douyin.com",
        "zlink.fqnovel.com",
    )

    private val blockedPathFragments = listOf(
        "/m/applink",
        "/applink",
        "/openapp",
        "/appdownload",
        "/download/app",
        "/download_app",
        "/luna/download",
        "/qishui/download",
        "/store/app",
        "/m/down",
        "/r/redirect",
        "/client/download",
        "openinapp",
    )

    fun desktopLoginUrl(kind: MusicSourceKind): String = when (kind) {
        MusicSourceKind.NETEASE -> "https://music.163.com/#/login"
        MusicSourceKind.QQ -> "https://y.qq.com/"
        MusicSourceKind.KUGOU -> "https://www.kugou.com/"
        MusicSourceKind.QISHUI -> "https://music.douyin.com/"
        else -> "https://music.163.com/#/login"
    }

    fun cookieDomains(kind: MusicSourceKind): List<String> = when (kind) {
        MusicSourceKind.NETEASE -> listOf("https://music.163.com/", "https://interface.music.163.com/")
        MusicSourceKind.QQ -> listOf("https://y.qq.com/", "https://u.y.qq.com/", "https://qq.com/")
        MusicSourceKind.KUGOU -> listOf("https://www.kugou.com/", "https://kugou.com/", "https://login-user.kugou.com/")
        MusicSourceKind.QISHUI -> listOf("https://music.douyin.com/", "https://api.qishui.com/", "https://www.douyin.com/")
        else -> listOf("https://music.163.com/")
    }

    fun shouldBlock(rawUrl: String?): Boolean {
        val u = rawUrl?.trim().orEmpty()
        if (u.isEmpty()) return false
        val uri = runCatching { Uri.parse(u) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme.isNotEmpty() && scheme !in allowedSchemes) return true
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase().orEmpty()
        if (host in blockedHosts) return true
        val lower = u.lowercase()
        return blockedPathFragments.any { lower.contains(it) }
    }

    fun mergeCookies(rawCookieStrings: List<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (raw in rawCookieStrings) {
            if (raw.isBlank()) continue
            for (part in raw.split(';')) {
                val t = part.trim()
                val idx = t.indexOf('=')
                if (idx > 0) {
                    val k = t.substring(0, idx).trim()
                    val v = t.substring(idx + 1).trim()
                    if (k.isNotEmpty() && v.isNotEmpty()) out[k] = v
                }
            }
        }
        return out
    }

    fun matchesLoggedInCookies(cookies: Map<String, String>, kind: MusicSourceKind): Boolean {
        fun has(k: String) = !cookies[k].isNullOrBlank()
        return when (kind) {
            MusicSourceKind.NETEASE -> has("MUSIC_U")
            MusicSourceKind.QQ -> has("qm_keyst") || has("qqmusic_key") || has("musickey") || (has("uin") && has("skey"))
            MusicSourceKind.KUGOU -> has("t") || has("token") || has("KugooID")
            MusicSourceKind.QISHUI -> has("sessionid") || has("sessionid_ss") || has("sid_tt")
            else -> false
        }
    }

    val antiH5Script: String = """
        (function() {
          try {
            Object.defineProperty(navigator, 'platform', { get: function() { return 'Win32'; }, configurable: true });
            Object.defineProperty(navigator, 'maxTouchPoints', { get: function() { return 0; }, configurable: true });
            Object.defineProperty(navigator, 'userAgentData', {
              get: function() { return { mobile: false, platform: 'Windows', brands: [{ brand: 'Chromium', version: '131' }] }; },
              configurable: true
            });
          } catch (e) {}
          var isBlockedUrl = function(u) {
            if (!u || typeof u !== 'string') return false;
            var s = u.trim().toLowerCase();
            if (!s || s.startsWith('/') || s.startsWith('#') || s.startsWith('.')) return false;
            if (/^[a-z][a-z0-9+.-]*:/.test(s)) {
              if (!(s.startsWith('http:') || s.startsWith('https:') || s.startsWith('about:') || s.startsWith('data:') || s.startsWith('blob:') || s.startsWith('javascript:'))) return true;
            }
            return s.indexOf('/m/applink') >= 0 || s.indexOf('a.app.qq.com') >= 0 || s.indexOf('/openapp') >= 0;
          };
          try {
            var origOpen = window.open;
            window.open = function(url) {
              if (isBlockedUrl(url)) return null;
              if (origOpen) return origOpen.apply(window, arguments);
              return null;
            };
          } catch (e) {}
          try {
            var desc = Object.getOwnPropertyDescriptor(HTMLIFrameElement.prototype, 'src');
            if (desc && desc.set) {
              Object.defineProperty(HTMLIFrameElement.prototype, 'src', {
                get: desc.get,
                set: function(v) { if (!isBlockedUrl(v)) desc.set.call(this, v); },
                configurable: true
              });
            }
            var origSetAttr = Element.prototype.setAttribute;
            Element.prototype.setAttribute = function(k, v) {
              if (this.tagName === 'IFRAME' && String(k).toLowerCase() === 'src' && isBlockedUrl(v)) return;
              return origSetAttr.call(this, k, v);
            };
          } catch (e) {}
        })();
    """.trimIndent()
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MusicSourceLoginDialog(
    pref: MusicSourcePref,
    onDismiss: () -> Unit,
    onSuccess: (String) -> Unit,
) {
    val store = IdjApp.instance.musicStore
    val scope = rememberCoroutineScope()
    val isNetEase = pref.kind == MusicSourceKind.NETEASE
    val isQQ = pref.kind == MusicSourceKind.QQ
    val isKugou = pref.kind == MusicSourceKind.KUGOU
    val isQishui = pref.kind == MusicSourceKind.QISHUI
    val modes = when (pref.kind) {
        MusicSourceKind.NETEASE -> listOf("手机号", "扫码", "桌面网页", "Cookie")
        MusicSourceKind.QQ -> listOf("扫码", "手机号", "桌面网页", "Cookie")
        MusicSourceKind.KUGOU -> listOf("手机号", "扫码", "桌面网页", "Cookie")
        MusicSourceKind.QISHUI -> listOf("扫码", "桌面网页", "Cookie")
        else -> listOf("桌面网页", "Cookie")
    }
    var modeIdx by remember { mutableIntStateOf(0) }

    // Phone + SMS / Password / Email state (NetEase + QQ + Kugou)
    var phoneAuthMode by remember { mutableIntStateOf(0) } // 0 = SMS, 1 = Phone Password, 2 = Email Password
    var countryCode by remember { mutableStateOf("86") }
    var phoneNumber by remember { mutableStateOf("") }
    var emailAddress by remember { mutableStateOf("") }
    var smsCode by remember { mutableStateOf("") }
    var phonePassword by remember { mutableStateOf("") }
    var smsCooldown by remember { mutableIntStateOf(0) }
    var smsSending by remember { mutableStateOf(false) }
    var phoneSubmitting by remember { mutableStateOf(false) }
    var phoneMessage by remember { mutableStateOf<String?>(null) }
    var phoneNeedFallback by remember { mutableStateOf(false) }
    var qqSecurityUrl by remember { mutableStateOf<String?>(null) }

    // QR state (NetEase + QQ/WeChat + Kugou/WeChat + Qishui/Douyin)
    var qqQRKind by remember { mutableStateOf(MusicProviderHub.QQQRKind.QQ) }
    var qqQRSession by remember { mutableStateOf<MusicProviderHub.QQQRSession?>(null) }
    var kugouQRKind by remember { mutableStateOf(MusicProviderHub.KugouQRKind.KUGOU) }
    var kugouWXSession by remember { mutableStateOf<MusicProviderHub.KugouWeChatQRSession?>(null) }
    var qishuiQRSession by remember { mutableStateOf<MusicProviderHub.QishuiQRSession?>(null) }
    var neteaseQRSession by remember { mutableStateOf<MusicProviderHub.NetEaseQRSession?>(null) }
    var qrKey by remember { mutableStateOf("") }
    var qrText by remember { mutableStateOf("") }
    var qrBase64 by remember { mutableStateOf<String?>(null) }
    var qrRawBytes by remember { mutableStateOf<ByteArray?>(null) }
    var qrStatus by remember { mutableStateOf("正在生成二维码…") }
    var qrNeedWebFallback by remember { mutableStateOf(false) }
    var qrRefreshTick by remember { mutableIntStateOf(0) }

    // Desktop WebView state
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var webStatus by remember { mutableStateOf("已启用桌面模式 · 自动拦截跳转外部 App") }
    var webStatusAlert by remember { mutableStateOf(false) }
    var webDelivered by remember { mutableStateOf(false) }

    var manualCookie by remember { mutableStateOf(pref.customCookie.orEmpty()) }

    LaunchedEffect(smsCooldown) {
        if (smsCooldown > 0) {
            delay(1000)
            smsCooldown -= 1
        }
    }

    fun completeLogin(cookie: String, prefilledNick: String? = null, prefilledAvatar: String? = null) {
        val clean = cookie.trim()
        if (clean.isEmpty()) return
        scope.launch {
            var nick = prefilledNick
            var avatar = prefilledAvatar
            if (isNetEase && nick.isNullOrBlank()) {
                val acc = MusicProviderHub.fetchNetEaseAccount(clean)
                if (acc != null) {
                    nick = acc.first
                    avatar = acc.second
                }
            } else if (isQQ && nick.isNullOrBlank()) {
                val acc = MusicProviderHub.fetchQQAccount(clean)
                if (acc != null) {
                    nick = acc.first
                    avatar = acc.second
                }
            } else if (isQishui && nick.isNullOrBlank()) {
                val acc = MusicProviderHub.fetchQishuiAccount(clean)
                if (acc != null) {
                    nick = acc.first
                    avatar = acc.second
                }
            }
            store.setSourceAccount(pref.id, clean, nick, avatar)
            val syncedCount = store.syncCloudPlaylists()
            val syncNote = if (syncedCount > 0) " · 已同步 $syncedCount 个云端歌单" else ""
            onSuccess("已登录 ${pref.kind.fullName}" + (nick?.let { "：$it" }.orEmpty()) + syncNote)
        }
    }

    fun inspectWebCookies(manual: Boolean = false) {
        if (webDelivered) return
        val cm = CookieManager.getInstance()
        val raws = MusicWebLoginGuard.cookieDomains(pref.kind).mapNotNull { url ->
            runCatching { cm.getCookie(url) }.getOrNull()
        }
        val mergedMap = MusicWebLoginGuard.mergeCookies(raws)
        if (MusicWebLoginGuard.matchesLoggedInCookies(mergedMap, pref.kind)) {
            webDelivered = true
            runCatching { cm.flush() }
            val merged = mergedMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
            completeLogin(merged)
        } else if (manual) {
            webStatus = "暂未检测到登录凭证，请先在页面内完成登录"
            webStatusAlert = false
        }
    }

    val activeMode = modes[modeIdx.coerceIn(0, modes.lastIndex)]

    LaunchedEffect(activeMode, qrRefreshTick, qqQRKind, kugouQRKind) {
        if (activeMode != "扫码") return@LaunchedEffect
        qrKey = ""
        qrText = ""
        qrBase64 = null
        qrRawBytes = null
        qqQRSession = null
        kugouWXSession = null
        qishuiQRSession = null
        neteaseQRSession = null
        qrNeedWebFallback = false
        qrStatus = when {
            isQQ -> if (qqQRKind == MusicProviderHub.QQQRKind.QQ) "正在生成 QQ 登录二维码…" else "正在生成微信登录二维码…"
            isKugou -> if (kugouQRKind == MusicProviderHub.KugouQRKind.KUGOU) "正在生成酷狗二维码…" else "正在生成酷狗微信登录二维码…"
            isQishui -> "正在生成抖音 / 汽水扫码二维码…"
            else -> "正在生成网易云二维码…"
        }
        if (isQQ) {
            val session = MusicProviderHub.createQQQR(qqQRKind)
            if (session == null) {
                qrStatus = "二维码获取失败，请切换另一种扫码或「桌面网页」"
                return@LaunchedEffect
            }
            qqQRSession = session
            qrRawBytes = session.imageBytes
            qrStatus = if (session.kind == MusicProviderHub.QQQRKind.QQ) "请用手机 QQ 扫码登录" else "请用微信扫码登录"
            while (isActive) {
                delay(if (session.kind == MusicProviderHub.QQQRKind.WECHAT) 400 else 2200)
                when (val res = MusicProviderHub.pollQQQR(session)) {
                    is MusicProviderHub.QQQRPoll.Waiting -> {
                        qrStatus = if (session.kind == MusicProviderHub.QQQRKind.QQ) "等待手机 QQ 扫码…" else "等待微信扫码…"
                    }
                    is MusicProviderHub.QQQRPoll.Scanned -> {
                        qrStatus = if (session.kind == MusicProviderHub.QQQRKind.QQ) "已扫码，请在手机 QQ 点击确认登录" else "已扫码，请在微信上点击确认登录"
                    }
                    is MusicProviderHub.QQQRPoll.Expired -> {
                        qrStatus = "二维码已过期，正在刷新…"
                        qrRefreshTick++
                        return@LaunchedEffect
                    }
                    is MusicProviderHub.QQQRPoll.Refused -> {
                        qrStatus = "已取消授权，点击「刷新二维码」重试"
                        return@LaunchedEffect
                    }
                    is MusicProviderHub.QQQRPoll.Authorized -> {
                        completeLogin(res.cookie, res.nickname, res.avatarURL)
                        return@LaunchedEffect
                    }
                    is MusicProviderHub.QQQRPoll.Failed -> qrStatus = res.message
                }
            }
        } else if (isQishui) {
            val session = MusicProviderHub.createQishuiQR()
            if (session == null) {
                qrStatus = "汽水二维码获取失败，请切换「桌面网页」"
                return@LaunchedEffect
            }
            qishuiQRSession = session
            qrBase64 = session.imageBase64
            qrText = session.qrText
            qrStatus = "请用「汽水音乐 App」扫一扫确认登录"
            while (isActive) {
                delay(2500)
                when (val res = MusicProviderHub.pollQishuiQR(session)) {
                    is MusicProviderHub.QishuiQRPoll.Waiting -> qrStatus = "请用「汽水音乐 App」扫一扫确认登录"
                    is MusicProviderHub.QishuiQRPoll.Scanned -> qrStatus = "已扫码，请在汽水音乐 App 点击确认登录"
                    is MusicProviderHub.QishuiQRPoll.Expired -> {
                        qrStatus = "二维码已过期，正在刷新…"
                        qrRefreshTick++
                        return@LaunchedEffect
                    }
                    is MusicProviderHub.QishuiQRPoll.Refused -> {
                        qrStatus = "已取消授权，点击「刷新二维码」重试"
                        return@LaunchedEffect
                    }
                    is MusicProviderHub.QishuiQRPoll.Authorized -> {
                        completeLogin(res.cookie, res.nickname, res.avatarURL)
                        return@LaunchedEffect
                    }
                    is MusicProviderHub.QishuiQRPoll.Failed -> qrStatus = res.message
                }
            }
        } else if (isKugou) {
            if (kugouQRKind == MusicProviderHub.KugouQRKind.WECHAT) {
                val session = MusicProviderHub.createKugouWeChatQR()
                if (session == null) {
                    qrStatus = "酷狗微信二维码获取失败，请切换「酷狗扫码」或「手机号」"
                    return@LaunchedEffect
                }
                kugouWXSession = session
                qrBase64 = session.imageBase64
                qrText = session.qrText
                qrStatus = "请用微信扫码登录酷狗"
                while (isActive) {
                    delay(400)
                    when (val res = MusicProviderHub.pollKugouWeChatQR(session.uuid)) {
                        is MusicProviderHub.KugouQRPoll.Waiting -> qrStatus = "等待微信扫码…"
                        is MusicProviderHub.KugouQRPoll.Scanned -> qrStatus = "已扫码，请在微信点击确认登录"
                        is MusicProviderHub.KugouQRPoll.Expired -> {
                            qrStatus = "二维码已过期，正在刷新…"
                            qrRefreshTick++
                            return@LaunchedEffect
                        }
                        is MusicProviderHub.KugouQRPoll.Authorized -> {
                            completeLogin(res.cookie, res.nickname, res.avatarURL)
                            return@LaunchedEffect
                        }
                        is MusicProviderHub.KugouQRPoll.Failed -> qrStatus = res.message
                    }
                }
            } else {
                val session = MusicProviderHub.createKugouQR()
                if (session == null) {
                    qrStatus = "酷狗二维码获取失败，请切换「手机号」或「桌面网页」"
                    return@LaunchedEffect
                }
                qrKey = session.key
                qrText = session.qrText
                qrBase64 = session.imageBase64
                qrStatus = "请用酷狗音乐 App 扫码登录"
                while (isActive) {
                    delay(2200)
                    when (val res = MusicProviderHub.pollKugouQR(session.key)) {
                        is MusicProviderHub.KugouQRPoll.Waiting -> qrStatus = "等待酷狗 App 扫码…"
                        is MusicProviderHub.KugouQRPoll.Scanned -> qrStatus = "已扫码${res.nickname?.let { "（$it）" }.orEmpty()}，请在酷狗 App 确认登录"
                        is MusicProviderHub.KugouQRPoll.Expired -> {
                            qrStatus = "二维码已过期，正在刷新…"
                            qrRefreshTick++
                            return@LaunchedEffect
                        }
                        is MusicProviderHub.KugouQRPoll.Authorized -> {
                            completeLogin(res.cookie, res.nickname, res.avatarURL)
                            return@LaunchedEffect
                        }
                        is MusicProviderHub.KugouQRPoll.Failed -> qrStatus = res.message
                    }
                }
            }
        } else {
            val session = MusicProviderHub.createNetEaseQRSession()
            if (session == null) {
                qrStatus = "二维码获取失败，请切换「手机号」或「桌面网页」"
                qrNeedWebFallback = true
                return@LaunchedEffect
            }
            neteaseQRSession = session
            qrKey = session.key
            qrText = session.url
            qrStatus = "请用网易云音乐 App 扫码登录"
            while (isActive) {
                delay(2200)
                when (val res = MusicProviderHub.pollNetEaseQR(session)) {
                    is MusicProviderHub.NetEaseQRPoll.Waiting -> qrStatus = "等待网易云 App 扫码…"
                    is MusicProviderHub.NetEaseQRPoll.Scanned -> qrStatus = "已扫码${res.nickname?.let { "（$it）" }.orEmpty()}，请在手机上确认登录"
                    is MusicProviderHub.NetEaseQRPoll.Expired -> {
                        qrStatus = "二维码已过期，正在刷新…"
                        qrRefreshTick++
                        return@LaunchedEffect
                    }
                    is MusicProviderHub.NetEaseQRPoll.Refused -> {
                        qrStatus = res.message
                        qrNeedWebFallback = true
                        return@LaunchedEffect
                    }
                    is MusicProviderHub.NetEaseQRPoll.Authorized -> {
                        completeLogin(res.cookie)
                        return@LaunchedEffect
                    }
                    is MusicProviderHub.NetEaseQRPoll.Failed -> qrStatus = res.message
                }
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth(0.95f)
                .heightIn(min = 420.dp, max = 720.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(YMColors.card)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("登录 ${pref.kind.fullName}", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = YMColors.fg)
                    Text("本机直连上游，绝无任何中转服务端", fontSize = 11.5.sp, color = YMColors.secondary)
                }
                TextButton(onClick = onDismiss) { Text("关闭", color = YMColors.secondary) }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                modes.forEachIndexed { i, m ->
                    val on = i == modeIdx
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .background(if (on) (if (YMColors.dark) Color.White else Color.Black) else YMColors.fill)
                            .clickable { modeIdx = i }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    ) {
                        Text(m, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (on) (if (YMColors.dark) Color.Black else Color.White) else YMColors.secondary)
                    }
                }
            }

            when (activeMode) {
                "手机号" -> {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (isNetEase) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(0 to "短信验证码", 1 to "手机密码", 2 to "邮箱密码").forEach { (mIdx, label) ->
                                    val on = phoneAuthMode == mIdx
                                    Box(
                                        Modifier
                                            .clip(CircleShape)
                                            .background(if (on) (if (YMColors.dark) Color.White else Color.Black) else YMColors.fill)
                                            .clickable {
                                                phoneAuthMode = mIdx
                                                phoneMessage = null
                                                phoneNeedFallback = false
                                            }
                                            .padding(horizontal = 11.dp, vertical = 6.dp),
                                    ) {
                                        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (on) (if (YMColors.dark) Color.Black else Color.White) else YMColors.secondary)
                                    }
                                }
                            }
                        }

                        val isEmailMode = isNetEase && phoneAuthMode == 2
                        if (isEmailMode) {
                            OutlinedTextField(
                                value = emailAddress,
                                onValueChange = { emailAddress = it },
                                placeholder = { Text("请输入网易云邮箱（如 xxx@163.com）") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = phonePassword,
                                onValueChange = { phonePassword = it },
                                placeholder = { Text("请输入网易云邮箱密码") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = countryCode,
                                    onValueChange = { countryCode = it.filter(Char::isDigit).take(4) },
                                    prefix = { Text("+", color = YMColors.secondary) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.width(86.dp),
                                )
                                OutlinedTextField(
                                    value = phoneNumber,
                                    onValueChange = { phoneNumber = it },
                                    placeholder = { Text("请输入手机号") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                    modifier = Modifier.weight(1f),
                                )
                            }

                            if (isQQ || isKugou || phoneAuthMode == 0) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = smsCode,
                                        onValueChange = { smsCode = it.filter(Char::isDigit).take(8) },
                                        placeholder = { Text("短信验证码") },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.weight(1f),
                                    )
                                    val canSend = !smsSending && smsCooldown == 0 && phoneNumber.count(Char::isDigit) >= 6
                                    Box(
                                        Modifier
                                            .height(54.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(YMColors.fill)
                                            .clickable(enabled = canSend) {
                                                scope.launch {
                                                    smsSending = true
                                                    phoneMessage = null
                                                    phoneNeedFallback = false
                                                    if (isQQ) {
                                                        when (val res = MusicProviderHub.sendQQSMSCode(phoneNumber, countryCode)) {
                                                            is MusicProviderHub.QQSMSResult.Sent -> {
                                                                phoneMessage = "验证码已发送，请查收短信"
                                                                smsCooldown = 60
                                                            }
                                                            is MusicProviderHub.QQSMSResult.CaptchaRequired -> {
                                                                phoneMessage = "QQ 音乐要求先完成安全滑块验证，验证后再重新点击「获取验证码」"
                                                                phoneNeedFallback = true
                                                                qqSecurityUrl = res.securityURL
                                                            }
                                                            is MusicProviderHub.QQSMSResult.Failed -> {
                                                                phoneMessage = res.message
                                                                phoneNeedFallback = true
                                                            }
                                                        }
                                                    } else if (isKugou) {
                                                        when (val res = MusicProviderHub.sendKugouSMSCode(phoneNumber, countryCode)) {
                                                            is MusicProviderHub.NetEaseSMSResult.Sent -> {
                                                                phoneMessage = "酷狗验证码已发送，请查收短信"
                                                                smsCooldown = 60
                                                            }
                                                            is MusicProviderHub.NetEaseSMSResult.Failed -> {
                                                                phoneMessage = res.message
                                                                phoneNeedFallback = true
                                                            }
                                                        }
                                                    } else {
                                                        when (val res = MusicProviderHub.sendNetEaseSMSCode(phoneNumber, countryCode)) {
                                                            is MusicProviderHub.NetEaseSMSResult.Sent -> {
                                                                phoneMessage = "验证码已发送，请查收短信"
                                                                smsCooldown = 60
                                                            }
                                                            is MusicProviderHub.NetEaseSMSResult.Failed -> {
                                                                phoneMessage = res.message
                                                                phoneNeedFallback = res.message.contains("限制") || res.message.contains("频繁") || res.message.contains("风险") || res.message.contains("验证")
                                                            }
                                                        }
                                                    }
                                                    smsSending = false
                                                }
                                            }
                                            .padding(horizontal = 14.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (smsSending) {
                                            CircularProgressIndicator(Modifier.size(18.dp), color = YMColors.fg, strokeWidth = 2.dp)
                                        } else {
                                            Text(
                                                if (smsCooldown > 0) "${smsCooldown}s" else "获取验证码",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (canSend) YMColors.fg else YMColors.secondary,
                                            )
                                        }
                                    }
                                }
                            } else {
                                OutlinedTextField(
                                    value = phonePassword,
                                    onValueChange = { phonePassword = it },
                                    placeholder = { Text("请输入网易云账号密码") },
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }

                        phoneMessage?.takeIf { it.isNotBlank() }?.let { msg ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(YMColors.fill)
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(msg, fontSize = 12.5.sp, color = if (phoneNeedFallback) YMColors.accent else YMColors.fg)
                                if (phoneNeedFallback) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        val qrIdx = modes.indexOf("扫码")
                                        if (qrIdx >= 0) {
                                            Box(
                                                Modifier
                                                    .clip(CircleShape)
                                                    .background(if (YMColors.dark) Color.White else Color.Black)
                                                    .clickable { modeIdx = qrIdx }
                                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                            ) {
                                                Text(
                                                    if (isQQ) "切换 QQ/微信扫码" else if (isKugou) "切换酷狗/微信扫码" else "切换扫码登录",
                                                    fontSize = 11.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (YMColors.dark) Color.Black else Color.White,
                                                )
                                            }
                                        }
                                        val webIdx = modes.indexOf("桌面网页")
                                        if (webIdx >= 0) {
                                            Box(
                                                Modifier
                                                    .clip(CircleShape)
                                                    .background(YMColors.card)
                                                    .clickable { modeIdx = webIdx }
                                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                            ) {
                                                Text("切换桌面网页", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = YMColors.fg)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        val canSubmit = !phoneSubmitting && if (isEmailMode) {
                            emailAddress.contains("@") && phonePassword.isNotEmpty()
                        } else {
                            phoneNumber.count(Char::isDigit) >= 6 &&
                                (if (isNetEase && phoneAuthMode == 1) phonePassword.isNotEmpty() else smsCode.isNotBlank())
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(CircleShape)
                                .background(
                                    (if (YMColors.dark) Color.White else Color.Black).copy(alpha = if (canSubmit) 1f else 0.45f),
                                )
                                .clickable(enabled = canSubmit) {
                                    scope.launch {
                                        phoneSubmitting = true
                                        phoneMessage = null
                                        phoneNeedFallback = false
                                        if (isQQ) {
                                            when (val res = MusicProviderHub.loginQQByPhone(phoneNumber, countryCode, smsCode)) {
                                                is MusicProviderHub.QQPhoneLoginResult.Authorized -> {
                                                    completeLogin(res.cookie, res.nickname, res.avatarURL)
                                                }
                                                is MusicProviderHub.QQPhoneLoginResult.Failed -> {
                                                    phoneMessage = res.message
                                                    phoneNeedFallback = true
                                                }
                                            }
                                        } else if (isKugou) {
                                            when (val res = MusicProviderHub.loginKugouByPhone(phoneNumber, countryCode, smsCode)) {
                                                is MusicProviderHub.NetEasePhoneLoginResult.Authorized -> {
                                                    completeLogin(res.cookie, res.nickname, res.avatarURL)
                                                }
                                                is MusicProviderHub.NetEasePhoneLoginResult.RequiresWebOrQR -> {
                                                    phoneMessage = res.message
                                                    phoneNeedFallback = true
                                                }
                                                is MusicProviderHub.NetEasePhoneLoginResult.Failed -> {
                                                    phoneMessage = res.message
                                                    phoneNeedFallback = true
                                                }
                                            }
                                        } else {
                                            val res = if (isEmailMode) {
                                                MusicProviderHub.loginNetEaseByEmail(emailAddress, phonePassword)
                                            } else {
                                                MusicProviderHub.loginNetEaseByPhone(
                                                    phone = phoneNumber,
                                                    countryCode = countryCode,
                                                    captcha = if (phoneAuthMode == 0) smsCode else null,
                                                    password = if (phoneAuthMode == 1) phonePassword else null,
                                                )
                                            }
                                            when (res) {
                                                is MusicProviderHub.NetEasePhoneLoginResult.Authorized -> {
                                                    completeLogin(res.cookie, res.nickname, res.avatarURL)
                                                }
                                                is MusicProviderHub.NetEasePhoneLoginResult.RequiresWebOrQR -> {
                                                    phoneMessage = res.message
                                                    phoneNeedFallback = true
                                                }
                                                is MusicProviderHub.NetEasePhoneLoginResult.Failed -> {
                                                    phoneMessage = res.message
                                                    phoneNeedFallback = res.message.contains("风险") || res.message.contains("验证")
                                                }
                                            }
                                        }
                                        phoneSubmitting = false
                                    }
                                }
                                .padding(vertical = 13.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (phoneSubmitting) {
                                CircularProgressIndicator(Modifier.size(18.dp), color = if (YMColors.dark) Color.Black else Color.White, strokeWidth = 2.dp)
                            } else {
                                Text("立即登录", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = if (YMColors.dark) Color.Black else Color.White)
                            }
                        }

                        Text(
                            when {
                                isQQ -> "本机直连 QQ 音乐官方短信登录接口（零中转）。若触发腾讯安全滑块，将打开官方验证页由你手动完成；也可直接使用「扫码」支持 QQ / 微信登录。"
                                isKugou -> "本机直连酷狗官方 Android v7 加密短信登录接口（零中转）。登录成功后自动启用无损直链与自动续期；也可一键切换「酷狗/微信扫码」。"
                                else -> "本机直连网易云 EAPI 接口，支持手机号验证码、手机密码与 163 邮箱密码登录，凭证不经过任何中转服务器。若触发风控滑块，可一键切换「扫码」或「桌面网页」。"
                            },
                            fontSize = 11.5.sp,
                            color = YMColors.secondary,
                            lineHeight = 16.sp,
                        )
                    }
                }

                "扫码" -> {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (isQQ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MusicProviderHub.QQQRKind.entries.forEach { k ->
                                    val on = qqQRKind == k
                                    Box(
                                        Modifier
                                            .clip(CircleShape)
                                            .background(if (on) (if (YMColors.dark) Color.White else Color.Black) else YMColors.fill)
                                            .clickable { if (qqQRKind != k) qqQRKind = k }
                                            .padding(horizontal = 14.dp, vertical = 6.dp),
                                    ) {
                                        Text(k.label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (on) (if (YMColors.dark) Color.Black else Color.White) else YMColors.secondary)
                                    }
                                }
                            }
                        } else if (isKugou) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MusicProviderHub.KugouQRKind.entries.forEach { k ->
                                    val on = kugouQRKind == k
                                    Box(
                                        Modifier
                                            .clip(CircleShape)
                                            .background(if (on) (if (YMColors.dark) Color.White else Color.Black) else YMColors.fill)
                                            .clickable { if (kugouQRKind != k) kugouQRKind = k }
                                            .padding(horizontal = 14.dp, vertical = 6.dp),
                                    ) {
                                        Text(k.label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (on) (if (YMColors.dark) Color.Black else Color.White) else YMColors.secondary)
                                    }
                                }
                            }
                        }
                        Box(
                            Modifier.size(204.dp).clip(RoundedCornerShape(18.dp)).background(Color.White),
                            contentAlignment = Alignment.Center,
                        ) {
                            val qrBmp = remember(qrKey, qrBase64, qrText, qrRawBytes) {
                                val fromRaw = qrRawBytes?.let { bytes ->
                                    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
                                }
                                val decoded = fromRaw ?: qrBase64?.let { b64 ->
                                    runCatching {
                                        val bytes = Base64.decode(b64, Base64.DEFAULT)
                                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                                    }.getOrNull()
                                }
                                decoded ?: if (qrText.isNotBlank()) LocalQrCode.encode(qrText) else null
                            }
                            if (qrBmp != null) {
                                Image(bitmap = qrBmp, contentDescription = "登录二维码", modifier = Modifier.size(186.dp))
                            } else {
                                CircularProgressIndicator(Modifier.size(24.dp), color = Color.Black, strokeWidth = 2.dp)
                            }
                        }
                        Text(
                            qrStatus,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (qrNeedWebFallback) YMColors.accent else YMColors.fg,
                            textAlign = TextAlign.Center,
                        )
                        if (qrNeedWebFallback) {
                            val webIdx = modes.indexOf("桌面网页")
                            if (webIdx >= 0) {
                                Box(
                                    Modifier
                                        .clip(CircleShape)
                                        .background(YMColors.accent)
                                        .clickable { modeIdx = webIdx }
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                ) {
                                    Text("切换桌面网页登录", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                                }
                            }
                        }
                        Text(
                            when {
                                isQQ && qqQRKind == MusicProviderHub.QQQRKind.QQ -> "用手机 QQ 扫一扫或截图相册扫码，确认后自动保存 QQ 音乐本机凭证。"
                                isQQ -> "用微信扫一扫或截图相册扫码，确认后自动保存 QQ 音乐本机凭证。"
                                isKugou && kugouQRKind == MusicProviderHub.KugouQRKind.WECHAT -> "用微信扫一扫或截图相册扫码，确认后自动保存酷狗本机凭证。"
                                isKugou -> "用酷狗音乐 App 扫一扫或截图相册扫码，确认后自动保存本机凭证。"
                                isQishui -> "用抖音或汽水音乐 App 扫一扫或截图相册扫码，确认后自动解锁完整搜索与个人歌单。"
                                else -> "用网易云音乐 App 扫一扫或截图相册扫码，确认后自动保存本机凭证。"
                            },
                            fontSize = 11.5.sp,
                            color = YMColors.secondary,
                            textAlign = TextAlign.Center,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TextButton(onClick = { qrRefreshTick++ }) { Text("刷新二维码", color = YMColors.accent) }
                            TextButton(onClick = {
                                scope.launch {
                                    if (isQQ) {
                                        qqQRSession?.let { s ->
                                            val res = MusicProviderHub.pollQQQR(s)
                                            if (res is MusicProviderHub.QQQRPoll.Authorized) completeLogin(res.cookie, res.nickname, res.avatarURL)
                                        }
                                    } else if (isQishui) {
                                        qishuiQRSession?.let { s ->
                                            val res = MusicProviderHub.pollQishuiQR(s)
                                            if (res is MusicProviderHub.QishuiQRPoll.Authorized) completeLogin(res.cookie, res.nickname, res.avatarURL)
                                        }
                                    } else if (isKugou && kugouWXSession != null) {
                                        kugouWXSession?.let { s ->
                                            val res = MusicProviderHub.pollKugouWeChatQR(s.uuid)
                                            if (res is MusicProviderHub.KugouQRPoll.Authorized) completeLogin(res.cookie, res.nickname, res.avatarURL)
                                        }
                                    } else if (isNetEase && neteaseQRSession != null) {
                                        neteaseQRSession?.let { s ->
                                            val res = MusicProviderHub.pollNetEaseQR(s)
                                            when (res) {
                                                is MusicProviderHub.NetEaseQRPoll.Authorized -> completeLogin(res.cookie)
                                                is MusicProviderHub.NetEaseQRPoll.Refused -> {
                                                    qrStatus = res.message
                                                    qrNeedWebFallback = true
                                                }
                                                else -> Unit
                                            }
                                        }
                                    } else if (qrKey.isNotBlank()) {
                                        if (isKugou) {
                                            val res = MusicProviderHub.pollKugouQR(qrKey)
                                            if (res is MusicProviderHub.KugouQRPoll.Authorized) completeLogin(res.cookie, res.nickname, res.avatarURL)
                                        } else {
                                            val res = MusicProviderHub.pollNetEaseQR(qrKey)
                                            if (res is MusicProviderHub.NetEaseQRPoll.Authorized) completeLogin(res.cookie)
                                        }
                                    }
                                }
                            }) { Text("我已确认登录", color = YMColors.fg, fontWeight = FontWeight.Bold) }
                        }
                    }
                }

                "桌面网页" -> {
                    val targetUrl = MusicWebLoginGuard.desktopLoginUrl(pref.kind)
                    val guideText = when (pref.kind) {
                        MusicSourceKind.NETEASE -> "桌面版网易云：支持手机号验证码 / 密码 / 扫码，登录完成后自动捕获凭证。"
                        MusicSourceKind.QQ -> "桌面版 QQ 音乐：点击右上角「登录」可用 QQ 账号密码或扫码（已拦截跳去 QQ / QQ音乐 App）。"
                        MusicSourceKind.KUGOU -> "桌面版酷狗：点击右上角「登录」可用手机号验证码或扫码（已拦截跳去酷狗 App）。"
                        MusicSourceKind.QISHUI -> "桌面版汽水音乐 / 抖音网页：支持抖音扫码或手机号登录，登录完成后自动捕获凭证。"
                        else -> "桌面版网页登录：登录完成后自动捕获凭证。"
                    }
                    LaunchedEffect(targetUrl) {
                        while (isActive && !webDelivered) {
                            delay(1500)
                            inspectWebCookies(manual = false)
                        }
                    }
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .clip(CircleShape)
                                    .background(YMColors.fill)
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                            ) {
                                Text(
                                    webStatus,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (webStatusAlert) YMColors.accent else YMColors.secondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Box(
                                Modifier
                                    .clip(CircleShape)
                                    .background(YMColors.fill)
                                    .clickable { webViewRef?.let { if (it.canGoBack()) it.goBack() } }
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                            ) { Text("返回", fontSize = 11.sp, color = YMColors.fg) }
                            Box(
                                Modifier
                                    .clip(CircleShape)
                                    .background(YMColors.fill)
                                    .clickable { webViewRef?.reload() }
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                            ) { Text("刷新", fontSize = 11.sp, color = YMColors.fg) }
                            Box(
                                Modifier
                                    .clip(CircleShape)
                                    .background(if (YMColors.dark) Color.White else Color.Black)
                                    .clickable { inspectWebCookies(manual = true) }
                                    .padding(horizontal = 9.dp, vertical = 5.dp),
                            ) { Text("读取凭证", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (YMColors.dark) Color.Black else Color.White) }
                        }

                        Text(guideText, fontSize = 11.5.sp, color = YMColors.secondary, lineHeight = 16.sp)

                        Box(Modifier.fillMaxWidth().height(440.dp).clip(RoundedCornerShape(14.dp))) {
                            AndroidView(
                                factory = { ctx ->
                                    WebView(ctx).apply {
                                        settings.javaScriptEnabled = true
                                        settings.domStorageEnabled = true
                                        settings.userAgentString = MusicWebLoginGuard.DESKTOP_UA
                                        settings.useWideViewPort = true
                                        settings.loadWithOverviewMode = true
                                        settings.builtInZoomControls = true
                                        settings.displayZoomControls = false
                                        settings.setSupportMultipleWindows(true)
                                        settings.javaScriptCanOpenWindowsAutomatically = true
                                        CookieManager.getInstance().setAcceptCookie(true)
                                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                        webViewClient = object : WebViewClient() {
                                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                                val urlStr = request?.url?.toString()
                                                if (MusicWebLoginGuard.shouldBlock(urlStr)) {
                                                    webStatus = "已拦截外部 App 跳转，请在桌面网页内登录"
                                                    webStatusAlert = true
                                                    return true
                                                }
                                                return false
                                            }

                                            @Deprecated("Deprecated in Java")
                                            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                                if (MusicWebLoginGuard.shouldBlock(url)) {
                                                    webStatus = "已拦截外部 App 跳转，请在桌面网页内登录"
                                                    webStatusAlert = true
                                                    return true
                                                }
                                                return false
                                            }

                                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                                view?.evaluateJavascript(MusicWebLoginGuard.antiH5Script, null)
                                            }

                                            override fun onPageFinished(view: WebView?, url: String?) {
                                                view?.evaluateJavascript(MusicWebLoginGuard.antiH5Script, null)
                                                inspectWebCookies(manual = false)
                                            }
                                        }
                                        webChromeClient = object : WebChromeClient() {
                                            override fun onCreateWindow(
                                                view: WebView?,
                                                isDialog: Boolean,
                                                isUserGesture: Boolean,
                                                resultMsg: Message?,
                                            ): Boolean {
                                                val mainWeb = view ?: return false
                                                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                                                val helper = WebView(mainWeb.context).apply {
                                                    webViewClient = object : WebViewClient() {
                                                        override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                                                            val u = req?.url?.toString()
                                                            if (!u.isNullOrBlank()) {
                                                                if (MusicWebLoginGuard.shouldBlock(u)) {
                                                                    webStatus = "已拦截外部 App 跳转，请在桌面网页内登录"
                                                                    webStatusAlert = true
                                                                } else {
                                                                    mainWeb.loadUrl(u)
                                                                }
                                                            }
                                                            v?.destroy()
                                                            return true
                                                        }
                                                    }
                                                }
                                                transport.webView = helper
                                                resultMsg.sendToTarget()
                                                return true
                                            }
                                        }
                                        webViewRef = this
                                        loadUrl(targetUrl)
                                    }
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }

                else -> {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("粘贴 ${pref.kind.fullName} Cookie（仅存本机）", fontSize = 12.sp, color = YMColors.secondary)
                        OutlinedTextField(
                            value = manualCookie,
                            onValueChange = { manualCookie = it },
                            placeholder = { Text("MUSIC_U=...;") },
                            maxLines = 5,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(CircleShape)
                                .background(if (YMColors.dark) Color.White else Color.Black)
                                .clickable(enabled = manualCookie.isNotBlank()) { completeLogin(manualCookie) }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("保存并验证", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (YMColors.dark) Color.Black else Color.White)
                        }
                    }
                }
            }
        }
    }

    qqSecurityUrl?.let { secUrl ->
        Dialog(onDismissRequest = { qqSecurityUrl = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(
                Modifier
                    .fillMaxWidth(0.94f)
                    .heightIn(min = 420.dp, max = 640.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(YMColors.card)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("腾讯安全验证", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = YMColors.fg)
                        Text("完成滑块验证后关闭此页，再重新点击「获取验证码」", fontSize = 11.5.sp, color = YMColors.secondary)
                    }
                    TextButton(onClick = { qqSecurityUrl = null }) { Text("完成/关闭", color = YMColors.accent) }
                }
                Box(Modifier.fillMaxWidth().height(460.dp).clip(RoundedCornerShape(14.dp))) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                webViewClient = WebViewClient()
                                loadUrl(secUrl)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

// ── 3. Artist Detail Screen (歌手详情) ──────────────────────────────────────────────────────────────

@Composable
fun MusicArtistDetailScreen(
    name: String,
    artistId: String?,
    sourceId: String,
    coverHint: String,
    onBack: () -> Unit,
) {
    val player = IdjApp.instance.musicPlayer
    val go = LocalNavigate.current
    val metrics = LocalLayoutMetrics.current
    var detail by remember { mutableStateOf<MusicArtistDetail?>(null) }
    var loading by remember { mutableStateOf(true) }
    var bioOpen by remember { mutableStateOf(false) }

    LaunchedEffect(name, artistId, sourceId) {
        loading = true
        detail = MusicProviderHub.fetchArtistDetail(name, artistId, sourceId, coverHint)
        loading = false
    }

    val bottomPad = if (player.shouldShowGlobalMiniBar) 96.dp else 32.dp

    Box(Modifier.fillMaxSize().background(YMColors.base)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            MusicSecondaryTopBar(title = detail?.name ?: name, onBack = onBack)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = bottomPad),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item("hero") {
                    Box(Modifier.fillMaxWidth().height(230.dp)) {
                        YMCoverImage(detail?.coverURL?.ifBlank { coverHint } ?: coverHint, Modifier.fillMaxSize(), corner = 0.dp)
                        Box(
                            Modifier.fillMaxSize().background(
                                Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f), YMColors.base)),
                            ),
                        )
                        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = metrics.pagePadding, vertical = 10.dp)) {
                            Text(detail?.name ?: name, fontSize = 26.sp, fontWeight = FontWeight.Black, color = YMColors.fg)
                            detail?.let { d ->
                                Text("${d.hotSongs.size} 首热门 · ${d.albums.size} 张专辑", fontSize = 12.5.sp, color = YMColors.secondary)
                            }
                        }
                    }
                }

                item("actions") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = metrics.pagePadding), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(CircleShape)
                                .background(if (YMColors.dark) Color.White else Color.Black)
                                .clickable(enabled = !detail?.hotSongs.isNullOrEmpty()) {
                                    val list = detail?.hotSongs.orEmpty()
                                    list.firstOrNull()?.let { player.play(it, list) }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Filled.PlayArrow, null, tint = if (YMColors.dark) Color.Black else Color.White, modifier = Modifier.size(18.dp))
                                Text("播放热门", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (YMColors.dark) Color.Black else Color.White)
                            }
                        }
                        Box(
                            Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(CircleShape)
                                .background(YMColors.fill)
                                .clickable(enabled = !detail?.hotSongs.isNullOrEmpty()) {
                                    val list = detail?.hotSongs.orEmpty().shuffled()
                                    if (list.isNotEmpty()) {
                                        player.playMode = MusicPlayMode.SHUFFLE
                                        player.play(list.first(), list)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Filled.Shuffle, null, tint = YMColors.fg, modifier = Modifier.size(18.dp))
                                Text("随机播放", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = YMColors.fg)
                            }
                        }
                    }
                }

                detail?.bio?.takeIf { it.isNotBlank() }?.let { bio ->
                    item("bio") {
                        YMDashedCard(Modifier.padding(horizontal = metrics.pagePadding).fillMaxWidth().clickable { bioOpen = !bioOpen }) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(bio, fontSize = 13.sp, color = YMColors.fg.copy(alpha = 0.86f), maxLines = if (bioOpen) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
                                Text(if (bioOpen) "收起简介" else "展开简介", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = YMColors.accent)
                            }
                        }
                    }
                }

                if (loading) {
                    item("loading") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 36.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(22.dp), color = YMColors.accent, strokeWidth = 2.dp)
                        }
                    }
                } else {
                    val hot = detail?.hotSongs.orEmpty()
                    if (hot.isNotEmpty()) {
                        item("hot_hdr") {
                            Text("热门歌曲", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = YMColors.fg, modifier = Modifier.padding(horizontal = metrics.pagePadding))
                        }
                        itemsIndexed(hot.take(20), key = { _, t -> t.id }) { idx, t ->
                            Box(Modifier.padding(horizontal = metrics.pagePadding)) {
                                TrackRowItem(rank = idx + 1, track = t, onClick = { player.play(t, hot) })
                            }
                        }
                    }
                    val albums = detail?.albums.orEmpty()
                    if (albums.isNotEmpty()) {
                        item("alb_hdr") {
                            Text("专辑作品", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = YMColors.fg, modifier = Modifier.padding(horizontal = metrics.pagePadding))
                        }
                        itemsIndexed(albums.chunked(2), key = { i, _ -> "alb-row-$i" }) { _, row ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = metrics.pagePadding), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                row.forEach { al ->
                                    Column(
                                        Modifier.weight(1f).clickable {
                                            go(MusicRoutes.album(al.name, al.id, al.artist, al.artistId, al.sourceId, al.coverURL))
                                        },
                                        verticalArrangement = Arrangement.spacedBy(5.dp),
                                    ) {
                                        YMCoverImage(al.coverURL, Modifier.fillMaxWidth().aspectRatio(1f), corner = 14.dp)
                                        Text(al.name, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = YMColors.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(listOfNotNull(al.yearText, al.trackCount.takeIf { it > 0 }?.let { "${it}首" }).joinToString(" · "), fontSize = 11.5.sp, color = YMColors.secondary)
                                    }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── 4. Album Detail Screen (专辑详情) ───────────────────────────────────────────────────────────────

@Composable
fun MusicAlbumDetailScreen(
    name: String,
    albumId: String?,
    artist: String,
    artistId: String?,
    sourceId: String,
    coverHint: String,
    onBack: () -> Unit,
) {
    val player = IdjApp.instance.musicPlayer
    val store = IdjApp.instance.musicStore
    val go = LocalNavigate.current
    val metrics = LocalLayoutMetrics.current
    val scope = rememberCoroutineScope()

    var detail by remember { mutableStateOf<MusicAlbumDetail?>(null) }
    var loading by remember { mutableStateOf(true) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(name, albumId, artist, sourceId) {
        loading = true
        detail = MusicProviderHub.fetchAlbumDetail(name, albumId, artist, artistId, sourceId, coverHint)
        loading = false
    }

    val bottomPad = if (player.shouldShowGlobalMiniBar) 96.dp else 32.dp

    Box(Modifier.fillMaxSize().background(YMColors.base)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            MusicSecondaryTopBar(title = detail?.name ?: name, onBack = onBack)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = metrics.pagePadding, end = metrics.pagePadding, top = 8.dp, bottom = bottomPad),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item("header") {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        YMCoverImage(detail?.coverURL?.ifBlank { coverHint } ?: coverHint, Modifier.size(216.dp), corner = 20.dp)
                        Text(detail?.name ?: name, fontSize = 21.sp, fontWeight = FontWeight.Black, color = YMColors.fg, textAlign = TextAlign.Center)
                        Row(
                            Modifier.clickable {
                                val aName = detail?.artist ?: artist
                                val aId = detail?.artistId ?: artistId
                                go(MusicRoutes.artist(aName, aId, sourceId, detail?.coverURL ?: coverHint))
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(detail?.artist ?: artist, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = YMColors.accent)
                            Icon(Icons.Filled.ChevronRight, null, tint = YMColors.accent, modifier = Modifier.size(16.dp))
                        }
                        val meta = listOfNotNull(detail?.releaseDate, detail?.company, detail?.tracks?.size?.let { "${it} 首歌曲" }).joinToString(" · ")
                        if (meta.isNotBlank()) Text(meta, fontSize = 12.sp, color = YMColors.secondary)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .clip(CircleShape)
                                    .background(if (YMColors.dark) Color.White else Color.Black)
                                    .clickable(enabled = !detail?.tracks.isNullOrEmpty()) {
                                        val list = detail?.tracks.orEmpty()
                                        list.firstOrNull()?.let { player.play(it, list) }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("播放专辑", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (YMColors.dark) Color.Black else Color.White)
                            }
                            Box(
                                Modifier
                                    .height(44.dp)
                                    .clip(CircleShape)
                                    .background(YMColors.fill)
                                    .clickable(enabled = !detail?.tracks.isNullOrEmpty()) {
                                        val d = detail ?: return@clickable
                                        val (added, _) = store.cloneChartAsPlaylist(d.name, d.tracks)
                                        toast = "已收进歌单（$added 首）"
                                        scope.launch { delay(2000); toast = null }
                                    }
                                    .padding(horizontal = 18.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("收进歌单", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = YMColors.fg)
                            }
                        }
                    }
                }

                if (loading) {
                    item("loading") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 36.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(22.dp), color = YMColors.accent, strokeWidth = 2.dp)
                        }
                    }
                } else {
                    val tracks = detail?.tracks.orEmpty()
                    itemsIndexed(tracks, key = { _, t -> t.id }) { idx, t ->
                        TrackRowItem(rank = idx + 1, track = t, onClick = { player.play(t, tracks) })
                    }
                    detail?.description?.takeIf { it.isNotBlank() }?.let { desc ->
                        item("desc") {
                            YMDashedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("专辑介绍", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = YMColors.fg)
                                    Text(desc, fontSize = 12.5.sp, color = YMColors.secondary, lineHeight = 18.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
        toast?.let { msg ->
            Text(
                msg,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (YMColors.dark) Color.Black else Color.White,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp).clip(CircleShape).background(if (YMColors.dark) YMColors.Lime else Color.Black).padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

// ── 5. Pushable Chart / Playlist Detail Screen (Keeps Global Mini Bar Visible!) ─────────────────────

@Composable
fun MusicTrackListScreen(
    title: String,
    subtitle: String,
    tracks: List<MusicTrack>,
    canClone: Boolean,
    remoteSource: MusicSourceKind? = null,
    remotePlaylistId: String? = null,
    playlistId: String? = null,
    onBack: () -> Unit,
) {
    val player = IdjApp.instance.musicPlayer
    val store = IdjApp.instance.musicStore
    val metrics = LocalLayoutMetrics.current
    val scope = rememberCoroutineScope()
    var loadedTracks by remember(playlistId, remotePlaylistId) { mutableStateOf<List<MusicTrack>>(emptyList()) }
    var loadingRemote by remember(playlistId, remotePlaylistId) { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    val displayTracks = loadedTracks.ifEmpty { tracks }
    val bottomPad = if (player.shouldShowGlobalMiniBar) 96.dp else 32.dp

    LaunchedEffect(remoteSource, remotePlaylistId) {
        if (remoteSource != null && !remotePlaylistId.isNullOrBlank() && displayTracks.isEmpty()) {
            loadingRemote = true
            val fetched = MusicProviderHub.fetchRemotePlaylistTracks(remoteSource, remotePlaylistId, store.cookieMap()[remoteSource])
            if (fetched.isNotEmpty()) {
                loadedTracks = fetched
                if (playlistId != null && playlistId.startsWith("cloud:")) {
                    store.updateCloudPlaylistTracks(playlistId, fetched)
                }
            }
            loadingRemote = false
        }
    }

    Box(Modifier.fillMaxSize().background(YMColors.base)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            MusicSecondaryTopBar(title = title, onBack = onBack)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = metrics.pagePadding, end = metrics.pagePadding, top = 8.dp, bottom = bottomPad),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item("actions") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(subtitle, fontSize = 12.sp, color = YMColors.secondary)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .clip(CircleShape)
                                    .background(if (YMColors.dark) Color.White else Color.Black)
                                    .clickable(enabled = displayTracks.isNotEmpty()) {
                                        displayTracks.firstOrNull()?.let { player.play(it, displayTracks) }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("全部播放（${displayTracks.size} 首）", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (YMColors.dark) Color.Black else Color.White)
                            }
                            if (canClone) {
                                Box(
                                    Modifier
                                        .height(44.dp)
                                        .clip(CircleShape)
                                        .background(YMColors.fill)
                                        .clickable(enabled = displayTracks.isNotEmpty()) {
                                            val (added, skipped) = store.cloneChartAsPlaylist(title, displayTracks)
                                            toast = "已克隆到本地歌单：$added 首" + if (skipped > 0) "（跳过 $skipped 重复）" else ""
                                            scope.launch { delay(2200); toast = null }
                                        }
                                        .padding(horizontal = 16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("克隆歌单", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = YMColors.fg)
                                }
                            }
                        }
                    }
                }
                if (loadingRemote && displayTracks.isEmpty()) {
                    item("loading_remote") {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 36.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(20.dp), color = YMColors.accent, strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("正在直连拉取歌单曲目…", fontSize = 13.sp, color = YMColors.secondary)
                        }
                    }
                } else {
                    itemsIndexed(displayTracks, key = { _, t -> t.id }) { idx, t ->
                        TrackRowItem(rank = idx + 1, track = t, onClick = { player.play(t, displayTracks) })
                    }
                }
            }
        }
        toast?.let { msg ->
            Text(
                msg,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (YMColors.dark) Color.Black else Color.White,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp).clip(CircleShape).background(if (YMColors.dark) YMColors.Lime else Color.Black).padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun MusicSecondaryTopBar(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Filled.ChevronLeft, "返回", tint = YMColors.fg, modifier = Modifier.size(28.dp))
        }
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = YMColors.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun MusicAddNASDialog(onDismiss: () -> Unit, onSave: (MusicNASConfig) -> Unit) {
    val kinds = listOf(MusicSourceKind.NAVIDROME, MusicSourceKind.EMBY, MusicSourceKind.FNOS)
    var kind by remember { mutableStateOf(MusicSourceKind.NAVIDROME) }
    var name by remember { mutableStateOf("我的 NAS 音乐") }
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = YMColors.card,
        title = { Text("直连个人音乐 NAS", color = YMColors.fg) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    kinds.forEach { k ->
                        val on = k == kind
                        Box(
                            Modifier
                                .clip(CircleShape)
                                .background(if (on) YMColors.Lime else YMColors.fill)
                                .clickable { kind = k }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(k.label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (on) Color.Black else YMColors.secondary)
                        }
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text("显示名称") }, singleLine = true)
                OutlinedTextField(url, { url = it }, label = { Text("服务器地址 (https://...)") }, singleLine = true)
                OutlinedTextField(user, { user = it }, label = { Text("用户名") }, singleLine = true)
                OutlinedTextField(pass, { pass = it }, label = { Text("密码 / API Token") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(
                enabled = url.isNotBlank(),
                onClick = {
                    onSave(
                        MusicNASConfig(
                            id = "nas-${System.currentTimeMillis()}",
                            kindId = kind.id,
                            name = name.ifBlank { kind.label },
                            serverURL = url.trim(),
                            username = user.trim(),
                            passwordOrToken = pass.trim(),
                        ),
                    )
                },
            ) { Text("连接并保存", color = YMColors.accent, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = YMColors.secondary) } },
    )
}

// ── 6. Dedicated Daily Recommendations Screen (每日推荐详情页) ──────────────────────────────────────

@Composable
fun MusicDailyRecScreen(onBack: () -> Unit) {
    val player = IdjApp.instance.musicPlayer
    val store = IdjApp.instance.musicStore
    val metrics = LocalLayoutMetrics.current
    val scope = rememberCoroutineScope()

    var tracks by remember {
        mutableStateOf(MusicProviderHub.cachedDailyTracks(30))
    }
    var isPersonalized by remember { mutableStateOf(false) }
    var subtitle by remember { mutableStateOf("正在拉取今日专属推荐…") }
    var loading by remember { mutableStateOf(tracks.isEmpty()) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        loading = true
        val res = MusicProviderHub.fetchDailyRecommendations(store.enabledOnlineSources, store.cookieMap(), force = false)
        if (res.tracks.isNotEmpty()) {
            tracks = res.tracks
            isPersonalized = res.isPersonalized
            subtitle = res.subtitle
        } else if (tracks.isNotEmpty()) {
            subtitle = "多源今日混排精选 · 共 ${tracks.size} 首"
        }
        loading = false
    }

    val cal = remember { java.util.Calendar.getInstance() }
    val month = cal.get(java.util.Calendar.MONTH) + 1
    val day = String.format(java.util.Locale.US, "%02d", cal.get(java.util.Calendar.DAY_OF_MONTH))
    val wkNames = arrayOf("", "周日", "周一", "周二", "周三", "周四", "周五", "周六")
    val wk = wkNames[cal.get(java.util.Calendar.DAY_OF_WEEK)]
    val bottomPad = if (player.shouldShowGlobalMiniBar) 96.dp else 32.dp

    Box(Modifier.fillMaxSize().background(YMColors.base)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            MusicSecondaryTopBar(title = "每日推荐", onBack = onBack)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = metrics.pagePadding, end = metrics.pagePadding, top = 8.dp, bottom = bottomPad),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item("banner") {
                    YMDashedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Column(
                                Modifier
                                    .size(68.dp, 78.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(YMColors.fill)
                                    .border(1.dp, YMColors.accent.copy(alpha = 0.35f), RoundedCornerShape(14.dp)),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text("${month}月", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = YMColors.accent)
                                Text(day, fontSize = 30.sp, fontWeight = FontWeight.Black, color = YMColors.fg)
                                Text(wk, fontSize = 10.sp, color = YMColors.secondary)
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("每日推荐", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = YMColors.fg)
                                    if (isPersonalized) YMQualityPill("私人日推", isLossless = true)
                                }
                                Text(subtitle, fontSize = 12.5.sp, color = YMColors.secondary)
                                Text("点任意单曲播放，右侧 ··· 可看歌手与专辑", fontSize = 11.sp, color = YMColors.tertiary)
                            }
                        }
                    }
                }
                item("actions") {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(CircleShape)
                                .background(if (YMColors.dark) Color.White else Color.Black)
                                .clickable(enabled = tracks.isNotEmpty()) {
                                    tracks.firstOrNull()?.let { player.play(it, tracks) }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("全部播放", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (YMColors.dark) Color.Black else Color.White)
                        }
                        Box(
                            Modifier
                                .height(44.dp)
                                .clip(CircleShape)
                                .background(YMColors.fill)
                                .clickable(enabled = tracks.isNotEmpty()) {
                                    tracks = tracks.shuffled()
                                    toast = "已重排今日推荐顺序"
                                    scope.launch { delay(2000); toast = null }
                                }
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("换一批", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = YMColors.fg)
                        }
                        Box(
                            Modifier
                                .height(44.dp)
                                .clip(CircleShape)
                                .background(YMColors.fill)
                                .clickable(enabled = tracks.isNotEmpty()) {
                                    val (added, _) = store.cloneChartAsPlaylist("每日推荐 $month/$day", tracks)
                                    toast = "已存为歌单：导入 $added 首"
                                    scope.launch { delay(2000); toast = null }
                                }
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("存歌单", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = YMColors.accent)
                        }
                    }
                }
                if (loading && tracks.isEmpty()) {
                    item("loading") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(22.dp), color = YMColors.accent, strokeWidth = 2.dp)
                        }
                    }
                } else {
                    itemsIndexed(tracks, key = { _, t -> "daily-${t.id}" }) { idx, track ->
                        TrackRowItem(rank = idx + 1, track = track, onClick = { player.play(track, tracks) })
                    }
                }
            }
        }
        toast?.let { msg ->
            Text(
                msg,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (YMColors.dark) Color.Black else Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 56.dp)
                    .clip(CircleShape)
                    .background(if (YMColors.dark) YMColors.Lime else Color.Black)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}
