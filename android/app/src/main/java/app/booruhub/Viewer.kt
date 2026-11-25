package app.booruhub

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.widthIn

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.Image
import coil3.compose.rememberAsyncImagePainter
import kotlinx.coroutines.awaitCancellation
import coil3.request.CachePolicy
import coil3.SingletonImageLoader
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.Intent
import android.net.Uri
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.mutableLongStateOf
import kotlinx.coroutines.delay
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(hub: Hub, feed: Feed, start: Int) {
    val pager = rememberPagerState(initialPage = start.coerceIn(0, (feed.posts.size - 1).coerceAtLeast(0))) { feed.posts.size }
    var comments by remember { mutableStateOf(hub.reopenComments.also { hub.reopenComments = false }) }
    var collect by remember { mutableStateOf(false) }
    var tagFor by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showNotes by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf(0) }
    // The page whose full picture has arrived; only then is the next one fetched.
    var loadedPage by remember { mutableStateOf(-1) }
    val ctx = LocalContext.current
    val density = LocalDensity.current

    // The bars and the panel. Over a playing video they fade after a few
    // quiet seconds; any touch brings them back, and the touch that does is
    // not also taken as a pause.
    var chrome by remember { mutableStateOf(true) }
    var touches by remember { mutableIntStateOf(0) }
    var revealing by remember { mutableStateOf(false) }
    var touching by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    val current = feed.posts.getOrNull(pager.currentPage)
    val onVideo = current != null && current.isVideo && current.videoUrl != null
    // The notes of the post on screen, fetched when it has any.
    var notes by remember { mutableStateOf<Pair<String, List<Note>>?>(null) }
    LaunchedEffect(current?.key) {
        val p = current?.takeIf { it.hasNotes && !it.isVideo } ?: return@LaunchedEffect
        if (notes?.first == p.key) return@LaunchedEffect
        notes = runCatching { p.key to E621.notes(p) }.getOrNull()
    }
    LaunchedEffect(pager.currentPage) { chrome = true }
    LaunchedEffect(touches, touching, chrome, playing, onVideo, comments, collect) {
        if (touching || !chrome || !onVideo || !playing || comments || collect) return@LaunchedEffect
        delay(3000)
        chrome = false
    }

    BackHandler { hub.closeViewer() }
    LaunchedEffect(pager.currentPage) {
        hub.viewerPage = pager.currentPage
        if (pager.currentPage >= feed.posts.size - 6) hub.loadMore(feed)
        feed.posts.getOrNull(pager.currentPage)?.let { hub.categorize(it) }
    }
    // Prefetch: the next post's picture goes to the disk cache while this one
    // is looked at, so swiping on shows it at once. Swiping away cancels it.
    LaunchedEffect(pager.currentPage, loadedPage) {
        if (loadedPage != pager.currentPage) return@LaunchedEffect
        val next = feed.posts.getOrNull(pager.currentPage + 1) ?: return@LaunchedEffect
        val url = (if (next.isVideo) next.thumbUrl else next.viewUrl(hub.original)) ?: return@LaunchedEffect
        val job = SingletonImageLoader.get(ctx).enqueue(
            ImageRequest.Builder(ctx).data(url).memoryCachePolicy(CachePolicy.DISABLED).build(),
        )
        try {
            awaitCancellation()
        } finally {
            job.dispose()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0810))
            // Sees every touch first and takes none of them.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    revealing = !chrome
                    chrome = true
                    touching = true
                    touches++
                    // Nothing hides while a finger stays down, however long it drags.
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                    } while (event.changes.any { it.pressed })
                    touching = false
                    touches++
                }
            },
    ) {
        HorizontalPager(pager, beyondViewportPageCount = 1, key = { feed.posts.getOrNull(it)?.key ?: it }) { page ->
            val p = feed.posts.getOrNull(page) ?: return@HorizontalPager
            if (p.isVideo && p.videoUrl != null) {
                val above = with(density) { panel.toDp() }
                if (page == pager.currentPage) Video(
                    p.videoUrl, above, chrome, hub.seekStep,
                    takeReveal = { revealing.also { revealing = false } },
                    onPlaying = { playing = it },
                )
                else Still(p.thumbUrl, null, p.ratio, panel)
            } else {
                // Only the page on screen loads the full picture; neighbours keep
                // the grid's thumbnail, so one swiped past stops downloading.
                val url = if (page == pager.currentPage) p.viewUrl(hub.original) else p.thumbUrl
                val shown = notes?.takeIf { showNotes && it.first == p.key && page == pager.currentPage }?.second.orEmpty()
                Still(url, p.thumbUrl, p.ratio, panel, shown, p.width, p.height) { if (page == pager.currentPage) loadedPage = page }
            }
        }

        val index = pager.currentPage
        val p = feed.posts.getOrNull(index)

        // Top: back, where we are, what kind of file — as in the design.
        AnimatedVisibility(p != null && chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            if (p == null) return@AnimatedVisibility
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xD90D0B12), Color.Transparent)))
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = 14.dp, end = 16.dp, top = 6.dp, bottom = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                RoundButton(Icons.Back, { hub.closeViewer() }, size = 36.dp, bg = Color(0x8C0D0B12))
                Column(Modifier.weight(1f)) {
                    Text("пост ${index + 1} / ${feed.posts.size}${if (feed.end) "" else "+"} · ${p.source.title}", style = T.body.copy(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold))
                    Text("#${p.id} · ${p.width}×${p.height}", style = T.mono.copy(fontSize = 10.5.sp, color = C.N500))
                }
                if (p.hasNotes && !p.isVideo) Box(
                    Modifier
                        .clip(S.pill)
                        .background(if (showNotes) C.Accent.copy(alpha = 0.3f) else Color(0x8C0D0B12))
                        .clickable { showNotes = !showNotes }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    val n = notes?.takeIf { it.first == p.key }?.second?.size
                    Text("ноты" + (n?.let { " $it" } ?: ""), style = T.mono.copy(fontSize = 10.sp, color = if (showNotes) C.Accent else C.N400, fontWeight = FontWeight.SemiBold))
                }
                Box(
                    Modifier.clip(S.pill).background(Color(0x8C0D0B12)).padding(horizontal = 10.dp, vertical = 5.dp),
                ) { Text(p.ext.uppercase(), style = T.mono.copy(fontSize = 10.sp, color = C.Amber, fontWeight = FontWeight.SemiBold)) }
            }
        }

        // Bottom: the design's info panel, in the thumb's reach.
        AnimatedVisibility(
            p != null && chrome,
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut() + slideOutVertically { it / 3 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            if (p == null) return@AnimatedVisibility
            InfoPanel(
                hub, feed, index, p,
                onComments = { comments = true },
                onCollect = { collect = true },
                onTag = { t, cat -> tagFor = t to cat },
                expanded = hub.panelOpen,
                onExpanded = { hub.choosePanelOpen(it) },
                modifier = Modifier.onSizeChanged { panel = it.height },
            )
        }

        if (comments && p != null) {
            ModalBottomSheet(
                onDismissRequest = { comments = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
                containerColor = C.Surface,
                shape = S.sheet,
            ) { CommentsSheet(p) { hub.openPost(it, fromComments = true) } }
        }
        tagFor?.let { (t, cat) ->
            if (p != null) ModalBottomSheet(
                onDismissRequest = { tagFor = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = C.Surface,
                shape = S.sheet,
            ) { TagSheet(hub, p.site, t, cat) { tagFor = null } }
        }
        if (collect && p != null) {
            ModalBottomSheet(
                onDismissRequest = { collect = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = C.Surface,
                shape = S.sheet,
            ) { CollectionPicker(hub, p) }
        }
    }
}

/**
 * The design's panel: the artist with "Все работы", the post's numbers in mono,
 * the description and every tag by category, scrolled within the panel, and
 * the actions always in reach under them. A pull down at the top of the
 * scroll folds it to the actions; a swipe up unfolds it.
 */
@kotlin.OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun InfoPanel(
    hub: Hub,
    feed: Feed,
    index: Int,
    p: Post,
    onComments: () -> Unit,
    onCollect: () -> Unit,
    onTag: (String, String) -> Unit,
    expanded: Boolean,
    onExpanded: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val all = p.allTags
    // A post that came with its tags grouped (old answers, collections) has
    // its categories already; a flat one waits for the viewer's fetch, and
    // until then shows its tags as one plain group.
    val flat = p.tags[E621.FLAT] != null
    val artists = (if (flat) all.filter { hub.cat(p, it) == 1 } else p.tags["artist"].orEmpty()).filter { it !in NOT_ARTISTS }
    val known = !flat || all.all { hub.cat(p, it) != null }
    val groups: List<Pair<String, List<String>>> = when {
        !flat -> E621.TAG_ORDER.mapNotNull { c -> p.tags[c]?.takeIf { it.isNotEmpty() }?.let { c to it } }
        !known -> listOf("" to all)
        else -> {
            val byCat = all.groupBy { tagCategory(hub.cat(p, it) ?: 0) }
            E621.TAG_ORDER.mapNotNull { c -> byCat[c]?.let { c to it } }
        }
    }
    val maxScroll = (LocalConfiguration.current.screenHeightDp * 0.42f).dp
    val fold by rememberUpdatedState(onExpanded)
    val pullToFold = remember {
        object : NestedScrollConnection {
            var pulled = 0f
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                if (available.y > 0f) {
                    pulled += available.y
                    if (pulled > 140f) { pulled = 0f; fold(false) }
                    return Offset(0f, available.y)
                }
                if (consumed.y != 0f) pulled = 0f
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                pulled = 0f
                return Velocity.Zero
            }
        }
    }
    val swipe = Modifier.pointerInput(expanded) {
        var total = 0f
        detectVerticalDragGestures(
            onDragStart = { total = 0f },
            onDragEnd = {
                if (total > 40f && expanded) onExpanded(false)
                if (total < -40f && !expanded) onExpanded(true)
            },
        ) { change, dy -> total += dy; change.consume() }
    }

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
            .background(Color(0xF7140F1D))
            .border(1.dp, C.Hair, RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            // Folded, the whole panel takes the swipe up; unfolded, its content scrolls.
            .then(if (expanded) Modifier else swipe)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(bottom = 14.dp),
    ) {
        // The handle: a wide target; a tap toggles, a swipe folds or unfolds.
        Box(
            Modifier
                .fillMaxWidth()
                .height(26.dp)
                .then(swipe)
                .clickable(remember { MutableInteractionSource() }, indication = null) { onExpanded(!expanded) },
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.padding(top = 4.dp).width(38.dp).height(4.dp).clip(S.pill).background(C.N700)) }

        AnimatedVisibility(
            expanded,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        ) {
            Column(
                Modifier
                    .heightIn(max = maxScroll)
                    .nestedScroll(pullToFold)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 18.dp, end = 18.dp, top = 2.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (artists.isNotEmpty()) {
                    val artist = artists.first()
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                        Box(
                            Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(C.Glow),
                            contentAlignment = Alignment.Center,
                        ) { Text(artist.take(1).uppercase(), style = T.card.copy(color = Color.White)) }
                        Column(Modifier.weight(1f)) {
                            Text(artist.replace('_', ' '), style = T.card.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (artists.size > 1) "автор · и ещё ${artists.size - 1}" else "автор", style = T.mono.copy(fontSize = 10.5.sp, color = C.N500))
                        }
                        Box(
                            Modifier
                                .clip(S.pill)
                                .border(1.dp, C.Violet.copy(alpha = 0.5f), S.pill)
                                .clickable { hub.search(artist, on = p.site, keepMix = true) }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) { Text("Все работы", style = T.small.copy(color = C.Violet300, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)) }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Stat(p.rating.uppercase(), ratingColor(p.rating), ratingColor(p.rating).copy(alpha = 0.14f))
                    Stat("↑ ${p.score}")
                    if (p.source.favs) Stat("♡ ${p.favCount}")
                    if (p.size > 0) Stat(size(p.size))
                    Spacer(Modifier.weight(1f))
                    // Comments open in a sheet of their own.
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(C.Violet.copy(alpha = 0.16f))
                            .clickable(onClick = onComments)
                            .padding(horizontal = 9.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Ico(Icons.Comment, C.Violet300, size = 13.dp)
                        // Some sites do not count a post's comments: then only the icon.
                        if (p.comments >= 0) Text("${p.comments}", style = T.mono.copy(fontSize = 11.sp, color = C.Violet300, fontWeight = FontWeight.SemiBold))
                    }
                }

                if (p.about.isNotBlank()) {
                    var whole by remember(p.id) { mutableStateOf(false) }
                    Text(
                        remember(p.about) { dtext(p.about) },
                        style = T.small.copy(fontSize = 13.sp, color = C.Text.copy(alpha = 0.8f)),
                        maxLines = if (whole) Int.MAX_VALUE else 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(remember { MutableInteractionSource() }, indication = null) { whole = !whole },
                    )
                }

                PoolCards(hub, p)
                FamilyStrip(hub, p)

                for ((cat, list) in groups) {
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        if (cat.isNotEmpty()) Kicker(tagCategoryTitle(cat))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            val color = if (cat.isEmpty()) C.N300 else tagColor(cat)
                            for (t in list) {
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(9.dp))
                                        .background(if (cat.isEmpty()) Color(0xFF221C30) else color.copy(alpha = 0.12f))
                                        // A tap searches it; a long press says what it means.
                                        .combinedClickable(
                                            onLongClick = { onTag(t, cat.ifEmpty { hub.cat(p, t)?.let(::tagCategory).orEmpty() }) },
                                            onClick = { hub.search(t, on = p.site, keepMix = true) },
                                        )
                                        .padding(horizontal = 11.dp, vertical = 6.dp),
                                ) {
                                    Text(t.replace('_', ' '), style = T.small.copy(color = if (cat.isEmpty()) C.Text.copy(alpha = 0.85f) else color, fontSize = 12.sp))
                                }
                            }
                        }
                    }
                }
            }
        }

        Row(Modifier.padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(C.Glow)
                    .clickable { hub.download(p) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                Ico(Icons.Download, Color.White, size = 18.dp)
                Text("Скачать", style = T.card.copy(fontSize = 14.sp, color = Color.White), maxLines = 1)
            }
            HeartSquare(hub.faved(p)) { hub.toggleFav(feed, index) }
            Square(Icons.Folder, if (hub.colls.any { c -> c.posts.any { it.key == p.key } }) C.Violet300 else C.N300, C.Tint, onCollect)
            Square(Icons.Share, C.N300, C.Tint) {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, p.webUrl)
                runCatching { ctx.startActivity(Intent.createChooser(send, null)) }
            }
            Square(Icons.External, C.N300, C.Tint) {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.webUrl))) }
            }
        }
    }
}

/** Tags the API files as artists that are not one. */
private val NOT_ARTISTS = setOf("conditional_dnp", "sound_warning", "epilepsy_warning", "unknown_artist", "anonymous_artist", "third-party_edit")

@Composable
private fun Stat(text: String, color: Color = C.Text.copy(alpha = 0.7f), bg: Color = Color(0x0FFFFFFF)) {
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 9.dp, vertical = 5.dp)) {
        Text(text, style = T.mono.copy(fontSize = 11.sp, color = color, fontWeight = FontWeight.SemiBold), maxLines = 1)
    }
}

@Composable
private fun Square(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, bg: Color, onClick: () -> Unit) {
    Box(
        Modifier.width(48.dp).height(48.dp).clip(RoundedCornerShape(15.dp)).background(bg).springClick(0.86f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Ico(icon, tint, size = 20.dp) }
}

/** The heart: it jumps and throws a ring of sparks when it fills, shrinks a little when it empties. */
@Composable
private fun HeartSquare(on: Boolean, onClick: () -> Unit) {
    val pop = remember { Animatable(1f) }
    val burst = remember { Animatable(1f) }
    val was = remember { booleanArrayOf(on) }
    LaunchedEffect(on) {
        if (on == was[0]) return@LaunchedEffect
        was[0] = on
        if (on) {
            launch { burst.snapTo(0f); burst.animateTo(1f, tween(560)) }
            pop.snapTo(0.6f)
            pop.animateTo(1f, spring(dampingRatio = 0.3f, stiffness = 420f))
        } else {
            pop.snapTo(0.8f)
            pop.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 500f))
        }
    }
    val src = remember { MutableInteractionSource() }
    val bg by androidx.compose.animation.animateColorAsState(C.Accent.copy(alpha = if (on) 0.3f else 0.16f), tween(220), label = "heartbg")
    Box(
        Modifier
            .pressable(src, 0.86f)
            .width(48.dp)
            .height(48.dp)
            // The sparks fly out past the square's edge, so they are drawn before the clip.
            .drawBehind {
                val t = burst.value
                if (t >= 1f) return@drawBehind
                val reach = size.minDimension * (0.4f + 0.6f * t)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0 + 22.5)
                    drawCircle(
                        C.Accent.copy(alpha = 1f - t),
                        radius = 3.2.dp.toPx() * (1f - 0.6f * t),
                        center = center + Offset((Math.cos(a) * reach).toFloat(), (Math.sin(a) * reach).toFloat()),
                    )
                }
                drawCircle(C.Accent.copy(alpha = 0.6f * (1f - t)), radius = size.minDimension * (0.3f + 0.45f * t), style = Stroke(2.5.dp.toPx() * (1f - t)))
            }
            .clip(RoundedCornerShape(15.dp))
            .background(bg)
            .clickable(interactionSource = src, indication = LocalIndication.current, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value }) {
            Ico(if (on) Icons.HeartFilled else Icons.Heart, C.Accent, size = 20.dp)
        }
    }
}

private fun size(n: Long) = if (n > 0) bytes(n) else "—"

/** A still with pinch, pan and double-tap zoom that leaves swipes to the pager. */
@Composable
private fun Still(
    url: String?, placeholder: String?, ratio: Float, panel: Int,
    notes: List<Note> = emptyList(), w: Int = 0, h: Int = 0,
    onLoaded: () -> Unit = {},
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val zoomer = androidx.compose.runtime.rememberCoroutineScope()
    // Lift the picture out from under the panel when there is empty room above
    // it: never more than that room, so nothing is ever scaled down.
    val lift by animateFloatAsState(
        run {
            if (size.height == 0 || ratio <= 0f) return@run 0f
            val fitted = minOf(size.height.toFloat(), size.width / ratio)
            val room = (size.height - fitted) / 2f
            minOf(room, maxOf(0f, panel - room))
        },
        tween(220),
        label = "lift",
    )
    val ctx = LocalContext.current

    fun clamp(o: Offset, s: Float): Offset {
        val mx = size.width * (s - 1) / 2f
        val my = size.height * (s - 1) / 2f
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }

    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { at ->
                        val fromS = scale
                        val fromO = offset
                        val toS: Float
                        val toO: Offset
                        if (scale > 1.01f) {
                            toS = 1f; toO = Offset.Zero
                        } else {
                            toS = 2.5f
                            val center = Offset(size.width / 2f, size.height / 2f)
                            toO = clamp((center - at) * 1.5f, 2.5f)
                        }
                        zoomer.launch {
                            androidx.compose.animation.core.animate(0f, 1f, animationSpec = spring(dampingRatio = 0.82f, stiffness = 380f)) { t, _ ->
                                scale = fromS + (toS - fromS) * t
                                offset = fromO + (toO - fromO) * t
                            }
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val multi = event.changes.count { it.pressed } > 1
                        if (multi || scale > 1.01f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val next = (scale * zoom).coerceIn(1f, 6f)
                            offset = if (next <= 1.01f) Offset.Zero else clamp(offset + pan, next)
                            scale = next
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            // The spinner follows the painter's own state: a picture served
            // from memory is Success from the first frame, with no callback
            // to clear a separate "loading" flag.
            val painter = rememberAsyncImagePainter(
                ImageRequest.Builder(ctx)
                    .data(url)
                    .apply { if (placeholder != null) placeholderMemoryCacheKey(placeholder) }
                    .build(),
                contentScale = ContentScale.Fit,
            )
            val state by painter.state.collectAsState()
            LaunchedEffect(state) { if (state is AsyncImagePainter.State.Success) onLoaded() }
            Image(
                painter = painter,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale; scaleY = scale
                        translationX = offset.x; translationY = offset.y - lift
                    },
            )
            if (notes.isNotEmpty() && state is AsyncImagePainter.State.Success) Box(
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = offset.x; translationY = offset.y
                },
            ) { NotesLayer(notes, w, h, size, lift) }
            if (state is AsyncImagePainter.State.Loading) CircularProgressIndicator(color = C.Accent, strokeWidth = 2.5.dp)
        } else {
            Text("Файл скрыт — войдите в аккаунт", style = T.small)
        }
    }
}

/**
 * A video with our own controls. A tap plays and pauses (a big play mark
 * shows while paused); the seek bar sits right above the viewer's buttons and
 * fades with them while the video plays untouched.
 *
 * Frames go into a TextureView, not PlayerView's SurfaceView: a SurfaceView is
 * a separate layer that ignores Compose's alpha, so it stayed on screen over
 * the grid while the viewer faded out.
 */
@OptIn(UnstableApi::class)
@Composable
private fun Video(url: String, above: Dp, chrome: Boolean, step: Int, takeReveal: () -> Boolean, onPlaying: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val player = remember(url) {
        // When a hardware decoder refuses the stream, try the next one
        // (down to the software decoder) instead of stopping on a black frame.
        ExoPlayer.Builder(ctx, DefaultRenderersFactory(ctx).setEnableDecoderFallback(true))
            .setMediaSourceFactory(DefaultMediaSourceFactory(VideoCache.factory(ctx.applicationContext)))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(url))
                repeatMode = Player.REPEAT_MODE_ONE
                playWhenReady = true
                prepare()
            }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    var playing by remember(player) { mutableStateOf(true) }
    var buffering by remember(player) { mutableStateOf(true) }
    var ratio by remember(player) { mutableFloatStateOf(0f) }
    var position by remember(player) { mutableLongStateOf(0L) }
    var duration by remember(player) { mutableLongStateOf(0L) }
    var dragging by remember(player) { mutableStateOf<Float?>(null) }
    var failed by remember(player) { mutableStateOf<String?>(null) }

    val report by rememberUpdatedState(onPlaying)
    LaunchedEffect(playing, buffering, failed, dragging) { report(playing && !buffering && failed == null && dragging == null) }
    DisposableEffect(Unit) { onDispose { report(false) } }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { playing = playWhenReady }
            override fun onPlaybackStateChanged(state: Int) { buffering = state == Player.STATE_BUFFERING }
            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) ratio = size.width * size.pixelWidthHeightRatio / size.height
            }
            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.w("booruhub", "video failed", error)
                buffering = false
                failed = "Видео не воспроизводится (${error.errorCodeName})"
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            duration = player.duration.coerceAtLeast(0L)
            delay(250)
        }
    }

    // Seeking by taps, as on YouTube: two quick taps on a side jump [step]
    // seconds; every further quick tap on that side jumps once more.
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var seekSide by remember(player) { mutableIntStateOf(0) }
    var seekTotal by remember(player) { mutableIntStateOf(0) }
    var seekTick by remember(player) { mutableIntStateOf(0) }
    var lastTap by remember(player) { mutableStateOf<Pair<Long, Int>?>(null) }
    var pendingToggle by remember(player) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val window = 650L
    fun toggle() {
        // The tap that brought the controls back only shows them.
        if (!takeReveal()) {
            if (player.playWhenReady) player.pause() else player.play()
        }
    }
    fun seek(side: Int) {
        takeReveal()
        val end = if (duration > 0) duration else Long.MAX_VALUE
        val to = (player.currentPosition + side * step * 1000L).coerceIn(0L, end - 200L)
        player.seekTo(to); position = to
        seekTotal = if (seekSide == side) seekTotal + step else step
        seekSide = side
        val tick = ++seekTick
        scope.launch {
            delay(window)
            if (seekTick == tick) { seekSide = 0; seekTotal = 0 }
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(player) {
                detectTapGestures(onTap = { at ->
                    val now = System.currentTimeMillis()
                    val side = when {
                        at.x < size.width * 0.35f -> -1
                        at.x > size.width * 0.65f -> 1
                        else -> 0
                    }
                    val last = lastTap
                    lastTap = now to side
                    when {
                        side == 0 -> { pendingToggle?.cancel(); toggle() }
                        // Seeking already: each tap on that side goes on.
                        seekSide == side -> seek(side)
                        last != null && last.second == side && now - last.first < 300 -> {
                            pendingToggle?.cancel(); pendingToggle = null
                            seek(side)
                        }
                        // A side tap waits a moment, in case a second one follows.
                        else -> {
                            pendingToggle?.cancel()
                            pendingToggle = scope.launch { delay(300); toggle() }
                        }
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { TextureView(it).also { tv -> player.setVideoTextureView(tv) } },
            modifier = if (ratio > 0f) Modifier.aspectRatio(ratio) else Modifier.size(1.dp),
        )
        when {
            failed != null -> Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(failed.orEmpty(), style = T.small, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                GhostButton("Ещё раз", {
                    failed = null; buffering = true
                    player.prepare(); player.play()
                }, icon = Icons.Refresh)
            }
            buffering && seekSide == 0 -> CircularProgressIndicator(color = C.Accent, strokeWidth = 2.5.dp)
            !playing && seekSide == 0 -> Box(
                Modifier.size(76.dp).clip(CircleShape).background(Color(0x99140F1D)),
                contentAlignment = Alignment.Center,
            ) { Ico(Icons.Play, C.Accent, size = 34.dp) }
        }
        for (side in listOf(-1, 1)) AnimatedVisibility(
            seekSide == side,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(260)),
            modifier = Modifier.align(if (side < 0) Alignment.CenterStart else Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.38f),
        ) {
            // An arc bulging in from the side, as an oval twice as wide as the box.
            val shape = androidx.compose.foundation.shape.GenericShape { box, _ ->
                val l = if (side < 0) -box.width else 0f
                addOval(androidx.compose.ui.geometry.Rect(l, -box.height * 0.15f, l + box.width * 2, box.height * 1.15f))
            }
            // Each further tap bounces the label.
            val pop = remember { androidx.compose.animation.core.Animatable(1f) }
            LaunchedEffect(seekTick) {
                pop.snapTo(1.18f)
                pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 600f))
            }
            Box(Modifier.fillMaxSize().clip(shape).background(Color(0x33FFFFFF)), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(if (side < 0) "◀◀" else "▶▶", style = T.body.copy(color = Color.White, fontSize = 18.sp))
                    Text("${if (side < 0) "−" else "+"}${rememberLast(seekTotal.takeIf { it > 0 }) ?: step} с", style = T.card.copy(color = Color.White, fontSize = 15.sp))
                }
            }
        }
        AnimatedVisibility(
            chrome,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 22.dp, end = 22.dp, bottom = above + 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(clock(position), style = T.tiny.copy(color = C.N300))
                Slider(
                    value = dragging ?: if (duration > 0) position.toFloat() / duration else 0f,
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.let { player.seekTo((it * duration).toLong()); position = (it * duration).toLong() }
                        dragging = null
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = C.Accent,
                        activeTrackColor = C.Accent,
                        inactiveTrackColor = C.Hair,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Text(clock(duration), style = T.tiny.copy(color = C.N300))
            }
        }
    }
}

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** A post's comments, oldest first, as on the site. */
@Composable
private fun CommentsSheet(p: Post, onOpen: (Post) -> Unit) {
    var list by remember(p.id) { mutableStateOf<List<Comment>?>(null) }
    // Avatars come after the comments, so the text never waits for pictures.
    var avatars by remember(p.id) { mutableStateOf<Map<Long, Post>>(emptyMap()) }
    LaunchedEffect(list) {
        // Only e621 has avatars.
        val users = list?.takeIf { p.site == "e621" }?.map { it.user }?.filter { it > 0 }?.distinct().orEmpty()
        if (users.isNotEmpty()) avatars = runCatching { E621.avatars(users) }.getOrDefault(emptyMap())
    }
    var error by remember(p.id) { mutableStateOf<String?>(null) }
    var attempt by remember(p.id) { mutableIntStateOf(0) }
    LaunchedEffect(p.id, attempt) {
        error = null
        try {
            list = E621.comments(p.site, p.id)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = humanError(e)
        }
    }
    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        // The sheet draws behind the navigation bar; keep the last comment above it.
        contentPadding = WindowInsets.navigationBars.asPaddingValues(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                Text("Комментарии", style = T.card.copy(fontSize = 18.sp), modifier = Modifier.weight(1f))
                Text("#${p.id}", style = T.mono.copy(fontSize = 11.sp, color = C.N500))
            }
        }
        val got = list
        when {
            error != null && got == null -> item {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(error.orEmpty(), style = T.small)
                    GhostButton("Ещё раз", { attempt++ }, icon = Icons.Refresh)
                }
            }
            got == null -> item {
                Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = C.Accent, strokeWidth = 2.5.dp)
                }
            }
            got.isEmpty() -> item {
                Text("Комментариев пока нет", style = T.small.copy(color = C.N500), modifier = Modifier.padding(vertical = 24.dp))
            }
            else -> items(got, key = { it.id }) { c -> CommentCard(c, avatars[c.user], onOpen) }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun CommentCard(c: Comment, avatar: Post?, onOpen: (Post) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(C.Raised)
            .padding(start = 11.dp, end = 14.dp, top = 11.dp, bottom = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
    Avatar(c.author, avatar, c.avatar, onOpen)
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(c.author, style = T.card.copy(fontSize = 13.sp, color = C.Violet300), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 190.dp))
            Text(remember(c.at) { whenWritten(c.at) }, style = T.mono.copy(fontSize = 10.5.sp, color = C.N500))
            Spacer(Modifier.weight(1f))
            if (c.score != 0) Text(
                (if (c.score > 0) "↑ " else "↓ ") + kotlin.math.abs(c.score),
                style = T.mono.copy(fontSize = 10.5.sp, color = if (c.score > 0) C.Good else C.Bad),
            )
        }
        Text(remember(c.body) { dtext(c.body) }, style = T.small.copy(fontSize = 13.5.sp, color = C.Text.copy(alpha = 0.9f)))
    }
    }
}

/** The post the author wears as an avatar, which a tap opens; their initial when there is none to show. */
@Composable
private fun Avatar(author: String, post: Post?, picture: String?, onOpen: (Post) -> Unit) {
    val fromPost = post?.takeIf { !it.blocked }?.previewUrl
    val url = fromPost ?: picture
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(C.Violet.copy(alpha = 0.22f))
            .then(if (fromPost != null) Modifier.clickable { onOpen(post) } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(author.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?", style = T.card.copy(fontSize = 14.sp, color = C.Violet300))
        if (url != null) AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(url).build(),
            contentDescription = "аватар $author",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun whenWritten(iso: String): String =
    runCatching { ago(java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()) }.getOrDefault("")

/** e621's DText, roughly: what reads as text stays, the markup becomes style. */
private val DTEXT = Regex(
    // Android's ICU regex wants every literal ] and } escaped.
    """\[(/?)(b|i|u|s|spoiler|quote|sup|sub|code|color|section|tn)(?:=[^\]]*)?\]|"([^"\n]+)":(\[[^\]\s]+\]|[^\s<>"]+)|\[\[([^\]|]+)(?:\|([^\]]+))?\]\]|\{\{([^\}]+)\}\}""",
    RegexOption.IGNORE_CASE,
)

fun dtext(body: String): AnnotatedString = buildAnnotatedString {
    val open = ArrayList<Pair<String, Int>>()
    var at = 0
    val text = body.replace("\r\n", "\n").trim().replace(Regex("\\s+\\[/quote\\]", RegexOption.IGNORE_CASE), "[/quote]")
    for (m in DTEXT.findAll(text)) {
        append(text.substring(at, m.range.first))
        at = m.range.last + 1
        val g = m.groupValues
        when {
            g[2].isNotEmpty() -> {
                val name = g[2].lowercase()
                if (g[1].isEmpty()) {
                    val style = when (name) {
                        "b" -> SpanStyle(fontWeight = FontWeight.Bold)
                        "i" -> SpanStyle(fontStyle = FontStyle.Italic)
                        "u" -> SpanStyle(textDecoration = TextDecoration.Underline)
                        "s" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                        "spoiler" -> SpanStyle(background = C.Violet.copy(alpha = 0.22f), color = C.Violet300)
                        "quote" -> SpanStyle(color = C.N500, fontStyle = FontStyle.Italic)
                        "code" -> SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        else -> null
                    }
                    if (style != null) open.add(name to pushStyle(style))
                } else {
                    val i = open.indexOfLast { it.first == name }
                    if (i >= 0) {
                        pop(open[i].second)
                        while (open.size > i) open.removeAt(open.lastIndex)
                        // A quote ends a paragraph: one break after it, not three.
                        if (name == "quote") {
                            while (at < text.length && text[at] == '\n') at++
                            if (at < text.length) append('\n')
                        }
                        if (name == "quote" && text.startsWith("\n", at)) append('\n')
                    }
                }
            }
            g[3].isNotEmpty() -> withStyle(SpanStyle(color = C.Violet300, textDecoration = TextDecoration.Underline)) { append(g[3]) }
            g[5].isNotEmpty() -> withStyle(SpanStyle(color = C.Violet300)) { append(g[6].ifEmpty { g[5] }) }
            g[7].isNotEmpty() -> withStyle(SpanStyle(color = C.Violet300)) { append(g[7]) }
        }
    }
    append(text.substring(at))
}
