package app.iduanju.ui.player

import app.iduanju.IdjApp
import app.iduanju.core.hongguo.HGDanmakuResult
import app.iduanju.core.plugin.PluginVideo

/** Comments fetched through the owning idj1 plugin's `danmaku` export (红果 official comments live behind it). */
internal object PluginDanmaku {
    fun fetch(seriesId: String, episodeVid: String): HGDanmakuResult {
        val pid = PluginVideo.pluginId(seriesId) ?: return HGDanmakuResult(emptyList(), error = "插件剧集 id 无效")
        val video = runCatching { IdjApp.instance.plugins.video(pid) }.getOrNull()
            ?: return HGDanmakuResult(emptyList(), error = "插件已删除")
        return video.danmaku(episodeVid)
    }

    fun capable(seriesId: String): Boolean {
        val pid = PluginVideo.pluginId(seriesId) ?: return false
        return runCatching { "danmaku" in IdjApp.instance.plugins.video(pid).m.capabilities }.getOrDefault(false)
    }
}