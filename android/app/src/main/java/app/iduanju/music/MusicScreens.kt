package app.iduanju.music

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import app.iduanju.ui.components.DoodleCrayonWave
import app.iduanju.ui.components.DoodleSparkle
import app.iduanju.ui.components.ymDockSurface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.iduanju.IdjApp
import app.iduanju.ui.LocalNavigate
import app.iduanju.ui.components.LocalLayoutMetrics
import app.iduanju.ui.home.rootBottomInset
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class MusicSubTab(val label: String) {
    DISCOVER("发现"),
    CHARTS("排行"),
    ARCHIVE("典藏"),
    LIBRARY("曲库"),
}

/**
 * Standalone Yimeng-styled Music root tab (`发现 · 排行 · 典藏 · 曲库`), mounted directly in Android `RootScreen`'s
 * bottom dock & tablet rail with 100% direct no-proxy upstream connections.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicHomePage(
    isRootTab: Boolean = true,
    onBack: () -> Unit = {},
) {
    val store = IdjApp.instance.musicStore
    val player = IdjApp.instance.musicPlayer
    val go = LocalNavigate.current
    val metrics = LocalLayoutMetrics.current
    val scope = rememberCoroutineScope()
    val bottomInset = if (isRootTab) rootBottomInset() else 24.dp

    var subTab by remember { mutableStateOf(MusicSubTab.DISCOVER) }
    var chartPlatform by remember { mutableStateOf<MusicSourceKind?>(MusicSourceKind.NETEASE) }
    var charts by remember { mutableStateOf<List<MusicChart>>(emptyList()) }
    var dailyTracks by remember { mutableStateOf<List<MusicTrack>>(emptyList()) }
    var dailyIsPersonalized by remember { mutableStateOf(false) }
    var recommendedPlaylists by remember { mutableStateOf<List<MusicPlaylist>>(emptyList()) }
    var recPlaylistsPersonalized by remember { mutableStateOf(false) }
    var fmTracks by remember { mutableStateOf<List<MusicTrack>>(emptyList()) }
    var fmIsPersonalized by remember { mutableStateOf(false) }
    var fmSourceLabel by remember { mutableStateOf("私人 FM") }
    var loadingFM by remember { mutableStateOf(false) }
    var loadingCharts by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<MusicTrack>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var showYueqian by remember { mutableStateOf(false) }
    var yueqianHook by remember { mutableStateOf("谁都只得那双手 靠拥抱亦难任你拥有") }
    var archiveIndex by remember { mutableIntStateOf(0) }
    var librarySegment by remember { mutableIntStateOf(0) }
    var showNewPlaylist by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var toast by remember { mutableStateOf<String?>(null) }

    fun showToast(msg: String) {
        toast = msg
        scope.launch {
            delay(2200)
            if (toast == msg) toast = null
        }
    }

    val enabledChartSources = store.enabledOnlineSources.filter {
        it == MusicSourceKind.NETEASE || it == MusicSourceKind.QQ || it == MusicSourceKind.KUGOU || it == MusicSourceKind.QISHUI
    }
    val enabledSourcesKey = enabledChartSources.joinToString(",") { it.id }

    suspend fun reloadCharts(force: Boolean) {
        if (enabledChartSources.isEmpty()) {
            charts = emptyList()
            dailyTracks = emptyList()
            dailyIsPersonalized = false
            recommendedPlaylists = emptyList()
            recPlaylistsPersonalized = false
            fmTracks = emptyList()
            fmIsPersonalized = false
            return
        }
        loadingCharts = true
        val cookies = store.cookieMap()
        val allowSet = enabledChartSources.toSet()
        val res = MusicProviderHub.fetchCharts(enabledChartSources, cookies, force)
        charts = res.filter { it.source in allowSet }
        val dailyRes = MusicProviderHub.fetchDailyRecommendations(enabledChartSources, cookies, force)
        dailyTracks = dailyRes.tracks.filter { it.source in allowSet }
        dailyIsPersonalized = dailyRes.isPersonalized && dailyTracks.isNotEmpty()
        val recRes = MusicProviderHub.fetchRecommendedPlaylists(enabledChartSources, cookies, force)
        recommendedPlaylists = recRes.playlists.filter { it.source == null || it.source in allowSet }
        recPlaylistsPersonalized = recRes.isPersonalized && recommendedPlaylists.isNotEmpty()
        val fmRes = MusicProviderHub.fetchPersonalFM(enabledChartSources, cookies)
        fmTracks = fmRes.tracks.filter { it.source in allowSet }
        fmIsPersonalized = fmRes.isPersonalized && fmTracks.isNotEmpty()
        fmSourceLabel = fmRes.sourceLabel
        loadingCharts = false
        if (store.hasAnyLoggedInSource && (force || store.cloudPlaylists.isEmpty())) {
            scope.launch { store.syncCloudPlaylists() }
        }
        val firstTrack = dailyTracks.firstOrNull() ?: charts.firstOrNull()?.tracks?.firstOrNull()
        if (firstTrack != null) {
            val lrc = MusicProviderHub.fetchLyrics(firstTrack, store.settings.lrclibEnabled, cookies)
            MusicLyricsParser.pickYueqianHook(lrc)?.let { yueqianHook = it }
        }
    }

    LaunchedEffect(Unit) {
        if (chartPlatform != null && chartPlatform !in enabledChartSources) {
            chartPlatform = enabledChartSources.firstOrNull()
        } else if (chartPlatform == null && enabledChartSources.size == 1) {
            chartPlatform = enabledChartSources.first()
        }
        if (charts.isEmpty() || recommendedPlaylists.isEmpty()) reloadCharts(false)
        if (store.hasAnyLoggedInSource && store.cloudPlaylists.isEmpty()) {
            store.syncCloudPlaylists()
        }
    }

    LaunchedEffect(enabledSourcesKey) {
        if (chartPlatform != null && chartPlatform !in enabledChartSources) {
            chartPlatform = enabledChartSources.firstOrNull()
        } else if (chartPlatform == null && enabledChartSources.size == 1) {
            chartPlatform = enabledChartSources.first()
        }
        val allowSet = enabledChartSources.toSet()
        charts = charts.filter { it.source in allowSet }
        recommendedPlaylists = recommendedPlaylists.filter { it.source == null || it.source in allowSet }
        dailyTracks = dailyTracks.filter { it.source in allowSet }
        fmTracks = fmTracks.filter { it.source in allowSet }
        reloadCharts(true)
    }

    LaunchedEffect(store.loginFingerprint) {
        if (charts.isNotEmpty()) {
            reloadCharts(true)
        }
    }

    val visibleCharts = remember(charts, enabledSourcesKey) {
        val allow = enabledChartSources.toSet()
        charts.filter { it.source in allow }
    }
    val visibleRecPlaylists = remember(recommendedPlaylists, enabledSourcesKey) {
        val allow = enabledChartSources.toSet()
        recommendedPlaylists.filter { it.source == null || it.source in allow }
    }
    val allChartTracks = remember(visibleCharts) { visibleCharts.flatMap { it.tracks }.distinctBy { it.id } }
    val heroTracks = remember(dailyTracks, allChartTracks, store.recents, enabledSourcesKey) {
        val allow = enabledChartSources.toSet()
        (dailyTracks.filter { it.source in allow } + allChartTracks + store.recents)
            .distinctBy { it.id }
            .filter { it.coverURL.isNotBlank() }
            .take(5)
    }
    val searchPlaceholder = remember(enabledSourcesKey, store.nasConfigs.size) {
        val names = enabledChartSources.map { it.label }.toMutableList()
        if (store.nasConfigs.isNotEmpty()) names += "NAS"
        when {
            names.isEmpty() -> "请先在设置中启用音乐源"
            names.size == 1 -> "搜歌（${names[0]}）"
            else -> "聚合搜歌（${names.joinToString(" / ")}）"
        }
    }
    val yueqianTrack = player.currentTrack ?: dailyTracks.firstOrNull() ?: allChartTracks.firstOrNull()

    Box(Modifier.fillMaxSize().background(YMColors.base)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            if (!isRootTab) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = metrics.pagePadding, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ChevronLeft, "返回", tint = YMColors.fg)
                    }
                    Text("音乐", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = YMColors.fg)
                }
            }

            // Yimeng signature DoodleTabs directly at top (zero double top bar)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = metrics.pagePadding, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    YMDoodleTabs(
                        items = MusicSubTab.entries,
                        selected = subTab,
                        label = { it.label },
                        onSelect = { subTab = it },
                    )
                }
                IconButton(onClick = { go("music/settings") }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Filled.Settings, "音乐与音源设置", tint = YMColors.secondary, modifier = Modifier.size(19.dp))
                }
            }

            val extraBottom = bottomInset + if (player.hasActiveSession) 74.dp else 16.dp
            when (subTab) {
                MusicSubTab.DISCOVER -> DiscoverTab(
                    pad = metrics.pagePadding,
                    bottomPad = extraBottom,
                    searchText = searchText,
                    onSearchTextChange = {
                        searchText = it
                        if (it.isBlank()) searchResults = emptyList()
                    },
                    onSearchSubmit = {
                        scope.launch {
                            searching = true
                            searchResults = MusicProviderHub.searchAll(
                                searchText,
                                store.enabledOnlineSources,
                                store.nasTracks,
                                store.cookieMap(),
                            )
                            searching = false
                        }
                    },
                    searching = searching,
                    searchResults = searchResults,
                    searchPlaceholder = searchPlaceholder,
                    heroTracks = heroTracks,
                    charts = visibleCharts,
                    dailyTracks = dailyTracks,
                    dailyIsPersonalized = dailyIsPersonalized,
                    recommendedPlaylists = visibleRecPlaylists,
                    recPlaylistsPersonalized = recPlaylistsPersonalized,
                    fmTracks = fmTracks,
                    fmIsPersonalized = fmIsPersonalized,
                    loadingFM = loadingFM,
                    loadingCharts = loadingCharts,
                    yueqianTrack = yueqianTrack,
                    yueqianHook = yueqianHook,
                    onOpenYueqian = { showYueqian = true },
                    onOpenChart = { go(MusicRoutes.chart(it.id)) },
                    onOpenPlaylist = { go(MusicRoutes.playlist(it.id)) },
                    onPlayTrack = { t, list -> player.play(t, list) },
                    onPlayDaily = { go("music/daily") },
                    onPlayFM = {
                        scope.launch {
                            if (fmTracks.isNotEmpty()) {
                                val list = fmTracks
                                player.play(list.first(), list)
                                showToast("$fmSourceLabel 已开启（共 ${list.size} 首）")
                                val fresh = MusicProviderHub.fetchPersonalFM(enabledChartSources, store.cookieMap())
                                if (fresh.tracks.isNotEmpty()) {
                                    fmTracks = fresh.tracks
                                    fmIsPersonalized = fresh.isPersonalized
                                    fmSourceLabel = fresh.sourceLabel
                                }
                            } else {
                                loadingFM = true
                                val res = MusicProviderHub.fetchPersonalFM(enabledChartSources, store.cookieMap())
                                loadingFM = false
                                val pool = res.tracks.ifEmpty { allChartTracks.shuffled() }
                                if (pool.isNotEmpty()) {
                                    fmTracks = pool
                                    fmIsPersonalized = res.isPersonalized
                                    fmSourceLabel = res.sourceLabel
                                    player.play(pool.first(), pool)
                                    showToast("${res.sourceLabel} 已开启（共 ${pool.size} 首）")
                                }
                            }
                        }
                    },
                    onRefresh = { scope.launch { reloadCharts(true) } },
                )

                MusicSubTab.CHARTS -> ChartsTab(
                    pad = metrics.pagePadding,
                    bottomPad = extraBottom,
                    enabledPlatforms = enabledChartSources,
                    selectedPlatform = chartPlatform,
                    onSelectPlatform = { chartPlatform = it },
                    charts = visibleCharts,
                    loading = loadingCharts,
                    onRefresh = { scope.launch { reloadCharts(true) } },
                    onOpenChart = { go(MusicRoutes.chart(it.id)) },
                    onOpenSettings = { go("music/settings") },
                    onCloneChart = { c ->
                        val (added, skipped) = store.cloneChartAsPlaylist(c.name, c.tracks)
                        showToast("已克隆到曲库：导入 $added 首" + if (skipped > 0) "，跳过 $skipped 首重复" else "")
                    },
                )

                MusicSubTab.ARCHIVE -> ArchiveTab(
                    pad = metrics.pagePadding,
                    bottomPad = extraBottom,
                    tracks = (store.favorites + dailyTracks + allChartTracks).distinctBy { it.id }.take(18),
                    currentIndex = archiveIndex,
                    onChangeIndex = { archiveIndex = it },
                    onPlay = { t, list -> player.play(t, list, openFull = true) },
                )

                MusicSubTab.LIBRARY -> LibraryTab(
                    pad = metrics.pagePadding,
                    bottomPad = extraBottom,
                    segment = librarySegment,
                    onSegment = { librarySegment = it },
                    store = store,
                    onSyncCloud = {
                        scope.launch {
                            val cnt = store.syncCloudPlaylists()
                            showToast(if (cnt > 0) "已同步 $cnt 个云端歌单" else "未拉取到云端歌单，请检查账号登录状态")
                        }
                    },
                    onNewPlaylist = { newPlaylistName = ""; showNewPlaylist = true },
                    onOpenPlaylist = { go(MusicRoutes.playlist(it.id)) },
                    onPlayTrack = { t, list -> player.play(t, list) },
                    onOpenSettings = { go("music/settings") },
                )
            }
        }

        AnimatedVisibility(
            visible = toast != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 54.dp),
        ) {
            Text(
                text = toast.orEmpty(),
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (YMColors.dark) Color.Black else Color.White,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (YMColors.dark) YMColors.Lime else Color.Black)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }

    // Warm paper Yueqian modal
    if (showYueqian && yueqianTrack != null) {
        AlertDialog(
            onDismissRequest = { showYueqian = false },
            containerColor = YMColors.PaperBg,
            title = {
                Text("今日乐签 · YUEQIAN", fontSize = 11.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.Bold, color = YMColors.PaperMuted)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "「$yueqianHook」",
                        fontSize = 20.sp,
                        lineHeight = 29.sp,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.SemiBold,
                        color = YMColors.PaperInk,
                    )
                    HorizontalDivider(color = YMColors.PaperInk.copy(alpha = 0.14f))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        YMCoverImage(yueqianTrack.coverURL, Modifier.size(44.dp), corner = 8.dp)
                        Column(Modifier.weight(1f)) {
                            Text(yueqianTrack.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = YMColors.PaperInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${yueqianTrack.artist} · ${yueqianTrack.sourceDisplayLabel}", fontSize = 11.5.sp, color = YMColors.PaperMuted, maxLines = 1)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    player.play(yueqianTrack, allChartTracks, openFull = true)
                    showYueqian = false
                }) {
                    Text("立即播放", color = YMColors.PaperInk, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showYueqian = false }) {
                    Text("收起", color = YMColors.PaperMuted)
                }
            },
        )
    }

    // Create local playlist dialog
    if (showNewPlaylist) {
        AlertDialog(
            onDismissRequest = { showNewPlaylist = false },
            containerColor = YMColors.card,
            title = { Text("新建本地歌单", color = YMColors.fg) },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    placeholder = { Text("例如：深夜通勤 / 粤语精选") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    store.createPlaylist(newPlaylistName)
                    showNewPlaylist = false
                    showToast("已创建本地歌单")
                }) { Text("创建", color = YMColors.accent, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showNewPlaylist = false }) { Text("取消", color = YMColors.secondary) }
            },
        )
    }
}

// ── Tab 1: Discover (发现) ──────────────────────────────────────────────────────────────────────────

@Composable
private fun DiscoverTab(
    pad: androidx.compose.ui.unit.Dp,
    bottomPad: androidx.compose.ui.unit.Dp,
    searchText: String,
    onSearchTextChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    searching: Boolean,
    searchResults: List<MusicTrack>,
    searchPlaceholder: String,
    heroTracks: List<MusicTrack>,
    charts: List<MusicChart>,
    dailyTracks: List<MusicTrack>,
    dailyIsPersonalized: Boolean,
    recommendedPlaylists: List<MusicPlaylist>,
    recPlaylistsPersonalized: Boolean,
    fmTracks: List<MusicTrack>,
    fmIsPersonalized: Boolean,
    loadingFM: Boolean,
    loadingCharts: Boolean,
    yueqianTrack: MusicTrack?,
    yueqianHook: String,
    onOpenYueqian: () -> Unit,
    onOpenChart: (MusicChart) -> Unit,
    onOpenPlaylist: (MusicPlaylist) -> Unit,
    onPlayTrack: (MusicTrack, List<MusicTrack>) -> Unit,
    onPlayDaily: () -> Unit,
    onPlayFM: () -> Unit,
    onRefresh: () -> Unit,
) {
    val fg = YMColors.fg
    val sec = YMColors.secondary
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = pad, end = pad, top = 8.dp, bottom = bottomPad),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Multi-source search bar
        item("search") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(CircleShape)
                    .background(YMColors.fill)
                    .border(0.8.dp, YMColors.border, CircleShape)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Filled.Search, null, tint = sec, modifier = Modifier.size(18.dp))
                Box(Modifier.weight(1f)) {
                    if (searchText.isEmpty()) {
                        Text(searchPlaceholder, fontSize = 13.5.sp, color = sec, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    BasicTextField(
                        value = searchText,
                        onValueChange = onSearchTextChange,
                        singleLine = true,
                        textStyle = TextStyle(color = fg, fontSize = 14.sp),
                        cursorBrush = SolidColor(YMColors.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearchSubmit() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (searchText.isNotEmpty()) {
                    IconButton(onClick = { onSearchTextChange("") }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Close, "清空", tint = sec, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        if (searching) {
            item("searching") {
                Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = YMColors.accent, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("客户端多源直连聚合检索中…", fontSize = 13.sp, color = sec)
                }
            }
        } else if (searchResults.isNotEmpty()) {
            item("search_hdr") {
                Text("多源聚合结果 · ${searchResults.size} 首", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = sec)
            }
            itemsIndexed(searchResults, key = { _, t -> t.id }) { idx, track ->
                TrackRowItem(rank = idx + 1, track = track, onClick = { onPlayTrack(track, searchResults) })
            }
        } else {
            // Swipeable Hero Spotlight Carousel
            if (heroTracks.isNotEmpty()) {
                item("hero_carousel") {
                    val pagerState = rememberPagerState(pageCount = { heroTracks.size })
                    val playQueue = dailyTracks.ifEmpty { heroTracks }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxWidth().height(176.dp),
                            pageSpacing = 12.dp,
                        ) { page ->
                            val track = heroTracks[page]
                            val isPersonal = dailyIsPersonalized && dailyTracks.any { it.id == track.id }
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(20.dp))
                                    .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(20.dp))
                                    .clickable { onPlayTrack(track, playQueue) },
                            ) {
                                YMCoverImage(track.coverURL, Modifier.fillMaxSize(), corner = 20.dp)
                                Box(
                                    Modifier.fillMaxSize().background(
                                        Brush.verticalGradient(listOf(Color(0x18000000), Color(0x45000000), Color(0xD8000000))),
                                    ),
                                )
                                Column(Modifier.fillMaxSize().padding(15.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Row(
                                            Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 10.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            DoodleSparkle(YMColors.Lime, size = 9.dp)
                                            Text("${if (isPersonal) "为你推荐" else "今日主打"} · ${track.source.label}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                        }
                                        Text("${page + 1}/${heroTracks.size}", fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.72f))
                                    }
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                            Text(track.title, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("${track.artist} · ${track.qualityLabel}", fontSize = 12.sp, color = Color.White.copy(alpha = 0.84f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Row(
                                            Modifier
                                                .clip(CircleShape)
                                                .background(Color.White)
                                                .clickable { onPlayTrack(track, playQueue) }
                                                .padding(horizontal = 14.dp, vertical = 7.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            Icon(Icons.Filled.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(15.dp))
                                            Text("播放", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                                        }
                                    }
                                }
                            }
                        }
                        if (heroTracks.size > 1) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                repeat(heroTracks.size) { i ->
                                    val active = pagerState.currentPage == i
                                    Box(
                                        Modifier
                                            .padding(horizontal = 3.dp)
                                            .height(5.dp)
                                            .width(if (active) 18.dp else 6.dp)
                                            .clip(CircleShape)
                                            .background(if (active) YMColors.accent else sec.copy(alpha = 0.32f)),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Warm-paper 今日乐签 (Yimeng signature card)
            item("yueqian") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(YMColors.PaperBg)
                        .clickable { onOpenYueqian() }
                        .padding(18.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("今日乐签 · YUEQIAN", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, color = YMColors.PaperMuted)
                            Text("无服务端直连", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = YMColors.PaperInk.copy(alpha = 0.55f))
                        }
                        Text(
                            "「$yueqianHook」",
                            fontSize = 18.sp,
                            lineHeight = 26.sp,
                            fontFamily = FontFamily.Serif,
                            fontWeight = FontWeight.SemiBold,
                            color = YMColors.PaperInk,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (yueqianTrack != null) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                YMCoverImage(yueqianTrack.coverURL, Modifier.size(34.dp), corner = 8.dp)
                                Column(Modifier.weight(1f)) {
                                    Text(yueqianTrack.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = YMColors.PaperInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(yueqianTrack.artist, fontSize = 11.sp, color = YMColors.PaperMuted, maxLines = 1)
                                }
                                Box(
                                    Modifier.clip(CircleShape).background(YMColors.PaperInk).padding(horizontal = 12.dp, vertical = 6.dp),
                                ) {
                                    Text("听这首", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = YMColors.PaperBg)
                                }
                            }
                        }
                    }
                }
            }

            // Quick Action Dual Cards: 每日推荐 + 真·私人 FM
            item("quick") {
                val dailyPreview = dailyTracks.take(2).joinToString(" / ") { it.title }
                    .ifBlank { if (dailyIsPersonalized) "账号私人日推 · 30 首" else "查看今日直连精选 30 首" }
                val fmPreview = fmTracks.firstOrNull()?.let { "${it.title} · ${it.primaryArtistName}" }
                    ?: if (fmIsPersonalized) "账号私人 FM · 直连漫游" else "多源探索电台 · 自动换源"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    YMDashedCard(Modifier.weight(1f).clickable { onPlayDaily() }) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text("每日推荐", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = fg)
                                    if (dailyIsPersonalized) {
                                        Text(
                                            "私人",
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.Black,
                                            color = if (YMColors.dark) Color.Black else Color.White,
                                            modifier = Modifier.clip(CircleShape).background(YMColors.accent).padding(horizontal = 5.dp, vertical = 1.dp),
                                        )
                                    }
                                }
                                Icon(Icons.Filled.ChevronRight, null, tint = YMColors.accent, modifier = Modifier.size(18.dp))
                            }
                            Text(dailyPreview, fontSize = 11.5.sp, color = sec, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    YMDashedCard(Modifier.weight(1f).clickable { onPlayFM() }) {
                        val isPlayingFm = IdjApp.instance.musicPlayer.isPlaying
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                if (loadingFM) {
                                    CircularProgressIndicator(Modifier.size(15.dp), color = YMColors.Pink, strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Filled.Radio, null, tint = YMColors.Pink, modifier = Modifier.size(17.dp))
                                }
                                Text("私人 FM", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = fg)
                                if (fmIsPersonalized) {
                                    Text(
                                        "私人",
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Black,
                                        color = if (YMColors.dark) Color.Black else Color.White,
                                        modifier = Modifier.clip(CircleShape).background(YMColors.accent).padding(horizontal = 5.dp, vertical = 1.dp),
                                    )
                                }
                            }
                            DoodleCrayonWave(
                                color = YMColors.accent.copy(alpha = 0.85f),
                                waves = 4,
                                strokeWidth = 2.dp,
                                durationMs = if (isPlayingFm) 1400 else 2800,
                                modifier = Modifier.fillMaxWidth().height(12.dp),
                            )
                            Text(fmPreview, fontSize = 11.5.sp, color = sec, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            // 推荐歌单 (Curated & Personalized Horizontal Playlist Carousel)
            if (recommendedPlaylists.isNotEmpty()) {
                item("rec_pl_hdr") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            DoodleSparkle(YMColors.accent, size = 11.dp)
                            Text("推荐歌单", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = fg)
                            if (recPlaylistsPersonalized) {
                                Text(
                                    "个性",
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Black,
                                    color = if (YMColors.dark) Color.Black else Color.White,
                                    modifier = Modifier.clip(CircleShape).background(YMColors.accent).padding(horizontal = 5.dp, vertical = 1.dp),
                                )
                            }
                        }
                        Text(
                            "换一批",
                            fontSize = 12.sp,
                            color = sec,
                            modifier = Modifier.clickable { onRefresh() }.padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
                item("rec_pl_row") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(recommendedPlaylists, key = { it.id }) { pl ->
                            Column(
                                Modifier.width(132.dp).clickable { onOpenPlaylist(pl) },
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Box(Modifier.size(132.dp).clip(RoundedCornerShape(14.dp))) {
                                    YMCoverImage(pl.displayCoverURL, Modifier.fillMaxSize(), corner = 14.dp)
                                    Box(
                                        Modifier.fillMaxSize().background(
                                            Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.62f))),
                                        ),
                                    )
                                    Row(
                                        Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(7.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        pl.playCountText?.takeIf { it.isNotBlank() }?.let { badge ->
                                            Text(
                                                badge,
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 6.dp, vertical = 2.dp),
                                            )
                                        } ?: Spacer(Modifier.width(1.dp))
                                        pl.source?.let { src ->
                                            Text(
                                                src.label,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.Black,
                                                modifier = Modifier.clip(CircleShape).background(YMColors.Lime).padding(horizontal = 5.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                }
                                Text(
                                    pl.name,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = fg,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    lineHeight = 16.5.sp,
                                )
                            }
                        }
                    }
                }
            }

            // 为你推荐 (Inline 4-Track Daily Recommendations Preview)
            if (dailyTracks.isNotEmpty()) {
                item("daily_inline_hdr") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            DoodleSparkle(YMColors.accent, size = 11.dp)
                            Text(if (dailyIsPersonalized) "为你推荐" else "今日精选推荐", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = fg)
                        }
                        Text(
                            "全部 ${dailyTracks.size} 首 ›",
                            fontSize = 12.5.sp,
                            color = sec,
                            modifier = Modifier.clickable { onPlayDaily() },
                        )
                    }
                }
                itemsIndexed(dailyTracks.take(4), key = { _, t -> "rec_${t.id}" }) { idx, t ->
                    TrackRowItem(rank = idx + 1, track = t, onClick = { onPlayTrack(t, dailyTracks) })
                }
            }

            // Hot Charts Preview
            item("charts_hdr") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DoodleSparkle(YMColors.accent, size = 11.dp)
                        Text("全网实时榜单", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = fg)
                    }
                    IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Refresh, "刷新榜单", tint = sec, modifier = Modifier.size(18.dp))
                    }
                }
            }

            if (loadingCharts && charts.isEmpty()) {
                item("loading") {
                    Row(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), color = YMColors.accent, strokeWidth = 2.dp)
                    }
                }
            } else {
                itemsIndexed(charts.take(4), key = { _, c -> c.id }) { _, chart ->
                    ChartCardItem(chart = chart, onClick = { onOpenChart(chart) })
                }
            }
        }
    }
}

// ── Tab 2: Charts (排行) ────────────────────────────────────────────────────────────────────────────

@Composable
private fun ChartsTab(
    pad: androidx.compose.ui.unit.Dp,
    bottomPad: androidx.compose.ui.unit.Dp,
    enabledPlatforms: List<MusicSourceKind>,
    selectedPlatform: MusicSourceKind?,
    onSelectPlatform: (MusicSourceKind?) -> Unit,
    charts: List<MusicChart>,
    loading: Boolean,
    onRefresh: () -> Unit,
    onOpenChart: (MusicChart) -> Unit,
    onOpenSettings: () -> Unit,
    onCloneChart: (MusicChart) -> Unit,
) {
    val platforms = remember(enabledPlatforms) {
        buildList<MusicSourceKind?> {
            if (enabledPlatforms.size > 1) add(null)
            addAll(enabledPlatforms)
        }
    }
    val effectivePlatform = remember(selectedPlatform, enabledPlatforms) {
        when {
            enabledPlatforms.isEmpty() -> null
            selectedPlatform != null && selectedPlatform in enabledPlatforms -> selectedPlatform
            enabledPlatforms.size == 1 -> enabledPlatforms.first()
            else -> null
        }
    }
    val filtered = remember(charts, effectivePlatform, enabledPlatforms) {
        val allow = enabledPlatforms.toSet()
        charts.filter { it.source in allow && (effectivePlatform == null || it.source == effectivePlatform) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = pad, end = pad, top = 8.dp, bottom = bottomPad),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (platforms.isNotEmpty()) {
            item("filters") {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    platforms.forEach { p ->
                        val on = p == effectivePlatform
                        Box(
                            Modifier
                                .clip(CircleShape)
                                .background(if (on) (if (YMColors.dark) Color.White else Color.Black) else YMColors.fill)
                                .clickable { onSelectPlatform(p) }
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                        ) {
                            Text(
                                text = p?.label ?: "全部平台",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (on) (if (YMColors.dark) Color.Black else Color.White) else YMColors.secondary,
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f, fill = false))
                    IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Refresh, "刷新", tint = YMColors.secondary, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        if (enabledPlatforms.isEmpty()) {
            item("no_sources") {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("已关闭全部在线音乐源", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = YMColors.fg)
                    Text("在「音乐与音源设置」中开启任意音源即可查看对应榜单", fontSize = 12.5.sp, color = YMColors.secondary)
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .background(if (YMColors.dark) Color.White else Color.Black)
                            .clickable { onOpenSettings() }
                            .padding(horizontal = 18.dp, vertical = 9.dp),
                    ) {
                        Text("前往音源设置", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (YMColors.dark) Color.Black else Color.White)
                    }
                }
            }
        } else if (loading && filtered.isEmpty()) {
            item("loading") {
                Box(Modifier.fillMaxWidth().padding(vertical = 36.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = YMColors.accent, strokeWidth = 2.dp)
                }
            }
        } else {
            itemsIndexed(filtered, key = { _, c -> c.id }) { _, chart ->
                YMDashedCard(Modifier.fillMaxWidth().clickable { onOpenChart(chart) }) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            YMChartCollage(chart.coverURLs, size = 72.dp)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(chart.name, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = YMColors.fg)
                                    YMQualityPill(chart.source.label)
                                }
                                Text(chart.subtitle, fontSize = 12.sp, color = YMColors.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(chart.updatedLabel, fontSize = 11.sp, color = YMColors.tertiary)
                                    Text("·", color = YMColors.tertiary)
                                    Text(
                                        "克隆歌单",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = YMColors.accent,
                                        modifier = Modifier.clickable { onCloneChart(chart) },
                                    )
                                }
                            }
                        }
                        chart.tracks.take(3).forEachIndexed { idx, t ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                YMRankBadge(idx + 1)
                                Text(t.title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = YMColors.fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(t.artist, fontSize = 11.5.sp, color = YMColors.secondary, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Tab 3: Archive (典藏 · 益梦 3D 竖滑唱片栈) ───────────────────────────────────────────────────────

@Composable
private fun ArchiveTab(
    pad: androidx.compose.ui.unit.Dp,
    bottomPad: androidx.compose.ui.unit.Dp,
    tracks: List<MusicTrack>,
    currentIndex: Int,
    onChangeIndex: (Int) -> Unit,
    onPlay: (MusicTrack, List<MusicTrack>) -> Unit,
) {
    if (tracks.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("加载榜单或收藏歌曲后即可浏览典藏唱片架", color = YMColors.secondary, fontSize = 14.sp)
        }
        return
    }
    val go = LocalNavigate.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current.density
    val safeIdx = currentIndex.coerceIn(0, tracks.lastIndex)

    val focusAnim = remember { Animatable(safeIdx.toFloat()) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    var lastTickIdx by remember { mutableIntStateOf(safeIdx) }

    LaunchedEffect(safeIdx, tracks.size) {
        if (abs(focusAnim.value - safeIdx) > 0.01f) {
            focusAnim.animateTo(safeIdx.toFloat(), spring(stiffness = 200f, dampingRatio = 0.78f))
        }
    }

    val effectiveFocus = (focusAnim.value - dragOffsetPx / 210f)
        .coerceIn(-0.4f, tracks.lastIndex + 0.4f)
    val focalIdx = effectiveFocus.roundToInt().coerceIn(0, tracks.lastIndex)
    val current = tracks[focalIdx]

    fun jumpTo(target: Int) {
        val clamped = target.coerceIn(0, tracks.lastIndex)
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onChangeIndex(clamped)
        scope.launch {
            focusAnim.animateTo(clamped.toFloat(), spring(stiffness = 200f, dampingRatio = 0.78f))
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(start = pad, end = pad, top = 8.dp, bottom = bottomPad),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("典藏唱片栈 · ARCHIVE", fontSize = 11.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.Bold, color = YMColors.secondary)
            Text("${focalIdx + 1} / ${tracks.size} · 上下滑动切歌", fontSize = 11.sp, color = YMColors.tertiary)
        }

        // Yimeng-faithful vertical-drag 3D tilting vinyl deck (no outer verticalScroll stealing drag!)
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .heightIn(min = 260.dp)
                .pointerInput(tracks.size) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragOffsetPx = 0f
                            lastTickIdx = focusAnim.value.roundToInt().coerceIn(0, tracks.lastIndex)
                        },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            dragOffsetPx += dragAmount
                            val live = (focusAnim.value - dragOffsetPx / 210f)
                                .coerceIn(-0.4f, tracks.lastIndex + 0.4f)
                            val nearest = live.roundToInt().coerceIn(0, tracks.lastIndex)
                            if (nearest != lastTickIdx) {
                                lastTickIdx = nearest
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        },
                        onDragEnd = {
                            val live = (focusAnim.value - dragOffsetPx / 210f)
                                .coerceIn(-0.4f, tracks.lastIndex + 0.4f)
                            val target = live.roundToInt().coerceIn(0, tracks.lastIndex)
                            dragOffsetPx = 0f
                            onChangeIndex(target)
                            scope.launch {
                                focusAnim.snapTo(live)
                                focusAnim.animateTo(target.toFloat(), spring(stiffness = 200f, dampingRatio = 0.78f))
                            }
                        },
                        onDragCancel = {
                            val live = (focusAnim.value - dragOffsetPx / 210f)
                                .coerceIn(0f, tracks.lastIndex.toFloat())
                            val target = live.roundToInt().coerceIn(0, tracks.lastIndex)
                            dragOffsetPx = 0f
                            scope.launch {
                                focusAnim.snapTo(live)
                                focusAnim.animateTo(target.toFloat(), spring(stiffness = 200f, dampingRatio = 0.78f))
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            val minIdx = (focalIdx - 2).coerceAtLeast(0)
            val maxIdx = (focalIdx + 3).coerceAtMost(tracks.lastIndex)
            for (idx in maxIdx downTo minIdx) {
                val track = tracks[idx]
                val rel = idx - effectiveFocus
                val rotX = when {
                    rel > 0f -> -36f * rel.coerceAtMost(1.35f)
                    rel < 0f -> 18f * (-rel).coerceAtMost(1.25f)
                    else -> 0f
                }
                val transY = when {
                    rel > 0f -> rel * 44f * density
                    rel < 0f -> rel * 185f * density
                    else -> 0f
                }
                val scale = when {
                    rel > 0f -> (1f - rel * 0.085f).coerceAtLeast(0.72f)
                    rel < 0f -> (1f + rel * 0.05f).coerceAtLeast(0.84f)
                    else -> 1f
                }
                val cardAlpha = when {
                    rel < -0.95f -> ((1.35f + rel) / 0.4f).coerceIn(0f, 1f)
                    rel > 2.4f -> ((3.0f - rel) / 0.6f).coerceIn(0f, 1f)
                    rel > 0f -> (1f - rel * 0.16f).coerceIn(0.35f, 1f)
                    else -> 1f
                }
                val z = if (rel >= 0f) 10f - rel else 12f + rel

                Box(
                    Modifier
                        .zIndex(z)
                        .size(236.dp)
                        .graphicsLayer {
                            rotationX = rotX
                            translationY = transY
                            scaleX = scale
                            scaleY = scale
                            alpha = cardAlpha
                            transformOrigin = TransformOrigin(0.5f, 0.14f)
                            cameraDistance = 14f * density
                        }
                        .clip(RoundedCornerShape(24.dp))
                        .border(
                            width = if (abs(rel) < 0.35f) 1.4.dp else 0.8.dp,
                            color = Color.White.copy(alpha = if (abs(rel) < 0.35f) 0.28f else 0.12f),
                            shape = RoundedCornerShape(24.dp),
                        )
                        .clickable {
                            if (idx == focalIdx) onPlay(track, tracks) else jumpTo(idx)
                        },
                ) {
                    YMCoverImage(track.coverURL, Modifier.fillMaxSize(), corner = 24.dp)
                }
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(current.title, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = YMColors.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(YMColors.fill)
                        .clickable {
                            go(MusicRoutes.artist(current.primaryArtistName, current.artistId, current.source.id, current.coverURL))
                        }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text("${current.primaryArtistName} ▸", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = YMColors.secondary, maxLines = 1)
                }
                if (current.album.isNotBlank()) {
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .background(YMColors.fill)
                            .clickable {
                                go(MusicRoutes.album(current.album, current.albumId, current.primaryArtistName, current.artistId, current.source.id, current.coverURL))
                            }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text("${current.album} ▸", fontSize = 12.sp, color = YMColors.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { jumpTo((focalIdx - 1).coerceAtLeast(0)) }, enabled = focalIdx > 0) {
                Icon(Icons.Filled.ChevronLeft, "上一张", tint = if (focalIdx > 0) YMColors.fg else YMColors.tertiary)
            }
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(if (YMColors.dark) Color.White else Color.Black)
                    .clickable { onPlay(current, tracks) }
                    .padding(horizontal = 24.dp, vertical = 10.dp),
            ) {
                Text("播放这张", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (YMColors.dark) Color.Black else Color.White)
            }
            IconButton(onClick = { jumpTo((focalIdx + 1).coerceAtMost(tracks.lastIndex)) }, enabled = focalIdx < tracks.lastIndex) {
                Icon(Icons.Filled.ChevronRight, "下一张", tint = if (focalIdx < tracks.lastIndex) YMColors.fg else YMColors.tertiary)
            }
        }
    }
}

// ── Tab 4: Library (曲库) ───────────────────────────────────────────────────────────────────────────

@Composable
private fun LibraryTab(
    pad: androidx.compose.ui.unit.Dp,
    bottomPad: androidx.compose.ui.unit.Dp,
    segment: Int,
    onSegment: (Int) -> Unit,
    store: MusicStore,
    onSyncCloud: () -> Unit,
    onNewPlaylist: () -> Unit,
    onOpenPlaylist: (MusicPlaylist) -> Unit,
    onPlayTrack: (MusicTrack, List<MusicTrack>) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val totalPlaylists = store.cloudPlaylists.size + store.playlists.size
    val cloudLiked = store.cloudPlaylists.firstOrNull { it.playCountText == "红心" || it.name.contains("我喜欢") }
    val segs = listOf("红心 (${store.favorites.size})", "歌单 ($totalPlaylists)", "最近", "NAS (${store.nasTracks.size})")
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = pad, end = pad, top = 8.dp, bottom = bottomPad),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item("segs") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    segs.forEachIndexed { idx, label ->
                        val on = idx == segment
                        Box(
                            Modifier
                                .clip(CircleShape)
                                .background(if (on) (if (YMColors.dark) Color.White else Color.Black) else YMColors.fill)
                                .clickable { onSegment(idx) }
                                .padding(horizontal = 13.dp, vertical = 7.dp),
                        ) {
                            Text(label, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = if (on) (if (YMColors.dark) Color.Black else Color.White) else YMColors.secondary)
                        }
                    }
                }
                IconButton(onClick = onOpenSettings, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Filled.Settings, "音源与播放设置", tint = YMColors.secondary, modifier = Modifier.size(19.dp))
                }
            }
        }

        when (segment) {
            0 -> {
                if (cloudLiked != null) {
                    item("cloud_liked_banner") {
                        YMDashedCard(Modifier.fillMaxWidth().clickable { onOpenPlaylist(cloudLiked) }) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                YMCoverImage(cloudLiked.displayCoverURL, Modifier.size(50.dp), corner = 12.dp)
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(cloudLiked.name, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = YMColors.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            "云端红心",
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.Black,
                                            color = Color.White,
                                            modifier = Modifier.clip(CircleShape).background(YMColors.Pink).padding(horizontal = 6.dp, vertical = 1.dp),
                                        )
                                    }
                                    Text(cloudLiked.subtitle, fontSize = 12.sp, color = YMColors.secondary)
                                }
                                Icon(Icons.Filled.ChevronRight, null, tint = YMColors.secondary)
                            }
                        }
                    }
                }
                if (store.favorites.isEmpty()) {
                    item("empty_fav") {
                        Text(
                            if (cloudLiked != null) "本机收藏为空，在播放页点红心可离线收藏到本机" else "在播放页点击红心即可收藏到本地",
                            fontSize = 13.sp,
                            color = YMColors.secondary,
                            modifier = Modifier.padding(vertical = 22.dp),
                        )
                    }
                } else {
                    itemsIndexed(store.favorites, key = { _, t -> t.id }) { idx, t ->
                        TrackRowItem(rank = idx + 1, track = t, onClick = { onPlayTrack(t, store.favorites) })
                    }
                }
            }
            1 -> {
                item("cloud_hdr") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("账号云端歌单（${store.cloudPlaylists.size}）", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = YMColors.fg)
                        if (store.hasAnyLoggedInSource) {
                            Row(
                                Modifier.clickable(enabled = !store.syncingCloudPlaylists) { onSyncCloud() }.padding(horizontal = 4.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                if (store.syncingCloudPlaylists) {
                                    CircularProgressIndicator(Modifier.size(13.dp), color = YMColors.accent, strokeWidth = 1.8.dp)
                                } else {
                                    Icon(Icons.Filled.Refresh, null, tint = YMColors.accent, modifier = Modifier.size(15.dp))
                                }
                                Text(if (store.syncingCloudPlaylists) "同步中…" else "立即同步", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = YMColors.accent)
                            }
                        } else {
                            Text("登录同步 ›", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = YMColors.accent, modifier = Modifier.clickable { onOpenSettings() })
                        }
                    }
                }
                if (store.cloudPlaylists.isEmpty()) {
                    item("cloud_empty") {
                        Text(
                            if (store.hasAnyLoggedInSource) "点击右上角「立即同步」拉取已登录账号的云端歌单" else "登录后自动同步网易云 / QQ 音乐 / 酷狗 / 汽水云端歌单",
                            fontSize = 12.5.sp,
                            color = YMColors.secondary,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                    }
                } else {
                    itemsIndexed(store.cloudPlaylists, key = { _, p -> p.id }) { _, pl ->
                        YMDashedCard(Modifier.fillMaxWidth().clickable { onOpenPlaylist(pl) }) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                YMCoverImage(pl.displayCoverURL, Modifier.size(52.dp), corner = 12.dp)
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Text(pl.name, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = YMColors.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (pl.playCountText == "红心") {
                                            Icon(Icons.Filled.Favorite, null, tint = YMColors.Pink, modifier = Modifier.size(13.dp))
                                        }
                                    }
                                    Text(pl.subtitle, fontSize = 12.sp, color = YMColors.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                pl.source?.let { src ->
                                    Text(
                                        src.label,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = YMColors.accent,
                                        modifier = Modifier.clip(CircleShape).background(YMColors.fill).padding(horizontal = 7.dp, vertical = 3.dp),
                                    )
                                }
                                Icon(Icons.Filled.ChevronRight, null, tint = YMColors.secondary)
                            }
                        }
                    }
                }
                item("local_hdr") {
                    Text("本机歌单（${store.playlists.size}）", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = YMColors.fg, modifier = Modifier.padding(top = 6.dp))
                }
                item("new_pl") {
                    YMDashedCard(Modifier.fillMaxWidth().clickable { onNewPlaylist() }) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Filled.Add, null, tint = YMColors.accent)
                            Text("新建本地歌单（支持从排行/歌单页一键克隆）", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = YMColors.fg)
                        }
                    }
                }
                itemsIndexed(store.playlists, key = { _, p -> p.id }) { _, pl ->
                    YMDashedCard(Modifier.fillMaxWidth().clickable { onOpenPlaylist(pl) }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            YMCoverImage(pl.displayCoverURL, Modifier.size(52.dp), corner = 12.dp)
                            Column(Modifier.weight(1f)) {
                                Text(pl.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = YMColors.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(pl.subtitle, fontSize = 12.sp, color = YMColors.secondary)
                            }
                            IconButton(onClick = { store.deletePlaylist(pl.id) }) {
                                Icon(Icons.Filled.DeleteOutline, "删除歌单", tint = YMColors.tertiary)
                            }
                        }
                    }
                }
            }
            2 -> {
                if (store.recents.isEmpty()) {
                    item("empty_rec") { Text("暂无最近播放记录", fontSize = 13.5.sp, color = YMColors.secondary, modifier = Modifier.padding(vertical = 28.dp)) }
                } else {
                    itemsIndexed(store.recents, key = { _, t -> t.id }) { idx, t ->
                        TrackRowItem(rank = idx + 1, track = t, onClick = { onPlayTrack(t, store.recents) })
                    }
                }
            }
            else -> {
                if (store.nasTracks.isEmpty()) {
                    item("empty_nas") {
                        YMDashedCard(Modifier.fillMaxWidth().clickable { onOpenSettings() }) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Filled.Storage, null, tint = YMColors.accent)
                                Column {
                                    Text("直连个人曲库（Navidrome / Emby / 飞牛音乐）", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = YMColors.fg)
                                    Text("前往「我的 → 音乐与音源设置」添加并同步", fontSize = 12.sp, color = YMColors.secondary)
                                }
                            }
                        }
                    }
                } else {
                    itemsIndexed(store.nasTracks, key = { _, t -> t.id }) { idx, t ->
                        TrackRowItem(rank = idx + 1, track = t, onClick = { onPlayTrack(t, store.nasTracks) })
                    }
                }
            }
        }
    }
}

// ── Shared Row / Card Items ─────────────────────────────────────────────────────────────────────────

@Composable
private fun ChartCardItem(chart: MusicChart, onClick: () -> Unit) {
    YMDashedCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            YMChartCollage(chart.coverURLs, size = 66.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(chart.name, fontSize = 15.5.sp, fontWeight = FontWeight.ExtraBold, color = YMColors.fg)
                    YMQualityPill(chart.source.label)
                }
                chart.tracks.take(2).forEachIndexed { i, t ->
                    Text("${i + 1}. ${t.title} - ${t.artist}", fontSize = 12.sp, color = YMColors.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(Icons.Filled.ChevronRight, null, tint = YMColors.tertiary)
        }
    }
}

@Composable
internal fun TrackRowItem(rank: Int, track: MusicTrack, onClick: () -> Unit) {
    val player = IdjApp.instance.musicPlayer
    val store = IdjApp.instance.musicStore
    val go = LocalNavigate.current
    val active = player.currentTrack?.id == track.id
    val fav = store.isFavorite(track)
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) YMColors.Lime.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        YMRankBadge(rank)
        YMCoverImage(track.coverURL, Modifier.size(44.dp), corner = 10.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                track.title,
                fontSize = 14.5.sp,
                fontWeight = if (active) FontWeight.ExtraBold else FontWeight.SemiBold,
                color = if (active) YMColors.accent else YMColors.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                YMQualityPill(track.sourceDisplayLabel)
                Text(
                    buildString {
                        append(track.artist)
                        if (track.album.isNotBlank()) append(" · ").append(track.album)
                    },
                    fontSize = 12.sp,
                    color = YMColors.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(track.formattedDuration, fontSize = 11.5.sp, color = YMColors.tertiary)
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Filled.MoreHoriz, "更多操作", tint = YMColors.tertiary, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("查看歌手：${track.primaryArtistName}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Filled.Person, null, Modifier.size(18.dp)) },
                    onClick = {
                        menuOpen = false
                        go(MusicRoutes.artist(track.primaryArtistName, track.artistId, track.source.id, track.coverURL))
                    },
                )
                DropdownMenuItem(
                    text = { Text("查看专辑：${track.album.ifBlank { track.title }}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Filled.Album, null, Modifier.size(18.dp)) },
                    onClick = {
                        menuOpen = false
                        go(MusicRoutes.album(track.album.ifBlank { track.title }, track.albumId, track.primaryArtistName, track.artistId, track.source.id, track.coverURL))
                    },
                )
                DropdownMenuItem(
                    text = { Text(if (fav) "取消红心收藏" else "加入红心收藏") },
                    leadingIcon = { Icon(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, null, Modifier.size(18.dp)) },
                    onClick = {
                        menuOpen = false
                        store.toggleFavorite(track)
                    },
                )
                DropdownMenuItem(
                    text = { Text("打开全屏播放") },
                    leadingIcon = { Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp)) },
                    onClick = {
                        menuOpen = false
                        player.play(track, listOf(track), openFull = true)
                    },
                )
            }
        }
    }
}

// ── Global 54dp Floating Circular-Art Mini Player Bar ───────────────────────────────────────────────

@Composable
fun MusicMiniPlayerBar(modifier: Modifier = Modifier) {
    val player = IdjApp.instance.musicPlayer
    val track = player.currentTrack ?: return
    if (player.miniBarDismissed) return

    var dragX by remember { mutableFloatStateOf(0f) }

    Row(
        modifier = modifier
            .offset { IntOffset(dragX.roundToInt(), 0) }
            .height(54.dp)
            .ymDockSurface(CircleShape, shadowElevation = 14.dp)
            .pointerInput(track.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (dragX < -160f) player.dismissMiniBar()
                        dragX = 0f
                    },
                    onDragCancel = { dragX = 0f },
                ) { _, amount ->
                    dragX = (dragX + amount).coerceAtMost(0f)
                }
            }
            .padding(start = 8.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.weight(1f).clickable { player.showFullPlayer = true },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box {
                YMCoverImage(track.coverURL, Modifier.size(40.dp).border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape), isCircle = true)
                if (player.isPlaying) {
                    Box(Modifier.align(Alignment.BottomEnd).size(9.dp).clip(CircleShape).background(YMColors.Lime).border(1.5.dp, Color(0xFF0A0A0C), CircleShape))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(track.title, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = YMColors.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${track.artist} · ${track.sourceDisplayLabel}", fontSize = 11.sp, color = YMColors.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (player.isLoading) {
            CircularProgressIndicator(Modifier.size(18.dp), color = YMColors.accent, strokeWidth = 2.dp)
        } else {
            IconButton(onClick = { player.togglePlayPause() }, modifier = Modifier.size(34.dp)) {
                Icon(if (player.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "播放/暂停", tint = YMColors.fg)
            }
        }
        IconButton(onClick = { player.nextTrack() }, modifier = Modifier.size(30.dp)) {
            Icon(Icons.Filled.SkipNext, "下一首", tint = YMColors.fg.copy(alpha = 0.85f), modifier = Modifier.size(20.dp))
        }
    }
}

// ── Slim 44dp Reader Menu Music Bar (ONLY shown when Novel/Manga menu/chrome is visible!) ───────────

@Composable
fun ReaderMusicMiniBar(modifier: Modifier = Modifier) {
    val player = IdjApp.instance.musicPlayer
    val track = player.currentTrack ?: return
    if (player.miniBarDismissed) return

    Row(
        modifier = modifier
            .height(44.dp)
            .clip(CircleShape)
            .background(Color(0xCC121218))
            .border(0.8.dp, Color.White.copy(alpha = 0.18f), CircleShape)
            .padding(start = 7.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.weight(1f).clickable { player.showFullPlayer = true },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            YMCoverImage(track.coverURL, Modifier.size(30.dp), isCircle = true)
            Column(Modifier.weight(1f)) {
                Text(track.title, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist, fontSize = 10.5.sp, color = Color.White.copy(alpha = 0.68f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = { player.togglePlayPause() }, modifier = Modifier.size(32.dp)) {
            Icon(if (player.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "播放/暂停", tint = Color.White, modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = { player.nextTrack() }, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.SkipNext, "下一首", tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(18.dp))
        }
    }
}

// ── Full-Screen Now Playing Overlay (Phone DreamField + Tablet 33⅓ rpm Vinyl) ───────────────────────

@Composable
fun MusicNowPlayingOverlay() {
    val player = IdjApp.instance.musicPlayer
    val store = IdjApp.instance.musicStore
    val track = player.currentTrack
    val metrics = LocalLayoutMetrics.current
    val scope = rememberCoroutineScope()

    var showLyricsOnly by remember { mutableStateOf(false) }
    var showTrans by remember { mutableStateOf(true) }
    var showRoma by remember { mutableStateOf(false) }
    var showSignal by remember { mutableStateOf(false) }
    var showAlts by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var alts by remember { mutableStateOf<List<MusicAlternative>>(emptyList()) }
    var loadingAlts by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visible = player.showFullPlayer && track != null,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
    ) {
        if (track == null) return@AnimatedVisibility
        val isWide = metrics.widthDp >= 720
        val fav = store.isFavorite(track)

        Box(Modifier.fillMaxSize()) {
            YMDreamField(coverURL = track.coverURL, isPlaying = player.isPlaying, dimForLyrics = !isWide && showLyricsOnly)

            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                // Top Bar
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { player.showFullPlayer = false }) {
                        Icon(Icons.Filled.KeyboardArrowDown, "收起", tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                    Column(
                        Modifier.weight(1f).clickable { showSignal = true },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(track.sourceDisplayLabel, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = YMColors.Lime)
                        val qBadge = player.activeQualityLabel.ifBlank { track.qualityLabel }
                        Text("$qBadge ▾ · 点击切换音质", fontSize = 11.sp, color = Color.White.copy(alpha = 0.72f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { showSignal = true }) {
                        Icon(Icons.Filled.GraphicEq, "音质与信号路径", tint = YMColors.Lime)
                    }
                }

                if (isWide) {
                    // Tablet Landscape: 33⅓ rpm Vinyl Turntable left + Live Lyrics right
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(0.46f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            YMVinylTurntable(coverURL = track.coverURL, isPlaying = player.isPlaying, size = 270.dp)
                            NowPlayingMetaAndTransport(
                                track = track,
                                fav = fav,
                                store = store,
                                player = player,
                                onOpenAlts = {
                                    showAlts = true
                                    scope.launch {
                                        loadingAlts = true
                                        alts = MusicProviderHub.findAlternatives(track, store.nasTracks, store.cookieMap())
                                        loadingAlts = false
                                    }
                                },
                                onOpenQueue = { showQueue = true },
                                onOpenSleep = { showSleep = true },
                            )
                        }
                        Box(Modifier.weight(0.54f).fillMaxHeight()) {
                            LiveLyricsPanel(
                                trackId = track.id,
                                trackTitle = track.title,
                                trackArtist = track.artist,
                                lyrics = player.lyrics,
                                activeIndex = player.activeLyricIndex,
                                isVisible = true,
                                showTrans = showTrans,
                                showRoma = showRoma,
                                onToggleTrans = { showTrans = !showTrans },
                                onToggleRoma = { showRoma = !showRoma },
                                onCloseToCover = null,
                                onSeek = { player.seekTo(it) },
                            )
                        }
                    }
                } else {
                    // Phone Portrait: keep Artwork + Live Lyrics mounted in fixed Box (zero Coil/LazyColumn rebuild stutter)
                    val lyricsAlpha by animateFloatAsState(
                        targetValue = if (showLyricsOnly) 1f else 0f,
                        animationSpec = tween(160),
                        label = "lyricsAlpha",
                    )
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .zIndex(if (!showLyricsOnly) 1f else 0f)
                                .graphicsLayer { alpha = 1f - lyricsAlpha }
                                .clickable(enabled = !showLyricsOnly) { showLyricsOnly = true },
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                                YMDoodleCoverHalo(coverURL = track.coverURL, isPlaying = player.isPlaying, size = 248.dp)
                                val activeLine = player.lyrics.getOrNull(player.activeLyricIndex)
                                if (activeLine != null) {
                                    Text(
                                        activeLine.text,
                                        fontSize = 14.sp,
                                        color = Color.White.copy(alpha = 0.82f),
                                        textAlign = TextAlign.Center,
                                        maxLines = 2,
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                    )
                                } else {
                                    Text("轻点封面切换逐行歌词", fontSize = 12.sp, color = Color.White.copy(alpha = 0.45f))
                                }
                            }
                        }

                        Box(
                            Modifier
                                .fillMaxSize()
                                .zIndex(if (showLyricsOnly) 1f else 0f)
                                .graphicsLayer { alpha = lyricsAlpha },
                        ) {
                            LiveLyricsPanel(
                                trackId = track.id,
                                trackTitle = track.title,
                                trackArtist = track.artist,
                                lyrics = player.lyrics,
                                activeIndex = player.activeLyricIndex,
                                isVisible = showLyricsOnly,
                                showTrans = showTrans,
                                showRoma = showRoma,
                                onToggleTrans = { showTrans = !showTrans },
                                onToggleRoma = { showRoma = !showRoma },
                                onCloseToCover = { showLyricsOnly = false },
                                onSeek = { player.seekTo(it) },
                            )
                        }
                    }
                    NowPlayingMetaAndTransport(
                        track = track,
                        fav = fav,
                        store = store,
                        player = player,
                        isLyricsVisible = showLyricsOnly,
                        onToggleLyrics = { showLyricsOnly = !showLyricsOnly },
                        onOpenAlts = {
                            showAlts = true
                            scope.launch {
                                loadingAlts = true
                                alts = MusicProviderHub.findAlternatives(track, store.nasTracks, store.cookieMap())
                                loadingAlts = false
                            }
                        },
                        onOpenQueue = { showQueue = true },
                        onOpenSleep = { showSleep = true },
                    )
                }
            }
        }

        // Audio Quality Switcher & Signal Path Dialog
        if (showSignal) {
            val sp = player.signalPath
            val currentPref = store.settings.preferredQuality
            AlertDialog(
                onDismissRequest = { showSignal = false },
                containerColor = Color(0xFF141418),
                title = { Text("音质切换与信号链路", color = Color.White) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("首选播放音质（点击实时切换）", fontSize = 11.5.sp, color = YMColors.Lime, fontWeight = FontWeight.Bold)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            MusicAudioQuality.entries.forEach { q ->
                                val selected = currentPref == q
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (selected) YMColors.Lime.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.06f))
                                        .clickable {
                                            player.switchQuality(q)
                                            showSignal = false
                                        }
                                        .padding(horizontal = 12.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(q.label, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = if (selected) YMColors.Lime else Color.White)
                                        Text(q.detail, fontSize = 11.sp, color = Color.White.copy(alpha = 0.65f))
                                    }
                                    if (selected) {
                                        Text("当前", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = YMColors.Lime)
                                    }
                                }
                            }
                        }
                        Text("音频信号链路", fontSize = 11.5.sp, color = Color.White.copy(alpha = 0.6f), fontWeight = FontWeight.Bold)
                        listOf(
                            "1. 音源" to (sp?.sourceStage ?: track.sourceDisplayLabel),
                            "2. 传输" to (sp?.streamStage ?: "客户端直连"),
                            "3. 解码" to (sp?.decodeStage ?: player.activeQualityLabel.ifBlank { track.qualityLabel }),
                            "4. 输出" to (sp?.outputStage ?: "本机音频"),
                        ).forEach { (k, v) ->
                            Column {
                                Text(k, fontSize = 11.sp, color = YMColors.Lime, fontWeight = FontWeight.Bold)
                                Text(v, fontSize = 12.5.sp, color = Color.White)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showSignal = false }) { Text("关闭", color = YMColors.Lime) } },
            )
        }

        // Alternatives / Switch Source Dialog
        if (showAlts) {
            AlertDialog(
                onDismissRequest = { showAlts = false },
                containerColor = Color(0xFF141418),
                title = { Text("换源 · 备选音质", color = Color.White) },
                text = {
                    if (loadingAlts) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = YMColors.Lime, strokeWidth = 2.dp)
                            Text("正在直连检索其他可用音源…", color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
                        }
                    } else if (alts.isEmpty()) {
                        Text("暂未匹配到其他可播线路，可在「音源」页配置网易云 Cookie 或添加 NAS。", color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            alts.forEach { alt ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color.White.copy(alpha = 0.08f))
                                        .clickable {
                                            player.switchToAlternative(alt)
                                            showAlts = false
                                        }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text("${alt.source.fullName} · ${alt.qualityLabel}", color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                                        Text("${alt.track.title} - ${alt.track.artist}", color = Color.White.copy(alpha = 0.65f), fontSize = 11.5.sp, maxLines = 1)
                                    }
                                    Text(alt.entitlement.badge, color = YMColors.Lime, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showAlts = false }) { Text("关闭", color = YMColors.Lime) } },
            )
        }

        // Queue Dialog (supports swipe-left to remove any track smoothly)
        if (showQueue) {
            AlertDialog(
                onDismissRequest = { showQueue = false },
                containerColor = Color(0xFF141418),
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("播放队列 (${player.queue.size})", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text("左滑歌曲可移出队列", color = Color.White.copy(alpha = 0.52f), fontSize = 11.5.sp)
                    }
                },
                text = {
                    LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        itemsIndexed(player.queue, key = { _, t -> t.id }) { idx, qTrack ->
                            val cur = idx == player.currentIndex
                            val dismissState = rememberSwipeToDismissBoxState(
                                confirmValueChange = { value ->
                                    if (value == SwipeToDismissBoxValue.EndToStart) {
                                        player.removeFromQueue(qTrack.id)
                                        if (player.queue.isEmpty()) showQueue = false
                                        true
                                    } else false
                                },
                                positionalThreshold = { it * 0.35f },
                            )
                            SwipeToDismissBox(
                                state = dismissState,
                                modifier = Modifier.animateItem(),
                                enableDismissFromStartToEnd = false,
                                enableDismissFromEndToStart = true,
                                backgroundContent = {
                                    Box(
                                        Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Color(0xFFD93B4A))
                                            .padding(horizontal = 14.dp),
                                        contentAlignment = Alignment.CenterEnd,
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Icon(Icons.Filled.DeleteOutline, "移除", tint = Color.White, modifier = Modifier.size(16.dp))
                                            Text("移除", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                },
                            ) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (cur) Color(0xFF232919) else Color(0xFF1C1C22))
                                        .clickable {
                                            player.play(qTrack, player.queue)
                                            showQueue = false
                                        }
                                        .padding(horizontal = 10.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(qTrack.title, color = if (cur) YMColors.Lime else Color.White, fontSize = 13.5.sp, fontWeight = if (cur) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    Text(qTrack.artist, color = Color.White.copy(alpha = 0.6f), fontSize = 11.5.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showQueue = false }) { Text("关闭", color = YMColors.Lime) } },
            )
        }

        // Sleep Timer Dialog
        if (showSleep) {
            AlertDialog(
                onDismissRequest = { showSleep = false },
                containerColor = Color(0xFF141418),
                title = { Text("睡眠定时（最后 30 秒平滑淡出）", color = Color.White, fontSize = 15.sp) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(null to "关闭定时", 15 to "15 分钟", 30 to "30 分钟", 45 to "45 分钟", 60 to "60 分钟").forEach { (mins, label) ->
                            Text(
                                text = label,
                                color = Color.White,
                                fontSize = 14.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.White.copy(alpha = 0.07f))
                                    .clickable {
                                        player.setSleepTimer(mins)
                                        showSleep = false
                                    }
                                    .padding(12.dp),
                            )
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showSleep = false }) { Text("取消", color = YMColors.Lime) } },
            )
        }
    }
}

@Composable
private fun NowPlayingMetaAndTransport(
    track: MusicTrack,
    fav: Boolean,
    store: MusicStore,
    player: MusicPlayerController,
    isLyricsVisible: Boolean = false,
    onToggleLyrics: (() -> Unit)? = null,
    onOpenAlts: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenSleep: () -> Unit,
) {
    val go = LocalNavigate.current
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(track.title, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "${track.primaryArtistName} ▸",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.82f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable {
                            player.showFullPlayer = false
                            go(MusicRoutes.artist(track.primaryArtistName, track.artistId, track.source.id, track.coverURL))
                        },
                    )
                    if (track.album.isNotBlank()) {
                        Text("·", color = Color.White.copy(alpha = 0.4f), fontSize = 13.sp)
                        Text(
                            "${track.album} ▸",
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.65f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable {
                                player.showFullPlayer = false
                                go(MusicRoutes.album(track.album, track.albumId, track.primaryArtistName, track.artistId, track.source.id, track.coverURL))
                            },
                        )
                    }
                }
            }
            if (onToggleLyrics != null) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (isLyricsVisible) YMColors.Lime else Color.White.copy(alpha = 0.14f))
                        .clickable { onToggleLyrics() }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(
                        if (isLyricsVisible) "封面" else "歌词",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isLyricsVisible) Color.Black else Color.White,
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
            IconButton(onClick = { store.toggleFavorite(track) }) {
                Icon(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "红心", tint = if (fav) YMColors.Pink else Color.White)
            }
            IconButton(onClick = onOpenAlts) {
                Icon(Icons.Filled.SwapHoriz, "换源", tint = YMColors.Lime)
            }
        }

        player.statusMessage?.let { msg ->
            Text(msg, fontSize = 11.5.sp, color = YMColors.Lime, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        val dur = player.duration.coerceAtLeast(1.0)
        Slider(
            value = (player.currentTime / dur).toFloat().coerceIn(0f, 1f),
            onValueChange = { frac -> player.seekTo(frac * dur) },
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = YMColors.Lime,
                inactiveTrackColor = Color.White.copy(alpha = 0.22f),
            ),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(fmtSec(player.currentTime), fontSize = 11.sp, color = Color.White.copy(alpha = 0.6f))
            Text(fmtSec(dur), fontSize = 11.sp, color = Color.White.copy(alpha = 0.6f))
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { player.playMode = player.playMode.next }) {
                val icon = when (player.playMode) {
                    MusicPlayMode.LOOP_ALL -> Icons.Filled.Repeat
                    MusicPlayMode.LOOP_ONE -> Icons.Filled.RepeatOne
                    MusicPlayMode.SHUFFLE -> Icons.Filled.Shuffle
                }
                Icon(icon, player.playMode.label, tint = Color.White.copy(alpha = 0.78f))
            }
            IconButton(onClick = { player.previousTrack() }) {
                Icon(Icons.Filled.SkipPrevious, "上一首", tint = Color.White, modifier = Modifier.size(34.dp))
            }
            Box(
                Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable { player.togglePlayPause() },
                contentAlignment = Alignment.Center,
            ) {
                if (player.isLoading) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = Color.Black, strokeWidth = 2.5.dp)
                } else {
                    Icon(if (player.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "播放/暂停", tint = Color.Black, modifier = Modifier.size(32.dp))
                }
            }
            IconButton(onClick = { player.nextTrack() }) {
                Icon(Icons.Filled.SkipNext, "下一首", tint = Color.White, modifier = Modifier.size(34.dp))
            }
            Row {
                IconButton(onClick = onOpenSleep) {
                    Icon(Icons.Filled.Bedtime, "定时", tint = if (player.sleepRemainingSec != null) YMColors.Lime else Color.White.copy(alpha = 0.78f), modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onOpenQueue) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, "队列", tint = Color.White.copy(alpha = 0.78f))
                }
            }
        }
    }
}

/**
 * Isolated synced-lyrics stage:
 * - Receives plain immutable props (never reads `player.currentTime` directly, avoiding 250ms recomposition churn).
 * - Measures real viewport pixel height via `onSizeChanged` so opening lyrics or switching tracks snaps
 *   instantly and accurately to the vertical center on both Android phones & tablets.
 */
@Composable
private fun LiveLyricsPanel(
    trackId: String,
    trackTitle: String,
    trackArtist: String,
    lyrics: List<MusicLyricLine>,
    activeIndex: Int,
    isVisible: Boolean,
    showTrans: Boolean,
    showRoma: Boolean,
    onToggleTrans: () -> Unit,
    onToggleRoma: () -> Unit,
    onCloseToCover: (() -> Unit)?,
    onSeek: (Double) -> Unit,
) {
    val listState = rememberLazyListState()
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    var snappedForVisibility by remember(trackId, lyrics.size) { mutableStateOf(false) }

    fun centerOffsetPx(): Int {
        val h = if (viewportHeightPx > 0) viewportHeightPx else listState.layoutInfo.viewportSize.height
        return -((h * 0.36f).toInt().coerceAtLeast(160))
    }

    // Immediate non-animated centered jump when opened, track switches, lyrics finish loading, or layout height settles.
    LaunchedEffect(isVisible, trackId, lyrics.size, viewportHeightPx > 0) {
        if (isVisible && lyrics.isNotEmpty() && viewportHeightPx > 0) {
            val target = activeIndex.coerceIn(0, lyrics.lastIndex)
            runCatching { listState.scrollToItem(target, centerOffsetPx()) }
            snappedForVisibility = true
        } else if (!isVisible) {
            snappedForVisibility = false
        }
    }

    // Smooth scroll as lyrics advance while visible.
    LaunchedEffect(activeIndex, isVisible) {
        if (isVisible && lyrics.isNotEmpty() && snappedForVisibility && viewportHeightPx > 0) {
            val target = activeIndex.coerceIn(0, lyrics.lastIndex)
            runCatching { listState.animateScrollToItem(target, centerOffsetPx()) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onCloseToCover != null) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = 0.14f))
                        .clickable(enabled = isVisible) { onCloseToCover() }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(Icons.Filled.Album, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(13.dp))
                    Text("封面", color = Color.White.copy(alpha = 0.85f), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onToggleTrans, enabled = isVisible) {
                Text("译", color = if (showTrans) YMColors.Lime else Color.White.copy(alpha = 0.45f), fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = onToggleRoma, enabled = isVisible) {
                Text("罗", color = if (showRoma) YMColors.Lime else Color.White.copy(alpha = 0.45f), fontWeight = FontWeight.Bold)
            }
        }

        if (lyrics.isEmpty()) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clickable(enabled = isVisible && onCloseToCover != null) { onCloseToCover?.invoke() },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("暂无逐行同步歌词", color = Color.White.copy(alpha = 0.62f), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text("$trackTitle — $trackArtist", color = Color.White.copy(alpha = 0.42f), fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (onCloseToCover != null) {
                        Text("轻点返回封面", color = Color.White.copy(alpha = 0.35f), fontSize = 11.5.sp)
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                userScrollEnabled = isVisible,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { sz -> if (sz.height > 0 && sz.height != viewportHeightPx) viewportHeightPx = sz.height },
                contentPadding = PaddingValues(vertical = 120.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                itemsIndexed(lyrics, key = { _, l -> l.id }) { idx, line ->
                    val dist = kotlin.math.abs(idx - activeIndex)
                    val on = dist == 0
                    val alpha = if (on) 1f else if (dist == 1) 0.66f else if (dist == 2) 0.42f else 0.24f
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = isVisible) { onSeek(line.time) }
                            .padding(horizontal = 8.dp),
                    ) {
                        Text(
                            line.text,
                            fontSize = if (on) 21.sp else 16.5.sp,
                            fontWeight = if (on) FontWeight.ExtraBold else FontWeight.Medium,
                            color = if (on) YMColors.Lime else Color.White.copy(alpha = alpha),
                            lineHeight = if (on) 29.sp else 23.sp,
                        )
                        if (showTrans && !line.translation.isNullOrBlank()) {
                            Text(line.translation, fontSize = 13.sp, color = Color.White.copy(alpha = if (on) 0.82f else alpha * 0.8f))
                        }
                        if (showRoma && !line.romaji.isNullOrBlank()) {
                            Text(line.romaji, fontSize = 11.5.sp, color = Color.White.copy(alpha = if (on) 0.68f else alpha * 0.7f))
                        }
                    }
                }
            }
        }
    }
}

private fun fmtSec(sec: Double): String {
    val s = sec.toInt().coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}
