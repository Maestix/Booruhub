package app.booruhub

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class DlState { Queued, Running, Paused, Done, Failed }

/** One file in the queue; its numbers are state, so the screen follows them. */
class Dl(val postId: Long, val file: String, val url: String, val thumb: String?, val isVideo: Boolean, val site: String = "e621") {
    var state by mutableStateOf(DlState.Queued)
    var done by mutableLongStateOf(0L)
    var total by mutableLongStateOf(0L)
    var speed by mutableLongStateOf(0L)
    var error by mutableStateOf<String?>(null)
    var uri by mutableStateOf<String?>(null)
    var finishedAt = 0L
    /** Set while the finished file moves to the gallery, so it moves once. */
    var publishing = false
}

/**
 * The download queue, run by the Rust core (crates/core/src/download.rs):
 * two files at a time, each growing in a ".part" in the cache and resumed
 * with a Range request after a pause. This side starts and stops files, polls
 * their progress twice a second while any runs, and hands finished ones to
 * MediaStore under Pictures/BooruHub (videos under Movies/BooruHub) — no
 * permission needed on Android 10 and later.
 */
object Downloads {
    val items = mutableStateListOf<Dl>()
    var paused by mutableStateOf(false)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var polling: Job? = null
    private lateinit var app: Context

    /** Where a file grows while it downloads, so a pause can resume it. */
    private fun part(d: Dl) = File(app.cacheDir, "dl/${d.file}.part")

    private fun core(m: String, vararg args: Pair<String, Any?>) = scope.launch(Dispatchers.IO) {
        runCatching { Core.request(m, *args) }
    }

    fun init(ctx: Context) {
        app = ctx.applicationContext
        // Finished files are remembered between launches; the queue is not.
        val prefs = app.getSharedPreferences("downloads", Context.MODE_PRIVATE)
        runCatching {
            val a = JSONArray(prefs.getString("done", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                items.add(Dl(o.getLong("id"), o.getString("file"), o.optString("url"), o.optString("thumb").ifBlank { null }, o.optBoolean("video"), o.optString("site", "e621").ifBlank { "e621" }).apply {
                    state = DlState.Done; total = o.optLong("size"); done = total
                    uri = o.optString("uri").ifBlank { null }; finishedAt = o.optLong("at")
                })
            }
        }
    }

    private fun persist() {
        val a = JSONArray(items.filter { it.state == DlState.Done }.takeLast(500).map {
            JSONObject().put("id", it.postId).put("file", it.file).put("url", it.url).put("thumb", it.thumb ?: "")
                .put("video", it.isVideo).put("site", it.site).put("size", it.total).put("uri", it.uri ?: "").put("at", it.finishedAt)
        })
        app.getSharedPreferences("downloads", Context.MODE_PRIVATE).edit().putString("done", a.toString()).apply()
    }

    /** Queues a post's original file; false when it is already there. */
    fun add(p: Post): Boolean {
        val url = p.fileUrl ?: return false
        // Ids repeat across sites: the queue keys files by site and id together.
        if (items.any { it.postId == p.uid && it.state != DlState.Failed }) return false
        items.removeAll { it.postId == p.uid }
        val d = Dl(p.uid, "${p.site}_${p.id}.${p.ext}", url, p.previewUrl, p.isVideo, p.site)
        items.add(0, d)
        if (paused) d.state = DlState.Paused else start(d)
        return true
    }

    private fun start(d: Dl) {
        d.state = DlState.Queued
        d.error = null
        core("dl_start", "id" to d.postId, "url" to d.url, "part" to part(d).path)
        poll()
    }

    private fun pause(d: Dl) {
        d.state = DlState.Paused
        d.speed = 0
        core("dl_pause", "id" to d.postId)
    }

    /** Follows the core's progress while anything is queued or running. */
    private fun poll() {
        if (polling?.isActive == true) return
        polling = scope.launch {
            while (items.any { it.state == DlState.Queued || it.state == DlState.Running }) {
                delay(500)
                val list = withContext(Dispatchers.IO) { runCatching { Core.request("dl_status") as? JSONArray }.getOrNull() } ?: continue
                for (i in 0 until list.length()) {
                    val st = list.getJSONObject(i)
                    val d = items.firstOrNull { it.postId == st.optLong("id") } ?: continue
                    if (d.state == DlState.Paused || d.state == DlState.Done || d.publishing) continue
                    d.done = st.optLong("done"); d.total = st.optLong("total"); d.speed = st.optLong("speed")
                    when (st.optString("state")) {
                        "queued" -> d.state = DlState.Queued
                        "running" -> d.state = DlState.Running
                        "failed" -> { d.state = DlState.Failed; d.error = st.optString("error", "ошибка") }
                        "done" -> finish(d)
                    }
                }
            }
        }
    }

    /** A finished file goes to the gallery; its ".part" and the core's entry go away. */
    private fun finish(d: Dl) {
        d.publishing = true
        d.state = DlState.Running
        d.speed = 0
        scope.launch {
            try {
                d.uri = withContext(Dispatchers.IO) { publish(d) }
                d.finishedAt = System.currentTimeMillis()
                d.done = d.total
                d.state = DlState.Done
                persist()
            } catch (e: Exception) {
                d.error = humanError(e)
                d.state = DlState.Failed
            }
            d.publishing = false
            core("dl_remove", "id" to d.postId, "part" to part(d).path)
        }
    }

    private fun publish(d: Dl): String {
        val part = part(d)
        val ext = d.file.substringAfterLast('.').lowercase()
        val mime = when (ext) {
            "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"
            "webm" -> "video/webm"; "mp4" -> "video/mp4"; else -> "application/octet-stream"
        }
        val video = mime.startsWith("video/")
        val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, d.file)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/BooruHub")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = app.contentResolver
        val uri = resolver.insert(collection, values) ?: throw ApiError("галерея не приняла файл")
        resolver.openOutputStream(uri)?.use { out -> part.inputStream().use { it.copyTo(out) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri.toString()
    }

    fun pauseAll() {
        paused = true
        items.filter { it.state == DlState.Running || it.state == DlState.Queued }.forEach(::pause)
    }

    fun resumeAll() {
        paused = false
        items.filter { it.state == DlState.Paused }.forEach(::start)
    }

    fun toggle(d: Dl) = when (d.state) {
        DlState.Running, DlState.Queued -> pause(d)
        DlState.Paused, DlState.Failed -> start(d)
        DlState.Done -> Unit
    }

    /** Clears what the chosen tab shows; files already saved stay in the gallery. */
    fun clear(tab: Int) {
        val drop = items.filter { inTab(it, tab) }
        drop.filter { it.state != DlState.Done }.forEach { core("dl_remove", "id" to it.postId, "part" to part(it).path) }
        items.removeAll(drop)
        persist()
    }
}

internal fun inTab(d: Dl, tab: Int) = when (tab) {
    0 -> d.state == DlState.Queued || d.state == DlState.Running || d.state == DlState.Paused
    1 -> d.state == DlState.Done
    else -> d.state == DlState.Failed
}

/** The design's downloads: speed up top, three tabs, cards with a bar, two buttons below. */
@Composable
fun DownloadsScreen(hub: Hub) {
    val ctx = LocalContext.current
    var tab by remember { mutableStateOf(0) }
    val list = Downloads.items
    val speed = list.filter { it.state == DlState.Running }.sumOf { it.speed }
    val counts = (0..2).map { t -> list.count { inTab(it, t) } }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text("Загрузки", style = T.title.copy(fontSize = 22.sp), modifier = Modifier.weight(1f))
            Text(
                if (speed > 0) "${bytes(speed)}/s" else if (Downloads.paused) "пауза" else "",
                style = T.mono.copy(fontSize = 12.sp, color = if (speed > 0) C.Good else C.N500),
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Chip("Текущие · ${counts[0]}", tab == 0) { tab = 0 }
            Chip("Завершённые · ${counts[1]}", tab == 1) { tab = 1 }
            Chip("Ошибки · ${counts[2]}", tab == 2, C.Bad) { tab = 2 }
        }
        Spacer(Modifier.height(12.dp))

        val shown = list.filter { inTab(it, tab) }
        Box(Modifier.weight(1f)) {
            if (shown.isEmpty()) {
                Text(
                    when (tab) {
                        0 -> "Очередь пуста. Скачать файл — кнопка «Скачать» у поста."
                        1 -> "Пока ничего не скачано"
                        else -> "Ошибок нет ✿"
                    },
                    style = T.small, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                items(shown, key = { it.postId }) { d ->
                    DlCard(d) {
                        if (d.state == DlState.Done) {
                            d.uri?.let { u ->
                                runCatching {
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW).setData(Uri.parse(u)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                }.onFailure { hub.say("Нечем открыть файл") }
                            }
                        } else Downloads.toggle(d)
                    }
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, C.Bg)))
                .abovePill()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BottomButton(if (Downloads.paused) "Продолжить" else "Пауза всех", C.Text, C.Tint, Modifier.weight(1f)) {
                if (Downloads.paused) Downloads.resumeAll() else Downloads.pauseAll()
            }
            BottomButton("Очистить", C.Bad, C.Bad.copy(alpha = 0.14f), Modifier.weight(1f)) { Downloads.clear(tab) }
        }
    }
}

@Composable
fun BottomButton(text: String, fg: Color, bg: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(50.dp).clip(RoundedCornerShape(18.dp)).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = T.card.copy(fontSize = 14.sp, color = fg)) }
}

@Composable
private fun DlCard(d: Dl, onClick: () -> Unit) {
    val frac = if (d.total > 0) (d.done.toFloat() / d.total).coerceIn(0f, 1f) else 0f
    val (pct, pctColor, barColor) = when (d.state) {
        DlState.Done -> Triple("100%", C.Good, C.Good)
        DlState.Failed -> Triple("—", C.Bad, C.Bad)
        DlState.Running -> Triple("${(frac * 100).toInt()}%", C.Violet300, C.Violet)
        else -> Triple("${(frac * 100).toInt()}%", C.N500, C.N600)
    }
    val stateText = when (d.state) {
        DlState.Queued -> "в очереди"
        DlState.Running -> "загрузка"
        DlState.Paused -> "пауза · тап — продолжить"
        DlState.Done -> "готово · ${ago(d.finishedAt)}"
        DlState.Failed -> "тап — повторить"
    }
    val meta = when (d.state) {
        DlState.Failed -> d.error ?: "ошибка"
        DlState.Done -> bytes(d.total)
        else -> "${bytes(d.done)} / ${if (d.total > 0) bytes(d.total) else "?"}" + if (d.speed > 0) " · ${bytes(d.speed)}/s" else ""
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(S.card)
            .background(C.Surface)
            .border(1.dp, Color(0x0DFFFFFF), S.card)
            .clickable(onClick = onClick)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(Modifier.width(52.dp).height(66.dp).clip(RoundedCornerShape(11.dp)).background(C.Raised)) {
            if (d.thumb != null) AsyncImage(d.thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(d.file, style = T.mono.copy(fontSize = 12.sp, color = C.Text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(pct, style = T.mono.copy(fontSize = 10.5.sp, color = pctColor, fontWeight = FontWeight.SemiBold))
            }
            Text(meta, style = T.mono.copy(fontSize = 10.5.sp, color = C.N500), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.fillMaxWidth().height(4.dp).clip(S.pill).background(Color(0x14FFFFFF))) {
                Box(Modifier.fillMaxWidth(if (d.state == DlState.Done) 1f else frac.coerceAtLeast(0.02f)).height(4.dp).clip(S.pill).background(barColor))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0x0FFFFFFF)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                    Text(Site.of(d.site).title, style = T.mono.copy(fontSize = 9.5.sp, color = Site.of(d.site).color, fontWeight = FontWeight.SemiBold))
                }
                Text(stateText, style = T.mono.copy(fontSize = 10.sp, color = C.N600))
            }
        }
    }
}

