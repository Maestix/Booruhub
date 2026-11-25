package app.booruhub

import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

// --- shared pieces ----------------------------------------------------------------------

/** A screen title with an optional back arrow and a mono note on the right. */
@Composable
fun PageHeader(title: String, note: String = "", onBack: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = if (onBack != null) 8.dp else 18.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onBack != null) BackButton(onBack)
        Text(title, style = T.title.copy(fontSize = 22.sp), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (note.isNotEmpty()) Text(note, style = T.mono.copy(fontSize = 11.5.sp, color = C.N500))
        trailing()
    }
}

/** The design's dashed "+ …" button. */
fun Modifier.dashed(color: Color, radius: Float = 18f): Modifier = drawBehind {
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(radius * density),
        style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
    )
}

/** A small dialog with text fields, in the app's colours. */
@Composable
fun TextDialog(
    title: String,
    fields: List<Pair<String, String>>,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    val values = remember { fields.map { mutableStateOf(it.second) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Surface,
        shape = S.card,
        title = { Text(title, style = T.card.copy(fontSize = 17.sp)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                fields.forEachIndexed { i, (label, _) ->
                    Field(values[i].value, { values[i].value = it }, label, Modifier.fillMaxWidth(), ime = ImeAction.Next)
                }
            }
        },
        confirmButton = {
            Text(
                confirm, style = T.body.copy(color = C.Violet300, fontWeight = FontWeight.Bold),
                modifier = Modifier.clip(S.pill).clickable { onConfirm(values.map { it.value.trim() }) }.padding(12.dp),
            )
        },
        dismissButton = {
            Text("Отмена", style = T.body.copy(color = C.N500), modifier = Modifier.clip(S.pill).clickable(onClick = onDismiss).padding(12.dp))
        },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Surface,
        shape = S.card,
        title = { Text(title, style = T.card.copy(fontSize = 17.sp)) },
        text = { Text(text, style = T.small) },
        confirmButton = {
            Text(confirm, style = T.body.copy(color = C.Bad, fontWeight = FontWeight.Bold),
                modifier = Modifier.clip(S.pill).clickable(onClick = onConfirm).padding(12.dp))
        },
        dismissButton = {
            Text("Отмена", style = T.body.copy(color = C.N500), modifier = Modifier.clip(S.pill).clickable(onClick = onDismiss).padding(12.dp))
        },
    )
}

// --- 1f: saved queries ----------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SavedScreen(hub: Hub) {
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Saved?>(null) }
    LaunchedEffect(Unit) { hub.checkSaved() }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        PageHeader("Запросы", "${hub.saved.size} сохранено", onBack = { hub.page = null })
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (hub.saved.isEmpty()) item {
                Text(
                    "Сохраните запрос — и здесь будет видно, сколько у него новых постов.",
                    style = T.small, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(32.dp),
                )
            }
            items(hub.saved, key = { it.id }) { q ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .card()
                        .combinedClickable(onClick = { hub.openSaved(q) }, onLongClick = { removing = q })
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(q.name, style = T.card.copy(fontSize = 14.sp), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val (badge, fg) = when {
                            !q.watch || q.checkedAt == 0L -> "—" to C.N500
                            q.fresh >= 100 -> "99+" to C.Violet300
                            q.fresh >= 10 -> "+${q.fresh}" to C.Violet300
                            q.fresh > 0 -> "+${q.fresh}" to C.Good
                            else -> "0" to C.N500
                        }
                        Box(Modifier.clip(RoundedCornerShape(7.dp)).background(fg.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                            Text(badge, style = T.mono.copy(fontSize = 10.sp, color = fg, fontWeight = FontWeight.SemiBold))
                        }
                        Toggle(q.watch) { hub.watchSaved(q, it) }
                    }
                    Text(q.query.ifBlank { "все посты" }, style = T.mono.copy(fontSize = 11.sp, color = C.N500))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(14.dp).clip(RoundedCornerShape(5.dp)).background(Site.of(q.site).color))
                        Text("${Site.of(q.site).title} · ${if (q.watch) ago(q.checkedAt) else "не слежу"}", style = T.mono.copy(fontSize = 10.5.sp, color = C.N600))
                    }
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .abovePill()
                .padding(start = 16.dp, end = 16.dp, top = 10.dp)
                .height(50.dp)
                .dashed(C.Violet.copy(alpha = 0.45f))
                .clip(RoundedCornerShape(18.dp))
                .clickable { adding = true },
            contentAlignment = Alignment.Center,
        ) { Text("+ Новый запрос", style = T.card.copy(fontSize = 14.sp, color = C.Violet300)) }
    }

    if (adding) SaveQueryDialog(hub, hub.main.tags) { adding = false }
    removing?.let { q ->
        ConfirmDialog("Удалить запрос?", "«${q.name}» исчезнет из списка.", "Удалить", { removing = null }) {
            hub.removeSaved(q); removing = null
        }
    }
}

@Composable
fun SaveQueryDialog(hub: Hub, query: String, onDone: () -> Unit) {
    TextDialog(
        "Новый запрос",
        listOf("Название" to query.split(' ').take(3).joinToString(" ").replace('_', ' '), "Теги" to query),
        "Сохранить",
        onDone,
    ) { (name, q) ->
        hub.addSaved(name.ifBlank { q.ifBlank { "Все посты" } }, q)
        onDone()
    }
}

// --- 1g: collections ----------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CollectionsScreen(hub: Hub) {
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Coll?>(null) }
    val total = hub.colls.sumOf { it.posts.size }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        PageHeader("Коллекции", if (total > 0) "$total ${plural(total, "пост", "поста", "постов")}" else "") {
            RoundButton(Icons.Shield, { hub.page = Page.Blacklist }, size = 34.dp, bg = C.Tint, tint = C.N400)
            Spacer(Modifier.size(6.dp))
            RoundButton(Icons.Gear, { hub.page = Page.Settings }, size = 34.dp, bg = C.Tint, tint = C.N400)
        }
        LazyVerticalGrid(
            GridCells.Fixed(2),
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = pillClearance()),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(2) }) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(S.card)
                        .background(Brush.linearGradient(listOf(C.Accent.copy(alpha = 0.16f), C.Violet.copy(alpha = 0.14f))))
                        .border(1.dp, Color(0x12FFFFFF), S.card)
                        .clickable { hub.page = Page.Favorites }
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(C.Accent.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                        Ico(Icons.HeartFilled, C.Accent, size = 20.dp)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Избранное", style = T.card.copy(fontSize = 15.sp))
                        val where = listOfNotNull(
                            hub.user?.let { "e621" },
                            hub.logins["danbooru"]?.let { "Danbooru" },
                            hub.likes.size.takeIf { it > 0 }?.let { "$it ${plural(it, "лайк", "лайка", "лайков")}" },
                        )
                        Text(where.joinToString(" · ").ifEmpty { "все сайты" }, style = T.mono.copy(fontSize = 11.sp, color = C.N500))
                    }
                    Ico(Icons.Right, C.N600, size = 16.dp)
                }
            }
            items(hub.colls, key = { it.id }) { c ->
                Column(
                    Modifier.animateItem().combinedClickable(onClick = { hub.page = Page.Collection(c.id) }, onLongClick = { removing = c }),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(C.Raised)) {
                        c.posts.firstOrNull()?.thumbUrl?.let {
                            AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                        Box(Modifier.align(Alignment.TopStart).padding(7.dp).size(14.dp).clip(RoundedCornerShape(5.dp)).background(Color(COLL_COLORS[c.color % COLL_COLORS.size])))
                        Box(Modifier.align(Alignment.BottomEnd).padding(7.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xCC0D0B12)).padding(horizontal = 7.dp, vertical = 2.dp)) {
                            Text(c.posts.size.toString(), style = T.mono.copy(fontSize = 9.5.sp, color = C.N300, fontWeight = FontWeight.SemiBold))
                        }
                    }
                    Text(c.name, style = T.body.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("обновлено ${ago(c.updatedAt)}", style = T.mono.copy(fontSize = 10.5.sp, color = C.N600))
                }
            }
            if (hub.user != null) item(span = { GridItemSpan(2) }) {
                LaunchedEffect(hub.user) { if (!hub.setsLoaded) hub.loadSets() }
                if (hub.sets.isNotEmpty()) Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Kicker("СЕТЫ E621")
                    for (s in hub.sets) Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(C.Tint)
                            .clickable { hub.search("set:${s.short}", on = "e621") }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(s.name, style = T.body.copy(fontSize = 14.sp), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${s.posts.size}", style = T.mono.copy(fontSize = 11.sp, color = C.N500))
                        Ico(Icons.Right, C.N600, size = 14.dp)
                    }
                }
            }
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .dashed(C.Violet.copy(alpha = 0.4f), 14f)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { adding = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Ico(Icons.Plus, C.Violet300, size = 22.dp)
                        Text("Новая", style = T.body.copy(fontSize = 12.sp, color = C.Violet300, fontWeight = FontWeight.SemiBold))
                    }
                }
            }
        }
    }

    if (adding) NewCollectionDialog(hub, emptyList()) { adding = false }
    removing?.let { c ->
        ConfirmDialog("Удалить коллекцию?", "«${c.name}» и её ${c.posts.size} ${plural(c.posts.size, "пост", "поста", "постов")} пропадут из приложения. На e621 ничего не изменится.", "Удалить", { removing = null }) {
            hub.removeColl(c); removing = null
        }
    }
}

/** The sheet the viewer opens: tick the collections a post belongs to. */
@Composable
fun CollectionPicker(hub: Hub, p: Post) = CollectionPicker(hub, listOf(p))

/** Tick the collections (and e621 sets) the posts go to; a tick on all of them takes them out. */
@Composable
fun CollectionPicker(hub: Hub, posts: List<Post>) {
    var adding by remember { mutableStateOf(false) }
    val one = posts.singleOrNull()
    val e621 = posts.filter { it.site == "e621" }
    LaunchedEffect(Unit) { if (hub.user != null && e621.isNotEmpty()) hub.loadSets() }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("В коллекцию", style = T.card.copy(fontSize = 18.sp))
        Text(
            if (one != null) "Пост #${one.id} сохраняется в приложении целиком — коллекция откроется и без поиска."
            else "${posts.size} ${plural(posts.size, "пост", "поста", "постов")} сохранятся в приложении целиком.",
            style = T.small.copy(fontSize = 12.5.sp),
        )
        Spacer(Modifier.height(4.dp))
        for (c in hub.colls) {
            val inside = posts.all { p -> c.posts.any { it.key == p.key } }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (inside) C.Violet.copy(alpha = 0.14f) else C.Tint)
                    .clickable { if (one != null) hub.toggleInColl(c, one) else hub.putInColl(c, posts, !inside) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(14.dp).clip(RoundedCornerShape(5.dp)).background(Color(COLL_COLORS[c.color % COLL_COLORS.size])))
                Text(c.name, style = T.body.copy(fontSize = 14.sp), modifier = Modifier.weight(1f))
                Text("${c.posts.size}", style = T.mono.copy(fontSize = 11.sp, color = C.N500))
                if (inside) Ico(Icons.Check, C.Violet300, size = 18.dp)
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(46.dp)
                .dashed(C.Violet.copy(alpha = 0.4f), 12f)
                .clip(RoundedCornerShape(12.dp))
                .clickable { adding = true },
            contentAlignment = Alignment.Center,
        ) { Text("+ Новая коллекция", style = T.body.copy(fontSize = 13.5.sp, color = C.Violet300, fontWeight = FontWeight.SemiBold)) }

        // e621's sets live on the site, under the account.
        if (hub.user != null && e621.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Kicker("СЕТЫ E621")
            if (hub.setsLoaded && hub.sets.isEmpty()) Text("Сетов нет — их создают на сайте.", style = T.small.copy(color = C.N500))
            for (s in hub.sets) {
                val inside = e621.all { it.id in s.posts }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (inside) C.Sky.copy(alpha = 0.14f) else C.Tint)
                        .clickable { hub.toggleInSet(s, e621) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(s.name, style = T.body.copy(fontSize = 14.sp), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${s.posts.size}", style = T.mono.copy(fontSize = 11.sp, color = C.N500))
                    if (inside) Ico(Icons.Check, C.Sky, size = 18.dp)
                }
            }
        }
    }
    if (adding) NewCollectionDialog(hub, posts) { adding = false }
}

@Composable
private fun NewCollectionDialog(hub: Hub, posts: List<Post>, onDone: () -> Unit) {
    TextDialog("Новая коллекция", listOf("Название" to ""), "Создать", onDone) { (name) ->
        if (name.isNotBlank()) hub.addColl(name, posts.firstOrNull(), posts.drop(1))
        onDone()
    }
}

// --- 1i: the blacklist ------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BlacklistScreen(hub: Hub) {
    val ctx = LocalContext.current
    var input by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .imePadding(),
    ) {
        PageHeader("Чёрный список", onBack = { hub.page = null })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Посты с этими тегами не появятся ни в одной ленте. Правило из нескольких тегов срабатывает, когда есть все; «-тег» — когда тега нет; «*» — любая часть.",
                style = T.small.copy(fontSize = 12.5.sp),
            )
            Field(
                input, { input = it }, "+ добавить тег или маску (gore*)", Modifier.fillMaxWidth(),
                ime = ImeAction.Done,
                onIme = { hub.addBlock(input); input = "" },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Kicker("ПРАВИЛА · ${hub.blacklist.size}", Modifier.weight(1f))
                if (hub.blacklist.isNotEmpty()) Text(
                    "Экспорт", style = T.small.copy(fontSize = 12.sp, color = C.Violet300),
                    modifier = Modifier.clip(S.pill).clickable {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("blacklist", hub.blacklist.joinToString("\n")))
                        hub.say("Список скопирован")
                    }.padding(4.dp),
                )
            }
            if (hub.blacklist.isEmpty()) Text("Пусто. Добавьте теги или загрузите список из аккаунта e621.", style = T.small.copy(color = C.N600, fontSize = 12.5.sp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (rule in hub.blacklist.toList()) {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(C.Bad.copy(alpha = 0.13f))
                            .border(1.dp, C.Bad.copy(alpha = 0.2f), RoundedCornerShape(9.dp))
                            .clickable { hub.removeBlock(rule) }
                            .padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(rule, style = T.small.copy(fontSize = 12.5.sp, color = C.Bad))
                        Ico(Icons.Close, C.Bad, size = 11.dp)
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            OptionCard("Скрывать вместо размытия", "посты исчезают из сетки полностью", hub.blockHide) { hub.chooseBlockHide(it) }
            OptionCard("Учитывать в поиске", "добавлять минус-теги в запрос", hub.blockInSearch) { hub.chooseBlockInSearch(it) }
            OptionCard(
                "Синхронизировать с аккаунтом",
                if (hub.user == null) "e621 blacklist · нужен вход" else "e621 blacklist · загружать при запуске",
                hub.blockSync,
            ) { hub.chooseBlockSync(it) }
            Spacer(Modifier.height(pillClearance(16.dp)))
        }
    }
}

@Composable
private fun OptionCard(label: String, hint: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(C.Surface)
            .clickable { onChange(!on) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = T.body.copy(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold))
            Text(hint, style = T.mono.copy(fontSize = 10.5.sp, color = C.N500))
        }
        Toggle(on) { onChange(it) }
    }
}

/**
 * Hearts from every site. e621 and Danbooru keep favourites in the account;
 * Gelbooru and Rule34 take none through their API, so their hearts are likes
 * kept on the phone.
 */
@Composable
fun FavoritesScreen(hub: Hub) {
    val site = Site.of(hub.favSite)
    val feed = hub.favFeed(site.code)
    val empty = when {
        feed == null -> "Войдите в ${site.title} в настройках — и здесь будет избранное из аккаунта"
        !site.favs -> "Пока пусто. Сердечко у поста ${site.title} сохраняет его сюда"
        else -> "Избранное пустое"
    }
    val note = when {
        feed == null -> "нужен вход"
        !site.favs -> "${feed.posts.size} ${plural(feed.posts.size, "лайк", "лайка", "лайков")} · на телефоне"
        else -> null
    }
    androidx.compose.runtime.key(site.code) {
        FeedScreen(hub, feed ?: hub.noFeed(), empty = if (feed == null) "" else empty, title = "Избранное", note = note, onBack = { hub.page = null }) {
            FavSites(hub)
            // Not signed in to this site: say so, and lead to the form.
            if (feed == null) Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp).card().padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    Modifier.size(56.dp).clip(RoundedCornerShape(19.dp)).background(C.Accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) { Ico(Icons.HeartFilled, C.Accent, size = 26.dp) }
                Text(empty, style = T.small, textAlign = TextAlign.Center)
                PrimaryButton("Войти", { hub.tab = Tab.More; hub.page = Page.Settings }, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun FavSites(hub: Hub) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (s in Site.entries) {
            val on = hub.favSite == s.code
            val bg by androidx.compose.animation.animateColorAsState(if (on) s.color.copy(alpha = 0.18f) else C.Tint, label = "favbg")
            val edge by androidx.compose.animation.animateColorAsState(if (on) s.color.copy(alpha = 0.75f) else Color.Transparent, label = "favedge")
            Row(
                Modifier
                    .clip(S.pill)
                    .background(bg)
                    .border(1.dp, edge, S.pill)
                    .springClick(0.92f) { hub.chooseFavSite(s.code) }
                    .padding(start = 9.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                SiteMark(s, size = 12.dp)
                Text(s.title, style = T.small.copy(fontSize = 12.5.sp, color = if (on) C.Text else C.N400, fontWeight = FontWeight.SemiBold))
            }
        }
    }
    val site = Site.of(hub.favSite)
    if (!site.favs) Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "${site.title} не даёт ставить избранное через API — сердечки хранятся в приложении и попадают в резервную копию.",
            style = T.small.copy(fontSize = 11.5.sp, color = C.N500),
            modifier = Modifier.weight(1f),
        )
        // Gelbooru's own favourites can still be read: they are a search.
        val id = hub.logins["gelbooru"]?.first
        if (site == Site.Gelbooru && id != null) Box(
            Modifier
                .clip(S.pill)
                .background(C.Violet.copy(alpha = 0.18f))
                .springClick(0.92f) { hub.page = null; hub.search("fav:$id", on = "gelbooru") }
                .padding(horizontal = 11.dp, vertical = 7.dp),
        ) { Text("На сайте", style = T.small.copy(fontSize = 12.sp, color = C.Violet300, fontWeight = FontWeight.SemiBold)) }
    }
}
