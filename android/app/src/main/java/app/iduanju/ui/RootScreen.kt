package app.iduanju.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import app.iduanju.ui.components.YMTabGlyph
import app.iduanju.ui.components.YMTabIcon
import app.iduanju.ui.components.ymDockSurface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import app.iduanju.ui.category.CategoryScreen
import app.iduanju.ui.components.GlassNavigationRail
import app.iduanju.ui.components.GlassToastHost
import app.iduanju.ui.components.GlassStyle
import app.iduanju.ui.components.GlassStylePref
import app.iduanju.ui.components.LayoutMetrics
import app.iduanju.ui.components.LocalLayoutMetrics
import app.iduanju.ui.components.LocalRootBackdrop
import app.iduanju.ui.components.Motion
import app.iduanju.ui.components.glassPill
import app.iduanju.ui.components.dockLabel
import app.iduanju.ui.components.dockVeil
import app.iduanju.ui.components.glassSurface
import app.iduanju.ui.detail.DetailScreen
import app.iduanju.ui.home.HomeScreen
import app.iduanju.ui.player.PlayerScreen
import app.iduanju.ui.profile.ProfileScreen
import app.iduanju.ui.search.SearchScreen
import app.iduanju.ui.theme.IdjThemeColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The root tab pages: they cross-fade, everything else is pushed. */
private val RootRoutes = setOf("home", "tvbox", "category", "music", "profile")

/** Lets deep rows (继续观看 · 全部) open a route without threading callbacks through every page. */
val LocalNavigate = androidx.compose.runtime.staticCompositionLocalOf<(String) -> Unit> { {} }

@Composable
fun RootScreen(initialRoute: String? = null) {
    val nav = rememberNavController()
    LaunchedEffect(initialRoute) {
        val to = initialRoute?.takeUnless { it == "home" } ?: return@LaunchedEffect
        nav.currentBackStackEntryFlow.first() // the graph is set while the first frame is laid out
        runCatching { nav.navigate(to) }
    }
    val updater = app.iduanju.IdjApp.instance.updater
    val webdav = app.iduanju.IdjApp.instance.webdav
    val rootScope = rememberCoroutineScope()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(Unit) { updater.autoCheck() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(2_200)
        webdav.autoSyncIfNeeded()
    }
    DisposableEffect(lifecycleOwner, webdav) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) {
                app.iduanju.music.AppAudioArbiter.appInForeground = true
            } else if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                app.iduanju.music.AppAudioArbiter.appInForeground = false
                rootScope.launch { webdav.autoSyncIfNeeded() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    // The first player opens without paying for codec lists and native libraries on the main thread.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1_500)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.iduanju.ui.player.PlayerWarmup.run() }
    }
    val showMusicTab = app.iduanju.IdjApp.instance.settings.showMusicTab
    val tabRoutes = remember(showMusicTab) {
        buildList {
            add("home"); add("tvbox"); add("category")
            if (showMusicTab) add("music")
            add("profile")
        }
    }
    var tab by remember { mutableIntStateOf(0) }
    var novelImmersive by remember { mutableStateOf(false) }
    val back = nav.currentBackStackEntryAsState().value
    val route = back?.destination?.route ?: "home"
    val activeTab = tabRoutes.indexOf(route).takeIf { it >= 0 } ?: tab.coerceIn(0, (tabRoutes.size - 1).coerceAtLeast(0))
    LaunchedEffect(activeTab) { tab = activeTab }
    LaunchedEffect(showMusicTab, route) {
        if (!showMusicTab && route == "music") {
            nav.navigate("home") {
                popUpTo(nav.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
    }
    val atRoot = route in RootRoutes
    /** Backdrop for the floating liquid-glass navigation bar: everything the NavHost draws. */
    val bgColor = IdjThemeColors.bg
    val backdrop = rememberLayerBackdrop {
        drawRect(bgColor)
        drawContent()
    }
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val metrics = remember(maxWidth, maxHeight, cfg.smallestScreenWidthDp) {
            LayoutMetrics(
                widthDp = maxWidth.value.toInt(),
                heightDp = maxHeight.value.toInt(),
                smallestWidthDp = cfg.smallestScreenWidthDp,
            )
        }
        val showChromeNav = atRoot && !(route == "home" && novelImmersive)
        val navItems = buildList<Pair<String, @Composable (Boolean) -> Unit>> {
            add("首页" to { on -> YMTabIcon(YMTabGlyph.HOME, size = 21.dp, active = on) })
            add("TVBox" to { on -> YMTabIcon(YMTabGlyph.TVBOX, size = 21.dp, active = on) })
            add("找剧" to { on -> YMTabIcon(YMTabGlyph.DISCOVER, size = 21.dp, active = on) })
            if (showMusicTab) {
                add("音乐" to { on -> YMTabIcon(YMTabGlyph.MUSIC, size = 21.dp, active = on) })
            }
            add("我的" to { on -> YMTabIcon(YMTabGlyph.PROFILE, size = 21.dp, active = on) })
        }
        val goHome: () -> Unit = {
            if (nav.currentBackStackEntry?.destination?.route != "home" && !nav.popBackStack("home", inclusive = false)) {
                nav.navigate("home") {
                    popUpTo(nav.graph.id) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }
        val onSelectTab: (Int) -> Unit = { i ->
            val target = tabRoutes.getOrNull(i) ?: "home"
            val cur = nav.currentBackStackEntry?.destination?.route
            if (target == "home") {
                goHome()
            } else if (cur != target) {
                if (cur == "search" || cur?.startsWith("actorSearch/") == true) nav.popBackStack()
                nav.navigate(target) {
                    popUpTo(nav.graph.startDestinationId) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
            tab = tabRoutes.indexOf(nav.currentBackStackEntry?.destination?.route).takeIf { it >= 0 } ?: i
        }

        // "home/<source>" (我的 → Telegram 频道 / 漫画) switches to the 首页 tab and shows that source page.
        val navigate: (String) -> Unit = { r ->
            if (r == "home/music") {
                val musicIdx = tabRoutes.indexOf("music")
                if (musicIdx >= 0) onSelectTab(musicIdx) else nav.navigate("music")
            } else if (r.startsWith("home/")) {
                app.iduanju.ui.home.HomeJump.label = mapOf("channels" to "频道", "manga" to "漫画")[r.removePrefix("home/")]
                tab = 0
                goHome()
            } else nav.navigate(r)
        }
        androidx.compose.runtime.CompositionLocalProvider(
            LocalNavigate provides navigate,
            LocalLayoutMetrics provides metrics,
            // Root backdrop is only active while the NavHost is recorded into it (chrome nav visible).
            LocalRootBackdrop provides (if (showChromeNav) backdrop else null),
        ) {
            Row(Modifier.fillMaxSize().background(if (route == "music") app.iduanju.music.YMColors.base else IdjThemeColors.bg)) {
                if (showChromeNav && metrics.useRail) {
                    GlassNavigationRail(
                        selected = tab,
                        items = navItems,
                        onSelect = onSelectTab,
                        backdrop = LocalRootBackdrop.current,
                        onSearch = { nav.navigate("search") },
                        searchIcon = { on -> YMTabIcon(YMTabGlyph.SEARCH, size = 20.dp, active = on) },
                    )
                }
                // NavHost content is drawn INSIDE layerBackdrop(backdrop): never hand it the root backdrop (feedback loop).
                androidx.compose.runtime.CompositionLocalProvider(LocalRootBackdrop provides null) {
                    NavHost(
                        nav,
                        startDestination = "home",
                        modifier = Modifier.weight(1f).fillMaxHeight().then(if (showChromeNav) Modifier.layerBackdrop(backdrop) else Modifier),
                        // The library default is a 700 ms cross-fade; back stack and predictive back are untouched.
                        // Keep underlying page 100% opaque when popping back from dark player / manga reader (eliminates light-mode black flash).
                        enterTransition = { if (targetState.destination.route in RootRoutes) Motion.tabEnter else Motion.pagePushEnter },
                        exitTransition = {
                            val to = targetState.destination.route
                            when {
                                to in RootRoutes -> Motion.tabExit
                                to?.startsWith("player/") == true || to?.startsWith("mangaRead/") == true -> androidx.compose.animation.ExitTransition.None
                                else -> Motion.pagePushExit
                            }
                        },
                        popEnterTransition = {
                            val from = initialState.destination.route
                            when {
                                from in RootRoutes -> Motion.tabEnter
                                from?.startsWith("player/") == true || from?.startsWith("mangaRead/") == true -> androidx.compose.animation.EnterTransition.None
                                else -> Motion.pagePopEnter
                            }
                        },
                        popExitTransition = { if (initialState.destination.route in RootRoutes) Motion.tabExit else Motion.pagePopExit },
                    ) {
                        composable("home") {
                            HomeScreen(
                                onOpenDetail = { nav.navigate(Routes.detail(it)) },
                                onSearch = { nav.navigate("search") },
                                onPlay = { nav.navigate(Routes.player(it, 0)) },
                                onOpenManga = { nav.navigate("manga/" + android.net.Uri.encode(it)) },
                                onImmersiveChanged = { novelImmersive = it },
                            )
                        }
                        composable("tvbox") {
                            app.iduanju.tvbox.TVBoxHubScreen(
                                onOpenConfig = { nav.navigate("tvbox/config") },
                                onOpenDetail = { sid -> nav.navigate(Routes.detail(sid)) },
                                onPlay = { sid, ep -> nav.navigate(Routes.player(sid, ep)) },
                            )
                        }
                        composable("tvbox/config") { app.iduanju.tvbox.TVBoxConfigScreen(onBack = { nav.popBackStack() }) }
                        composable("search") {
                            SearchScreen(
                                onOpenDetail = { nav.navigate(Routes.detail(it)) },
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable("category") {
                            CategoryScreen(
                                onOpenDetail = { nav.navigate(Routes.detail(it)) },
                                onSearch = { nav.navigate("search") },
                            )
                        }
                        composable("library/{kind}") { e ->
                            app.iduanju.ui.library.LibraryListScreen(
                                kind = e.arguments!!.getString("kind")!!,
                                onBack = { nav.popBackStack() },
                                onOpenDetail = { nav.navigate(Routes.detail(it)) },
                                onPlay = { sid, ep -> nav.navigate(Routes.player(sid, ep)) },
                            )
                        }
                        composable("profile") { ProfileScreen(onOpenDetail = { nav.navigate(Routes.detail(it)) }) }
                        composable("webdav") { app.iduanju.ui.profile.WebDAVSyncScreen(onBack = { nav.popBackStack() }) }
                        composable("hongguo-login") { app.iduanju.ui.profile.HongguoLoginScreen(onBack = { nav.popBackStack() }) }
                        composable("sources") { app.iduanju.ui.profile.SourcesScreen(onBack = { nav.popBackStack() }) }
                        composable("plugins") { app.iduanju.ui.profile.PluginsScreen(onBack = { nav.popBackStack() }) }
                        composable("novel") { app.iduanju.novel.NovelScreen(onBack = { nav.popBackStack() }) }
                        composable("music") { app.iduanju.music.MusicHomePage(isRootTab = true, onBack = { nav.popBackStack() }) }
                        composable("music/daily") { app.iduanju.music.MusicDailyRecScreen(onBack = { nav.popBackStack() }) }
                        composable("music/settings") { app.iduanju.music.MusicSettingsScreen(onBack = { nav.popBackStack() }) }
                        composable(
                            "music/artist?name={name}&id={id}&src={src}&cover={cover}",
                            arguments = listOf("name", "id", "src", "cover").map { navArgument(it) { defaultValue = "" } },
                        ) { e ->
                            val args = requireNotNull(e.arguments)
                            app.iduanju.music.MusicArtistDetailScreen(
                                name = args.getString("name").orEmpty(),
                                artistId = args.getString("id")?.takeIf { it.isNotBlank() },
                                sourceId = args.getString("src").orEmpty().ifBlank { "netease" },
                                coverHint = args.getString("cover").orEmpty(),
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable(
                            "music/album?name={name}&id={id}&artist={artist}&aid={aid}&src={src}&cover={cover}",
                            arguments = listOf("name", "id", "artist", "aid", "src", "cover").map { navArgument(it) { defaultValue = "" } },
                        ) { e ->
                            val args = requireNotNull(e.arguments)
                            app.iduanju.music.MusicAlbumDetailScreen(
                                name = args.getString("name").orEmpty(),
                                albumId = args.getString("id")?.takeIf { it.isNotBlank() },
                                artist = args.getString("artist").orEmpty(),
                                artistId = args.getString("aid")?.takeIf { it.isNotBlank() },
                                sourceId = args.getString("src").orEmpty().ifBlank { "netease" },
                                coverHint = args.getString("cover").orEmpty(),
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable("music/chart/{id}") { e ->
                            val chartId = android.net.Uri.decode(e.arguments?.getString("id").orEmpty())
                            val chart = app.iduanju.music.MusicProviderHub.findCachedChart(chartId)
                            app.iduanju.music.MusicTrackListScreen(
                                title = chart?.name ?: "榜单详情",
                                subtitle = chart?.let { "${it.updatedLabel} · ${it.tracks.size} 首" } ?: "实时榜单",
                                tracks = chart?.tracks.orEmpty(),
                                canClone = chart != null,
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable("music/playlist/{id}") { e ->
                            val plId = android.net.Uri.decode(e.arguments?.getString("id").orEmpty())
                            val pl = app.iduanju.IdjApp.instance.musicStore.findPlaylistById(plId)
                            app.iduanju.music.MusicTrackListScreen(
                                title = pl?.name ?: "歌单详情",
                                subtitle = pl?.subtitle ?: "歌单",
                                tracks = pl?.tracks.orEmpty(),
                                canClone = pl?.isCloud == true,
                                remoteSource = pl?.source,
                                remotePlaylistId = pl?.remoteId,
                                playlistId = pl?.id,
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable("licenses") { app.iduanju.ui.profile.LicensesScreen(onBack = { nav.popBackStack() }) }
                        composable("actorRank") { app.iduanju.ui.profile.ActorRankScreen(onBack = { nav.popBackStack() }) }
                        composable("actor/{id}?name={name}&avatar={avatar}&role={role}&intro={intro}",
                            arguments = listOf("name", "avatar", "role", "intro").map { navArgument(it) { defaultValue = "" } }) { entry ->
                            val args = requireNotNull(entry.arguments)
                            val actor = app.iduanju.core.hongguo.HGCelebrity(requireNotNull(args.getString("id")),
                                args.getString("name").orEmpty(), args.getString("avatar").orEmpty(),
                                args.getString("role").orEmpty(), args.getString("intro").orEmpty())
                            app.iduanju.ui.profile.ActorProfileScreen(actor, onBack = { nav.popBackStack() }, onOpen = { nav.navigate(Routes.detail(it)) })
                        }
                        composable("actorSearch/{name}") { e ->
                            SearchScreen(
                                onOpenDetail = { nav.navigate(Routes.detail(it)) },
                                initialQuery = e.arguments!!.getString("name")!!,
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable("board/{genre}/{sub}") { e -> app.iduanju.ui.category.BoardScreen(app.iduanju.ui.home.BoardSpec(app.iduanju.core.hongguo.HGRankGenre.valueOf(e.arguments!!.getString("genre")!!), app.iduanju.core.hongguo.HGRankSub.valueOf(e.arguments!!.getString("sub")!!)), onBack = { nav.popBackStack() }, onOpenDetail = { nav.navigate(Routes.detail(it)) }) }
                        composable("detail/{sid}", arguments = listOf(navArgument("sid") { type = NavType.StringType })) { e ->
                            val sid = e.arguments!!.getString("sid")!!
                            DetailScreen(
                                seriesId = sid,
                                onBack = { nav.popBackStack() },
                                onPlay = { s, ep -> nav.navigate(Routes.player(s, ep)) },
                                onOpenSeries = { nav.navigate(Routes.detail(it)) },
                            )
                        }
                        composable("manga/{id}") { e -> app.iduanju.manga.MangaDetailScreen(e.arguments!!.getString("id")!!, onBack = { nav.popBackStack() },
                                onRead = { id, i, p -> nav.navigate("mangaRead/" + android.net.Uri.encode(id) + "/$i/$p") }) }
                        composable("mangaRead/{id}/{i}/{p}", arguments = listOf(navArgument("i") { type = NavType.IntType }, navArgument("p") { type = NavType.IntType })) { e ->
                            app.iduanju.manga.MangaReaderScreen(e.arguments!!.getString("id")!!, e.arguments!!.getInt("i"), e.arguments!!.getInt("p"), onBack = { nav.popBackStack() }) }
                        composable(
                            "player/{sid}/{ep}",
                            arguments = listOf(
                                navArgument("sid") { type = NavType.StringType },
                                navArgument("ep") { type = NavType.IntType },
                            ),
                            enterTransition = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) },
                            exitTransition = { androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)) },
                            popEnterTransition = { androidx.compose.animation.EnterTransition.None },
                            popExitTransition = { androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)) },
                        ) { e ->
                            PlayerScreen(
                                seriesId = e.arguments!!.getString("sid")!!,
                                startEp = e.arguments!!.getInt("ep"),
                                onBack = { nav.popBackStack() },
                                onSwitchSeries = { sid -> nav.navigate(Routes.player(sid, 0)) { popUpTo("player/{sid}/{ep}") { inclusive = true } } },
                            )
                        }
                    }
                }
            }
        }
        val musicPlayer = app.iduanju.IdjApp.instance.musicPlayer
        val showGlobalMiniBar = musicPlayer.shouldShowGlobalMiniBar && !(route == "home" && novelImmersive)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visible = !metrics.useRail && showGlobalMiniBar,
                enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) +
                    androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(220)) { it + 32 },
                exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(140)) +
                    androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(180)) { it + 32 },
            ) {
                app.iduanju.music.MusicMiniPlayerBar(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                )
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = showChromeNav && !metrics.useRail,
                enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) +
                    androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(220)) { it + 48 },
                exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(140)) +
                    androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(180)) { it + 48 },
            ) {
                SwipeableGlassTabBar(
                    selected = tab,
                    items = navItems,
                    onSelect = onSelectTab,
                    backdrop = backdrop,
                )
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = metrics.useRail && showGlobalMiniBar,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 20.dp, bottom = 16.dp),
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(140)),
        ) {
            app.iduanju.music.MusicMiniPlayerBar(
                modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
            )
        }
        GlassToastHost(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = if (showChromeNav && !metrics.useRail) 82.dp else 28.dp),
            backdrop = if (showChromeNav) backdrop else null,
        )
        androidx.compose.runtime.CompositionLocalProvider(
            LocalNavigate provides navigate,
            LocalLayoutMetrics provides metrics,
        ) {
            app.iduanju.music.MusicNowPlayingOverlay()
        }
        app.iduanju.update.UpdateDialog(updater)
        app.iduanju.ui.components.SiteVerifyHostOverlay()
    }
}

private val DockPillShape = RoundedCornerShape(percent = 50)

/**
 * Symmetrical floating bottom Liquid Glass dock (`首页 · TVBox · 找剧 · 我的`)
 * with horizontal finger-slide tab scrubbing, sliding glass indicator pill, and haptic ticks.
 */
@Composable
private fun SwipeableGlassTabBar(
    selected: Int,
    items: List<Pair<String, @Composable (Boolean) -> Unit>>,
    onSelect: (Int) -> Unit,
    backdrop: Backdrop? = null,
    modifier: Modifier = Modifier,
) {
    val c = IdjThemeColors
    val liquid = GlassStylePref.style == GlassStyle.LIQUID
    val haptic = LocalHapticFeedback.current

    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(selected.toFloat()) }
    var hoveredIdx by remember { mutableIntStateOf(selected) }
    val latestSelected by rememberUpdatedState(selected)
    val latestOnSelect by rememberUpdatedState(onSelect)

    LaunchedEffect(selected) {
        if (!isDragging) {
            dragFraction = selected.toFloat()
            hoveredIdx = selected
        }
    }

    val activeTab = if (isDragging) hoveredIdx else selected
    val indicatorFraction by animateFloatAsState(
        targetValue = if (isDragging) dragFraction else selected.toFloat(),
        animationSpec = if (isDragging) snap() else spring(dampingRatio = 0.78f, stiffness = 420f),
        label = "dockIndicator",
    )
    val indicatorScale by animateFloatAsState(
        targetValue = if (isDragging) 1.04f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 480f),
        label = "dockIndicatorScale",
    )

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .height(58.dp)
                .ymDockSurface(DockPillShape, shadowElevation = 18.dp)
                .padding(5.dp),
            contentAlignment = Alignment.Center,
        ) {
            BoxWithConstraints(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.CenterStart,
            ) {
                val count = items.size.coerceAtLeast(1)
                val slotWidth = maxWidth / count
                val slotWidthPx = with(LocalDensity.current) { slotWidth.toPx() }.coerceAtLeast(1f)
                val maxIdx = (count - 1).toFloat()

                Box(
                    Modifier
                        .offset {
                            IntOffset((indicatorFraction.coerceIn(0f, maxIdx) * slotWidthPx).roundToInt(), 0)
                        }
                        .width(slotWidth)
                        .height(48.dp)
                        .graphicsLayer {
                            scaleX = indicatorScale
                            scaleY = indicatorScale
                        }
                        .clip(DockPillShape)
                        .background(if (c.dark) Color.White.copy(alpha = 0.11f) else Color.Black.copy(alpha = 0.08f)),
                )

                Row(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(count, slotWidthPx) {
                            if (count <= 1) return@pointerInput
                            detectHorizontalDragGestures(
                                onDragStart = { startOffset ->
                                    isDragging = true
                                    val idx = (startOffset.x / slotWidthPx).toInt().coerceIn(0, count - 1)
                                    dragFraction = (startOffset.x / slotWidthPx - 0.5f).coerceIn(0f, maxIdx)
                                    if (idx != hoveredIdx) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                    hoveredIdx = idx
                                },
                                onHorizontalDrag = { change, _ ->
                                    change.consume()
                                    val x = change.position.x
                                    dragFraction = (x / slotWidthPx - 0.5f).coerceIn(0f, maxIdx)
                                    val idx = (x / slotWidthPx).toInt().coerceIn(0, count - 1)
                                    if (idx != hoveredIdx) {
                                        hoveredIdx = idx
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                },
                                onDragEnd = {
                                    val target = hoveredIdx
                                    isDragging = false
                                    if (target != latestSelected) latestOnSelect(target)
                                },
                                onDragCancel = {
                                    isDragging = false
                                    hoveredIdx = latestSelected
                                    dragFraction = latestSelected.toFloat()
                                },
                            )
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items.forEachIndexed { i, (label, icon) ->
                        val on = i == activeTab
                        Box(
                            Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clip(DockPillShape)
                                .clickable { latestOnSelect(i) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                icon(on)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    label,
                                    color = if (on) c.accent else c.text.copy(alpha = 0.62f),
                                    fontSize = 10.sp,
                                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
