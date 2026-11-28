package app.iduanju.ui.components

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iduanju.ui.theme.IdjThemeColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Loading / empty / error states: skeletons first, spinners only for short inline waits, stale content never blanked.

private class ShimmerNode(var tint: Color) : Modifier.Node(), DrawModifierNode {
    private var phase = 0f
    private var job: Job? = null
    override fun onAttach() {
        job = coroutineScope.launch {
            val motion = coroutineContext[MotionDurationScale]
            while (isActive) {
                // Animations switched off in the system settings: hold a static highlight until they come back.
                if (motion?.scaleFactor == 0f) {
                    phase = 0.5f
                    invalidateDraw()
                    snapshotFlow { motion.scaleFactor }.first { it > 0f }
                }
                // Infinite-animation clock: UI tests stay idle while a skeleton is visible.
                withInfiniteAnimationFrameNanos { t ->
                    phase = (t / 1_000_000L % PERIOD_MS) / PERIOD_MS.toFloat()
                    invalidateDraw()
                }
            }
        }
    }
    override fun onDetach() { job?.cancel(); job = null }
    override fun ContentDrawScope.draw() {
        drawContent()
        val w = size.width
        val x = -w + phase * 3f * w
        drawRect(Brush.linearGradient(listOf(Color.Transparent, tint, Color.Transparent), Offset(x, 0f), Offset(x + w, size.height * 0.4f)))
    }
    private companion object { const val PERIOD_MS = 1500L }
}

private data class ShimmerElement(val tint: Color) : ModifierNodeElement<ShimmerNode>() {
    override fun create() = ShimmerNode(tint)
    override fun update(node: ShimmerNode) { node.tint = tint }
    override fun InspectorInfo.inspectableProperties() { name = "shimmer" }
}

/** Moving highlight over a placeholder; draw-phase only. */
@Composable
fun Modifier.shimmer(): Modifier = this.then(ShimmerElement(Color.White.copy(alpha = if (IdjThemeColors.dark) 0.07f else 0.55f)))

/** One skeleton block. */
@Composable
fun SkeletonBlock(modifier: Modifier = Modifier, corner: Dp = 10.dp) {
    Box(modifier.clip(RoundedCornerShape(corner)).background(IdjThemeColors.glass).shimmer())
}

/** Poster cell skeleton (3:4 cover + two text lines). */
@Composable
fun PosterSkeleton(modifier: Modifier = Modifier, aspect: Float = 3f / 4f) {
    Column(modifier) {
        SkeletonBlock(Modifier.fillMaxWidth().aspectRatio(aspect), 12.dp)
        SkeletonBlock(Modifier.padding(top = 8.dp).fillMaxWidth(0.78f).height(12.dp), 4.dp)
        SkeletonBlock(Modifier.padding(top = 6.dp).fillMaxWidth(0.5f).height(10.dp), 4.dp)
    }
}

/** List row skeleton (thumbnail + two lines). */
@Composable
fun RowSkeleton(modifier: Modifier = Modifier, thumb: Dp = 56.dp) {
    Row(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SkeletonBlock(Modifier.size(thumb, thumb * 4 / 3), 10.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SkeletonBlock(Modifier.fillMaxWidth(0.66f).height(14.dp), 4.dp)
            SkeletonBlock(Modifier.fillMaxWidth(0.4f).height(11.dp), 4.dp)
        }
    }
}

/** Centred spinner with an optional caption, for a wait inside an already laid-out area. */
@Composable
fun LoadingState(modifier: Modifier = Modifier, text: String? = null) {
    Column(modifier.fillMaxWidth().padding(24.dp).semantics { stateDescription = text ?: "加载中" }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GlassSpinner()
        if (text != null) Text(text, color = IdjThemeColors.secondary, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun EmptyState(title: String, modifier: Modifier = Modifier, note: String? = null, icon: ImageVector = Icons.Rounded.Inbox, actionText: String? = null, onAction: () -> Unit = {}) =
    StateBlock(title, note, icon, actionText, onAction, modifier)

@Composable
fun ErrorState(message: String, modifier: Modifier = Modifier, note: String? = null, retryText: String? = "重试", onRetry: () -> Unit = {}) =
    StateBlock(message, note, Icons.Rounded.CloudOff, retryText, onRetry, modifier)

@Composable
private fun StateBlock(title: String, note: String?, icon: ImageVector, actionText: String?, onAction: () -> Unit, modifier: Modifier) {
    val c = IdjThemeColors
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        IconWell(icon, 60.dp)
        Text(title, color = c.text, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp))
        DoodleCrayonWave(c.accent.copy(alpha = 0.45f), waves = 3, modifier = Modifier.padding(top = 6.dp).size(54.dp, 8.dp))
        if (!note.isNullOrEmpty()) Text(note, color = c.secondary, fontSize = 13.sp, lineHeight = 19.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        if (actionText != null) GlassButton(actionText, Modifier.padding(top = 16.dp), ButtonTone.Primary, ButtonSize.Regular, onClick = onAction)
    }
}

/**
 * Pull-to-refresh with the app's own indicator (a small glass disc) instead of the Material arrow. [topInset] = height
 * of a header drawn over the content, so the disc rests below it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassPullToRefresh(isRefreshing: Boolean, onRefresh: () -> Unit, modifier: Modifier = Modifier, topInset: Dp = 0.dp, content: @Composable BoxScope.() -> Unit) {
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        state = state,
        indicator = {
            Box(
                Modifier.align(Alignment.TopCenter).size(40.dp).graphicsLayer {
                    val k = if (isRefreshing) 1f else state.distanceFraction.coerceIn(0f, 1.3f)
                    translationY = k * (56.dp + topInset).toPx() - 44.dp.toPx()
                    alpha = (k * 1.4f).coerceIn(0f, 1f)
                    val s = 0.7f + 0.3f * k.coerceAtMost(1f)
                    scaleX = s; scaleY = s
                }.glassSurface(RoundedCornerShape(50), shadowRadius = 8.dp, fallbackAlpha = 1.6f),
                contentAlignment = Alignment.Center,
            ) {
                if (isRefreshing || state.distanceFraction > 0f) GlassSpinner(size = 20.dp)
            }
        },
        content = content,
    )
}
