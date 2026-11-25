package app.booruhub

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the app keeps, in one JSON file: settings, the blacklist, search
 * tabs, saved queries and collections; the API keys only when asked for.
 */
object Backup {
    private const val KIND = "booruhub-backup"

    /** Settings that are the account, not the app: left out unless keys are wanted. */
    private fun secret(k: String) = k == "user" || k == "key" || k.startsWith("login.")

    suspend fun write(ctx: Context, to: Uri, keys: Boolean): Int = withContext(Dispatchers.IO) {
        val prefs = ctx.getSharedPreferences("booruhub", Context.MODE_PRIVATE)
        val settings = JSONObject()
        for ((k, v) in prefs.all) {
            if (!keys && secret(k)) continue
            settings.put(k, when (v) {
                is String -> JSONObject().put("s", v)
                is Boolean -> JSONObject().put("b", v)
                is Int -> JSONObject().put("i", v)
                is Long -> JSONObject().put("l", v)
                is Float -> JSONObject().put("f", v.toDouble())
                is Set<*> -> JSONObject().put("set", JSONArray(v.map { it.toString() }))
                else -> continue
            })
        }
        val saved = Core.request("saved_list") as? JSONArray ?: JSONArray()
        val colls = Core.request("colls_list") as? JSONArray ?: JSONArray()
        val all = JSONObject()
            .put("kind", KIND).put("version", 1).put("app", BuildConfig.VERSION_NAME).put("at", System.currentTimeMillis())
            .put("settings", settings).put("saved", saved).put("colls", colls)
        ctx.contentResolver.openOutputStream(to, "wt")!!.use { it.write(all.toString().toByteArray()) }
        colls.length()
    }

    /** Puts a backup's contents in place of the app's own; the app starts again after. */
    suspend fun read(ctx: Context, from: Uri) = withContext(Dispatchers.IO) {
        val text = ctx.contentResolver.openInputStream(from)!!.use { it.readBytes().decodeToString() }
        val all = runCatching { JSONObject(text) }.getOrNull()?.takeIf { it.optString("kind") == KIND }
            ?: throw ApiError("Это не резервная копия BooruHub")
        val settings = all.optJSONObject("settings") ?: JSONObject()
        val prefs = ctx.getSharedPreferences("booruhub", Context.MODE_PRIVATE)
        // Keys the file does not carry stay as they are.
        val hasKeys = settings.keys().asSequence().any(::secret)
        val e = prefs.edit()
        for (k in prefs.all.keys) if (hasKeys || !secret(k)) e.remove(k)
        for (k in settings.keys()) {
            val v = settings.getJSONObject(k)
            when {
                v.has("s") -> e.putString(k, v.getString("s"))
                v.has("b") -> e.putBoolean(k, v.getBoolean("b"))
                v.has("i") -> e.putInt(k, v.getInt("i"))
                v.has("l") -> e.putLong(k, v.getLong("l"))
                v.has("f") -> e.putFloat(k, v.getDouble("f").toFloat())
                v.has("set") -> e.putStringSet(k, v.getJSONArray("set").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
            }
        }
        e.commit()
        Core.request("saved_replace", "list" to (all.optJSONArray("saved") ?: JSONArray()))
        Core.request("colls_replace", "list" to (all.optJSONArray("colls") ?: JSONArray()))
    }

    /** Starts the app afresh, so everything reads what was restored. */
    fun restart(ctx: Context) {
        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ctx.startActivity(intent)
        Runtime.getRuntime().exit(0)
    }
}
