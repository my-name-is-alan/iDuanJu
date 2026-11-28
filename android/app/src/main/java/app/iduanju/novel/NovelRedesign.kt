package app.iduanju.novel

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import app.iduanju.core.novel.*
import app.iduanju.ui.components.*
import app.iduanju.ui.theme.IdjThemeColors
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlin.math.roundToInt

internal data class ShelfEntry(
    val sourceId: String,
    val book: NovelHit,
    val totalChapters: Int = 0,
    val currentChapter: Int = -1,
    val currentChapterTitle: String = "",
    val isLocal: Boolean = false,
    val addedAt: Long = 0,
) {
    val key: String get() = "$sourceId|${book.url}"
    val fraction: Float?
        get() = if (currentChapter >= 0 && totalChapters > 0) ((currentChapter + 1).toFloat() / totalChapters).coerceIn(0.01f, 1f) else null
    val remaining: Int
        get() = if (currentChapter >= 0 && totalChapters > 0) (totalChapters - currentChapter - 1).coerceAtLeast(0) else totalChapters
}

private enum class ShelfFilter(val label: String) { All("全部"), Reading("在读"), Local("本地") }

/** Novel shelf aligned with HomeScreen's STVTheme language: one atmospheric hero banner, borderless quick strip, open 3-col grid. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NovelShelf(
    entries: List<ShelfEntry>,
    onContinue: (ShelfEntry) -> Unit,
    onBook: (ShelfEntry) -> Unit,
    onDelete: (List<ShelfEntry>) -> Unit = {},
    onImport: () -> Unit,
    onSearch: () -> Unit,
    onRank: () -> Unit,
    onSources: () -> Unit,
    embedded: Boolean = false,
    loading: Boolean = false,
) {
    val c = IdjThemeColors
    var filter by remember { mutableStateOf(ShelfFilter.All) }
    var managing by remember { mutableStateOf(false) }
    var selectedKeys by remember { mutableStateOf(setOf<String>()) }
    var confirmTargets by remember { mutableStateOf<List<ShelfEntry>>(emptyList()) }
    fun entryKey(e: ShelfEntry) = e.key
    val cols = LocalLayoutMetrics.current.posterCols
    val hero = remember(entries) { entries.firstOrNull { it.currentChapter >= 0 } ?: entries.firstOrNull() }
    val filtered = remember(entries, filter) {
        when (filter) {
            ShelfFilter.All -> entries
            ShelfFilter.Reading -> entries.filter { it.currentChapter >= 0 }
            ShelfFilter.Local -> entries.filter { it.isLocal }
        }
    }
    val rows = remember(filtered, cols) { filtered.chunked(cols) }
    LaunchedEffect(entries.size) {
        if (entries.isEmpty()) { managing = false; selectedKeys = emptySet() }
    }

    LazyColumn(
        Modifier.fillMaxSize().testTag("novel.shelf"),
        contentPadding = PaddingValues(top = if (embedded) 10.dp else 4.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        if (!embedded) {
            item(key = "title") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("小说", color = c.text, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                    RoundIconButton(Icons.Rounded.Search, "搜书", onClick = onSearch)
                    Spacer(Modifier.width(8.dp))
                    RoundIconButton(Icons.Rounded.Add, "导入书籍", onClick = onImport)
                }
            }
        }
        if (hero != null) {
            item(key = "hero") {
                ContinueHeroBanner(
                    entry = hero,
                    onOpen = { onBook(hero) },
                    onContinue = { onContinue(hero) },
                    modifier = Modifier.padding(horizontal = 16.dp).padding(top = 6.dp),
                )
            }
        }
        item(key = "quick") {
            Column(Modifier.padding(horizontal = 16.dp).padding(top = if (hero != null) 16.dp else 8.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 14.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    ShelfQuickOpen(Icons.Rounded.Search, "搜书", Modifier.weight(1f), onSearch)
                    ShelfQuickOpen(Icons.Rounded.Leaderboard, "排行榜", Modifier.weight(1f), onRank)
                    ShelfQuickOpen(BooksIcon, "书源", Modifier.weight(1f), onSources)
                    ShelfQuickOpen(Icons.Rounded.FileDownload, "导入", Modifier.weight(1f), onImport)
                }
                Hairline()
            }
        }
        item(key = "shelf-header") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("我的书架", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                if (entries.isNotEmpty()) {
                    Spacer(Modifier.width(7.dp))
                    Text("${entries.size} 本", color = c.tertiary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.weight(1f))
                if (entries.isNotEmpty()) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(start = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!managing) {
                            ShelfFilter.entries.forEach { f -> ShelfFilterChip(f.label, f == filter) { filter = f } }
                        }
                        Box(
                            Modifier
                                .heightIn(min = 30.dp)
                                .clip(CircleShape)
                                .background(if (managing) c.accentSoft else c.glass)
                                .pressClick(0.95f) {
                                    managing = !managing
                                    if (!managing) selectedKeys = emptySet()
                                }
                                .padding(horizontal = 11.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (managing) "完成" else "管理",
                                color = if (managing) c.accent else c.text,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                        }
                    }
                } else {
                    MoreLink("导入", onClick = onImport)
                }
            }
        }
        if (managing && filtered.isNotEmpty()) {
            item(key = "manage-bar") {
                val allSelected = remember(filtered, selectedKeys) { filtered.all { entryKey(it) in selectedKeys } }
                val picked = remember(entries, selectedKeys) { entries.filter { entryKey(it) in selectedKeys } }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 12.dp)
                        .dsCard(14.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlassTextButton(if (allSelected) "取消全选" else "全选 (${filtered.size})", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) {
                        selectedKeys = if (allSelected) emptySet() else filtered.map(::entryKey).toSet()
                    }
                    Spacer(Modifier.weight(1f))
                    GlassTextButton(
                        if (picked.isEmpty()) "点击书籍勾选" else "删除所选 (${picked.size})",
                        color = c.danger,
                        enabled = picked.isNotEmpty(),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    ) { confirmTargets = picked }
                }
            }
        }
        if (entries.isEmpty() && loading) {
            items(2, key = { "skeleton$it" }, contentType = { "skeleton" }) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(cols) { PosterSkeleton(Modifier.weight(1f), aspect = 0.71f) }
                }
            }
        } else if (entries.isEmpty()) {
            item(key = "empty") {
                EmptyNovel("书架空空的", "导入本地 TXT，或添加书源后搜索并缓存喜欢的小说。")
            }
        } else if (filtered.isEmpty()) {
            item(key = "empty-filter") {
                EmptyNovel(
                    if (filter == ShelfFilter.Local) "暂无本地书籍" else "暂无阅读记录",
                    if (filter == ShelfFilter.Local) "点击上方「导入」选择本地 TXT 文件。" else "打开书架里的任意书籍开始阅读，进度会自动同步到这里。",
                )
            }
        } else {
            items(rows, key = { "row|" + entryKey(it.first()) }, contentType = { "row" }) { row ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    row.forEach { entry ->
                        val k = entryKey(entry)
                        val checked = k in selectedKeys
                        ShelfBookCell(
                            entry = entry,
                            modifier = Modifier.weight(1f),
                            managing = managing,
                            selected = checked,
                            onClick = {
                                if (managing) {
                                    selectedKeys = if (checked) selectedKeys - k else selectedKeys + k
                                } else {
                                    onBook(entry)
                                }
                            },
                            onLongClick = { confirmTargets = listOf(entry) },
                        )
                    }
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    if (confirmTargets.isNotEmpty()) {
        val label = if (confirmTargets.size == 1) "「${confirmTargets.first().book.name}」" else "所选的 ${confirmTargets.size} 本书"
        GlassConfirm(
            onDismissRequest = { confirmTargets = emptyList() },
            title = "从书架删除$label？",
            message = "会同时清除本机缓存与阅读记录。",
            confirmText = "删除",
            destructive = true,
            onConfirm = {
                val toRemove = confirmTargets
                confirmTargets = emptyList()
                onDelete(toRemove)
                selectedKeys = selectedKeys - toRemove.map(::entryKey).toSet()
                if (managing && selectedKeys.isEmpty()) managing = false
            },
        )
    }
}

@Composable
private fun ShelfFilterChip(label: String, on: Boolean, onClick: () -> Unit) {
    val c = IdjThemeColors
    val src = remember { MutableInteractionSource() }
    Box(
        Modifier
            .pressScale(src, 0.95f)
            .heightIn(min = 30.dp)
            .clip(CircleShape)
            .background(if (on) c.accentSoft else Color.Transparent)
            .selectable(on, src, null, true, Role.Tab, onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (on) c.accent else c.secondary,
            fontSize = 12.sp,
            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@Composable
private fun ContinueHeroBanner(
    entry: ShelfEntry,
    onOpen: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = IdjThemeColors
    val shape = RoundedCornerShape(20.dp)
    val frac = entry.fraction
    val pct = frac?.let { "${(it * 100).toInt()}%" }
    Box(
        modifier
            .fillMaxWidth()
            .shadow(if (c.dark) 0.dp else 14.dp, shape, ambientColor = Color(0x2810141C), spotColor = Color(0x3010141C))
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF25344E), Color(0xFF172032), Color(0xFF121722))))
            .pressClick(0.985f, onClick = onOpen),
    ) {
        if (entry.book.cover.isNotBlank()) {
            NovelBlurredBackdrop(entry.book.cover, Modifier.matchParentSize(), radius = 22.dp, alpha = 0.72f)
        }
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color(0x70111724),
                        0.55f to Color(0xB8111724),
                        1f to Color(0xDC0E121C),
                    )
                )
                .border(1.dp, Color.White.copy(0.12f), shape),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
            horizontalArrangement = Arrangement.spacedBy(15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NovelPoster(entry.book, Modifier.width(82.dp).aspectRatio(0.71f), thumb = 82.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    if (pct != null) "最近在读 · $pct" else if (entry.isLocal) "本地书籍 · 离线可读" else "继续阅读",
                    color = Color(0xFFF2B55C),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    entry.book.name,
                    color = Color.White,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
                val subtitle = entry.currentChapterTitle.ifBlank {
                    listOf(entry.book.author, entry.book.kind, if (entry.isLocal) "TXT 文件" else "").filter { it.isNotBlank() }.joinToString(" · ")
                }
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        color = Color(0xFFEEF0F3).copy(0.74f),
                        fontSize = 12.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                if (frac != null || entry.totalChapters > 0) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.weight(1f).height(3.dp).clip(CircleShape).background(Color.White.copy(0.20f))) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(frac ?: 0f).clip(CircleShape).background(Color(0xFF8AB4FF)))
                        }
                        val tail = when {
                            frac != null && entry.remaining > 0 -> "余 ${entry.remaining} 章"
                            entry.totalChapters > 0 -> "共 ${entry.totalChapters} 章"
                            else -> ""
                        }
                        if (tail.isNotEmpty()) Text(tail, color = Color(0xFFEEF0F3).copy(0.70f), fontSize = 11.sp, maxLines = 1)
                    }
                }
                Spacer(Modifier.height(12.dp))
                PillButton(
                    if (frac != null || entry.isLocal) "继续阅读" else "开始阅读",
                    icon = Icons.Rounded.PlayArrow,
                    tone = PillTone.White,
                    small = true,
                    onClick = onContinue,
                )
            }
        }
    }
}

@Composable
private fun ShelfQuickOpen(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    val c = IdjThemeColors
    Column(
        modifier.pressClick(0.95f, label = label, onClick = onClick).padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(38.dp).clip(CircleShape).background(c.glass),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(18.dp), tint = c.text)
        }
        Text(label, color = c.secondary, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfBookCell(
    entry: ShelfEntry,
    modifier: Modifier,
    managing: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    val c = IdjThemeColors
    val frac = entry.fraction
    val src = remember { MutableInteractionSource() }
    Column(
        modifier.pressScale(src)
            .combinedClickable(src, null, onLongClickLabel = "删除", onLongClick = onLongClick, onClick = onClick)
            .semantics { if (managing) this.selected = selected },
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.71f)) {
            NovelPoster(
                book = entry.book,
                modifier = Modifier.fillMaxSize(),
                badge = if (entry.isLocal) "TXT" else null,
                progress = frac,
            )
            if (managing) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(if (selected) c.accent else Color.Black.copy(alpha = 0.45f))
                        .border(1.5.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
        }
        Text(
            entry.book.name,
            color = c.text,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 7.dp),
        )
        val stat = when {
            frac != null && entry.isLocal -> "本地 · ${(frac * 100).toInt()}%"
            frac != null -> "已读 ${(frac * 100).toInt()}%"
            entry.isLocal -> "本地 · 未读"
            entry.totalChapters > 0 -> "未读 · ${entry.totalChapters} 章"
            else -> entry.book.author.ifBlank { "未读" }
        }
        Text(
            stat,
            color = c.tertiary,
            fontSize = 11.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** Shared blurred cover backdrop (HomeScreen's BlurBackdrop): a 48×64 decode, so the blur itself can stay ≤ 12 dp; older APIs upscale a tiny one. */
@Composable
internal fun NovelBlurredBackdrop(url: String?, modifier: Modifier = Modifier, radius: Dp = 28.dp, alpha: Float = 1f) {
    if (url.isNullOrBlank()) return
    val ctx = LocalContext.current
    if (android.os.Build.VERSION.SDK_INT >= 31) {
        val req = remember(url) { ImageRequest.Builder(ctx).data(url).size(48, 64).memoryCacheKey("novel-blur:$url").build() }
        AsyncImage(req, null, modifier.scale(1.25f).blur((radius / 2).coerceAtMost(12.dp)), contentScale = ContentScale.Crop, alpha = alpha)
    } else {
        val req = remember(url) { ImageRequest.Builder(ctx).data(url).size(16, 22).memoryCacheKey("novel-blur16:$url").build() }
        AsyncImage(req, null, modifier, contentScale = ContentScale.Crop, alpha = alpha, filterQuality = FilterQuality.High)
    }
}

/**
 * Book cover with physical spine crease, subtle top-edge sheen, optional corner badge / bottom progress bar / rank numeral.
 * [thumb] = the fixed width of small covers: they decode at exactly that size and use the compact generated cover.
 */
@Composable
internal fun NovelPoster(
    book: NovelHit,
    modifier: Modifier,
    corner: Dp = 9.dp,
    badge: String? = null,
    progress: Float? = null,
    rank: Int? = null,
    thumb: Dp? = null,
) {
    val c = IdjThemeColors
    val shape = RoundedCornerShape(corner)
    Box(
        modifier
            .shadow(if (c.dark) 8.dp else 6.dp, shape, ambientColor = Color.Black.copy(0.28f), spotColor = Color.Black.copy(0.32f))
            .clip(shape)
            .background(Color(0xFF1C2331)),
        contentAlignment = Alignment.Center,
    ) {
        if (book.cover.isNotBlank()) {
            val ctx = LocalContext.current
            val px = if (thumb == null) 0 else with(LocalDensity.current) { thumb.roundToPx() }
            val req = remember(book.cover, px) {
                ImageRequest.Builder(ctx).data(book.cover).apply { if (px > 0) size(px, (px / 0.71f).roundToInt()) }.build()
            }
            AsyncImage(req, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        } else {
            GeneratedBookCover(book, small = thumb != null && thumb < 74.dp)
        }
        // Tactile left spine highlight + crease shadow + subtle inner rim
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.horizontalGradient(
                        0.0f to Color.White.copy(0.24f),
                        0.03f to Color.White.copy(0.06f),
                        0.055f to Color.Black.copy(0.24f),
                        0.075f to Color.White.copy(0.10f),
                        0.12f to Color.Transparent,
                    )
                )
                .border(0.6.dp, Color.White.copy(0.18f), shape),
        )
        if (rank != null) {
            val medal = when (rank) { 1 -> Color(0xFFFFC740); 2 -> Color(0xFFAEBBD6); 3 -> Color(0xFFDB8C52); else -> Color.Black }
            val off = with(LocalDensity.current) { 2.dp.toPx() }
            Text(
                "$rank",
                Modifier.align(Alignment.TopStart).padding(start = 7.dp, top = 1.dp),
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Black,
                fontStyle = FontStyle.Italic,
                style = TextStyle(
                    lineHeight = 32.sp,
                    shadow = Shadow(medal, Offset(off, off), 0f),
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
            )
        }
        if (!badge.isNullOrBlank()) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .height(17.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0x990E1014))
                    .padding(horizontal = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(badge, color = Color.White, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
        if (progress != null) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp).background(Color.Black.copy(0.36f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0.02f, 1f)).background(Color(0xFF8AB4FF)))
            }
        }
    }
}

/** Muted clothbound editorial palettes for coverless books (aligned with STVTheme dark-slate aesthetic). */
private val clothPalettes = listOf(
    Pair(0xFF29394F, 0xFF131C29),
    Pair(0xFF3A2C46, 0xFF1B1424),
    Pair(0xFF233B46, 0xFF0F1E24),
    Pair(0xFF3A302B, 0xFF1D1714),
    Pair(0xFF283E35, 0xFF12201A),
    Pair(0xFF472C33, 0xFF221217),
    Pair(0xFF2F3744, 0xFF161B23),
    Pair(0xFF3F3626, 0xFF1F1A11),
).map { (a, b) -> Color(a) to Color(b) }

internal fun bookCoverBaseColor(title: String): Pair<Color, Color> =
    clothPalettes[Math.floorMod(title.hashCode(), clothPalettes.size)]

/** Clothbound fallback book cover (GeneratedBookCover v2): muted cloth gradient, delicate inset frame, serif typography. */
@Composable
private fun BoxScope.GeneratedBookCover(book: NovelHit, small: Boolean) {
    val (c1, c2) = remember(book.name) { bookCoverBaseColor(book.name) }
    Box(Modifier.matchParentSize()) {
        val inset = if (small) 5.dp else 7.dp
        val leftInset = if (small) 8.dp else 11.dp
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.linearGradient(listOf(c1, c2), Offset(0f, 0f), Offset(size.width * 0.4f, size.height)))
            drawRect(
                Brush.radialGradient(
                    0f to Color(0x24DCE8FA),
                    0.65f to Color.Transparent,
                    center = Offset(size.width * 0.8f, size.height * 0.16f),
                    radius = size.width * 0.9f,
                )
            )
            val l = leftInset.toPx()
            val t = inset.toPx()
            drawRoundRect(
                color = Color(0x3DEEF2FA),
                topLeft = Offset(l, t),
                size = Size((size.width - l - t).coerceAtLeast(1f), (size.height - t * 2).coerceAtLeast(1f)),
                cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                style = Stroke(width = 0.75.dp.toPx()),
            )
        }
        Text(
            book.name,
            Modifier
                .align(Alignment.TopCenter)
                .topAt(0.24f)
                .padding(start = leftInset + 3.dp, end = inset + 3.dp),
            color = Color(0xFFEEF2F8),
            fontSize = if (small) 9.5.sp else 14.sp,
            lineHeight = if (small) 12.sp else 18.sp,
            letterSpacing = if (small) 0.2.sp else 1.sp,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (!small && book.author.isNotBlank()) {
            Text(
                book.author,
                Modifier.align(Alignment.BottomCenter).padding(start = leftInset, end = inset, bottom = 12.dp),
                color = Color(0xA8EEF2FA),
                fontSize = 9.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Starts the content at [fraction] of the available height (BoxWithConstraints would subcompose every cover in a list). */
private fun Modifier.topAt(fraction: Float): Modifier = layout { measurable, constraints ->
    val top = if (constraints.hasBoundedHeight) (constraints.maxHeight * fraction).roundToInt() else 0
    val placeable = measurable.measure(constraints.offset(vertical = -top).copy(minHeight = 0))
    layout(placeable.width, placeable.height + top) { placeable.place(0, top) }
}

/** The design's `books` glyph: two upright volumes and a leaning one. */
internal val BooksIcon: ImageVector by lazy {
    ImageVector.Builder("books", 24.dp, 24.dp, 24f, 24f).addPath(
        addPathNodes(
            "M5,4h1.6a1,1 0 0 1 1,1v14a1,1 0 0 1 -1,1h-1.6a1,1 0 0 1 -1,-1v-14a1,1 0 0 1 1,-1z" +
                "M10.6,4h1.6a1,1 0 0 1 1,1v14a1,1 0 0 1 -1,1h-1.6a1,1 0 0 1 -1,-1v-14a1,1 0 0 1 1,-1z" +
                "M15.4,6.3l3.3,-0.9 3,13.4 -3.3,0.9z"
        ),
        fill = SolidColor(Color.Black),
    ).build()
}

@Composable
internal fun EmptyNovel(title: String, note: String) =
    EmptyState(title, Modifier.padding(vertical = 12.dp), note = note, icon = Icons.AutoMirrored.Rounded.MenuBook)

/** Integrated compact search header: Back + glass input (with inline clear & 搜索 action) + 书源 count capsule. */
@Composable
internal fun NovelSearchTopBar(
    value: String,
    enabledSources: Int,
    onValue: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    onSources: () -> Unit,
) {
    val c = IdjThemeColors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BackButton(onClick = onBack)
        GlassTextField(
            value = value,
            onValueChange = onValue,
            modifier = Modifier.weight(1f),
            placeholder = "书名或作者",
            icon = Icons.Rounded.Search,
            capsule = true,
            minHeight = 44.dp,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            trailing = if (value.isEmpty()) null else ({ MiniButton("搜索", onSubmit) }),
        )
        Row(
            Modifier
                .heightIn(min = 36.dp)
                .clip(CircleShape)
                .background(c.glass)
                .border(1.dp, c.hairline, CircleShape)
                .pressClick(label = "书源", onClick = onSources)
                .padding(horizontal = 11.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("书源", color = c.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (enabledSources > 0) {
                Text("$enabledSources", color = c.accent, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun highlightTerm(text: String, query: String, accent: Color): AnnotatedString {
    val q = query.trim()
    if (q.isEmpty()) return AnnotatedString(text)
    val idx = text.indexOf(q, ignoreCase = true)
    if (idx < 0) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text.substring(0, idx))
        withStyle(SpanStyle(color = accent)) { append(text.substring(idx, idx + q.length)) }
        append(text.substring(idx + q.length))
    }
}

/** 0-card open search result row; lead row (exact match) gets slightly larger cover & 2-line intro. */
@Composable
internal fun NovelSearchRow(
    book: NovelHit,
    query: String,
    sourceName: String,
    sourceCount: Int,
    lead: Boolean = false,
    onClick: () -> Unit,
) {
    val c = IdjThemeColors
    val exact = remember(book.name, query) { query.trim().isNotEmpty() && book.name.equals(query.trim(), ignoreCase = true) }
    val title = remember(book.name, query, c.accent) { highlightTerm(book.name, query, c.accent) }
    val meta = remember(book.author, book.kind) { listOf(book.author, book.kind).filter { it.isNotBlank() }.joinToString(" · ") }
    val thumb = if (lead) 64.dp else 58.dp
    Row(
        Modifier
            .fillMaxWidth()
            .pressClick(0.985f, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = if (lead) 15.dp else 13.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp),
        verticalAlignment = if (lead) Alignment.Top else Alignment.CenterVertically,
    ) {
        NovelPoster(
            book = book,
            modifier = Modifier.width(thumb).aspectRatio(0.71f),
            corner = if (lead) 8.dp else 7.dp,
            thumb = thumb,
        )
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        title,
                        color = c.text,
                        fontSize = if (lead) 17.sp else 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (exact) DSStatus("最佳匹配", StatusTone.Accent)
                }
                DSStatus("$sourceCount 个书源", if (sourceCount > 1) StatusTone.Accent else StatusTone.Muted)
            }
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    color = c.secondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            if (book.intro.isNotBlank()) {
                Text(
                    book.intro,
                    color = if (lead) c.secondary else c.tertiary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    maxLines = if (lead) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (book.latest.isNotBlank()) "最新：${book.latest}" else "",
                    color = c.tertiary,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (sourceName.isNotBlank()) {
                    Text(
                        if (sourceCount > 1) "首选 $sourceName" else sourceName,
                        color = c.secondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 132.dp),
                    )
                }
            }
        }
    }
}

/** 0-card Top-3 book podium matching short-drama 01-rank podium. */
@Composable
internal fun NovelRankPodium(books: List<NovelHit>, onOpen: (NovelHit) -> Unit) {
    val c = IdjThemeColors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 6.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        books.take(3).forEachIndexed { idx, book ->
            val sub = remember(book.author, book.kind) {
                val firstTag = book.kind.split('·', '/', ',', '，', '|').map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
                listOf(book.author, firstTag).filter { it.isNotBlank() }.joinToString(" · ")
            }
            Column(Modifier.weight(1f).pressClick(0.97f) { onOpen(book) }) {
                NovelPoster(
                    book = book,
                    modifier = Modifier.fillMaxWidth().aspectRatio(0.71f),
                    corner = 9.dp,
                    rank = idx + 1,
                )
                Text(
                    book.name,
                    color = c.text,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 7.dp),
                )
                if (sub.isNotBlank()) {
                    Text(
                        sub,
                        color = c.secondary,
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/** 0-card full-width ranked row (#4+, or #1..#2 when a list has fewer than 3 items). */
@Composable
internal fun NovelRankRow(book: NovelHit, rank: Int, onClick: () -> Unit) {
    val c = IdjThemeColors
    val tags = remember(book.kind) { book.kind.split('·', '/', ',', '，', '|').map { it.trim() }.filter { it.isNotEmpty() }.take(2) }
    val meta = remember(book.author, book.latest) { listOf(book.author, book.latest).filter { it.isNotBlank() }.joinToString(" · ") }
    Row(
        Modifier
            .fillMaxWidth()
            .pressClick(0.985f, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RankNumeral(rank, 19, Modifier.width(24.dp))
        NovelPoster(book, Modifier.width(56.dp).aspectRatio(0.71f), corner = 7.dp, thumb = 56.dp)
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    book.name,
                    color = c.text,
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (tags.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        tags.forEach { DSTag(it) }
                    }
                }
            }
            if (book.intro.isNotBlank()) {
                Text(
                    book.intro,
                    color = c.secondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    color = c.tertiary,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
internal fun NovelSourcesPage(
    sources: List<NovelSource>,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onToggle: (NovelSource, Boolean) -> Unit,
    onDelete: (NovelSource) -> Unit,
    loading: Boolean = false,
    onLogin: (NovelSource) -> Unit = {},
    onDebug: (NovelSource) -> Unit = {},
    onEdit: (NovelSource) -> Unit = {},
    onCheckAll: () -> Unit = {},
    onReplaceRules: () -> Unit = {},
) {
    val c = IdjThemeColors
    val context = androidx.compose.ui.platform.LocalContext.current
    var filter by remember { mutableStateOf("全部") }
    var search by remember { mutableStateOf("") }
    val enabledCount = remember(sources) { sources.count { it.enabled } }
    val needsLogin = remember(sources) { sources.filter { it.hasLogin }.mapTo(HashSet()) { it.id } }
    val jsSources = remember(sources) { sources.filter { it.isJsSource }.mapTo(HashSet()) { it.id } }
    val checks = remember(sources) { app.iduanju.core.novel.LegadoTools.allChecks() }
    val failedIds = remember(sources, checks) { sources.filter { checks[it.id]?.let { r -> r.status != app.iduanju.core.novel.LegadoTools.CheckStatus.OK } == true }.mapTo(HashSet()) { it.id } }
    val shown = remember(sources, filter, search, failedIds) {
        val base = when (filter) {
            "已启用" -> sources.filter { it.enabled }
            "需登录" -> sources.filter { it.id in needsLogin }
            "JS源" -> sources.filter { it.id in jsSources }
            "校验失败" -> sources.filter { it.id in failedIds }
            else -> sources
        }
        val q = search.trim()
        if (q.isEmpty()) base else base.filter { it.name.contains(q, true) || it.id.contains(q, true) || it.group.contains(q, true) }
    }
    Column(Modifier.fillMaxSize().dsAmbient(cool = true).statusBarsPadding()) {
        DSNavBar(
            "书源",
            leading = { BackButton(onClick = onBack) },
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundIconButton(Icons.Rounded.AutoFixHigh, "替换净化", onClick = onReplaceRules)
                    RoundIconButton(Icons.Rounded.FactCheck, "批量校验", onClick = onCheckAll)
                    RoundIconButton(Icons.Rounded.Add, "导入书源", Tone.Accent, onClick = onAdd)
                }
            },
        )
        Text("共 ${sources.size} 个书源 · 已启用 $enabledCount 个", color = c.secondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
        GlassSearchField(search, { search = it }, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), placeholder = "搜索书源名称、域名或分组")
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("全部", "已启用", "需登录", "JS源", "校验失败").forEach { f ->
                ToneChip(
                    f,
                    f == filter,
                    neutral = f == "全部",
                    count = when (f) {
                        "已启用" -> enabledCount
                        "需登录" -> needsLogin.size
                        "JS源" -> jsSources.size
                        "校验失败" -> failedIds.size
                        else -> sources.size
                    },
                ) { filter = f }
            }
        }
        LazyColumn(
            Modifier.padding(top = 6.dp).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
        ) {
            if (loading) items(6, key = { "skeleton$it" }, contentType = { "skeleton" }) { SkeletonBlock(Modifier.padding(vertical = 5.dp).fillMaxWidth().height(58.dp), 18.dp) }
            else if (sources.isEmpty()) item(key = "empty") { EmptyNovel("还没有书源", "导入 Legado 书源 JSON 或单文件 .js 书源，即可搜索与浏览榜单。") }
            itemsIndexed(shown, key = { _, s -> s.id }, contentType = { _, _ -> "source" }) { i, s ->
                SourceRow(
                    s = s,
                    needsLogin = s.id in needsLogin,
                    checkResult = checks[s.id],
                    first = i == 0,
                    last = i == shown.lastIndex,
                    onToggle = onToggle,
                    onDelete = onDelete,
                    onLogin = onLogin,
                    onDebug = onDebug,
                    onEdit = onEdit,
                    onExport = { sc ->
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val raw = if (sc.isJsSource) sc.mainJs else sc.json.toString(2)
                        cm.setPrimaryClip(android.content.ClipData.newPlainText(sc.name, raw))
                    },
                )
            }
        }
    }
}

@Composable
private fun SourceRow(
    s: NovelSource,
    needsLogin: Boolean,
    checkResult: app.iduanju.core.novel.LegadoTools.CheckResult?,
    first: Boolean,
    last: Boolean,
    onToggle: (NovelSource, Boolean) -> Unit,
    onDelete: (NovelSource) -> Unit,
    onLogin: (NovelSource) -> Unit,
    onDebug: (NovelSource) -> Unit,
    onEdit: (NovelSource) -> Unit,
    onExport: (NovelSource) -> Unit,
) {
    val c = IdjThemeColors
    var menu by remember { mutableStateOf(false) }
    val host = remember(s.id) { runCatching { java.net.URI(s.id).host }.getOrNull().orEmpty() }
    val loggedIn = remember(s.id, needsLogin) { needsLogin && app.iduanju.core.novel.LegadoLogin.isLoggedIn(s) }
    val kindLabel = when {
        s.isJsSource -> "JS源"
        s.isRSS -> "RSS"
        s.isImage -> "漫画"
        s.isFile -> "下载"
        s.bookSourceType == 1 -> "有声"
        s.bookSourceType == 4 -> "视频"
        else -> null
    }
    val top = if (first) 18.dp else 0.dp
    val bottom = if (last) 18.dp else 0.dp
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(top, top, bottom, bottom)).background(c.card).padding(start = 14.dp, end = 6.dp)) {
        if (!first) Hairline(Modifier.padding(end = 8.dp))
        Row(Modifier.fillMaxWidth().heightIn(min = 66.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(s.name, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    kindLabel?.let { DSStatus(it, StatusTone.Accent) }
                    if (needsLogin) DSStatus(if (loggedIn) "已登录" else "需登录", if (loggedIn) StatusTone.Ok else StatusTone.Warn)
                    checkResult?.let { cr ->
                        DSStatus(if (cr.status == app.iduanju.core.novel.LegadoTools.CheckStatus.OK) "${cr.latencyMs}ms" else cr.status.label, if (cr.status == app.iduanju.core.novel.LegadoTools.CheckStatus.OK) StatusTone.Ok else StatusTone.Warn)
                    }
                }
                val sub = listOfNotNull(s.group.takeIf { it.isNotBlank() }, host.takeIf { it.isNotBlank() }).joinToString(" · ")
                Text(sub.ifBlank { s.id }, color = c.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (needsLogin && !loggedIn) MiniButton("登录") { onLogin(s) }
            GlassSwitch(s.enabled, { onToggle(s, it) }, label = s.name)
            Box {
                PlainIconButton(Icons.Rounded.MoreVert, "更多", tint = c.secondary) { menu = true }
                GlassMenu(menu, { menu = false }) {
                    if (needsLogin) GlassMenuItem("书源登录", icon = Icons.Rounded.Person) { menu = false; onLogin(s) }
                    GlassMenuItem("单源调试", icon = Icons.Rounded.BugReport) { menu = false; onDebug(s) }
                    GlassMenuItem("编辑源码", icon = Icons.Rounded.Code) { menu = false; onEdit(s) }
                    GlassMenuItem("复制导出", icon = Icons.Rounded.ContentCopy) { menu = false; onExport(s) }
                    GlassMenuItem("删除书源", icon = Icons.Rounded.DeleteOutline, destructive = true) { menu = false; onDelete(s) }
                }
            }
        }
    }
}
