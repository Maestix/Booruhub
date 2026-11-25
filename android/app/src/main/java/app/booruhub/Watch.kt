package app.booruhub

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * New posts in watched saved queries, while the app is closed: the system
 * runs [WatchJob] about once an hour when there is a network, the core counts
 * what is new (as the saved-queries screen does), and each query with more
 * than last time gets a notification that opens it.
 */
object Watch {
    private const val CHANNEL = "watch"
    private const val JOB = 7021
    const val EXTRA_SAVED = "saved"

    fun schedule(ctx: Context, on: Boolean) {
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        if (!on) { js.cancel(JOB); return }
        js.schedule(
            JobInfo.Builder(JOB, ComponentName(ctx, WatchJob::class.java))
                .setPeriodic(TimeUnit.HOURS.toMillis(1), TimeUnit.MINUTES.toMillis(20))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build(),
        )
    }

    fun allowed(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Signs the core in as the app would, from what the app keeps. */
    private fun signIn(ctx: Context) {
        val prefs = ctx.getSharedPreferences("booruhub", Context.MODE_PRIVATE)
        val u = prefs.getString("user", null)
        val k = prefs.getString("key", null)
        if (Core.login == null && !u.isNullOrBlank() && !k.isNullOrBlank()) Core.login = u to k
        if (Core.logins.isEmpty()) Core.logins = Site.entries.filter { it != Site.E621 }.mapNotNull { s ->
            val lu = prefs.getString("login.${s.code}.user", null)
            val lk = prefs.getString("login.${s.code}.key", null)
            if (lu.isNullOrBlank() || lk.isNullOrBlank()) null else s.code to (lu to lk)
        }.toMap()
    }

    suspend fun check(ctx: Context) {
        val prefs = ctx.getSharedPreferences("booruhub", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("notify", false)) return
        signIn(ctx)
        val ratings = prefs.getStringSet("ratings", setOf("s", "q", "e"))!!
        val rating = when (ratings.size) {
            1 -> "rating:${ratings.first()}"
            2 -> "-rating:" + listOf("s", "q", "e").first { it !in ratings }
            else -> ""
        }
        val list = savedFrom(Core.ask("saved_check", "rating" to rating) as? JSONArray ?: return)
        val seen = ctx.getSharedPreferences("watch", Context.MODE_PRIVATE)
        val edit = seen.edit()
        for (q in list.filter { it.watch }) {
            val before = seen.getInt("n.${q.id}", 0)
            if (q.fresh > before) notify(ctx, q)
            edit.putInt("n.${q.id}", q.fresh)
        }
        edit.apply()
    }

    private fun notify(ctx: Context, q: Saved) {
        if (!allowed(ctx)) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Новые посты по запросам", NotificationManager.IMPORTANCE_DEFAULT))
        val open = Intent(ctx, MainActivity::class.java).putExtra(EXTRA_SAVED, q.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(ctx, q.id.toInt(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = q.fresh
        val note = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(q.name)
            .setContentText("${if (n >= 100) "100+" else "$n"} ${plural(n, "новый пост", "новых поста", "новых постов")} · ${Site.of(q.site).title}")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(q.id.toInt(), note) }
    }
}

class WatchJob : JobService() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartJob(params: JobParameters): Boolean {
        scope.launch {
            try {
                Watch.check(applicationContext)
            } catch (e: Exception) {
                android.util.Log.w("booruhub", "watch failed", e)
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
