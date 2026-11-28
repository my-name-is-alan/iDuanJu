package app.iduanju.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Pixel-matched to iOS STVTheme (Theme.swift). The second block are the ui-redesign tokens
 * (ui-redesign/styles.css); screens use these instead of ad-hoc colours.
 */
@androidx.compose.runtime.Immutable
data class IdjColors(
    val dark: Boolean,
    val bg: Color,
    val surface: Color,
    val elevated: Color,
    val text: Color,
    val secondary: Color,
    val accent: Color,
    val accentWarm: Color,
    val tertiary: Color,
    val glass: Color,
    val glassBorder: Color,
    val hairline: Color,
    val accentSoft: Color,
    val accentLine: Color,
    val onAccent: Color,
    val warmSoft: Color,
    val ok: Color,
    val okSoft: Color,
    val card: Color,
    val cardBorder: Color,
    val thumb: Color,
    val bar: Color,
    val sheet: Color,
    val rail: Color,
    val gold: Color,
    val silver: Color,
    val bronze: Color,
    val pin: Color,
    val dim: Color,
    val shadowSoft: Color,
    val shadowThumb: Color,
    val barShadow: Color,
    val ambientWarm: List<Color>,
    val ambientCool: List<Color>,
    /** Destructive actions, failures and the LIVE dot. */
    val danger: Color,
    val dangerSoft: Color,
    /** Coral-red heart for favorited dramas (iOS STVTheme.favorite). */
    val favorite: Color,
) {
    fun rankColor(r: Int): Color = when (r) { 1 -> gold; 2 -> silver; 3 -> bronze; else -> tertiary }
}

private val Dark = IdjColors(
    dark = true,
    bg = Color(0xFF0A0A0C),
    surface = Color(0xFF141418),
    elevated = Color(0xFF26272D),
    text = Color(0xFFF4F4F6),
    secondary = Color(0xFF9A9CA6),
    accent = Color(0xFFC8F542),
    accentWarm = Color(0xFFF2B55C),
    tertiary = Color(0xA39A9CA6),
    glass = Color(0x12FFFFFF),
    glassBorder = Color(0x21FFFFFF),
    hairline = Color(0x1AFFFFFF),
    accentSoft = Color(0x26C8F542),
    accentLine = Color(0x70C8F542),
    onAccent = Color(0xFF0A0A0C),
    warmSoft = Color(0x26F2B55C),
    ok = Color(0xFF5FD08A),
    okSoft = Color(0x245FD08A),
    card = Color(0x0EFFFFFF),
    cardBorder = Color(0x1CFFFFFF),
    thumb = Color(0x29FFFFFF),
    bar = Color(0xC7101014),
    sheet = Color(0xFF141418),
    rail = Color(0x0AFFFFFF),
    gold = Color(0xFFFFC740),
    silver = Color(0xFFC7D1E6),
    bronze = Color(0xFFDB8C52),
    pin = Color(0xD10A0A0C),
    dim = Color(0x8F000000),
    shadowSoft = Color(0x00000000),
    shadowThumb = Color(0x00000000),
    barShadow = Color(0x57000000),
    ambientWarm = listOf(Color(0x24C8F542), Color(0x0D6E9130)),
    ambientCool = listOf(Color(0x1FA0D250), Color(0x0A466432)),
    danger = Color(0xFFFF6B6B),
    dangerSoft = Color(0x29FF6B6B),
    favorite = Color(0xFFFF5FA8),
)

private val Light = IdjColors(
    dark = false,
    bg = Color(0xFFF6F5F1),
    surface = Color(0xFFFFFFFF),
    elevated = Color(0xFFEBEAE6),
    text = Color(0xFF141416),
    secondary = Color(0xFF64666E),
    accent = Color(0xFF4F6B00),
    accentWarm = Color(0xFFD58514),
    tertiary = Color(0xAD64666E),
    glass = Color(0x0E141416),
    glassBorder = Color(0x1C141416),
    hairline = Color(0x17141416),
    accentSoft = Color(0x1C4F6B00),
    accentLine = Color(0x5C4F6B00),
    onAccent = Color(0xFFFFFFFF),
    warmSoft = Color(0x1FD58514),
    ok = Color(0xFF1E9E57),
    okSoft = Color(0x1F1E9E57),
    card = Color(0xFFFFFFFF),
    cardBorder = Color(0x17141416),
    thumb = Color(0xFFFFFFFF),
    bar = Color(0xD6F6F5F1),
    sheet = Color(0xFFF6F5F1),
    rail = Color(0x09141416),
    gold = Color(0xFFDB9A00),
    silver = Color(0xFF8793AD),
    bronze = Color(0xFFC5733A),
    pin = Color(0xD6F6F5F1),
    dim = Color(0x57141416),
    shadowSoft = Color(0x0F141416),
    shadowThumb = Color(0x1A141416),
    barShadow = Color(0x1F141416),
    ambientWarm = listOf(Color(0x174F6B00), Color(0x0DD5B96E)),
    ambientCool = listOf(Color(0x144F6B00), Color(0x084F6B00)),
    danger = Color(0xFFE5484D),
    dangerSoft = Color(0x1AE5484D),
    favorite = Color(0xFFE63984),
)

val LocalIdjColors = staticCompositionLocalOf { Dark }

/** The player, image viewer and readers stay dark: wrap them in `IdjTheme(dark = true)`. */
@Composable
fun IdjTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) Dark else Light
    val scheme = if (dark) darkColorScheme(
        primary = colors.accent, onPrimary = colors.onAccent, background = colors.bg,
        surface = colors.surface, onBackground = colors.text, onSurface = colors.text,
        secondary = colors.secondary, error = colors.danger, surfaceContainer = colors.sheet, surfaceContainerLow = colors.sheet,
        surfaceContainerHigh = colors.sheet, outline = colors.glassBorder, outlineVariant = colors.hairline,
    ) else lightColorScheme(
        primary = colors.accent, onPrimary = colors.onAccent, background = colors.bg,
        surface = colors.surface, onBackground = colors.text, onSurface = colors.text,
        secondary = colors.secondary, error = colors.danger, surfaceContainer = colors.sheet, surfaceContainerLow = colors.sheet,
        surfaceContainerHigh = colors.sheet, outline = colors.glassBorder, outlineVariant = colors.hairline,
    )
    CompositionLocalProvider(LocalIdjColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

val IdjThemeColors: IdjColors
    @Composable get() = LocalIdjColors.current

/** The player always renders on black with the dark palette (spec `.pl`), whatever the app theme is. */
val IdjDarkColors: IdjColors get() = Dark
