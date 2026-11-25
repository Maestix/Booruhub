package app.booruhub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

/** Loads once per post; null while it comes, the error's words if it fails. */
@Composable
private fun <T> rememberFetch(key: Any, fetch: suspend () -> T): Pair<T?, String?> {
    var got by remember(key) { mutableStateOf<T?>(null) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    LaunchedEffect(key) {
        try {
            got = fetch()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = humanError(e)
        }
    }
    return got to error
}

/** The post's parent and siblings as a strip of thumbnails; a tap opens them all over the viewer. */
@Composable
fun FamilyStrip(hub: Hub, p: Post) {
    if (!p.inFamily) return
    val (family, error) = rememberFetch(p.key) { E621.family(p) }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Kicker(if (p.parent > 0) "СВЯЗАННЫЕ · ВЕРСИИ И СТРАНИЦЫ" else "У ПОСТА ЕСТЬ ВЕРСИИ")
        when {
            error != null -> Text(error, style = T.small.copy(color = C.N500))
            family == null -> CircularProgressIndicator(color = C.Accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(family, key = { _, it -> it.key }) { i, q ->
                    val here = q.key == p.key
                    Box(
                        Modifier
                            .size(width = 58.dp, height = 74.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(C.Surface)
                            .then(if (here) Modifier.border(2.dp, C.Accent, RoundedCornerShape(10.dp)) else Modifier)
                            .clickable(enabled = !here) {
                                hub.openOver(Feed().apply { posts.addAll(family); local = true; end = true; site = p.site }, i)
                            },
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current).data(q.previewUrl).build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (q.id == (if (p.parent > 0) p.parent else p.id) && family.size > 1) {
                            Badge("ОСН", C.Violet, Modifier.align(Alignment.TopStart).padding(3.dp))
                        }
                    }
                }
            }
        }
    }
}

/** The pools the post is in: read on from here, or see every page. */
@Composable
fun PoolCards(hub: Hub, p: Post) {
    // e621 names its pools in the post; Danbooru is asked; the old API has none.
    if (!(p.site == "e621" && p.pools.isNotEmpty()) && p.site != "danbooru") return
    val (pools, _) = rememberFetch(p.key) { E621.pools(p) }
    for (pool in pools.orEmpty()) {
        val at = pool.posts.indexOf(p.id)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(C.Violet.copy(alpha = 0.10f))
                .border(1.dp, C.Violet.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
                .padding(start = 13.dp, end = 8.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(pool.name, style = T.card.copy(fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "пул · ${if (at >= 0) "${at + 1} из " else ""}${pool.posts.size}",
                    style = T.mono.copy(fontSize = 10.5.sp, color = C.N500),
                )
            }
            Box(
                Modifier.clip(S.pill).background(C.Tint).clickable { hub.openPool(pool, p, read = false) }.padding(horizontal = 11.dp, vertical = 6.dp),
            ) { Text("Все", style = T.small.copy(color = C.N300, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)) }
            Box(
                Modifier.clip(S.pill).background(C.Violet.copy(alpha = 0.3f)).clickable { hub.openPool(pool, p, read = true) }.padding(horizontal = 11.dp, vertical = 6.dp),
            ) { Text(if (at > 0) "Читать дальше" else "Читать", style = T.small.copy(color = C.Violet300, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)) }
        }
    }
}

/**
 * What a tag means, from the site's wiki, with the tags seen next to it and
 * what to do with the tag: search it, add it to the query, leave it out, block it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagSheet(hub: Hub, site: String, tag: String, category: String, onClose: () -> Unit) {
    val (wiki, error) = rememberFetch("$site/$tag") { E621.wiki(site, tag) }
    val color = if (category.isEmpty()) C.Violet300 else tagColor(category)
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 620.dp)
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column {
            Text(tag.replace('_', ' '), style = T.title.copy(fontSize = 22.sp, color = color))
            val sub = listOfNotNull(
                category.takeIf { it.isNotEmpty() }?.let { tagCategoryTitle(it).lowercase() },
                wiki?.count?.takeIf { it > 0 }?.let { "$it ${plural(it, "пост", "поста", "постов")}" },
                Site.of(site).title,
            ).joinToString(" · ")
            Text(sub, style = T.mono.copy(fontSize = 11.sp, color = C.N500))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SheetAction("Искать", C.Violet, Modifier.weight(1f)) { onClose(); hub.search(tag, on = site, keepMix = true) }
            SheetAction("+ к запросу", C.Sky, Modifier.weight(1f)) { onClose(); hub.search(hub.main.tags + " " + tag, on = site, keepMix = true) }
            SheetAction("− исключить", C.Bad, Modifier.weight(1f)) { onClose(); hub.search(hub.main.tags + " -" + tag, on = site, keepMix = true) }
        }
        when {
            error != null -> Text(error, style = T.small.copy(color = C.N500))
            wiki == null -> Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = C.Accent, strokeWidth = 2.5.dp)
            }
            else -> {
                Text(
                    wiki.body.ifBlank { "Описания в вики нет." },
                    style = T.small.copy(fontSize = 13.5.sp, color = C.Text.copy(alpha = if (wiki.body.isBlank()) 0.5f else 0.88f)),
                )
                TagCloud("В ТЕКСТЕ", wiki.links.take(16)) { onClose(); hub.search(it, on = site, keepMix = true) }
                TagCloud("ЧАСТО ВМЕСТЕ", wiki.related.take(20)) { onClose(); hub.search(it, on = site, keepMix = true) }
            }
        }
        GhostButton("В чёрный список", { hub.addBlock(tag); hub.say("«${tag.replace('_', ' ')}» в чёрном списке"); onClose() }, color = C.Bad)
    }
}

@Composable
private fun SheetAction(text: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.16f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = T.small.copy(color = color, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp), maxLines = 1) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagCloud(title: String, tags: List<String>, onTag: (String) -> Unit) {
    if (tags.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Kicker(title)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (t in tags) Box(
                Modifier.clip(RoundedCornerShape(9.dp)).background(Color(0xFF221C30)).clickable { onTag(t) }.padding(horizontal = 10.dp, vertical = 5.dp),
            ) { Text(t.replace('_', ' '), style = T.small.copy(fontSize = 12.sp, color = C.Text.copy(alpha = 0.85f))) }
        }
    }
}

/**
 * Notes over a picture shown at [box] size with the file's [w]×[h] fitted in
 * it: a frame on each, a tap shows its words under it.
 */
@Composable
fun NotesLayer(notes: List<Note>, w: Int, h: Int, box: IntSize, lift: Float) {
    if (notes.isEmpty() || w <= 0 || h <= 0 || box.width == 0) return
    val fit = minOf(box.width.toFloat() / w, box.height.toFloat() / h)
    val left = (box.width - w * fit) / 2f
    val top = (box.height - h * fit) / 2f - lift
    var open by remember(notes) { mutableStateOf(-1) }
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize()) {
        notes.forEachIndexed { i, n ->
            with(density) {
                Box(
                    Modifier
                        .offset((left + n.x * fit).toDp(), (top + n.y * fit).toDp())
                        .size((n.w * fit).toDp(), (n.h * fit).toDp())
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (open == i) C.Accent.copy(alpha = 0.22f) else Color(0x33FFFFFF))
                        .border(1.dp, if (open == i) C.Accent else Color(0xAAFFFFFF), RoundedCornerShape(4.dp))
                        .clickable(remember { MutableInteractionSource() }, indication = null) { open = if (open == i) -1 else i },
                )
            }
        }
        notes.getOrNull(open)?.let { n ->
            with(density) {
                val y = top + (n.y + n.h) * fit + 6.dp.toPx()
                Box(
                    Modifier
                        .offset((left + n.x * fit).coerceIn(8.dp.toPx(), (box.width - 260.dp.toPx()).coerceAtLeast(8.dp.toPx())).toDp(), y.toDp())
                        .widthIn(max = 260.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xF2201A2B))
                        .border(1.dp, C.Accent.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                        .clickable(remember { MutableInteractionSource() }, indication = null) { open = -1 }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                ) { Text(n.body, style = T.small.copy(fontSize = 13.sp, color = C.Text)) }
            }
        }
    }
}

/** The best-of tab's spans, under the sort tabs. */
@Composable
fun BestScales(hub: Hub, feed: Feed) {
    Row(Modifier.padding(start = 16.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((key, label) in BEST_SCALES) Chip(label, feed.order == BEST + key) { hub.setOrder(feed, BEST + key) }
        Spacer(Modifier.width(4.dp))
    }
}
