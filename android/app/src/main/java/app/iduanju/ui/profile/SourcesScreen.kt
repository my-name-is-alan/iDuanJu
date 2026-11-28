package app.iduanju.ui.profile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iduanju.IdjApp
import app.iduanju.ui.LocalNavigate
import app.iduanju.ui.components.DS
import app.iduanju.ui.components.GlassConfirm
import app.iduanju.ui.components.GlassSlider
import app.iduanju.ui.components.GlassTextField
import app.iduanju.ui.components.Motion
import app.iduanju.ui.theme.IdjThemeColors
import kotlin.math.roundToInt

/**
 * iOS SourcesView (我的 → 片源管理): 内置 sources, adult gate (with 18+ confirmation), 插件与采集站 link,
 * playback options. The 红果 device override is an advanced option, folded at the bottom.
 */
@Composable
fun SourcesScreen(onBack: () -> Unit) {
    val c = IdjThemeColors
    val ia = IdjApp.instance
    val go = LocalNavigate.current
    var confirmAdult by remember { mutableStateOf(false) }
    var adStrip by remember { mutableStateOf(app.iduanju.core.hls.HLSAdFilter.enabled) }
    var prefetch by remember { mutableStateOf(ia.settings.prefetchEnabled) }
    var cacheMB by remember { mutableIntStateOf(ia.settings.playbackCacheMB) }
    var message by remember { mutableStateOf<String?>(null) }
    val adult = ia.cms.adultUnlocked

    SettingsPage("片源管理", onBack) {
        Group("内置") {
            BuiltinRow("红果短剧", ProfileIcons.play)
        }
        Group("首页功能频道", footer = "可按个人使用习惯开启或隐藏首页顶部的频道、漫画、小说、音乐标签；关闭后仍可在「我的」或设置中随时重新开启。") {
            SwitchRow("频道", ProfileIcons.telegram, "显示首页「频道」标签", ia.settings.showChannelsTab) {
                ia.settings.updateShowChannelsTab(it)
            }
            RowDivider()
            SwitchRow("漫画", ProfileIcons.manga, "显示首页「漫画」标签", ia.settings.showMangaTab) {
                ia.settings.updateShowMangaTab(it)
            }
            RowDivider()
            SwitchRow("小说", Icons.Filled.MenuBook, "显示首页「小说」标签", ia.settings.showNovelTab) {
                ia.settings.updateShowNovelTab(it)
            }
            RowDivider()
            SwitchRow("底部音乐入口", Icons.Filled.MusicNote, "在底部导航栏（平板侧栏）显示独立「音乐」标签", ia.settings.showMusicTab) {
                ia.settings.updateShowMusicTab(it)
            }
        }
        Group("成人内容（默认隐藏）", footer = "开启后黄果、标记为成人的采集站 / 插件 / Telegram 频道 / 漫画插件才会出现在首页、搜索、频道与漫画；所有结果一律过滤未成年人与非自愿题材。") {
            SwitchRow("显示成人片源", ProfileIcons.film, "黄果短剧、成人采集站 / 插件 / 频道 / 漫画 · 仅限年满 18 周岁", adult) {
                if (it) confirmAdult = true else { ia.cms.adultUnlocked = false; message = "已隐藏成人片源" }
            }
            if (adult) {
                RowDivider()
                val installedAdult = ia.plugins.plugins.count { it.adult }
                LinkRow("内置成人视频与漫画插件", ProfileIcons.plugin, trailing = if (installedAdult > 0) "已装 $installedAdult 个" else "去安装",
                    sub = "MissAV、Jable、91Porn、Hanime1 等内置推荐插件在「插件管理」一键安装") { go("plugins") }
            }
        }
        Group("插件与采集站") {
            LinkRow("插件管理", ProfileIcons.plugin, sub = "JS 插件、订阅、苹果 CMS 采集站；成人插件在这里解锁后才显示") { go("plugins") }
        }
        Group("播放设置") {
            SwitchRow("去广告", ProfileIcons.adblock, "去掉 HLS 片源里插播的广告片段（adjump、异域名短片段、重复片段）", adStrip) {
                adStrip = it; app.iduanju.core.hls.HLSAdFilter.enabled = it
                ia.settings.adStripEnabled = it
            }
            RowDivider()
            SwitchRow("预加载下一集", ProfileIcons.download, "播放时提前缓存下一集，可减少切集等待", prefetch) {
                prefetch = it; ia.settings.prefetchEnabled = it
            }
            RowDivider()
            Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("播放缓存上限", color = c.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Text("${cacheMB} MB", color = c.secondary, fontSize = 14.sp)
                }
                GlassSlider(cacheMB.toFloat(), { cacheMB = (it / 100f).roundToInt() * 100 }, valueRange = 100f..2000f, steps = 18,
                    onValueChangeFinished = { ia.settings.playbackCacheMB = cacheMB }, label = "播放缓存上限", valueText = { "${it.roundToInt()} MB" })
            }
        }
        DeviceOverrideGroup()
        Note(message)
    }
    if (confirmAdult) GlassConfirm(
        onDismissRequest = { confirmAdult = false },
        title = "显示成人片源？",
        message = "仅限年满 18 周岁的用户。开启后黄果与标记为成人的片源会出现在首页、搜索、频道与漫画；可随时在这里关闭。",
        confirmText = "我已年满 18 周岁",
        onConfirm = { ia.cms.adultUnlocked = true; confirmAdult = false; message = "已显示成人片源" },
    )
}

@Composable
private fun BuiltinRow(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, sub: String? = null) {
    val c = IdjThemeColors
    Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Icon(icon, null, tint = c.text, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = c.text, fontSize = 16.sp)
            sub?.let { Text(it, color = c.secondary, fontSize = 12.sp) }
        }
        Tag("内置")
    }
}

/** 高级：红果播放设备覆盖 (iOS `stv.hg.purePlayerDevice`). Collapsed by default; empty = register automatically. */
@Composable
private fun DeviceOverrideGroup() {
    val c = IdjThemeColors
    val settings = IdjApp.instance.settings
    var open by remember { mutableStateOf(settings.playerDeviceOverride() != null) }
    var did by remember { mutableStateOf(settings.playerDeviceOverride()?.first ?: "") }
    var iid by remember { mutableStateOf(settings.playerDeviceOverride()?.second ?: "") }
    var saved by remember { mutableStateOf<String?>(null) }
    var custom by remember { mutableStateOf(settings.playerDeviceOverride() != null) }
    Group("高级", footer = if (open) "留空时自动注册一台播放设备（只保存在内存里）；填写后所有取流都使用这台设备，不会写进源码。" else null) {
        LinkRow("红果播放设备", ProfileIcons.device, trailing = if (custom) "自定义" else "自动") { open = !open }
        AnimatedVisibility(open, enter = expandVertically(DS.panel()) + fadeIn(Motion.fade()), exit = shrinkVertically(DS.panel()) + fadeOut(Motion.fade(Motion.QUICK))) {
            Column(Modifier.padding(horizontal = 14.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassTextField(did, { did = it }, label = "device_id")
                GlassTextField(iid, { iid = it }, label = "iid")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextAction("保存", enabled = did.isNotBlank() && iid.isNotBlank()) { settings.setPlayerDeviceOverride(did.trim(), iid.trim()); custom = true; saved = "已保存，之后取流使用这台设备" }
                    TextAction("恢复自动") { did = ""; iid = ""; settings.setPlayerDeviceOverride(null, null); custom = false; saved = "已恢复自动注册" }
                }
                saved?.let { Text(it, color = c.accent, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp)) }
            }
        }
    }
}
