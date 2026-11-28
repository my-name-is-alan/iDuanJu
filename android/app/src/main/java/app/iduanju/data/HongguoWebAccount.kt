package app.iduanju.data

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class HongguoWebAccount(ctx: Context) {

    data class Profile(val nickname: String, val avatarUrl: String)

    sealed class QRPollResult {
        data object Waiting : QRPollResult()
        data object Scanned : QRPollResult()
        data class Confirmed(val redirectUrl: String?) : QRPollResult()
        data object Expired : QRPollResult()
        data object Failed : QRPollResult()
    }

    companion object {
        const val WEB_ORIGIN = "https://hongguoduanju.com"
        const val PC_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private const val PREF_NAME = "idj"
        private const val PREF_PROFILE_KEY = "stv.hg.web.profile"
        private const val TAG = "IDJ-HGWeb"
        private const val SESSION_TAG_TTL_MS = 15 * 60_000L
        private const val REPORT_THROTTLE_MS = 30_000L

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val TAG_RE_PLAIN = Regex(""""pcHistorySessionTag"\s*:\s*"([^"]+)"""")
        private val TAG_RE_ESCAPED = Regex("""\\"pcHistorySessionTag\\"\s*:\s*\\"([^\\"]+)\\"""")

        fun buildCheckQrUrl(checkUrl: String?, token: String?): String? {
            val raw = checkUrl?.trim().orEmpty()
            if (raw.startsWith("https://") && raw.contains("hongguoduanju.com")) {
                return raw
            }
            val tok = token?.trim().orEmpty()
            if (tok.isEmpty()) return null
            val encodedNext = java.net.URLEncoder.encode("$WEB_ORIGIN/", "UTF-8")
            val encodedTok = java.net.URLEncoder.encode(tok, "UTF-8")
            return "$WEB_ORIGIN/passport/web/check_qrconnect/?next=$encodedNext&token=$encodedTok" +
                "&passport_jssdk_version=2.4.13&passport_jssdk_type=normal&is_from_ttaccountsdk=1" +
                "&aid=513755&language=zh&account_sdk_source=web"
        }

        fun parseQrConnectResponse(body: String): QRPollResult {
            val root = runCatching { JSONObject(body) }.getOrNull() ?: return QRPollResult.Failed
            val d = root.optJSONObject("data") ?: return QRPollResult.Failed
            val rawStatus = d.opt("status")?.toString()?.trim()?.lowercase().orEmpty()
            val redirect = d.optString("redirect_url").trim().takeIf { it.isNotEmpty() }
            return when (rawStatus) {
                "confirmed", "3" -> QRPollResult.Confirmed(redirect)
                "scanned", "2" -> QRPollResult.Scanned
                "expired", "5", "refuse", "refused", "cancel", "cancelled", "4" -> QRPollResult.Expired
                "new", "1" -> QRPollResult.Waiting
                else -> if (d.optInt("error_code", 0) != 0) QRPollResult.Expired else QRPollResult.Failed
            }
        }
    }

    private val sp = ctx.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    private val noRedirectHttp = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionTags = ConcurrentHashMap<String, Pair<String, Long>>()
    private val lastReportedAt = ConcurrentHashMap<String, Long>()

    var profile by mutableStateOf<Profile?>(null)
        private set

    var syncing by mutableStateOf(false)
        private set

    var lastSyncNote by mutableStateOf<String?>(null)
        private set

    val isLoggedIn: Boolean get() = profile != null

    init {
        runCatching {
            val raw = sp.getString(PREF_PROFILE_KEY, null)
            if (!raw.isNullOrBlank()) {
                val j = JSONObject(raw)
                val nickname = j.optString("nickname").trim()
                val avatarUrl = j.optString("avatarUrl").trim()
                if (nickname.isNotEmpty()) {
                    profile = Profile(nickname, avatarUrl)
                }
            }
        }
    }

    fun cookies(): String? = runCatching {
        CookieManager.getInstance().getCookie(WEB_ORIGIN)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun extractCookieValue(cookieHeader: String?, name: String): String? {
        if (cookieHeader.isNullOrBlank()) return null
        return cookieHeader.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter("=")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    /**
     * Native `check_qrconnect` poll that survives Android background WebView throttling / SDK "Network Error"
     * when the user switches to Hongguo App to scan a saved/screenshotted QR code. Writes all `Set-Cookie`
     * headers (and follows `redirect_url` hops if present) directly into `CookieManager`.
     */
    suspend fun pollQRConnectNative(
        checkUrl: String?,
        token: String?,
        csrfToken: String? = null,
        traceId: String? = null,
    ): QRPollResult = withContext(Dispatchers.IO) {
        val targetUrl = buildCheckQrUrl(checkUrl, token) ?: return@withContext QRPollResult.Failed
        val cm = CookieManager.getInstance()
        runCatching { cm.flush() }
        val cookie = cm.getCookie(targetUrl) ?: cm.getCookie(WEB_ORIGIN)
        val resolvedCsrf = csrfToken?.takeIf { it.isNotBlank() }
            ?: extractCookieValue(cookie, "passport_csrf_token")
            ?: extractCookieValue(cookie, "passport_csrf_token_default")

        val reqBuilder = Request.Builder()
            .url(targetUrl)
            .header("User-Agent", PC_USER_AGENT)
            .header("Referer", "$WEB_ORIGIN/")
            .header("Accept", "application/json, text/javascript")
            .get()
        if (!cookie.isNullOrBlank()) reqBuilder.header("Cookie", cookie)
        if (!resolvedCsrf.isNullOrBlank()) reqBuilder.header("x-tt-passport-csrf-token", resolvedCsrf)
        if (!traceId.isNullOrBlank()) reqBuilder.header("x-tt-passport-trace-id", traceId)

        try {
            val (result, cookiesUpdated) = noRedirectHttp.newCall(reqBuilder.build()).execute().use { resp ->
                val setCookies = resp.headers("Set-Cookie")
                setCookies.forEach { sc -> cm.setCookie(targetUrl, sc) }
                val body = resp.body?.string().orEmpty()
                parseQrConnectResponse(body) to setCookies.isNotEmpty()
            }
            var dirty = cookiesUpdated
            if (result is QRPollResult.Confirmed && !result.redirectUrl.isNullOrBlank()) {
                var currentUrl: String? = result.redirectUrl
                var hops = 0
                while (!currentUrl.isNullOrBlank() && hops < 6) {
                    hops++
                    val hopUrl = currentUrl
                    val hopCookie = cm.getCookie(hopUrl) ?: cm.getCookie(WEB_ORIGIN)
                    val hopReq = Request.Builder()
                        .url(hopUrl)
                        .header("User-Agent", PC_USER_AGENT)
                        .header("Referer", "$WEB_ORIGIN/")
                        .apply { if (!hopCookie.isNullOrBlank()) header("Cookie", hopCookie) }
                        .get()
                        .build()
                    val nextLoc = runCatching {
                        noRedirectHttp.newCall(hopReq).execute().use { hopResp ->
                            val hopSetCookies = hopResp.headers("Set-Cookie")
                            hopSetCookies.forEach { sc -> cm.setCookie(hopUrl, sc) }
                            if (hopSetCookies.isNotEmpty()) dirty = true
                            if (hopResp.code in 300..399) {
                                val loc = hopResp.header("Location")
                                if (!loc.isNullOrBlank()) java.net.URI(hopUrl).resolve(loc).toString() else null
                            } else {
                                null
                            }
                        }
                    }.getOrNull()
                    currentUrl = nextLoc
                }
            }
            if (dirty) runCatching { cm.flush() }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "pollQRConnectNative failed", e)
            QRPollResult.Failed
        }
    }

    private suspend fun saveProfile(next: Profile?) {
        if (next == null) {
            sp.edit().remove(PREF_PROFILE_KEY).apply()
        } else {
            val json = JSONObject()
                .put("nickname", next.nickname)
                .put("avatarUrl", next.avatarUrl)
                .toString()
            sp.edit().putString(PREF_PROFILE_KEY, json).apply()
        }
        withContext(Dispatchers.Main + NonCancellable) {
            profile = next
        }
    }

    suspend fun refreshProfile(): Profile? = withContext(Dispatchers.IO) {
        withContext(Dispatchers.Main) { syncing = true }
        try {
            runCatching { CookieManager.getInstance().flush() }
            val cookie = cookies()
            if (cookie == null) {
                saveProfile(null)
                return@withContext null
            }

            val req = Request.Builder()
                .url("$WEB_ORIGIN/api/pc/profile")
                .header("Cookie", cookie)
                .header("User-Agent", PC_USER_AGENT)
                .header("Referer", "$WEB_ORIGIN/")
                .header("Accept", "application/json")
                .get()
                .build()

            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.code == 200) {
                    val p = JSONObject(body).optJSONObject("profile")
                    if (p == null) {
                        saveProfile(null)
                        return@withContext null
                    }
                    val nickname = p.optString("nickname").ifBlank { p.optString("name") }.trim()
                    val avatarUrl = p.optString("avatarUrl").ifBlank { p.optString("avatar_url") }.trim()
                    if (nickname.isEmpty()) {
                        saveProfile(null)
                        return@withContext null
                    }
                    val next = Profile(nickname, avatarUrl)
                    saveProfile(next)
                    return@withContext next
                }
            }
            profile
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "refreshProfile failed", e)
            profile
        } finally {
            withContext(Dispatchers.Main + NonCancellable) { syncing = false }
        }
    }

    fun logout() {
        runCatching {
            val cm = CookieManager.getInstance()
            val raw = cm.getCookie(WEB_ORIGIN)
            if (!raw.isNullOrBlank()) {
                raw.split(";").forEach { pair ->
                    val name = pair.substringBefore("=").trim()
                    if (name.isNotEmpty()) {
                        cm.setCookie(WEB_ORIGIN, "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/")
                        cm.setCookie(WEB_ORIGIN, "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Domain=.hongguoduanju.com; Path=/")
                    }
                }
            }
            cm.flush()
        }.onFailure {
            Log.w(TAG, "logout cookie clear failed", it)
        }
        sessionTags.clear()
        lastReportedAt.clear()
        profile = null
        lastSyncNote = null
        sp.edit().remove(PREF_PROFILE_KEY).apply()
    }

    fun reportProgressAsync(
        seriesId: String,
        vid: String,
        episodeNumber: Int,
        positionMs: Long,
        accumulatedMs: Long,
        force: Boolean = false,
    ) {
        if (!isLoggedIn ||
            seriesId.isEmpty() || !seriesId.all { it.isDigit() } ||
            vid.isEmpty() || !vid.all { it.isDigit() } ||
            episodeNumber <= 0 || positionMs <= 0
        ) {
            return
        }

        val key = "$seriesId:$vid"
        val now = System.currentTimeMillis()
        if (!force && now - (lastReportedAt[key] ?: 0L) < REPORT_THROTTLE_MS) {
            return
        }
        lastReportedAt[key] = now

        scope.launch {
            try {
                val cookie = cookies() ?: return@launch
                val payload = JSONObject()
                    .put("book_id", seriesId)
                    .put("book_type", 2)
                    .put("is_delete", false)
                    .put("read_timestamp_ms", System.currentTimeMillis().toString())
                    .put("vid", vid)
                    .put("vid_index", episodeNumber.toString())
                    .put("current_play_position", positionMs.coerceAtLeast(1L).toString())
                    .put("player_accumulate_total_time", accumulatedMs.coerceAtLeast(1000L).toString())
                    .toString()

                var lastReason: String? = null
                for (attempt in 0..1) {
                    val sessionTag = fetchSessionTag(seriesId, vid, cookie, forceRefresh = attempt > 0)
                    if (sessionTag == null) {
                        Log.w(TAG, "reportProgressAsync: missing pcHistorySessionTag for $seriesId/$vid")
                        withContext(Dispatchers.Main + NonCancellable) { lastSyncNote = "未获取到网页同步会话，请尝试重新登录" }
                        return@launch
                    }

                    val req = Request.Builder()
                        .url("$WEB_ORIGIN/api/pc/watch-history")
                        .header("Cookie", cookie)
                        .header("User-Agent", PC_USER_AGENT)
                        .header("Accept", "application/json")
                        .header("Origin", WEB_ORIGIN)
                        .header("Referer", "$WEB_ORIGIN/player/$seriesId/$vid")
                        .header("X-PC-History-Session", sessionTag)
                        .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                        .build()

                    val succeeded = http.newCall(req).execute().use { resp ->
                        val respBody = resp.body?.string().orEmpty()
                        val json = runCatching { JSONObject(respBody) }.getOrNull()
                        val ok = json?.optBoolean("isSuccess") == true
                        Log.i(TAG, "watch-history attempt=$attempt HTTP ${resp.code} ok=$ok body=${respBody.take(160)}")
                        if (ok) {
                            withContext(Dispatchers.Main + NonCancellable) {
                                lastSyncNote = "已同步第 $episodeNumber 集到红果"
                            }
                            true
                        } else {
                            sessionTags.remove(key)
                            lastReason = json?.optString("reason")?.takeIf { it.isNotBlank() } ?: "HTTP ${resp.code}"
                            false
                        }
                    }
                    if (succeeded) return@launch
                }
                lastReason?.let { reason ->
                    withContext(Dispatchers.Main + NonCancellable) {
                        lastSyncNote = "同步未生效（$reason）"
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "reportProgressAsync failed", e)
            }
        }
    }

    private fun fetchSessionTag(seriesId: String, vid: String, cookie: String, forceRefresh: Boolean = false): String? {
        val key = "$seriesId:$vid"
        val now = System.currentTimeMillis()
        val cached = sessionTags[key]
        if (!forceRefresh && cached != null && now - cached.second < SESSION_TAG_TTL_MS) {
            return cached.first
        }

        for (pageUrl in listOf("$WEB_ORIGIN/player/$seriesId/$vid", "$WEB_ORIGIN/player/$seriesId")) {
            val req = Request.Builder()
                .url(pageUrl)
                .header("Cookie", cookie)
                .header("User-Agent", PC_USER_AGENT)
                .header("Referer", "$WEB_ORIGIN/")
                .get()
                .build()

            val tag = runCatching {
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val html = resp.body?.string().orEmpty()
                    TAG_RE_PLAIN.find(html)?.groupValues?.getOrNull(1)
                        ?: TAG_RE_ESCAPED.find(html)?.groupValues?.getOrNull(1)
                }
            }.getOrNull()
            if (!tag.isNullOrBlank()) {
                sessionTags[key] = tag to System.currentTimeMillis()
                return tag
            }
        }
        return null
    }
}
