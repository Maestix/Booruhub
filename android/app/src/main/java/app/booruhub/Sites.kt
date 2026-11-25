package app.booruhub

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The boorus the app reads; the core (crates/core/src/boorus.rs) speaks to each in its own way. */
enum class Site(
    val code: String,
    val title: String,
    /** The design's two- or three-letter mark. */
    val short: String,
    val color: Color,
    val base: String,
    /** Answers apps only with an API key. */
    val keyed: Boolean,
    /** Takes favourites through its API. */
    val favs: Boolean,
    val userLabel: String,
    val help: String,
    val keyPage: String,
) {
    E621(
        "e621", "e621", "E6", Color(0xFF8EA0FF), "https://e621.net", keyed = false, favs = true,
        "Имя пользователя", "", "https://e621.net/session/new",
    ),
    Danbooru(
        "danbooru", "Danbooru", "DB", Color(0xFFF0A3C8), "https://danbooru.donmai.us", keyed = false, favs = true,
        "Имя пользователя",
        "Danbooru открыт и без входа, но тогда ищет не больше двух тегов. Вход — по имени и API-ключу: My Account → API Key.",
        "https://danbooru.donmai.us/profile",
    ),
    Gelbooru(
        "gelbooru", "Gelbooru", "GB", Color(0xFF7FC8E8), "https://gelbooru.com", keyed = true, favs = false,
        "User ID",
        "Gelbooru отвечает приложениям только с ключом. Он на сайте: My Account → Options → API Access Credentials. Строку «&api_key=…&user_id=…» можно вставить целиком в любое поле.",
        "https://gelbooru.com/index.php?page=account&s=options",
    ),
    Rule34(
        "rule34", "Rule34", "R34", Color(0xFF9BD7A0), "https://rule34.xxx", keyed = true, favs = false,
        "User ID",
        "Rule34 отвечает приложениям только с ключом. Он на сайте: My Account → Options → API Access Credentials. Строку «&api_key=…&user_id=…» можно вставить целиком в любое поле.",
        "https://rule34.xxx/index.php?page=account&s=options",
    );

    fun postUrl(id: Long) = when (this) {
        E621, Danbooru -> "$base/posts/$id"
        else -> "$base/index.php?page=post&s=view&id=$id"
    }

    companion object {
        fun of(code: String?): Site = entries.firstOrNull { it.code == code } ?: E621
    }
}

/** The feed that takes from several sites at once (the design's 1b). */
const val MIX = "mix"

/** "&api_key=…&user_id=…", as the old API's options page shows it, pasted into either field. */
fun splitKeyLine(user: String, key: String): Pair<String, String> {
    val line = "$user&$key"
    val k = Regex("api_key=([A-Za-z0-9]+)").find(line)?.groupValues?.get(1)
    val u = Regex("user_id=([0-9]+)").find(line)?.groupValues?.get(1)
    return if (k != null && u != null) u to k else user.trim() to key.trim()
}

@Composable
fun SiteMark(site: Site, modifier: Modifier = Modifier, size: Dp = 16.dp) =
    Box(modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(site.color))

/** The marks of a mixed feed's sites, overlapping as in the design. */
@Composable
fun MixMarks(sites: List<Site>) {
    Box(Modifier.width((16 + 11 * (sites.size - 1).coerceAtLeast(0)).dp).height(16.dp)) {
        sites.forEachIndexed { i, s -> SiteMark(s, Modifier.offset(x = (11 * i).dp)) }
    }
}

/** The feed header's source (the design's 1c): its mark, its name, a chevron; a tap opens the list. */
@Composable
fun SourcePill(hub: Hub, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(S.pill)
            .background(Color(0x12FFFFFF))
            .clickable(onClick = onClick)
            .padding(start = 9.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (hub.site == MIX) {
            MixMarks(hub.mixSites.map(Site::of))
            Text("Смешанная", style = T.body.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold), maxLines = 1)
        } else {
            val s = Site.of(hub.site)
            SiteMark(s)
            Text(s.title, style = T.body.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold), maxLines = 1)
        }
        Ico(Icons.Down, C.N500, size = 13.dp)
    }
}

/** The design's 1a: every source, a tap to open its feed, switches to build the mixed one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesSheet(hub: Hub, onClose: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = C.Surface,
        shape = S.sheet,
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Источники", style = T.card.copy(fontSize = 18.sp), modifier = Modifier.padding(start = 4.dp))
            Text(
                "Нажмите на источник — откроется его лента. Переключатели собирают смешанную.",
                style = T.small.copy(fontSize = 12.sp, color = C.N500),
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
            val shape = RoundedCornerShape(14.dp)
            for (s in Site.entries) {
                val on = hub.site == s.code
                val ready = hub.ready(s)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(Color(0xFF171320))
                        .border(1.dp, if (on) C.Violet.copy(alpha = 0.7f) else Color(0x0DFFFFFF), shape)
                        .clickable {
                            onClose()
                            if (ready) hub.chooseSite(s.code) else hub.needKey(s)
                        }
                        .padding(horizontal = 13.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(13.dp),
                ) {
                    Box(
                        Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(s.color),
                        contentAlignment = Alignment.Center,
                    ) { Text(s.short, style = T.mono.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0D0B12))) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(s.title, style = T.body.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
                        Text(hub.siteStatus(s), style = T.mono.copy(fontSize = 11.sp, color = if (ready) C.N500 else C.Amber), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Toggle(s.code in hub.mixSites) { hub.toggleMix(s, it) }
                }
            }
            val names = hub.mixSites.map { Site.of(it).title }
            Row(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF7C5CFF))
                    .clickable {
                        if (hub.mixSites.size < 2) hub.say("Отметьте хотя бы два источника")
                        else { onClose(); hub.chooseSite(MIX) }
                    }
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Собрать смешанную ленту", style = T.body.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White))
                    Text(
                        names.joinToString(" · ").ifEmpty { "ничего не отмечено" },
                        style = T.mono.copy(fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f)),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(Modifier.clip(RoundedCornerShape(9.dp)).background(Color.White.copy(alpha = 0.18f)).padding(horizontal = 9.dp, vertical = 5.dp)) {
                    Text("${names.size}", style = T.mono.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White))
                }
            }
        }
    }
}

/** A site's sign-in in the settings: a name (or user id) and an API key, checked by the site before they are kept. */
@Composable
fun SiteLogin(hub: Hub, s: Site, hairline: Boolean) {
    val login = hub.logins[s.code]
    var open by remember { mutableStateOf(false) }
    var user by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    if (hairline) Hairline()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = login == null) { open = !open }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(13.dp)).background(s.color),
            contentAlignment = Alignment.Center,
        ) { Text(s.short, style = T.mono.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0D0B12))) }
        Column(Modifier.weight(1f)) {
            Text(s.title, style = T.card.copy(fontSize = 14.5.sp))
            Text(
                when {
                    login != null -> "вход: ${login.first}"
                    s.keyed -> "нужен API-ключ"
                    else -> "без входа"
                },
                style = T.mono.copy(fontSize = 11.sp, color = if (login == null && s.keyed) C.Amber else C.N500),
            )
        }
        if (login != null) {
            Text(
                "Выйти",
                style = T.body.copy(fontSize = 13.sp, color = C.Bad),
                modifier = Modifier.clip(S.pill).clickable { hub.siteLogout(s) }.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        } else {
            Ico(Icons.Down, C.N500, size = 16.dp)
        }
    }
    if (login == null && open) {
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(s.help, style = T.small.copy(fontSize = 12.5.sp))
            Field(user, { user = it }, s.userLabel, Modifier.fillMaxWidth(), ime = ImeAction.Next, leading = Icons.User)
            Field(
                key, { key = it }, "API-ключ", Modifier.fillMaxWidth(),
                secret = true, ime = ImeAction.Done, onIme = { hub.siteLogin(s, user, key) }, leading = Icons.Key,
            )
            hub.siteError[s.code]?.let { Text(it, style = T.small.copy(color = C.Bad)) }
            if (hub.siteBusy == s.code) {
                Box(Modifier.fillMaxWidth().height(50.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = C.Accent, strokeWidth = 2.5.dp, modifier = Modifier.size(26.dp))
                }
            } else {
                PrimaryButton("Войти", { hub.siteLogin(s, user, key) }, Modifier.fillMaxWidth())
            }
            GhostButton(
                "Открыть страницу с ключом",
                { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.keyPage))) } },
                Modifier.fillMaxWidth(), color = C.Violet, icon = Icons.External,
            )
        }
    }
}
