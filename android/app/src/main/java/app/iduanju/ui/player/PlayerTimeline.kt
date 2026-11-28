package app.iduanju.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iduanju.ui.components.DS

/**
 * Elapsed · scrubber · remaining. Only the two time texts recompose, once per second; the track is drawn straight from
 * the clock.
 */
@Composable
internal fun PlayerTimeBar(clock: PlaybackClock, thick: Boolean, preview: SeekPreviewState, modifier: Modifier = Modifier, onSeek: (Long) -> Unit) {
    val style = remember(thick) {
        TextStyle(color = Color.White.copy(0.86f), fontSize = if (thick) 13.sp else 12.sp, fontWeight = FontWeight.Medium,
            fontFeatureSettings = "tnum", shadow = Shadow(Color.Black.copy(0.6f), blurRadius = 4f))
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        ClockText(style) { clock.pos / 1000 }
        Spacer(Modifier.width(10.dp))
        ScrubTrack(clock, thick, preview, Modifier.weight(1f), onSeek)
        Spacer(Modifier.width(10.dp))
        RemainingText(style, clock)
    }
}

@Composable
private fun ClockText(style: TextStyle, seconds: () -> Long) {
    val s by remember { derivedStateOf(seconds) }
    Text(fmtClock(s * 1000), style = style)
}

@Composable
private fun RemainingText(style: TextStyle, clock: PlaybackClock) {
    val s by remember { derivedStateOf { if (clock.dur > 0) (clock.dur - clock.pos).coerceAtLeast(0L) / 1000 else 0L } }
    Text("−" + fmtClock(s * 1000), style = style)
}

/** spec `.ptrack`: 4 dp track, buffered white 30 %, 14 dp round knob; grows while dragging. Tap / drag to seek. */
@Composable
private fun ScrubTrack(clock: PlaybackClock, thick: Boolean, preview: SeekPreviewState, modifier: Modifier, onSeek: (Long) -> Unit) {
    val seek by rememberUpdatedState(onSeek)
    var drag by remember { mutableStateOf<Float?>(null) }
    // Accessibility hears whole seconds, not every 250 ms tick.
    val second = remember(clock) { derivedStateOf { clock.pos / 1000 } }
    // A recomposition-free exit if the bar leaves composition mid-drag (HUD hidden, rotation).
    DisposableEffect(preview) { onDispose { preview.end() } }
    val grow = animateFloatAsState(if (drag != null) 1f else 0f, DS.press(), label = "trk")
    Canvas(
        modifier.height(if (thick) 34.dp else 32.dp)
            .onGloballyPositioned { preview.onTrackPositioned(it) }
            .semantics {
                contentDescription = "播放进度"
                val d = clock.dur; val p = second.value * 1000
                stateDescription = "${fmtClock(p)} / ${fmtClock(d)}"
                progressBarRangeInfo = ProgressBarRangeInfo(if (d > 0) (p.toFloat() / d).coerceIn(0f, 1f) else 0f, 0f..1f)
                setProgress { f -> if (clock.dur > 0) { seek((f.coerceIn(0f, 1f) * clock.dur).toLong()); true } else false }
            }
            .pointerInput(Unit) { detectTapGestures { o -> if (clock.dur > 0) seek((o.x / size.width * clock.dur).toLong()) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o ->
                        val f = (o.x / size.width).coerceIn(0f, 1f)
                        drag = f
                        if (clock.dur > 0) preview.begin(f, clock.pos)
                    },
                    onDragEnd = { drag?.let { if (clock.dur > 0) seek((it * clock.dur).toLong()) }; drag = null; preview.end() },
                    onDragCancel = { drag = null; preview.end() },
                ) { ch, _ ->
                    ch.consume()
                    val f = (ch.position.x / size.width).coerceIn(0f, 1f)
                    drag = f
                    preview.update(f)
                }
            },
    ) {
        val d = clock.dur
        val frac = (drag ?: if (d > 0) clock.pos.toFloat() / d else 0f).coerceIn(0f, 1f)
        val buf = (if (d > 0) clock.buffered.toFloat() / d else 0f).coerceIn(0f, 1f)
        val g = grow.value
        val h = (if (thick) 4.5.dp.toPx() + 3.5.dp.toPx() * g else 3.5.dp.toPx() + 3.5.dp.toPx() * g)
        val knob = (if (thick) 6.dp.toPx() + 3.dp.toPx() * g else 5.dp.toPx() + 3.dp.toPx() * g)
        val cy = size.height / 2f
        val r = CornerRadius(h / 2f, h / 2f)
        val top = cy - h / 2f
        drawRoundRect(Color.White.copy(0.22f), Offset(0f, top), Size(size.width, h), r)
        if (buf > 0f) drawRoundRect(Color.White.copy(0.42f), Offset(0f, top), Size(size.width * buf, h), r)
        if (frac > 0f) drawRoundRect(Color.White, Offset(0f, top), Size(maxOf(h, size.width * frac), h), r)
        val x = (size.width * frac).coerceIn(knob, (size.width - knob).coerceAtLeast(knob))
        if (g > 0.01f) drawCircle(Color.White.copy(0.18f * g), knob * 2f, Offset(x, cy))
        drawCircle(Color.Black.copy(0.32f), knob + 1.dp.toPx(), Offset(x, cy + 1.dp.toPx()))
        drawCircle(Color.White, knob, Offset(x, cy))
    }
}
