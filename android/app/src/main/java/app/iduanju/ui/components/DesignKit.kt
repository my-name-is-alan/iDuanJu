package app.iduanju.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import app.iduanju.ui.theme.IdjThemeColors
import coil.compose.AsyncImage
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect

// i短剧 redesign building blocks — Compose twin of iOS DesignKit.swift. Every size is the spec's pt value as dp.

object DS {
    /** Selection (tabs / segments / chips) and panel springs; same feel as the iOS springs. */
    fun <T> select() = spring<T>(dampingRatio = 0.86f, stiffness = 380f)
    fun <T> panel() = spring<T>(dampingRatio = 0.88f, stiffness = 230f)
    fun <T> press() = spring<T>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)

    /** "512.6 万", "1.2 亿". */
    fun count(n: Long): String = when {
        n >= 100_000_000 -> String.format("%.1f 亿", n / 100_000_000.0)
        n >= 10_000 -> String.format("%.1f 万", n / 10_000.0)
        else -> n.toString()
    }

    /** "更新至24集" → "更新至 24 集". */
    fun spaced(t: String): String {
        val out = StringBuilder(); var prev: Boolean? = null
        for (ch in t) {
            val d = ch.isDigit() || ch == '.'
            if (prev != null && prev != d && ch != ' ' && out.lastOrNull() != ' ') out.append(' ')
            out.append(ch); prev = if (ch == ' ') null else d
        }
        return out.toString()
    }
}

// MARK: surfaces

/** `.card`: Yimeng hand-drawn dashed sketch card border + warm/obsidian surface. */
@Composable
fun Modifier.dsCard(radius: Dp = 20.dp): Modifier {
    val c = IdjThemeColors; val s = RoundedCornerShape(radius)
    return this
        .then(if (!c.dark) Modifier.shadow(12.dp, s, ambientColor = c.shadowSoft, spotColor = Color.Black.copy(0.08f)) else Modifier)
        .clip(s).background(c.card)
        .drawWithContent {
            drawContent()
            val sw = 1.15.dp.toPx()
            val cr = radius.toPx()
            drawRoundRect(
                color = c.cardBorder,
                topLeft = Offset(sw / 2f, sw / 2f),
                size = androidx.compose.ui.geometry.Size(size.width - sw, size.height - sw),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(cr, cr),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = sw,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                ),
            )
        }
}

/** Draws a dashed pill outline (Yimeng hand-drawn sketch rim). */
internal fun Modifier.dashedCapsuleBorder(color: Color, width: Dp = 1.dp, dash: Dp = 5.dp, gap: Dp = 3.5.dp): Modifier =
    this.drawWithContent {
        drawContent()
        val sw = width.toPx()
        val r = size.height / 2f
        drawRoundRect(
            color = color,
            topLeft = Offset(sw / 2f, sw / 2f),
            size = androidx.compose.ui.geometry.Size(size.width - sw, size.height - sw),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = sw,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(dash.toPx(), gap.toPx())),
            ),
        )
    }

/** Draws Yimeng's organic hand-drawn scribble ellipse around a badge, icon well, or tab item. */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawScribbleEllipse(
    color: Color,
    strokeWidth: Float,
    seed: Int = 19,
    inset: Float = 0f,
) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val rx = (size.width / 2f - inset).coerceAtLeast(1f)
    val ry = (size.height / 2f - inset).coerceAtLeast(1f)
    val path = androidx.compose.ui.graphics.Path()
    val steps = 36
    for (i in 0..steps) {
        val t = i.toFloat() / steps
        val a = (t * 2.0 * kotlin.math.PI * 1.08 - 0.35)
        val wob = (1.0 + 0.045 * kotlin.math.sin(a * 3.0 + seed)).toFloat()
        val x = cx + (kotlin.math.cos(a).toFloat() * wob) * rx
        val y = cy + (kotlin.math.sin(a).toFloat() * wob) * ry
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color = color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round))
}

@Composable
internal fun DoodleSparkle(color: Color, size: Dp = 11.dp, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.size(size)) {
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val rx = this.size.width * 0.48f
        val ry = this.size.height * 0.48f
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(cx, cy - ry)
            quadraticTo(cx + rx * 0.14f, cy - ry * 0.14f, cx + rx, cy)
            quadraticTo(cx + rx * 0.14f, cy + ry * 0.14f, cx, cy + ry)
            quadraticTo(cx - rx * 0.14f, cy + ry * 0.14f, cx - rx, cy)
            quadraticTo(cx - rx * 0.14f, cy - ry * 0.14f, cx, cy - ry)
            close()
        }
        drawPath(p, color = color)
    }
}

@Composable
fun DoodleCrayonWave(
    color: Color,
    waves: Int = 3,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 1.9.dp,
    durationMs: Int = 2600,
    active: Boolean = true,
) {
    val phase = if (active) {
        val transition = rememberInfiniteTransition(label = "crayonWave")
        val p by transition.animateFloat(
            initialValue = 0f,
            targetValue = (2.0 * kotlin.math.PI).toFloat(),
            animationSpec = infiniteRepeatable(animation = tween(durationMs, easing = LinearEasing)),
            label = "wavePhase",
        )
        p
    } else 0f
    androidx.compose.foundation.Canvas(modifier) {
        val count = (waves * 14).coerceAtLeast(24)
        val path = androidx.compose.ui.graphics.Path()
        for (i in 0..count) {
            val t = i.toFloat() / count
            val x = t * size.width
            val y = size.height / 2f + kotlin.math.sin(t * kotlin.math.PI.toFloat() * 2f * waves - phase) * (size.height * 0.36f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val sw = strokeWidth.toPx()
        translate(0.7.dp.toPx(), 0.8.dp.toPx()) {
            drawPath(
                path,
                color = color.copy(alpha = color.alpha * 0.32f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = sw * 0.85f,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round,
                ),
            )
        }
        drawPath(
            path,
            color = color,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = sw,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round,
            ),
        )
    }
}

/** Unified Yimeng hand-drawn double-pass crayon tab glyphs (1:1 with iOS `YMTabGlyph`). */
enum class YMTabGlyph {
    HOME, TVBOX, DISCOVER, MUSIC, PROFILE, SEARCH;

    fun subpaths(s: Float): List<androidx.compose.ui.graphics.Path> = when (this) {
        HOME -> listOf(
            androidx.compose.ui.graphics.Path().apply {
                moveTo(0.50f * s, 0.14f * s)
                lineTo(0.88f * s, 0.46f * s)
                lineTo(0.78f * s, 0.46f * s)
                lineTo(0.78f * s, 0.86f * s)
                lineTo(0.56f * s, 0.86f * s)
                lineTo(0.56f * s, 0.62f * s)
                lineTo(0.44f * s, 0.62f * s)
                lineTo(0.44f * s, 0.86f * s)
                lineTo(0.22f * s, 0.86f * s)
                lineTo(0.22f * s, 0.46f * s)
                lineTo(0.12f * s, 0.46f * s)
                close()
            },
        )
        TVBOX -> listOf(
            androidx.compose.ui.graphics.Path().apply {
                moveTo(0.34f * s, 0.14f * s); lineTo(0.50f * s, 0.30f * s)
            },
            androidx.compose.ui.graphics.Path().apply {
                moveTo(0.66f * s, 0.14f * s); lineTo(0.50f * s, 0.30f * s)
            },
            androidx.compose.ui.graphics.Path().apply {
                addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(
                        left = 0.14f * s, top = 0.30f * s, right = 0.86f * s, bottom = 0.84f * s,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(0.14f * s, 0.14f * s),
                    ),
                )
            },
            androidx.compose.ui.graphics.Path().apply {
                moveTo(0.44f * s, 0.47f * s)
                lineTo(0.62f * s, 0.57f * s)
                lineTo(0.44f * s, 0.67f * s)
                close()
            },
        )
        DISCOVER -> {
            val r = androidx.compose.ui.geometry.CornerRadius(0.08f * s, 0.08f * s)
            listOf(
                androidx.compose.ui.graphics.Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(0.16f * s, 0.16f * s, 0.44f * s, 0.44f * s, r)) },
                androidx.compose.ui.graphics.Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(0.56f * s, 0.16f * s, 0.84f * s, 0.44f * s, r)) },
                androidx.compose.ui.graphics.Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(0.16f * s, 0.56f * s, 0.44f * s, 0.84f * s, r)) },
                androidx.compose.ui.graphics.Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(0.56f * s, 0.56f * s, 0.84f * s, 0.84f * s, r)) },
            )
        }
        MUSIC -> listOf(
            androidx.compose.ui.graphics.Path().apply { addOval(androidx.compose.ui.geometry.Rect(0.17f * s, 0.63f * s, 0.39f * s, 0.81f * s)) },
            androidx.compose.ui.graphics.Path().apply { addOval(androidx.compose.ui.geometry.Rect(0.55f * s, 0.56f * s, 0.77f * s, 0.74f * s)) },
            androidx.compose.ui.graphics.Path().apply {
                moveTo(0.38f * s, 0.71f * s)
                lineTo(0.38f * s, 0.22f * s)
                lineTo(0.76f * s, 0.16f * s)
                lineTo(0.76f * s, 0.64f * s)
            },
        )
        PROFILE -> listOf(
            androidx.compose.ui.graphics.Path().apply { addOval(androidx.compose.ui.geometry.Rect(0.32f * s, 0.13f * s, 0.68f * s, 0.49f * s)) },
            androidx.compose.ui.graphics.Path().apply {
                moveTo(0.16f * s, 0.87f * s)
                quadraticTo(0.50f * s, 0.49f * s, 0.84f * s, 0.87f * s)
            },
        )
        SEARCH -> listOf(
            androidx.compose.ui.graphics.Path().apply { addOval(androidx.compose.ui.geometry.Rect(0.18f * s, 0.14f * s, 0.70f * s, 0.66f * s)) },
            androidx.compose.ui.graphics.Path().apply {
                moveTo(0.62f * s, 0.58f * s)
                lineTo(0.86f * s, 0.84f * s)
            },
        )
    }
}

/** Double-pass hand-drawn crayon icon with animated stroke draw-in when activated. */
@Composable
fun YMTabIcon(glyph: YMTabGlyph, size: Dp = 21.dp, active: Boolean = false, modifier: Modifier = Modifier) {
    val c = IdjThemeColors
    val ink = if (active) c.accent else c.text.copy(alpha = 0.68f)
    val drawn = remember { Animatable(1f) }
    var wasActive by remember { mutableStateOf(active) }
    LaunchedEffect(active) {
        if (active && !wasActive) {
            drawn.snapTo(0.08f)
            drawn.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
        } else if (!active) {
            drawn.snapTo(1f)
        }
        wasActive = active
    }
    val scale by animateFloatAsState(
        targetValue = if (active) 1.04f else 1f,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = 420f),
        label = "ymTabScale",
    )
    androidx.compose.foundation.Canvas(modifier.size(size).scale(scale)) {
        val s = this.size.minDimension
        val subpaths = glyph.subpaths(s)
        val progress = drawn.value.coerceIn(0f, 1f)
        val drawList: List<androidx.compose.ui.graphics.Path> = if (progress >= 0.999f) {
            subpaths
        } else {
            val pm = androidx.compose.ui.graphics.PathMeasure()
            val lengths = subpaths.map { p -> pm.setPath(p, false); pm.length }
            var remaining = lengths.sum() * progress
            buildList {
                subpaths.forEachIndexed { idx, p ->
                    if (remaining <= 0f) return@forEachIndexed
                    val len = lengths[idx]
                    if (remaining >= len) {
                        add(p)
                        remaining -= len
                    } else {
                        pm.setPath(p, false)
                        val seg = androidx.compose.ui.graphics.Path()
                        pm.getSegment(0f, remaining, seg, true)
                        add(seg)
                        remaining = 0f
                    }
                }
            }
        }
        val lw = (if (active) 1.85.dp else 1.55.dp).toPx()
        val offX = 0.65.dp.toPx()
        val offY = 0.7.dp.toPx()
        val secondStyle = androidx.compose.ui.graphics.drawscope.Stroke(
            width = lw * 0.82f,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round,
        )
        val mainStyle = androidx.compose.ui.graphics.drawscope.Stroke(
            width = lw,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round,
        )
        translate(offX, offY) {
            drawList.forEach { p -> drawPath(p, color = ink.copy(alpha = 0.32f), style = secondStyle) }
        }
        drawList.forEach { p -> drawPath(p, color = ink, style = mainStyle) }
    }
}

/** Crystal-clear Yimeng floating dock surface: deep obsidian dark / warm cream light + dashed sketch rim. */
@Composable
fun Modifier.ymDockSurface(shape: Shape = CircleShape, shadowElevation: Dp = 16.dp): Modifier {
    val c = IdjThemeColors
    val fill = if (c.dark) Color(0xE60E0E12) else Color(0xF2FAF9F6)
    val rim = if (c.dark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.09f)
    val shadowCol = Color.Black.copy(alpha = if (c.dark) 0.45f else 0.12f)
    return this
        .shadow(shadowElevation, shape, ambientColor = shadowCol, spotColor = shadowCol)
        .clip(shape)
        .background(fill)
        .drawWithContent {
            drawContent()
            val sw = 1.dp.toPx()
            val innerSize = androidx.compose.ui.geometry.Size(
                (size.width - sw).coerceAtLeast(1f),
                (size.height - sw).coerceAtLeast(1f),
            )
            val outline = shape.createOutline(innerSize, layoutDirection, this)
            translate(sw / 2f, sw / 2f) {
                drawOutline(
                    outline = outline,
                    color = rim,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = sw,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                            floatArrayOf(6.dp.toPx(), 4.dp.toPx()),
                        ),
                    ),
                )
            }
        }
}

/** Floating glass bar (tab bar, sticky strip, play bar). Real blur on API 31+ through Haze. */
@Composable
fun Modifier.dsBar(radius: Dp, haze: HazeState? = null): Modifier {
    val c = IdjThemeColors; val s = RoundedCornerShape(radius)
    val blur = haze != null && android.os.Build.VERSION.SDK_INT >= 31
    return this
        .shadow(20.dp, s, ambientColor = c.barShadow, spotColor = c.barShadow)
        .clip(s)
        .then(if (blur) Modifier.hazeEffect(haze!!, HazeStyle(c.bar, emptyList(), 22.dp, noiseFactor = 0f)) else Modifier.background(c.sheet.copy(alpha = 0.94f)))
        .background(if (blur) Color.Transparent else c.bar)
        .border(1.dp, c.glassBorder, s)
}

/** Pinned header: page colour 80 % over blur + bottom hairline; fades in with [on]. */
@Composable
fun Modifier.dsPinned(on: Boolean, haze: HazeState? = null): Modifier {
    val c = IdjThemeColors
    val a by animateFloatAsState(if (on) 1f else 0f, label = "pin")
    val blur = haze != null && android.os.Build.VERSION.SDK_INT >= 31 && on
    return this
        .then(if (blur) Modifier.hazeEffect(haze!!, HazeStyle(c.pin, emptyList(), 20.dp, noiseFactor = 0f)) else Modifier)
        .drawWithContent {
            drawRect(if (blur) Color.Transparent else c.bg.copy(alpha = 0.96f * a))
            drawRect(c.pin.copy(alpha = c.pin.alpha * a))
            drawRect(c.hairline.copy(alpha = c.hairline.alpha * a), topLeft = Offset(0f, size.height - 1.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx()))
            drawContent()
        }
}

/** radial-gradient(130% 100% at 25% -8%, c0 0, c1 36–40 %, transparent 72 %) over the top 380 dp. */
@Composable
fun AmbientGlow(cool: Boolean = false, visible: Boolean = true, modifier: Modifier = Modifier) {
    val c = IdjThemeColors
    val cs = if (cool) c.ambientCool else c.ambientWarm
    val a by animateFloatAsState(if (visible) 1f else 0f, label = "amb")
    Box(modifier.fillMaxWidth().height(380.dp).graphicsLayer { alpha = a }.drawWithContent {
        drawRect(ambientBrush(cs, cool, size.width, size.height))
    })
}

/** [DSPage]'s ground and glow as a background, for screens whose root is a Column rather than a Box. */
@Composable
fun Modifier.dsAmbient(cool: Boolean = false): Modifier {
    val c = IdjThemeColors
    val cs = if (cool) c.ambientCool else c.ambientWarm
    return drawBehind {
        drawRect(c.bg)
        val h = 380.dp.toPx()
        drawRect(ambientBrush(cs, cool, size.width, h), size = androidx.compose.ui.geometry.Size(size.width, minOf(h, size.height)))
    }
}

private fun ambientBrush(cs: List<Color>, cool: Boolean, width: Float, height: Float) =
    Brush.radialGradient(0f to cs[0], (if (cool) 0.40f else 0.36f) to cs[1], 0.72f to cs[1].copy(alpha = 0f),
        center = Offset(width * 0.25f, -height * 0.08f), radius = width * 1.3f)

/** Page ground + top glow. */
@Composable
fun DSPage(cool: Boolean = false, glow: Boolean = true, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize().background(IdjThemeColors.bg)) {
        AmbientGlow(cool, glow)
        content()
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) = Box(modifier.fillMaxWidth().height(1.dp).background(IdjThemeColors.hairline))

/** Press feedback (spec "按下"): scale 0.96 and 85 % opacity. The animated scale is read in the layer block only. */
fun Modifier.pressClick(scale: Float = 0.96f, enabled: Boolean = true, label: String? = null, onClick: () -> Unit): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    this.pressScale(src, scale, 0.85f)
        .clickable(src, null, enabled = enabled, role = Role.Button, onClick = onClick)
        .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)
}

/** Tap + long-press with the same press feedback. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun Modifier.pressLongClick(scale: Float = 0.96f, label: String? = null, longLabel: String? = null, onLongClick: () -> Unit, onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    return this.pressScale(src, scale, 0.85f)
        .combinedClickable(src, null, role = Role.Button, onLongClickLabel = longLabel, onLongClick = onLongClick, onClick = onClick)
        .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)
}

/** Press feedback of a full-width row inside a card: a quiet wash instead of a ripple or a scale. */
@Composable
fun Modifier.pressWash(source: InteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val k = animateFloatAsState(if (pressed) 1f else 0f, DS.press(), label = "pressWash")
    val ink = IdjThemeColors.text
    return this.drawBehind { if (k.value > 0.01f) drawRect(ink.copy(alpha = 0.07f * k.value.coerceIn(0f, 1f))) }
}

/** Tappable card row (button role, [pressWash]). */
@Composable
fun Modifier.rowClick(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    return this.pressWash(src).clickable(src, null, enabled = enabled, role = Role.Button, onClick = onClick)
}

// MARK: level 1 — underline tabs (`.ttabs`)

@Composable
fun <T> UnderlineTabs(items: List<T>, selected: T, label: (T) -> String, small: Boolean = false, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val c = IdjThemeColors
    val d = LocalDensity.current
    val pos = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }
    val idx = items.indexOf(selected).coerceAtLeast(0)
    val target = pos[idx]
    val uw = if (small) 22.dp else 26.dp
    val x by animateDpAsState(with(d) { target?.let { (l, w) -> if (small) (l + (w - uw.toPx()) / 2).toDp() else (l + 1.dp.toPx()).toDp() } ?: 0.dp }, DS.select(), label = "ux")
    Box(modifier.height(if (small) 34.dp else 40.dp)) {
        Row(Modifier.align(if (small) Alignment.CenterStart else Alignment.BottomStart), horizontalArrangement = Arrangement.spacedBy(if (small) 24.dp else 22.dp), verticalAlignment = Alignment.Bottom) {
            items.forEachIndexed { i, it ->
                val on = i == idx
                Text(label(it),
                    color = if (on) c.text else c.secondary,
                    fontSize = if (on) (if (small) 18.sp else 24.sp) else (if (small) 16.sp else 17.sp),
                    fontWeight = if (on) (if (small) FontWeight.Bold else FontWeight.ExtraBold) else FontWeight.Normal,
                    maxLines = 1,
                    modifier = Modifier.onGloballyPositioned { pos[i] = it.positionInParent().x to it.size.width.toFloat() }
                        .padding(bottom = if (small) 0.dp else 6.dp)
                        .selectable(on, remember { MutableInteractionSource() }, null, role = Role.Tab) { onSelect(it) }
                        .testTag("tabs.${label(it)}"))
            }
        }
        val strokeDrawn = remember { Animatable(1f) }
        LaunchedEffect(idx) {
            strokeDrawn.snapTo(0.05f)
            strokeDrawn.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
        }
        if (target != null) androidx.compose.foundation.Canvas(
            Modifier.align(Alignment.BottomStart).offset(x = x, y = 0.dp).size(if (small) 26.dp else 34.dp, 6.dp),
        ) {
            val fullPath = androidx.compose.ui.graphics.Path().apply {
                moveTo(2f, size.height * 0.72f)
                quadraticTo(size.width * 0.5f, size.height * 0.12f, size.width - 2f, size.height * 0.52f)
            }
            val pm = androidx.compose.ui.graphics.PathMeasure()
            pm.setPath(fullPath, false)
            val seg = androidx.compose.ui.graphics.Path()
            pm.getSegment(0f, pm.length * strokeDrawn.value.coerceIn(0f, 1f), seg, true)
            drawPath(
                path = seg,
                color = c.accent,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = (if (small) 2.5.dp else 3.2.dp).toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                ),
            )
        }
    }
}

/** A fixed-height control that still grows with the system font. */
@Composable
private fun scaledHeight(base: Dp): Dp = base * LocalDensity.current.fontScale.coerceIn(1f, 1.6f)

// MARK: level 2 — segmented control (`.seg`): 40 dp, radius 14, padding 3, thumb radius 11
// LIQUID: sheer glass track with a specular rim + glossy refractive thumb; FROSTED: frosted track + solid thumb.

@Composable
fun <T> SegmentedTabs(items: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val c = IdjThemeColors
    val liquid = GlassStylePref.style == GlassStyle.LIQUID
    val idx = items.indexOf(selected).coerceAtLeast(0)
    val trackShape = CircleShape
    val thumbShape = CircleShape
    val track = Modifier.clip(trackShape).background(c.glass).dashedCapsuleBorder(c.glassBorder, width = 1.dp, dash = 5.dp, gap = 3.5.dp)
    BoxWithConstraints(modifier.fillMaxWidth().height(scaledHeight(40.dp)).then(track).padding(3.5.dp)) {
        val w = maxWidth / items.size.coerceAtLeast(1)
        val x by animateDpAsState(w * idx, DS.select(), label = "seg")
        Box(Modifier.offset(x = x).width(w).fillMaxHeight()
            .then(if (!c.dark) Modifier.shadow(2.dp, thumbShape, ambientColor = c.shadowThumb, spotColor = c.shadowThumb) else Modifier)
            .clip(thumbShape)
            .background(c.text))
        Row(Modifier.fillMaxSize()) {
            items.forEachIndexed { i, it ->
                val on = i == idx
                val col by animateColorAsState(if (on) c.bg else c.secondary, label = "segc")
                Box(Modifier.weight(1f).fillMaxHeight().selectable(on, remember { MutableInteractionSource() }, null, role = Role.Tab) { onSelect(it) }
                    .semantics { contentDescription = "seg.${label(it)}" }, contentAlignment = Alignment.Center) {
                    Text(label(it), color = col, fontSize = 13.5.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}

// MARK: level 3 — tone chips (`.chip`): Yimeng crisp monochrome solid active capsule + subtle accent rim

@Composable
fun ToneChip(title: String, on: Boolean, neutral: Boolean = false, small: Boolean = false, count: Int? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val c = IdjThemeColors
    val fill by animateColorAsState(if (on) c.text else c.glass, label = "chip")
    val ink by animateColorAsState(if (on) c.bg else c.secondary, label = "chipInk")
    val borderCol = when {
        on && !neutral -> c.accent.copy(alpha = 0.55f)
        on -> Color.Transparent
        else -> c.hairline
    }
    Row(Modifier
        .graphicsLayer { alpha = if (enabled) 1f else 0.38f }
        .clip(CircleShape).background(fill)
        .then(if (on) Modifier.border(0.9.dp, borderCol, CircleShape) else Modifier.dashedCapsuleBorder(c.hairline, width = 0.9.dp, dash = 4.5.dp, gap = 3.5.dp))
        .pressClick(0.97f, enabled, onClick = onClick)
        .semantics { selected = on }
        .testTag("chip.$title")
        .heightIn(min = if (small) 28.dp else 32.dp).padding(horizontal = if (small) 12.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, color = ink, fontSize = if (small) 12.5.sp else 13.5.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
        if (count != null) Text("$count", color = ink.copy(alpha = ink.alpha * 0.72f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/** One scrolling chip row; right edge fades (`.chips.fade`). */
@Composable
fun <T> ChipRow(items: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, neutral: (T) -> Boolean = { false },
                small: Boolean = false, fade: Boolean = true, start: Dp = 16.dp, modifier: Modifier = Modifier) {
    // fadeEnd renders offscreen and clips to its bounds; bleeding the row vertically keeps the
    // selected neutral chip's shadow (`.chip.all.on`) from being cut off.
    Row(modifier.fillMaxWidth().verticalBleed(6.dp).then(if (fade) Modifier.fadeEnd() else Modifier).horizontalScroll(rememberScrollState())
        .padding(start = start, end = 24.dp, top = 6.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { ToneChip(label(it), it == selected, neutral(it), small) { onSelect(it) } }
    }
}

/** Measures the content [by] taller on each side than the space it reports, so shadows can draw into that margin. */
private fun Modifier.verticalBleed(by: Dp): Modifier = layout { measurable, constraints ->
    val px = by.roundToPx()
    val placeable = measurable.measure(constraints.offset(vertical = 2 * px))
    layout(placeable.width, (placeable.height - 2 * px).coerceAtLeast(0)) { placeable.place(0, -px) }
}

fun Modifier.fadeEnd(from: Float = 0.84f): Modifier = this.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
    drawContent()
    drawRect(Brush.horizontalGradient(from to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
}

// MARK: buttons

enum class Tone { Glass, Accent, Overlay }

/** `.cbtn`: 36 dp round button. */
@Composable
fun RoundIconButton(icon: ImageVector, label: String, tone: Tone = Tone.Glass, size: Dp = 36.dp, onClick: () -> Unit) {
    val c = IdjThemeColors
    val bg = when (tone) { Tone.Accent -> c.accentSoft; Tone.Overlay -> Color.Black.copy(0.30f); Tone.Glass -> c.glass }
    val fg = when (tone) { Tone.Accent -> c.accent; Tone.Overlay -> Color.White; Tone.Glass -> c.text }
    val br = when (tone) { Tone.Accent -> Color.Transparent; Tone.Overlay -> Color.White.copy(0.16f); Tone.Glass -> c.hairline }
    Box(Modifier.size(size).clip(CircleShape).background(bg).border(1.dp, br, CircleShape).pressClick(label = label, onClick = onClick),
        contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(18.dp), tint = fg) }
}

@Composable
fun BackButton(tone: Tone = Tone.Glass, onClick: () -> Unit) =
    RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBackIos, "返回", tone) { onClick() }

/** `.capbtn`: 36 dp capsule. */
@Composable
fun CapsuleButton(title: String, icon: ImageVector? = null, trailing: ImageVector? = null, onClick: () -> Unit) {
    val c = IdjThemeColors
    Row(Modifier.height(36.dp).clip(CircleShape).background(c.glass).border(1.dp, c.hairline, CircleShape).pressClick(label = title, onClick = onClick)
        .padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (icon != null) Icon(icon, null, Modifier.size(16.dp), tint = c.secondary)
        Text(title, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
        if (trailing != null) Icon(trailing, null, Modifier.size(14.dp), tint = c.secondary)
    }
}

/** [Play]: the primary 播放 action — the same saturated brand-blue capsule in light and dark (never theme-inverted). */
enum class PillTone { Accent, Ink, White, Play }

private val PlayGradient = Brush.linearGradient(listOf(Color(0xFFC8F542), Color(0xFFA8DF28)))

/** `.btn`: 48 dp accent / ink / white / play capsule (32 dp small). */
@Composable
fun PillButton(title: String, modifier: Modifier = Modifier, icon: ImageVector? = null, tone: PillTone = PillTone.Accent, small: Boolean = false,
               height: Dp? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val c = IdjThemeColors
    val bg = when (tone) { PillTone.Accent -> c.accent; PillTone.Ink -> c.text; PillTone.White -> Color.White; PillTone.Play -> c.accent }
    val fg = when (tone) { PillTone.Accent -> c.onAccent; PillTone.Ink -> c.bg; PillTone.White -> Color(0xFF0A0A0C); PillTone.Play -> c.onAccent }
    val fill = if (tone == PillTone.Play && c.dark) Modifier
        .clip(CircleShape).background(PlayGradient)
        .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
    else Modifier.clip(CircleShape).background(bg)
    Row(modifier.height(height ?: if (small) 32.dp else 48.dp).then(fill).pressClick(0.98f, enabled, title, onClick)
        .padding(horizontal = if (small) 14.dp else 22.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
        if (icon != null) Icon(icon, null, Modifier.size(if (small) 14.dp else 18.dp), tint = fg)
        Text(title, color = fg, fontSize = if (small) 13.sp else 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** `.mini`: small accent capsule action. */
@Composable
fun MiniButton(title: String, onClick: () -> Unit) {
    val c = IdjThemeColors
    Box(Modifier.height(30.dp).clip(CircleShape).background(c.accentSoft).pressClick(label = title, onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center) { Text(title, color = c.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}

// MARK: nav / search / headers

/** `.nav`: 44 dp bar, 96 dp side slots, centred 17 sp title. */
@Composable
fun DSNavBar(title: String, modifier: Modifier = Modifier, titleAlpha: Float = 1f, leading: @Composable () -> Unit = {}, trailing: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(96.dp), contentAlignment = Alignment.CenterStart) { leading() }
        Text(title, Modifier.weight(1f).graphicsLayer { alpha = titleAlpha }, color = IdjThemeColors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Box(Modifier.width(96.dp), contentAlignment = Alignment.CenterEnd) { trailing() }
    }
}

/** `.search`: 46 dp glass capsule (placeholder only; the real field lives in the search screens). */
@Composable
fun DSSearchBar(placeholder: String = "搜索你想看的作品", modifier: Modifier = Modifier, ink: Color? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    val c = IdjThemeColors
    Row(modifier.fillMaxWidth().height(46.dp).clip(CircleShape).background(c.glass).dashedCapsuleBorder(c.glassBorder, width = 1.dp, dash = 5.5.dp, gap = 4.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Rounded.Search, null, Modifier.size(20.dp), tint = ink ?: c.secondary)
        Text(placeholder, Modifier.weight(1f), color = ink ?: c.secondary, fontSize = 16.sp, maxLines = 1)
        trailing()
    }
}

/** `.sech`: 19 sp bold title + note + trailing. */
@Composable
fun SectionHeader2(title: String, note: String? = null, inset: Dp = 16.dp, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    val c = IdjThemeColors
    Row(modifier.fillMaxWidth().padding(horizontal = inset), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DoodleSparkle(c.accent, size = 11.dp)
        Column {
            Text(title, color = c.text, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
            androidx.compose.foundation.Canvas(Modifier.size(24.dp, 4.dp)) {
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(1f, size.height * 0.7f)
                    quadraticTo(size.width * 0.5f, size.height * 0.1f, size.width - 1f, size.height * 0.5f)
                }
                drawPath(p, color = c.accent.copy(alpha = 0.65f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
            }
        }
        if (note != null) Text(note, color = c.secondary, fontSize = 13.sp, modifier = Modifier.padding(start = 2.dp))
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

/** "全部 ›" (`.more`). */
@Composable
fun MoreLink(title: String = "全部", icon: ImageVector? = null, onClick: () -> Unit) {
    val c = IdjThemeColors
    Row(Modifier.pressClick(label = title, onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Icon(icon, null, Modifier.size(14.dp).padding(end = 3.dp), tint = c.secondary)
        Text(title, color = c.secondary, fontSize = 13.sp)
        if (icon == null) Icon(Icons.Rounded.ChevronRight, null, Modifier.size(14.dp), tint = c.secondary)
    }
}

/** `.tag`: neutral 20 dp label. */
@Composable
fun DSTag(text: String) {
    val c = IdjThemeColors
    Box(Modifier.height(20.dp).clip(RoundedCornerShape(6.dp)).background(c.glass).padding(horizontal = 7.dp), contentAlignment = Alignment.Center) {
        Text(text, color = c.secondary, fontSize = 11.sp, maxLines = 1)
    }
}

enum class StatusTone { Ok, Warn, Muted, Accent }

/** `.st`: status label. */
@Composable
fun DSStatus(text: String, tone: StatusTone) {
    val c = IdjThemeColors
    val (fg, bg) = when (tone) {
        StatusTone.Ok -> c.ok to c.okSoft; StatusTone.Warn -> c.accentWarm to c.warmSoft
        StatusTone.Muted -> c.tertiary to c.glass; StatusTone.Accent -> c.accent to c.accentSoft
    }
    Box(Modifier.height(20.dp).clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 7.dp), contentAlignment = Alignment.Center) {
        Text(text, color = fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** `.bar`: 3 dp progress track. */
@Composable
fun DSProgressBar(value: Float, modifier: Modifier = Modifier) {
    val c = IdjThemeColors
    val v = animateFloatAsState(value.coerceIn(0f, 1f), label = "bar")
    Box(modifier.fillMaxWidth().height(3.dp).clip(CircleShape).drawBehind {
        drawRect(c.glassBorder)
        if (v.value > 0f) drawRoundRect(c.accent, size = androidx.compose.ui.geometry.Size(size.width * v.value, size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f, size.height / 2f))
    })
}

/** `.dot` / `.lico`: tinted icon well. */
@Composable
fun IconWell(icon: ImageVector, size: Dp = 34.dp, corner: Dp? = null) {
    val c = IdjThemeColors
    Box(
        Modifier.size(size).clip(RoundedCornerShape(corner ?: (size / 2))).background(c.accentSoft)
            .drawWithContent {
                drawContent()
                if (corner == null) drawScribbleEllipse(c.accent.copy(alpha = 0.68f), 1.4.dp.toPx(), seed = 17, inset = 1.dp.toPx())
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(size * 0.5f), tint = c.accent)
    }
}

// MARK: posters

/** Rank numeral on posters: italic 800, gold / silver / bronze for the top 3. */
@Composable
fun RankNumeral(rank: Int, size: Int = 20, modifier: Modifier = Modifier) {
    val c = IdjThemeColors
    Text("$rank", modifier, color = c.rankColor(rank), fontSize = size.sp, fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic, maxLines = 1)
}

/** Poster with bottom scrim and optional top-left rank numeral (`.poster`). */
@Composable
fun PosterArt(url: String, modifier: Modifier = Modifier, corner: Dp = 12.dp, rank: Int? = null, badge: String? = null) {
    val c = IdjThemeColors
    Box(modifier.clip(RoundedCornerShape(corner)).background(c.glass)) {
        AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.46f to Color.Transparent, 1f to Color.Black.copy(0.62f))))
        if (rank != null) {
            val medal = when (rank) { 1 -> Color(0xFFF2B705); 2 -> Color(0xFFC7D1E6); 3 -> Color(0xFFDB8C52); else -> Color.White }
            Column(
                Modifier.align(Alignment.TopStart).padding(start = 6.dp, top = if (rank == 1) 4.dp else 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (rank == 1) {
                    androidx.compose.foundation.Canvas(Modifier.size(16.dp, 9.dp).graphicsLayer { rotationZ = -8f }) {
                        val p = androidx.compose.ui.graphics.Path().apply {
                            moveTo(1f, size.height - 1f)
                            lineTo(size.width * 0.14f, size.height * 0.25f)
                            lineTo(size.width * 0.36f, size.height * 0.62f)
                            lineTo(size.width * 0.5f, 0.5f)
                            lineTo(size.width * 0.64f, size.height * 0.62f)
                            lineTo(size.width * 0.86f, size.height * 0.25f)
                            lineTo(size.width - 1f, size.height - 1f)
                            close()
                        }
                        drawPath(p, color = medal)
                    }
                }
                Box(
                    Modifier
                        .size(if (rank <= 3) 27.dp else 23.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = if (rank <= 3) 0.68f else 0.52f))
                        .drawWithContent {
                            drawContent()
                            if (rank <= 3) drawScribbleEllipse(medal, 1.5.dp.toPx(), seed = rank * 29, inset = 1.dp.toPx())
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "$rank",
                        color = medal,
                        fontSize = if (rank <= 3) 13.5.sp else 12.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                    )
                }
            }
        }
        if (!badge.isNullOrEmpty()) Text(badge, Modifier.align(Alignment.BottomStart).padding(8.dp), color = Color.White.copy(0.9f), fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}
