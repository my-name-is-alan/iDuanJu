package app.iduanju.ui.player

import android.app.Activity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iduanju.BuildConfig
import app.iduanju.PipHost
import app.iduanju.ui.UiEvidence
import app.iduanju.ui.components.DS
import app.iduanju.ui.components.LocalLayoutMetrics
import app.iduanju.ui.components.MiniButton
import app.iduanju.ui.theme.IdjDarkColors
import app.iduanju.ui.theme.IdjTheme
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.delay

/**
 * The player of the app: short drama, long video, TVBox VOD, TVBox live and (with [queue]) novel video books.
 *
 * [startEp] is the episode number (`HGEpisode.ep`), the channel number of live lists. Playback itself lives in
 * [PlayerSession], which survives recompositions and rotation; this composable only lays out surface, gestures, chrome
 * and panels and never reads the clock, so playback ticks do not recompose it.
 */
@Composable
fun PlayerScreen(seriesId: String, startEp: Int, onBack: () -> Unit, queue: VideoQueueSession? = null, onSwitchSeries: (String) -> Unit = {}) {
    // The player is dark in both themes.
    IdjTheme(dark = true) { PlayerContent(seriesId, startEp, onBack, queue, onSwitchSeries) }
}

@Composable
private fun PlayerContent(seriesId: String, startEp: Int, onBack: () -> Unit, queue: VideoQueueSession?, onSwitchSeries: (String) -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    val scope = rememberCoroutineScope()
    val metrics = LocalLayoutMetrics.current
    val dims = remember(metrics) { PlayerDimens(metrics) }
    val inPip by PipHost.isInPip

    val session = remember(seriesId, queue?.player) { PlayerSession(ctx.applicationContext, seriesId, startEp, queue, scope) }
    val ui = remember(seriesId) { PlayerUi(ctx.applicationContext) }
    val enhance = remember(session) { EnhanceState(ctx.applicationContext, session) }
    val nextDrama = remember(session) { NextDramaController(ctx.applicationContext, session) }
    val hud = remember { GestureHud() }
    val seekPreview = remember { SeekPreviewState() }
    val pip = remember(session, activity) { PlayerPip(ctx, activity, session, ui) }
    val switchSeries by rememberUpdatedState(onSwitchSeries)
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(Unit) {
        app.iduanju.IdjApp.instance.musicPlayer.enterImmersive("video")
        onDispose { app.iduanju.IdjApp.instance.musicPlayer.leaveImmersive("video") }
    }
    DisposableEffect(session, lifecycleOwner, activity) {
        session.beforePrepare = { enhance.sync(activity); enhance.install() }
        session.onEffectFailure = { enhance.onFailure() }
        session.onFinalEnded = { nextDrama.onFinalEnded() }
        session.hasSurface = { enhance.textureView?.isAvailable == true }
        session.inForeground = {
            val pipNow = PipHost.isInPip.value || (activity?.isInPictureInPictureMode == true)
            pipNow || lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        }
        nextDrama.onSwitchSeries = { switchSeries(it) }
        session.start()
        onDispose { session.release() }
    }
    LaunchedEffect(queue?.index, queue?.loading, queue?.failure, queue?.detail) { queue?.let(session::onQueue) }
    LaunchedEffect(session.fixture) {
        if (!session.fixture) return@LaunchedEffect
        if (UiEvidence.landscape) setLandscape(activity, ui, true)
        if (UiEvidence.switchEpisode) { delay(6_000); session.next() }
    }

    val label = rememberEpisodeLabel(session, startEp)
    DisposableEffect(lifecycleOwner, session, activity) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    val pipNow = PipHost.isInPip.value || (activity?.isInPictureInPictureMode == true)
                    if (!pipNow && !session.queued) {
                        session.player.playWhenReady = false
                        session.player.pause()
                    }
                }
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> session.syncPlayingState()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(inPip) { if (!inPip) session.syncPlayingState() }
    PlayerWindowEffects(session, ui, enhance, activity, ctx, dims.landscape)
    PlayerMediaSessionEffects(session, ctx, label)
    pip.Effects(inPip)
    nextDrama.Effects(paused = ui.panel != null)

    // First-frame handoff never outlives two seconds.
    LaunchedEffect(session.handoff) { if (session.handoff != null) { delay(2_000); session.handoff = null } }
    val scrubbing by remember { derivedStateOf { seekPreview.active } }
    LaunchedEffect(ui.showChrome, session.playing, ui.panel, scrubbing, ui.channelDrawer) {
        if (ui.showChrome && session.playing && ui.panel == null && !scrubbing && !ui.channelDrawer && !(BuildConfig.DEBUG && UiEvidence.keepChrome)) { delay(4_000); ui.showChrome = false }
    }
    // Leaving landscape closes the drawer; the panel takes over in portrait.
    LaunchedEffect(dims.landscape) { if (!dims.landscape) ui.channelDrawer = false }

    val padCapable = !inPip && dims.tablet && dims.landscape && metrics.widthDp >= 840 && !session.channelList && isPortraitMedia(session) && session.detail != null
    Row(Modifier.fillMaxSize().background(Color.Black)) {
        PlayerStage(session, ui, enhance, nextDrama, hud, seekPreview, dims, inPip, label, activity, padCapable, onBack, { pip.enter() }, onSwitchSeries, {
            val text = if (session.channelList) session.episode?.title.orEmpty() else session.detail?.title.orEmpty()
            if (text.isNotEmpty()) {
                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("title", text))
                session.hint = if (session.channelList) "已复制频道名" else "已复制剧名"
            }
        }, Modifier.weight(1f).fillMaxHeight())
        if (padCapable && ui.padDrawerOpen) PadDrawer(session, ui, onSwitchSeries)
    }
}

@Composable
private fun PlayerStage(
    session: PlayerSession, ui: PlayerUi, enhance: EnhanceState, nextDrama: NextDramaController, hud: GestureHud, seekPreview: SeekPreviewState, dims: PlayerDimens,
    inPip: Boolean, label: String, activity: Activity?, padCapable: Boolean, onBack: () -> Unit, onPip: () -> Unit, onSwitchSeries: (String) -> Unit, onCopyTitle: () -> Unit, modifier: Modifier,
) {
    BoxWithConstraints(modifier.background(Color.Black).clipToBounds()) {
        val aspect = if (constraints.maxHeight > 0) constraints.maxWidth.toFloat() / constraints.maxHeight else 0.5625f
        val height = constraints.maxHeight.toFloat()
        val controls = !inPip && (ui.showChrome || ui.panel != null || ui.channelDrawer || session.error != null || nextDrama.countdownOn)
        // One backdrop layer for every glass surface over the video; recorded only while one of them shows.
        val layer = rememberLayerBackdrop { drawRect(Color.Black); drawContent() }
        val backdrop: Backdrop? = if (controls) layer else null
        Box(Modifier.fillMaxSize().then(if (controls) Modifier.layerBackdrop(layer) else Modifier)) {
            if (padCapable) {
                val haloCover = session.detail?.cover.orEmpty()
                if (haloCover.isNotEmpty()) {
                    app.iduanju.ui.components.BlurredCover(haloCover, Modifier.fillMaxSize().scale(1.25f), alpha = 0.24f)
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.radialGradient(listOf(Color.Black.copy(0.20f), Color.Black.copy(0.86f))),
                        ),
                    )
                }
            }
            PlayerSurface(session, ui, enhance, aspect, height, nextDrama, Modifier.fillMaxSize())
            DanmakuLayer(session, ui, dims, inPip)
        }
        GestureLayer(session, ui, hud, nextDrama, activity, inPip, dims.landscape, Modifier.fillMaxSize())
        if (inPip) return@BoxWithConstraints

        PlayerStatus(session, backdrop)
        hud.Overlay(true, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = if (dims.landscape) 54.dp else 62.dp))

        val chromeOn = ui.showChrome && !ui.locked
        val fade = animateFloatAsState(if (ui.showChrome || ui.panel != null) 1f else 0f, DS.panel(), label = "chrome")
        val alpha = { fade.value }
        // Composed only while visible or fading, so a hidden HUD costs nothing.
        val present by remember { derivedStateOf { fade.value > 0.005f } }
        if (present) {
            val locked by rememberUpdatedState(ui.locked)
            val danmakuOpen by rememberUpdatedState(ui.panel == PlayerPanel.Danmaku)
            ChromeScrims({ if (locked) 0f else fade.value }, { if (locked || danmakuOpen) 0f else fade.value }, dims)
            if (!ui.locked) TopBar(session, ui, enhance, dims, label, chromeOn, padCapable, alpha, onBack, onPip)
            if (!ui.locked && ui.panel != PlayerPanel.Danmaku) BottomConsole(
                session, ui, dims, label, chromeOn, seekPreview, backdrop, alpha,
                // A tablet held in landscape has nothing to exit; only offer the toggle where it can act.
                canToggleOrientation = !(dims.tablet && dims.landscape && ui.manualLandscape != true),
                onOrientation = { setLandscape(activity, ui, !dims.landscape) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        // Lock on the left edge (landscape / tablet); while locked it is the only control (kept even if the device
        // rotated to phone portrait while locked, so it can always be undone).
        if ((dims.wide || ui.locked) && ui.panel == null && present) Box(Modifier.align(Alignment.CenterStart).padding(start = dims.pad).graphicsLayer { this.alpha = fade.value }) {
            CircleBtn(if (ui.locked) Icons.Filled.Lock else Icons.Filled.LockOpen, if (ui.locked) "解锁" else "锁屏", ui.showChrome, dims.button, dims.buttonIcon,
                tint = if (ui.locked) IdjDarkColors.accent else Color.White) {
                ui.locked = !ui.locked
                ui.showChrome = true
                session.hint = if (ui.locked) "已锁定屏幕" else "已解锁"
            }
        }
        val ready = session.status.isEmpty() && session.error == null && session.firstFrame
        CenterTransport(
            session,
            dims,
            ui.showChrome && ready && !session.centerSuppressed && !ui.locked && ui.panel == null && !ui.channelDrawer && !nextDrama.countdownOn && !session.holding,
            backdrop,
            Modifier.align(Alignment.Center),
        )
        // Immersive playback stays completely clean; timeline is shown only when full controls are visible.
        // Floating seek-bar thumbnail above the thumb while scrubbing; draw-only, no touches.
        if (!session.live) SeekPreviewOverlay(state = seekPreview, extractor = session.seekExtractor, durationProvider = { session.clock.dur },
            coverUrl = session.detail?.cover.orEmpty(), portraitVideo = isPortraitMedia(session))
        nextDrama.CountdownCard(!ui.locked, ui.panel != null, backdrop, Modifier.align(Alignment.Center).padding(horizontal = 24.dp))
        if (session.channelList) ChannelDrawer(session, ui, dims, ui.channelDrawer && !ui.locked, backdrop, Modifier.align(Alignment.CenterEnd))
        PanelHost(session, ui, enhance, nextDrama, dims, inPip, backdrop, onSwitchSeries, onCopyTitle)
    }
}

/** Comments: lanes under the top bar / cutout, never into the bottom controls. */
@Composable
private fun DanmakuLayer(session: PlayerSession, ui: PlayerUi, dims: PlayerDimens, inPip: Boolean) {
    val preview = ui.panel == PlayerPanel.Danmaku
    val ep = session.episode
    val rawTop = with(LocalDensity.current) { WindowInsets.statusBars.getTop(this).toDp() }
    var top by remember { mutableStateOf(0.dp) }
    if (rawTop > top) top = rawTop
    if (inPip || session.channelList || !(preview || (ui.danmaku && ep != null && session.danmakuCapable))) return
    DanmakuOverlay(
        player = session.player,
        seriesId = session.seriesId,
        vid = ep?.vid.orEmpty(),
        laneTop = if (dims.landscape) 16.dp else top + (if (dims.tablet) 60.dp else 48.dp),
        bottomLimit = if (dims.landscape) 96.dp else 210.dp,
        previewActive = preview,
        externalUrl = session.danmakuUrl,
        externalLan = session.stream?.lan ?: true,
        playing = session.playing,
    ) { android.util.Log.i("IDJ", "danmaku: $it") }
}

/** Large tablets in landscape with a portrait drama: frameless smoked-glass side drawer beside the ambient stage. */
@Composable
private fun PadDrawer(session: PlayerSession, ui: PlayerUi, onSwitchSeries: (String) -> Unit) {
    val d = session.detail ?: return
    val pc = IdjDarkColors
    Box(Modifier.width(340.dp).fillMaxHeight().background(Color(0xFF0E1015))) {
        if (d.cover.isNotEmpty()) {
            app.iduanju.ui.components.BlurredCover(d.cover, Modifier.fillMaxSize().scale(1.25f), alpha = 0.14f)
        }
        Box(Modifier.align(Alignment.CenterStart).width(1.dp).fillMaxHeight().background(Color.White.copy(0.10f)))
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(d.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val cur = (session.episode?.ep ?: 1)
                    val total = d.episodes.size
                    if (total > 1) {
                        Text("第 $cur / $total 集", color = Color.White.copy(0.56f), fontSize = 12.sp, maxLines = 1)
                    }
                }
                Spacer(Modifier.width(8.dp))
                MiniButton("收起") { ui.padDrawerOpen = false }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(0.08f)))
            Column(Modifier.weight(1f)) { EpisodesPanel(session, onPick = { session.select(it) }, onSeason = onSwitchSeries) }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(0.08f)))
            Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("当前解码与音轨", color = Color.White.copy(0.54f), fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                val t = session.tags
                Text(
                    listOfNotNull(t.res, t.range, t.codec, t.audio).joinToString(" · ").ifEmpty { "Media3 + FFmpeg 软解就绪" },
                    color = pc.accent,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
