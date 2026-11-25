package app.booruhub

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/** A JSON string field, or null when it is missing, null or blank. */
fun JSONObject.str(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).ifBlank { null }

/** A saved query; with [watch] on, the app counts the posts newer than [seenId]. */
@Immutable
data class Saved(
    val id: Long,
    val name: String,
    val query: String,
    val watch: Boolean = true,
    val seenId: Long = 0,
    val fresh: Int = 0,
    val checkedAt: Long = 0,
    val site: String = "e621",
)

/** A collection kept on the phone: the posts themselves, so it opens offline. */
@Immutable
data class Coll(
    val id: Long,
    val name: String,
    val color: Int,
    val posts: List<Post> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** The design's source colours, for collection marks. */
val COLL_COLORS = listOf(0xFF8EA0FF, 0xFFC9A0F0, 0xFF9BD7A0, 0xFFF0A3C8, 0xFFF0C08E, 0xFF7FC8E8)

fun Post.toJson(): JSONObject = JSONObject().apply {
    put("id", id); put("site", site)
    putOpt("preview", previewUrl); putOpt("sample", sampleUrl); putOpt("file", fileUrl); putOpt("video", videoUrl)
    put("ext", ext); put("w", width); put("h", height); put("size", size)
    put("rating", rating); put("score", score); put("favs", favCount); put("fav", isFav)
    put("comments", comments); if (about.isNotEmpty()) put("about", about)
    if (parent > 0) put("parent", parent); if (hasChildren) put("children", true); if (hasNotes) put("notes", true)
    if (pools.isNotEmpty()) put("pools", JSONArray(pools))
    put("tags", JSONObject().apply { for ((k, v) in tags) put(k, JSONArray(v)) })
}

fun postFromJson(o: JSONObject): Post {
    val t = o.optJSONObject("tags") ?: JSONObject()
    val tags = LinkedHashMap<String, List<String>>()
    for (k in t.keys()) {
        val a = t.getJSONArray(k)
        tags[k] = (0 until a.length()).map { a.getString(it) }
    }
    return Post(
        id = o.getLong("id"), previewUrl = o.str("preview"), sampleUrl = o.str("sample"), fileUrl = o.str("file"), videoUrl = o.str("video"),
        ext = o.optString("ext"), width = o.optInt("w"), height = o.optInt("h"), size = o.optLong("size"),
        rating = o.optString("rating", "s"), score = o.optInt("score"), favCount = o.optInt("favs"),
        tags = tags, isFav = o.optBoolean("fav"), blocked = o.optBoolean("blocked"),
        comments = o.optInt("comments"), about = o.optString("about"), site = o.optString("site", "e621").ifBlank { "e621" },
        parent = o.optLong("parent"), hasChildren = o.optBoolean("children"), hasNotes = o.optBoolean("notes"),
        pools = o.optJSONArray("pools")?.let { a -> (0 until a.length()).map { a.optLong(it) } }.orEmpty(),
    )
}

fun savedFrom(a: JSONArray): List<Saved> = runCatching {
    (0 until a.length()).map { a.getJSONObject(it) }.map {
        Saved(it.getLong("id"), it.optString("name"), it.optString("query"), it.optBoolean("watch", true),
            it.optLong("seen"), it.optInt("fresh"), it.optLong("checked"), it.optString("site", "e621").ifBlank { "e621" })
    }
}.getOrDefault(emptyList())

fun collsFrom(a: JSONArray): List<Coll> = runCatching {
    (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
        val p = o.optJSONArray("posts") ?: JSONArray()
        Coll(o.getLong("id"), o.optString("name"), o.optInt("color"),
            (0 until p.length()).mapNotNull { runCatching { postFromJson(p.getJSONObject(it)) }.getOrNull() },
            o.optLong("updated"))
    }
}.getOrDefault(emptyList())

/** "2 мин назад", "вчера"… for the cards' mono meta lines. */
fun ago(ms: Long): String {
    if (ms <= 0) return "ещё не проверено"
    val m = (System.currentTimeMillis() - ms) / 60_000
    return when {
        m < 1 -> "только что"
        m < 60 -> "$m мин назад"
        m < 24 * 60 -> "${m / 60} ч назад"
        m < 48 * 60 -> "вчера"
        else -> "${m / (24 * 60)} дн назад"
    }
}
