package app.booruhub

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicLong
import android.content.Context
import android.webkit.CookieManager
import org.json.JSONObject
import java.io.File

/**
 * The Rust core (crates/android → libbooruhub.so): two calls, strings in and
 * strings out. Everything else is a JSON request to [call]; see
 * crates/core/src/rpc.rs for the methods. A call blocks its thread while the
 * core works; [ask] runs it on an IO thread and, when the coroutine is
 * cancelled (a post swiped past, a screen left), tells the core to drop it.
 */
object Core {
    private external fun init(dir: String, agent: String, proxy: String?): Boolean
    private external fun call(request: String): String
    private external fun cancel(rid: Long)

    private val rids = AtomicLong()
    private val io = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var dir: String

    /** The signed-in name and API key, sent with every request; null when signed out. */
    @Volatile
    var login: Pair<String, String>? = null

    /** The other boorus' logins by site code, sent with every request too. */
    @Volatile
    var logins: Map<String, Pair<String, String>> = emptyMap()

    /** Remembers where the core keeps its files; nothing is loaded yet. */
    fun start(ctx: Context) {
        dir = File(ctx.filesDir, "core").path
    }

    // Loading the library and reading collections from disk happen on the
    // first request, which always comes on an IO thread — not at app start.
    private val loaded = lazy {
        System.loadLibrary("booruhub")
        val host = System.getProperty("http.proxyHost")
        val port = System.getProperty("http.proxyPort")
        val proxy = if (!host.isNullOrBlank()) "$host:${port ?: "80"}" else null
        init(dir, Net.ua, proxy)
    }
    private val ready by loaded

    /**
     * One request: the method, its arguments, and the WebView's cookies so a
     * Cloudflare pass carries over. Answers the `ok` value, or throws
     * [CloudflareChallenge] / [ApiError] as the OkHttp calls used to.
     */
    fun request(m: String, vararg args: Pair<String, Any?>): Any? = request(m, 0L, *args)

    private fun request(m: String, rid: Long, vararg args: Pair<String, Any?>): Any? {
        check(ready) { "ядро не запустилось" }
        val req = JSONObject()
        for ((k, v) in args) req.put(k, v ?: JSONObject.NULL)
        req.put("m", m)
        if (rid > 0) req.put("rid", rid)
        req.put("cookies", CookieManager.getInstance().getCookie(E621.BASE).orEmpty())
        req.put("login", login?.let { org.json.JSONArray(listOf(it.first, it.second)) } ?: JSONObject.NULL)
        req.put("logins", JSONObject().apply { for ((site, pair) in logins) put(site, org.json.JSONArray(listOf(pair.first, pair.second))) })
        val answer = JSONObject(call(req.toString()))
        answer.optJSONObject("err")?.let { err ->
            when (err.optString("kind")) {
                "cloudflare" -> throw CloudflareChallenge()
                "cancelled" -> throw CancellationException("отменено")
            }
            throw ApiError(err.optString("msg", "ошибка ядра"))
        }
        return answer.opt("ok").takeIf { it != JSONObject.NULL }
    }

    /**
     * [request], suspending: it runs on an IO thread, and cancelling the
     * caller cancels the core's request too, mid-flight, instead of letting
     * it finish for nobody.
     */
    suspend fun ask(m: String, vararg args: Pair<String, Any?>): Any? = suspendCancellableCoroutine { cont ->
        val rid = rids.incrementAndGet()
        io.launch {
            val result = runCatching { request(m, rid, *args) }
            cont.resumeWith(result)
        }
        cont.invokeOnCancellation { runCatching { if (loaded.isInitialized()) cancel(rid) } }
    }
}
