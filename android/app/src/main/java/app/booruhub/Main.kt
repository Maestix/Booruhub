package app.booruhub

import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.ui.unit.Dp
import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade

class BooruApp : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        Net.init(this)
        Core.start(this)
        Downloads.init(this)
    }

    // Pictures go through the same client, so Cloudflare's cookie and the
    // agent that earned it reach the image servers too.
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { Net.client }))
                add(AnimatedImageDecoder.Factory())
            }
            .crossfade(true)
            .build()
}

class MainActivity : ComponentActivity() {
    private val hub: Hub by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent { HubTheme { App(hub) } }
        openFrom(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        openFrom(intent)
    }

    /** A watched query's notification names the query to open. */
    private fun openFrom(intent: android.content.Intent?) {
        val id = intent?.getLongExtra(Watch.EXTRA_SAVED, 0L) ?: 0L
        if (id != 0L) {
            intent?.removeExtra(Watch.EXTRA_SAVED)
            hub.openSavedById(id)
        }
    }
}

@Composable
fun App(hub: Hub) {
    // Pixel 7's hole-punch sits inside the status bar upright; on its side it
    // would cover the screen's edge, so keep content off it there.
    Box(
        Modifier
            .fillMaxSize()
            .background(C.Bg)
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)),
    ) {
        BackHandler(enabled = hub.tab != Tab.Feed && hub.page == null && hub.viewer == null && hub.web == null) { hub.tab = Tab.Feed }
        BackHandler(enabled = hub.page != null && hub.viewer == null && hub.web == null) { hub.page = null }
        // On the feed, back undoes the last search (a tag tapped in a post, say) before it leaves the app.
        BackHandler(enabled = hub.backDepth > 0 && hub.tab == Tab.Feed && hub.page == null && hub.viewer == null && hub.web == null) { hub.goBack() }
        // Choosing posts: back stops choosing first. Registered last, so it
        // goes before the handlers above (the last enabled one wins).
        BackHandler(enabled = hub.picking && hub.viewer == null && hub.web == null) { hub.clearPicks() }

        AnimatedContent(
            hub.tab,
            // Sideways, the way the pill's buttons lie.
            transitionSpec = {
                val d = if (targetState.ordinal > initialState.ordinal) 1 else -1
                (slideInHorizontally(tween(260)) { d * it / 8 } + fadeIn(tween(200))) togetherWith
                    (slideOutHorizontally(tween(200)) { -d * it / 8 } + fadeOut(tween(140)))
            },
            label = "tab",
        ) { tab ->
            when (tab) {
                Tab.Feed -> AnimatedContent(
                    hub.main,
                    // Search tabs slide in from the side they lie on.
                    transitionSpec = {
                        val d = if (hub.feedTabs.indexOf(targetState) >= hub.feedTabs.indexOf(initialState)) 1 else -1
                        (slideInHorizontally(tween(280)) { d * it / 3 } + fadeIn(tween(220))) togetherWith
                            (slideOutHorizontally(tween(240)) { -d * it / 3 } + fadeOut(tween(160)))
                    },
                    label = "feedTab",
                ) { f -> FeedScreen(hub, f, empty = "Ничего не нашлось") }
                Tab.Search -> SearchScreen(hub)
                Tab.Downloads -> DownloadsScreen(hub)
                Tab.More -> CollectionsScreen(hub)
            }
        }

        // Pages open over their tab, with a back arrow.
        AnimatedContent(
            hub.page,
            // A page comes in from the right and leaves the same way.
            transitionSpec = {
                when {
                    initialState == null -> (slideInHorizontally(tween(280)) { it / 4 } + fadeIn(tween(220))) togetherWith fadeOut(tween(160))
                    targetState == null -> fadeIn(tween(160)) togetherWith (slideOutHorizontally(tween(240)) { it / 4 } + fadeOut(tween(200)))
                    else -> (slideInHorizontally(tween(260)) { it / 5 } + fadeIn(tween(200))) togetherWith fadeOut(tween(140))
                }.using(null)
            },
            label = "page",
        ) { page ->
            // A page covers its tab: the ground is laid here once, for every page.
            if (page != null) Box(Modifier.fillMaxSize().background(C.Bg)) {
                when (page) {
                    Page.Saved -> SavedScreen(hub)
                    Page.Settings -> AccountScreen(hub)
                    Page.Blacklist -> BlacklistScreen(hub)
                    Page.Favorites -> FavoritesScreen(hub)
                    is Page.Posts -> FeedScreen(hub, page.feed, empty = "Пусто", title = page.title, note = page.note, onBack = { hub.page = null })
                    is Page.Collection -> {
                        val c = hub.colls.firstOrNull { it.id == page.id }
                        val feed = hub.collFeed(page.id)
                        if (c != null && feed != null) FeedScreen(
                            hub, feed, empty = "Пусто. Добавить пост — кнопка с папкой у поста.",
                            title = c.name, note = "${c.posts.size} ${plural(c.posts.size, "пост", "поста", "постов")}",
                            onBack = { hub.page = null },
                        )
                    }
                }
            }
        }

        // Choosing posts: the pill sinks and the actions rise in its place.
        AnimatedContent(
            hub.picking && hub.viewer == null,
            Modifier.align(Alignment.BottomCenter),
            transitionSpec = {
                (slideInVertically(spring(dampingRatio = 0.75f, stiffness = 500f)) { it } + fadeIn(tween(160))) togetherWith
                    (slideOutVertically(tween(180)) { it } + fadeOut(tween(140)))
            },
            label = "bar",
        ) { choosing -> if (choosing) PickBar(hub) else NavPill(hub) }

        AnimatedVisibility(
            visible = hub.viewer != null,
            enter = fadeIn(tween(180)) + scaleIn(spring(dampingRatio = 0.8f, stiffness = 420f), initialScale = 0.9f),
            exit = fadeOut(tween(150)) + scaleOut(tween(170), targetScale = 0.92f),
        ) {
            // The last viewer stays drawn while it fades out.
            rememberLast(hub.viewer)?.let { (feed, index) -> androidx.compose.runtime.key(feed) { ViewerScreen(hub, feed, index) } }
        }

        hub.web?.let { WebScreen(hub, it) }

        Toast(hub.toast, Modifier.align(Alignment.BottomCenter))
    }
}

private data class NavItem(val tab: Tab, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val navItems = listOf(
    NavItem(Tab.Feed, "Лента", Icons.Grid),
    NavItem(Tab.Search, "Поиск", Icons.Search),
    NavItem(Tab.Downloads, "Загрузки", Icons.Download),
    NavItem(Tab.More, "Ещё", Icons.Gear),
)

private val PillHeight = 62.dp
private val PillGap = 2.dp

/** How much of the screen the floating pill takes above the system bar. */
val PillSpace = PillHeight + PillGap

/** Keeps something clear of the floating pill: the system bar, the pill, then [gap]. */
fun Modifier.abovePill(gap: Dp = 8.dp) = navigationBarsPadding().padding(bottom = PillSpace + gap)

/** The same room as a number, for a list's content padding or a spacer. */
@Composable
fun pillClearance(gap: Dp = 12.dp): Dp =
    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + PillSpace + gap

/** The floating pill from the design: everything pressed lives at the bottom. */
@Composable
fun NavPill(hub: Hub, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(30.dp)
    BoxWithConstraints(
        modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            // Close to the screen's edge, as in the design.
            .padding(start = 16.dp, end = 16.dp, bottom = PillGap)
            .fillMaxWidth()
            .height(PillHeight)
            .clip(shape)
            .background(Color(0xFF231D2F))
            .border(1.dp, Color(0x14FFFFFF), shape)
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        val gap = 4.dp
        val w = (maxWidth - gap * (navItems.size - 1)) / navItems.size
        val at = navItems.indexOfFirst { it.tab == hub.tab }.coerceAtLeast(0)
        // The highlight slides to the chosen button and settles with a little give.
        val x by animateDpAsState((w + gap) * at, spring(dampingRatio = 0.68f, stiffness = 420f), label = "pill")
        Box(
            Modifier
                .offset(x = x)
                .width(w)
                .height(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(C.Violet.copy(alpha = 0.16f)),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
            for (item in navItems) {
                val on = hub.tab == item.tab
                val fg by animateColorAsState(if (on) C.Violet300 else C.N500, tween(220), label = "navfg")
                val pop by animateFloatAsState(if (on) 1.14f else 1f, spring(dampingRatio = 0.35f, stiffness = 450f), label = "pop")
                val src = remember { MutableInteractionSource() }
                Column(
                    Modifier
                        .width(w)
                        .height(48.dp)
                        .pressable(src, 0.88f)
                        .clip(RoundedCornerShape(24.dp))
                        .clickable(src, indication = null) {
                            if (hub.tab == item.tab && item.tab == Tab.Feed && hub.page == null) hub.load(hub.main, reset = true)
                            hub.page = null
                            hub.tab = item.tab
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(Modifier.graphicsLayer { scaleX = pop; scaleY = pop }) { Ico(item.icon, fg, size = 20.dp) }
                    Text(item.label, style = T.tiny.copy(color = fg, fontSize = 10.5.sp))
                }
            }
        }
    }
}

/** While posts are picked the pill holds what to do with them. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PickBar(hub: Hub, modifier: Modifier = Modifier) {
    var collect by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val shape = RoundedCornerShape(30.dp)
    Row(
        modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 16.dp, end = 16.dp, bottom = PillGap)
            .fillMaxWidth()
            .height(PillHeight)
            .clip(shape)
            .background(Color(0xFF231D2F))
            .border(1.dp, C.Accent.copy(alpha = 0.35f), shape)
            .padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PickAction(Icons.Download, "Скачать", Modifier.weight(1f)) { hub.downloadPicked() }
        PickAction(Icons.Folder, "В папку", Modifier.weight(1f)) { collect = true }
        PickAction(Icons.Heart, "В избранное", Modifier.weight(1f)) { hub.favPicked() }
    }
    if (collect) androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = { collect = false },
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = C.Surface,
        shape = S.sheet,
    ) { CollectionPicker(hub, hub.picked.values.toList()) }
}

@Composable
private fun PickAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).springClick(0.88f, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Ico(icon, C.Violet300, size = 20.dp)
        Text(label, style = T.tiny.copy(color = C.Violet300, fontSize = 10.5.sp))
    }
}

@Composable
fun Toast(text: String?, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = text != null,
        modifier = modifier.abovePill(gap = 12.dp),
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
    ) {
        val last = rememberLast(text)
        Box(
            Modifier
                .padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(C.Raised)
                .border(1.dp, C.Accent.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) { Text(last.orEmpty(), style = T.body.copy(fontSize = 14.sp)) }
    }
}
