package app.iduanju

import android.app.Application
import app.iduanju.core.hongguo.HGPlayerDevice
import app.iduanju.core.hongguo.HongguoClient
import app.iduanju.data.LibraryStore
import app.iduanju.data.SettingsStore

class IdjApp : Application(), coil.ImageLoaderFactory {
    lateinit var settings: SettingsStore
        private set
    lateinit var hongguo: HongguoClient
        private set
    lateinit var hgWebAccount: app.iduanju.data.HongguoWebAccount
        private set
    lateinit var library: LibraryStore
        private set
    lateinit var cms: app.iduanju.data.CMSStore
    lateinit var tvbox: app.iduanju.tvbox.TVBoxStore
        private set
    lateinit var plugins: app.iduanju.plugin.PluginStore
        private set
    lateinit var tg: app.iduanju.tg.TGStore
        private set
    lateinit var manga: app.iduanju.manga.MangaStore
        private set
    lateinit var huangguo: app.iduanju.core.huangguo.HuangguoClient
        private set
    lateinit var updater: app.iduanju.update.AppUpdater
        private set
    lateinit var webdav: app.iduanju.data.WebDAVSync
        private set
    lateinit var musicStore: app.iduanju.music.MusicStore
        private set
    lateinit var musicPlayer: app.iduanju.music.MusicPlayerController
        private set

    /** Covers: 黄果 CDNs obfuscate images (AES / XOR) — decoded in an OkHttp interceptor; TVBox covers carry request headers. */
    override fun newImageLoader(): coil.ImageLoader = coil.ImageLoader.Builder(this)
        .okHttpClient {
            okhttp3.OkHttpClient.Builder().addInterceptor(app.iduanju.huangguo.CoverDecryptInterceptor)
                .dns(app.iduanju.tvbox.TVBoxPictureGuard).addNetworkInterceptor(app.iduanju.tvbox.TVBoxPictureGuard).build()
        }
        .components { add(app.iduanju.tvbox.TVBoxPictureInterceptor); add(app.iduanju.plugin.PluginImageFetcher.Factory()) }
        .crossfade(true).build()

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsStore(this)
        app.iduanju.core.novel.NovelSources.configureStorage(filesDir)
        HGPlayerDevice.storageDir = filesDir
        app.iduanju.core.hongguo.HGNet.playerUsesSystemProxy = settings.playerUsesSystemProxy
        HGPlayerDevice.overrideProvider = {
            settings.playerDeviceOverride()?.let { it }
        }
        hongguo = HongguoClient(filesDir)
        hgWebAccount = app.iduanju.data.HongguoWebAccount(this)
        app.iduanju.core.hls.HLSAdFilter.enabled = getSharedPreferences("idj", MODE_PRIVATE).getBoolean("stv.hls.adstrip", true)
        library = LibraryStore(this)
        cms = app.iduanju.data.CMSStore(this)
        tvbox = app.iduanju.tvbox.TVBoxStore(this)
        plugins = app.iduanju.plugin.PluginStore(this)
        tg = app.iduanju.tg.TGStore(this)
        manga = app.iduanju.manga.MangaStore(this)
        huangguo = app.iduanju.core.huangguo.HuangguoClient(filesDir, app.iduanju.huangguo.PrefsMap(this), app.iduanju.huangguo.WebViewCookieJar)
        app.iduanju.core.hls.HLSAdFilter.enabled = settings.adStripEnabled
        updater = app.iduanju.update.AppUpdater(this)
        webdav = app.iduanju.data.WebDAVSync(this)
        app.iduanju.music.AppAudioArbiter.init(this)
        musicStore = app.iduanju.music.MusicStore(this)
        musicPlayer = app.iduanju.music.MusicPlayerController(this, musicStore)
        app.iduanju.core.novel.LegadoWebView.backstage = app.iduanju.novel.AndroidBackstageWebView(this)
        app.iduanju.core.novel.LegadoCookies.bridge = object : app.iduanju.core.novel.LegadoCookies.PlatformBridge {
            override fun getCookies(url: String): String? = runCatching { android.webkit.CookieManager.getInstance().getCookie(url) }.getOrNull()
            override fun setCookie(url: String, cookieHeader: String) {
                runCatching {
                    val cm = android.webkit.CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    cm.setCookie(url, cookieHeader)
                    cm.flush()
                }
            }
            override fun removeCookiesForDomain(url: String) {
                runCatching {
                    val cm = android.webkit.CookieManager.getInstance()
                    val existing = cm.getCookie(url) ?: return
                    for (part in existing.split(';')) {
                        val name = part.substringBefore('=').trim()
                        if (name.isNotEmpty()) cm.setCookie(url, "$name=; Max-Age=0; Path=/")
                    }
                    cm.flush()
                }
            }
        }
    }

    companion object {
        lateinit var instance: IdjApp
            private set
    }
}
