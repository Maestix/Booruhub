package app.booruhub

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import org.json.JSONTokener

private fun isChallengeTitle(t: String) =
    t.contains("just a moment", true) || t.contains("attention required", true) ||
        t.contains("один момент", true) || t.isBlank()

/** Reads the key off the api_key page, and the name e621 puts in a meta tag. */
private const val KEY_JS = """
(function(){
  var t = document.body ? document.body.innerText : '';
  var m = t.match(/\b[A-Za-z0-9]{24}\b/);
  var meta = document.querySelector('meta[name="current-user-name"]');
  var d = document.body && document.body.dataset ? document.body.dataset : {};
  var n = (meta && meta.content) || d.userName || d.currentUserName || '';
  return JSON.stringify({k: m ? m[0] : '', n: n});
})()
"""

/**
 * e621 in a WebView with the app's own agent and cookie store: for passing
 * Cloudflare's check, and for signing in on the site to fetch the API key.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebScreen(hub: Hub, web: Web) {
    var url by remember { mutableStateOf(web.url) }
    var loading by remember { mutableStateOf(true) }
    val cf = web.purpose == WebPurpose.Cloudflare

    BackHandler { hub.closeWeb(solved = cf && WebCookies.has(E621.BASE, "cf_clearance")) }

    Column(Modifier.fillMaxSize().background(C.Bg)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(C.Surface)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RoundButton(Icons.Close, { hub.closeWeb(solved = false) }, size = 40.dp, bg = C.Tint)
            Column(Modifier.weight(1f)) {
                Text(
                    if (cf) "Проверка Cloudflare" else "Вход на e621",
                    style = T.card,
                )
                Text(
                    if (cf) "Дождитесь сайта — окно закроется само"
                    else "Войдите, затем Account → Manage API Access",
                    style = T.tiny, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = C.Accent, trackColor = C.Surface)

        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(android.graphics.Color.rgb(0x14, 0x0F, 0x1D))
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = Net.ua
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, u: String, favicon: Bitmap?) {
                            loading = true; url = u
                        }

                        override fun onPageFinished(view: WebView, u: String) {
                            loading = false
                            url = u
                            val title = view.title.orEmpty()
                            CookieManager.getInstance().flush()
                            if (cf) {
                                // The site itself has loaded: whatever cookie
                                // Cloudflare gave, the app has it now.
                                if (!isChallengeTitle(title) && u.startsWith(E621.BASE)) hub.closeWeb(solved = true)
                            } else if (u.contains("api_key")) {
                                view.evaluateJavascript(KEY_JS) { raw ->
                                    val json = runCatching { JSONTokener(raw).nextValue() as String }.getOrNull() ?: return@evaluateJavascript
                                    val o = runCatching { JSONObject(json) }.getOrNull() ?: return@evaluateJavascript
                                    val key = o.optString("k")
                                    if (key.length == 24) hub.foundKey(o.optString("n").ifBlank { null }, key)
                                }
                            }
                        }
                    }
                    loadUrl(web.url)
                }
            },
            onRelease = { it.stopLoading(); it.destroy() },
        )

        Row(
            Modifier
                .fillMaxWidth()
                .background(C.Surface)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(url, style = T.tiny, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            PrimaryButton("Готово", { hub.closeWeb(solved = cf) })
        }
    }
}
