package app.booruhub

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.border
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    hub: Hub, feed: Feed, empty: String, title: String? = null, note: String? = null, onBack: (() -> Unit)? = null,
    top: (@Composable () -> Unit)? = null,
) {
    LaunchedEffect(feed, feed.tags) {
        if (feed.posts.isEmpty() && !feed.loading && feed.error == null) hub.load(feed, reset = true)
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            Header(hub, feed, title, note, onBack)
            top?.invoke()
            if (title == null) SortTabs(hub, feed)
            if (title == null && feed.order.startsWith(BEST) && feed.tags.isBlank() && hasBest(feed.site)) BestScales(hub, feed)
            PullToRefreshBox(
                isRefreshing = feed.loading && feed.page == 1 && feed.posts.isNotEmpty(),
                onRefresh = { hub.load(feed, reset = true) },
                modifier = Modifier.fillMaxSize(),
            ) {
                // The design's dense masonry: edge to edge, hairline gaps, each
                // tile in its file's proportions.
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(hub.columns),
                    state = feed.grid,
                    contentPadding = PaddingValues(start = 3.dp, end = 3.dp, top = 2.dp, bottom = pillClearance()),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalItemSpacing = 3.dp,
                    modifier = Modifier.fillMaxSize().pickByDrag(hub, feed),
                ) {
                    // Grey tiles breathing while the first page comes.
                    if (feed.posts.isEmpty() && feed.loading) items(18) { i -> Skeleton(i, hub.squareTiles) }
                    itemsIndexed(feed.posts, key = { _, p -> p.key }) { i, p ->
                        // New tiles fade in; the rest glide to their new places.
                        Box(Modifier.animateItem()) {
                            Thumb(
                                p, hub.squareTiles, blurred = !feed.local && hub.isBlocked(p), showSite = feed.site == MIX || feed.local,
                                picked = if (hub.picking) p.key in hub.picked else null, faved = hub.faved(p),
                            ) { hub.open(feed, i) }
                        }
                        if (i >= feed.posts.size - 15) LaunchedEffect(feed.posts.size) { hub.loadMore(feed) }
                    }
                    item(span = StaggeredGridItemSpan.FullLine) { Footer(hub, feed, empty) }
                }
            }
        }
    }
}

/**
 * A long press picks the tile under the finger; dragging on without lifting
 * picks every tile from that one to the one under the finger now.
 */
private fun Modifier.pickByDrag(hub: Hub, feed: Feed): Modifier = pointerInput(feed) {
    fun at(o: androidx.compose.ui.geometry.Offset): Int? = feed.grid.layoutInfo.visibleItemsInfo.firstOrNull { item ->
        o.x >= item.offset.x && o.x < item.offset.x + item.size.width && o.y >= item.offset.y && o.y < item.offset.y + item.size.height
    }?.index?.takeIf { it < feed.posts.size }
    // Seen before the grid and its tiles (the Initial pass): once the press
    // has lasted, every move and the lift are taken here, so the grid does
    // not scroll and the tile does not also take it as a tap.
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                val c = e.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                if (!c.pressed || (c.position - down.position).getDistance() > viewConfiguration.touchSlop || e.changes.size > 1) return@withTimeoutOrNull false
            }
            @Suppress("UNREACHABLE_CODE") false
        } == null
        if (!held) return@awaitEachGesture
        val start = at(down.position) ?: return@awaitEachGesture
        val before = hub.picked.keys.toSet()
        val adding = feed.posts[start].key !in hub.picked
        hub.togglePick(feed.posts[start])
        while (true) {
            val e = awaitPointerEvent(PointerEventPass.Initial)
            val c = e.changes.firstOrNull { it.id == down.id } ?: break
            e.changes.forEach { it.consume() }
            if (!c.pressed) break
            val i = at(c.position) ?: continue
            val range = minOf(start, i)..maxOf(start, i)
            for ((j, p) in feed.posts.withIndex()) {
                val want = if (j in range) adding else p.key in before
                if (want && p.key !in hub.picked) hub.picked[p.key] = p
                if (!want && p.key in hub.picked) hub.picked.remove(p.key)
            }
        }
    }
}

/** The header while choosing: how many, all of them, done. */
@Composable
private fun PickHeader(hub: Hub, feed: Feed) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(start = 10.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RoundButton(Icons.Close, { hub.clearPicks() }, size = 34.dp, bg = C.Tint, tint = C.N300)
        val n = hub.picked.size
        Text("Выбрано $n", style = T.title.copy(fontSize = 19.sp), modifier = Modifier.weight(1f))
        val all = feed.posts.isNotEmpty() && feed.posts.all { it.key in hub.picked }
        Box(
            Modifier.clip(S.pill).background(C.Violet.copy(alpha = 0.18f)).clickable { hub.pickAll(feed) }.padding(horizontal = 12.dp, vertical = 7.dp),
        ) { Text(if (all) "Снять все" else "Все ${feed.posts.size}", style = T.small.copy(color = C.Violet300, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)) }
    }
}

/** The design's one thin header row: name, the query in mono, two round buttons. */
@Composable
private fun Header(hub: Hub, feed: Feed, title: String?, note: String?, onBack: (() -> Unit)?) {
    if (hub.picking) { PickHeader(hub, feed); return }
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(start = if (onBack != null) 8.dp else 18.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (onBack != null) BackButton(onBack)
        var sources by remember { mutableStateOf(false) }
        if (sources) SourcesSheet(hub) { sources = false }
        if (title == null) SourcePill(hub) { sources = true } else Text(title, style = T.title.copy(fontSize = 20.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        var tabsOpen by remember { mutableStateOf(false) }
        if (tabsOpen) TabsSheet(hub) { tabsOpen = false }
        Row(
            Modifier
                .weight(1f)
                .clip(S.pill)
                // The main feed's query: a sideways swipe moves between tabs.
                .then(if (title == null) Modifier.pointerInput(hub.feedTabs.size) {
                    var total = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = {
                            val at = hub.feedTabs.indexOfFirst { it === hub.main }
                            if (total < -70f) hub.switchTab(at + 1) else if (total > 70f) hub.switchTab(at - 1)
                        },
                    ) { change, dx -> total += dx; change.consume() }
                } else Modifier)
                .clickable { hub.tab = Tab.Search }
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                note ?: feed.tags.ifBlank { if (title == null) "все посты" else "" },
                style = T.mono.copy(fontSize = 11.5.sp, color = if (feed.tags.isBlank()) C.N600 else C.N400),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            if (title == null && feed.tags.isNotBlank()) {
                Box(
                    Modifier.padding(start = 4.dp).size(22.dp).clip(S.pill).clickable { hub.search("") },
                    contentAlignment = Alignment.Center,
                ) { Ico(Icons.Close, C.N500, size = 12.dp) }
            }
        }
        if (title == null) {
            // The design's variant A: how many tabs, a tap lists them.
            val n = hub.feedTabs.size
            Box(
                Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .border(1.5.dp, if (n > 1) C.Violet300 else C.N600, RoundedCornerShape(9.dp))
                    .clickable { tabsOpen = true },
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    n,
                    transitionSpec = {
                        val up = targetState > initialState
                        (slideInVertically { if (up) it else -it } + fadeIn()) togetherWith (slideOutVertically { if (up) -it else it } + fadeOut())
                    },
                    label = "tabs",
                ) { k -> Text("$k", style = T.mono.copy(fontSize = 12.sp, color = if (k > 1) C.Violet300 else C.N500, fontWeight = FontWeight.SemiBold)) }
            }
        }
        if (!feed.local && title != null) {
            RoundButton(Icons.Refresh, { hub.load(feed, reset = true) }, size = 34.dp, bg = C.Violet.copy(alpha = 0.18f), tint = C.Violet300)
        }
        if (!feed.local && title == null) {
            RoundButton(Icons.Search, { hub.page = null; hub.tab = Tab.Search }, size = 34.dp, bg = C.Tint, tint = C.N400)
            RoundButton(Icons.Refresh, { hub.load(feed, reset = true) }, size = 34.dp, bg = C.Violet.copy(alpha = 0.18f), tint = C.Violet300)
        }
    }
}

/** The design's tabs: text, a violet underline as wide as the chosen label. */
@Composable
private fun SortTabs(hub: Hub, feed: Feed) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 8.dp, end = 8.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // The site's own best-of page, where it has one and nothing is typed.
        val tabs = if (hasBest(feed.site) && feed.tags.isBlank()) ORDERS + ((BEST + "day") to "Лучшее") else ORDERS
        for ((key, label) in tabs) {
            val on = feed.order == key || (key.startsWith(BEST) && feed.order.startsWith(BEST))
            val color by animateColorAsState(if (on) C.Violet300 else C.N500, tween(200), label = "tab")
            val line by animateColorAsState(if (on) C.Violet else Color.Transparent, tween(200), label = "line")
            val reach by animateFloatAsState(if (on) 1f else 0.2f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "reach")
            Column(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { if (!(key.startsWith(BEST) && feed.order.startsWith(BEST))) hub.setOrder(feed, key) }
                    .padding(start = 10.dp, end = 10.dp, top = 4.dp)
                    .width(IntrinsicSize.Max),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(label, style = T.body.copy(fontSize = 13.sp, color = color, fontWeight = FontWeight.SemiBold))
                Box(Modifier.fillMaxWidth().height(2.dp).graphicsLayer { scaleX = reach }.clip(S.pill).background(line))
            }
        }
    }
}

/** Width over height of the file, held to a range a column can show. */
private fun Post.tileRatio(): Float = ratio.takeIf { it > 0f }?.coerceIn(0.5f, 1.8f) ?: 0.78f

@Composable
fun Thumb(
    p: Post, square: Boolean = false, blurred: Boolean = false, showSite: Boolean = false, picked: Boolean? = null,
    faved: Boolean = p.isFav, onClick: () -> Unit,
) {
    val ctx = LocalContext.current
    val src = remember { MutableInteractionSource() }
    // Picked tiles sit a little back, like pressed in.
    val sink by animateFloatAsState(if (picked == true) 0.93f else 1f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "sink")
    Box(
        Modifier
            .graphicsLayer { scaleX = sink; scaleY = sink }
            .pressable(src, 0.95f)
            .aspectRatio(if (square) 1f else p.tileRatio())
            .clip(S.tile)
            .background(C.Surface)
            .clickable(interactionSource = src, indication = LocalIndication.current, onClick = onClick),
    ) {
        val url = p.thumbUrl
        if (url != null) {
            AsyncImage(
                model = ImageRequest.Builder(ctx).data(url).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // A blacklisted post, when shown at all, is blurred past recognition.
                modifier = Modifier.fillMaxSize().then(if (blurred) Modifier.blur(28.dp) else Modifier),
            )
        } else {
            Text(
                "скрыто", style = T.tiny, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Row(
            Modifier.align(Alignment.TopStart).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Badge(p.rating.uppercase(), ratingColor(p.rating))
            if (p.isVideo) Badge(null, C.Text, icon = Icons.Play)
            if (p.isGif) Badge("GIF", C.Amber)
        }
        // The design's 1b: in a mixed feed each tile says where it is from.
        Row(
            Modifier.align(Alignment.TopEnd).padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            // The heart pops in with a bounce when the post is hearted.
            AnimatedVisibility(
                faved,
                enter = scaleIn(spring(dampingRatio = 0.35f, stiffness = 500f)) + fadeIn(),
                exit = scaleOut() + fadeOut(),
            ) { Ico(Icons.HeartFilled, C.Accent, size = 15.dp) }
            if (showSite) Badge(p.source.short, p.source.color)
        }
        // Choosing: a ring on every tile, filled on the ones picked.
        AnimatedVisibility(picked == true, Modifier.matchParentSize(), enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(C.Accent.copy(alpha = 0.22f)).border(2.dp, C.Accent, S.tile))
        }
        AnimatedVisibility(
            picked != null, Modifier.align(Alignment.BottomEnd),
            enter = scaleIn(spring(dampingRatio = 0.5f)) + fadeIn(), exit = scaleOut() + fadeOut(),
        ) {
            val on = picked == true
            Box(
                Modifier
                    .padding(6.dp)
                    .size(20.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (on) C.Accent else Color(0x66000000))
                    .border(1.5.dp, if (on) C.Accent else Color.White, androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedVisibility(on, enter = scaleIn(spring(dampingRatio = 0.4f, stiffness = 700f)), exit = scaleOut()) {
                    Ico(Icons.Check, Color.White, size = 13.dp)
                }
            }
        }
    }
}

/** A tile's place while the first page loads: tiles breathe in a wave across the grid. */
@Composable
private fun Skeleton(i: Int, square: Boolean) {
    val shapes = floatArrayOf(0.75f, 1.2f, 0.9f, 0.66f, 1f, 0.8f, 1.4f)
    val t = rememberInfiniteTransition(label = "skeleton")
    val a by t.animateFloat(
        0.35f, 0.85f,
        infiniteRepeatable(tween(850), RepeatMode.Reverse, initialStartOffset = StartOffset(i * 90)),
        label = "breath",
    )
    Box(
        Modifier
            .aspectRatio(if (square) 1f else shapes[i % shapes.size])
            .graphicsLayer { alpha = a }
            .clip(S.tile)
            .background(C.Raised),
    )
}

@Composable
private fun Footer(hub: Hub, feed: Feed, empty: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
        when {
            feed.loading -> if (feed.posts.isNotEmpty()) CircularProgressIndicator(color = C.Accent, strokeWidth = 2.5.dp, modifier = Modifier.size(30.dp))
            feed.error != null -> Column(
                Modifier.padding(horizontal = 16.dp).card().padding(18.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Не загрузилось", style = T.card)
                Text(feed.error.orEmpty(), style = T.small, textAlign = TextAlign.Center)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton("Ещё раз", { hub.load(feed, reset = feed.posts.isEmpty()) }, icon = Icons.Refresh)
                    if (feed.site == "e621" || feed.site == MIX) GhostButton("Cloudflare", { hub.openCloudflare() }, color = C.Violet, icon = Icons.Shield)
                }
            }
            feed.end && feed.posts.isEmpty() -> Text(empty, style = T.small, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
            feed.end -> Text("Это всё ✿", style = T.tiny)
        }
    }
}

/** The search tabs: each one's source and query; a tap shows it, ✕ closes it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabsSheet(hub: Hub, onClose: () -> Unit) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = C.Surface,
        shape = S.sheet,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 20.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)) {
                Text("Вкладки", style = T.card.copy(fontSize = 18.sp), modifier = Modifier.weight(1f))
                Text("смахните запрос в шапке, чтобы листать", style = T.mono.copy(fontSize = 10.5.sp, color = C.N600))
            }
            hub.feedTabs.forEachIndexed { i, f ->
                val on = f === hub.main
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (on) C.Violet.copy(alpha = 0.16f) else C.Tint)
                        .clickable { hub.switchTab(i); onClose() }
                        .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (f.site == MIX) MixMarks(f.mix.map { Site.of(it) }) else SiteMark(Site.of(f.site))
                    Column(Modifier.weight(1f)) {
                        Text(
                            f.tags.ifBlank { "все посты" },
                            style = T.mono.copy(fontSize = 12.5.sp, color = if (f.tags.isBlank()) C.N500 else C.Text),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        val sort = ORDERS.firstOrNull { it.first == f.order }?.second ?: if (f.order.startsWith(BEST)) "Лучшее" else ""
                        Text(
                            listOf(if (f.site == MIX) f.mix.joinToString(" + ") { Site.of(it).short } else Site.of(f.site).title, sort, if (f.posts.isNotEmpty()) "${f.posts.size} загружено" else "").filter { it.isNotEmpty() }.joinToString(" · "),
                            style = T.mono.copy(fontSize = 10.5.sp, color = C.N500),
                        )
                    }
                    Box(Modifier.size(36.dp).clip(S.pill).clickable { hub.closeTab(i) }, contentAlignment = Alignment.Center) {
                        Ico(Icons.Close, C.N500, size = 14.dp)
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, C.Accent.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .clickable { onClose(); hub.newTab() },
                contentAlignment = Alignment.Center,
            ) { Text("+ Новая вкладка", style = T.body.copy(fontSize = 13.5.sp, color = C.Accent, fontWeight = FontWeight.SemiBold)) }
        }
    }
}

@Composable
fun SignInFirst(hub: Hub) {
    Box(
        Modifier.fillMaxSize().abovePill(gap = 26.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(24.dp).card().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(22.dp)).background(C.Accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) { Ico(Icons.HeartFilled, C.Accent, size = 30.dp) }
            Text("Избранное живёт в аккаунте", style = T.card)
            Text(
                "Войдите в e621 с API-ключом — и сердечки будут сохраняться на сайте.",
                style = T.small, textAlign = TextAlign.Center,
            )
            PrimaryButton("Войти", { hub.tab = Tab.More; hub.page = Page.Settings }, Modifier.fillMaxWidth())
        }
    }
}

