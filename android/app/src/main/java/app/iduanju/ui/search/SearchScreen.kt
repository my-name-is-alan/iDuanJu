package app.iduanju.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NorthWest
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.iduanju.IdjApp
import app.iduanju.core.hongguo.HGRankGenre
import app.iduanju.core.hongguo.HGRankSub
import app.iduanju.core.hongguo.HGSeries
import app.iduanju.ui.components.*
import app.iduanju.ui.home.HomeFmt
import app.iduanju.ui.home.HomePosterCard
import app.iduanju.ui.theme.IdjThemeColors
import coil.compose.AsyncImage
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private object HotCache { var hot: List<HGSeries> = emptyList() }

/** Kept with the page: opening a drama and coming back shows the same query, source, results and scroll position. */
class SearchState : ViewModel() {
    val query = mutableStateOf("")
    val submitted = mutableStateOf("")
    val source = mutableStateOf("hongguo")
    val items = mutableStateListOf<HGSeries>()
    val cursor = mutableStateOf<String?>(null)
    val hasMore = mutableStateOf(false)
    val loading = mutableStateOf(false)
    val err = mutableStateOf<String?>(null)
    val verify = mutableStateOf<app.iduanju.core.util.VerificationRequiredException?>(null)
    val gen = mutableIntStateOf(0)
    val pageNo = mutableIntStateOf(1)
    val grid = LazyGridState()
    var started = false
}

/**
 * Unified standalone Search screen:
 * - Single top Liquid Glass search bar (`[←] [🔍 input ⊗] [搜索]`) — no duplicate bottom dock search bar.
 * - Multi-source selector strip (`红果` · `黄果` [18+] · `采集站` · `插件`) always visible under the bar when multiple
 *   sources are enabled, dynamically updating on 18+ / plugin / CMS changes.
 * - Clean 3-state body: Landing (历史搜索, 搜索发现, 搜索实时排行 as on iOS), Live Suggestions, Results.
 */
@Composable
fun SearchScreen(
    onOpenDetail: (String) -> Unit,
    initialQuery: String = "",
    onBack: (() -> Unit)? = null,
) {
    val c = IdjThemeColors
    val metrics = LocalLayoutMetrics.current
    val ia = IdjApp.instance
    val lib = ia.library
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    val vm: SearchState = viewModel()
    val scope = vm.viewModelScope
    var query by vm.query
    var submitted by vm.submitted
    var hot by remember { mutableStateOf(HotCache.hot) }
    var hotErr by remember { mutableStateOf<String?>(null) }
    var seed by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf(false) }

    val items = vm.items
    var cursor by vm.cursor
    var hasMore by vm.hasMore
    var loading by vm.loading
    var err by vm.err
    var verify by vm.verify
    var searchGen by vm.gen

    // Reactive multi-source list: [红果, 黄果 (when adultUnlocked)] + enabled CMS sites + enabled video/shortdrama plugins
    val sources by remember { derivedStateOf {
        val adult = ia.cms.adultUnlocked
        listOf("hongguo" to "红果") +
            (if (adult) listOf("huangguo" to "黄果") else emptyList()) +
            ia.cms.visibleSites.map { "cms:" + it.key to it.name } +
            ia.plugins.visible(adult).filter { it.type == "video" || it.type == "shortdrama" }.map { "plugin:" + it.id to it.name }
    } }

    var source by vm.source
    var pageNo by vm.pageNo
    val currentSourceLabel = sources.firstOrNull { it.first == source }?.second ?: "红果"

    suspend fun loadHot() {
        hotErr = null
        try {
            hot = withContext(Dispatchers.IO) { ia.hongguo.rank(HGRankSub.hotSearch, HGRankGenre.all).items.take(23) }
            HotCache.hot = hot
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            hotErr = e.message ?: "加载失败"
        }
    }
    val uiScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (initialQuery.isBlank() && !vm.started) {
            vm.started = true
            delay(80)
            runCatching { focusRequester.requestFocus(); keyboard?.show() }
        }
        if (hot.isEmpty()) loadHot()
    }

    fun loadMore(reset: Boolean) {
        if (loading && !reset) return
        val q = submitted
        if (q.isEmpty()) return
        val myGen = if (reset) ++searchGen else searchGen
        loading = true
        err = null
        verify = null
        scope.launch {
            try {
                if (reset) pageNo = 1
                val reqPage = pageNo
                val src = source
                val p = withContext(Dispatchers.IO) {
                    when {
                        src == "hongguo" -> ia.hongguo.search(q, cursor = if (reset) null else cursor)
                        src == "huangguo" -> app.iduanju.core.hongguo.HGPage(ia.huangguo.search(q), false)
                        src.startsWith("cms:") -> ia.cms.site(src.removePrefix("cms:"))?.let {
                            app.iduanju.core.cms.MacCMS.search(it, q, reqPage)
                        } ?: app.iduanju.core.hongguo.HGPage(emptyList(), false)
                        else -> try {
                            ia.plugins.video(src.removePrefix("plugin:")).search(q, reqPage)
                        } catch (e: app.iduanju.core.plugin.PluginException) {
                            throw Exception(e.friendly)
                        }
                    }
                }
                if (myGen != searchGen) return@launch
                pageNo = reqPage + 1
                if (reset) items.clear()
                val seen = items.mapTo(HashSet()) { it.seriesId }
                items.addAll(p.items.filter { seen.add(it.seriesId) })
                cursor = p.nextCursor
                hasMore = p.hasMore && p.items.isNotEmpty()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: app.iduanju.core.util.VerificationRequiredException) {
                if (myGen == searchGen) { verify = e; err = "需要验证" }
            } catch (e: Exception) {
                if (myGen == searchGen) err = e.message ?: "搜索失败"
            } finally {
                if (myGen == searchGen) loading = false
            }
        }
    }

    LaunchedEffect(sources) {
        if (sources.none { it.first == source }) {
            source = "hongguo"
            cursor = null
            items.clear()
            if (submitted.isNotEmpty()) loadMore(true)
        }
    }

    fun submit(q0: String = query) {
        val q = q0.trim()
        if (q.isEmpty()) return
        focusManager.clearFocus()
        keyboard?.hide()
        query = q
        lib.addSearch(q)
        submitted = q
        cursor = null
        items.clear()
        vm.grid.requestScrollToItem(0)
        loadMore(true)
    }

    fun clearQuery() {
        searchGen++
        loading = false
        query = ""
        submitted = ""
        cursor = null
        err = null
        verify = null
        items.clear()
    }

    LaunchedEffect(initialQuery) {
        if (initialQuery.isNotBlank() && !vm.started) { vm.started = true; submit(initialQuery) }
    }

    BackHandler(enabled = submitted.isNotEmpty()) {
        clearQuery()
    }

    val bgColor = c.bg
    val searchBackdrop = rememberLayerBackdrop {
        drawRect(bgColor)
        drawContent()
    }
    val density = LocalDensity.current
    var topBarH by remember { mutableStateOf(58.dp) }
    // The keyboard is handled by imePadding (layout phase); reading its inset here would recompose every frame.
    val bottomPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp
    val cols = metrics.posterCols
    val full: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }
    val openFromList: (String) -> Unit = { sid ->
        focusManager.clearFocus()
        keyboard?.hide()
        onOpenDetail(sid)
    }

    Box(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        // Content layer recorded into backdrop for floating Liquid Glass header
        Box(Modifier.fillMaxSize().layerBackdrop(searchBackdrop).imePadding()) {
            when {
                submitted.isNotEmpty() -> {
                    val grid = vm.grid
                    LaunchedEffect(grid, items.size) {
                        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
                            if (hasMore && !loading && last >= items.size - 4) loadMore(false)
                        }
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(cols),
                        modifier = Modifier.fillMaxSize(),
                        state = grid,
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topBarH + 8.dp, bottom = bottomPad),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(items, key = { it.seriesId + "|" + it.title }, contentType = { "poster" }) { s -> HomePosterCard(s, openFromList) }
                        val v = verify
                        if (items.isEmpty()) when {
                            loading -> items(cols * 3, key = { "skeleton$it" }, contentType = { "skeleton" }) { PosterSkeleton() }
                            v != null -> item("state", full, "state") { app.iduanju.huangguo.VerifyRequiredState(v) { loadMore(true) } }
                            err != null -> item("state", full, "state") { ErrorState("加载失败", note = err) { loadMore(true) } }
                            else -> item("state", full, "state") {
                                EmptyState("无搜索结果", icon = Icons.Filled.Search,
                                    note = "没有找到与“$submitted”相关的内容" + if (sources.size > 1) "，可点击上方片源标签切换其他站源检索" else "")
                            }
                        } else item("footer", full, "footer") {
                            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                                when {
                                    loading -> GlassSpinner(size = 24.dp)
                                    v != null -> app.iduanju.huangguo.VerifyRequiredState(v) { loadMore(false) }
                                    err != null -> GlassTextButton("$err · 点击重试", fontSize = 13.sp) { loadMore(false) }
                                    !hasMore -> Text("没有更多了", color = c.tertiary, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                else -> {
                    val typing = query.isNotBlank()
                    val historySnapshot = lib.searchHistory.toList()
                    Crossfade(targetState = typing, animationSpec = Motion.fade(), label = "searchLandingOrSuggest") { isTyping ->
                        if (isTyping) {
                            SearchSuggestionsList(
                                query = query.trim(),
                                sourceLabel = currentSourceLabel,
                                history = historySnapshot,
                                hot = hot,
                                topPadding = topBarH,
                                bottomPadding = bottomPad,
                                onSubmit = { submit(it) },
                                onFill = { query = it },
                                onOpenDetail = openFromList,
                            )
                        } else {
                            SearchLandingContent(
                                history = historySnapshot,
                                editing = editing,
                                onEditToggle = { editing = it },
                                onClearHistory = { lib.clearSearch(); editing = false },
                                onRemoveHistory = {
                                    lib.removeSearch(it)
                                    if (lib.searchHistory.isEmpty()) editing = false
                                },
                                hot = hot,
                                hotErr = hotErr,
                                seed = seed,
                                onRefreshSeed = { seed++ },
                                onRetryHot = { uiScope.launch { loadHot() } },
                                topPadding = topBarH,
                                bottomPadding = bottomPad,
                                onPick = { submit(it) },
                                onOpenDetail = openFromList,
                            )
                        }
                    }
                }
            }
        }

        // Subtle gradient scrim beneath floating header
        Box(
            Modifier
                .fillMaxWidth()
                .height(topBarH + 14.dp)
                .background(remember(c.bg) {
                    Brush.verticalGradient(
                        0f to c.bg.copy(alpha = 0.96f),
                        0.72f to c.bg.copy(alpha = 0.80f),
                        1f to c.bg.copy(alpha = 0f),
                    )
                }),
        )

        // Unified Top Header: Search Input Capsule + Multi-Source Selector Strip
        Column(
            Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {}
                .onSizeChanged { topBarH = with(density) { it.height.toDp() } },
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .padding(top = 6.dp, bottom = if (sources.size > 1) 6.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (onBack != null) {
                    GlassIconButton(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        "返回",
                        backdrop = searchBackdrop,
                        size = 44.dp,
                        iconSize = 20.dp,
                        onClick = {
                            focusManager.clearFocus()
                            keyboard?.hide()
                            onBack()
                        },
                    )
                }

                Row(
                    Modifier
                        .weight(1f)
                        .glassSurface(RoundedCornerShape(50), backdrop = searchBackdrop, shadowRadius = 8.dp)
                        .heightIn(min = 44.dp)
                        .padding(start = 14.dp, end = if (query.isEmpty()) 14.dp else 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Search, null, tint = c.secondary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                if (sources.size > 1) "搜索 $currentSourceLabel（共 ${sources.size} 个片源）" else "搜索短剧 / 演员",
                                color = c.secondary,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = {
                                query = it
                                if (it.isEmpty() && submitted.isNotEmpty()) clearQuery()
                            },
                            singleLine = true,
                            textStyle = TextStyle(color = c.text, fontSize = 15.sp),
                            cursorBrush = SolidColor(c.accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { submit() }),
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).semantics { contentDescription = "搜索" },
                        )
                    }
                    if (query.isNotEmpty()) PlainIconButton(Icons.Filled.Cancel, "清除", tint = c.secondary, iconSize = 18.dp) { clearQuery() }
                }

                val hasDraftChange = query.trim().isNotEmpty() && query.trim() != submitted
                if (submitted.isNotEmpty() && !hasDraftChange) GlassButton("重置", Modifier.heightIn(min = 40.dp), ButtonTone.Glass, ButtonSize.Small) { clearQuery() }
                else GlassButton("搜索", Modifier.heightIn(min = 40.dp), size = ButtonSize.Small, enabled = query.trim().isNotEmpty()) { submit() }
            }

            if (sources.size > 1) {
                val strip = rememberLazyListState()
                val active = sources.indexOfFirst { it.first == source }
                LaunchedEffect(active) { if (active >= 0) strip.animateScrollToItem((active - 1).coerceAtLeast(0)) }
                LazyRow(state = strip, contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(sources, key = { it.first }, contentType = { "chip" }) { (id, name) ->
                        OptionChip(name, id == source, minHeight = 34.dp) {
                            if (id != source) {
                                source = id
                                cursor = null
                                items.clear()
                                if (submitted.isNotEmpty()) loadMore(true)
                                else if (query.isNotBlank()) submit(query)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = IdjThemeColors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).semantics { heading() })
        trailing()
    }
}

@Composable
private fun SearchLandingContent(
    history: List<String>,
    editing: Boolean,
    onEditToggle: (Boolean) -> Unit,
    onClearHistory: () -> Unit,
    onRemoveHistory: (String) -> Unit,
    hot: List<HGSeries>,
    hotErr: String?,
    seed: Int,
    onRefreshSeed: () -> Unit,
    onRetryHot: () -> Unit,
    topPadding: Dp,
    bottomPadding: Dp,
    onPick: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
) {
    val c = IdjThemeColors
    val metrics = LocalLayoutMetrics.current
    val grid = rememberLazyGridState()
    val discover = remember(hot, seed) {
        if (hot.size > 3) hot.drop(3).map { it.title }.shuffled(java.util.Random(seed + 1L)).take(8) else emptyList()
    }
    val topCount = when (metrics.tier) {
        SizeTier.Compact -> 3
        SizeTier.Medium -> 4
        SizeTier.Expanded -> 5
    }
    val gap = if (metrics.tier == SizeTier.Compact) 8.dp else 12.dp
    val rest = remember(hot, topCount) { hot.drop(topCount) }
    val full: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(if (metrics.tier == SizeTier.Compact) 1 else metrics.wideCardCols),
        modifier = Modifier.fillMaxSize(),
        state = grid,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topPadding, bottom = bottomPadding),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (history.isNotEmpty()) {
            item("history", full, "chips") {
                Column {
                    SectionTitle("历史搜索") {
                        if (editing) {
                            GlassTextButton("全部删除", onClick = onClearHistory)
                            GlassTextButton("完成", color = c.text) { onEditToggle(false) }
                        } else PlainIconButton(Icons.Outlined.Delete, "编辑历史", tint = c.secondary, iconSize = 20.dp) { onEditToggle(true) }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(history, key = { it }, contentType = { "chip" }) { term ->
                            SearchChip(term, badge = null, removable = editing, Modifier.animateItem()) {
                                if (editing) onRemoveHistory(term) else onPick(term)
                            }
                        }
                    }
                }
            }
        }

        if (discover.isNotEmpty()) {
            item("discover", full, "chips") {
                Column {
                    SectionTitle("搜索发现") { PlainIconButton(Icons.Rounded.Sync, "换一批", tint = c.secondary, iconSize = 20.dp, onClick = onRefreshSeed) }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(discover, contentType = { _, _ -> "chip" }) { i, title ->
                            SearchChip(title, badge = when (i) { 0 -> "热"; 1 -> "荐"; else -> null }, removable = false) { onPick(title) }
                        }
                    }
                }
            }
        }

        item("hotHeader", full, "hot") {
            Column {
                SectionTitle("搜索实时排行")
                when {
                    hot.isEmpty() && hotErr != null -> ErrorState("加载失败", note = hotErr, onRetry = onRetryHot)
                    hot.isEmpty() -> Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        repeat(topCount) { SkeletonBlock(Modifier.weight(1f).aspectRatio(0.6f), 12.dp) }
                    }
                    else -> Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        hot.take(topCount).forEachIndexed { i, s ->
                            Box(Modifier.weight(1f)) {
                                TopRankCard(s, i + 1, onOpenDetail)
                            }
                        }
                    }
                }
            }
        }

        if (hot.isEmpty() && hotErr == null) items(6, key = { "skeleton$it" }, contentType = { "skeleton" }) { RowSkeleton(thumb = 46.dp) }
        items(rest.size, key = { i -> "rank_${rest[i].seriesId}_$i" }, contentType = { "rank" }) { i ->
            CompactRankRow(rest[i], i + topCount + 1, onOpenDetail)
        }
    }
}

@Composable
private fun SearchSuggestionsList(
    query: String,
    sourceLabel: String,
    history: List<String>,
    hot: List<HGSeries>,
    topPadding: Dp,
    bottomPadding: Dp,
    onSubmit: (String) -> Unit,
    onFill: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
) {
    val c = IdjThemeColors
    val histHits = remember(query, history) {
        history.filter { it.contains(query, ignoreCase = true) && !it.equals(query, ignoreCase = true) }.take(5)
    }
    val hotHits = remember(query, hot) {
        hot.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.tags.any { t -> t.contains(query, ignoreCase = true) }
        }.take(10)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = topPadding + 6.dp, bottom = bottomPadding),
    ) {
        item(key = "directSearch") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .pressClick(0.98f) { onSubmit(query) }
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IconWell(Icons.Filled.Search, 36.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        "搜索「$query」",
                        color = c.accent,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("当前片源：$sourceLabel", color = c.secondary, fontSize = 12.sp)
                }
            }
            Hairline(Modifier.padding(horizontal = 16.dp))
        }

        items(histHits, key = { "h_$it" }, contentType = { "history" }) { h ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .pressClick(0.98f) { onSubmit(h) }
                    .heightIn(min = 48.dp)
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Filled.History, null, tint = c.secondary, modifier = Modifier.size(18.dp))
                Text(h, color = c.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                PlainIconButton(Icons.Filled.NorthWest, "填入搜索词", tint = c.secondary, iconSize = 16.dp) { onFill(h) }
            }
        }

        if (hotHits.isNotEmpty()) {
            item(key = "hotSuggestTitle") {
                Text(
                    "热榜匹配",
                    color = c.secondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            items(hotHits, key = { "s_${it.seriesId}" }, contentType = { "hot" }) { s ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .pressClick(0.98f) {
                            app.iduanju.data.Catalog.rememberPreview(s.seriesId, s.title, s.cover)
                            onOpenDetail(s.seriesId)
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(42.dp, 56.dp).clip(RoundedCornerShape(6.dp)).background(c.glass)) {
                        AsyncImage(s.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(s.title, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(HomeFmt.meta(s), color = c.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** iOS ChipFlow cell: quiet capsule, optional ·热 / ·荐 accent, ✕ while editing. */
@Composable
private fun SearchChip(text: String, badge: String?, removable: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = IdjThemeColors
    Row(
        modifier
            .pressClick(0.97f, label = if (removable) "删除 $text" else null, onClick = onClick)
            .clip(CircleShape)
            .background(c.glass)
            .heightIn(min = 36.dp)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = c.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
        if (badge != null) Text("·$badge", color = c.accentWarm, fontSize = 15.sp, modifier = Modifier.padding(start = 2.dp))
        if (removable) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Filled.Cancel, null, tint = c.secondary, modifier = Modifier.size(14.dp))
        }
    }
}

private fun rankGradient(r: Int): List<Color> = when (r) {
    1 -> listOf(Color(0.95f, 0.25f, 0.3f), Color(0.75f, 0.1f, 0.2f))
    2 -> listOf(Color(0.25f, 0.35f, 0.95f), Color(0.15f, 0.2f, 0.7f))
    else -> listOf(Color(0.98f, 0.45f, 0.25f), Color(0.85f, 0.25f, 0.15f))
}

private fun rankColor(r: Int): Color = when (r) {
    1 -> Color(1f, 0.27f, 0.2f)
    2 -> Color(1f, 0.55f, 0.15f)
    3 -> Color(1f, 0.78f, 0.2f)
    else -> Color(0.8f, 0.15f, 0.25f)
}

@Composable
private fun TopRankCard(s: HGSeries, rank: Int, onOpen: (String) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(0.6f)
            .pressClick(0.97f) {
                app.iduanju.data.Catalog.rememberPreview(s.seriesId, s.title, s.cover)
                onOpen(s.seriesId)
            }
            .clip(RoundedCornerShape(12.dp))
            .background(IdjThemeColors.glass),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AsyncImage(s.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        val scrim = remember(rank) { rankGradient(rank).let { g -> Brush.verticalGradient(0.35f to Color.Transparent, 0.62f to g[0], 1f to g[1]) } }
        Box(Modifier.fillMaxSize().background(scrim))
        Column(
            Modifier.padding(horizontal = 6.dp).padding(bottom = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("TOP$rank", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
            Text(s.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(HomeFmt.metaLine(s), color = Color.White.copy(alpha = 0.82f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CompactRankRow(s: HGSeries, rank: Int, onOpen: (String) -> Unit) {
    val c = IdjThemeColors
    Row(
        Modifier
            .fillMaxWidth()
            .pressClick(0.98f) {
                app.iduanju.data.Catalog.rememberPreview(s.seriesId, s.title, s.cover)
                onOpen(s.seriesId)
            }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(46.dp, 62.dp).clip(RoundedCornerShape(6.dp)).background(c.glass)) {
            AsyncImage(s.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Text(
                "$rank",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(RoundedCornerShape(3.dp))
                    .background(rankColor(rank))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(s.title, color = c.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (s.playCount > 0) Text(HomeFmt.count(s.playCount), color = Color(0.95f, 0.3f, 0.3f), fontSize = 12.sp, maxLines = 1)
                Text(if (s.episodeCount > 0) "${s.episodeCount}集" else "短剧", color = c.secondary, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}
