package app.booruhub

import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import android.app.Application
import android.content.Context
import android.webkit.CookieManager
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Tab { Feed, Search, Downloads, More }

/** A screen opened over a tab, with a back arrow. */
sealed interface Page {
    data object Saved : Page
    data object Settings : Page
    data object Blacklist : Page
    data object Favorites : Page
    data class Collection(val id: Long) : Page
    /** A pool's posts in reading order, or a post's family. */
    class Posts(val title: String, val note: String, val feed: Feed) : Page
}

/** A list of posts for one query, paged as the person scrolls. */
class Feed(tags: String = "") {
    var tags by mutableStateOf(tags)
    val posts = mutableStateListOf<Post>()
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var end by mutableStateOf(false)
    var page = 1
    var job: Job? = null
    val grid = LazyStaggeredGridState()
    /** A collection's posts, kept on the phone: nothing to load. */
    var local = false
    /** One of [ORDERS]' keys: the sort tab under the header. */
    var order by mutableStateOf("order:hot")
    /** A [Site.code], or [MIX]. */
    var site by mutableStateOf("e621")
    /** The sources a mixed feed takes from, in the list's order: each tab its own. */
    val mix = mutableStateListOf<String>()
    /** A pool's posts by id, in order: the feed pages through these instead of a query. */
    var ids: List<Long>? = null
}

/** How many posts a page of a feed asks for. */
const val PAGE = 60

/** The sort tabs from the original design, as e621 order tags. */
val ORDERS = listOf("order:hot" to "Популярное", "" to "Новое", "order:random" to "Случайное", "order:comment_bumped" to "Обсуждаемое")

/** The site's own best-of page, by span: a tab of its own where the site has one. */
const val BEST = "best:"
val BEST_SCALES = listOf("day" to "день", "week" to "неделя", "month" to "месяц")

/** Sites with a best-of page. */
fun hasBest(site: String) = site == "e621" || site == "danbooru"

enum class WebPurpose { Cloudflare, ApiKey }

data class Web(val url: String, val purpose: WebPurpose)

fun humanError(e: Exception): String {
    android.util.Log.w("booruhub", "request failed", e)
    return when (e) {
    is CloudflareChallenge -> "Cloudflare просит проверку"
    is ApiError -> e.message.orEmpty()
    is java.net.UnknownHostException -> "Нет сети: e621.net не находится"
    is java.net.SocketTimeoutException, is java.net.ConnectException -> "e621 не отвечает — проверьте сеть"
    is javax.net.ssl.SSLException -> "Не удалось установить защищённое соединение"
    else -> e.message ?: e.javaClass.simpleName
    }
}

class Hub(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("booruhub", Context.MODE_PRIVATE)

    var tab by mutableStateOf(Tab.Feed)
    var page by mutableStateOf<Page?>(null)
    /** Search tabs: each a feed with its own query, sort and source. */
    val feedTabs = mutableStateListOf<Feed>()

    /** The tab on screen. */
    var main by mutableStateOf(Feed())
        private set
    val favs = Feed().apply { order = "" }
    /** Danbooru's favourites, newest first: `ordfav:` the login's name. */
    val danFavs = Feed().apply { order = ""; site = "danbooru" }
    /** Which site's favourites the favourites page shows. */
    var favSite by mutableStateOf(prefs.getString("favSite", "e621")!!)
        private set

    /**
     * Likes kept on the phone, newest first, for the sites whose API takes no
     * favourites (Gelbooru, Rule34). In the prefs, so a backup carries them.
     */
    val likes = mutableStateListOf<Post>().apply {
        val a = runCatching { JSONArray(prefs.getString("likes", "[]")) }.getOrElse { JSONArray() }
        for (i in 0 until a.length()) runCatching { add(postFromJson(a.getJSONObject(i))) }
    }
    private val likeKeys = mutableStateMapOf<String, Boolean>().apply { for (p in likes) put(p.key, true) }
    var viewer by mutableStateOf<Pair<Feed, Int>?>(null)
    var web by mutableStateOf<Web?>(null)
    var toast by mutableStateOf<String?>(null)
    private var toastJob: Job? = null

    var user by mutableStateOf<String?>(null)
        private set
    var ratings by mutableStateOf(prefs.getStringSet("ratings", setOf("s", "q", "e"))!!.toSet())
        private set
    var original by mutableStateOf(prefs.getBoolean("original", false))
        private set
    val recent = mutableStateListOf<String>()

    /** The search screen's chips and filter cards, kept between visits. */
    val searchTokens = mutableStateListOf<String>()
    val filters = searchFilters()
    private val filterWords = filters.flatMap { f -> f.options.flatMap { it.first.split(' ') } }.filter { it.isNotBlank() }.toSet()

    var squareTiles by mutableStateOf(prefs.getBoolean("square", false))
        private set

    fun chooseSquare(on: Boolean) {
        squareTiles = on
        prefs.edit().putBoolean("square", on).apply()
    }

    /** e621's tag categories, fetched as posts are viewed (the API gives tags flat). */
    val tagCats = mutableStateMapOf<String, Int>()

    /** Tag names repeat across sites with other meanings: each site keeps its own. */
    private fun catKey(site: String, tag: String) = if (site == "e621") tag else "$site/$tag"

    /** A tag's category on the post's site, once known. */
    fun cat(p: Post, tag: String): Int? = tagCats[catKey(p.site, tag)]

    /**
     * Runs in the viewer's page effect: swiping on cancels the request, and
     * the next page asks for everything it still lacks.
     */
    suspend fun categorize(p: Post) {
        if (p.tags[E621.FLAT] == null) return
        val missing = p.allTags.filter { catKey(p.site, it) !in tagCats }
        if (missing.isEmpty()) return
        val got = try {
            E621.categories(p.site, missing)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        for (t in missing) tagCats[catKey(p.site, t)] = got[t] ?: 0
    }

    // --- sources (the design's 1a–1c) ------------------------------------------------

    /** The main feed's source: a [Site.code], or [MIX]. */
    var site by mutableStateOf(prefs.getString("site", "e621")!!)
        private set

    /** The sources the tab on screen mixes, in the list's order. */
    val mixSites: androidx.compose.runtime.snapshots.SnapshotStateList<String> get() = main.mix

    /** The mix a new tab starts with: the last one chosen. */
    private fun defaultMix(): List<String> =
        prefs.getStringSet("mixSites", setOf("e621", "danbooru"))!!.sortedBy { Site.of(it).ordinal }

    /** The other boorus' logins by site code: (name or user id, API key). */
    val logins = mutableStateMapOf<String, Pair<String, String>>()
    val siteError = mutableStateMapOf<String, String>()
    var siteBusy by mutableStateOf<String?>(null)
        private set

    /** Where hints for typed tags come from. */
    val hintSite: String get() = if (site == MIX) mixSites.firstOrNull() ?: "e621" else site

    private fun pushLogins() {
        Core.logins = logins.toMap()
    }

    /** Whether the site answers at all: some only with a key. */
    fun ready(s: Site) = !s.keyed || logins[s.code] != null

    fun siteStatus(s: Site): String = when {
        s == Site.E621 -> user?.let { "вход: $it" } ?: "без входа"
        logins[s.code] != null -> "вход: ${logins[s.code]!!.first}"
        s.keyed -> "нужен API-ключ — в настройках"
        else -> "без входа · два тега в поиске"
    }

    fun needKey(s: Site) {
        say("${s.title} отвечает только с API-ключом")
        viewer = null; under.clear()
        tab = Tab.More
        page = Page.Settings
    }

    private fun moveTo(code: String) {
        site = code
        main.site = code
        prefs.edit().putString("site", code).apply()
    }

    /** Opens a source's feed (or the mixed one) with the same query; back returns to the last. */
    fun chooseSite(code: String) {
        viewer = null; under.clear(); page = null; tab = Tab.Feed
        if (code == site) return
        keepSpot()
        moveTo(code)
        main.posts.clear(); main.error = null
        load(main, reset = true)
    }

    fun toggleMix(s: Site, on: Boolean) {
        if (on && !ready(s)) { say("${s.title}: сначала API-ключ в настройках"); return }
        if (on) {
            if (s.code !in mixSites) mixSites.add(s.code)
            mixSites.sortBy { Site.of(it).ordinal }
        } else {
            mixSites.remove(s.code)
        }
        prefs.edit().putStringSet("mixSites", mixSites.toSet()).apply()
        saveTabs()
        if (site == MIX) { main.posts.clear(); load(main, reset = true) }
    }

    /** Signs in to a site after the site itself accepts the pair. */
    fun siteLogin(s: Site, rawUser: String, rawKey: String) {
        val (u, k) = splitKeyLine(rawUser, rawKey)
        if (u.isEmpty() || k.isEmpty()) { siteError[s.code] = "Нужны ${s.userLabel} и API-ключ"; return }
        siteBusy = s.code
        siteError.remove(s.code)
        viewModelScope.launch {
            try {
                E621.check(s.code, u, k)
                logins[s.code] = u to k
                prefs.edit().putString("login.${s.code}.user", u).putString("login.${s.code}.key", k).apply()
                pushLogins()
                say("${s.title}: вы вошли")
                if (main.site == s.code || main.site == MIX) { main.posts.clear(); main.error = null; load(main, reset = true) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                siteError[s.code] = humanError(e)
            } finally {
                siteBusy = null
            }
        }
    }

    fun siteLogout(s: Site) {
        logins.remove(s.code)
        prefs.edit().remove("login.${s.code}.user").remove("login.${s.code}.key").apply()
        pushLogins()
        if (s.keyed) {
            for (f in feedTabs) f.mix.remove(s.code)
            prefs.edit().putStringSet("mixSites", defaultMix().toSet() - s.code).apply()
            saveTabs()
        }
        say("${s.title}: вы вышли")
    }


    // --- the core's lists ------------------------------------------------------------

    /** A core call whose answer is read off the main thread; a failure is a toast. */
    private fun <T> coreList(m: String, vararg args: Pair<String, Any?>, read: (JSONArray) -> T, then: (T) -> Unit) {
        viewModelScope.launch {
            try {
                val value = withContext(Dispatchers.IO) { (Core.ask(m, *args) as? JSONArray)?.let(read) } ?: return@launch
                then(value)
            } catch (e: Exception) {
                say(humanError(e))
            }
        }
    }

    private fun savedCall(m: String, vararg args: Pair<String, Any?>, then: (List<Saved>) -> Unit = {}) =
        coreList(m, *args, read = ::savedFrom) { showSaved(it); then(it) }

    private fun collCall(m: String, vararg args: Pair<String, Any?>, then: (List<Coll>) -> Unit = {}) =
        coreList(m, *args, read = ::collsFrom) { showColls(it); then(it) }

    // --- 1f: saved queries (crates/core: saved_*) --------------------------------------

    val saved = mutableStateListOf<Saved>()

    private fun showSaved(list: List<Saved>) {
        saved.clear(); saved.addAll(list)
        pendingSaved?.let { id -> list.firstOrNull { it.id == id }?.let { pendingSaved = null; openSaved(it) } }
    }

    fun addSaved(name: String, query: String) = savedCall("saved_add", "name" to name, "query" to query, "site" to (if (site == MIX) "e621" else site)) {
        say("Запрос сохранён")
        checkSaved(only = it.firstOrNull()?.id)
    }

    fun removeSaved(q: Saved) = savedCall("saved_remove", "id" to q.id)

    fun watchSaved(q: Saved, on: Boolean) = savedCall("saved_watch", "id" to q.id, "on" to on) {
        if (on) checkSaved(only = q.id)
    }

    private var checking = false

    /** The core counts new posts for watched queries not checked in the last minutes. */
    fun checkSaved(only: Long? = null) {
        if (checking && only == null) return
        checking = true
        savedCall("saved_check", "only" to only, "rating" to ratingTag()) { checking = false }
    }

    /** Opens a saved query in the feed; the core marks everything in it as seen. */
    fun openSaved(q: Saved) {
        page = null
        search(q.query, on = q.site)
        savedCall("saved_open", "id" to q.id, "rating" to ratingTag())
    }

    // --- 1g: collections (crates/core: coll_*) -------------------------------------------

    val colls = mutableStateListOf<Coll>()

    private fun showColls(list: List<Coll>) {
        colls.clear(); colls.addAll(list)
        collFeeds.clear()
    }

    fun addColl(name: String, first: Post? = null, more: List<Post> = emptyList()) = collCall("coll_add", "name" to name, "post" to first?.toJson()) { list ->
        if (more.isNotEmpty()) list.lastOrNull { it.name == name }?.let { putInColl(it, more, true) }
        else say(if (first != null) "Добавлено в «$name»" else "Коллекция «$name» создана")
    }

    fun removeColl(c: Coll) = collCall("coll_remove", "id" to c.id)

    fun toggleInColl(c: Coll, p: Post) {
        val inside = c.posts.any { it.key == p.key }
        collCall("coll_toggle", "id" to c.id, "post" to p.toJson()) {
            say(if (inside) "Убрано из «${c.name}»" else "Добавлено в «${c.name}»")
        }
    }

    /** Several posts into a collection at once (or out, with [on] false). */
    fun putInColl(c: Coll, posts: List<Post>, on: Boolean) {
        collCall("coll_put", "id" to c.id, "on" to on, "posts" to JSONArray(posts.map { it.toJson() })) {
            val n = posts.size
            say((if (on) "В «${c.name}»: " else "Из «${c.name}» убрано: ") + "$n ${plural(n, "пост", "поста", "постов")}")
        }
    }

    // --- e621's sets ------------------------------------------------------------------

    val sets = mutableStateListOf<PostSet>()
    var setsLoaded by mutableStateOf(false)
        private set

    fun loadSets() {
        val u = user ?: return
        viewModelScope.launch {
            try {
                val got = E621.sets(u)
                sets.clear(); sets.addAll(got); setsLoaded = true
            } catch (e: Exception) {
                say(humanError(e))
            }
        }
    }

    /** Puts the posts into the set, or takes them out when all are in it already. */
    fun toggleInSet(s: PostSet, posts: List<Post>) {
        val mine = posts.filter { it.site == "e621" }
        if (mine.isEmpty()) return
        val on = !mine.all { it.id in s.posts }
        val i = sets.indexOfFirst { it.id == s.id }
        if (i >= 0) sets[i] = s.copy(posts = if (on) s.posts + mine.map { it.id } else s.posts - mine.map { it.id }.toSet())
        viewModelScope.launch {
            try {
                for (p in mine) if ((p.id in s.posts) != on) E621.setPost(s.id, p.id, on)
                say(if (on) "В сете «${s.name}»" else "Убрано из «${s.name}»")
            } catch (e: Exception) {
                val j = sets.indexOfFirst { it.id == s.id }
                if (j >= 0) sets[j] = s
                say(humanError(e))
            }
        }
    }

    // --- choosing several posts (a long press on a tile) -----------------------------------

    /** The posts picked, by key, in the order picked; empty when not choosing. */
    val picked = mutableStateMapOf<String, Post>()
    val picking: Boolean get() = picked.isNotEmpty()

    fun togglePick(p: Post) { if (picked.remove(p.key) == null) picked[p.key] = p }

    fun pickAll(feed: Feed) {
        val all = feed.posts.filter { !it.blocked || !blockHide }
        if (all.all { it.key in picked }) picked.clear() else all.forEach { picked[it.key] = it }
    }

    fun clearPicks() = picked.clear()

    fun downloadPicked() {
        val list = picked.values.toList()
        val added = list.count { it.fileUrl != null && Downloads.add(it) }
        say(if (added > 0) "В загрузках: $added ${plural(added, "пост", "поста", "постов")}" else "Уже в загрузках")
        picked.clear()
    }

    fun favPicked() {
        val local = picked.values.filter { !it.source.favs }
        if (local.isNotEmpty()) like(local, true)
        val list = picked.values.filter { it.source.favs && !it.isFav }
        if (list.isEmpty()) {
            picked.clear()
            say(if (local.isNotEmpty()) "В лайках: ${local.size}" else "Уже в избранном"); return
        }
        if (list.any { if (it.source == Site.E621) user == null else logins[it.site] == null }) { say("Избранное — после входа на сайт"); return }
        picked.clear()
        viewModelScope.launch {
            var done = 0
            for (p in list) {
                try { E621.favorite(p.site, p.id, true); done++ } catch (_: Exception) {}
                for (f in listOf(main, favs, danFavs)) {
                    val i = f.posts.indexOfFirst { it.key == p.key }
                    if (i >= 0) f.posts[i] = f.posts[i].copy(isFav = true)
                }
            }
            say("В избранном: ${done + local.size}")
        }
    }

    // Rebuilt whenever the core answers with new collections (showColls).
    private val collFeeds = HashMap<Long, Feed>()

    /** A collection as a feed the grid and the viewer can show; it never loads. */
    fun collFeed(id: Long): Feed? {
        val c = colls.firstOrNull { it.id == id } ?: return null
        return collFeeds.getOrPut(id) {
            Feed().apply { local = true; end = true; posts.addAll(c.posts) }
        }
    }

    // --- 1i: the blacklist (the core matches it: crates/core/src/blacklist.rs) ----------

    val blacklist = mutableStateListOf<String>().apply {
        addAll(prefs.getString("blacklist", "")!!.split('\n').filter { it.isNotBlank() })
    }
    var blockHide by mutableStateOf(prefs.getBoolean("blockHide", true))
        private set
    var blockInSearch by mutableStateOf(prefs.getBoolean("blockInSearch", true))
        private set
    var blockSync by mutableStateOf(prefs.getBoolean("blockSync", false))
        private set

    private fun persistBlocks() {
        prefs.edit().putString("blacklist", blacklist.joinToString("\n")).apply()
        val lines = JSONArray(blacklist.toList())
        viewModelScope.launch {
            // The new rules, then the posts already on screen marked again.
            val shown = listOf(main, favs).flatMap { it.posts.toList() }
            val marks = withContext(Dispatchers.IO) {
                runCatching { Core.request("blacklist", "lines" to lines) }
                val flags = runCatching { Core.request("blocked", "posts" to JSONArray(shown.map { it.toJson() })) }.getOrNull() as? JSONArray
                shown.indices.associate { i -> shown[i].key to (flags?.optBoolean(i) ?: shown[i].blocked) }
            }
            for (f in listOf(main, favs)) f.posts.replaceAll { p -> marks[p.key]?.let { p.copy(blocked = it) } ?: p }
        }
    }

    fun addBlock(rule: String) {
        val r = rule.trim().lowercase().replace(Regex("\\s+"), " ")
        if (r.isEmpty() || r in blacklist) return
        blacklist.add(r); persistBlocks()
    }

    fun removeBlock(rule: String) { blacklist.remove(rule); persistBlocks() }

    fun chooseBlockHide(on: Boolean) { blockHide = on; prefs.edit().putBoolean("blockHide", on).apply() }

    fun chooseBlockInSearch(on: Boolean) {
        blockInSearch = on; prefs.edit().putBoolean("blockInSearch", on).apply()
        main.posts.clear(); main.error = null
    }

    fun chooseBlockSync(on: Boolean) {
        if (on && user == null) { say("Сначала войдите в e621"); return }
        blockSync = on; prefs.edit().putBoolean("blockSync", on).apply()
        if (on) syncBlacklist(loud = true)
    }

    private fun syncBlacklist(loud: Boolean) {
        val u = user ?: return
        viewModelScope.launch {
            try {
                val rules = E621.accountBlacklist(u)
                val before = blacklist.size
                for (r in rules) addBlock(r)
                if (loud) say("С аккаунта: ${blacklist.size - before} ${plural(blacklist.size - before, "новое правило", "новых правила", "новых правил")}")
            } catch (e: Exception) {
                if (loud) say(humanError(e))
            }
        }
    }

    fun isBlocked(p: Post) = p.blocked

    /** Plain single tags the core adds as minus-tags where a site has room (e621 takes 40 tags in all). */
    private fun blockTags(): List<String> =
        if (!blockInSearch) emptyList() else blacklist
            .filter { ' ' !in it && '*' !in it && !it.startsWith("-") && ':' !in it }
            .take(12)

    // The login form's fields, so the WebView can fill them in.
    var draftUser by mutableStateOf("")
    var draftKey by mutableStateOf("")

    init {
        val u = prefs.getString("user", null)
        val k = prefs.getString("key", null)
        if (!u.isNullOrBlank() && !k.isNullOrBlank()) {
            E621.auth(u, k)
            user = u
            favs.tags = "fav:$u"
        }
        draftUser = u.orEmpty()
        for (s in Site.entries) if (s != Site.E621) {
            val lu = prefs.getString("login.${s.code}.user", null)
            val lk = prefs.getString("login.${s.code}.key", null)
            if (!lu.isNullOrBlank() && !lk.isNullOrBlank()) logins[s.code] = lu to lk
        }
        pushLogins()
        main.site = site
        restoreTabs()
        recent.addAll(prefs.getString("recent", "")!!.split('\n').filter { it.isNotBlank() })
        viewModelScope.launch {
            val (s, c) = withContext(Dispatchers.IO) {
                fun list(v: Any?) = v as? JSONArray ?: JSONArray()
                Core.request("blacklist", "lines" to JSONArray(blacklist.toList()))
                // Lists kept in prefs before the core existed move into it, once.
                val oldColls = app.getSharedPreferences("collections", Context.MODE_PRIVATE).getString("colls", null)
                    ?: prefs.getString("colls", null)
                savedFrom(list(Core.request("saved_import", "list" to JSONArray(prefs.getString("saved", null) ?: "[]")))) to
                    collsFrom(list(Core.request("colls_import", "list" to JSONArray(oldColls ?: "[]"))))
            }
            showSaved(s)
            showColls(c)
            prefs.edit().remove("saved").remove("colls").apply()
            app.getSharedPreferences("collections", Context.MODE_PRIVATE).edit().clear().apply()
            if (prefs.getBoolean("blockSync", false) && user != null) syncBlacklist(loud = false)
        }
    }

    // --- search tabs ----------------------------------------------------------------------

    private fun restoreTabs() {
        val arr = runCatching { JSONArray(prefs.getString("tabs", "[]")) }.getOrDefault(JSONArray())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            feedTabs.add(Feed(o.optString("tags")).apply {
                site = o.optString("site", "e621"); order = o.optString("order", "order:hot")
                // Tabs saved before each kept its own mix take the shared one.
                val m = o.optJSONArray("mix")
                mix.addAll(if (m != null) (0 until m.length()).map { m.getString(it) } else defaultMix())
            })
        }
        if (feedTabs.isEmpty()) {
            main.mix.addAll(defaultMix())
            feedTabs.add(main)
        } else {
            main = feedTabs[prefs.getInt("tabIndex", 0).coerceIn(0, feedTabs.lastIndex)]
            site = main.site
        }
        showTokens(main.tags)
    }

    private fun saveTabs() {
        val arr = JSONArray()
        for (f in feedTabs) arr.put(JSONObject().put("tags", f.tags).put("site", f.site).put("order", f.order).put("mix", JSONArray(f.mix.toList())))
        prefs.edit().putString("tabs", arr.toString()).putInt("tabIndex", feedTabs.indexOfFirst { it === main }.coerceAtLeast(0)).putString("site", main.site).apply()
    }

    /** Shows another tab; back's history belongs to the tab it was made in, so it goes. */
    fun switchTab(i: Int) {
        val f = feedTabs.getOrNull(i) ?: return
        viewer = null; under.clear(); page = null; tab = Tab.Feed
        if (f === main) return
        main.job?.cancel(); main.loading = false
        main = f
        site = f.site
        history.clear(); backDepth = 0
        showTokens(f.tags)
        saveTabs()
    }

    /** A new tab on the same source, its query to be typed. */
    fun newTab() {
        val f = Feed().apply { site = main.site; order = main.order; mix.addAll(main.mix) }
        feedTabs.add(feedTabs.indexOfFirst { it === main } + 1, f)
        switchTab(feedTabs.indexOfFirst { it === f })
        tab = Tab.Search
    }

    fun closeTab(i: Int) {
        val f = feedTabs.getOrNull(i) ?: return
        if (feedTabs.size == 1) { search(""); return }
        f.job?.cancel()
        val wasMain = f === main
        feedTabs.removeAt(i)
        if (wasMain) switchTab(i.coerceAtMost(feedTabs.lastIndex)) else saveTabs()
    }

    // --- notifications ----------------------------------------------------------------

    var notify by mutableStateOf(prefs.getBoolean("notify", false))
        private set

    fun chooseNotify(on: Boolean) {
        notify = on
        prefs.edit().putBoolean("notify", on).apply()
        Watch.schedule(getApplication(), on)
        say(if (on) "Проверка раз в час — по запросам со слежкой" else "Уведомления выключены")
    }

    /** A notification's saved query, opened once the list is in. */
    private var pendingSaved: Long? = null

    fun openSavedById(id: Long) {
        val q = saved.firstOrNull { it.id == id }
        if (q != null) openSaved(q) else pendingSaved = id
    }

    fun say(text: String) {
        toast = text
        toastJob?.cancel()
        toastJob = viewModelScope.launch { delay(2600); toast = null }
    }

    private fun ratingTag(): String = when (ratings.size) {
        1 -> "rating:${ratings.first()}"
        2 -> "-rating:" + listOf("s", "q", "e").first { it !in ratings }
        else -> ""
    }

    // A sort the person typed wins over the tab.
    private fun query(feed: Feed): String {
        // The best-of tab with tags typed: the best of those, by score.
        val order = (if (feed.order.startsWith(BEST)) "order:score" else feed.order).takeIf { "order:" !in feed.tags }.orEmpty()
        return listOf(feed.tags, order, ratingTag()).filter { it.isNotBlank() }.joinToString(" ")
    }

    fun setOrder(feed: Feed, order: String) {
        if (feed.order == order && order != "order:random") return
        feed.order = order
        load(feed, reset = true)
    }

    /** Whether the post panel is unfolded; kept, so a folded panel stays folded. */
    var panelOpen by mutableStateOf(prefs.getBoolean("panelOpen", true))
        private set

    fun choosePanelOpen(on: Boolean) {
        panelOpen = on
        prefs.edit().putBoolean("panelOpen", on).apply()
    }

    var columns by mutableStateOf(prefs.getInt("columns", 3))
        private set

    /** How far a tap on a video's side jumps, in seconds: 5, 10 or 15. */
    var seekStep by mutableStateOf(prefs.getInt("seekStep", 10))
        private set

    fun chooseSeekStep(s: Int) {
        seekStep = s
        prefs.edit().putInt("seekStep", s).apply()
    }

    fun chooseColumns(n: Int) {
        columns = n
        prefs.edit().putInt("columns", n).apply()
    }

    fun load(feed: Feed, reset: Boolean) {
        if (feed.local) return
        if (!reset && (feed.loading || feed.end)) return
        feed.job?.cancel()
        if (reset) {
            feed.page = 1; feed.end = false
        }
        feed.loading = true
        feed.error = null
        if (reset && feedTabs.any { it === feed }) saveTabs()
        feed.job = viewModelScope.launch {
            try {
                val ids = feed.ids
                val got = when {
                    ids != null -> E621.byIds(feed.site, ids.drop((feed.page - 1) * PAGE).take(PAGE)).also {
                        if (feed.page * PAGE >= ids.size) feed.end = true
                    }
                    feed.order.startsWith(BEST) && feed.tags.isBlank() && hasBest(feed.site) ->
                        E621.popular(feed.site, feed.order.removePrefix(BEST), feed.page)
                    feed.site == MIX -> E621.postsMix(feed.mix.toList().ifEmpty { defaultMix() }, query(feed), blockTags(), feed.page)
                    else -> E621.posts(feed.site, query(feed), blockTags(), feed.page)
                }
                if (reset) {
                    feed.posts.clear()
                    feed.grid.scrollToItem(0)
                }
                val seen = feed.posts.mapTo(HashSet()) { it.key }
                val hearted = feed === danFavs
                feed.posts.addAll(got.filter { it.key !in seen && !(blockHide && isBlocked(it)) }.map { if (hearted) it.copy(isFav = true) else it })
                feed.page++
                if (got.isEmpty() && ids == null) feed.end = true
            } catch (e: CloudflareChallenge) {
                feed.error = "Cloudflare просит проверку браузера"
                openCloudflare()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                feed.error = humanError(e)
            } finally {
                feed.loading = false
            }
        }
    }

    fun loadMore(feed: Feed) {
        if (feed.error == null) load(feed, reset = false)
    }

    /** Where the person was before a search took them elsewhere: the feed as it was, and what lay open over it. */
    private class Spot(
        val tab: Tab, val page: Page?, val viewer: Pair<Feed, Int>?,
        /** When the viewer was over a collection: which one, since its feed is rebuilt on every change. */
        val coll: Long?,
        val tags: String, val order: String, val posts: List<Post>, val pageNo: Int, val end: Boolean,
        val first: Int, val offset: Int,
        val under: List<Opened>,
        val site: String,
    )

    /** A viewer left open under another: its feed, its page, whether its comments were up. */
    class Opened(val feed: Feed, val page: Int, val comments: Boolean)

    /** Viewers under the one on screen, as a post opened from another (an avatar, say) leaves them. */
    private val under = ArrayList<Opened>()

    /** Set when back returns to a viewer whose comments were open; that viewer takes it once. */
    var reopenComments = false

    private val history = ArrayDeque<Spot>()

    /** How many searches back can undo; the back gesture walks them before it leaves the app. */
    var backDepth by mutableStateOf(0)
        private set

    /** The viewer's page now; it moves with every swipe, [viewer] keeps where it opened. */
    var viewerPage = 0

    private fun keepSpot() {
        if (main.posts.isEmpty() && main.tags.isBlank()) return
        history.addLast(Spot(
            // The post on screen, not the one the viewer was opened at.
            tab, page, viewer?.let { it.first to viewerPage },
            viewer?.first?.let { f -> collFeeds.entries.firstOrNull { it.value === f }?.key },
            main.tags, main.order, main.posts.toList(), main.page, main.end,
            main.grid.firstVisibleItemIndex, main.grid.firstVisibleItemScrollOffset,
            under.toList(),
            site,
        ))
        while (history.size > 30) history.removeFirst()
        backDepth = history.size
    }

    /** Back to before the last search: the same posts, scrolled where they were, the post still open. */
    fun goBack() {
        val s = history.removeLastOrNull() ?: return
        backDepth = history.size
        main.job?.cancel()
        main.loading = false
        main.error = null
        main.tags = s.tags
        main.order = s.order
        main.posts.clear(); main.posts.addAll(s.posts)
        main.page = s.pageNo
        main.end = s.end
        if (s.site != site) moveTo(s.site)
        showTokens(s.tags)
        tab = s.tab
        page = s.page
        viewer = when {
            s.coll != null -> s.viewer?.let { (_, i) -> collFeed(s.coll)?.let { it to i.coerceIn(0, (it.posts.size - 1).coerceAtLeast(0)) } }
            else -> s.viewer
        }?.takeIf { it.first.posts.isNotEmpty() }
        under.clear(); if (viewer != null) under.addAll(s.under)
        viewModelScope.launch { runCatching { main.grid.scrollToItem(s.first, s.offset) } }
    }

    private fun showTokens(t: String) {
        searchTokens.clear()
        searchTokens.addAll(t.split(' ').filter { it.isNotBlank() && it !in filterWords })
    }

    /**
     * The main feed, searching [tags]; [on] moves it to that site first (a
     * saved query's, a tapped tag's post's), unless [keepMix] and the feed is mixed.
     */
    fun search(tags: String, on: String? = null, keepMix: Boolean = false) {
        val t = tags.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        val move = on != null && on != site && !(keepMix && site == MIX)
        if (move || t != main.tags || tab != Tab.Feed || viewer != null || page != null) keepSpot()
        if (move) moveTo(on!!)
        main.tags = t
        // The search screen shows what the feed searches, minus its filter cards.
        showTokens(t)
        if (t.isNotBlank()) {
            recent.remove(t); recent.add(0, t)
            while (recent.size > 20) recent.removeAt(recent.lastIndex)
            prefs.edit().putString("recent", recent.joinToString("\n")).apply()
        }
        viewer = null
        under.clear()
        page = null
        tab = Tab.Feed
        load(main, reset = true)
    }

    fun forgetRecent() {
        recent.clear(); prefs.edit().remove("recent").apply()
    }

    fun open(feed: Feed, index: Int) {
        if (picking) { feed.posts.getOrNull(index)?.let(::togglePick); return }
        under.clear()
        viewer = feed to index
    }

    /** One post over the viewer, back returning to where it was. */
    fun openPost(p: Post, fromComments: Boolean) {
        viewer?.let { under.add(Opened(it.first, viewerPage, fromComments)) }
        while (under.size > 20) under.removeAt(0)
        viewer = Feed().apply { posts.add(p); local = true; end = true } to 0
    }

    /** Posts over the viewer, at [index], back returning to the post under them. */
    fun openOver(feed: Feed, index: Int) {
        viewer?.let { under.add(Opened(it.first, viewerPage, false)) }
        while (under.size > 20) under.removeAt(0)
        viewer = feed to index
    }

    /** A pool from a post: read on from that post, or see all its pages. */
    fun openPool(pool: Pool, from: Post, read: Boolean) {
        val feed = Feed().apply { site = from.site; ids = pool.posts; order = "" }
        val at = pool.posts.indexOf(from.id).coerceAtLeast(0)
        val n = pool.posts.size
        val title = pool.name
        val note = "$n ${plural(n, "пост", "поста", "постов")}"
        viewModelScope.launch {
            try {
                // The pages up to the post, so the viewer can open on it.
                while (!feed.end && feed.posts.size <= (if (read) at else 0)) {
                    val got = E621.byIds(feed.site, pool.posts.drop((feed.page - 1) * PAGE).take(PAGE))
                    feed.posts.addAll(got.filter { !(blockHide && isBlocked(it)) })
                    if (feed.page * PAGE >= n) feed.end = true
                    feed.page++
                }
            } catch (e: Exception) {
                say(humanError(e)); return@launch
            }
            if (read) {
                val i = feed.posts.indexOfFirst { it.id == from.id }.coerceAtLeast(0)
                if (feed.posts.isNotEmpty()) openOver(feed, i)
            } else {
                under.clear(); viewer = null
                page = Page.Posts(title, note, feed)
            }
        }
    }

    /** Back in the viewer: to the viewer under it, or out to the feed. */
    fun closeViewer() {
        val o = under.removeLastOrNull()
        reopenComments = o?.comments == true
        viewer = o?.let { it.feed to it.page.coerceIn(0, (it.feed.posts.size - 1).coerceAtLeast(0)) }?.takeIf { it.first.posts.isNotEmpty() }
        if (viewer == null) under.clear()
    }

    fun setRating(r: String, on: Boolean) = chooseRatings(if (on) ratings + r else ratings - r)

    fun chooseRatings(next: Set<String>) {
        if (next.isEmpty()) return
        ratings = next
        prefs.edit().putStringSet("ratings", next).apply()
        main.posts.clear(); favs.posts.clear(); danFavs.posts.clear()
        main.error = null; favs.error = null; danFavs.error = null
    }

    fun toggleOriginal(on: Boolean) {
        original = on
        prefs.edit().putBoolean("original", on).apply()
    }

    // --- account ----------------------------------------------------------------

    var signingIn by mutableStateOf(false)
        private set
    var loginError by mutableStateOf<String?>(null)

    fun login() {
        val u = draftUser.trim()
        val k = draftKey.trim()
        if (u.isEmpty() || k.isEmpty()) {
            loginError = "Нужны имя и API-ключ"; return
        }
        signingIn = true
        loginError = null
        viewModelScope.launch {
            try {
                E621.check("e621", u, k)
                E621.auth(u, k)
                prefs.edit().putString("user", u).putString("key", k).apply()
                user = u
                draftKey = ""
                favs.tags = "fav:$u"; favs.posts.clear(); favs.error = null
                main.posts.clear(); main.error = null
                say("Вы вошли как $u")
            } catch (e: CloudflareChallenge) {
                loginError = "Сначала пройдите проверку Cloudflare"
                openCloudflare()
            } catch (e: Exception) {
                loginError = humanError(e)
            } finally {
                signingIn = false
            }
        }
    }

    fun logout() {
        E621.auth(null, null)
        prefs.edit().remove("user").remove("key").apply()
        user = null
        favs.posts.clear(); favs.tags = ""
        main.posts.clear(); main.error = null
        say("Вы вышли")
    }

    // --- the WebView ---------------------------------------------------------------

    fun openCloudflare() {
        if (web == null) web = Web(E621.BASE + "/posts", WebPurpose.Cloudflare)
    }

    fun openKeyPage() {
        web = Web(E621.BASE + "/session/new", WebPurpose.ApiKey)
    }

    fun closeWeb(solved: Boolean) {
        val was = web
        web = null
        CookieManager.getInstance().flush()
        if (was?.purpose == WebPurpose.Cloudflare && solved) {
            say("Проверка пройдена")
            for (f in listOf(main, favs)) if (f.error != null || f.posts.isEmpty()) {
                if (f === favs && user == null) continue
                load(f, reset = f.posts.isEmpty())
            }
        }
    }

    fun foundKey(name: String?, key: String) {
        if (!name.isNullOrBlank()) draftUser = name
        draftKey = key
        web = null
        tab = Tab.More
        page = Page.Settings
        say("Ключ найден — нажмите «Войти»")
    }

    // --- post actions ----------------------------------------------------------------

    // --- favourites and likes ----------------------------------------------------------

    /** Hearted: in the site's favourites, or liked on the phone where the site keeps none. */
    fun faved(p: Post) = p.isFav || (!p.source.favs && p.key in likeKeys)

    fun chooseFavSite(code: String) {
        favSite = code
        prefs.edit().putString("favSite", code).apply()
    }

    private val likeFeeds = HashMap<String, Feed>()
    private val nobody = Feed().apply { local = true; end = true }

    /** The favourites page's feed for a site; null when the site needs a login first. */
    fun favFeed(code: String): Feed? = when {
        // Read through `likes`, so the page follows every like.
        !Site.of(code).favs -> likes.size.let { likeFeeds.getOrPut(code) { Feed().apply { local = true; end = true; site = code; posts.addAll(likes.filter { it.site == code }) } } }
        code == "e621" -> favs.takeIf { user != null }
        else -> logins[code]?.first?.let { name ->
            danFavs.also { if (it.tags != "ordfav:$name") { it.tags = "ordfav:$name"; it.posts.clear(); it.error = null } }
        }
    }

    /** An empty feed, for a site not signed in to. */
    fun noFeed(): Feed = nobody

    /** Likes on the phone: [on] adds the posts to them, else takes them out. */
    fun like(posts: List<Post>, on: Boolean) {
        for (p in posts) {
            if (on && p.key !in likeKeys) {
                likes.add(0, p.copy(isFav = true, blocked = false)); likeKeys[p.key] = true
            } else if (!on && likeKeys.remove(p.key) != null) {
                likes.removeAll { it.key == p.key }
            }
        }
        likeFeeds.clear()
        val all = JSONArray(likes.map { it.toJson() })
        viewModelScope.launch(Dispatchers.IO) { prefs.edit().putString("likes", all.toString()).apply() }
    }

    fun toggleFav(feed: Feed, index: Int) {
        val p = feed.posts.getOrNull(index) ?: return
        val s = p.source
        if (!s.favs) {
            val on = p.key !in likeKeys
            like(listOf(p), on)
            feed.posts[index] = p.copy(isFav = on)
            return
        }
        if (if (s == Site.E621) user == null else logins[s.code] == null) {
            say("Избранное — после входа в ${s.title}"); return
        }
        val on = !p.isFav
        feed.posts[index] = p.copy(isFav = on, favCount = p.favCount + if (on) 1 else -1)
        viewModelScope.launch {
            try {
                E621.favorite(p.site, p.id, on)
                val list = if (s == Site.E621) favs else danFavs
                if (feed !== list && list.posts.isNotEmpty()) {
                    list.posts.removeAll { it.key == p.key }
                    if (on) list.posts.add(0, p.copy(isFav = true, favCount = p.favCount + 1))
                }
            } catch (e: Exception) {
                val i = feed.posts.indexOfFirst { it.key == p.key }
                if (i >= 0) feed.posts[i] = p
                if (e is CloudflareChallenge) openCloudflare() else say(e.message ?: "Не вышло")
            }
        }
    }

    fun download(p: Post) {
        if (p.fileUrl == null) { say("У поста нет файла"); return }
        if (Downloads.add(p)) say("Скачиваю #${p.id}") else say("Уже в загрузках")
    }
}
