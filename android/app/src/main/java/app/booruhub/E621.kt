package app.booruhub

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@Immutable
data class Post(
    val id: Long,
    val previewUrl: String?,
    val sampleUrl: String?,
    val fileUrl: String?,
    val videoUrl: String?,
    val ext: String,
    val width: Int,
    val height: Int,
    val size: Long,
    val rating: String,
    val score: Int,
    val favCount: Int,
    val tags: Map<String, List<String>>,
    val isFav: Boolean,
    /** Set by the core's blacklist when the post was fetched. */
    val blocked: Boolean = false,
    val comments: Int = 0,
    /** The uploader's description, DText. */
    val about: String = "",
    /** Which booru it is from ([Site.code]); ids repeat across sites. */
    val site: String = "e621",
    /** The post this one is a version or a page of; 0 for none. */
    val parent: Long = 0,
    val hasChildren: Boolean = false,
    val hasNotes: Boolean = false,
    /** e621's pools the post is in. */
    val pools: List<Long> = emptyList(),
) {
    val inFamily get() = parent > 0 || hasChildren

    val source: Site get() = Site.of(site)

    /** Unique across sites, for lists and collections. */
    val key: String get() = "$site:$id"

    /** Unique across sites as a number, for the download queue. */
    val uid: Long get() = (source.ordinal.toLong() shl 40) or id

    val isVideo get() = ext == "webm" || ext == "mp4"
    val isGif get() = ext == "gif"

    /** What the grid shows: the sample when it is a still, else the preview. */
    val thumbUrl: String?
        get() = sampleUrl?.takeIf { !it.endsWith(".webm") && !it.endsWith(".mp4") } ?: previewUrl

    /** What the viewer shows for a still. Animated gifs need the file itself. */
    fun viewUrl(original: Boolean): String? = when {
        isGif || original -> fileUrl ?: sampleUrl
        else -> sampleUrl ?: fileUrl
    }

    val webUrl get() = source.postUrl(id)

    val allTags: List<String> get() = tags.values.flatten()

    /** Width over height, or 0 when e621 gave no size. */
    val ratio: Float get() = if (width > 0 && height > 0) width.toFloat() / height else 0f
}

@Immutable
data class Comment(
    val id: Long, val author: String, val body: String, val score: Int, val at: String, val user: Long = 0,
    /** A picture of the author where the site has one that is not a post (Gelbooru). */
    val avatar: String? = null,
)

@Immutable
data class TagHint(val name: String, val count: Int, val category: Int)

/** A note on a picture, in the file's own pixels. */
@Immutable
data class Note(val x: Int, val y: Int, val w: Int, val h: Int, val body: String)

/** A comic or a series: its posts in reading order. */
@Immutable
data class Pool(val id: Long, val name: String, val posts: List<Long>)

/** What a tag means, and the tags seen with it. */
@Immutable
data class Wiki(val title: String, val body: String, val links: List<String>, val related: List<String>, val count: Int)

/** One of an e621 account's sets. */
@Immutable
data class PostSet(val id: Long, val name: String, val short: String, val posts: List<Long>)

private fun longs(a: JSONArray?): List<Long> = if (a == null) emptyList() else (0 until a.length()).map { a.optLong(it) }
private fun strs(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }

/**
 * e621, through the Rust core (crates/core/src/e621.rs): the requests, both
 * shapes of the answers and the blacklist all live there. This only names the
 * calls and moves them off the main thread.
 */
object E621 {
    const val BASE = "https://e621.net"

    val TAG_ORDER = listOf("artist", "copyright", "character", "species", "general", "lore", "meta", "invalid")

    /** The key under which flat tags wait for their categories. */
    const val FLAT = "_flat"

    /** A core call and the reading of its answer, both off the main thread. */
    private suspend fun <T> call(m: String, vararg args: Pair<String, Any?>, read: (Any?) -> T): T =
        withContext(Dispatchers.IO) { read(Core.ask(m, *args)) }

    /** Signs every later call in (or out, with nulls). */
    fun auth(user: String?, key: String?) {
        Core.login = if (!user.isNullOrBlank() && !key.isNullOrBlank()) user to key else null
    }

    /** A post's comments, oldest first. */
    suspend fun comments(site: String, post: Long): List<Comment> = call("comments", "site" to site, "post" to post) { ok ->
        val arr = ok as? JSONArray ?: return@call emptyList()
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Comment(o.optLong("id"), o.optString("author"), o.optString("body"), o.optInt("score"), o.optString("at"), o.optLong("user"), o.str("avatar"))
        }
    }

    /** The posts these accounts picked as avatars, by account id. */
    suspend fun avatars(users: List<Long>): Map<Long, Post> = call("avatars", "users" to JSONArray(users)) { ok ->
        val o = ok as? JSONObject ?: return@call emptyMap()
        o.keys().asSequence().mapNotNull { k ->
            val id = k.toLongOrNull() ?: return@mapNotNull null
            runCatching { id to postFromJson(o.getJSONObject(k)) }.getOrNull()
        }.toMap()
    }

    private fun postList(ok: Any?): List<Post> {
        val arr = ok as? JSONArray ?: return emptyList()
        return (0 until arr.length()).mapNotNull { runCatching { postFromJson(arr.getJSONObject(it)) }.getOrNull() }
    }

    /** A page of a query on one site; [exclude] are tags to leave out where the site has room for them. */
    suspend fun posts(site: String, tags: String, exclude: List<String>, page: Int, limit: Int = 60): List<Post> =
        call("posts", "site" to site, "tags" to tags, "exclude" to JSONArray(exclude), "page" to page, "limit" to limit, read = ::postList)

    /** A page of a query on several sites at once, taken in turn. */
    suspend fun postsMix(sites: List<String>, tags: String, exclude: List<String>, page: Int, limit: Int = 60): List<Post> =
        call("posts_mix", "sites" to JSONArray(sites), "tags" to tags, "exclude" to JSONArray(exclude), "page" to page, "limit" to limit, read = ::postList)

    /** Throws unless the site accepts the pair. */
    suspend fun check(site: String, user: String, key: String) = call("check", "site" to site, "user" to user, "key" to key) {}

    suspend fun favorite(site: String, id: Long, on: Boolean) = call("favorite", "site" to site, "id" to id, "on" to on) {}

    suspend fun autocomplete(site: String, prefix: String): List<TagHint> = call("autocomplete", "site" to site, "prefix" to prefix) { ok ->
        val arr = ok as? JSONArray ?: return@call emptyList()
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            TagHint(o.optString("name"), o.optInt("count"), o.optInt("category"))
        }
    }

    suspend fun accountBlacklist(user: String): List<String> = call("account_blacklist", "user" to user) { ok ->
        val arr = ok as? JSONArray ?: return@call emptyList()
        (0 until arr.length()).map { arr.getString(it) }
    }

    /** The post's parent and its children, the post among them, parent first. */
    suspend fun family(p: Post): List<Post> = call("family", "site" to p.site, "post" to p.id, "parent" to p.parent, read = ::postList)

    /** The pools a post is in. */
    suspend fun pools(p: Post): List<Pool> = call("pools", "site" to p.site, "post" to p.id, "ids" to JSONArray(p.pools)) { ok ->
        val arr = ok as? JSONArray ?: return@call emptyList()
        (0 until arr.length()).map { arr.getJSONObject(it) }.map { Pool(it.optLong("id"), it.optString("name"), longs(it.optJSONArray("posts"))) }
    }

    /** Posts by id, in the order given. */
    suspend fun byIds(site: String, ids: List<Long>): List<Post> = call("by_ids", "site" to site, "ids" to JSONArray(ids), read = ::postList)

    suspend fun notes(p: Post): List<Note> = call("notes", "site" to p.site, "post" to p.id) { ok ->
        val arr = ok as? JSONArray ?: return@call emptyList()
        (0 until arr.length()).map { arr.getJSONObject(it) }.map { Note(it.optInt("x"), it.optInt("y"), it.optInt("w"), it.optInt("h"), it.optString("body")) }
    }

    suspend fun wiki(site: String, tag: String): Wiki = call("wiki", "site" to site, "tag" to tag) { ok ->
        val o = ok as? JSONObject ?: JSONObject()
        Wiki(o.optString("title", tag), o.optString("body"), strs(o.optJSONArray("links")), strs(o.optJSONArray("related")), o.optInt("count"))
    }

    /** The most liked posts of the day, week or month. */
    suspend fun popular(site: String, scale: String, page: Int): List<Post> =
        call("popular", "site" to site, "scale" to scale, "page" to page, read = ::postList)

    suspend fun sets(user: String): List<PostSet> = call("sets", "user" to user) { ok ->
        val arr = ok as? JSONArray ?: return@call emptyList()
        (0 until arr.length()).map { arr.getJSONObject(it) }.map { PostSet(it.optLong("id"), it.optString("name"), it.optString("short"), longs(it.optJSONArray("posts"))) }
    }

    suspend fun setPost(set: Long, post: Long, on: Boolean) = call("set_post", "set" to set, "post" to post, "on" to on) {}

    /** Categories for a post's tags: the new posts.json gives them flat. */
    suspend fun categories(site: String, names: List<String>): Map<String, Int> = call("categories", "site" to site, "names" to JSONArray(names)) { ok ->
        val o = ok as? JSONObject ?: return@call emptyMap()
        o.keys().asSequence().associateWith { o.optInt(it) }
    }
}
