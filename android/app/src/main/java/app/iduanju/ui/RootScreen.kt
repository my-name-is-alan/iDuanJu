package app.iduanju.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

/** The four tab pages: they cross-fade, everything else is pushed. */
private val RootRoutes = setOf("home", "tvbox", "category", "profile")

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
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
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
    var tab by remember { mutableIntStateOf(0) }
    var novelImmersive by remember { mutableStateOf(false) }
    val back = nav.currentBackStackEntryAsState().value
    val route = back?.destination?.route ?: "home"
    val activeTab = when (route) {
        "home" -> 0
        "tvbox" -> 1
        "category" -> 2
        "profile" -> 3
        else -> tab
    }
    LaunchedEffect(activeTab) { tab = activeTab }
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
        var dockHidden by remember { mutableStateOf(false) }
        val dockRestoreJob = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
        LaunchedEffect(route, showChromeNav) {
            dockRestoreJob[0]?.cancel()
            dockHidden = false
        }
        val dockScroll = remember(rootScope) {
            object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
                override fun onPostScroll(
                    consumed: androidx.compose.ui.geometry.Offset,
                    available: androidx.compose.ui.geometry.Offset,
                    source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
                ): androidx.compose.ui.geometry.Offset {
                    if (consumed.y < -10f) {
                        if (!dockHidden) dockHidden = true
                        dockRestoreJob[0]?.cancel()
                    } else if (consumed.y > 10f) {
                        dockRestoreJob[0]?.cancel()
                        if (dockHidden) dockHidden = false
                    }
                    return androidx.compose.ui.geometry.Offset.Zero
                }

                override suspend fun onPostFling(
                    consumed: androidx.compose.ui.unit.Velocity,
                    available: androidx.compose.ui.unit.Velocity,
                ): androidx.compose.ui.unit.Velocity {
                    if (dockHidden) {
                        dockRestoreJob[0]?.cancel()
                        dockRestoreJob[0] = rootScope.launch {
                            kotlinx.coroutines.delay(650)
                            dockHidden = false
                        }
                    }
                    return androidx.compose.ui.unit.Velocity.Zero
                }
            }
        }
        val navItems = listOf<Pair<String, @Composable (Boolean) -> Unit>>(
            "首页" to { on -> Icon(Icons.Filled.Home, null, Modifier.size(20.dp), tint = if (on) IdjThemeColors.text else dockLabel()) },
            "TVBox" to { on -> Icon(Icons.Filled.LiveTv, null, Modifier.size(20.dp), tint = if (on) IdjThemeColors.text else dockLabel()) },
            "找剧" to { on -> Icon(Icons.Filled.GridView, null, Modifier.size(20.dp), tint = if (on) IdjThemeColors.text else dockLabel()) },
            "我的" to { on -> Icon(Icons.Filled.Person, null, Modifier.size(20.dp), tint = if (on) IdjThemeColors.text else dockLabel()) },
        )
        val tabRoutes = listOf("home", "tvbox", "category", "profile")
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
            tab = when (nav.currentBackStackEntry?.destination?.route) {
                "home" -> 0; "tvbox" -> 1; "category" -> 2; "profile" -> 3; else -> i
            }
        }

        // "home/<source>" (我的 → Telegram 频道 / 漫画) switches to the 首页 tab and shows that source page.
        val navigate: (String) -> Unit = { r ->
            if (r.startsWith("home/")) {
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
            Row(Modifier.fillMaxSize().background(IdjThemeColors.bg).then(if (showChromeNav && !metrics.useRail) Modifier.nestedScroll(dockScroll) else Modifier)) {
                if (showChromeNav && metrics.useRail) {
                    GlassNavigationRail(
                        selected = tab,
                        items = navItems,
                        onSelect = onSelectTab,
                        backdrop = LocalRootBackdrop.current,
                        onSearch = { nav.navigate("search") },
                        searchIcon = { on -> Icon(Icons.Filled.Search, null, Modifier.size(20.dp), tint = if (on) IdjThemeColors.accent else IdjThemeColors.text) },
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
        androidx.compose.animation.AnimatedVisibility(
            visible = showChromeNav && !metrics.useRail && !dockHidden,
            modifier = Modifier.align(Alignment.BottomCenter),
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
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 6.dp),
            )
        }
        GlassToastHost(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = if (showChromeNav && !metrics.useRail) 82.dp else 28.dp),
            backdrop = if (showChromeNav) backdrop else null,
        )
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
                .widthIn(max = 380.dp)
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .height(58.dp)
                .glassSurface(shape = DockPillShape, backdrop = backdrop, shadowRadius = 18.dp, veil = dockVeil())
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
                        .then(
                            if (liquid) Modifier.glassPill(DockPillShape, selected = true, neutralSelected = true)
                            else Modifier.clip(DockPillShape).background(if (c.dark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.08f)),
                        ),
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
                                    color = if (on) c.text else dockLabel(),
                                    fontSize = 11.sp,
                                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
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
