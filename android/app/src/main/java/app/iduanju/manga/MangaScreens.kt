package app.iduanju.manga

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iduanju.IdjApp
import app.iduanju.core.manga.*
import app.iduanju.ui.components.*
import app.iduanju.ui.theme.IdjDarkColors
import app.iduanju.ui.theme.IdjThemeColors
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** [size] = the fixed layout size of small thumbnails: they decode at exactly that size. */
@Composable
private fun Cover(url: String, headers: Map<String, String>, mod: Modifier, corner: Int = 10, size: DpSize? = null) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val req = remember(url, size, density) {
        ImageRequest.Builder(ctx).data(url).apply {
            headers.forEach { (k, v) -> addHeader(k, v) }
            if (size != null) with(density) { size(size.width.roundToPx(), size.height.roundToPx()) }
        }.crossfade(true).build()
    }
    AsyncImage(req, null, mod.clip(RoundedCornerShape(corner.dp)).background(IdjThemeColors.glass), contentScale = ContentScale.Crop)
}

/** Screen-local source strip: labels never consume the next source's width or grow into the catalog.
 * Keep GlassChip elsewhere unchanged. Full labels remain available to accessibility despite ellipsis. */
@Composable
internal fun MangaSourceStrip(
    sources: List<Pair<String, String>>,
    selected: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = IdjThemeColors
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup().testTag("manga.source.strip"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        sources.forEach { (id, label) ->
            val active = id == selected
            val src = remember(id) { MutableInteractionSource() }
            Box(
                Modifier.pressScale(src).widthIn(min = 48.dp, max = 240.dp).heightIn(min = 48.dp)
                    .glassPill(CircleShape, selected = active)
                    .selectable(active, src, null, role = Role.Tab, onClick = { onSelected(id) })
                    .testTag("manga.source." + id)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (active) c.accent else c.secondary, fontSize = 14.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 「漫画」 home page (iOS MangaHomeView, embedded): source chips, search, categories, 继续阅读, 3-column grid. */
@Composable
fun MangaHomePage(onOpen: (String) -> Unit) {
    val c = IdjThemeColors
    val store = IdjApp.instance.manga
    val providers = store.providers
    var source by remember { mutableStateOf(providers[0].source) }
    LaunchedEffect(providers) { if (providers.none { it.source == source }) source = providers[0].source }
    val p = store.provider(source)
    var cat by remember(source) { mutableStateOf(p.categories[0]) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf<String?>(null) }
    val items = remember(source, cat, searching) { mutableStateListOf<MangaItem>() }
    var page by remember(source, cat, searching) { mutableIntStateOf(0) }
    var hasMore by remember(source, cat, searching) { mutableStateOf(true) }
    var loading by remember(source, cat, searching) { mutableStateOf(false) }
    var error by remember(source, cat, searching) { mutableStateOf<String?>(null) }
    suspend fun more() {
        if (loading || !hasMore) return
        loading = true
        try {
            val r = withContext(Dispatchers.IO) { searching?.let { p.search(it, page + 1) } ?: p.list(cat, page + 1) }
            val seen = items.map { it.id }.toHashSet(); items.addAll(r.items.filter { seen.add(it.id) }); page += 1; hasMore = r.hasMore; error = null
        } catch (e: Exception) { error = e.message ?: "加载失败"; hasMore = false } finally { loading = false }
    }
    LaunchedEffect(source, cat, searching) { more() }
    val grid = rememberLazyGridState()
    val nearEnd by remember { derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= grid.layoutInfo.totalItemsCount - 6 } }
    LaunchedEffect(nearEnd, items.size) { if (nearEnd && items.isNotEmpty()) more() }
    val scope = rememberCoroutineScope()
    val recent by remember { derivedStateOf { store.recent.take(10) } }
    val cols = LocalLayoutMetrics.current.posterCols

    LazyVerticalGrid(GridCells.Fixed(cols), state = grid, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
                MangaSourceStrip(providers.map { it.source to it.label }, source, { next ->
                    if (next != source) { source = next; searching = null; query = "" }
                })
                GlassTextField(query, { query = it }, placeholder = "搜索${p.label}", icon = Icons.Rounded.Search, capsule = true, minHeight = 44.dp,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { searching = query.trim().ifEmpty { null } }),
                    trailing = if (searching == null) null else ({ MiniButton("取消") { query = ""; searching = null } }))
                if (searching == null) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(p.categories, contentType = { "category" }) { k -> OptionChip(k.title, k.id == cat.id, minHeight = 36.dp) { cat = k } }
                }
                if (recent.isNotEmpty() && searching == null) {
                    Text("继续阅读", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(recent, key = { it.item.id }, contentType = { "recent" }) { r ->
                            val headers = remember(r.item.source) { store.provider(r.item.source).imageHeaders }
                            Column(Modifier.width(84.dp).pressClick(0.97f) { onOpen(store.put(r.item)) }) {
                                Cover(r.item.cover, headers, Modifier.size(84.dp, 112.dp), 8, RecentSize)
                                Text(r.item.title, color = c.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                                Text(r.chapterTitle, color = c.secondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
        if (items.isEmpty() && error == null && hasMore) items(cols * 3, key = { "skeleton$it" }, contentType = { "skeleton" }) { PosterSkeleton() }
        items(items, key = { it.id }, contentType = { "cell" }) { m ->
            Column(Modifier.testTag("manga.cell." + m.id).pressClick(0.97f) { onOpen(store.put(m)) }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Cover(m.cover, p.imageHeaders, Modifier.fillMaxWidth().aspectRatio(3f / 4))
                Text(m.title, color = c.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(remember(m) { m.tags.take(2).joinToString(" · ").ifEmpty { m.author } }, color = c.secondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        item(key = "footer", span = { GridItemSpan(maxLineSpan) }, contentType = "footer") {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val failure = error
                when {
                    failure != null && items.isEmpty() -> ErrorState(failure) { error = null; hasMore = true; scope.launch { more() } }
                    failure != null -> Text(failure, color = c.accentWarm, fontSize = 12.sp)
                    loading && items.isNotEmpty() -> GlassSpinner(size = 22.dp, color = c.secondary, stroke = 2.dp)
                }
                Text("来源：${p.note}", color = c.secondary, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        }
    }
}

private val RecentSize = DpSize(84.dp, 112.dp)

/** iOS MangaDetailView: cover + meta, 收藏 / 开始阅读 (续读), desc, chapter list with 正序/倒序. */
@Composable
fun MangaDetailScreen(id: String, onBack: () -> Unit, onRead: (String, Int, Int) -> Unit) {
    val c = IdjThemeColors
    val store = IdjApp.instance.manga
    val item = store.cache[id] ?: store.progress[id]?.item ?: store.favorites.firstOrNull { it.id == id } ?: run { onBack(); return }
    val p = remember(item.source) { store.provider(item.source) }
    var detail by remember { mutableStateOf<MangaDetail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var asc by remember { mutableStateOf(true) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(id, attempt) { runCatching { withContext(Dispatchers.IO) { p.detail(item) } }.onSuccess { detail = it; store.cache[id] = it.item; MangaReaderState.detail = it }.onFailure { error = it.message } }
    val shown = detail?.item ?: item
    val prog = store.progress[id]
    val list = rememberLazyListState()
    val pinAt = with(LocalDensity.current) { 60.dp.toPx() }
    val pinned by remember(pinAt) { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > pinAt } }
    val chapters = remember(detail, asc) { detail?.chapters.orEmpty().let { if (asc) it else it.reversed() } }
    val ground = c.bg
    val backdrop = rememberLayerBackdrop {
        drawRect(ground)
        drawContent()
    }
    val maxWidth = LocalLayoutMetrics.current.detailMaxWidth
    Box(Modifier.fillMaxSize().background(c.bg), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop), contentAlignment = Alignment.TopCenter) {
            CoverGlow(shown.cover, p.imageHeaders)
            LazyColumn(
                Modifier.then(if (maxWidth != Dp.Unspecified) Modifier.widthIn(max = maxWidth) else Modifier).fillMaxSize(),
                state = list,
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 58.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 40.dp,
                ),
            ) {
                item(key = "head", contentType = "head") {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Cover(shown.cover, p.imageHeaders, Modifier.size(110.dp, 147.dp), 12, DetailSize)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(shown.title, color = c.text, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
                            if (shown.author.isNotEmpty()) Text(shown.author, color = c.secondary, fontSize = 15.sp)
                            Text((listOf(detail?.status ?: "") + shown.tags).filter { it.isNotEmpty() }.take(5).joinToString(" · "), color = c.secondary, fontSize = 12.sp)
                            Text(p.label, color = c.accent, fontSize = 11.sp)
                        }
                    }
                }
                item(key = "actions", contentType = "actions") {
                    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        val fav = store.isFavorite(shown)
                        detail?.takeIf { it.chapters.isNotEmpty() }?.let { d ->
                            val i = remember(d, prog?.chapterId) { prog?.let { pr -> d.chapters.indexOfFirst { it.chapterId == pr.chapterId } } ?: -1 }
                            GlassButton(if (i >= 0) "续读 " + prog!!.chapterTitle.take(8) else "开始阅读", Modifier.weight(1f), size = ButtonSize.Large, icon = Icons.Filled.PlayArrow) {
                                if (i >= 0) onRead(id, i, prog!!.page) else onRead(id, 0, 0)
                            }
                        }
                        GlassButton(if (fav) "已收藏" else "收藏", tone = ButtonTone.Tinted, size = ButtonSize.Large, icon = if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder) { store.toggleFavorite(shown) }
                    }
                }
                detail?.desc?.takeIf { it.isNotEmpty() }?.let { item(key = "desc") { Text(it, color = c.secondary, fontSize = 13.sp, lineHeight = 20.sp, maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 16.dp)) } }
                error?.let { item(key = "error") { ErrorState(it, Modifier.padding(top = 8.dp)) { error = null; attempt++ } } }
                val d = detail
                if (d == null && error == null) items(6, key = { "skeleton$it" }, contentType = { "skeleton" }) { SkeletonBlock(Modifier.padding(top = if (it == 0) 24.dp else 14.dp).fillMaxWidth().height(20.dp), 6.dp) }
                if (d != null) {
                    item(key = "chapters", contentType = "chapters") {
                        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("章节（${d.chapters.size}）", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.weight(1f))
                            GlassTextButton(if (asc) "正序" else "倒序") { asc = !asc }
                        }
                    }
                    items(chapters, key = { "${it.index}|${it.chapterId}" }, contentType = { "chapter" }) { ch ->
                        val reading = prog?.chapterId == ch.chapterId
                        Column {
                            Box(Modifier.fillMaxWidth().pressClick(0.985f) { onRead(id, ch.index, if (reading) prog!!.page else 0) }.heightIn(min = 48.dp).padding(vertical = 11.dp), contentAlignment = Alignment.CenterStart) {
                                Text(ch.title, color = if (reading) c.accent else c.text, fontSize = 15.sp, fontWeight = if (reading) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Hairline()
                        }
                    }
                }
            }
        }
        MangaNav(shown.title, pinned, backdrop, onBack)
    }
}

private val DetailSize = DpSize(110.dp, 147.dp)

/** Own scope, so the title fade recomposes the bar only. */
@Composable
private fun MangaNav(title: String, pinned: Boolean, backdrop: Backdrop, onBack: () -> Unit) {
    val alpha by animateFloatAsState(if (pinned) 1f else 0f, Motion.fade(), label = "mangaNav")
    Box(Modifier.fillMaxWidth().dsPinned(pinned)) {
        DSNavBar(title, Modifier.statusBarsPadding().padding(bottom = 6.dp), titleAlpha = alpha, leading = {
            GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBackIos, "返回", backdrop = backdrop, size = 36.dp, iconSize = 18.dp, onClick = onBack)
        })
    }
}

/** One blurred, downsampled cover behind the detail header. */
@Composable
private fun CoverGlow(url: String, headers: Map<String, String>) {
    val c = IdjThemeColors
    if (url.isBlank()) { AmbientGlow(cool = true); return }
    val ctx = LocalContext.current
    val blur = android.os.Build.VERSION.SDK_INT >= 31
    val req = remember(url) {
        ImageRequest.Builder(ctx).data(url).apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .size(if (blur) 48 else 16, if (blur) 64 else 22).memoryCacheKey("manga-blur:$blur:$url").build()
    }
    Box(Modifier.fillMaxWidth().height(340.dp).clipToBounds()) {
        AsyncImage(req, null, Modifier.fillMaxSize().then(if (blur) Modifier.scale(1.25f).blur(12.dp) else Modifier), contentScale = ContentScale.Crop, alpha = 0.7f, filterQuality = FilterQuality.High)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to c.bg.copy(0.42f), 0.5f to c.bg.copy(0.70f), 1f to c.bg)))
    }
}

/** The chapter list travels from detail to reader in memory. */
object MangaReaderState { var detail: MangaDetail? = null }

/** iOS MangaReaderView: vertical webtoon scroll (default) or horizontal paging; chrome toggles on tap; progress recorded. */
@Composable
fun MangaReaderScreen(id: String, startIndex: Int, startPage: Int, onBack: () -> Unit) {
    val store = IdjApp.instance.manga
    val d = MangaReaderState.detail?.takeIf { it.item.id == id } ?: run { onBack(); return }
    val p = remember(d.item.source) { store.provider(d.item.source) }
    val ctx = LocalContext.current
    val sp = remember { ctx.getSharedPreferences("idj", android.content.Context.MODE_PRIVATE) }
    var vertical by remember { mutableStateOf(sp.getBoolean("stv.manga.vertical", true)) }
    var index by remember { mutableIntStateOf(startIndex) }
    var pages by remember(index) { mutableStateOf<List<String>>(emptyList()) }
    var error by remember(index) { mutableStateOf<String?>(null) }
    var attempt by remember(index) { mutableIntStateOf(0) }
    var chrome by remember { mutableStateOf(true) }
    var current by remember(index) { mutableIntStateOf(0) }
    val ratios = remember(index) { HashMap<String, Float>() }
    val chapter = d.chapters[index]
    LaunchedEffect(index, attempt) { runCatching { withContext(Dispatchers.IO) { p.pages(d.item, chapter) } }.onSuccess { pages = it }.onFailure { error = it.message } }
    LaunchedEffect(current, pages.size) { if (pages.isNotEmpty()) store.record(d.item, chapter, current, pages.size) }
    val backdrop = rememberLayerBackdrop {
        drawRect(Color.Black)
        drawContent()
    }
    val canGlass = android.os.Build.VERSION.SDK_INT >= 31
    val chromeState = remember { MutableTransitionState(true) }
    chromeState.targetState = chrome
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Recorded for the glass bars only while they are on screen, so plain reading never pays for it.
        Box(Modifier.fillMaxSize().then(if (canGlass && (chrome || chromeState.currentState)) Modifier.layerBackdrop(backdrop) else Modifier)) {
            if (pages.isEmpty()) {
                Column(Modifier.align(Alignment.Center).padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val failure = error
                    if (failure != null) {
                        Text(failure, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
                        GlassButton("重试", tone = ButtonTone.Tinted, colors = IdjDarkColors) { error = null; attempt++ }
                    } else GlassSpinner(color = Color.White)
                }
            } else if (vertical) {
                val mangaMax = LocalLayoutMetrics.current.mangaMaxWidth
                val list = rememberLazyListState(initialFirstVisibleItemIndex = if (index == startIndex) startPage.coerceIn(0, pages.size - 1) else 0)
                LaunchedEffect(list) { snapshotFlow { list.firstVisibleItemIndex }.collect { current = it.coerceAtMost(pages.size - 1) } }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    LazyColumn(
                        Modifier.widthIn(max = mangaMax).fillMaxSize().clickable(interactionSource = null, indication = null) { chrome = !chrome },
                        state = list,
                    ) {
                        itemsIndexed(pages, key = { i, u -> "$i$u" }, contentType = { _, _ -> "page" }) { _, u -> PageImage(u, p.imageHeaders, ratios) }
                        item(key = "end", contentType = "end") { ChapterEnd(d, index) { index = it } }
                    }
                }
            } else {
                val pager = rememberPagerState(if (index == startIndex) startPage.coerceIn(0, pages.size - 1) else 0) { pages.size + 1 }
                LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { current = it.coerceAtMost(pages.size - 1) } }
                HorizontalPager(pager, Modifier.fillMaxSize().clickable(interactionSource = null, indication = null) { chrome = !chrome }) { i ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { if (i < pages.size) PageImage(pages[i], p.imageHeaders, ratios) else ChapterEnd(d, index) { index = it } }
                }
            }
        }
        AnimatedVisibility(chromeState, Modifier.fillMaxSize(), enter = fadeIn(Motion.fade()), exit = fadeOut(Motion.fade())) {
            Box(Modifier.fillMaxSize()) {
                val glass = if (canGlass) backdrop else null
                Row(Modifier.statusBarsPadding().padding(horizontal = 14.dp, vertical = 6.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassIconButton(Icons.Filled.KeyboardArrowDown, "关闭", backdrop = glass, size = 44.dp, iconSize = 24.dp, dark = true, onClick = onBack)
                    Column(Modifier.weight(1f).glassSurface(RoundedCornerShape(22.dp), backdrop = glass, dark = true, shadowRadius = 8.dp).heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.Center) {
                        Text(d.item.title, color = Color.White, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(chapter.title, color = Color.White.copy(0.7f), fontSize = 12.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    GlassIconButton(
                        if (vertical) Icons.Filled.ViewDay else Icons.Filled.ViewCarousel, if (vertical) "切换为左右翻页" else "切换为上下滚动",
                        backdrop = glass, size = 44.dp, iconSize = 22.dp, dark = true,
                    ) { vertical = !vertical; sp.edit().putBoolean("stv.manga.vertical", vertical).apply() }
                }
                Row(
                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 30.dp, vertical = 10.dp).widthIn(max = 480.dp).fillMaxWidth()
                        .glassSurface(RoundedCornerShape(50), backdrop = glass, dark = true, shadowRadius = 12.dp).padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlainIconButton(Icons.Filled.SkipPrevious, "上一章", tint = Color.White, iconSize = 26.dp, enabled = index > 0) { index-- }
                    Text(if (pages.isEmpty()) "" else "${current + 1} / ${pages.size}", color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center, style = Tabular, modifier = Modifier.weight(1f))
                    PlainIconButton(Icons.Filled.SkipNext, "下一章", tint = Color.White, iconSize = 26.dp, enabled = index + 1 < d.chapters.size) { index++ }
                }
            }
        }
    }
}

private val Tabular = TextStyle(fontFeatureSettings = "tnum")

/** Full-width page. [ratios] keeps the aspect of pages already seen, so scrolling back never changes their height. */
@Composable
private fun PageImage(url: String, headers: Map<String, String>, ratios: MutableMap<String, Float>) {
    val ctx = LocalContext.current
    var retry by remember { mutableIntStateOf(0) }
    var phase by remember(url, retry) { mutableStateOf(PagePhase.Loading) }
    var ratio by remember(url) { mutableStateOf(ratios[url]) }
    val req = remember(url, retry) { ImageRequest.Builder(ctx).data(url).apply { headers.forEach { (k, v) -> addHeader(k, v) } }.build() }
    val known = ratio
    Box(Modifier.fillMaxWidth().then(if (known != null) Modifier.aspectRatio(known) else Modifier.heightIn(min = 420.dp)), contentAlignment = Alignment.Center) {
        AsyncImage(
            req, null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth,
            onSuccess = { s ->
                val size = s.painter.intrinsicSize
                if (size.width > 0f && size.height > 0f) { val r = size.width / size.height; ratios[url] = r; ratio = r }
                phase = PagePhase.Shown
            },
            onError = { phase = PagePhase.Failed },
        )
        when (phase) {
            PagePhase.Loading -> GlassSpinner(color = Color.White)
            PagePhase.Failed -> Box(Modifier.matchParentSize().clickable(remember { MutableInteractionSource() }, null, role = Role.Button) { retry++ }, contentAlignment = Alignment.Center) { Text("加载失败，点我重试", color = Color.White.copy(0.6f), fontSize = 14.sp) }
            PagePhase.Shown -> {}
        }
    }
}

private enum class PagePhase { Loading, Shown, Failed }

@Composable
private fun ChapterEnd(d: MangaDetail, index: Int, go: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 240.dp).padding(horizontal = 32.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)) {
        Text("本章完", color = Color.White.copy(0.7f), fontSize = 15.sp)
        if (index + 1 < d.chapters.size) GlassButton("下一章：" + d.chapters[index + 1].title, colors = IdjDarkColors) { go(index + 1) }
        else Text("已是最新一章", color = Color.White.copy(0.5f), fontSize = 12.sp)
    }
}
