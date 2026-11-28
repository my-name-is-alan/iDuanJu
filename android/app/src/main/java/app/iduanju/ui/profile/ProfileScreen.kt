package app.iduanju.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iduanju.IdjApp
import app.iduanju.data.HongguoWebAccount
import app.iduanju.data.WatchProgress
import app.iduanju.ui.LocalNavigate
import app.iduanju.ui.components.*
import app.iduanju.ui.home.rootBottomInset
import app.iduanju.ui.theme.IdjDarkColors
import app.iduanju.ui.theme.IdjThemeColors
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import app.iduanju.data.AppBackup
import app.iduanju.ui.Routes
import app.iduanju.ui.library.FavoriteManageCell
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private class FavoriteItem(val sid: String, val title: String, val cover: String)

/**
 * iOS ProfileView: 我的 · 红果账号 · 继续观看 · 我的收藏 (long-press delete + 管理), then grouped link rows —
 * (观看历史 / 剧星榜 / 我的收藏) (Telegram 频道 / 漫画 / 小说 / 下载管理) (数据备份与迁移: WebDAV / 导出 / 恢复)
 * (外观) (片源管理 / 插件 / 开源许可 / 检查更新) (maintenance), footer.
 */
@Composable
fun ProfileScreen(onOpenDetail: (String) -> Unit = {}) {
    val c = IdjThemeColors
    val ia = IdjApp.instance
    val lib = ia.library
    val go = LocalNavigate.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    var backupBusy by remember { mutableStateOf(false) }
    // Most rows sit far above the footer that shows the outcome, so it is also announced as a toast.
    val notify: (String) -> Unit = { message = it; GlassToast.show(it, long = true) }

    val exportBackupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null || backupBusy) return@rememberLauncherForActivityResult
        backupBusy = true
        scope.launch {
            runCatching {
                val bytes = AppBackup.createBackupData(ctx)
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("无法写入目标文件")
                }
                val kb = maxOf(1, bytes.size / 1024)
                "已导出完整备份（$kb KB）"
            }.onSuccess(notify).onFailure { notify("备份导出失败：${it.message ?: "未知错误"}") }
            backupBusy = false
        }
    }

    val importBackupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || backupBusy) return@rememberLauncherForActivityResult
        backupBusy = true
        scope.launch {
            runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("无法读取备份文件")
                }
                AppBackup.restore(ctx, bytes).toastText
            }.onSuccess(notify).onFailure { notify("恢复失败：${it.message ?: "文件无效"}") }
            backupBusy = false
        }
    }

    LaunchedEffect(Unit) { ia.hgWebAccount.refreshProfile() }

    val watching by remember { derivedStateOf { lib.continueWatching.take(10) } }
    val favorites by remember {
        derivedStateOf { lib.favorites.entries.sortedByDescending { it.value.third }.take(6).map { FavoriteItem(it.key, it.value.first, it.value.second) } }
    }
    val ver = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
    val metrics = LocalLayoutMetrics.current
    val cols = metrics.posterCols

    val favoritesBlock: @Composable (Int) -> Unit = { favCols ->
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("我的收藏", color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).semantics { heading() })
                Text(
                    if (lib.favorites.isEmpty()) "全部 0" else "管理 · 全部 ${lib.favorites.size}",
                    color = c.secondary,
                    fontSize = 14.sp,
                    modifier = Modifier.pressClick { go("library/favorites") },
                )
            }
            if (favorites.isEmpty()) Text("在详情页或播放页点 ♥ 收藏 · 长按卡片可移出", color = c.secondary, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp))
            else Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                favorites.chunked(favCols).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { f ->
                            FavoriteManageCell(
                                title = f.title,
                                cover = f.cover,
                                modifier = Modifier.weight(1f),
                                onOpenDetail = { onOpenDetail(f.sid) },
                                onPlay = {
                                    val ep = lib.progress(f.sid)?.episodeNumber ?: 1
                                    go(Routes.player(f.sid, ep))
                                },
                                onRemove = {
                                    lib.removeFavorite(f.sid)
                                    notify("已移出收藏")
                                },
                            )
                        }
                        repeat(favCols - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }

    val libraryGroupBlock: @Composable () -> Unit = {
        GroupItem {
            LinkRow("观看历史", ProfileIcons.history, trailing = lib.history.size.takeIf { it > 0 }?.toString()) { go("library/history") }
            RowDivider()
            LinkRow("剧星榜", ProfileIcons.star) { go("actorRank") }
            RowDivider()
            LinkRow("我的收藏", ProfileIcons.heart, trailing = "${lib.favorites.size}") { go("library/favorites") }
        }
    }

    val contentGroupBlock: @Composable () -> Unit = {
        GroupItem {
            LinkRow("Telegram 频道", ProfileIcons.telegram, trailing = "${ia.tg.visible(ia.cms.adultUnlocked).size} 个") { go("home/channels") }
            RowDivider()
            LinkRow("漫画", ProfileIcons.manga, trailing = ia.manga.favorites.size.takeIf { it > 0 }?.let { "收藏 $it" }) { go("home/manga") }
            RowDivider()
            LinkRow("小说", ProfileIcons.manga, sub = "TXT、阅读书源、搜索与发现") { go("novel") }
            RowDivider()
            val loggedMusic = ia.musicStore.sourcePrefs.count { !it.customCookie.isNullOrBlank() }
            val totalMusicSrc = ia.musicStore.enabledOnlineSources.size + ia.musicStore.nasConfigs.size
            LinkRow(
                "音乐与音源设置",
                Icons.Filled.MusicNote,
                sub = "账号登录、音源优先级、个人 NAS 与缓存",
                trailing = if (loggedMusic > 0) "已登 $loggedMusic · $totalMusicSrc 源" else "$totalMusicSrc 源",
            ) { go("music/settings") }
            RowDivider()
            LinkRow("下载管理", ProfileIcons.download, trailing = "暂未支持") { notify("Android 版暂不支持离线下载") }
        }
    }

    val backupGroupBlock: @Composable () -> Unit = {
        GroupItem("数据备份与迁移") {
            LinkRow(
                "WebDAV 多端同步",
                ProfileIcons.cloud,
                trailing = ia.webdav.statusBadge ?: "未配置",
                sub = "iOS / Android 双向合并进度、收藏与已装插件",
            ) { go("webdav") }
            RowDivider()
            LinkRow(
                if (backupBusy) "正在处理备份…" else "导出完整备份（.idjbackup）",
                ProfileIcons.backup,
                sub = "无损打包插件、采集站、TVBox、进度、收藏与书源",
            ) {
                if (!backupBusy) {
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                    exportBackupLauncher.launch("i短剧备份-$stamp.${AppBackup.FILE_EXTENSION}")
                }
            }
            RowDivider()
            LinkRow(
                "从备份文件恢复",
                ProfileIcons.restore,
                sub = "智能合并导入，不覆盖本机更新的观看进度",
            ) {
                if (!backupBusy) importBackupLauncher.launch(arrayOf("*/*"))
            }
        }
    }

    val manageGroupBlock: @Composable () -> Unit = {
        GroupItem {
            val adult = ia.cms.adultUnlocked
            LinkRow("片源管理", ProfileIcons.sources, trailing = if (adult) "红果 · 黄果" else "红果") { go("sources") }
            RowDivider()
            val n = ia.plugins.plugins.count { !it.adult || adult } + ia.cms.sites.count { !it.adult || adult }
            LinkRow("插件", ProfileIcons.plugin, trailing = n.takeIf { it > 0 }?.let { "$it 个" }) { go("plugins") }
            RowDivider()
            LinkRow("开源许可", ProfileIcons.license) { go("licenses") }
            RowDivider()
            val up = ia.updater
            LinkRow("检查更新", ProfileIcons.update, trailing = if (up.checking) "检查中…" else up.currentVersion.joinToString(".")) {
                scope.launch { up.manualCheck()?.let(notify) }
            }
        }
    }

    val maintenanceGroupBlock: @Composable () -> Unit = {
        GroupItem {
            ActRow("重置红果设备注册", ProfileIcons.reset, scope, notify) { withContext(Dispatchers.IO) { ia.hongguo.resetDevice() }; "已重置红果设备" }
            RowDivider()
            if (ia.cms.adultUnlocked) {
                ActRow("重置黄果访客会话", ProfileIcons.key, scope, notify) { withContext(Dispatchers.IO) { ia.huangguo.resetSession() }; "已清除黄果会话" }
                RowDivider()
            }
            ActRow("清空封面缓存", ProfileIcons.photos, scope, notify) {
                coil.Coil.imageLoader(ctx).let { l -> l.memoryCache?.clear(); withContext(Dispatchers.IO) { l.diskCache?.clear() } }; "封面缓存已清空"
            }
            RowDivider()
            ActRow("清空播放缓存", ProfileIcons.trash, scope, notify) { withContext(Dispatchers.IO) { java.io.File(ctx.cacheDir, "hg-play").deleteRecursively() }; "播放缓存已清空" }
        }
    }

    val footerBlock: @Composable () -> Unit = {
        Text(
            message ?: "所有数据由本机直连上游，观看记录和收藏只保存在本机。\ni短剧 $ver",
            color = if (message != null) c.accent else c.secondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }

    if (metrics.tier == SizeTier.Expanded) {
        Box(Modifier.fillMaxSize().background(c.bg).statusBarsPadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 1240.dp).fillMaxSize().padding(top = 12.dp)) {
                Text(
                    "我的",
                    color = c.text,
                    fontWeight = FontWeight.Black,
                    fontSize = 32.sp,
                    modifier = Modifier.padding(horizontal = metrics.pagePadding).padding(bottom = 14.dp).semantics { heading() },
                )
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    LazyColumn(
                        Modifier.weight(0.42f).fillMaxHeight(),
                        contentPadding = PaddingValues(bottom = rootBottomInset() + 16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        item("account", "account") {
                            AccountCard(ia.hgWebAccount.profile, ia.hgWebAccount.lastSyncNote, { go("hongguo-login") }) { confirmLogout = true }
                        }
                        if (watching.isNotEmpty()) item("watching", "shelf") {
                            ContinueShelf(watching, onOpenDetail, { go("library/history") }) { lib.removeProgress(it) }
                        }
                        item("favorites", "header") { favoritesBlock(3) }
                        item("library", "group") { libraryGroupBlock() }
                    }
                    LazyColumn(
                        Modifier.weight(0.58f).fillMaxHeight(),
                        contentPadding = PaddingValues(bottom = rootBottomInset() + 16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        item("content", "group") { contentGroupBlock() }
                        item("backup", "group") { backupGroupBlock() }
                        item("look", "group") { GroupItem("外观") { AppearanceRows() } }
                        item("manage", "group") { manageGroupBlock() }
                        item("maintenance", "group") { maintenanceGroupBlock() }
                        item("footer", "footer") { footerBlock() }
                    }
                }
            }
        }
    } else {
        Box(Modifier.fillMaxSize().background(c.bg).statusBarsPadding(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = if (metrics.tier == SizeTier.Medium) 740.dp else androidx.compose.ui.unit.Dp.Unspecified).fillMaxSize(),
                contentPadding = PaddingValues(top = 12.dp, bottom = rootBottomInset() + 12.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                item("title", "title") {
                    Text("我的", color = c.text, fontWeight = FontWeight.Black, fontSize = 32.sp, modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() })
                }
                item("account", "account") {
                    AccountCard(ia.hgWebAccount.profile, ia.hgWebAccount.lastSyncNote, { go("hongguo-login") }) { confirmLogout = true }
                }
                if (watching.isNotEmpty()) item("watching", "shelf") { ContinueShelf(watching, onOpenDetail, { go("library/history") }) { lib.removeProgress(it) } }
                item("favorites", "header") { favoritesBlock(cols) }
                item("library", "group") { libraryGroupBlock() }
                item("content", "group") { contentGroupBlock() }
                item("backup", "group") { backupGroupBlock() }
                item("look", "group") { GroupItem("外观") { AppearanceRows() } }
                item("manage", "group") { manageGroupBlock() }
                item("maintenance", "group") { maintenanceGroupBlock() }
                item("footer", "footer") { footerBlock() }
            }
        }
    }

    if (confirmLogout) GlassConfirm(
        onDismissRequest = { confirmLogout = false },
        title = "退出红果账号？",
        message = "退出后将停止同步看剧进度到红果网页端，本地观看记录不受影响。",
        confirmText = "退出",
        destructive = true,
        onConfirm = {
            ia.hgWebAccount.logout()
            confirmLogout = false
            notify("已退出红果账号")
        },
    )
}

/** [Group] with the page gutter. */
@Composable
private fun GroupItem(title: String? = null, rows: @Composable ColumnScope.() -> Unit) =
    Box(Modifier.padding(horizontal = 16.dp)) { Group(title, rows = rows) }

/** iOS `hongguoAccountCard`: scan-to-login, or the signed-in account with 退出. */
@Composable
private fun AccountCard(profile: HongguoWebAccount.Profile?, syncNote: String?, onLogin: () -> Unit, onLogout: () -> Unit) {
    val c = IdjThemeColors
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().dsCard(18.dp)
            .then(if (profile == null) Modifier.rowClick(onClick = onLogin) else Modifier).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (profile == null) IconWell(Icons.Outlined.QrCodeScanner, 44.dp)
        else Box(Modifier.size(44.dp).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
            if (profile.avatarUrl.isNotEmpty()) AsyncImage(profile.avatarUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(profile.nickname.take(1), color = c.accent, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(profile?.nickname ?: "红果短剧扫码登录", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (profile == null) "扫码连接账号，同步短剧观看进度" else syncNote ?: "已连接红果短剧，观看进度自动同步",
                color = c.secondary, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (profile == null) GlassButton("去扫码", size = ButtonSize.Small, onClick = onLogin)
        else GlassButton("退出", tone = ButtonTone.Glass, size = ButtonSize.Small, onClick = onLogout)
    }
}

/** iOS `ContinueRow`: 112×150 covers with the resume label and progress on the poster; long press deletes the record. */
@Composable
private fun ContinueShelf(list: List<WatchProgress>, onOpen: (String) -> Unit, onAll: () -> Unit, onRemove: (String) -> Unit) {
    val c = IdjThemeColors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("继续观看", color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).semantics { heading() })
            Text("全部", color = c.secondary, fontSize = 15.sp, modifier = Modifier.pressClick(onClick = onAll))
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(list, key = { it.seriesId }, contentType = { "watching" }) { p -> ContinueCover(p, onOpen, onRemove, Modifier.animateItem()) }
        }
    }
}

private val CoverScrim = Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(0.7f))

@Composable
private fun ContinueCover(p: WatchProgress, onOpen: (String) -> Unit, onRemove: (String) -> Unit, modifier: Modifier) {
    val c = IdjThemeColors
    var menu by remember { mutableStateOf(false) }
    Box(modifier.width(112.dp)) {
        Column(Modifier.pressLongClick(label = "${p.title}，${p.resumeLabel}", longLabel = "删除观看记录", onLongClick = { menu = true }) { onOpen(p.seriesId) },
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(112.dp, 150.dp).clip(PosterShape).background(c.glass)) {
                AsyncImage(p.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.matchParentSize().background(CoverScrim))
                Column(Modifier.align(Alignment.BottomCenter).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(p.resumeLabel, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    GlassProgressBar({ p.fraction }, height = 3.dp, colors = IdjDarkColors)
                }
            }
            Text(p.title, color = c.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        GlassMenu(menu, { menu = false }) {
            GlassMenuItem("删除观看记录", icon = Icons.Outlined.Delete, destructive = true) { menu = false; onRemove(p.seriesId) }
        }
    }
}

/** Plain library cell: 3:4 cover + one title line. */
@Composable
internal fun CoverCell(title: String, cover: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = IdjThemeColors
    Column(modifier.pressClick(0.97f, label = title, onClick = onClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.75f).clip(PosterShape).background(c.glass)) {
            AsyncImage(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Text(title, color = c.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 玻璃质感 + 通透度; its own scope so dragging the slider recomposes only these rows. */
@Composable
private fun AppearanceRows() {
    val c = IdjThemeColors
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("玻璃质感", color = c.text, fontSize = 16.sp)
        SegmentedTabs(GlassStyle.entries, GlassStylePref.style, { it.label }, { GlassStylePref.update(it) })
        if (GlassStylePref.style == GlassStyle.LIQUID) {
            val strength = GlassStylePref.strength
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("通透度", color = c.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text("${(strength * 100).roundToInt()}%", color = c.secondary, fontSize = 13.sp)
            }
            GlassSlider(strength, { GlassStylePref.updateStrength(it) }, Modifier.testTag("profile.glass.strength"),
                label = "通透度", valueText = { "${(it * 100).roundToInt()}%" })
        }
    }
}

/** Maintenance row: runs [work] and reports its message in the footer. */
@Composable
private fun ActRow(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, scope: kotlinx.coroutines.CoroutineScope,
    onDone: (String) -> Unit, work: suspend () -> String) =
LinkRow(title, icon) { scope.launch { onDone(runCatching { work() }.getOrElse { it.message ?: "失败" }) } }
