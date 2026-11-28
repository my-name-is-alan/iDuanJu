package app.iduanju.ui.home

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.iduanju.ui.components.*
import dev.chrisbanes.haze.hazeSource

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iduanju.IdjApp
import app.iduanju.core.cms.CMSSite
import app.iduanju.core.hongguo.HGRankGenre
import app.iduanju.core.hongguo.HGRankSub
import app.iduanju.core.hongguo.HGSeries
import app.iduanju.data.WatchProgress
import app.iduanju.plugin.PluginStore
import app.iduanju.ui.Routes
import app.iduanju.ui.theme.IdjThemeColors
import coil.compose.AsyncImage
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Mirrors iOS HomeSource / HomeTab (HomeView.swift). */
enum class HomeSource(val label: String) { hongguo("红果"), huangguo("黄果"), ai("AI剧"), channels("频道"), manga("漫画"), novel("小说") }

sealed class HomeTab(val subLabel: String) {
    data class Hongguo(val g: HGRankGenre) : HomeTab(
        when (g) { HGRankGenre.all -> "全部榜单"; HGRankGenre.ai -> "红果 AI剧"; else -> g.label }
    )
    data object ActorRank : HomeTab("剧星榜")
}

fun HomeSource.subs(): List<HomeTab> = when (this) {
    HomeSource.hongguo -> listOf(HomeTab.Hongguo(HGRankGenre.all), HomeTab.Hongguo(HGRankGenre.human), HomeTab.Hongguo(HGRankGenre.comic), HomeTab.ActorRank)
    HomeSource.ai -> listOf(HomeTab.Hongguo(HGRankGenre.ai))
    else -> emptyList()
}

data class BoardSpec(val genre: HGRankGenre, val sub: HGRankSub) {
    val id get() = "hg-${genre.name}-${sub.name}"
    val title get() = (if (genre == HGRankGenre.all) "" else genre.label) + sub.label + "榜"
}

/** First-page cache per board (iOS BoardCache). */
object BoardCache {
    val items = mutableStateMapOf<String, List<HGSeries>>()
    val failed = mutableStateMapOf<String, String>()
    private val loading = HashSet<String>()
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate)
    /** The running request (null when there is nothing to load), for callers that show its progress. */
    fun load(b: BoardSpec, force: Boolean = false): kotlinx.coroutines.Job? {
        synchronized(loading) { if (b.id in loading || (!force && items.containsKey(b.id))) return null; loading.add(b.id) }
        // Repository lifetime, not the current composition: even an early navigation away keeps the result.
        return scope.launch {
            try {
                android.util.Log.i("IDJ", "board fetch ${b.id}")
                val r = withContext(Dispatchers.IO) { IdjApp.instance.hongguo.rank(b.sub, b.genre).items }
                items[b.id] = r.distinctBy { it.seriesId }; failed.remove(b.id)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { failed[b.id] = e.message ?: "加载失败"; android.util.Log.w("IDJ", "board ${b.id} failed: $e") }
            finally { synchronized(loading) { loading.remove(b.id) } }
        }
    }
}

object HomeFmt {
    fun count(n: Long): String = when {
        n >= 100_000_000 -> String.format("%.1f亿", n / 100_000_000.0)
        n >= 10_000 -> String.format("%.1f万", n / 10_000.0)
        else -> n.toString()
    }
    fun metaLine(s: HGSeries): String = when {
        s.episodeCount > 0 && s.playCount > 0 -> "${s.episodeCount}集 · ${count(s.playCount)}热度"
        s.episodeCount > 0 -> "${s.episodeCount}集"
        s.playCount > 0 -> count(s.playCount) + "热度"
        else -> s.subtitle ?: ""
    }
    fun meta(s: HGSeries): String {
        val genres = s.tags.take(2).joinToString(" · ")
        val parts = listOfNotNull(genres.ifEmpty { null }, if (s.episodeCount > 0) "${s.episodeCount}集" else null)
        return if (parts.isEmpty()) metaLine(s) else parts.joinToString(" · ")
    }
    /** Only 「更新至N集 / 全N集」. */
    fun progress(s: HGSeries): String? {
        s.subtitle?.let { if (it.contains("更新至") || it.startsWith("全")) return it }
        return if (s.episodeCount > 0) "全${s.episodeCount}集" else null
    }
}

fun formatCount(n: Long) = HomeFmt.count(n)

private enum class HomePageKind(val label: String) {
    BUILTIN("内置"), CMS("采集站"), PLUGIN("插件"), FEATURE("功能");
    val isImported: Boolean get() = this == CMS || this == PLUGIN
}

/** One home page: a built-in source, a 采集站 or a plugin. [key] is stable across label / order changes. */
private data class HomePageRef(val key: String, val label: String, val target: Any, val kind: HomePageKind) {
    val canHideFromHome: Boolean get() = key != HomeSource.hongguo.name
}

/** All enabled sources/features eligible for Home, including folded ones, in user's persisted custom order. */
private fun allManageableHomePages(): List<HomePageRef> {
    val app = IdjApp.instance
    val adult = app.cms.adultUnlocked
    fun built(s: HomeSource, kind: HomePageKind = HomePageKind.BUILTIN) = HomePageRef(s.name, s.label, s, kind)
    val raw = buildList {
        add(built(HomeSource.hongguo))
        if (adult) add(built(HomeSource.huangguo))
        add(built(HomeSource.ai))
        app.cms.visibleSites.filterNot { it.origin.equals("TVBox", ignoreCase = true) || it.origin.startsWith("TVBox") }
            .forEach { add(HomePageRef("cms:" + it.key, it.name, it, HomePageKind.CMS)) }
        app.plugins.visible(adult).filter { it.type == "video" || it.type == "shortdrama" }
            .forEach { add(HomePageRef("plugin:" + it.id, it.name, it, HomePageKind.PLUGIN)) }
        if (app.settings.showChannelsTab) add(built(HomeSource.channels, HomePageKind.FEATURE))
        if (app.settings.showMangaTab) add(built(HomeSource.manga, HomePageKind.FEATURE))
        if (app.settings.showNovelTab) add(built(HomeSource.novel, HomePageKind.FEATURE))
    }
    val byKey = raw.associateBy { it.key }
    return app.settings.orderedKeys(raw.map { it.key }).mapNotNull { byKey[it] }
}

private fun homePages(): List<HomePageRef> {
    val settings = IdjApp.instance.settings
    val all = allManageableHomePages()
    val visible = all.filterNot { settings.isHiddenOnHome(it.key) }
    return visible.ifEmpty { all.take(1) }
}

/** A feed tells the home header whether it is scrolled; the header then pins (iOS `onScrolled`). */
internal val LocalFeedScrolled = staticCompositionLocalOf<(Boolean) -> Unit> { {} }

@Composable
internal fun ReportScrolled(scrolled: () -> Boolean) {
    val report = LocalFeedScrolled.current
    val latest by rememberUpdatedState(scrolled)
    LaunchedEffect(report) { snapshotFlow { latest() }.collect { report(it) } }
}

@Composable
internal fun ReportScrolled(list: LazyListState) = ReportScrolled { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 12 }

/** Space the floating tab bar (beside the tablet rail: only the system bar) covers at the bottom of a root page. */
@Composable
internal fun rootBottomInset(): Dp =
    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + if (LocalLayoutMetrics.current.useRail) 8.dp else 72.dp

@Composable
fun HomeScreen(onOpenDetail: (String) -> Unit, onSearch: () -> Unit = {}, onPlay: (String) -> Unit = {}, onOpenManga: (String) -> Unit = {}, onImmersiveChanged: (Boolean) -> Unit = {}) {
    val c = IdjThemeColors
    val allManageable by remember { derivedStateOf { allManageableHomePages() } }
    val pages by remember { derivedStateOf { homePages() } }
    val pager = rememberPagerState { pages.size }
    LaunchedEffect(pages.size) {
        if (pages.isNotEmpty() && pager.currentPage >= pages.size) {
            pager.scrollToPage(pages.lastIndex)
        }
    }
    LaunchedEffect(HomeJump.label, pages.size) {
        val label = HomeJump.label ?: return@LaunchedEffect
        val i = pages.indexOfFirst { it.label == label }
        if (i >= 0) { pager.scrollToPage(i); HomeJump.label = null }
    }
    val scope = rememberCoroutineScope()
    var tabScrollJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val jumpToPage: (Int) -> Unit = { target ->
        val clamped = target.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        tabScrollJob?.cancel()
        tabScrollJob = scope.launch {
            // Direct jump across >1 tab prevents sweeping intermediate plugin tabs and cancelled-animation snap-backs.
            if (kotlin.math.abs(clamped - pager.currentPage) <= 1) {
                pager.animateScrollToPage(clamped)
            } else {
                pager.scrollToPage(clamped)
            }
        }
    }
    val scrolled = remember { mutableStateMapOf<String, Boolean>() }
    val current by remember { derivedStateOf { pages[pager.currentPage.coerceIn(0, pages.size - 1)] } }
    val density = LocalDensity.current
    // 漫画 / 小说 own their lists, so their scroll is followed from outside; the other feeds report it (ReportScrolled).
    val feedScrollConnection = remember(density) {
        val offsets = HashMap<String, Float>()
        val threshold = with(density) { 8.dp.toPx() }
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): Offset {
                val page = current
                if (page.target != HomeSource.manga && page.target != HomeSource.novel) return Offset.Zero
                val v = ((offsets[page.key] ?: 0f) - consumed.y).coerceAtLeast(0f)
                offsets[page.key] = v
                if ((scrolled[page.key] == true) != (v > threshold)) scrolled[page.key] = v > threshold
                return Offset.Zero
            }
        }
    }
    var novelFullScreen by remember { mutableStateOf(false) }
    val immersive by remember { derivedStateOf { current.target == HomeSource.novel && novelFullScreen } }
    LaunchedEffect(immersive) { onImmersiveChanged(immersive) }
    DisposableEffect(Unit) { onDispose { onImmersiveChanged(false) } }
    val pinned by remember { derivedStateOf { scrolled[current.key] == true } }

    // Local backdrop of what sits behind the header: page colour + ambient glow. The header is a sibling drawn on top
    // of this layer, so its search capsule gets real drawBackdrop lens refraction (LIQUID) / blur (FROSTED). The
    // root backdrop can't be used here: this page lives inside it and would sample itself.
    val bgColor = c.bg
    val homeBackdrop = rememberLayerBackdrop {
        drawRect(bgColor)
        drawContent()
    }
    val haze = remember { dev.chrisbanes.haze.HazeState() }
    Box(Modifier.fillMaxSize().background(c.bg)) {
        Box(Modifier.fillMaxSize().layerBackdrop(homeBackdrop)) { AmbientGlow(visible = !pinned) }
        val metrics = LocalLayoutMetrics.current
        Column(Modifier.fillMaxSize().then(if (immersive) Modifier else Modifier.statusBarsPadding())) {
            // header: compact single-row bar on Expanded tablets (72dp rail has top search), 2-row bar on compact/medium.
            if (!immersive) Column(Modifier.dsPinned(pinned, haze).padding(top = 6.dp)) {
                val selectedPage = pager.currentPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                val channelBtnLabel = if (allManageable.size > 7 || allManageable.size > pages.size) "频道 · ${allManageable.size}" else "频道"
                if (metrics.useRail) {
                    Row(
                        Modifier.fillMaxWidth().padding(end = metrics.pagePadding),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f)) {
                            ChannelBar(pages, selectedPage) { jumpToPage(it) }
                        }
                        Spacer(Modifier.width(10.dp))
                        Row(
                            Modifier.height(38.dp)
                                .glassSurface(RoundedCornerShape(50), backdrop = homeBackdrop, shadowRadius = 0.dp, fallbackAlpha = if (c.dark) 0.62f else 0.82f)
                                .testTag("home.filter").pressClick(label = "筛选与全部频道") { HomeChannelSheetOpen.value = true }
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.FilterList, null, tint = c.text, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(5.dp))
                            Text(channelBtnLabel, color = c.text, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        }
                    }
                } else {
                    Row(
                        Modifier.padding(horizontal = metrics.pagePadding).fillMaxWidth().height(IntrinsicSize.Min)
                            // same glass material as the dock / detail chrome (follows 我的 → 玻璃质感)
                            .glassSurface(RoundedCornerShape(50), backdrop = homeBackdrop, shadowRadius = 0.dp, fallbackAlpha = if (c.dark) 0.60f else 0.80f)
                            .heightIn(min = 46.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(Modifier.weight(1f).fillMaxHeight().testTag("home.search").pressClick(0.98f, label = "搜索", onClick = onSearch).padding(start = 16.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Search, null, tint = c.secondary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("搜索你想看的作品", color = c.secondary, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Row(Modifier.fillMaxHeight().testTag("home.filter").pressClick(label = "筛选与全部频道") { HomeChannelSheetOpen.value = true }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.FilterList, null, tint = c.text, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text(channelBtnLabel, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    ChannelBar(pages, selectedPage) { jumpToPage(it) }
                }
                if (HomeChannelSheetOpen.value) ChannelSheet(
                    allItems = allManageable,
                    visibleItems = pages,
                    currentKey = pages.getOrNull(selectedPage)?.key.orEmpty(),
                    onDismiss = { HomeChannelSheetOpen.value = false },
                    onPickKey = { key, sub ->
                        val settings = IdjApp.instance.settings
                        if (settings.isHiddenOnHome(key)) settings.setHomeHidden(key, false)
                        HomeChannelSheetOpen.value = false
                        val updated = homePages()
                        val idx = updated.indexOfFirst { it.key == key }
                        if (idx >= 0) {
                            if (sub != null) HomeSubSel.map[updated[idx].label] = sub
                            jumpToPage(idx)
                        }
                    },
                )
            }
            HorizontalPager(
                pager,
                Modifier.fillMaxSize()
                    .then(if (android.os.Build.VERSION.SDK_INT >= 31) Modifier.hazeSource(haze) else Modifier)
                    .nestedScroll(feedScrollConnection),
                userScrollEnabled = !immersive,
                beyondViewportPageCount = 0,
                key = { pages.getOrNull(it)?.key ?: it },
            ) { index ->
                val page = pages.getOrElse(index) { pages.first() }
                val report = remember(page.key) { { on: Boolean -> if ((scrolled[page.key] == true) != on) scrolled[page.key] = on } }
                CompositionLocalProvider(LocalFeedScrolled provides report) {
                    when (val target = page.target) {
                        is CMSSite -> SiteFeedPage(target, onOpenDetail)
                        is PluginStore.Installed -> PluginFeedPage(target, onOpenDetail)
                        HomeSource.huangguo -> app.iduanju.huangguo.HuangguoFeedPage(onOpenDetail)
                        HomeSource.manga -> app.iduanju.manga.MangaHomePage(onOpenManga)
                        HomeSource.novel -> app.iduanju.novel.NovelScreen(onBack = {}, embedded = true, onFullScreenChange = { novelFullScreen = it })
                        HomeSource.channels -> app.iduanju.tg.ChannelFeedPage(onPlay)
                        HomeSource.ai -> AiPage(onOpenDetail)
                        else -> FeedPage(target as HomeSource, onOpenDetail)
                    }
                }
            }
        }
    }
}

/** iOS AI剧: 红果 AI剧 | 黄果 AI 站 (the latter only behind the adult gate). */
@Composable
private fun AiPage(onOpen: (String) -> Unit) {
    if (!IdjApp.instance.cms.adultUnlocked) { FeedPage(HomeSource.ai, onOpen); return }
    val aiTabs = remember { listOf("红果 AI剧", "黄果 AI 站") }
    val sel = (HomeSubSel.map[HomeSource.ai.label] ?: 0).coerceIn(0, 1)
    val metrics = LocalLayoutMetrics.current
    Column(Modifier.fillMaxSize()) {
        SegmentedTabs(
            items = aiTabs,
            selected = aiTabs[sel],
            label = { it },
            onSelect = { HomeSubSel.map[HomeSource.ai.label] = aiTabs.indexOf(it) },
            modifier = Modifier.padding(horizontal = metrics.pagePadding).padding(bottom = 12.dp),
        )
        Box(Modifier.weight(1f)) {
            if (sel == 1) app.iduanju.huangguo.HuangguoFeedPage(onOpen, aiOnly = true)
            else FeedPage(HomeSource.ai, onOpen)
        }
    }
}

/** 「筛选」 and the channel chevron open the same 频道 sheet. */
private val HomeChannelSheetOpen = mutableStateOf(false)

/** Sub-tab chosen from the 频道 sheet, per home page label. */
object HomeSubSel { val map = androidx.compose.runtime.mutableStateMapOf<String, Int>() }
/** One-shot request from another tab (我的 → Telegram 频道 / 漫画) to show a home source page. */
object HomeJump { var label by androidx.compose.runtime.mutableStateOf<String?>(null) }

/** Unified Home Channel & Source Manager sheet: search, category filter, fold-from-home, batch cleanup, pin & reorder. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ChannelSheet(
    allItems: List<HomePageRef>,
    visibleItems: List<HomePageRef>,
    currentKey: String,
    onDismiss: () -> Unit,
    onPickKey: (String, Int?) -> Unit,
) {
    val c = IdjThemeColors
    val settings = IdjApp.instance.settings
    var manageMode by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("全部") }

    val hiddenItems = remember(allItems, settings.homeHidden) { allItems.filter { settings.isHiddenOnHome(it.key) } }
    val importedItems = remember(allItems) { allItems.filter { it.kind.isImported } }
    val cmsCount = remember(allItems) { allItems.count { it.kind == HomePageKind.CMS } }
    val pluginCount = remember(allItems) { allItems.count { it.kind == HomePageKind.PLUGIN } }
    val filterTabs = remember(allItems, visibleItems.size, hiddenItems.size, cmsCount, pluginCount) {
        buildList {
            add("全部" to allItems.size)
            add("首页显示" to visibleItems.size)
            if (hiddenItems.isNotEmpty()) add("已收起" to hiddenItems.size)
            if (cmsCount > 0 || pluginCount > 0) {
                add("内置" to allItems.count { !it.kind.isImported })
                if (cmsCount > 0) add("采集站" to cmsCount)
                if (pluginCount > 0) add("插件" to pluginCount)
            }
        }
    }
    val q = search.trim()
    fun matches(item: HomePageRef): Boolean {
        if (q.isNotEmpty() && !item.label.contains(q, true) && !item.kind.label.contains(q, true)) return false
        return when (filter) {
            "首页显示" -> !settings.isHiddenOnHome(item.key)
            "已收起" -> settings.isHiddenOnHome(item.key)
            "内置" -> !item.kind.isImported
            "采集站" -> item.kind == HomePageKind.CMS
            "插件" -> item.kind == HomePageKind.PLUGIN
            else -> true
        }
    }
    val filteredVisible = remember(visibleItems, q, filter, settings.homeHidden) { visibleItems.filter(::matches) }
    val filteredHidden = remember(hiddenItems, q, filter, settings.homeHidden) { hiddenItems.filter(::matches) }

    GlassBottomSheet(onDismiss, title = "频道管理", doneText = "完成", scroll = true) {
        Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                "首页显示 ${visibleItems.size} 个 · 已收起 ${hiddenItems.size} 个（收起后搜索仍可用）",
                color = c.secondary,
                fontSize = 12.5.sp,
            )

            SegmentedTabs(
                items = listOf(false, true),
                selected = manageMode,
                label = { if (it) "管理与排序" else "快速直达" },
                onSelect = { manageMode = it },
            )

            GlassSearchField(search, { search = it }, Modifier.fillMaxWidth(), placeholder = "搜索频道或片源名称…")

            if (filterTabs.size > 2) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filterTabs, key = { it.first }) { (tab, count) ->
                        ToneChip(tab, filter == tab, neutral = tab == "全部", small = true, count = count) { filter = tab }
                    }
                }
            }

            if (manageMode) {
                if (importedItems.isNotEmpty() || settings.homeOrder.isNotEmpty() || hiddenItems.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("一键整理首页顶栏", color = c.secondary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (importedItems.isNotEmpty()) {
                                val allImportedHidden = importedItems.all { settings.isHiddenOnHome(it.key) }
                                SheetTile(
                                    if (allImportedHidden) "展开导入源" else "仅保留内置",
                                    !allImportedHidden,
                                    Modifier.weight(1f).fillMaxHeight(),
                                ) {
                                    settings.setHomeHiddenBatch(importedItems.map { it.key }, !allImportedHidden)
                                }
                            }
                            if (hiddenItems.isNotEmpty()) {
                                SheetTile("全部显示", false, Modifier.weight(1f).fillMaxHeight()) { settings.clearHomeHidden() }
                            }
                            if (settings.homeOrder.isNotEmpty()) {
                                SheetTile("默认顺序", false, Modifier.weight(1f).fillMaxHeight()) { settings.resetHomeOrder() }
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("功能频道开关", color = c.secondary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SheetTile("频道", settings.showChannelsTab, Modifier.weight(1f).fillMaxHeight()) { settings.updateShowChannelsTab(!settings.showChannelsTab) }
                        SheetTile("漫画", settings.showMangaTab, Modifier.weight(1f).fillMaxHeight()) { settings.updateShowMangaTab(!settings.showMangaTab) }
                        SheetTile("小说", settings.showNovelTab, Modifier.weight(1f).fillMaxHeight()) { settings.updateShowNovelTab(!settings.showNovelTab) }
                        SheetTile("底部音乐", settings.showMusicTab, Modifier.weight(1f).fillMaxHeight()) { settings.updateShowMusicTab(!settings.showMusicTab) }
                    }
                }

                if (filteredVisible.isNotEmpty()) {
                    var draggingKey by remember { mutableStateOf<String?>(null) }
                    var dragOffsetY by remember { mutableFloatStateOf(0f) }
                    val latestVisible by rememberUpdatedState(visibleItems)
                    val latestFiltered by rememberUpdatedState(filteredVisible)
                    val rowHeightPx = with(LocalDensity.current) { 52.dp.toPx() }
                    val haptic = LocalHapticFeedback.current

                    fun onDragDelta(itemKey: String, dy: Float) {
                        dragOffsetY += dy
                        val filt = latestFiltered
                        val curFiltIdx = filt.indexOfFirst { it.key == itemKey }
                        if (curFiltIdx < 0) return
                        val targetFiltIdx = when {
                            dragOffsetY > rowHeightPx * 0.52f && curFiltIdx + 1 < filt.size -> curFiltIdx + 1
                            dragOffsetY < -rowHeightPx * 0.52f && curFiltIdx - 1 >= 0 -> curFiltIdx - 1
                            else -> -1
                        }
                        if (targetFiltIdx >= 0) {
                            val full = latestVisible
                            val fromFull = full.indexOfFirst { it.key == itemKey }
                            val toFull = full.indexOfFirst { it.key == filt[targetFiltIdx].key }
                            if (fromFull >= 0 && toFull >= 0) {
                                val next = full.toMutableList()
                                val tmp = next[fromFull]; next[fromFull] = next[toFull]; next[toFull] = tmp
                                settings.updateHomeOrder(next.map { it.key })
                                dragOffsetY += if (targetFiltIdx > curFiltIdx) -rowHeightPx else rowHeightPx
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("首页已显示（${filteredVisible.size}）", color = c.secondary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                            Text("长按或拖 ≡ 排序", color = c.tertiary, fontSize = 11.5.sp)
                        }
                        Column(Modifier.fillMaxWidth().dsCard(16.dp)) {
                            filteredVisible.forEachIndexed { idx, item ->
                                key(item.key) {
                                    val fullIdx = visibleItems.indexOfFirst { it.key == item.key }
                                    val isDragging = draggingKey == item.key
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .height(52.dp)
                                            .zIndex(if (isDragging) 10f else 0f)
                                            .graphicsLayer {
                                                translationY = if (isDragging) dragOffsetY else 0f
                                                scaleX = if (isDragging) 1.02f else 1f
                                                scaleY = if (isDragging) 1.02f else 1f
                                            }
                                            .then(
                                                if (isDragging) Modifier
                                                    .shadow(10.dp, RoundedCornerShape(14.dp))
                                                    .clip(RoundedCornerShape(14.dp))
                                                    .background(c.card)
                                                    .border(1.2.dp, c.accent.copy(alpha = 0.8f), RoundedCornerShape(14.dp))
                                                else Modifier
                                            )
                                            .pointerInput(item.key) {
                                                detectDragGesturesAfterLongPress(
                                                    onDragStart = {
                                                        draggingKey = item.key
                                                        dragOffsetY = 0f
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    },
                                                    onDrag = { change, dragAmount ->
                                                        change.consume()
                                                        onDragDelta(item.key, dragAmount.y)
                                                    },
                                                    onDragEnd = { draggingKey = null; dragOffsetY = 0f },
                                                    onDragCancel = { draggingKey = null; dragOffsetY = 0f },
                                                )
                                            },
                                    ) {
                                        Row(
                                            Modifier.fillMaxSize().padding(horizontal = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            // Instant drag handle (touch-drag immediately with zero wait)
                                            Box(
                                                Modifier
                                                    .size(30.dp, 48.dp)
                                                    .pointerInput(item.key) {
                                                        detectVerticalDragGestures(
                                                            onDragStart = {
                                                                draggingKey = item.key
                                                                dragOffsetY = 0f
                                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                            },
                                                            onVerticalDrag = { change, dragAmount ->
                                                                change.consume()
                                                                onDragDelta(item.key, dragAmount)
                                                            },
                                                            onDragEnd = { draggingKey = null; dragOffsetY = 0f },
                                                            onDragCancel = { draggingKey = null; dragOffsetY = 0f },
                                                        )
                                                    },
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(
                                                    Icons.Rounded.DragHandle,
                                                    "拖动排序 ${item.label}",
                                                    tint = if (isDragging) c.accent else c.tertiary,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            }
                                            Text(
                                                "${idx + 1}",
                                                color = if (idx == 0) c.accent else c.tertiary,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.width(20.dp),
                                                textAlign = TextAlign.Center,
                                            )
                                            Text(
                                                item.label,
                                                color = c.text,
                                                fontSize = 14.5.sp,
                                                fontWeight = if (isDragging) FontWeight.SemiBold else FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f),
                                            )
                                            if (item.kind.isImported) DSTag(item.kind.label)
                                            PlainIconButton(
                                                Icons.Rounded.VerticalAlignTop,
                                                "置顶 ${item.label}",
                                                tint = if (fullIdx > 0) c.secondary else c.tertiary.copy(alpha = 0.3f),
                                                iconSize = 18.dp,
                                            ) {
                                                if (fullIdx > 0) {
                                                    val next = visibleItems.toMutableList()
                                                    val picked = next.removeAt(fullIdx)
                                                    next.add(0, picked)
                                                    settings.updateHomeOrder(next.map { it.key })
                                                }
                                            }
                                            if (item.canHideFromHome) {
                                                PlainIconButton(
                                                    Icons.Rounded.VisibilityOff,
                                                    "从首页收起 ${item.label}",
                                                    tint = c.secondary,
                                                    iconSize = 17.dp,
                                                ) {
                                                    settings.setHomeHidden(item.key, true)
                                                }
                                            }
                                        }
                                        if (!isDragging && idx < filteredVisible.lastIndex) {
                                            Hairline(Modifier.align(Alignment.BottomCenter).padding(start = 44.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (filteredHidden.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("已从首页收起（${filteredHidden.size} · 搜索仍可用）", color = c.secondary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                        Column(Modifier.fillMaxWidth().dsCard(16.dp)) {
                            filteredHidden.forEachIndexed { idx, item ->
                                Row(
                                    Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(horizontal = 14.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        item.label,
                                        color = c.secondary,
                                        fontSize = 14.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    DSTag(item.kind.label)
                                    Row(
                                        Modifier.clip(CircleShape).background(c.accentSoft)
                                            .pressClick(0.96f) { settings.setHomeHidden(item.key, false) }
                                            .padding(horizontal = 10.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                                    ) {
                                        Icon(Icons.Rounded.Add, null, tint = c.accent, modifier = Modifier.size(14.dp))
                                        Text("放回首页", color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                                if (idx < filteredHidden.lastIndex) Hairline(Modifier.padding(start = 14.dp))
                            }
                        }
                    }
                }
            } else {
                // Mode 1: 快速直达
                if (filteredVisible.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("首页频道（${filteredVisible.size}）", color = c.secondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                            if (importedItems.isNotEmpty()) {
                                GlassTextButton("管理 / 收起 ›", color = c.accent, fontSize = 13.sp) { manageMode = true }
                            }
                        }
                        filteredVisible.chunked(3).forEach { row ->
                            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { item ->
                                    SheetTile(item.label, item.key == currentKey, Modifier.weight(1f).fillMaxHeight()) { onPickKey(item.key, null) }
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }

                // Built-in multi-sub sections only (eliminates 50 duplicate single-chip CMS sections)
                if (q.isEmpty() && (filter == "全部" || filter == "内置" || filter == "首页显示")) {
                    visibleItems.filter(::matches).forEach { page ->
                        val p = page.target as? HomeSource ?: return@forEach
                        val subs: List<String> = when (p) {
                            HomeSource.huangguo -> listOf("视频站", "App 源")
                            HomeSource.ai -> p.subs().map { it.subLabel } + if (IdjApp.instance.cms.adultUnlocked) listOf("黄果 AI 站") else emptyList()
                            HomeSource.hongguo -> p.subs().map { it.subLabel }
                            else -> emptyList()
                        }
                        if (subs.size <= 1) return@forEach
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("${page.label} · 板块", color = c.secondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                            subs.chunked(3).forEach { row ->
                                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    row.forEach { t ->
                                        val si = subs.indexOf(t)
                                        val on = page.key == currentKey && (HomeSubSel.map[page.label] ?: 0) == si
                                        SheetTile(t, on, Modifier.weight(1f).fillMaxHeight()) { onPickKey(page.key, if (p != HomeSource.huangguo) si else null) }
                                    }
                                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }

                if (filteredHidden.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("已收起的片源（${filteredHidden.size} · 点按放回并打开）", color = c.secondary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                        filteredHidden.chunked(3).forEach { row ->
                            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { item ->
                                    Box(
                                        Modifier.weight(1f).fillMaxHeight()
                                            .pressClick(0.97f) { onPickKey(item.key, null) }
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(c.glass.copy(alpha = 0.65f))
                                            .dashedCapsuleBorder(c.hairline, width = 1.dp, dash = 4.dp, gap = 3.dp)
                                            .heightIn(min = 42.dp)
                                            .padding(horizontal = 6.dp, vertical = 8.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(item.label, color = c.secondary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }

                if (filteredVisible.isEmpty() && filteredHidden.isEmpty()) {
                    Text(
                        "没有匹配的频道或片源",
                        color = c.tertiary,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    )
                }
            }
        }
    }
}

/** Plain well on the sheet glass (iOS: glass fill, accent 14 % when selected, r10). */
@Composable
private fun SheetTile(text: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = IdjThemeColors
    Box(modifier.pressClick(0.97f, onClick = onClick).clip(RoundedCornerShape(12.dp))
        .background(if (on) c.text else c.glass)
        .border(0.9.dp, if (on) c.accent.copy(alpha = 0.55f) else c.hairline, RoundedCornerShape(12.dp))
        .semantics { selected = on }.heightIn(min = 44.dp).padding(horizontal = 6.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (on) c.bg else c.text, fontSize = 14.5.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** iOS HomeChannelBar parity: unselected 17 secondary, selected 24 heavy + 26x4 accent capsule; redundant chevron removed. */
@Composable
private fun ChannelBar(items: List<HomePageRef>, selected: Int, onSelect: (Int) -> Unit) {
    val state = rememberLazyListState()
    LaunchedEffect(selected) {
        val info = state.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == selected }
        if (item != null) {
            val center = (info.viewportEndOffset - info.viewportStartOffset) / 2
            state.animateScrollBy((item.offset + item.size / 2 - center).toFloat(), DS.select())
        } else state.animateScrollToItem(selected)
    }
    LazyRow(
        state = state,
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).fadeEnd(0.92f),
        contentPadding = PaddingValues(start = 20.dp, end = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        itemsIndexed(items, key = { _, p -> p.key }, contentType = { _, _ -> "channel" }) { i, page ->
            ChannelTab(page.label, i == selected) { onSelect(i) }
        }
    }
}

/** Yimeng-inspired editorial channel tab: 24 sp heavy ink + hand-drawn scribble ring & wavy crayon underline. */
@Composable
private fun ChannelTab(label: String, on: Boolean, onClick: () -> Unit) {
    val c = IdjThemeColors
    val drawn = remember { Animatable(if (on) 1f else 0f) }
    LaunchedEffect(on) {
        if (on) {
            drawn.snapTo(0.02f)
            drawn.animateTo(1f, tween(460, easing = FastOutSlowInEasing))
        } else {
            drawn.snapTo(0f)
        }
    }
    Box(
        Modifier.heightIn(min = 44.dp)
            .selectable(on, remember { MutableInteractionSource() }, null, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = "home.channel.$label" },
        contentAlignment = Alignment.BottomStart,
    ) {
        Box(
            Modifier.padding(bottom = 7.dp).drawBehind {
                val p = drawn.value.coerceIn(0f, 1f)
                if (p > 0.03f) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val rx = size.width * 0.56f + 6.dp.toPx()
                    val ry = size.height * 0.54f + 2.dp.toPx()
                    val fullPath = androidx.compose.ui.graphics.Path()
                    for (k in 0..32) {
                        val t = k.toFloat() / 32f
                        val ang = t * 2.0 * kotlin.math.PI * 1.08 - 0.35
                        val wob = (1.0 + 0.045 * kotlin.math.sin(ang * 3.0 + label.hashCode())).toFloat()
                        val x = cx + kotlin.math.cos(ang).toFloat() * wob * rx
                        val y = cy + kotlin.math.sin(ang).toFloat() * wob * ry
                        if (k == 0) fullPath.moveTo(x, y) else fullPath.lineTo(x, y)
                    }
                    val pm = androidx.compose.ui.graphics.PathMeasure()
                    pm.setPath(fullPath, false)
                    val seg = androidx.compose.ui.graphics.Path()
                    pm.getSegment(0f, pm.length * p, seg, true)
                    drawPath(seg, color = c.accent.copy(alpha = 0.28f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
                }
            },
        ) {
            Text(
                label,
                color = if (on) c.text else c.secondary,
                fontSize = if (on) 24.sp else 17.sp,
                fontWeight = if (on) FontWeight.ExtraBold else FontWeight.Normal,
                maxLines = 1,
                softWrap = false,
            )
        }
        if (drawn.value > 0.03f) {
            androidx.compose.foundation.Canvas(
                Modifier.offset(x = 1.dp).size(34.dp, 7.dp),
            ) {
                val fullPath = androidx.compose.ui.graphics.Path().apply {
                    moveTo(2f, size.height * 0.72f)
                    quadraticTo(size.width * 0.48f, size.height * 0.12f, size.width - 2f, size.height * 0.52f)
                }
                val pm = androidx.compose.ui.graphics.PathMeasure()
                pm.setPath(fullPath, false)
                val seg = androidx.compose.ui.graphics.Path()
                pm.getSegment(0f, pm.length * drawn.value.coerceIn(0f, 1f), seg, true)
                drawPath(
                    path = seg,
                    color = c.accent,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round),
                )
            }
        }
    }
}


@Composable
private fun FeedPage(source: HomeSource, onOpen: (String) -> Unit) {
    val subs = remember(source) { source.subs() }
    if (subs.isEmpty()) { EmptyState("「${source.label}」Android 版尚未移植", Modifier.padding(top = 80.dp)); return }
    val tab = subs.getOrNull(HomeSubSel.map[source.label] ?: 0) ?: subs.first()
    val boards = remember(tab) { (tab as? HomeTab.Hongguo)?.g?.let { g -> g.subs.map { BoardSpec(g, it) } } ?: emptyList() }
    val list = rememberSaveable(tab.subLabel, saver = LazyListState.Saver) { LazyListState() }
    ReportScrolled(list)
    LaunchedEffect(tab) { boards.firstOrNull()?.let { BoardCache.load(it) } }

    val subTabs: @Composable (Modifier) -> Unit = { m ->
        SegmentedTabs(subs, tab, { it.subLabel }, { HomeSubSel.map[source.label] = subs.indexOf(it) }, m)
    }
    if (tab == HomeTab.ActorRank) {
        app.iduanju.ui.profile.ActorRankList(rootBottomInset() + 16.dp, list) { subTabs(Modifier.padding(bottom = 6.dp)) }
        return
    }
    val metrics = LocalLayoutMetrics.current
    val library = IdjApp.instance.library
    val watching by remember { derivedStateOf { library.continueWatching.take(10) } }
    val scope = rememberCoroutineScope()
    var refreshing by remember(tab) { mutableStateOf(false) }
    GlassPullToRefresh(refreshing, {
        if (!refreshing) scope.launch {
            refreshing = true
            try { boards.mapNotNull { BoardCache.load(it, force = true) }.joinAll() } finally { refreshing = false }
        }
    }, Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), list, PaddingValues(bottom = rootBottomInset() + 16.dp)) {
            if (subs.size > 1) item("tabs", "tabs") { subTabs(Modifier.padding(horizontal = metrics.pagePadding).padding(bottom = 16.dp)) }
            item("hero", "hero") { FeedHero(boards.firstOrNull(), onOpen) }
            if (source == HomeSource.hongguo && watching.isNotEmpty()) item("watching", "watching") {
                Box(Modifier.padding(top = 24.dp)) { ContinueRow(watching, onOpen) }
            }
            itemsIndexed(boards, key = { _, b -> b.id }, contentType = { _, _ -> "shelf" }) { n, b -> BoardShelf(b, n == 0, onOpen) }
        }
    }
}

private const val HERO_PICKS = 5

/** Hero of the first board: its top picks, a skeleton while they load, or a retry block. */
@Composable
private fun FeedHero(board: BoardSpec?, onOpen: (String) -> Unit) {
    val items = board?.let { BoardCache.items[it.id] }
    val picks = remember(items) { items?.take(HERO_PICKS).orEmpty() }
    val failed = board?.let { BoardCache.failed[it.id] }
    when {
        picks.isNotEmpty() -> PosterHero(board!!.title, picks, onOpen)
        items == null && failed != null -> ErrorState("加载失败", note = failed) { BoardCache.load(board, force = true) }
        items == null -> SkeletonBlock(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(if (LocalLayoutMetrics.current.tier == SizeTier.Compact) 178.dp else 204.dp), 22.dp)
    }
}

private val HeroShape = RoundedCornerShape(22.dp)
private val HeroGold = Color(0xFFF2B55C)
private val NoFontPadding = PlatformTextStyle(includeFontPadding = false)
private val HeroKicker = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.6.sp, platformStyle = NoFontPadding)
private val HeroTitle = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold, platformStyle = NoFontPadding)
private val HeroMeta = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, platformStyle = NoFontPadding)
private val HeroButton = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, platformStyle = NoFontPadding)
private val HeroScrim = Brush.linearGradient(listOf(Color.Black.copy(0.18f), Color.Black.copy(0.62f)))
private val HeroSideScrim = Brush.linearGradient(listOf(Color.Black.copy(0.42f), Color.Black.copy(0.70f)))
private const val HERO_CTA = "查看详情"
private val CtaPad = 14.dp
private val CtaIcon = 14.dp
private val CtaGap = 6.dp

private fun heroKicker(board: String, i: Int) = if (board.isEmpty()) "TOP ${i + 1}" else "$board · TOP ${i + 1}"
private fun heroMeta(s: HGSeries) = listOf(
    listOfNotNull(s.tags.take(2).joinToString(" · ").ifEmpty { null }, if (s.episodeCount > 0) "${s.episodeCount} 集" else null).joinToString(" · "),
    if (s.playCount > 0) DS.count(s.playCount) + "热度" else ""
).filter { it.isNotEmpty() }

/** Sizes shared by every hero page, measured once per (picks, width, font scale). */
@Immutable
private class HeroMetrics(val poster: Dp, val cardHeight: Dp, val ctaWidth: Dp, val pillHeight: Dp, val touchHeight: Dp, val footerHeight: Dp, val stackedFooter: Boolean)

/** Spec 10/11: intrinsic-height poster/info grid. Android font metrics must never
 * consume the footer's measurement budget (a constrained Column shrank the CTA).
 * Measure all real pager titles first so every page shares one sufficient height. */
@Composable
private fun PosterHero(board: String, picks: List<HGSeries>, onOpen: (String) -> Unit) {
    val pager = rememberPagerState { picks.size }
    // Follows settledPage: currentPage flips halfway through the animation, which would restart the wait
    // and cancel the scroll, leaving the hero stuck between two pages.
    LaunchedEffect(pager, picks.size) {
        if (picks.size > 1) snapshotFlow { pager.settledPage }.collectLatest { page ->
            delay(6000)
            if (!pager.isScrollInProgress) heroScrollTo(pager, (page + 1) % picks.size)
        }
    }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    // Material Text merges the ambient style (including letter spacing). Measure
    // that same resolved style, not a partial style that underestimates the label.
    val ambient = LocalTextStyle.current
    val buttonStyle = remember(ambient) { ambient.merge(HeroButton) }
    val metrics = LocalLayoutMetrics.current
    val scope = rememberCoroutineScope()
    // Tablet: the hero card takes ~62 % and a 「热播速览」 stack fills the rest, so a wide window never shows one
    // small poster in front of an empty dark gradient.
    val withSide = metrics.tier != SizeTier.Compact && picks.size > 1
    BoxWithConstraints(Modifier.padding(horizontal = metrics.pagePadding).fillMaxWidth()) {
        val sideGap = 16.dp
        val heroWidth = if (withSide) (maxWidth - sideGap) * 0.64f else maxWidth
        // Ambient Glass Stage cinema proportions on tablets; exact 112 dp poster preserved on phones.
        val posterMax = when (metrics.tier) {
            SizeTier.Expanded -> 152.dp
            SizeTier.Medium -> 134.dp
            SizeTier.Compact -> 112.dp
        }
        val m = remember(picks, board, heroWidth, posterMax, density, measurer, buttonStyle) {
            val posterWidth = minOf(posterMax, (heroWidth - 28.dp - 14.dp - 160.dp).coerceAtLeast(80.dp))
            val infoWidth = heroWidth - 28.dp - posterWidth - 14.dp
            val infoPx = with(density) { infoWidth.roundToPx() }
            fun textHeight(text: String, style: TextStyle, lines: Int) = with(density) {
                measurer.measure(text, style, maxLines = lines, overflow = TextOverflow.Ellipsis,
                    constraints = Constraints(maxWidth = infoPx)).size.height.toDp()
            }
            val ctaWidth = with(density) {
                val labelWidth = measurer.measure(HERO_CTA, buttonStyle, maxLines = 1, softWrap = false).size.width
                (labelWidth + 2 * CtaPad.roundToPx() + CtaIcon.roundToPx() + CtaGap.roundToPx()).toDp()
            }
            val pillHeight = with(density) { 16.sp.toDp() + 12.dp }.coerceAtLeast(32.dp)
            val touchHeight = maxOf(48.dp, pillHeight)
            val dotsWidth = if (picks.size > 1) (14 + (picks.size - 1) * 9).dp else 0.dp
            val stackedFooter = picks.size > 1 && infoWidth < ctaWidth + dotsWidth + 8.dp
            val footerHeight = touchHeight + if (stackedFooter) 12.dp else 0.dp
            val infoHeight = picks.mapIndexed { i, s ->
                val meta = heroMeta(s)
                4.dp + textHeight(heroKicker(board, i), HeroKicker, 1) + 6.dp + textHeight(s.title, HeroTitle, 2) +
                    (if (meta.isEmpty()) 0.dp else 7.dp + meta.map { textHeight(it, HeroMeta, 1) }.fold(0.dp) { a, b -> a + b }) +
                    8.dp + footerHeight
            }.maxOrNull() ?: 149.dp
            HeroMetrics(posterWidth, maxOf(posterWidth * 4 / 3, infoHeight) + 28.dp, ctaWidth, pillHeight, touchHeight, footerHeight, stackedFooter)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(sideGap)) {
            Box(Modifier.width(heroWidth).height(m.cardHeight)
                .shadow(15.dp, HeroShape, ambientColor = Color.Black.copy(0.22f), spotColor = Color.Black.copy(0.22f))
                .clip(HeroShape).background(Color(0xFF241A1A))) {
                HeroBackdrop(picks, pager)
                Box(Modifier.fillMaxSize().background(HeroScrim))
                HorizontalPager(pager, Modifier.fillMaxSize().testTag("home.hero.pager")) { i -> picks.getOrNull(i)?.let { HeroPage(i, it, board, picks.size, pager, m, buttonStyle, onOpen) } }
            }
            if (withSide) HeroSideStack(board, picks, pager, Modifier.weight(1f).height(m.cardHeight), onOpen) { i ->
                scope.launch { heroScrollTo(pager, i) }
            }
        }
    }
}

private suspend fun heroScrollTo(pager: PagerState, page: Int) = pager.animateScrollToPage(page, animationSpec = DS.panel())

/** Poster colours behind the hero; only this layer follows the page change. */
@Composable
private fun HeroBackdrop(picks: List<HGSeries>, pager: PagerState) {
    val cover by remember(picks, pager) { derivedStateOf { picks.getOrNull(pager.currentPage)?.cover } }
    Crossfade(cover, animationSpec = Motion.fade(Motion.LONG), label = "heroBg") { url -> BlurredCover(url, Modifier.fillMaxSize().scale(1.5f), 0.85f) }
}

@Composable
private fun HeroPage(i: Int, s: HGSeries, board: String, count: Int, pager: PagerState, m: HeroMetrics, buttonStyle: TextStyle, onOpen: (String) -> Unit) {
    Row(Modifier.fillMaxSize().testTag("home.hero.page.$i").graphicsLayer { alpha = 1f - kotlin.math.abs(pager.getOffsetDistanceInPages(i)).coerceIn(0f, 1f) * 1.6f }
        .pressClick(0.98f, label = "${heroKicker(board, i)}，${s.title}") {
            app.iduanju.data.Catalog.rememberPreview(s.seriesId, s.title, s.cover)
            onOpen(s.seriesId)
        }.padding(14.dp), verticalAlignment = Alignment.Top) {
        PosterArt(s.cover, Modifier.size(m.poster, m.poster * 4 / 3), badge = HomeFmt.progress(s)?.let { DS.spaced(it) })
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).fillMaxHeight().padding(top = 3.dp, bottom = 1.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                app.iduanju.ui.components.DoodleSparkle(Color(0xFFC8F542), size = 9.dp)
                Text(heroKicker(board, i), Modifier.testTag("home.hero.kicker.$i"), color = Color(0xFFC8F542), style = HeroKicker, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(s.title, Modifier.padding(top = 6.dp).testTag("home.hero.title.$i"), color = Color.White, style = HeroTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            heroMeta(s).forEachIndexed { j, line ->
                Text(line, Modifier.padding(top = if (j == 0) 7.dp else 0.dp).testTag("home.hero.meta.$i.$j"),
                    color = Color.White.copy(0.74f), style = HeroMeta, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.weight(1f).heightIn(min = 8.dp))
            val prog = remember(s.seriesId) { IdjApp.instance.library.progress(s.seriesId) }
            val cta: @Composable () -> Unit = { HeroCta(i, m, buttonStyle, prog?.fraction ?: 0f) { onOpen(s.seriesId) } }
            if (m.stackedFooter) Column(Modifier.fillMaxWidth().height(m.footerHeight)) {
                cta()
                Box(Modifier.fillMaxWidth().height(12.dp), contentAlignment = Alignment.CenterEnd) { HeroDots(i, count, pager) }
            } else Row(Modifier.fillMaxWidth().height(m.footerHeight), verticalAlignment = Alignment.CenterVertically) {
                cta(); Spacer(Modifier.weight(1f)); HeroDots(i, count, pager)
            }
        }
    }
}

/** Explicit 48 dp hit slot; Ambient Glass Stage crisp white pill with embedded #286DEF progress rail on tablets. */
@Composable
private fun HeroCta(i: Int, m: HeroMetrics, style: TextStyle, resumeFraction: Float = 0f, onClick: () -> Unit) {
    val tablet = LocalLayoutMetrics.current.tier != SizeTier.Compact
    Box(Modifier.height(m.touchHeight).width(m.ctaWidth).testTag("home.hero.cta.$i")
        .pressClick(0.98f, label = HERO_CTA, onClick = onClick), contentAlignment = Alignment.Center) {
        if (tablet) {
            Box(Modifier.fillMaxWidth().height(m.pillHeight.coerceAtLeast(38.dp)).testTag("home.hero.pill.$i")
                .clip(CircleShape).background(Color(0xFFF8FAFD))) {
                Row(Modifier.fillMaxSize().padding(horizontal = CtaPad),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CtaGap)) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(CtaIcon), tint = Color(0xFF111317))
                    Text(HERO_CTA, Modifier.weight(1f).testTag("home.hero.ctaText.$i"), color = Color(0xFF111317), style = style, maxLines = 1, softWrap = false)
                }
                if (resumeFraction > 0.01f) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.5.dp).background(Color(0xFFC8F542).copy(alpha = 0.24f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(resumeFraction.coerceIn(0.06f, 1f)).background(Color(0xFFC8F542)))
                    }
                }
            }
        } else {
            // Glass CTA over the blurred hero on phones, following 我的 → 液态玻璃 / 毛玻璃.
            Row(Modifier.fillMaxWidth().height(m.pillHeight).testTag("home.hero.pill.$i")
                .glassSurface(CircleShape, dark = true, shadowRadius = 6.dp, fallbackAlpha = 0.70f, activeOverlay = Color.White.copy(alpha = 0.10f))
                .padding(horizontal = CtaPad),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CtaGap)) {
                Icon(Icons.Filled.PlayArrow, null, Modifier.size(CtaIcon), tint = Color.White)
                Text(HERO_CTA, Modifier.weight(1f).testTag("home.hero.ctaText.$i"), color = Color.White, style = style, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun HeroDots(i: Int, count: Int, pager: PagerState) {
    if (count <= 1) return
    val current = pager.currentPage
    Row(Modifier.testTag("home.hero.dots.$i").semantics { contentDescription = "第 ${current + 1} 部，共 $count 部" }, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { n ->
            val w by animateDpAsState(if (n == current) 16.dp else 5.dp, DS.select(), label = "dot")
            Box(Modifier.size(w, 5.dp).clip(CircleShape).background(if (n == current) Color(0xFFC8F542) else Color.White.copy(0.38f)))
        }
    }
}

/**
 * Tablet-only 「热播速览」 companion to the hero: the other picks of the same board as compact rows on the same
 * blurred-cover material, so the hero row is balanced edge to edge. Tapping a row focuses it in the hero; the
 * round arrow opens its detail page.
 */
@Composable
private fun HeroSideStack(board: String, picks: List<HGSeries>, pager: PagerState, modifier: Modifier, onOpen: (String) -> Unit, onFocus: (Int) -> Unit) {
    val others by remember(picks, pager) { derivedStateOf { picks.indices.filter { it != pager.currentPage }.take(3) } }
    Box(modifier
        .shadow(15.dp, HeroShape, ambientColor = Color.Black.copy(0.22f), spotColor = Color.Black.copy(0.22f))
        .clip(HeroShape).background(Color(0xFF1E1A22))) {
        Crossfade(picks.getOrNull(others.firstOrNull() ?: 0)?.cover, animationSpec = Motion.fade(Motion.LONG), label = "heroSideBg") { url ->
            BlurredCover(url, Modifier.fillMaxSize().scale(1.6f), 0.7f)
        }
        Box(Modifier.fillMaxSize().background(HeroSideScrim))
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("热播速览", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                if (board.isNotEmpty()) Text(board, color = Color.White.copy(0.55f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(6.dp))
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.SpaceEvenly) {
                others.forEach { i ->
                    val s = picks[i]
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .testTag("home.hero.side.$i").pressClick(0.98f, label = s.title) { onFocus(i) }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(36.dp, 48.dp).clip(RoundedCornerShape(7.dp)).background(Color.White.copy(0.1f))) {
                            AsyncImage(s.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text("${i + 1}", color = HeroGold, fontSize = 16.sp, fontWeight = FontWeight.Black,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, modifier = Modifier.width(18.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val meta = homeShelfMeta(s)
                            if (meta.isNotEmpty()) Text(meta, color = Color.White.copy(0.62f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Box(Modifier.size(44.dp).pressClick(label = "打开 ${s.title}") { onOpen(s.seriesId) }, contentAlignment = Alignment.Center) {
                            Box(Modifier.size(30.dp).clip(CircleShape).background(Color.White.copy(0.14f)), contentAlignment = Alignment.Center) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HomeBadge(text: String, modifier: Modifier = Modifier) {
    Box(modifier.heightIn(min = 17.dp).clip(RoundedCornerShape(5.dp)).background(Color.Black.copy(0.62f)).padding(horizontal = 5.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** ui-redesign 10/11 `.hs`: one board as section header + horizontal row of 112 dp cells; loads when it is about to show. */
@Composable
private fun BoardShelf(b: BoardSpec, first: Boolean, onOpen: (String) -> Unit) {
    val go = app.iduanju.ui.LocalNavigate.current
    val metrics = LocalLayoutMetrics.current
    LaunchedEffect(b.id) { BoardCache.load(b) }
    val items = BoardCache.items[b.id]
    val failed = items == null && BoardCache.failed.containsKey(b.id)
    val useTabletMatrix = first && metrics.tier != SizeTier.Compact
    val cols = metrics.posterCols
    // 第一个榜单一律跳过 hero 里已展示的剧；平板端首榜按列数整行截断为无框海报矩阵，消除第二行大面积空位。
    val list = remember(items, first, useTabletMatrix, cols, metrics.shelfItemCount) {
        val rem = items?.drop(if (first) HERO_PICKS else 0)
        if (rem == null) null
        else if (useTabletMatrix) {
            val fullRows = (rem.size / cols) * cols
            val count = if (fullRows > 0) minOf(fullRows, cols * 2) else cols
            rem.take(count)
        } else {
            rem.take(metrics.shelfItemCount)
        }
    }
    val cell = metrics.shelfCardWidth
    val gap = if (metrics.tier == SizeTier.Compact) 10.dp else 14.dp
    Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader2(b.title, if (failed) "加载失败，下拉重试" else null, inset = metrics.pagePadding) { MoreLink { go("board/${b.genre.name}/${b.sub.name}") } }
        if (!failed) {
            if (useTabletMatrix) {
                val cols = metrics.posterCols
                val rows = (list ?: emptyList()).chunked(cols)
                Column(Modifier.padding(horizontal = metrics.pagePadding), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    if (list == null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            repeat(cols) { PosterSkeleton(Modifier.weight(1f)) }
                        }
                    } else {
                        rows.forEach { rowItems ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                rowItems.forEach { s ->
                                    app.iduanju.ui.category.PosterCell(s, null, homeShelfMeta(s), onOpen, Modifier.weight(1f))
                                }
                                repeat(cols - rowItems.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            } else {
                LazyRow(contentPadding = PaddingValues(horizontal = metrics.pagePadding), horizontalArrangement = Arrangement.spacedBy(gap), userScrollEnabled = list != null) {
                    if (list == null) items(if (metrics.tier == SizeTier.Compact) 4 else 9, key = { "skeleton$it" }, contentType = { "skeleton" }) { PosterSkeleton(Modifier.width(cell)) }
                    else items(list, key = { it.seriesId }, contentType = { "poster" }) { s ->
                        app.iduanju.ui.category.PosterCell(s, null, homeShelfMeta(s), onOpen, Modifier.width(cell))
                    }
                }
            }
        }
    }
}

/** "悬疑 · 96 集". */
private fun homeShelfMeta(s: HGSeries): String {
    val parts = listOfNotNull(s.tags.firstOrNull(), if (s.episodeCount > 0) "${s.episodeCount} 集" else null)
    return if (parts.isEmpty()) (if (s.playCount > 0) DS.count(s.playCount) + "热度" else HomeFmt.meta(s)) else parts.joinToString(" · ")
}

/** iOS HomePosterCard: 3:4 poster r10 + 0.6 border, 13 semibold 2 lines, 11 meta. */
@Composable
fun HomePosterCard(s: HGSeries, onOpen: (String) -> Unit) {
    val c = IdjThemeColors
    Column(Modifier.pressClick(0.97f) {
        app.iduanju.data.Catalog.rememberPreview(s.seriesId, s.title, s.cover)
        onOpen(s.seriesId)
    }) {
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(10.dp)).background(c.glass).border(0.6.dp, c.text.copy(0.12f), RoundedCornerShape(10.dp))) {
            AsyncImage(s.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            HomeFmt.progress(s)?.let { HomeBadge(it, Modifier.align(Alignment.BottomEnd).padding(5.dp)) }
        }
        Text(s.title, color = c.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            lineHeight = 17.sp, modifier = Modifier.padding(top = 7.dp))
        val meta = HomeFmt.meta(s)
        if (meta.isNotEmpty()) Text(meta, color = c.secondary, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
fun PosterCard(s: HGSeries, onOpen: (String) -> Unit) = HomePosterCard(s, onOpen)

/** ui-redesign 10 `.cw`: 296 dp cards on phones; equal-width 4-col (Expanded) / 2-col (Medium) cinema grid on tablets. */
@Composable
internal fun ContinueRow(list: List<WatchProgress>, onOpen: (String) -> Unit, onPlay: ((WatchProgress) -> Unit)? = null) {
    val go = app.iduanju.ui.LocalNavigate.current
    val metrics = LocalLayoutMetrics.current
    val play: (WatchProgress) -> Unit = onPlay ?: { p -> go(Routes.resume(p)) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader2("继续观看", inset = metrics.pagePadding) { MoreLink { go("library/history") } }
        if (metrics.continueGridCols > 0) {
            val cols = metrics.continueGridCols
            val visible = list.take(cols)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = metrics.pagePadding),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                visible.forEach { p ->
                    TabletContinueCard(p, onOpen, play, Modifier.weight(1f))
                }
                repeat(cols - visible.size) { Spacer(Modifier.weight(1f)) }
            }
        } else {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(list.take(10), key = { it.seriesId }, contentType = { "watching" }) { p ->
                    ContinueCard(p, onOpen, play, Modifier.animateItem().width(metrics.continueCardWidth))
                }
            }
        }
    }
}

@Composable
private fun TabletContinueCard(p: WatchProgress, onOpen: (String) -> Unit, onPlay: (WatchProgress) -> Unit, modifier: Modifier) {
    val c = IdjThemeColors
    var menu by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF232836), Color(0xFF141720))))
                .pressLongClick(0.98f, label = p.title, longLabel = "删除观看记录", onLongClick = { menu = true }) { onOpen(p.seriesId) },
        ) {
            if (p.cover.isNotBlank()) {
                AsyncImage(p.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
            } else {
                Icon(Icons.Filled.PlayArrow, null, Modifier.align(Alignment.Center).size(30.dp), tint = Color.White.copy(0.22f))
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(0.04f),
                        0.54f to Color.Black.copy(0.20f),
                        1f to Color.Black.copy(0.68f),
                    ),
                ),
            )
            Box(
                Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 11.dp).size(36.dp)
                    .glassSurface(CircleShape, dark = true, shadowRadius = 6.dp, fallbackAlpha = 0.65f, activeOverlay = Color.White.copy(0.10f))
                    .pressClick(label = "继续播放 ${p.title}") { onPlay(p) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp).offset(x = 1.dp), tint = Color.White)
            }
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(0.22f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(p.fraction.coerceIn(0.04f, 1f)).background(c.accent))
            }
            GlassMenu(menu, { menu = false }) {
                GlassMenuItem("删除观看记录", icon = Icons.Outlined.Delete, destructive = true) { menu = false; IdjApp.instance.library.removeProgress(p.seriesId) }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(p.title, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(remember(p.resumeLabel) { DS.spaced(p.resumeLabel) }, color = c.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ContinueCard(p: WatchProgress, onOpen: (String) -> Unit, onPlay: (WatchProgress) -> Unit, modifier: Modifier) {
    val c = IdjThemeColors
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(Modifier.fillMaxWidth().pressLongClick(0.98f, label = p.title, longLabel = "删除观看记录", onLongClick = { menu = true }) { onOpen(p.seriesId) }
            .dsCard(18.dp).padding(start = 10.dp, end = 9.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(52.dp, 69.dp).clip(RoundedCornerShape(8.dp)).background(c.glass)) {
                AsyncImage(p.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Column(Modifier.weight(1f)) {
                Text(p.title, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(remember(p.resumeLabel) { DS.spaced(p.resumeLabel) }, color = c.secondary, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                DSProgressBar(p.fraction, Modifier.padding(top = 9.dp))
            }
            Box(Modifier.size(44.dp).pressClick(label = "继续播放 ${p.title}") { onPlay(p) }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(38.dp).clip(CircleShape).background(c.text), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp).offset(x = 1.dp), tint = c.bg)
                }
            }
        }
        GlassMenu(menu, { menu = false }) {
            GlassMenuItem("删除观看记录", icon = Icons.Outlined.Delete, destructive = true) { menu = false; IdjApp.instance.library.removeProgress(p.seriesId) }
        }
    }
}
