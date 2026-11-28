package app.iduanju.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow
import app.iduanju.ui.theme.IdjThemeColors

private val PillShape = RoundedCornerShape(percent = 50)
private val RailShape = RoundedCornerShape(28.dp)

/**
 * Shared search input state bridging the morphing bottom Liquid Glass search dock
 * and [app.iduanju.ui.search.SearchScreen].
 */
@Stable
class SearchDockState(initialQuery: String = "") {
    var query by mutableStateOf(initialQuery)
    var submitted by mutableStateOf(initialQuery.trim())
    var submitVersion by mutableIntStateOf(if (initialQuery.isNotBlank()) 1 else 0)
    var clearVersion by mutableIntStateOf(0)
    /**
     * true while the user is editing the query: the bottom dock hides and SearchScreen shows its own input bar at the
     * top with 历史搜索 / 搜索建议 directly below it. false = the dock rests at the bottom as `[←] [🔍 搜索…]`.
     */
    var isTyping by mutableStateOf(false)

    fun submit(q: String = query) {
        val trimmed = q.trim()
        if (trimmed.isEmpty()) return
        query = trimmed
        submitted = trimmed
        submitVersion++
        isTyping = false
    }

    /** Leave typing mode without searching: the dock shows the last submitted query again. */
    fun cancelTyping() {
        query = submitted
        isTyping = false
    }

    fun clear() {
        query = ""
        submitted = ""
        clearVersion++
    }
}

/** Global glass material, switchable live from 我的 → 外观. */
enum class GlassStyle(val label: String) { LIQUID("液态玻璃"), FROSTED("毛玻璃") }

/** Reactive, SharedPreferences-backed holder for the app-wide [GlassStyle] (default [GlassStyle.LIQUID]). */
object GlassStylePref {
    private const val PREFS = "idj"
    private const val KEY = "ui.glass.style"
    private const val KEY_STRENGTH = "ui.glass.strength"
    private val prefs by lazy {
        runCatching { app.iduanju.IdjApp.instance.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE) }.getOrNull()
    }

    var style: GlassStyle by mutableStateOf(
        prefs?.getString(KEY, null)?.let { n -> GlassStyle.entries.firstOrNull { it.name == n } } ?: GlassStyle.FROSTED,
    )
        private set

    fun update(newStyle: GlassStyle) {
        if (style == newStyle) return
        style = newStyle
        prefs?.edit()?.putString(KEY, newStyle.name)?.apply()
    }

    /**
     * LIQUID 通透度 0..1 (我的 → 外观): 0 = densest tint / most readable, 1 = crystal-clear glass with the strongest lens
     * refraction. Every LIQUID surface and capsule interpolates continuously from it.
     */
    var strength: Float by mutableFloatStateOf(prefs?.getFloat(KEY_STRENGTH, 0.72f)?.coerceIn(0f, 1f) ?: 0.72f)
        private set

    fun updateStrength(v: Float) {
        val c = v.coerceIn(0f, 1f)
        strength = c
        prefs?.edit()?.putFloat(KEY_STRENGTH, c)?.apply()
    }
}

/** Linear blend from the 不透明 end ([low], strength 0) to the 通透 end ([high], strength 1). */
private fun glassLerp(low: Float, high: Float, s: Float) = low + (high - low) * s

/** Opacity multiplier for LIQUID capsule fills: 1 at the default 72 %, denser below, sheerer above. */
private fun liquidFillScale(s: Float) = 1f + (0.72f - s) * 1.25f

/**
 * Backdrop recorded from the root NavHost content. Only valid for overlays drawn OUTSIDE the
 * `layerBackdrop(rootBackdrop)` subtree (tab bar / rail); anything inside the NavHost must use its own local
 * `rememberLayerBackdrop()` (or [glassSurface] without a backdrop), otherwise the layer would sample itself.
 */
val LocalRootBackdrop = compositionLocalOf<Backdrop?> { null }

/** Colours shared by every glass surface so LIQUID / FROSTED look identical everywhere in the app. */
private class GlassPalette(private val dark: Boolean) {
    private val liquidBase = if (dark) Color.Black else Color.White
    /** Lens-path tint: 0.58 / 0.56 at 通透度 0 → 0.05 / 0.03 at 100 %. */
    fun liquidTint(s: Float, least: Float) =
        liquidBase.copy(alpha = (if (dark) glassLerp(0.58f, 0.05f, s) else glassLerp(0.56f, 0.03f, s)).coerceAtLeast(least))
    val frostTint = if (dark) Color(0xFF1C1E24).copy(alpha = 0.78f) else Color.White.copy(alpha = 0.76f)
    val frostFallback = if (dark) Color(0xFF1C1E24).copy(alpha = 0.90f) else Color.White.copy(alpha = 0.94f)
    val rimTop = Color.White.copy(alpha = if (dark) 0.12f else 0.45f)
    val rimBottom = Color.White.copy(alpha = if (dark) 0.03f else 0.12f)
    val frostBorder = if (dark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f)
    val sheen = Color.White.copy(alpha = if (dark) 0.05f else 0.14f)
    val shadow = Color.Black.copy(alpha = if (dark) 0.22f else 0.10f)
}

/**
 * The single glass material of the app, driven by [GlassStylePref].
 *
 * - [backdrop] != null on API 31+: real backdrop sampling. LIQUID (API 33+) = refractive lens + sheer tint + specular
 *   highlight; FROSTED = 18 dp blur, high-readability tint and a 0.7 dp hairline.
 * - otherwise: a static surface that visually matches the selected style (sheer fill + specular rim for LIQUID,
 *   denser frosted fill + soft hairline for FROSTED). [fallbackAlpha] scales the sheer LIQUID fallback fill
 *   (x0.36 → 0.18..0.44 dark, x0.28 → 0.14..0.36 light); FROSTED always uses its high-readability fill.
 *
 * [dark] forces the dark palette (player chrome); [base] is the surface colour of LIQUID fallbacks. [veil] is the least
 * tint whatever the 通透度: bars that carry small labels over pictures stay readable.
 */
@Composable
fun Modifier.glassSurface(
    shape: CornerBasedShape,
    backdrop: Backdrop? = null,
    dark: Boolean = IdjThemeColors.dark,
    base: Color = if (dark == IdjThemeColors.dark) IdjThemeColors.surface else Color(0xFF15171C),
    shadowRadius: Dp = 18.dp,
    activeOverlay: Color = Color.Transparent,
    fallbackAlpha: Float = 0.90f,
    highlightAlpha: Float = 0.50f,
    veil: Float = 0f,
): Modifier {
    val frosted = GlassStylePref.style == GlassStyle.FROSTED
    val s = GlassStylePref.strength
    val p = remember(dark) { GlassPalette(dark) }
    val canBlur = backdrop != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
    val hasLens = canBlur && !frosted && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
    val shadowColor = p.shadow
    val noLensTint = base.copy(alpha = ((if (dark) 0.50f else 0.54f) * liquidFillScale(s)).coerceIn(maxOf(0.10f, veil), 0.90f))
    val edge = if (frosted) {
        Modifier.border(0.7.dp, p.frostBorder, shape)
    } else {
        Modifier.border(0.7.dp, Brush.verticalGradient(listOf(p.rimTop, p.rimBottom)), shape)
    }
    return if (canBlur) {
        this
            .drawBackdrop(
                backdrop = backdrop!!,
                shape = { shape },
                effects = {
                    vibrancy()
                    if (hasLens) {
                        blur(glassLerp(12f, 1f, s).coerceAtLeast(if (veil > 0f) 9f else 0f).dp.toPx())
                        lens(
                            refractionHeight = glassLerp(8f, 14f, s).dp.toPx(),
                            refractionAmount = glassLerp(12f, 32f, s).dp.toPx(),
                            depthEffect = true,
                            chromaticAberration = true,
                        )
                    } else {
                        blur((if (frosted) 18f else 12f).dp.toPx())
                    }
                },
                highlight = {
                    when {
                        hasLens -> Highlight(
                            width = 0.75.dp,
                            blurRadius = 0.5.dp,
                            alpha = highlightAlpha,
                            style = HighlightStyle.Default(intensity = 0.5f),
                        )
                        frosted -> Highlight(width = 0.5.dp, blurRadius = 0.dp, alpha = 0.22f, style = HighlightStyle.Plain)
                        else -> Highlight(width = 0.75.dp, blurRadius = 0.dp, alpha = highlightAlpha * 0.8f, style = HighlightStyle.Plain)
                    }
                },
                shadow = {
                    Shadow(
                        radius = shadowRadius,
                        offset = DpOffset(0.dp, 6.dp),
                        color = shadowColor,
                    )
                },
                onDrawSurface = {
                    drawRect(
                        when {
                            frosted -> p.frostTint
                            hasLens -> p.liquidTint(s, veil)
                            else -> noLensTint
                        },
                    )
                    if (activeOverlay.alpha > 0f) drawRect(activeOverlay)
                },
            )
            .then(if (!hasLens) edge else Modifier)
    } else {
        this
            // A sheer LIQUID fill lets an elevation shadow show through and grey the glass, so it gets half the depth.
            .shadow(if (frosted) shadowRadius else shadowRadius * 0.5f, shape, ambientColor = shadowColor, spotColor = shadowColor)
            .clip(shape)
            .then(
                if (frosted) {
                    // high-readability frosted fill (0.84 dark / 0.90 light) + matte hairline
                    Modifier.background(p.frostFallback)
                } else {
                    // Subtle translucent surface without harsh fake specular gradients when no live backdrop is sampled.
                    val sheer = if (dark) glassLerp(0.76f, 0.42f, s) else glassLerp(0.84f, 0.56f, s)
                    val liquidAlpha = (sheer * fallbackAlpha / 0.90f).coerceIn(maxOf(0.28f, veil), 0.90f)
                    Modifier
                        .background(base.copy(alpha = liquidAlpha))
                        .background(
                            Brush.verticalGradient(
                                0f to Color.White.copy(alpha = if (dark) 0.06f else 0.20f),
                                0.35f to Color.Transparent,
                            ),
                        )
                },
            )
            .then(if (activeOverlay.alpha > 0f) Modifier.background(activeOverlay) else Modifier)
            .then(if (frosted) edge else Modifier.border(0.6.dp, Brush.verticalGradient(listOf(p.rimTop, p.rimBottom)), shape))
    }
}

/** Tint floor of the tab bar and the rail. */
@Composable
fun dockVeil(): Float = if (IdjThemeColors.dark) 0.50f else 0.62f

/** Unselected tab: stronger than secondary, as pictures move under the glass. */
@Composable
fun dockLabel(): Color = IdjThemeColors.text.copy(alpha = 0.76f)

/**
 * Top->bottom specular rim of a Liquid Glass capsule. With [accent] the rim glows in that colour (selected chips).
 * Light mode fades into a faint dark hairline so a white rim still reads on a light page.
 */
fun liquidRimBrush(dark: Boolean, accent: Color? = null): Brush = when {
    accent != null -> Brush.verticalGradient(listOf(accent.copy(alpha = 0.65f), accent.copy(alpha = 0.25f)))
    dark -> Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.04f)))
    else -> Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.08f), Color.Black.copy(alpha = 0.05f)))
}

/** Liquid-lens specular sheen: subtle top highlight -> clear centre. */
fun liquidSheenBrush(dark: Boolean, strong: Boolean = false): Brush = Brush.verticalGradient(
    0f to Color.White.copy(alpha = if (dark) (if (strong) 0.08f else 0.04f) else (if (strong) 0.22f else 0.12f)),
    0.45f to Color.Transparent,
)

/**
 * Inner capsule material (chips, option pills, segment thumbs, tab indicators) driven by [GlassStylePref], so the
 * capsules inside a glass surface always match it.
 *
 * - LIQUID: sheer glass fill + specular sheen + 0.8 dp top->bottom specular rim; [selected] adds an accent tint and a
 *   glowing accent rim, or with [neutralSelected] a bright white glass thumb (segmented control / tab pill).
 * - FROSTED: high-readability frosted fill + 0.7 dp matte hairline (accent-tinted when [selected]).
 */
@Composable
fun Modifier.glassPill(
    shape: Shape,
    selected: Boolean = false,
    dark: Boolean = IdjThemeColors.dark,
    accent: Color = IdjThemeColors.accent,
    neutralSelected: Boolean = false,
): Modifier {
    val liquid = GlassStylePref.style == GlassStyle.LIQUID
    return if (liquid) {
        val k = liquidFillScale(GlassStylePref.strength)
        val fill = when {
            selected && neutralSelected -> Color.White.copy(alpha = ((if (dark) 0.17f else 0.70f) * k).coerceAtMost(0.95f))
            selected -> accent.copy(alpha = ((if (dark) 0.24f else 0.16f) * k).coerceAtMost(0.60f))
            else -> Color.White.copy(alpha = ((if (dark) 0.06f else 0.40f) * k).coerceAtMost(0.85f))
        }
        this
            .clip(shape)
            .background(fill)
            .background(liquidSheenBrush(dark, strong = selected))
            .border(0.8.dp, liquidRimBrush(dark, if (selected && !neutralSelected) accent else null), shape)
    } else {
        val fill = when {
            selected && neutralSelected -> if (dark) Color(0xFF3A3D45).copy(alpha = 0.94f) else Color.White
            selected -> accent.copy(alpha = if (dark) 0.22f else 0.14f)
            else -> if (dark) Color.White.copy(alpha = 0.09f) else Color.Black.copy(alpha = 0.05f)
        }
        val line = when {
            selected && !neutralSelected -> accent.copy(alpha = 0.55f)
            dark -> Color.White.copy(alpha = 0.10f)
            else -> Color.Black.copy(alpha = 0.07f)
        }
        this.clip(shape).background(fill).border(0.7.dp, line, shape)
    }
}

/** Round glass icon button (back / favourite / more) built on [glassSurface]. */
@Composable
fun GlassIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
    size: Dp = 40.dp,
    iconSize: Dp = 20.dp,
    dark: Boolean = IdjThemeColors.dark,
    tint: Color = if (dark) Color.White else IdjThemeColors.text,
    fallbackAlpha: Float = 0.55f,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(size)
            .glassSurface(
                shape = CircleShape,
                backdrop = backdrop,
                dark = dark,
                shadowRadius = 8.dp,
                fallbackAlpha = fallbackAlpha,
            )
            .pressClick(0.92f, enabled = enabled, label = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

@Composable
fun GlassCapsule(
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
    contentPadding: PaddingValues = PaddingValues(6.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val c = IdjThemeColors
    Row(
        modifier
            .glassSurface(shape = PillShape, backdrop = backdrop, shadowRadius = 18.dp)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun GlassTabBar(
    selected: Int,
    items: List<Pair<String, @Composable (Boolean) -> Unit>>,
    onSelect: (Int) -> Unit,
    backdrop: Backdrop? = null,
    modifier: Modifier = Modifier,
    searchSelected: Boolean = false,
    onSearch: (() -> Unit)? = null,
    searchIcon: (@Composable (Boolean) -> Unit)? = null,
    searchDockState: SearchDockState? = null,
    onCloseSearch: () -> Unit = {},
) {
    val c = IdjThemeColors
    val liquid = GlassStylePref.style == GlassStyle.LIQUID
    val focusManager = LocalFocusManager.current
    val hasSearch = onSearch != null && searchIcon != null
    val morph by animateFloatAsState(
        targetValue = if (hasSearch && searchSelected) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 360f),
        label = "dockMorph",
    )

    // The morphed dock never grabs focus / pops the keyboard by itself; tapping its capsule enters typing mode.
    LaunchedEffect(searchSelected) { if (!searchSelected) focusManager.clearFocus() }

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        BoxWithConstraints(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            val gap = 10.dp
            val circleSize = 58.dp
            val totalW = maxWidth
            val expandedW = if (hasSearch) (totalW - gap - circleSize).coerceAtLeast(circleSize) else totalW
            val t = morph.coerceIn(0f, 1f)
            val leftW = if (hasSearch) lerp(expandedW, circleSize, t) else totalW
            val rightW = lerp(circleSize, expandedW, t)

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                // Left Liquid Glass Capsule: 4 navigation tabs (t -> 0) morphing into circular Back button (t -> 1)
                Box(
                    Modifier
                        .width(leftW)
                        .height(circleSize)
                        .glassSurface(shape = PillShape, backdrop = backdrop, shadowRadius = 18.dp)
                        .then(
                            if (searchSelected) {
                                Modifier.clickable {
                                    focusManager.clearFocus()
                                    onCloseSearch()
                                }
                            } else {
                                Modifier
                            },
                        )
                        .padding(if (t < 0.5f) 5.dp else 0.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (t < 0.5f) {
                        val tabsAlpha = (1f - t * 2f).coerceIn(0f, 1f)
                        Row(
                            Modifier.fillMaxSize().graphicsLayer { alpha = tabsAlpha },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            items.forEachIndexed { i, (label, icon) ->
                                val on = i == selected && !searchSelected
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .then(
                                            when {
                                                // glossy refractive liquid pill: white sheen + specular rim gradient
                                                on && liquid -> Modifier.glassPill(PillShape, selected = true, neutralSelected = true)
                                                on -> Modifier.clip(PillShape).background(if (c.dark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.08f))
                                                else -> Modifier.clip(PillShape)
                                            },
                                        )
                                        .clickable { onSelect(i) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        icon(on)
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            label,
                                            color = if (on) c.text else c.secondary,
                                            fontSize = 11.sp,
                                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        val backAlpha = ((t - 0.5f) * 2f).coerceIn(0f, 1f)
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = c.text,
                            modifier = Modifier.size(22.dp).graphicsLayer { alpha = backAlpha },
                        )
                    }
                }

                // Right Liquid Glass Capsule: circular Search button (t -> 0) morphing into full Liquid Glass Search Input Bar (t -> 1)
                if (hasSearch) {
                    val searchQuery = searchDockState?.query.orEmpty()
                    Box(
                        Modifier
                            .width(rightW)
                            .height(circleSize)
                            .glassSurface(shape = PillShape, backdrop = backdrop, shadowRadius = 18.dp, activeOverlay = if (searchSelected) c.accent.copy(alpha = if (c.dark) 0.06f else 0.04f) else Color.Transparent)
                            .clickable {
                                if (!searchSelected) onSearch?.invoke() else searchDockState?.isTyping = true
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (t < 0.38f && !searchSelected) {
                            val iconAlpha = (1f - t / 0.38f).coerceIn(0f, 1f)
                            Box(Modifier.graphicsLayer { alpha = iconAlpha }, contentAlignment = Alignment.Center) {
                                searchIcon?.invoke(false)
                            }
                        } else {
                            val fieldAlpha = ((t - 0.25f) / 0.75f).coerceIn(0f, 1f)
                            Row(
                                Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp)
                                    .graphicsLayer { alpha = fieldAlpha },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Filled.Search,
                                    contentDescription = "搜索",
                                    tint = c.accent,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                // At rest the dock is a tap target, not a text field: tapping it opens the top input bar.
                                Text(
                                    searchQuery.ifEmpty { "搜索短剧 / 演员 / 频道…" },
                                    color = if (searchQuery.isEmpty()) c.secondary else c.text,
                                    fontSize = 15.sp,
                                    fontWeight = if (searchQuery.isEmpty()) FontWeight.Normal else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (searchQuery.isNotEmpty()) {
                                    Spacer(Modifier.width(6.dp))
                                    Icon(
                                        Icons.Filled.Cancel,
                                        contentDescription = "清除",
                                        tint = c.secondary,
                                        modifier = Modifier
                                            .size(18.dp)
                                            .clickable { searchDockState?.clear() },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Vertical floating 72 dp Liquid Glass navigation rail for Expanded tablet / desktop widths (>= 840dp).
 *
 * 72 dp slot = 8 dp gutters + a 56 dp glass pill column + independent 48 dp circular search button at top.
 */
@Composable
fun GlassNavigationRail(
    selected: Int,
    items: List<Pair<String, @Composable (Boolean) -> Unit>>,
    onSelect: (Int) -> Unit,
    backdrop: Backdrop? = null,
    modifier: Modifier = Modifier,
    searchSelected: Boolean = false,
    onSearch: (() -> Unit)? = null,
    searchIcon: (@Composable (Boolean) -> Unit)? = null,
) {
    val c = IdjThemeColors
    val liquid = GlassStylePref.style == GlassStyle.LIQUID
    Column(
        modifier
            .width(72.dp)
            .fillMaxHeight()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(top = 10.dp, bottom = 14.dp, start = 12.dp, end = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (onSearch != null && searchIcon != null) {
            Box(
                Modifier
                    .size(48.dp)
                    .testTag("home.search")
                    .ymDockSurface(CircleShape, shadowElevation = 12.dp)
                    .pressClick(0.94f, label = "搜索", onClick = onSearch),
                contentAlignment = Alignment.Center,
            ) {
                searchIcon(searchSelected)
            }
        }
        Spacer(Modifier.weight(1f))
        Column(
            Modifier
                .width(56.dp)
                .ymDockSurface(RoundedCornerShape(30.dp), shadowElevation = 14.dp)
                .padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items.forEachIndexed { i, (label, icon) ->
                val on = i == selected && !searchSelected
                val pill by animateFloatAsState(if (on) 1f else 0f, spring(dampingRatio = 0.8f, stiffness = 520f), label = "railPill")
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .clickable { onSelect(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(Modifier.size(42.dp, 28.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .matchParentSize()
                                .graphicsLayer { scaleX = 0.6f + 0.4f * pill; alpha = pill }
                                .clip(PillShape)
                                .background(if (c.dark) Color.White.copy(alpha = 0.11f) else Color.Black.copy(alpha = 0.08f)),
                        )
                        icon(on)
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        label,
                        color = if (on) c.accent else c.text.copy(alpha = 0.62f),
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        maxLines = 1,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (onSearch != null) Spacer(Modifier.height(48.dp))
    }
}

@Composable
fun GlassChip(title: String, selected: Boolean, onClick: () -> Unit) {
    val c = IdjThemeColors
    Box(
        Modifier
            .clip(PillShape)
            .background(if (selected) c.text else c.glass)
            .then(if (selected) Modifier.border(0.9.dp, c.accent.copy(alpha = 0.55f), PillShape) else Modifier.dashedCapsuleBorder(c.hairline, width = 0.9.dp, dash = 4.5.dp, gap = 3.5.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            title,
            color = if (selected) c.bg else c.text.copy(alpha = 0.82f),
            fontSize = 13.5.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

fun posterBadge(total: Int, updated: Int = 0): String = when {
    total > 0 && updated > 0 && updated < total -> "更新至${updated}集"
    total > 0 -> "全${total}集"
    updated > 0 -> "更新至${updated}集"
    else -> ""
}

val PosterShape = RoundedCornerShape(10.dp)
val CardShape = RoundedCornerShape(16.dp)
