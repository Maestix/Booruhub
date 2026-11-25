package app.booruhub

import android.webkit.WebView
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * One HTTP client for the API, the pictures and the videos.
 *
 * Cloudflare ties `cf_clearance` to the cookie *and* the User-Agent that
 * solved the challenge, so the client sends exactly the WebView's agent (with
 * the app's name appended, as e621 asks of API clients) and reads and writes
 * the WebView's own cookie store. A challenge solved in the WebView is then
 * good for every request the app makes.
 */
object Net {
    lateinit var ua: String
        private set
    lateinit var client: OkHttpClient
        private set

    /** The agent for every site but e621. */
    val plainUa = "booruhub/${BuildConfig.VERSION_NAME} (Android)"

    /** The site page a booru's file servers expect to be linked from. */
    fun refererFor(host: String): String? = when {
        host == "gelbooru.com" || host.endsWith(".gelbooru.com") -> "https://gelbooru.com/"
        host == "rule34.xxx" || host.endsWith(".rule34.xxx") -> "https://rule34.xxx/"
        host.endsWith("donmai.us") -> "https://danbooru.donmai.us/"
        else -> null
    }

    fun init(ctx: Context) {
        // Asking WebView for its agent loads WebView itself; keep the answer
        // until WebView updates, so a cold start does not pay for it again.
        val prefs = ctx.getSharedPreferences("net", Context.MODE_PRIVATE)
        val version = runCatching { WebView.getCurrentWebViewPackage()?.longVersionCode }.getOrNull() ?: -1L
        val base = prefs.getString("ua", null)?.takeIf { prefs.getLong("uaFor", -2L) == version }
            ?: try {
                WebSettings.getDefaultUserAgent(ctx).also {
                    prefs.edit().putString("ua", it).putLong("uaFor", version).apply()
                }
            } catch (_: Throwable) {
                "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) Chrome Mobile Safari/537.36"
            }
        ua = "$base booruhub/${BuildConfig.VERSION_NAME} (Android e621 client)"
        client = OkHttpClient.Builder()
            .cookieJar(WebCookies)
            .addInterceptor { chain ->
                // e621 gets the WebView's agent its Cloudflare pass is tied to; the
                // other boorus' CDNs stop a browser's agent that has no pass of theirs.
                val host = chain.request().url.host
                val agent = if (host == "e621.net" || host.endsWith(".e621.net")) ua else plainUa
                val req = chain.request().newBuilder().header("User-Agent", agent)
                // Gelbooru's CDN answers a request without its site as Referer
                // with a redirect to the gallery page: HTML instead of the picture.
                refererFor(host)?.let { req.header("Referer", it) }
                chain.proceed(req.build())
            }
            .dns(Ipv4First)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * IPv4 before IPv6. e621 publishes both, and on a network that hands out
 * IPv6 addresses without a working route (some carriers, the emulator) OkHttp
 * 4 tries the v6 address first and sits out the whole connect timeout.
 */
object Ipv4First : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        Dns.SYSTEM.lookup(hostname).sortedBy { if (it is Inet4Address) 0 else 1 }
}

/**
 * One disk cache for videos, so a loop or a swipe back plays from disk instead
 * of downloading the file again.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object VideoCache {
    private var cache: androidx.media3.datasource.cache.SimpleCache? = null

    fun factory(ctx: Context): androidx.media3.datasource.DataSource.Factory {
        val c = cache ?: androidx.media3.datasource.cache.SimpleCache(
            java.io.File(ctx.cacheDir, "video"),
            androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor(200L * 1024 * 1024),
            androidx.media3.database.StandaloneDatabaseProvider(ctx),
        ).also { cache = it }
        return androidx.media3.datasource.cache.CacheDataSource.Factory()
            .setCache(c)
            .setUpstreamDataSourceFactory(androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(Net.client))
            .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
}

/** OkHttp's cookies are the WebView's. */
object WebCookies : CookieJar {
    private val cm: CookieManager get() = CookieManager.getInstance()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (c in cookies) cm.setCookie(url.toString(), c.toString())
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val raw = cm.getCookie(url.toString()) ?: return emptyList()
        return raw.split(';').mapNotNull { Cookie.parse(url, it.trim()) }
    }

    fun has(url: String, name: String): Boolean =
        cm.getCookie(url)?.split(';')?.any { it.trim().startsWith("$name=") } == true
}

/** Cloudflare wants a browser to prove itself: open the WebView. */
class CloudflareChallenge : IOException("Cloudflare challenge")

class ApiError(message: String) : IOException(message)

fun Response.isChallenge(): Boolean {
    if (code != 403 && code != 503 && code != 429) return false
    if (header("cf-mitigated").equals("challenge", ignoreCase = true)) return true
    val server = header("Server").orEmpty()
    val type = header("Content-Type").orEmpty()
    return server.contains("cloudflare", ignoreCase = true) && type.contains("text/html")
}
