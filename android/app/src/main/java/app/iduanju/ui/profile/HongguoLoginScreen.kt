package app.iduanju.ui.profile

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.iduanju.IdjApp
import app.iduanju.data.HongguoWebAccount
import app.iduanju.ui.components.BackButton
import app.iduanju.ui.components.ButtonSize
import app.iduanju.ui.components.ButtonTone
import app.iduanju.ui.components.DSNavBar
import app.iduanju.ui.components.GlassButton
import app.iduanju.ui.components.GlassSpinner
import app.iduanju.ui.components.GlassToast
import app.iduanju.ui.components.Hairline
import app.iduanju.ui.components.dsCard
import app.iduanju.ui.theme.IdjThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private const val BASE_LOGIN_STYLE_CSS =
    "*, *::before, *::after { animation: none !important; transition: none !important; " +
        "-webkit-backdrop-filter: none !important; backdrop-filter: none !important; } " +
        "video { display: none !important; } " +
        "[role=\"dialog\"], [class*=\"pc-login-dialog\"] { " +
        "-webkit-backdrop-filter: none !important; backdrop-filter: none !important; " +
        "max-height: calc(100% - 32px) !important; " +
        "opacity: 1 !important; visibility: visible !important; }"

private val VIEWPORT_META_RE = Regex("<meta\\b[^>]*\\bname\\s*=\\s*[\"']?viewport[\"']?[^>]*>", RegexOption.IGNORE_CASE)
private val HEAD_OPEN_RE = Regex("<head\\b[^>]*>", RegexOption.IGNORE_CASE)

private val desktopHtmlHttp = OkHttpClient.Builder()
    .callTimeout(15, TimeUnit.SECONDS)
    .build()

private fun isMainEntryUrl(url: Uri?): Boolean {
    if (url == null) return false
    if (!url.scheme.equals("https", ignoreCase = true)) return false
    if (!url.host.equals("hongguoduanju.com", ignoreCase = true)) return false
    val path = url.path
    return path.isNullOrEmpty() || path == "/"
}

private const val QR_HOOK_INLINE_JS =
    "(function(){if(window.__idjQrHookInstalled)return;window.__idjQrHookInstalled=true;" +
        "window.__idjQrMeta={token:'',checkUrl:'',csrfToken:'',traceId:''};" +
        "function absUrl(u){try{return new URL(String(u),location.href).href;}catch(e){return String(u||'');}}" +
        "function tokFrom(u){try{return new URL(u,location.href).searchParams.get('token')||'';}catch(e){return '';}}" +
        "function emit(){var m=window.__idjQrMeta;if((m.token||m.checkUrl)&&window.IdjNativeLogin&&window.IdjNativeLogin.onQrMeta){try{window.IdjNativeLogin.onQrMeta(m.token||'',m.checkUrl||'',m.csrfToken||'',m.traceId||'');}catch(e){}}}" +
        "function emitStatus(st){if(st&&window.IdjNativeLogin&&window.IdjNativeLogin.onQrPollStatus){try{window.IdjNativeLogin.onQrPollStatus(st);}catch(e){}}}" +
        "function inspect(u,t){try{var j=JSON.parse(t);var d=j&&j.data;if(!d||typeof d!=='object')return;" +
        "if(u.indexOf('/passport/web/get_qrcode')>=0){if(d.token){window.__idjQrMeta.token=String(d.token);window.__idjQrMeta.checkUrl='';emit();}}" +
        "else if(u.indexOf('/passport/web/check_qrconnect')>=0){var s=String(d.status||'').toLowerCase();" +
        "if(s==='2'||s==='scanned')emitStatus('scanned');" +
        "else if(s==='3'||s==='confirmed'){emitStatus('confirmed');if(d.redirect_url&&!window.__idjRedirectHandled){window.__idjRedirectHandled=true;fetch(d.redirect_url,{credentials:'include',redirect:'follow'}).catch(function(){});}}" +
        "else if(s==='5'||s==='expired'||s==='4'||s==='refuse'||s==='refused')emitStatus('expired');}}catch(e){}}" +
        "var oo=XMLHttpRequest.prototype.open,oh=XMLHttpRequest.prototype.setRequestHeader,os=XMLHttpRequest.prototype.send;" +
        "XMLHttpRequest.prototype.open=function(m,u){this.__idjUrl=absUrl(u);if(this.__idjUrl.indexOf('/passport/web/check_qrconnect')>=0){window.__idjQrMeta.checkUrl=this.__idjUrl;var tk=tokFrom(this.__idjUrl);if(tk)window.__idjQrMeta.token=tk;emit();}return oo.apply(this,arguments);};" +
        "XMLHttpRequest.prototype.setRequestHeader=function(k,v){try{var lk=String(k||'').toLowerCase();if(this.__idjUrl&&this.__idjUrl.indexOf('/passport/web/check_qrconnect')>=0){if(lk==='x-tt-passport-csrf-token'&&v)window.__idjQrMeta.csrfToken=String(v);if(lk==='x-tt-passport-trace-id'&&v)window.__idjQrMeta.traceId=String(v);}}catch(e){}return oh.apply(this,arguments);};" +
        "XMLHttpRequest.prototype.send=function(){var self=this,u=self.__idjUrl||'';if(u.indexOf('/passport/web/check_qrconnect')>=0)emit();if(u.indexOf('/passport/web/get_qrcode')>=0||u.indexOf('/passport/web/check_qrconnect')>=0){self.addEventListener('load',function(){if(self.status===200&&typeof self.responseText==='string')inspect(u,self.responseText);});}return os.apply(this,arguments);};" +
        "if(window.fetch){var of=window.fetch;window.fetch=function(inp,init){var u=absUrl(typeof inp==='string'?inp:(inp&&inp.url)||'');if(u.indexOf('/passport/web/check_qrconnect')>=0){window.__idjQrMeta.checkUrl=u;var tk=tokFrom(u);if(tk)window.__idjQrMeta.token=tk;emit();}var p=of.apply(this,arguments);if(u.indexOf('/passport/web/get_qrcode')>=0||u.indexOf('/passport/web/check_qrconnect')>=0){p.then(function(r){if(r&&r.ok)r.clone().text().then(function(t){inspect(u,t);}).catch(function(){});}).catch(function(){});}return p;};}" +
        "})();"

private fun forceDesktopViewport(html: String): String {
    val desktopMeta = "<meta name=\"viewport\" content=\"width=1280\"><script>$QR_HOOK_INLINE_JS</script>"
    if (VIEWPORT_META_RE.containsMatchIn(html)) {
        return VIEWPORT_META_RE.replace(html, desktopMeta)
    }
    val headMatch = HEAD_OPEN_RE.find(html)
    return if (headMatch != null) {
        html.replaceRange(
            headMatch.range.last + 1,
            headMatch.range.last + 1,
            desktopMeta,
        )
    } else {
        "$desktopMeta$html"
    }
}

private fun fetchDesktopHtmlResponse(url: String): WebResourceResponse? {
    return try {
        val cm = CookieManager.getInstance()
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", HongguoWebAccount.PC_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
        val cookie = cm.getCookie(url)
        if (!cookie.isNullOrBlank()) {
            reqBuilder.header("Cookie", cookie)
        }
        desktopHtmlHttp.newCall(reqBuilder.build()).execute().use { resp ->
            Log.i("IDJ-HGLogin", "fetchDesktopHtmlResponse code=${resp.code} url=$url")
            if (!resp.isSuccessful) return null
            resp.headers("Set-Cookie").forEach { sc ->
                cm.setCookie(url, sc)
            }
            val html = resp.body?.string()
            if (html.isNullOrBlank()) return null
            val patched = forceDesktopViewport(html)
            val vpMatch = VIEWPORT_META_RE.find(patched)?.value
            Log.i("IDJ-HGLogin", "fetchDesktopHtmlResponse len=${html.length} vp=$vpMatch")
            WebResourceResponse("text/html", "UTF-8", patched.byteInputStream())
        }
    } catch (t: Throwable) {
        Log.w("IDJ-HGLogin", "fetchDesktopHtmlResponse failed for $url", t)
        null
    }
}

private val BRIDGE_JS: String by lazy {
    val cssLiteral = JSONObject.quote(BASE_LOGIN_STYLE_CSS)
    """
(function() {
  try {
    function ensureFixStyle() {
      var st = document.getElementById('idj-login-fix');
      if (!st) {
        st = document.createElement('style');
        st.id = 'idj-login-fix';
        (document.head || document.documentElement).appendChild(st);
      }
      var css = $cssLiteral;
      if (st.textContent !== css) st.textContent = css;
    }
    ensureFixStyle();

    function isElVisible(el) {
      if (!el) return false;
      if (el.offsetWidth <= 0 || el.offsetHeight <= 0) return false;
      var cs = getComputedStyle(el);
      return cs.display !== 'none' && cs.visibility !== 'hidden' && cs.opacity !== '0';
    }

    function findDialog() {
      return document.querySelector('[role="dialog"], [class*="pc-login-dialog"]');
    }

    function diag(msg) {
      try {
        if (window.IdjNativeLogin && window.IdjNativeLogin.logDiag) {
          window.IdjNativeLogin.logDiag(String(msg));
        }
      } catch (e) {}
    }

    function buildPollUrl() {
      var m = window.__idjQrMeta || {};
      if (m.checkUrl) return m.checkUrl;
      if (m.token) {
        return '/passport/web/check_qrconnect/?next=' + encodeURIComponent(location.origin + '/') +
          '&token=' + encodeURIComponent(m.token) +
          '&passport_jssdk_version=2.4.13&passport_jssdk_type=normal&is_from_ttaccountsdk=1&aid=513755&language=zh&account_sdk_source=web';
      }
      return '';
    }

    function pollQrInWebView() {
      var url = buildPollUrl();
      if (!url || window.__idjPollBusy) return;
      window.__idjPollBusy = true;
      var m = window.__idjQrMeta || {};
      var hdrs = { 'Accept': 'application/json, text/javascript' };
      if (m.csrfToken) hdrs['x-tt-passport-csrf-token'] = m.csrfToken;
      if (m.traceId) hdrs['x-tt-passport-trace-id'] = m.traceId;
      fetch(url, { credentials: 'include', cache: 'no-store', headers: hdrs })
        .then(function(r) { return r.ok ? r.json() : null; })
        .then(function(j) {
          var d = j && j.data;
          if (!d) return;
          var s = String(d.status || '').toLowerCase();
          if (s === '3' || s === 'confirmed') {
            if (d.redirect_url) {
              fetch(d.redirect_url, { credentials: 'include', redirect: 'follow' }).catch(function(){});
            }
          }
        })
        .catch(function() {})
        .then(function() { window.__idjPollBusy = false; });
    }

    window.__idjActions = {
      refreshQr: function() {
        window.__idjRedirectHandled = false;
        if (window.__idjQrMeta) {
          window.__idjQrMeta.token = '';
          window.__idjQrMeta.checkUrl = '';
        }
        var dlg = findDialog();
        if (dlg) {
          var mask = dlg.querySelector('.web-login-scan-code__content__qrcode-wrapper__mask, [class*="qrcode-wrapper__mask"]');
          if (mask && isElVisible(mask)) {
            mask.click();
            var innerBtn = mask.querySelector('button, [role="button"], svg, span');
            if (innerBtn) innerBtn.click();
            return 'clicked';
          }
        }
        location.reload();
        return 'reloaded';
      },
      pollQrNow: function() {
        pollQrInWebView();
      }
    };

    var vpEl = document.querySelector('meta[name="viewport"]');
    diag('init iw=' + window.innerWidth + ' ih=' + window.innerHeight + ' vp=' + (vpEl ? vpEl.getAttribute('content') : 'none'));

    if (window.__idjLoginOpenTimer) clearInterval(window.__idjLoginOpenTimer);
    var openTries = 0;
    window.__idjLoginOpenTimer = setInterval(function() {
      openTries++;
      try {
        ensureFixStyle();
        var dlg = findDialog();
        var rect = dlg ? dlg.getBoundingClientRect() : null;
        if (dlg && rect.width > 0 && rect.height > 0) {
          diag('dlgOpen iw=' + window.innerWidth + ' rect=' + Math.round(rect.width) + 'x' + Math.round(rect.height));
          clearInterval(window.__idjLoginOpenTimer);
          window.__idjLoginOpenTimer = null;
          return;
        }
        if (openTries % 2 === 1) {
          var clicked = false;
          var selectors = ['button', 'a, [role="button"]', 'span, div'];
          for (var s = 0; s < selectors.length && !clicked; s++) {
            var btns = document.querySelectorAll(selectors[s]);
            for (var i = 0; i < btns.length; i++) {
              var el = btns[i];
              if ((el.textContent || '').trim() === '登录') {
                el.click();
                clicked = true;
                break;
              }
            }
          }
        }
      } catch (e) { diag('openErr=' + e); }
      if (openTries >= 40) {
        diag('openTimeout iw=' + window.innerWidth);
        clearInterval(window.__idjLoginOpenTimer);
        window.__idjLoginOpenTimer = null;
      }
    }, 500);

    window.__idjLastQrKey = null;
    if (window.__idjStateObserver) clearInterval(window.__idjStateObserver);
    window.__idjStateObserver = setInterval(function() {
      try {
        ensureFixStyle();
        var dlg = findDialog();

        // QR image + status
        var qrSrc = '';
        var qrStatus = 'loading';
        if (dlg) {
          var imgs = dlg.querySelectorAll('img');
          for (var i = 0; i < imgs.length; i++) {
            var im = imgs[i];
            var src = im.getAttribute('src') || im.src || '';
            if (src.indexOf('data:image/') === 0 && (im.naturalWidth >= 100 || !!im.closest('.web-login-scan-code') || (im.alt || '').indexOf('二维码') >= 0)) {
              qrSrc = src;
              break;
            }
          }
          if (!qrSrc) {
            var scanImg = dlg.querySelector('.web-login-scan-code img, [alt*="二维码"]');
            if (scanImg) {
              var ss = scanImg.getAttribute('src') || scanImg.src || '';
              if (ss.indexOf('data:image/') === 0) qrSrc = ss;
            }
          }
          if (!qrSrc) {
            var canvas = dlg.querySelector('.web-login-scan-code canvas');
            if (canvas && canvas.width >= 100) {
              try { qrSrc = canvas.toDataURL('image/png'); } catch (ce) {}
            }
          }

          var dlgText = dlg.textContent || '';
          var mask = dlg.querySelector('.web-login-scan-code__content__qrcode-wrapper__mask, [class*="qrcode-wrapper__mask"]');
          if (dlgText.indexOf('已扫描') >= 0 || dlgText.indexOf('请在手机') >= 0) {
            qrStatus = 'scanned';
          } else if (isElVisible(mask) || dlgText.indexOf('点击刷新') >= 0 || dlgText.indexOf('已过期') >= 0 || dlgText.indexOf('二维码失效') >= 0) {
            qrStatus = 'expired';
          } else if (qrSrc) {
            qrStatus = 'ready';
          }
        }

        // Push QR updates on change
        var qrKey = qrStatus + '|' + qrSrc;
        if (qrKey !== window.__idjLastQrKey) {
          window.__idjLastQrKey = qrKey;
          if (window.IdjNativeLogin && window.IdjNativeLogin.onQrUpdate) {
            window.IdjNativeLogin.onQrUpdate(qrSrc, qrStatus);
          }
        }
      } catch (e) {}
    }, 1200);
  } catch (e) {}
})();
""".trimIndent()
}

private class IdjNativeLoginBridge(
    private val onQr: (base64DataUrl: String, status: String) -> Unit,
    private val onMeta: (token: String, checkUrl: String, csrfToken: String, traceId: String) -> Unit,
    private val onPollStatus: (status: String) -> Unit,
) {
    @JavascriptInterface
    fun onQrUpdate(base64DataUrl: String, status: String) {
        Log.i("IDJ-HGLogin", "onQrUpdate status=$status len=${base64DataUrl.length}")
        onQr(base64DataUrl, status)
    }

    @JavascriptInterface
    fun onQrMeta(token: String, checkUrl: String, csrfToken: String, traceId: String) {
        onMeta(token, checkUrl, csrfToken, traceId)
    }

    @JavascriptInterface
    fun onQrPollStatus(status: String) {
        onPollStatus(status)
    }

    @JavascriptInterface
    fun logDiag(msg: String) {
        Log.i("IDJ-HGLogin", "JS: $msg")
    }
}

private fun decodeDataUrlBitmap(dataUrl: String): Bitmap? {
    if (dataUrl.isBlank()) return null
    return runCatching {
        val b64 = dataUrl.substringAfter("base64,", "").trim()
        if (b64.isEmpty()) return null
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}

private fun saveQrBitmapToGallery(context: Context, bitmap: Bitmap): Boolean {
    return runCatching {
        val resolver = context.contentResolver
        val fileName = "hongguo_qr_${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/iDuanJu")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        try {
            resolver.openOutputStream(uri)?.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw IllegalStateException("Bitmap compress failed")
                }
            } ?: throw IllegalStateException("Unable to open output stream")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            }
            true
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }.getOrDefault(false)
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun HongguoLoginScreen(onBack: () -> Unit) {
    val c = IdjThemeColors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val account = IdjApp.instance.hgWebAccount
    val currentOnBack by rememberUpdatedState(onBack)

    var webView by remember { mutableStateOf<WebView?>(null) }
    var loggedInName by remember { mutableStateOf<String?>(null) }

    // QR state
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var qrStatus by remember { mutableStateOf("loading") }
    var qrToken by remember { mutableStateOf<String?>(null) }
    var qrCheckUrl by remember { mutableStateOf<String?>(null) }
    var qrCsrfToken by remember { mutableStateOf<String?>(null) }
    var qrTraceId by remember { mutableStateOf<String?>(null) }
    var verifyingNow by remember { mutableStateOf(false) }
    val qrSeq = remember { AtomicLong(0L) }
    var savingQr by remember { mutableStateOf(false) }
    var saveNotice by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf(false) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    suspend fun verifyQrAndLogin(userInitiated: Boolean) {
        if (loggedInName != null) return
        if (userInitiated) verifyingNow = true
        try {
            webView?.evaluateJavascript("window.__idjActions && window.__idjActions.pollQrNow();", null)
            var pollRes: HongguoWebAccount.QRPollResult = HongguoWebAccount.QRPollResult.Failed
            if (!qrToken.isNullOrBlank() || !qrCheckUrl.isNullOrBlank()) {
                pollRes = account.pollQRConnectNative(qrCheckUrl, qrToken, qrCsrfToken, qrTraceId)
                when (pollRes) {
                    is HongguoWebAccount.QRPollResult.Scanned -> qrStatus = "scanned"
                    is HongguoWebAccount.QRPollResult.Expired -> qrStatus = "expired"
                    is HongguoWebAccount.QRPollResult.Confirmed -> {
                        if (userInitiated) {
                            saveNotice = "已确认授权，正在同步账号…"
                            saveError = false
                        }
                    }
                    else -> Unit
                }
            }
            val p = account.refreshProfile()
            if (p != null) {
                loggedInName = p.nickname
                delay(600)
                currentOnBack()
                return
            }
            if (pollRes is HongguoWebAccount.QRPollResult.Confirmed) {
                delay(500)
                val p2 = account.refreshProfile()
                if (p2 != null) {
                    loggedInName = p2.nickname
                    delay(600)
                    currentOnBack()
                    return
                }
            }
            if (userInitiated && loggedInName == null) {
                saveError = pollRes is HongguoWebAccount.QRPollResult.Expired
                saveNotice = when (pollRes) {
                    is HongguoWebAccount.QRPollResult.Scanned -> "已检测到扫码，请在红果短剧 App 中点击「确认登录」"
                    is HongguoWebAccount.QRPollResult.Expired -> "二维码已过期，请点击「刷新二维码」重新获取"
                    is HongguoWebAccount.QRPollResult.Confirmed -> "正在同步红果会话，请稍等 1–2 秒后再点一次"
                    else -> "尚未检测到确认状态，请确认已在红果短剧 App 中点击「确认登录」"
                }
            }
        } finally {
            if (userInitiated) verifyingNow = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.removeJavascriptInterface("IdjNativeLogin")
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && loggedInName == null) {
                scope.launch {
                    for (ms in longArrayOf(0L, 650L, 1800L)) {
                        if (ms > 0L) delay(ms)
                        if (loggedInName != null) break
                        verifyQrAndLogin(userInitiated = false)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        while (isActive && loggedInName == null) {
            delay(2200)
            verifyQrAndLogin(userInitiated = false)
        }
    }

    fun refreshQr() {
        saveNotice = null
        qrStatus = "loading"
        qrToken = null
        qrCheckUrl = null
        qrCsrfToken = null
        qrTraceId = null
        val wv = webView ?: return
        wv.evaluateJavascript(
            "(function(){ if (window.__idjActions) { return window.__idjActions.refreshQr(); } location.reload(); return 'reloaded'; })();",
        ) { result ->
            if (result?.contains("reloaded") == true) {
                qrBitmap = null
            }
        }
    }

    fun saveQrToGallery() {
        val bmp = qrBitmap ?: return
        if (savingQr) return
        savingQr = true
        saveNotice = null
        scope.launch {
            val ok = withContext(Dispatchers.IO) { saveQrBitmapToGallery(context, bmp) }
            savingQr = false
            if (ok) {
                val msg = "已保存到相册，可在红果 App 扫一扫中选择相册图片"
                saveNotice = msg
                saveError = false
                GlassToast.show(msg, long = true)
            } else {
                val msg = "保存失败，请检查相册权限后重试"
                saveNotice = msg
                saveError = true
                GlassToast.show(msg)
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // Native Top Bar
        DSNavBar("登录红果账号", leading = { BackButton(onClick = onBack) })
        Hairline()

        if (loggedInName != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(c.okSoft)
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    "已登录红果账号「$loggedInName」，正在返回…",
                    color = c.ok,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Hairline()
        }

        Box(Modifier.fillMaxWidth().weight(1f)) {
            // Hidden WebView engine mounted continuously at full layout size behind the native UI.
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        visibility = View.INVISIBLE
                        isFocusable = false
                        isFocusableInTouchMode = false
                        setOnTouchListener { _, _ -> true }
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            userAgentString = HongguoWebAccount.PC_USER_AGENT
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        }
                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        cm.setAcceptThirdPartyCookies(this, true)
                        addJavascriptInterface(
                            IdjNativeLoginBridge(
                                onQr = { dataUrl, status ->
                                    val seq = qrSeq.incrementAndGet()
                                    scope.launch {
                                        if (status == "expired" && !qrToken.isNullOrBlank() && qrStatus != "expired") {
                                            verifyQrAndLogin(userInitiated = false)
                                            return@launch
                                        }
                                        val decoded = if (dataUrl.isNotBlank()) {
                                            withContext(Dispatchers.Default) {
                                                decodeDataUrlBitmap(dataUrl)
                                            }
                                        } else {
                                            null
                                        }
                                        if (seq != qrSeq.get()) return@launch
                                        qrStatus = status
                                        if (decoded != null) {
                                            qrBitmap = decoded
                                        } else if (status == "loading" && dataUrl.isBlank()) {
                                            qrBitmap = null
                                        }
                                    }
                                },
                                onMeta = { token, checkUrl, csrfToken, traceId ->
                                    scope.launch {
                                        if (token.isNotBlank()) qrToken = token
                                        if (checkUrl.isNotBlank()) qrCheckUrl = checkUrl
                                        if (csrfToken.isNotBlank()) qrCsrfToken = csrfToken
                                        if (traceId.isNotBlank()) qrTraceId = traceId
                                    }
                                },
                                onPollStatus = { st ->
                                    scope.launch {
                                        when (st) {
                                            "scanned" -> qrStatus = "scanned"
                                            "expired" -> qrStatus = "expired"
                                            "confirmed" -> verifyQrAndLogin(userInitiated = false)
                                        }
                                    }
                                },
                            ),
                            "IdjNativeLogin",
                        )
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(cm: ConsoleMessage?): Boolean {
                                if (cm != null && cm.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                                    Log.w("IDJ-HGLogin", "console[ERROR]: ${cm.message()} (${cm.sourceId()}:${cm.lineNumber()})")
                                }
                                return super.onConsoleMessage(cm)
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): WebResourceResponse? {
                                if (request == null || !request.isForMainFrame) return null
                                if (!request.method.equals("GET", ignoreCase = true)) return null
                                val url = request.url ?: return null
                                val isEntry = isMainEntryUrl(url)
                                Log.i("IDJ-HGLogin", "shouldInterceptRequest mainFrame=$url isEntry=$isEntry")
                                if (!isEntry) return null
                                return fetchDesktopHtmlResponse(url.toString())
                            }

                            override fun onPageCommitVisible(view: WebView?, url: String?) {
                                super.onPageCommitVisible(view, url)
                                Log.i("IDJ-HGLogin", "onPageCommitVisible url=$url")
                                view?.evaluateJavascript(BRIDGE_JS, null)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                Log.i("IDJ-HGLogin", "onPageFinished url=$url")
                                view?.evaluateJavascript(BRIDGE_JS, null)
                            }
                        }
                        loadUrl(HongguoWebAccount.WEB_ORIGIN)
                        webView = this
                    }
                },
            )

            // Opaque 100% native Compose UI layer covering the hidden WebView
            Box(
                Modifier
                    .fillMaxSize()
                    .background(c.bg)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {},
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Column(
                        Modifier
                            .widthIn(max = 420.dp)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        QrLoginCard(
                            qrBitmap = qrBitmap,
                            qrStatus = qrStatus,
                            savingQr = savingQr,
                            verifyingNow = verifyingNow,
                            saveNotice = saveNotice,
                            saveError = saveError,
                            onRefresh = { refreshQr() },
                            onSave = { saveQrToGallery() },
                            onCheckNow = { scope.launch { verifyQrAndLogin(userInitiated = true) } },
                        )

                        Text(
                            "登录后看剧记录与进度将自动同步到你的红果账号",
                            color = c.secondary,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QrLoginCard(
    qrBitmap: Bitmap?,
    qrStatus: String,
    savingQr: Boolean,
    verifyingNow: Boolean,
    saveNotice: String?,
    saveError: Boolean,
    onRefresh: () -> Unit,
    onSave: () -> Unit,
    onCheckNow: () -> Unit,
) {
    val c = IdjThemeColors
    Column(
        Modifier
            .fillMaxWidth()
            .dsCard(24.dp)
            .padding(horizontal = 22.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "使用红果短剧 App 扫码登录",
                color = c.text,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "打开红果短剧 App →「我的」右上角 ≡ → 扫一扫",
                color = c.secondary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }

        // White QR Code Container
        Box(
            Modifier
                .padding(vertical = 4.dp)
                .size(224.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White)
                .border(1.dp, c.hairline, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (qrBitmap != null) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "红果登录二维码",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    contentScale = ContentScale.Fit,
                )

                if (qrStatus == "expired") {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.72f))
                            .clickable(remember { MutableInteractionSource() }, null, role = Role.Button, onClick = onRefresh),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                "二维码已过期",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            GlassButton("点击刷新", size = ButtonSize.Small, onClick = onRefresh)
                        }
                    }
                } else if (qrStatus == "scanned") {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color(0xE614281D))
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = c.ok,
                                modifier = Modifier.size(34.dp),
                            )
                            Text(
                                "已扫码，请在手机上点击确认",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    GlassSpinner(size = 28.dp, color = Color(0xFF286DEF), stroke = 2.5.dp)
                    Text(
                        "正在获取二维码…",
                        color = Color(0xFF5F6672),
                        fontSize = 12.sp,
                    )
                }
            }
        }

        val statusCaption = when (qrStatus) {
            "scanned" -> "请在红果短剧 App 中确认登录"
            "expired" -> "二维码已过期，请点击刷新"
            "ready" -> "二维码有效，请使用红果短剧 App 扫描"
            else -> "正在连接红果登录服务…"
        }
        Text(
            statusCaption,
            color = if (qrStatus == "scanned") c.ok else c.secondary,
            fontSize = 12.sp,
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GlassButton("刷新二维码", Modifier.weight(1f).heightIn(min = 44.dp), ButtonTone.Glass, ButtonSize.Small, icon = Icons.Filled.Refresh, onClick = onRefresh)
            GlassButton(
                if (savingQr) "保存中…" else "保存二维码",
                Modifier.weight(1f).heightIn(min = 44.dp),
                size = ButtonSize.Small,
                icon = Icons.Outlined.Download,
                enabled = qrBitmap != null,
                loading = savingQr,
                onClick = onSave,
            )
        }

        if (qrBitmap != null) {
            GlassButton(
                if (verifyingNow) "正在核验登录状态…" else "我已扫码确认，立即检查登录状态",
                Modifier.fillMaxWidth().heightIn(min = 42.dp),
                ButtonTone.Glass,
                ButtonSize.Small,
                enabled = !verifyingNow,
                loading = verifyingNow,
                onClick = onCheckNow,
            )
        }

        if (!saveNotice.isNullOrBlank()) {
            Text(
                saveNotice,
                color = if (saveError) c.accentWarm else c.ok,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
