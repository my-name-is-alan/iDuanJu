package app.iduanju.novel

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.media3.common.Player
import app.iduanju.core.hongguo.HGEpisode
import app.iduanju.core.hongguo.HGSeriesDetail
import app.iduanju.core.novel.LegadoMedia
import app.iduanju.core.util.NetGuard
import app.iduanju.ui.components.*
import app.iduanju.ui.player.PlayerScreen
import app.iduanju.ui.player.VideoQueueSession
import app.iduanju.ui.theme.IdjThemeColors
import coil.compose.AsyncImage
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Defense before TCP connect (network interceptors run after connect, and IP literals may skip Dns). */
internal class GuardedMediaSocketFactory:SocketFactory(){
 override fun createSocket():Socket=object:Socket(){
  override fun connect(endpoint:SocketAddress?,timeout:Int){
   val target=endpoint as? InetSocketAddress
   if(target?.address==null || target.port !in listOf(80,443) || NetGuard.isBlockedIP(target.address.hostAddress.orEmpty()))throw IOException("媒体连接拒绝内网地址或非标准端口")
   super.connect(endpoint,timeout)
  }
 }
 override fun createSocket(host:String,port:Int):Socket=createSocket().apply{connect(InetSocketAddress(host,port))}
 override fun createSocket(host:String,port:Int,local:InetAddress,localPort:Int):Socket=createSocket().apply{bind(InetSocketAddress(local,localPort));connect(InetSocketAddress(host,port))}
 override fun createSocket(host:InetAddress,port:Int):Socket=createSocket().apply{connect(InetSocketAddress(host,port))}
 override fun createSocket(host:InetAddress,port:Int,local:InetAddress,localPort:Int):Socket=createSocket().apply{bind(InetSocketAddress(local,localPort));connect(InetSocketAddress(host,port))}
}

/** Validate each request, not just DNS lookup: OkHttp may bypass Dns for IP-literal redirects. */
internal fun guardedMediaRequest(req:okhttp3.Request,origin:String):okhttp3.Request {
 LegadoMedia.checkedURL(req.url.toString())
 return if(LegadoMedia.sameOrigin(req.url.toString(),origin))req else req.newBuilder().removeHeader("Authorization").removeHeader("Cookie").build()
}

@Composable
fun NovelMediaPlayer(state: NovelPlayback.State, onClose: () -> Unit) {
    val player = NovelPlayback.exo ?: return
    DisposableEffect(Unit) {
        app.iduanju.IdjApp.instance.musicPlayer.enterImmersive("novelAudio")
        onDispose { app.iduanju.IdjApp.instance.musicPlayer.leaveImmersive("novelAudio") }
    }
    val lifecycle = LocalView.current.findViewTreeLifecycleOwner()?.lifecycle
    DisposableEffect(lifecycle, player, state.video) {
        val observer = LifecycleEventObserver { _, event -> if (state.video && event == Lifecycle.Event.ON_STOP) player.pause() }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    DisposableEffect(player) { onDispose { if (NovelPlayback.state?.video == true) NovelPlayback.stop() } }
    BackHandler(onBack = onClose)
    // Written 4×/s: read in the status semantics and, through lambdas, in the audiobook timeline — never in this scope.
    var position by remember(state.index) { mutableLongStateOf(0) }
    var duration by remember(state.index) { mutableLongStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    LaunchedEffect(player, state.index) {
        while (isActive) {
            position = player.currentPosition
            duration = player.duration.coerceAtLeast(0)
            playing = player.isPlaying
            delay(250)
        }
    }
    Box(Modifier.fillMaxSize().testTag("novel.media.player")) {
        Box(
            Modifier.fillMaxSize().testTag("novel.media.status").semantics {
                stateDescription = "已播放 %.2f 秒 · %s".format(java.util.Locale.ROOT, position / 1000.0, if (playing) "播放中" else "暂停")
            },
        ) {
            if (state.video) {
                val detail = remember(state.queue) {
                    val episodes = state.queue.titles.mapIndexed { i, title -> HGEpisode(i + 1, i.toString(), title = title) }
                    HGSeriesDetail(seriesId = "novel:" + state.queue.key, title = state.queue.book, episodes = episodes,
                        totalEps = episodes.size, source = "视频书", cover = state.queue.cover)
                }
                val loading = state.media == null && state.failure == null
                val session = remember(player, detail, state.index, loading, state.failure) {
                    VideoQueueSession(player, detail, state.index, loading, state.failure, NovelPlayback::select)
                }
                PlayerScreen(detail.seriesId, state.index + 1, onClose, session)
            } else {
                AudioBookPlayer(state, { position }, { duration }, { playing }, onClose, onSeek = { player.seekTo(it); position = it })
            }
        }
    }
}

private fun clock(ms: Long) = "%d:%02d".format(ms / 60000, ms / 1000 % 60)

private val Tabular = TextStyle(fontFeatureSettings = "tnum")

/** [position] / [duration] / [playing] are read in the timeline and the play button only, so the poll never recomposes the page. */
@Composable
private fun AudioBookPlayer(
    state: NovelPlayback.State,
    position: () -> Long,
    duration: () -> Long,
    playing: () -> Boolean,
    onClose: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    val player = NovelPlayback.player ?: return
    val c = IdjThemeColors
    var listOpen by remember { mutableStateOf(false) }
    val ground = c.bg
    val backdrop = rememberLayerBackdrop {
        drawRect(ground)
        drawContent()
    }
    Box(Modifier.fillMaxSize().background(c.bg)) {
        // The only blurred layer of the page; the nav and the play button sample it.
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) { AudioGround(state.queue.cover) }
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassIconButton(Icons.Rounded.KeyboardArrowDown, "关闭播放", backdrop = backdrop, size = 44.dp, iconSize = 24.dp, onClick = onClose)
                Text("有声书", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                GlassIconButton(Icons.AutoMirrored.Rounded.QueueMusic, "章节列表", backdrop = backdrop, size = 44.dp, iconSize = 22.dp, onClick = { listOpen = true })
            }
            BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 26.dp)) {
                if (maxWidth > maxHeight) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(26.dp), verticalAlignment = Alignment.CenterVertically) {
                        AudioCover(state.queue.book, state.queue.cover, Modifier.weight(.85f).fillMaxHeight())
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { AudioControls(state, position, duration, playing, backdrop, player, onSeek) }
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        AudioCover(state.queue.book, state.queue.cover, Modifier.weight(1f).fillMaxWidth())
                        AudioControls(state, position, duration, playing, backdrop, player, onSeek)
                    }
                }
            }
        }
    }
    if (listOpen) AudioChapterSheet(state) { listOpen = false }
}

/** Cover colours over the page ground; without a cover the cool ambient glow of the other novel pages. */
@Composable
private fun AudioGround(cover: String) {
    val c = IdjThemeColors
    Box(Modifier.fillMaxSize().clipToBounds().dsAmbient(cool = true)) {
        if (cover.isBlank()) return@Box
        NovelBlurredBackdrop(cover, Modifier.fillMaxSize(), radius = 24.dp, alpha = if (c.dark) 0.80f else 0.62f)
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to c.bg.copy(alpha = if (c.dark) 0.34f else 0.46f),
                    0.5f to c.bg.copy(alpha = 0.62f),
                    1f to c.bg.copy(alpha = 0.94f),
                ),
            ),
        )
    }
}

@Composable
private fun AudioCover(book: String, cover: String, modifier: Modifier) {
    val c = IdjThemeColors
    val shape = RoundedCornerShape(20.dp)
    val (c1, c2) = remember(book) { bookCoverBaseColor(book) }
    Box(modifier.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.sizeIn(maxWidth = 320.dp, maxHeight = 320.dp).aspectRatio(1f)
                .shadow(22.dp, shape, ambientColor = Color.Black.copy(0.40f), spotColor = Color.Black.copy(0.40f))
                .clip(shape).background(Brush.linearGradient(listOf(c1, c2))),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val radius = size.minDimension / 2
                drawCircle(Color.Black.copy(alpha = .42f), radius * .87f)
                for (ring in 4..12) drawCircle(Color.White.copy(alpha = .10f), radius * ring / 15, style = Stroke(width = 1.dp.toPx()))
                drawCircle(c.accent, radius * .31f)
            }
            Icon(Icons.Rounded.Headphones, null, Modifier.fillMaxSize(.15f), tint = c.onAccent)
            if (cover.isNotBlank()) AsyncImage(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.matchParentSize().border(0.8.dp, Color.White.copy(0.16f), shape))
        }
    }
}

@Composable
private fun AudioControls(
    state: NovelPlayback.State,
    position: () -> Long,
    duration: () -> Long,
    playing: () -> Boolean,
    backdrop: Backdrop,
    player: Player,
    onSeek: (Long) -> Unit,
) {
    val c = IdjThemeColors
    val loading = state.media == null && state.failure == null
    Column(Modifier.fillMaxWidth()) {
        Text(state.title, color = c.text, fontSize = 22.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(state.queue.book, color = c.secondary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        state.failure?.let {
            GlassTextButton("$it · 重试", Modifier.padding(top = 4.dp).testTag("novel.media.error"), color = c.danger, icon = Icons.Rounded.Refresh) { NovelPlayback.select(state.index) }
        }
        Spacer(Modifier.height(14.dp))
        AudioTimeline(state.index, state.media != null, position, duration, onSeek)
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(34.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlainIconButton(Icons.Rounded.SkipPrevious, "上一章", Modifier.size(56.dp), iconSize = 36.dp, enabled = state.hasPrevious, onClick = NovelPlayback::previous)
            AudioPlayButton(loading, state.media != null && state.failure == null, playing, backdrop) {
                if (playing()) player.pause() else {
                    if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                    player.play()
                }
            }
            PlainIconButton(Icons.Rounded.SkipNext, "下一章", Modifier.size(56.dp), iconSize = 36.dp, enabled = state.hasNext, onClick = NovelPlayback::next)
        }
    }
}

@Composable
private fun AudioTimeline(index: Int, ready: Boolean, position: () -> Long, duration: () -> Long, onSeek: (Long) -> Unit) {
    val c = IdjThemeColors
    var drag by remember(index) { mutableStateOf<Float?>(null) }
    val total = duration()
    val shown = drag?.let { (it * total).toLong() } ?: position()
    Column(Modifier.fillMaxWidth()) {
        GlassSlider(
            value = drag ?: (shown.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f),
            onValueChange = { drag = it },
            enabled = total > 0 && ready,
            onValueChangeFinished = { drag?.let { onSeek((it * total).toLong()) }; drag = null },
            label = "播放进度",
            valueText = { "${clock(shown)} / ${clock(total)}" },
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(clock(shown), color = c.secondary, fontSize = 12.sp, style = Tabular)
            Text(clock(total), color = c.secondary, fontSize = 12.sp, style = Tabular)
        }
    }
}

@Composable
private fun AudioPlayButton(loading: Boolean, enabled: Boolean, playing: () -> Boolean, backdrop: Backdrop, onClick: () -> Unit) {
    val c = IdjThemeColors
    val src = remember { MutableInteractionSource() }
    Box(
        Modifier.size(76.dp).pressScale(src, 0.94f)
            .graphicsLayer { alpha = if (enabled || loading) 1f else 0.45f }
            .glassSurface(CircleShape, backdrop = backdrop, shadowRadius = 18.dp, activeOverlay = c.accent.copy(alpha = 0.85f))
            .clickable(src, null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) GlassSpinner(size = 28.dp, color = c.onAccent)
        else {
            val on = playing()
            Icon(if (on) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (on) "暂停" else "播放", Modifier.size(38.dp), tint = c.onAccent)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioChapterSheet(state: NovelPlayback.State, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val titles = state.queue.titles
    val list = rememberLazyListState((state.index - 3).coerceAtLeast(0))
    GlassBottomSheet(onDismiss, title = "章节列表", sheetState = sheet, contentPadding = PaddingValues(horizontal = 12.dp)) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.64f), state = list) {
            items(titles.size, key = { it }, contentType = { "chapter" }) { i ->
                SelectRow(titles[i], selected = i == state.index) {
                    if (i != state.index) NovelPlayback.select(i)
                    scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss() }
                }
            }
        }
    }
}
