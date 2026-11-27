package app.iduanju.tvbox

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.iduanju.IdjApp
import app.iduanju.core.tvbox.LiveGroup
import app.iduanju.core.tvbox.LiveKey
import app.iduanju.core.tvbox.SiteKind
import app.iduanju.core.tvbox.TVBoxEngine
import app.iduanju.core.tvbox.TVBoxId
import app.iduanju.core.tvbox.TVBoxSite
import app.iduanju.core.tvbox.Trust
import app.iduanju.core.tvbox.VodItem
import app.iduanju.data.Catalog
import app.iduanju.ui.components.CapsuleButton
import app.iduanju.ui.components.ChipRow
import app.iduanju.ui.components.DSPage
import app.iduanju.ui.components.DSProgressBar
import app.iduanju.ui.components.DSStatus
import app.iduanju.ui.components.IconWell
import app.iduanju.ui.components.LocalLayoutMetrics
import app.iduanju.ui.components.MiniButton
import app.iduanju.ui.components.MoreLink
import app.iduanju.ui.components.PillButton
import app.iduanju.ui.components.PosterArt
import app.iduanju.ui.components.RoundIconButton
import app.iduanju.ui.components.SectionHeader2
import app.iduanju.ui.components.SegmentedTabs
import app.iduanju.ui.components.SizeTier
import app.iduanju.ui.components.StatusTone
import app.iduanju.ui.components.ToneChip
import app.iduanju.ui.components.dsCard
import app.iduanju.ui.components.pressClick
import app.iduanju.ui.theme.IdjThemeColors
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch

private val DockSpace = 112.dp

/** TVBox tab: 点播 / 直播, the current site or live source, search, categories and posters. Playback always goes through the shared detail page and player. */
@Composable
fun TVBoxHubScreen(onOpenConfig: () -> Unit, onOpenDetail: (sid: String) -> Unit, onPlay: (sid: String, index: Int) -> Unit) {
    val store = IdjApp.instance.tvbox
    val model = viewModel<TVBoxHubModel>()
    val adult = IdjApp.instance.cms.adultUnlocked
    val repo = store.activeRepo
    // before any pane is created for this state of the config
    remember(repo?.id, repo?.record?.updatedAt, repo?.record?.trust) { model.sync(repo); true }
    val sites = remember(repo, adult) { repo?.let { store.visibleSites(it, adult) }.orEmpty() }
    val lives = remember(repo) { repo?.let { store.visibleLives(it) }.orEmpty() }
    val site = sites.firstOrNull { it.uid == store.state.lastSite[repo?.id] } ?: sites.firstOrNull { !it.site.kind.isCode || repo?.trusted == true } ?: sites.firstOrNull()
    val live = lives.firstOrNull { it.uid == store.state.lastLive[repo?.id] } ?: lives.firstOrNull()
    LaunchedEffect(repo?.id, sites.isEmpty(), lives.isEmpty()) { if (sites.isEmpty() && lives.isNotEmpty()) model.mode = 1 else if (lives.isEmpty() && sites.isNotEmpty()) model.mode = 0 }
    var picker by remember { mutableStateOf(false) }
    var asking by remember(repo?.id) { mutableStateOf(false) }

    DSPage(cool = model.mode == 1) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SegmentedTabs(listOf(0, 1), model.mode, { if (it == 0) "点播" else "直播" }, { model.mode = it }, Modifier.width(140.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    val label = if (model.mode == 0) site?.site?.name else live?.live?.name
                    if (label != null) CapsuleButton(label, trailing = Icons.Rounded.KeyboardArrowDown) { picker = true }
                }
                RoundIconButton(Icons.Rounded.Settings, "TVBox 配置", onClick = onOpenConfig)
            }
            when {
                !store.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { TvSpinner(26) }
                repo == null -> TvEmpty("还没有 TVBox 配置", "支持 CMS、T4、JS、Python、JAR 站点和 M3U / TXT 直播，也支持多仓与加密配置。", "添加配置", onAction = onOpenConfig)
                else -> {
                    if (repo.needsTrust) TrustBanner(repo) { asking = true }
                    if (repo.error != null) TvEmpty("配置无法读取", repo.error, "去配置管理", onAction = onOpenConfig)
                    else if (model.mode == 0) {
                        if (site == null) TvEmpty("没有可用的点播站点", "可以在配置里启用站点，或换一个配置。", "去配置管理", onAction = onOpenConfig)
                        else VodSection(model, repo, site, sites, onOpenDetail, onPlay, onTrust = { asking = true })
                    } else {
                        if (live == null) TvEmpty("没有直播源", "当前配置没有启用的直播列表。", "去配置管理", onAction = onOpenConfig)
                        else key(live.uid) { LiveSection(model.live(live.uid), live, onPlay) }
                    }
                }
            }
        }
    }

    if (picker && repo != null) {
        if (model.mode == 0) SitePicker(repo, sites, site?.uid, onDismiss = { picker = false }, onConfig = { picker = false; onOpenConfig() }) { picker = false; store.setLastSite(repo.id, it); model.search.clear() }
        else LivePicker(repo, lives, live?.uid, onDismiss = { picker = false }) { picker = false; store.setLastLive(repo.id, it) }
    }
    if (asking && repo != null) TrustDialog(repo, { ok -> asking = false; store.setTrust(repo.id, if (ok) Trust.Trusted else Trust.Denied) }, { asking = false; store.setTrust(repo.id, Trust.Denied) })
    model.message?.let { m -> TvDialog("提示", "知道了", { model.message = null }, { model.message = null }, dismiss = null) { TvText(m) } }
}

@Composable
private fun TrustBanner(repo: TVBoxRepo, onAsk: () -> Unit) {
    val c = IdjThemeColors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(c.warmSoft).clickable(onClick = onAsk).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.Shield, null, Modifier.size(18.dp), tint = c.accentWarm)
        Text("「${repo.name}」含 JAR / JS / Python 站点，信任后才会运行", color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        MiniButton("查看", onAsk)
    }
}

// ---------------------------------------------------------------------------------------------------------------- vod

@Composable
private fun VodSection(model: TVBoxHubModel, repo: TVBoxRepo, site: TVBoxSiteRef, sites: List<TVBoxSiteRef>, onOpenDetail: (String) -> Unit, onPlay: (String, Int) -> Unit, onTrust: () -> Unit) {
    val focus = LocalFocusManager.current
    val search = model.search
    val pane = model.site(site.uid)
    val searchable = remember(sites, repo.trusted) { sites.filter { it.site.searchable && it.site.kind != SiteKind.Unsupported && (!it.site.kind.isCode || repo.trusted) } }
    val blocked = site.site.kind.isCode && !repo.trusted
    LaunchedEffect(pane, blocked) { if (!blocked) pane.ensure() }
    val submit = { focus.clearFocus(); search.submit(site.uid, searchable) }
    BackHandler(search.active) { search.clear() }
    BackHandler(!search.active && pane.folders.isNotEmpty()) { pane.folders.removeAt(pane.folders.lastIndex) }

    Column(Modifier.fillMaxSize()) {
        TvSearchField(
            search.query, { search.query = it; if (it.isEmpty() && search.active) search.clear() }, if (search.all) "搜索全部 ${searchable.size} 个站点" else "搜索 ${site.site.name}",
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp), onSearch = submit,
        ) {
            Row(Modifier.padding(end = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ToneChip("当前站", !search.all, small = true) { if (search.all) { search.all = false; if (search.active) submit() } }
                ToneChip("全部站", search.all, small = true) { if (!search.all) { search.all = true; if (search.query.isNotBlank()) submit() } }
            }
        }
        val open: (VodItem) -> Unit = { item ->
            when {
                item.action.isNotEmpty() -> model.action(item)
                item.folder -> { search.clear(); pane.folders.add(item) }
                else -> { Catalog.rememberPreview(item.sid, item.title, item.cover); onOpenDetail(item.sid) }
            }
        }
        when {
            search.active -> SearchResults(search, site, open)
            blocked -> TvEmpty("${site.site.kind.badge} 站点尚未启用", "这个站点的代码需要你先信任「${repo.name}」才会运行。", "信任配置", onAction = onTrust)
            pane.folders.isNotEmpty() -> FolderList(pane, open)
            else -> SiteContent(model, pane, site, open, onPlay)
        }
    }
}

@Composable
private fun SiteContent(model: TVBoxHubModel, pane: SitePane, site: TVBoxSiteRef, open: (VodItem) -> Unit, onPlay: (String, Int) -> Unit) {
    val home = pane.home
    var filterFor by remember(pane) { mutableStateOf<String?>(null) }
    val tabs = remember(home, pane.loading, pane.category.isEmpty()) { buildList { if (home == null || home.items.isNotEmpty() || pane.loading && pane.category.isEmpty()) add("" to "推荐"); home?.categories?.forEach { add(it.id to it.name) } } }
    if (tabs.size > 1) Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        ChipRow(tabs, tabs.firstOrNull { it.first == pane.category } ?: tabs.first(), { it.second }, { pane.category = it.first }, modifier = Modifier.weight(1f))
        if (pane.category.isNotEmpty() && pane.filters(pane.category).isNotEmpty()) {
            val on = pane.picked(pane.category).isNotEmpty()
            RoundIconButton(Icons.Rounded.Tune, "筛选", if (on) app.iduanju.ui.components.Tone.Accent else app.iduanju.ui.components.Tone.Glass, 32.dp) { filterFor = pane.category }
            Spacer(Modifier.width(16.dp))
        }
    }
    if (pane.category.isEmpty()) {
        LaunchedEffect(pane) { pane.show(null) }
        val items = home?.items.orEmpty()
        PosterGrid(pane.homeGrid, items, landscape = items.firstOrNull()?.landscape == true, open = open, header = {
            item(key = "continue", span = { GridItemSpan(maxLineSpan) }) { ContinueRow(onPlay) }
            item(key = "head", span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader2("推荐", site.site.name, inset = 0.dp, modifier = Modifier.padding(top = 4.dp)) { if (pane.loading && items.isNotEmpty()) TvSpinner(16) else MoreLink("刷新", Icons.Rounded.Refresh) { pane.refresh() } }
            }
        }, footer = { ListStatus(pane.loading, items.isEmpty(), pane.error, "这个站点没有推荐内容", { pane.load(true) }) })
    } else {
        val list = pane.list(pane.category, pane.picked(pane.category))
        LaunchedEffect(list) { pane.show(list) }
        key(list) { PagedList(list) { open(it) } }
    }
    filterFor?.let { tid -> FilterSheet(pane, tid) { filterFor = null } }
}

@Composable
private fun PagedList(list: VodListState, open: (VodItem) -> Unit) {
    LaunchedEffect(list) { list.ensure() }
    LoadMore(list.grid, !list.loading && list.error == null) { list.load() }
    PosterGrid(list.grid, list.items, landscape = list.items.firstOrNull()?.landscape == true, open = open, footer = { ListStatus(list.loading, list.items.isEmpty(), list.error, "这里还没有内容", { if (list.items.isEmpty()) list.load(true) else list.load() }) })
}

@Composable
private fun FolderList(pane: SitePane, open: (VodItem) -> Unit) {
    val c = IdjThemeColors
    val current = pane.folders.last()
    val list = pane.list(current.id)
    LaunchedEffect(list) { pane.show(list) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MoreLink("返回", Icons.Rounded.Folder) { pane.folders.removeAt(pane.folders.lastIndex) }
        pane.folders.forEachIndexed { i, f ->
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(14.dp), tint = c.tertiary)
            Text(f.title, color = if (i == pane.folders.lastIndex) c.text else c.secondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).clickable { while (pane.folders.size > i + 1) pane.folders.removeAt(pane.folders.lastIndex) })
        }
    }
    key(list) { PagedList(list, open) }
}

@Composable
private fun SearchResults(search: SearchPane, site: TVBoxSiteRef, open: (VodItem) -> Unit) {
    val groups = remember(search.sites.toMap()) { listOf("" to ("全部" to search.items.size)) + search.sites.entries.map { it.key to it.value } }
    if (search.all) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            if (groups.size > 1) ChipRow(groups, groups.firstOrNull { it.first == search.only } ?: groups.first(), { "${it.second.first} ${if (it.first.isEmpty()) search.items.size else it.second.second}" }, { search.only = it.first }, small = true)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("已返回 ${search.done} / ${search.total} 个站点" + if (search.failed > 0) " · ${search.failed} 个失败" else "", color = IdjThemeColors.secondary, fontSize = 12.sp)
                if (search.loading) TvSpinner(12)
            }
        }
    }
    val shown = if (search.only.isEmpty()) search.items else search.items.filter { it.uid == search.only }
    if (!search.all) LoadMore(search.grid, !search.loading && search.error == null) { search.more(site.uid) }
    PosterGrid(search.grid, shown, landscape = false, open = open, label = if (search.all) { item -> search.sites[item.uid]?.first.orEmpty() } else null,
        footer = { ListStatus(search.loading && !search.all || search.loading && search.items.isEmpty(), shown.isEmpty(), search.error, "没有找到「${search.submitted}」", null) })
}

/** The next page when the end comes near. [idle]: also when that happened while something was still loading. */
@Composable
private fun LoadMore(grid: LazyGridState, idle: Boolean, load: () -> Unit) {
    val latest by rememberUpdatedState(load)
    val near by remember(grid) { derivedStateOf { grid.layoutInfo.let { i -> i.totalItemsCount > 0 && (i.visibleItemsInfo.lastOrNull()?.index ?: 0) >= i.totalItemsCount - 8 } } }
    val total by remember(grid) { derivedStateOf { grid.layoutInfo.totalItemsCount } }
    LaunchedEffect(near, total, idle) { if (idle && near && total > 3) latest() }
}

/** Poster wall; landscape sites get 16:9 cards. Keys are stable, covers load at cell size. */
@Composable
private fun PosterGrid(state: LazyGridState, items: List<VodItem>, landscape: Boolean, open: (VodItem) -> Unit, label: ((VodItem) -> String)? = null, header: LazyGridScope.() -> Unit = {}, footer: @Composable () -> Unit) {
    val m = LocalLayoutMetrics.current
    LazyVerticalGrid(
        GridCells.Fixed(if (landscape) m.wideCardCols else m.posterCols), Modifier.fillMaxSize(), state, PaddingValues(start = m.pagePadding, end = m.pagePadding, top = 6.dp, bottom = DockSpace),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        header()
        itemsIndexed(items, key = { i, it -> "${it.uid}|${it.id}|$i" }, contentType = { _, _ -> "poster" }) { _, item -> PosterCell(item, landscape, label?.invoke(item), open) }
        item(key = "footer", span = { GridItemSpan(maxLineSpan) }) { footer() }
    }
}

@Composable
private fun PosterCell(item: VodItem, landscape: Boolean, label: String?, open: (VodItem) -> Unit) {
    val c = IdjThemeColors
    Column(Modifier.pressClick(0.97f, label = item.title) { open(item) }) {
        Box(Modifier.fillMaxWidth().aspectRatio(if (landscape) 16f / 9f else 3f / 4f)) {
            if (item.folder && item.cover.isEmpty()) Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(c.glass), contentAlignment = Alignment.Center) { IconWell(Icons.Rounded.Folder, 44.dp) }
            else PosterArt(item.cover, Modifier.fillMaxSize(), badge = item.remark.ifEmpty { null })
            if (!label.isNullOrEmpty()) Text(
                label, Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(0.55f)).padding(horizontal = 6.dp, vertical = 2.dp),
                color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(item.title, color = c.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ListStatus(loading: Boolean, empty: Boolean, error: String?, nothing: String, onRetry: (() -> Unit)?) {
    val c = IdjThemeColors
    Box(Modifier.fillMaxWidth().padding(vertical = if (empty) 48.dp else 16.dp), contentAlignment = Alignment.Center) {
        when {
            error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(error, color = c.secondary, fontSize = 13.sp, lineHeight = 19.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                if (onRetry != null) MiniButton("重试", onRetry)
            }
            loading -> TvSpinner()
            empty -> Text(nothing, color = c.secondary, fontSize = 13.sp)
        }
    }
}

/** 继续观看 of TVBox titles, straight into the player. */
@Composable
private fun ContinueRow(onPlay: (String, Int) -> Unit) {
    val lib = IdjApp.instance.library
    val repos = IdjApp.instance.tvbox.repos
    val list = remember(lib.history.toList(), repos) { lib.continueWatching.filter { p -> TVBoxId.parseVod(p.seriesId)?.let { IdjApp.instance.tvbox.engine.site(it.uid) } != null }.take(12) }
    if (list.isEmpty()) return
    val c = IdjThemeColors
    Column(Modifier.padding(bottom = 4.dp)) {
        SectionHeader2("继续观看", inset = 0.dp)
        Spacer(Modifier.height(10.dp))
        LazyRow(contentPadding = PaddingValues(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(list, key = { it.seriesId }) { p ->
                Row(Modifier.width(232.dp).dsCard(16.dp).pressClick(0.98f, label = "继续播放 ${p.title}") { onPlay(p.seriesId, p.episodeNumber) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(48.dp, 64.dp).clip(RoundedCornerShape(9.dp)).background(c.glass)) { AsyncImage(p.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(p.title, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(p.resumeLabel, color = c.secondary, fontSize = 12.sp, maxLines = 1)
                        DSProgressBar(p.fraction)
                    }
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(20.dp), tint = c.accent)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(pane: SitePane, tid: String, onDismiss: () -> Unit) {
    val c = IdjThemeColors
    TvSheet("筛选", onDismiss, trailing = { MiniButton("重置") { pane.resetFilters(tid) } }) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            pane.filters(tid).forEach { f ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(f.name, color = c.secondary, fontSize = 13.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val cur = pane.picked(tid)[f.key].orEmpty()
                        f.options.forEach { (n, v) -> ToneChip(n, cur == v, small = true) { pane.pick(tid, f.key, v) } }
                    }
                }
            }
        }
        PillButton("完成", Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp), height = 44.dp, onClick = onDismiss)
    }
}

// ---------------------------------------------------------------------------------------------------------------- pickers

@Composable
private fun SitePicker(repo: TVBoxRepo, sites: List<TVBoxSiteRef>, current: String?, onDismiss: () -> Unit, onConfig: () -> Unit, onPick: (String) -> Unit) {
    val store = IdjApp.instance.tvbox
    var query by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("全部") }
    val kinds = remember(sites) { listOf("全部") + sites.map { it.site.kind.badge }.distinct() }
    val shown = remember(sites, query, kind) { sites.filter { (kind == "全部" || it.site.kind.badge == kind) && (query.isBlank() || it.site.name.contains(query.trim(), true) || it.site.key.contains(query.trim(), true)) } }
    TvSheet("选择站点", onDismiss, trailing = { MiniButton("切换配置", onConfig) }) {
        TvSearchField(query, { query = it }, "搜索 ${sites.size} 个站点", Modifier.padding(horizontal = 16.dp))
        if (kinds.size > 2) ChipRow(kinds, kind, { it }, { kind = it }, small = true, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(shown, key = { it.uid }) { s ->
                TvRow(s.site.name, siteStatus(repo, s), selected = s.uid == current, leading = { KindBadge(s.site.kind) }, onClick = { onPick(s.uid) }) {
                    store.latency[s.uid]?.let { ms -> DSStatus(if (ms < 0) "失败" else "$ms ms", if (ms < 0) StatusTone.Muted else if (ms < 1500) StatusTone.Ok else StatusTone.Warn) }
                }
            }
            if (shown.isEmpty()) item { TvEmpty("没有匹配的站点") }
        }
    }
}

private fun siteStatus(repo: TVBoxRepo, s: TVBoxSiteRef): String? = when {
    s.site.kind == SiteKind.Unsupported -> "不支持的类型"
    s.site.kind.isCode && !repo.trusted -> "需要信任配置"
    s.site.kind == SiteKind.Py && !app.iduanju.tvbox.runtime.PyRuntime.INCLUDED -> "此版本未包含 Python 运行时"
    IdjApp.instance.tvbox.engine.status(s.uid) == TVBoxEngine.SpiderStatus.Ready -> "已加载"
    !s.site.searchable -> "不参与搜索"
    else -> null
}

@Composable
private fun LivePicker(repo: TVBoxRepo, lives: List<TVBoxLiveRef>, current: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) = TvSheet("选择直播源", onDismiss) {
    LazyColumn(Modifier.weight(1f, fill = false)) {
        items(lives, key = { it.uid }) { l ->
            val kind = l.live.kind
            TvRow(l.live.name, if (kind != null && !repo.trusted) "需要信任配置" else l.live.url.ifEmpty { if (kind != null) l.live.api else "配置内置" }, selected = l.uid == current, leading = { if (kind != null) KindBadge(kind) }, onClick = { onPick(l.uid) })
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------- live

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiveSection(pane: LivePane, live: TVBoxLiveRef, onPlay: (String, Int) -> Unit) {
    val c = IdjThemeColors
    val store = IdjApp.instance.tvbox
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var locked by remember { mutableStateOf<LiveGroup?>(null) }
    LaunchedEffect(pane) { pane.ensure() }
    val unlocked = store.state.unlocked[pane.uid]
    LaunchedEffect(unlocked) { pane.show() }
    val groups = remember(pane.data) { listOf(LivePane.ALL, LivePane.FAVORITES) + pane.data?.list?.groups.orEmpty().map { it.name } }
    val favorites = store.state.favorites
    val shown = remember(pane.rows, pane.group, favorites, query) {
        val q = query.trim()
        pane.rows.filter { r ->
            r.number > 0 && (if (q.isNotEmpty()) r.channel.name.contains(q, true) else when (pane.group) { LivePane.ALL -> true; LivePane.FAVORITES -> r.channel.name in favorites; else -> r.group.name == pane.group })
        }
    }
    Column(Modifier.fillMaxSize()) {
        TvSearchField(query, { query = it }, "搜索 ${pane.rows.size} 个频道", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        if (query.isBlank() && groups.size > 2) ChipRow(groups, pane.group, { g -> if (pane.data?.list?.groups?.firstOrNull { it.name == g }?.let { pane.locked(it) } == true) "$g（已锁）" else LivePane.label(g) }, { g ->
            val target = pane.data?.list?.groups?.firstOrNull { it.name == g }
            if (target != null && pane.locked(target)) locked = target else pane.group = g
        }, modifier = Modifier.padding(vertical = 4.dp))
        val metrics = LocalLayoutMetrics.current
        val liveCols = when {
            metrics.isUltra -> 3
            metrics.tier != SizeTier.Compact -> 2
            else -> 1
        }
        val epg = remember(pane.data) { pane.data?.let { d -> d.ref.live.epg.isNotEmpty() || d.list.epg.isNotEmpty() || d.list.groups.any { g -> g.channels.any { it.epg.isNotEmpty() } } } == true }
        LazyColumn(Modifier.fillMaxSize(), pane.list, contentPadding = PaddingValues(bottom = DockSpace)) {
            if (liveCols > 1) {
                val chunked = shown.chunked(liveCols)
                items(chunked, key = { it.first().key }, contentType = { "channelRow" }) { pair ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = metrics.pagePadding - 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { row ->
                            if (epg) LaunchedEffect(row.key) { pane.nowPlaying(row) }
                            Box(Modifier.weight(1f)) {
                                ChannelRow(row, pane.now[row.key]?.text.orEmpty(), row.channel.name in favorites, live.live.logo, onFavorite = { store.toggleFavorite(row.channel.name) }) {
                                    Catalog.rememberChannel(TVBoxId.live(pane.uid), LiveKey(row.number, row.group.name, row.channel.name, row.channel.urls.firstOrNull().orEmpty()))
                                    onPlay(TVBoxId.live(pane.uid), row.number)
                                }
                            }
                        }
                        repeat(liveCols - pair.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            } else {
                items(shown, key = { it.key }, contentType = { "channel" }) { row ->
                    if (epg) LaunchedEffect(row.key) { pane.nowPlaying(row) }
                    ChannelRow(row, pane.now[row.key]?.text.orEmpty(), row.channel.name in favorites, live.live.logo, onFavorite = { store.toggleFavorite(row.channel.name) }) {
                        Catalog.rememberChannel(TVBoxId.live(pane.uid), LiveKey(row.number, row.group.name, row.channel.name, row.channel.urls.firstOrNull().orEmpty()))
                        onPlay(TVBoxId.live(pane.uid), row.number)
                    }
                }
            }
            item(key = "footer") {
                ListStatus(pane.loading && shown.isEmpty(), shown.isEmpty(), pane.error?.takeIf { shown.isEmpty() || pane.data == null }, if (pane.group == LivePane.FAVORITES && query.isBlank()) "还没有收藏的频道" else "没有频道", { pane.load(true) })
            }
        }
    }
    locked?.let { g ->
        var pass by remember(g) { mutableStateOf("") }
        var wrong by remember(g) { mutableStateOf(false) }
        val open: () -> Unit = { if (pass.trim() == g.pass) { store.unlock(pane.uid, g.name); locked = null; scope.launch { pane.show(); pane.group = g.name } } else wrong = true }
        TvDialog("「${g.name}」已加锁", "打开", open, { locked = null }) {
            TvField(pass, { pass = it; wrong = false }, "输入分组密码", onAction = open)
            if (wrong) Text("密码不正确", color = c.accentWarm, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun ChannelRow(row: LiveRow, now: String, favorite: Boolean, logoTemplate: String, onFavorite: () -> Unit, onClick: () -> Unit) {
    val c = IdjThemeColors
    val ch = row.channel
    val logo = ch.logo.ifEmpty { if (logoTemplate.contains("{name}")) logoTemplate.replace("{name}", ch.guideName) else "" }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(56.dp, 40.dp).clip(RoundedCornerShape(10.dp)).background(c.glass).border(1.dp, c.hairline, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            if (logo.isEmpty()) Text(ch.name.take(4), color = c.secondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            else AsyncImage(ImageRequest.Builder(LocalContext.current).data(logo).crossfade(false).build(), null, Modifier.fillMaxSize().padding(5.dp), contentScale = ContentScale.Fit)
        }
        Column(Modifier.weight(1f)) {
            Text(ch.name, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(now.ifEmpty { if (ch.urls.size > 1) "${row.group.name} · ${ch.urls.size} 条线路" else row.group.name }, color = if (now.isEmpty()) c.tertiary else c.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder, if (favorite) "取消收藏" else "收藏", Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onFavorite).padding(8.dp), tint = if (favorite) c.accentWarm else c.tertiary)
    }
}
