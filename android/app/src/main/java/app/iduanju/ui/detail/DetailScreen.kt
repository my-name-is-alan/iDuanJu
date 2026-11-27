package app.iduanju.ui.detail

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iduanju.IdjApp
import app.iduanju.core.hongguo.HGEpisode
import app.iduanju.core.hongguo.HGSeries
import app.iduanju.core.hongguo.HGSeriesDetail
import app.iduanju.data.WatchProgress
import app.iduanju.ui.components.*
import app.iduanju.ui.theme.IdjThemeColors
import coil.compose.AsyncImage
import com.kyant.backdrop.backdrops.layerBackdrop
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Same as iOS/spec: groups of 30 (≤300), 50 (≤600), 60; tablet portrait uses 50 per tab so 10-col grids fill naturally. */
object EpisodeGroups {
    fun size(total: Int, groupLimit: Int = 30) = when {
        total <= groupLimit -> maxOf(total, 1)
        total <= 300 -> groupLimit
        total <= 600 -> maxOf(groupLimit, 50)
        else -> 60
    }
    fun ranges(total: Int, groupLimit: Int = 30): List<IntRange> {
        if (total <= 0) return emptyList()
        val g = size(total, groupLimit)
        if (total <= g) return listOf(0 until total)
        return (0 until total step g).map { it..minOf(it + g - 1, total - 1) }
    }
    fun label(r: IntRange, eps: List<HGEpisode>): String {
        val a = eps.getOrNull(r.first)?.ep ?: (r.first + 1); val b = eps.getOrNull(r.last)?.ep ?: (r.last + 1)
        return if (a == b) "$a" else "$a-$b"
    }
}

/**
 * Cinema Editorial Detail Screen (1:1 parity with redesigned iOS DramaDetailView):
 * - Adaptive 3:4 portrait hero vs 16:9 widescreen hero (proper statusBarsPadding, never flashes wrong source).
 * - Inline high-density stats row (no broken 1/3-width card; zero height when empty).
 * - High-contrast primary Play pill (with embedded resume progress bar) + coral-red circular Heart + Share button.
 * - Compact 2-row episode preview + full modal episode drawer Sheet (reveals 系列 & 演员 on first screen).
 * - Real Haze backdrop blur on pinned header.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(seriesId: String, onBack: () -> Unit, onPlay: (String, Int) -> Unit, onOpenSeries: (String) -> Unit = {}) {
    val c = IdjThemeColors
    val lib = IdjApp.instance.library
    var d by remember { mutableStateOf<HGSeriesDetail?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(seriesId, reload) {
        err = null
        try {
            if (app.iduanju.BuildConfig.DEBUG && seriesId in listOf("ui-color-bars", "ui-detail-sparse", "ui-detail-full")) {
                d = HGSeriesDetail(
                    seriesId = seriesId, title = "本地界面测试 · 非真实片源",
                    desc = "此页面使用明确标注的本地样例数据，用于检查详情页的排版、折叠、选集和滚动吸顶，不代表真实片源服务。".repeat(4),
                    author = "界面验证", totalEps = 48,
                    episodes = (1..48).map { HGEpisode(it, "fixture-$it", 60, "测试第 $it 集") },
                    tags = listOf("本地样例", "排版验证"), favoriteCount = 403000,
                    rating = if (seriesId == "ui-detail-sparse") 0.0 else 8.6,
                    ratingCount = if (seriesId == "ui-detail-sparse") "" else "1.2万",
                    hotText = if (seriesId == "ui-detail-sparse") "" else "512万",
                    source = "本地样例",
                    relatedSeries = (1..3).map { HGSeries(seriesId = if (it == 1) seriesId else "sample-$it", title = "本地系列样例 $it", episodeCount = 48) },
                    cast = (1..5).map { app.iduanju.core.hongguo.HGCelebrity(id = "sample-$it", name = "样例演员 $it", role = "饰 样例") },
                )
            } else {
                val base = app.iduanju.data.Catalog.load(seriesId)
                d = base
                if (app.iduanju.data.Catalog.isHongguo(seriesId) && base.cast.isEmpty() && base.tags.isEmpty()) {
                    val enriched = withContext(Dispatchers.IO) { app.iduanju.data.Catalog.enrich(seriesId, base) }
                    if (enriched != null && d?.seriesId == enriched.seriesId) d = enriched
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            err = e.message ?: "加载失败"
        }
    }
    val metrics = LocalLayoutMetrics.current
    val isExpanded = metrics.tier == SizeTier.Expanded
    val isTabletLandscape = isExpanded && metrics.isLandscape
    val isTabletPortrait = !metrics.isLandscape && metrics.tier != SizeTier.Compact
    val epGroupSize = if (isTabletPortrait) 50 else 30

    val resume = lib.progress(seriesId)
    var lineIndex by remember(seriesId) { mutableIntStateOf(0) }
    LaunchedEffect(d) {
        d?.lines?.let { ls ->
            lineIndex = lib.preferredLine(seriesId)?.let { id -> ls.indexOfFirst { it.id == id && it.episodes.isNotEmpty() } }?.takeIf { it >= 0 } ?: 0
        }
    }
    val eps = remember(d, lineIndex) { d?.let { dd -> dd.lines.getOrNull(lineIndex)?.episodes?.takeIf { it.isNotEmpty() } ?: dd.episodes }.orEmpty() }
    val picker = remember(seriesId) { EpisodePickerState() }
    LaunchedEffect(eps, epGroupSize) {
        picker.group = EpisodeGroups.ranges(eps.size, epGroupSize).indexOfFirst { resume != null && resume.episodeIndex in it }.coerceAtLeast(0)
    }
    val selectLine: (Int) -> Unit = { i ->
        lineIndex = i
        d?.lines?.getOrNull(i)?.let { lib.setPreferredLine(seriesId, it.id) }
    }
    LaunchedEffect(eps, resume?.episodeNumber) {
        if (eps.isEmpty() || !seriesId.all { it.isDigit() } || !IdjApp.instance.settings.prefetchEnabled) return@LaunchedEffect
        kotlinx.coroutines.delay(1_200)
        val ep = eps.firstOrNull { it.ep == resume?.episodeNumber } ?: eps.first()
        app.iduanju.ui.player.EpisodeCache.warm(ep, lib.preferredQuality(seriesId))
    }
    val scroll = rememberScrollState()
    LaunchedEffect(d) {
        if (d != null && app.iduanju.BuildConfig.DEBUG && app.iduanju.ui.UiEvidence.scroll) {
            kotlinx.coroutines.delay(1200)
            scroll.scrollTo((scroll.maxValue * 0.75).toInt())
        }
    }
    val density = LocalDensity.current
    val pinThresholdPx = with(density) { 210.dp.toPx() }
    val pinned by remember(pinThresholdPx) { derivedStateOf { scroll.value > pinThresholdPx } }
    var actionsBottom by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    val actionsThresholdPx = with(density) { 96.dp.toPx() }
    val actionsOut by remember(actionsThresholdPx) { derivedStateOf { actionsBottom < actionsThresholdPx } }
    var episodeDrawerOpen by remember { mutableStateOf(false) }

    val live = d?.isLive == true
    val startEp = (if (live && resume != null) eps.getOrNull(app.iduanju.core.tvbox.LiveKey(resume.episodeNumber, resume.episodeGroup, resume.episodeTitle).find(eps))?.ep else resume?.episodeNumber)
        ?: eps.firstOrNull()?.ep ?: 1
    val play: (String, Int) -> Unit = { s, ep ->
        if (live) eps.firstOrNull { it.ep == ep }?.let { app.iduanju.data.Catalog.rememberChannel(s, app.iduanju.core.tvbox.LiveKey(ep, it.page.orEmpty(), it.title)) }
        onPlay(s, ep)
    }
    val playTitle = when {
        live -> "观看直播"
        resume == null -> "开始播放"
        eps.size <= 1 -> "继续播放 " + clockText(resume.seconds)
        else -> "继续第 ${resume.episodeNumber} 集"
    }
    val resumeFraction = if (resume != null && !live) resume.fraction else 0f

    val useTwoPane = isTabletLandscape && d != null
    val usePortraitEditorial = !useTwoPane && isTabletPortrait && d != null
    val isTabletLayout = useTwoPane || usePortraitEditorial
    val bgColor = c.bg
    val pageBackdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop {
        drawRect(bgColor)
        drawContent()
    }
    val haze = remember { HazeState() }

    Box(Modifier.fillMaxSize().background(c.bg), contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier.fillMaxSize()
                .then(if (android.os.Build.VERSION.SDK_INT >= 31) Modifier.hazeSource(haze) else Modifier)
                .layerBackdrop(pageBackdrop),
            contentAlignment = Alignment.TopCenter,
        ) {
            when {
                useTwoPane -> {
                    val dd = d!!
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        // Ambient Glass Stage: soft optical poster halo washing into the page ground.
                        Box(Modifier.fillMaxSize().clipToBounds()) {
                            BlurredCover(dd.cover, Modifier.fillMaxSize().scale(1.18f), alpha = if (c.dark) 0.28f else 0.12f)
                            Box(
                                Modifier.fillMaxSize().background(
                                    Brush.verticalGradient(
                                        0f to c.bg.copy(alpha = 0.28f),
                                        0.52f to c.bg.copy(alpha = 0.84f),
                                        1f to c.bg,
                                    ),
                                ),
                            )
                        }
                        Row(
                            Modifier
                                .widthIn(max = metrics.detailMaxWidth)
                                .fillMaxSize()
                                .statusBarsPadding()
                                .navigationBarsPadding()
                                .padding(top = 56.dp, start = metrics.pagePadding, end = metrics.pagePadding, bottom = 24.dp),
                            horizontalArrangement = Arrangement.spacedBy(32.dp),
                        ) {
                            Column(
                                Modifier
                                    .weight(0.38f)
                                    .widthIn(min = 320.dp, max = 420.dp)
                                    .fillMaxHeight()
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                val total = episodeCountText(dd)
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(3f / 4f)
                                        .heightIn(max = 430.dp)
                                        .shadow(20.dp, RoundedCornerShape(20.dp), ambientColor = Color.Black.copy(0.45f), spotColor = Color.Black.copy(0.45f)),
                                ) {
                                    PosterArt(dd.cover, Modifier.fillMaxSize(), corner = 20.dp)
                                }
                                if (dd.source.isNotBlank()) DSStatus(dd.source, StatusTone.Accent)
                                Text(dd.title, color = c.text, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 32.sp)
                                val meta = listOfNotNull(
                                    if (dd.episodeLabel.isEmpty()) total.takeIf { it.isNotEmpty() } else DS.spaced(dd.episodeLabel),
                                    dd.author.ifEmpty { null },
                                ).joinToString(" · ")
                                if (meta.isNotEmpty()) Text(meta, color = c.secondary, fontSize = 13.5.sp)
                                InlineStatsRow(dd)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    PrimaryPlayButton(playTitle, resumeFraction, eps.isNotEmpty(), Modifier.weight(1f), height = 48.dp) { play(seriesId, startEp) }
                                    Heart(seriesId, dd, 48.dp)
                                    ShareButton(dd.title, 48.dp)
                                }
                            }
                            Column(
                                Modifier
                                    .weight(0.62f)
                                    .fillMaxHeight()
                                    .verticalScroll(scroll)
                                    .padding(bottom = 48.dp),
                            ) {
                                DetailBody(
                                    d = dd,
                                    sid = seriesId,
                                    resume = resume,
                                    eps = eps,
                                    lineIndex = lineIndex,
                                    onLine = selectLine,
                                    picker = picker,
                                    onPlay = play,
                                    onOpenSeries = onOpenSeries,
                                    onOpenAllEpisodes = { episodeDrawerOpen = true },
                                    playTitle = playTitle,
                                    resumeFraction = resumeFraction,
                                    startEp = startEp,
                                    showActions = false,
                                    onActions = { actionsBottom = it },
                                )
                            }
                        }
                    }
                }
                usePortraitEditorial -> {
                    val dd = d!!
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(scroll)
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            .padding(top = 56.dp, bottom = 48.dp),
                    ) {
                        TabletPortraitHero(
                            d = dd,
                            sid = seriesId,
                            playTitle = playTitle,
                            resumeFraction = resumeFraction,
                            startEp = startEp,
                            onPlay = play,
                        )
                        if (dd.desc.isNotEmpty()) {
                            Intro(dd.desc, horizontalPadding = 24.dp, topPadding = 20.dp, collapsedLines = 4)
                        }
                        EpisodeSection(
                            d = dd,
                            sid = seriesId,
                            resume = resume,
                            eps = eps,
                            lineIndex = lineIndex,
                            onLine = selectLine,
                            picker = picker,
                            onPlay = play,
                            onOpenAllEpisodes = { episodeDrawerOpen = true },
                            inset = 24.dp,
                            epCols = 10,
                            groupSize = epGroupSize,
                        )
                        if (dd.relatedSeries.size > 1) {
                            RelatedShelf(dd.relatedSeries, dd.seriesId, onOpenSeries, inset = 24.dp, cardWidth = 128.dp)
                        }
                        if (dd.cast.isNotEmpty()) {
                            CastShelf(dd.cast, inset = 24.dp)
                        }
                    }
                }
                else -> {
                    Column(
                        Modifier
                            .then(if (metrics.detailMaxWidth != Dp.Unspecified) Modifier.widthIn(max = metrics.detailMaxWidth) else Modifier)
                            .fillMaxSize()
                            .verticalScroll(scroll)
                            .navigationBarsPadding()
                            .padding(bottom = 110.dp),
                    ) {
                        DetailHero(
                            d = d,
                            sid = seriesId,
                            coverHint = remember(seriesId) { app.iduanju.data.Catalog.peekCover(seriesId) },
                            titleHint = remember(seriesId) { app.iduanju.data.Catalog.peekTitle(seriesId) },
                        )
                        val dd = d
                        when {
                            dd != null -> DetailBody(
                                d = dd,
                                sid = seriesId,
                                resume = resume,
                                eps = eps,
                                lineIndex = lineIndex,
                                onLine = selectLine,
                                picker = picker,
                                onPlay = play,
                                onOpenSeries = onOpenSeries,
                                onOpenAllEpisodes = { episodeDrawerOpen = true },
                                playTitle = playTitle,
                                resumeFraction = resumeFraction,
                                startEp = startEp,
                            ) { actionsBottom = it }
                            err != null -> ErrorState(err!!, Modifier.padding(top = 12.dp)) { reload++ }
                            else -> Column(Modifier.padding(horizontal = 20.dp).padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                SkeletonBlock(Modifier.fillMaxWidth().height(50.dp), 25.dp)
                                repeat(3) { i -> SkeletonBlock(Modifier.fillMaxWidth(if (i == 2) 0.6f else 1f).height(15.dp), 4.dp) }
                            }
                        }
                    }
                }
            }
        }

        // Pinned glass nav header with real Haze blur
        Box(Modifier.fillMaxWidth().dsPinned(pinned || useTwoPane, haze)) {
            DSNavBar(
                d?.title ?: "",
                Modifier.statusBarsPadding().padding(bottom = 6.dp),
                titleAlpha = animateFloatAsState(if (pinned || useTwoPane) 1f else 0f, label = "t").value,
                leading = {
                    val onHero = !(pinned || isTabletLayout)
                    GlassIconButton(
                        Icons.AutoMirrored.Rounded.ArrowBackIos, "返回",
                        backdrop = pageBackdrop, size = 36.dp, iconSize = 18.dp,
                        dark = if (onHero) true else c.dark, fallbackAlpha = if (onHero) 0.34f else 0.72f,
                        onClick = onBack,
                    )
                },
            )
        }

        // Smooth floating bottom play bar once primary CTA scrolls out of view (phone only)
        AnimatedVisibility(
            !isTabletLayout && eps.isNotEmpty() && actionsOut,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 6.dp),
            enter = slideInVertically(DS.panel()) { it } + fadeIn(),
            exit = slideOutVertically(DS.panel()) { it } + fadeOut(),
        ) {
            Row(
                Modifier.fillMaxWidth().glassSurface(RoundedCornerShape(32.dp), backdrop = pageBackdrop, shadowRadius = 20.dp).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PrimaryPlayButton(playTitle, resumeFraction, true, Modifier.weight(1f), height = 46.dp) { play(seriesId, startEp) }
                d?.let { Heart(seriesId, it, 46.dp) }
            }
        }
    }

    // Full episode drawer sheet (when >12 episodes)
    val dd = d
    if (episodeDrawerOpen && dd != null && eps.isNotEmpty()) {
        val drawerCols = when (metrics.tier) { SizeTier.Compact -> 5; SizeTier.Medium -> 8; SizeTier.Expanded -> 10 }
        val textual = remember(eps) { !seriesId.all { it.isDigit() } && EpisodeNames.textual(eps) }
        val ranges = remember(eps.size, epGroupSize) { EpisodeGroups.ranges(eps.size, epGroupSize) }
        val g = picker.group.coerceIn(0, (ranges.size - 1).coerceAtLeast(0))
        val cells = remember(ranges, g, picker.reversed) { ranges.getOrNull(g)?.toList().orEmpty().let { if (picker.reversed) it.reversed() else it } }
        GlassBottomSheet(
            onDismissRequest = { episodeDrawerOpen = false },
            title = "全部选集（${eps.size}）",
            doneText = "完成",
            scroll = true,
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (dd.lines.size > 1) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(dd.lines, key = { i, l -> "$i:${l.id}" }) { i, l ->
                            ToneChip(l.name.ifBlank { "线路 ${i + 1}" }, i == lineIndex, count = l.episodes.size, enabled = l.episodes.isNotEmpty()) { selectLine(i) }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (ranges.size > 1) {
                        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(ranges.size, key = { it }) { k ->
                                val i = if (picker.reversed) ranges.size - 1 - k else k
                                ToneChip(EpisodeGroups.label(ranges[i], eps), i == g, neutral = true, small = true) { picker.group = i }
                            }
                        }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.width(8.dp))
                    MoreLink(if (picker.reversed) "倒序" else "正序", Icons.Rounded.SwapVert) { picker.reversed = !picker.reversed }
                }
                EpisodeRows(
                    episodes = eps,
                    range = cells.filter { it in eps.indices },
                    columns = if (textual) textColumns(drawerCols) else drawerCols,
                    current = resume?.episodeIndex,
                    textual = textual,
                ) { idx ->
                    episodeDrawerOpen = false
                    play(seriesId, eps[idx].ep)
                }
            }
        }
    }
}

/** High-contrast primary CTA pill: crisp deep ink on light / luminous white on dark, with embedded resume progress line. */
@Composable
private fun PrimaryPlayButton(
    title: String,
    resumeFraction: Float,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = 50.dp,
    onClick: () -> Unit,
) {
    val c = IdjThemeColors
    val bg = if (c.dark) Color.White else Color(0xFF14171F)
    val fg = if (c.dark) Color(0xFF111317) else Color.White
    Box(
        modifier
            .height(height)
            .clip(CircleShape)
            .background(bg)
            .pressClick(0.97f, enabled = enabled, label = title, onClick = onClick),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            Icon(Icons.Filled.PlayArrow, null, Modifier.size(20.dp), tint = fg)
            Text(title, color = fg, fontSize = 15.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (resumeFraction > 0.02f) {
            val barColor = Color(0xFF286DEF)
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.5.dp).background(barColor.copy(alpha = 0.20f)),
            ) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(resumeFraction.coerceIn(0.06f, 1f)).background(barColor))
            }
        }
    }
}

/** Cinema Editorial Hero: adapts between 3:4 portrait short dramas and 16:9 widescreen single-episode videos. */
@Composable
private fun DetailHero(d: HGSeriesDetail?, sid: String, coverHint: String = "", titleHint: String = "") {
    val c = IdjThemeColors
    val coverUrl = d?.cover?.takeIf { it.isNotEmpty() } ?: coverHint
    val displayTitle = d?.title?.takeIf { it.isNotEmpty() } ?: titleHint
    val isWidescreen = d != null && !d.isLive && d.episodes.size <= 1 && !app.iduanju.data.Catalog.isHongguo(sid) && d.episodeLabel == "长视频"

    if (isWidescreen && coverUrl.isNotEmpty()) {
        // Apple TV+ style 16:9 widescreen hero (never crops horizontal long-video stills into a 3:4 box)
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f)) {
                AsyncImage(coverUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(0.42f),
                            0.38f to Color.Transparent,
                            0.80f to c.bg.copy(0.76f),
                            1f to c.bg,
                        ),
                    ),
                )
            }
            Column(Modifier.padding(horizontal = 20.dp).offset(y = (-14).dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (d?.source?.isNotBlank() == true) DSStatus(d.source, StatusTone.Accent)
                Text(
                    displayTitle, color = c.text, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 28.sp,
                    modifier = Modifier.copyOnLongPress(displayTitle),
                )
                val meta = listOfNotNull(d?.author?.ifEmpty { null }).joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, color = c.secondary, fontSize = 13.sp, maxLines = 1)
                if (d != null) InlineStatsRow(d)
            }
        }
        return
    }

    Box(Modifier.fillMaxWidth()) {
        if (coverUrl.isNotEmpty()) {
            Box(Modifier.matchParentSize().clipToBounds()) { BlurredCover(coverUrl, Modifier.fillMaxSize().scale(1.14f)) }
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(0.32f),
                        0.32f to Color.Black.copy(0.10f),
                        0.74f to c.bg.copy(0.80f),
                        1f to c.bg,
                    ),
                ),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = 56.dp, start = 20.dp, end = 20.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier
                    .size(114.dp, 152.dp)
                    .shadow(16.dp, RoundedCornerShape(14.dp), ambientColor = Color.Black.copy(0.42f), spotColor = Color.Black.copy(0.42f))
                    .border(0.7.dp, Color.White.copy(if (c.dark) 0.16f else 0.45f), RoundedCornerShape(14.dp)),
            ) {
                PosterArt(coverUrl, Modifier.fillMaxSize(), corner = 14.dp)
            }
            Column(Modifier.weight(1f).padding(bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val srcLabel = d?.source?.takeIf { it.isNotBlank() }
                if (srcLabel != null) DSStatus(srcLabel, StatusTone.Accent)
                Text(
                    displayTitle,
                    color = c.text,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 28.sp,
                    modifier = Modifier.copyOnLongPress(displayTitle),
                )
                val epsText = d?.let(::episodeCountText)?.takeIf { it.isNotEmpty() }
                val meta = listOfNotNull(
                    d?.let { if (it.episodeLabel.isEmpty()) epsText else DS.spaced(it.episodeLabel) },
                    d?.author?.ifEmpty { null },
                ).joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, color = c.secondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (d != null) InlineStatsRow(d)
            }
        }
    }
}

/** Frameless high-density inline metrics row (replaces orphan 1/3-width Stats card; occupies 0 dp when empty). */
@Composable
private fun InlineStatsRow(d: HGSeriesDetail, modifier: Modifier = Modifier) {
    val c = IdjThemeColors
    val hasAny = d.rating > 0 || d.hotText.isNotEmpty() || d.favoriteCount > 0
    if (!hasAny) return
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (d.rating > 0) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Icon(Icons.Filled.Star, null, Modifier.size(13.dp), tint = c.accentWarm)
                Text(String.format("%.1f", d.rating), color = c.accentWarm, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (d.hotText.isNotEmpty()) {
            if (d.rating > 0) Text("·", color = c.tertiary, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Icon(Icons.Filled.LocalFireDepartment, null, Modifier.size(13.dp), tint = c.accentWarm)
                Text(DS.spaced(d.hotText), color = c.secondary, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
            }
        }
        if (d.favoriteCount > 0) {
            if (d.rating > 0 || d.hotText.isNotEmpty()) Text("·", color = c.tertiary, fontSize = 12.sp)
            Text("${DS.count(d.favoriteCount)} 收藏", color = c.secondary, fontSize = 12.5.sp)
        }
    }
}

/** Coral-red Heart button (`c.favorite` #FF4D6A when active, matching iOS STVTheme.favorite). */
@Composable
private fun Heart(sid: String, d: HGSeriesDetail, size: Dp) {
    val c = IdjThemeColors
    val lib = IdjApp.instance.library
    val haptics = rememberHaptics()
    val fav = lib.isFavorite(sid)
    val s by animateFloatAsState(if (fav) 1f else 0.9f, DS.press(), label = "heart")
    Box(
        Modifier.size(size)
            .clip(CircleShape)
            .background(if (fav) c.favorite.copy(alpha = if (c.dark) 0.18f else 0.12f) else c.glass)
            .border(1.dp, if (fav) c.favorite.copy(alpha = 0.42f) else c.hairline, CircleShape)
            .pressClick(label = if (fav) "取消收藏" else "收藏") {
                haptics.tick()
                lib.toggleFavorite(sid, d.title, d.cover)
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            null,
            Modifier.size(22.dp).scale(s / 0.95f),
            tint = if (fav) c.favorite else c.text,
        )
    }
}

@Composable
private fun ShareButton(title: String, size: Dp) {
    val c = IdjThemeColors
    val ctx = LocalContext.current
    Box(
        Modifier.size(size)
            .clip(CircleShape)
            .background(c.glass)
            .border(1.dp, c.hairline, CircleShape)
            .pressClick(label = "分享") {
                if (title.isNotBlank()) {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, title)
                    }
                    runCatching { ctx.startActivity(Intent.createChooser(intent, "分享剧名")) }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Share, null, Modifier.size(19.dp), tint = c.text)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TabletPortraitHero(
    d: HGSeriesDetail,
    sid: String,
    playTitle: String,
    resumeFraction: Float,
    startEp: Int,
    onPlay: (String, Int) -> Unit,
) {
    val c = IdjThemeColors
    val go = app.iduanju.ui.LocalNavigate.current
    val epsLabel = episodeCountText(d)

    Box(
        Modifier
            .fillMaxWidth(),
    ) {
        Box(Modifier.matchParentSize().clipToBounds()) {
            BlurredCover(d.cover, Modifier.fillMaxSize().scale(1.12f), alpha = if (c.dark) 0.28f else 0.14f)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to c.bg.copy(alpha = 0.16f),
                            0.55f to c.bg.copy(alpha = 0.78f),
                            1f to c.bg,
                        ),
                    ),
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(176.dp)
                    .aspectRatio(3f / 4f)
                    .shadow(16.dp, RoundedCornerShape(16.dp), ambientColor = Color.Black.copy(0.42f), spotColor = Color.Black.copy(0.42f)),
            ) {
                PosterArt(d.cover, Modifier.fillMaxSize(), corner = 16.dp)
            }

            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    if (d.source.isNotBlank()) DSStatus(d.source, StatusTone.Accent)
                    val epBadge = if (d.episodeLabel.isEmpty()) epsLabel else DS.spaced(d.episodeLabel)
                    if (epBadge.isNotEmpty()) HeroStatChip(epBadge)
                    if (d.author.isNotEmpty()) HeroStatChip(d.author)
                }

                Text(
                    d.title,
                    color = c.text,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold,
                    lineHeight = 32.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                InlineStatsRow(d)

                if (d.tags.isNotEmpty()) {
                    Text(d.tags.distinct().take(6).joinToString(" · "), color = c.secondary, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                if (d.cast.isEmpty() && d.actors.isNotEmpty()) {
                    val actors = d.actors.filter { it.isNotBlank() }
                    if (actors.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            itemVerticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("主演", color = c.secondary, fontSize = 12.sp)
                            actors.take(6).forEach { actorName ->
                                Box(
                                    Modifier.height(26.dp).clip(CircleShape).background(c.glass).border(0.6.dp, c.hairline, CircleShape)
                                        .clickable { go("actorSearch/${android.net.Uri.encode(actorName)}") }.padding(horizontal = 10.dp),
                                    contentAlignment = Alignment.Center,
                                ) { Text(actorName, color = c.text, fontSize = 12.sp, maxLines = 1) }
                            }
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PrimaryPlayButton(playTitle, resumeFraction, d.episodes.isNotEmpty(), Modifier.weight(1f), height = 50.dp) { onPlay(sid, startEp) }
                    Heart(sid, d, 50.dp)
                    ShareButton(d.title, 50.dp)
                }
            }
        }
    }
}

@Composable
private fun HeroStatChip(text: String) {
    val c = IdjThemeColors
    Box(
        Modifier
            .height(24.dp)
            .clip(CircleShape)
            .background(c.glass)
            .border(0.6.dp, c.hairline, CircleShape)
            .padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = c.secondary, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun DetailBody(
    d: HGSeriesDetail,
    sid: String,
    resume: WatchProgress?,
    eps: List<HGEpisode>,
    lineIndex: Int,
    onLine: (Int) -> Unit,
    picker: EpisodePickerState,
    onPlay: (String, Int) -> Unit,
    onOpenSeries: (String) -> Unit,
    onOpenAllEpisodes: () -> Unit,
    playTitle: String,
    resumeFraction: Float,
    startEp: Int,
    showActions: Boolean = true,
    onActions: (Float) -> Unit,
) {
    val c = IdjThemeColors
    val go = app.iduanju.ui.LocalNavigate.current

    if (showActions) {
        Row(
            Modifier.padding(horizontal = 20.dp).padding(top = 14.dp).onGloballyPositioned { onActions(it.boundsInRoot().bottom) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PrimaryPlayButton(playTitle, resumeFraction, eps.isNotEmpty(), Modifier.weight(1f), height = 48.dp) { onPlay(sid, startEp) }
            Heart(sid, d, 48.dp)
            ShareButton(d.title, 48.dp)
        }
    }

    // Unified info block: restrained tag line + actor chips + cleanly folding synopsis (no Pill Soup!)
    val uniqueTags = remember(d.tags) { d.tags.distinct().filter { it.isNotBlank() } }
    if (uniqueTags.isNotEmpty()) {
        LazyRow(Modifier.padding(top = 14.dp), contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(uniqueTags, key = { it }) { t ->
                Box(Modifier.height(24.dp).clip(RoundedCornerShape(7.dp)).background(c.glass).padding(horizontal = 9.dp), contentAlignment = Alignment.Center) {
                    Text(t, color = c.secondary, fontSize = 11.5.sp)
                }
            }
        }
    }
    if (d.cast.isEmpty() && d.actors.isNotEmpty()) {
        val actors = d.actors.filter { it.isNotBlank() }.distinct()
        if (actors.isNotEmpty()) {
            LazyRow(
                Modifier.padding(top = 10.dp),
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item { Text("主演", color = c.secondary, fontSize = 12.sp) }
                items(actors, key = { it }) { actorName ->
                    Box(
                        Modifier.height(26.dp).clip(CircleShape).background(c.glass).border(0.6.dp, c.hairline, CircleShape)
                            .clickable { go("actorSearch/${android.net.Uri.encode(actorName)}") }.padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(actorName, color = c.text, fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
        }
    }
    if (d.desc.isNotEmpty()) Intro(d.desc)

    val epCols = when (LocalLayoutMetrics.current.tier) { SizeTier.Compact -> 6; SizeTier.Medium -> 8; SizeTier.Expanded -> 10 }
    EpisodeSection(d, sid, resume, eps, lineIndex, onLine, picker, onPlay, onOpenAllEpisodes, inset = 20.dp, epCols = epCols, groupSize = 30)
    if (d.relatedSeries.size > 1) RelatedShelf(d.relatedSeries, d.seriesId, onOpenSeries)
    if (d.cast.isNotEmpty()) CastShelf(d.cast, inset = 20.dp)
}

@Stable
private class EpisodePickerState {
    var group by mutableIntStateOf(0)
    var reversed by mutableStateOf(false)
    var channelGroup by mutableStateOf<String?>(null)
}

/**
 * Two-stage Episode Section (matches redesigned iOS DramaDetailView):
 * - Single-episode long video with 1 line hides the redundant `[ 1 ]` grid.
 * - Multi-episode titles (>12 eps) display a crisp 2-row preview anchored near current resume episode + `全部 N 集 · 选集 ›`
 *   to open the full modal drawer, keeping 系列 & 演员 visible on the first screen.
 */
@Composable
private fun EpisodeSection(
    d: HGSeriesDetail,
    sid: String,
    resume: WatchProgress?,
    eps: List<HGEpisode>,
    lineIndex: Int,
    onLine: (Int) -> Unit,
    picker: EpisodePickerState,
    onPlay: (String, Int) -> Unit,
    onOpenAllEpisodes: () -> Unit,
    inset: Dp = 20.dp,
    epCols: Int = 6,
    groupSize: Int = 30,
) {
    if (d.isLive) { ChannelSection(eps, sid, resume, picker, onPlay, inset, epCols); return }
    if (d.lines.size > 1) {
        SectionHeader2("线路", "共 ${d.lines.size} 条", inset = inset, modifier = Modifier.padding(top = 20.dp))
        LazyRow(Modifier.padding(top = 10.dp), contentPadding = PaddingValues(horizontal = inset), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(d.lines, key = { i, l -> "$i:${l.id}" }) { i, l ->
                ToneChip(l.name.ifBlank { "线路 ${i + 1}" }, i == lineIndex, count = l.episodes.size, enabled = l.episodes.isNotEmpty()) { onLine(i) }
            }
        }
    }
    if (eps.isEmpty()) { EmptyState("暂无剧集", note = if (d.lines.size > 1) "可以换一条线路试试" else null); return }
    // Single-episode long video with a single line needs no `[ 1 ]` episode grid.
    if (eps.size <= 1 && d.lines.size <= 1) return

    val textual = remember(eps) { !sid.all { it.isDigit() } && EpisodeNames.textual(eps) }
    val displayCols = if (textual) textColumns(epCols) else epCols
    val previewCap = displayCols * 2
    val usePreviewDrawer = eps.size > previewCap
    val totalCount = if (textual) eps.size else maxOf(if (d.lines.size > 1) 0 else d.totalEps, eps.size)

    SectionHeader2(
        title = "选集",
        note = "共 $totalCount 集",
        inset = inset,
        modifier = Modifier.padding(top = 20.dp),
    ) {
        if (usePreviewDrawer) {
            MoreLink("全部选集", onClick = onOpenAllEpisodes)
        } else if (eps.size > 1) {
            MoreLink(if (picker.reversed) "倒序" else "正序", Icons.Rounded.SwapVert) { picker.reversed = !picker.reversed }
        }
    }

    val shownIndices = remember(eps.size, resume?.episodeIndex, previewCap, usePreviewDrawer, picker.reversed) {
        if (usePreviewDrawer) {
            val cur = (resume?.episodeIndex ?: 0).coerceIn(0, eps.lastIndex)
            val start = (cur / previewCap) * previewCap
            val end = minOf(start + previewCap, eps.size)
            (start until end).toList()
        } else {
            val all = eps.indices.toList()
            if (picker.reversed) all.reversed() else all
        }
    }

    androidx.compose.animation.AnimatedContent(shownIndices, label = "eps", transitionSpec = { fadeIn(DS.select()) togetherWith fadeOut(DS.select()) }) { list ->
        EpisodeRows(
            episodes = eps,
            range = list.filter { it in eps.indices },
            columns = displayCols,
            current = resume?.episodeIndex,
            textual = textual,
            modifier = Modifier.padding(horizontal = inset).padding(top = 10.dp),
        ) { i -> onPlay(sid, eps[i].ep) }
    }
}

/** Live lists opened from 收藏 / 历史: groups and channels, straight into the player. */
@Composable
private fun ChannelSection(channels: List<HGEpisode>, sid: String, resume: WatchProgress?, picker: EpisodePickerState, onPlay: (String, Int) -> Unit, inset: Dp, epCols: Int) {
    val groups = remember(channels) { channels.mapNotNull { it.page?.takeIf { p -> p.isNotBlank() } }.distinct() }
    val group = picker.channelGroup?.takeIf { it in groups } ?: resume?.let { channels.getOrNull(it.episodeIndex)?.page } ?: groups.firstOrNull()
    SectionHeader2("频道", "共 ${channels.size} 个", inset = inset, modifier = Modifier.padding(top = 20.dp))
    if (groups.size > 1) LazyRow(Modifier.padding(top = 10.dp), contentPadding = PaddingValues(horizontal = inset), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(groups, key = { it }) { name -> ToneChip(name, name == group, neutral = true, small = true) { picker.channelGroup = name } }
    }
    val shown = remember(channels, group) { channels.indices.filter { group == null || channels[it].page == group }.take(90) }
    EpisodeRows(channels, shown, textColumns(epCols), resume?.episodeIndex, textual = true, modifier = Modifier.padding(horizontal = inset).padding(top = 10.dp)) { i -> onPlay(sid, channels[i].ep) }
}

private fun episodeCountText(d: HGSeriesDetail): String = when {
    d.isLive -> "${d.episodes.size} 个频道"
    d.lines.size > 1 && d.episodes.size <= 1 -> "${d.lines.size} 条线路"
    d.episodes.size <= 1 && d.totalEps <= 1 -> ""
    else -> "全 ${maxOf(if (d.lines.size > 1) 0 else d.totalEps, d.episodes.size)} 集"
}

private fun clockText(seconds: Double): String {
    val s = seconds.toInt()
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.copyOnLongPress(text: String): Modifier {
    if (text.isEmpty()) return this
    val ctx = LocalContext.current
    val haptics = rememberHaptics()
    return combinedClickable(remember { MutableInteractionSource() }, null, onLongClickLabel = "复制剧名", onLongClick = {
        ctx.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("title", text))
        haptics.longPress()
        GlassToast.show("已复制剧名")
    }, onClick = {})
}

@Composable
private fun CastShelf(cast: List<app.iduanju.core.hongguo.HGCelebrity>, inset: Dp = 20.dp) {
    val c = IdjThemeColors
    val go = app.iduanju.ui.LocalNavigate.current
    SectionHeader2("演员", inset = inset, modifier = Modifier.padding(top = 24.dp))
    LazyRow(Modifier.padding(top = 12.dp), contentPadding = PaddingValues(horizontal = inset), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        itemsIndexed(cast, key = { i, p -> "$i:${p.id}" }) { _, p ->
            Column(
                Modifier
                    .width(64.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = p.id.isNotBlank() || p.name.isNotBlank()) {
                        if (p.id.isNotBlank()) {
                            go(
                                "actor/${android.net.Uri.encode(p.id)}" +
                                    "?name=${android.net.Uri.encode(p.name)}" +
                                    "&avatar=${android.net.Uri.encode(p.avatar)}" +
                                    "&role=${android.net.Uri.encode(p.role)}" +
                                    "&intro=${android.net.Uri.encode(p.intro)}",
                            )
                        } else if (p.name.isNotBlank()) {
                            go("actorSearch/${android.net.Uri.encode(p.name)}")
                        }
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(56.dp).clip(CircleShape).background(Brush.linearGradient(listOf(c.accent.copy(0.5f), c.accentWarm.copy(0.5f)))), contentAlignment = Alignment.Center) {
                    Text(p.name.take(1), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    if (p.avatar.isNotEmpty()) AsyncImage(p.avatar, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Text(p.name, color = c.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                if (p.role.isNotEmpty()) Text(p.role, color = c.secondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Clean natural-ellipsis synopsis with explicit 展开 ▾ / 收起 ▴ toggle (no half-clipped CJK overlap). */
@Composable
private fun Intro(
    text: String,
    horizontalPadding: Dp = 20.dp,
    topPadding: Dp = 12.dp,
    collapsedLines: Int = 3,
) {
    val c = IdjThemeColors
    var open by remember { mutableStateOf(false) }
    var clamped by remember { mutableStateOf(false) }
    Column(
        Modifier.padding(horizontal = horizontalPadding).padding(top = topPadding).fillMaxWidth().animateContentSize(DS.panel())
            .clickable(remember { MutableInteractionSource() }, null, enabled = clamped || open) { open = !open },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text,
            color = c.secondary,
            fontSize = 14.sp,
            lineHeight = 22.sp,
            maxLines = if (open) Int.MAX_VALUE else collapsedLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!open) clamped = it.hasVisualOverflow },
        )
        if (clamped || open) {
            Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (open) "收起" else "展开", color = c.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Icon(
                    if (open) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                    null,
                    Modifier.size(16.dp),
                    tint = c.accent,
                )
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    val c = IdjThemeColors
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
private fun RelatedShelf(
    items: List<HGSeries>,
    currentId: String,
    onOpen: (String) -> Unit,
    inset: Dp = 20.dp,
    cardWidth: Dp = 104.dp,
) {
    val c = IdjThemeColors
    val go = app.iduanju.ui.LocalNavigate.current
    val cardHeight = cardWidth * (4f / 3f)
    SectionHeader2("系列", "共 ${items.size} 季", inset = inset, modifier = Modifier.padding(top = 24.dp)) {
        MoreLink("搜全系列", Icons.Filled.Search) { go("actorSearch/" + android.net.Uri.encode(items.firstOrNull { it.seriesId == currentId }?.title ?: "")) }
    }
    LazyRow(Modifier.padding(top = 12.dp), contentPadding = PaddingValues(start = inset, end = inset, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(items.distinctBy { it.seriesId }, key = { it.seriesId }) { s ->
            val on = s.seriesId == currentId
            Column(Modifier.width(cardWidth).pressClick(0.97f, enabled = !on, label = s.title) { onOpen(s.seriesId) }) {
                Box(Modifier.size(cardWidth, cardHeight).then(if (on) Modifier.border(2.dp, c.accent, RoundedCornerShape(16.dp)).padding(4.dp) else Modifier)) {
                    PosterArt(s.cover, Modifier.fillMaxSize(), corner = 12.dp)
                    if (on) Box(Modifier.padding(6.dp).height(18.dp).clip(RoundedCornerShape(5.dp)).background(c.accent).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                        Text("当前", color = c.onAccent, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Text(s.title, color = c.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                if (s.episodeCount > 0) Text("${s.episodeCount} 集", color = c.secondary, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}
