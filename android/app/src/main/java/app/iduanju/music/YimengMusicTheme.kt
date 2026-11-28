package app.iduanju.music

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iduanju.ui.theme.IdjThemeColors
import coil.compose.AsyncImage
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 100% Faithful Yimeng Visual DNA tokens for Compose (1:1 parity with `YimengDesignKit.swift`).
 * OLED black `#0A0A0C`, surgical acid-lime `#C8F542`, doodle pink `#FF5FA8`, warm paper `#F7F1E6`,
 * monochrome white CTAs, dashed hand-drawn borders, scribble tabs, and 33⅓ rpm vinyl platter.
 */
object YMColors {
    val Lime = Color(0xFFC8F542)
    val LimeLight = Color(0xFF3E6F00)
    val Pink = Color(0xFFFF5FA8)
    val PaperBg = Color(0xFFF7F1E6)
    val PaperInk = Color(0xFF1A1916)
    val PaperMuted = Color(0xFF6B6559)

    val dark: Boolean @Composable get() = IdjThemeColors.dark
    val base: Color @Composable get() = if (dark) Color(0xFF0A0A0C) else Color(0xFFF6F5F1)
    val card: Color @Composable get() = if (dark) Color(0xFF141418) else Color(0xFFFFFFFF)
    val fill: Color @Composable get() = if (dark) Color(0x12FFFFFF) else Color(0x0D000000)
    val fg: Color @Composable get() = if (dark) Color(0xFFFAFAFC) else Color(0xFF141417)
    val secondary: Color @Composable get() = if (dark) Color(0xFF9E9EA8) else Color(0xFF63636D)
    val tertiary: Color @Composable get() = if (dark) Color(0xFF63636E) else Color(0xFF93939E)
    val border: Color @Composable get() = if (dark) Color(0x24FFFFFF) else Color(0x1E000000)
    val accent: Color @Composable get() = if (dark) Lime else LimeLight
}

/** Yimeng signature dashed-border wobbly card. */
@Composable
fun YMDashedCard(
    modifier: Modifier = Modifier,
    corner: Dp = 18.dp,
    fillColor: Color = YMColors.card,
    borderColor: Color = YMColors.border,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(corner)
    Box(
        modifier = modifier
            .clip(shape)
            .background(fillColor)
            .drawBehind {
                val r = corner.toPx()
                drawRoundRect(
                    color = borderColor,
                    topLeft = Offset(1f, 1f),
                    size = Size(size.width - 2f, size.height - 2f),
                    cornerRadius = CornerRadius(r, r),
                    style = Stroke(
                        width = 1.2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 6f), 0f),
                    ),
                )
            },
    ) {
        content()
    }
}

/** Yimeng signature DoodleTabs with hand-drawn ScribbleCircle + lime ScribbleUnderline. */
@Composable
fun <T> YMDoodleTabs(
    items: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fg = YMColors.fg
    val sec = YMColors.secondary
    val accent = YMColors.accent
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { idx, item ->
            val on = item == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onSelect(item) }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                val drawn = remember { Animatable(if (on) 1f else 0f) }
                LaunchedEffect(on) {
                    if (on) {
                        drawn.snapTo(0.02f)
                        drawn.animateTo(1f, tween(480, easing = FastOutSlowInEasing))
                    } else {
                        drawn.snapTo(0f)
                    }
                }
                if (on && drawn.value > 0.02f) {
                    Canvas(Modifier.matchParentSize()) {
                        val w = size.width
                        val h = size.height
                        val cx = w / 2f
                        val cy = h / 2f
                        val rx = w * 0.48f
                        val ry = h * 0.44f
                        val circlePath = Path()
                        val steps = 36
                        for (i in 0..steps) {
                            val t = i.toFloat() / steps
                            val angle = -0.35 + t * (2.0 * PI + 0.35)
                            val wobble = 1.0 + 0.04 * sin(angle * 3.0 + idx)
                            val x = (cx + cos(angle) * rx * wobble).toFloat()
                            val y = (cy + sin(angle) * ry * wobble).toFloat()
                            if (i == 0) circlePath.moveTo(x, y) else circlePath.lineTo(x, y)
                        }
                        val pm = androidx.compose.ui.graphics.PathMeasure()
                        pm.setPath(circlePath, false)
                        val circleSeg = Path()
                        pm.getSegment(0f, pm.length * drawn.value.coerceIn(0f, 1f), circleSeg, true)
                        drawPath(
                            path = circleSeg,
                            color = fg.copy(alpha = 0.24f),
                            style = Stroke(width = 1.3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )
                        val uPath = Path().apply {
                            moveTo(w * 0.14f, h - 2.dp.toPx())
                            quadraticTo(w * 0.5f, h - 5.dp.toPx(), w * 0.86f, h - 2.2.dp.toPx())
                        }
                        pm.setPath(uPath, false)
                        val uSeg = Path()
                        pm.getSegment(0f, pm.length * drawn.value.coerceIn(0f, 1f), uSeg, true)
                        drawPath(
                            path = uSeg,
                            color = accent,
                            style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }
                }
                Text(
                    text = label(item),
                    fontSize = 14.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    color = if (on) fg else sec,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Rank badge with hand-drawn lime crown on #1. */
@Composable
fun YMRankBadge(rank: Int, modifier: Modifier = Modifier) {
    val accent = YMColors.accent
    val sec = YMColors.secondary
    Box(modifier.width(30.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (rank == 1) {
                Canvas(Modifier.size(width = 16.dp, height = 9.dp)) {
                    val w = size.width
                    val h = size.height
                    val p = Path().apply {
                        moveTo(w * 0.1f, h * 0.85f)
                        lineTo(w * 0.18f, h * 0.2f)
                        lineTo(w * 0.42f, h * 0.55f)
                        lineTo(w * 0.5f, h * 0.1f)
                        lineTo(w * 0.58f, h * 0.55f)
                        lineTo(w * 0.82f, h * 0.2f)
                        lineTo(w * 0.9f, h * 0.85f)
                        close()
                    }
                    drawPath(p, color = accent, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
            Text(
                text = if (rank < 10) "0$rank" else "$rank",
                fontSize = if (rank <= 3) 14.sp else 12.5.sp,
                fontWeight = if (rank <= 3) FontWeight.ExtraBold else FontWeight.SemiBold,
                color = when (rank) {
                    1 -> accent
                    2 -> YMColors.Pink
                    3 -> YMColors.fg
                    else -> sec
                },
            )
        }
    }
}

private object DirectMusicCoil {
    @Volatile
    private var cached: coil.ImageLoader? = null

    fun get(ctx: android.content.Context): coil.ImageLoader =
        cached ?: synchronized(this) {
            cached ?: coil.ImageLoader.Builder(ctx.applicationContext)
                .okHttpClient { MusicProviderHub.directClient }
                .crossfade(true)
                .build()
                .also { cached = it }
        }
}

/** Cover image with direct no-proxy Coil loader and clean placeholder fallback. */
@Composable
fun YMCoverImage(
    url: String,
    modifier: Modifier = Modifier,
    corner: Dp = 10.dp,
    isCircle: Boolean = false,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val loader = androidx.compose.runtime.remember(ctx) { DirectMusicCoil.get(ctx) }
    val shape = if (isCircle) CircleShape else RoundedCornerShape(corner)
    Box(
        modifier = modifier
            .clip(shape)
            .background(YMColors.fill),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNotBlank()) {
            AsyncImage(
                model = url,
                imageLoader = loader,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                tint = YMColors.tertiary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 2x2 Album Art Collage for Charts cards. */
@Composable
fun YMChartCollage(urls: List<String>, modifier: Modifier = Modifier, size: Dp = 74.dp) {
    val padded = (0 until 4).map { urls.getOrNull(it).orEmpty() }
    Column(
        modifier = modifier.size(size).clip(RoundedCornerShape(12.dp)),
        verticalArrangement = Arrangement.spacedBy(1.5.dp),
    ) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(1.5.dp)) {
            YMCoverImage(padded[0], Modifier.weight(1f).fillMaxSize(), corner = 0.dp)
            YMCoverImage(padded[1], Modifier.weight(1f).fillMaxSize(), corner = 0.dp)
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(1.5.dp)) {
            YMCoverImage(padded[2], Modifier.weight(1f).fillMaxSize(), corner = 0.dp)
            YMCoverImage(padded[3], Modifier.weight(1f).fillMaxSize(), corner = 0.dp)
        }
    }
}

/** Full-screen Now Playing organic ambient DreamField backdrop (cached gradients, zero 60fps redraw churn). */
@Composable
fun YMDreamField(
    coverURL: String,
    isPlaying: Boolean,
    dimForLyrics: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val loader = androidx.compose.runtime.remember(ctx) { DirectMusicCoil.get(ctx) }
    val dimAlpha by animateFloatAsState(
        targetValue = if (dimForLyrics) 0.56f else 0f,
        animationSpec = tween(180),
        label = "dreamDim",
    )
    val glowScale = if (isPlaying) 1f else 0.72f
    Box(modifier.fillMaxSize().background(Color(0xFF0A0A0C))) {
        if (coverURL.isNotBlank()) {
            AsyncImage(
                model = coverURL,
                imageLoader = loader,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.24f,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val w = size.width
                    val h = size.height
                    val limeCenter = Offset(w * 0.26f, h * 0.22f)
                    val pinkCenter = Offset(w * 0.76f, h * 0.72f)
                    val limeBrush = Brush.radialGradient(
                        colors = listOf(YMColors.Lime.copy(alpha = 0.16f), Color.Transparent),
                        center = limeCenter,
                        radius = (w * 0.72f).coerceAtLeast(1f),
                    )
                    val pinkBrush = Brush.radialGradient(
                        colors = listOf(YMColors.Pink.copy(alpha = 0.14f), Color.Transparent),
                        center = pinkCenter,
                        radius = (w * 0.68f).coerceAtLeast(1f),
                    )
                    val scrimBrush = Brush.verticalGradient(
                        listOf(Color(0x880A0A0C), Color(0x550A0A0C), Color(0xE60A0A0C)),
                    )
                    onDrawBehind {
                        drawCircle(brush = limeBrush, center = limeCenter, radius = w * 0.72f, alpha = glowScale)
                        drawCircle(brush = pinkBrush, center = pinkCenter, radius = w * 0.68f, alpha = glowScale)
                        drawRect(brush = scrimBrush)
                    }
                },
        )
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = dimAlpha }
                .background(Color(0xFF0A080E)),
        )
    }
}

/** Hand-drawn Doodle halo around Now Playing artwork (render-layer rotation, zero 60fps recomposition). */
@Composable
fun YMDoodleCoverHalo(coverURL: String, isPlaying: Boolean, size: Dp = 250.dp) {
    val spinState = if (isPlaying) {
        rememberInfiniteTransition(label = "halo").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(24000, easing = LinearEasing)),
            label = "spin",
        )
    } else {
        null
    }
    Box(Modifier.size(size + 30.dp), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = spinState?.value ?: 0f },
        ) {
            val r = (size.toPx() / 2f) + 10.dp.toPx()
            drawCircle(
                color = YMColors.Lime.copy(alpha = 0.35f),
                radius = r,
                style = Stroke(width = 1.6.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f))),
            )
            drawCircle(
                color = YMColors.Pink.copy(alpha = 0.7f),
                radius = 4.dp.toPx(),
                center = Offset(center.x + r, center.y),
            )
        }
        YMCoverImage(
            url = coverURL,
            corner = 26.dp,
            modifier = Modifier
                .size(size)
                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(26.dp)),
        )
    }
}

/** 33⅓ rpm Vinyl Turntable for Android Tablet landscape Now Playing (render-layer rotation, zero 60fps recomposition). */
@Composable
fun YMVinylTurntable(coverURL: String, isPlaying: Boolean, size: Dp = 280.dp) {
    val angleState = if (isPlaying) {
        rememberInfiniteTransition(label = "vinyl").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(18000, easing = LinearEasing)),
            label = "angle",
        )
    } else {
        null
    }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = angleState?.value ?: 0f },
        ) {
            val r = this.size.minDimension / 2f
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0xFF1F1F25), Color(0xFF0E0E12), Color(0xFF1A1A20), Color(0xFF09090C)),
                ),
                radius = r,
            )
            for (i in 1..6) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.05f),
                    radius = r * (0.44f + i * 0.08f),
                    style = Stroke(width = 0.8.dp.toPx()),
                )
            }
            drawCircle(
                color = YMColors.Lime.copy(alpha = 0.4f),
                radius = r - 1.dp.toPx(),
                style = Stroke(width = 1.4.dp.toPx()),
            )
        }
        YMCoverImage(
            url = coverURL,
            isCircle = true,
            modifier = Modifier
                .size(size * 0.44f)
                .graphicsLayer { rotationZ = angleState?.value ?: 0f }
                .border(2.dp, Color(0xFF0A0A0C), CircleShape),
        )
        Box(
            Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(Color(0xFF0A0A0C))
                .border(1.5.dp, Color.White.copy(alpha = 0.35f), CircleShape),
        )
    }
}

@Composable
fun YMQualityPill(label: String, isLossless: Boolean = false) {
    val bg = if (isLossless) YMColors.Lime.copy(alpha = 0.16f) else YMColors.fill
    val fg = if (isLossless) YMColors.accent else YMColors.secondary
    Text(
        text = label,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
