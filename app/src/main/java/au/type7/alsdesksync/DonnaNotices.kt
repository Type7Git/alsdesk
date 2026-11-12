package au.type7.alsdesksync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Donna's short notices on the phone (and in Android Auto, which reads MessagingStyle notifications aloud
 * and takes spoken replies). Contract with the desk:
 *   GET  {deviceUrl}/notices          → { notices: [{id, at, title, text, kind, replyable}] }
 *   POST {deviceUrl}/notices/ack      { ids: [...] }
 *   POST {deviceUrl}/reply            { id, text } → { status, spoken }
 * A spoken reply can only answer Donna (questions, pencil confirms, reminders); it never sends anything
 * to another person. Messages to people stay as drafts on the desk for Al to review.
 */
object DonnaNotices {
    const val CHANNEL = "donna"
    private const val ONE_OFF = "notices-now"
    private const val PERIODIC = "notices-periodic"
    const val ACTION_REPLY = "au.type7.alsdesksync.NOTICE_REPLY"
    const val ACTION_READ = "au.type7.alsdesksync.NOTICE_READ"
    const val EXTRA_ID = "notice_id"
    const val KEY_TEXT = "donna_reply"
    const val DESK = "https://life-work-sidekick.lovable.app/desk"

    val donna: Person = Person.Builder().setName("Donna").setKey("donna").setImportant(true).build()
    val me: Person = Person.Builder().setName("Me").setKey("me").build()

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(ctx: Context) {
        ensureChannel(ctx)
        val req = PeriodicWorkRequestBuilder<NoticeWorker>(15, TimeUnit.MINUTES).setConstraints(online).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun cancel(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(PERIODIC)
        WorkManager.getInstance(ctx).cancelUniqueWork(ONE_OFF)
    }

    fun kick(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<NoticeWorker>().setConstraints(online).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(ONE_OFF, ExistingWorkPolicy.KEEP, req)
    }

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Donna", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Short notes and questions from Donna. Readable in Android Auto."
                }
            )
        }
    }

    fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Posts (or updates) one notice as a two-person conversation: Donna and Al. */
    fun show(ctx: Context, id: String, title: String, lines: List<Pair<Person, String>>, replyable: Boolean) {
        if (!canNotify(ctx)) return
        ensureChannel(ctx)
        val nid = id.hashCode()
        val style = NotificationCompat.MessagingStyle(me).setConversationTitle(title.take(60))
        val now = System.currentTimeMillis()
        lines.forEachIndexed { i, (who, text) -> style.addMessage(text.take(240), now - (lines.size - i) * 1000L, who) }

        val open = PendingIntent.getActivity(
            ctx, nid, Intent(Intent.ACTION_VIEW, Uri.parse(DESK)).setPackage(ctx.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val read = PendingIntent.getBroadcast(
            ctx, nid + 1, Intent(ctx, NoticeActionReceiver::class.java).setAction(ACTION_READ).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_donna)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .addAction(
                NotificationCompat.Action.Builder(R.drawable.ic_stat_donna, "Mark as read", read)
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                    .setShowsUserInterface(false)
                    .build()
            )
        if (replyable) {
            val reply = PendingIntent.getBroadcast(
                ctx, nid + 2, Intent(ctx, NoticeActionReceiver::class.java).setAction(ACTION_REPLY).putExtra(EXTRA_ID, id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            b.addAction(
                NotificationCompat.Action.Builder(R.drawable.ic_stat_donna, "Answer Donna", reply)
                    .addRemoteInput(RemoteInput.Builder(KEY_TEXT).setLabel("Answer Donna").build())
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                    .setShowsUserInterface(false)
                    .build()
            )
        }
        runCatching { NotificationManagerCompat.from(ctx).notify(nid, b.build()) }
    }

    data class Http(val code: Int, val body: String)

    fun call(url: String, method: String, json: String? = null): Http = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000; readTimeout = 20000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            if (json != null) { doOutput = true; setRequestProperty("Content-Type", "application/json") }
        }
        if (json != null) c.outputStream.use { it.write(json.toByteArray()) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText().take(50_000) } ?: ""
        c.disconnect()
        Http(code, text)
    } catch (e: Exception) {
        Http(-1, "")
    }
}

class NoticeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val store = Store.get(applicationContext)
        val base = store.deviceUrl?.trimEnd('/') ?: return Result.success()
        if (store.paused || !store.donnaNotices) return Result.success()
        val r = DonnaNotices.call("$base/notices", "GET")
        if (r.code !in 200..299) return Result.success() // desk not ready, link revoked or offline: try next time
        val notices = runCatching { JSONObject(r.body).optJSONArray("notices") ?: JSONArray() }.getOrDefault(JSONArray())
        val shown = JSONArray()
        for (i in 0 until minOf(notices.length(), 5)) {
            val n = notices.optJSONObject(i) ?: continue
            val id = n.optString("id").takeIf { it.isNotEmpty() && it.length <= 120 } ?: continue
            val title = n.optString("title").ifEmpty { "Donna" }
            val text = n.optString("text").takeIf { it.isNotEmpty() } ?: continue
            store.rememberNotice(id, title, text)
            DonnaNotices.show(applicationContext, id, title, listOf(DonnaNotices.donna to text), n.optBoolean("replyable"))
            shown.put(id)
        }
        if (shown.length() > 0) DonnaNotices.call("$base/notices/ack", "POST", JSONObject().put("ids", shown).toString())
        return Result.success()
    }
}

/** Handles "Answer Donna" (typed or spoken, e.g. in Android Auto) and "Mark as read". */
class NoticeActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra(DonnaNotices.EXTRA_ID) ?: return
        val nid = id.hashCode()
        if (intent.action == DonnaNotices.ACTION_READ) {
            NotificationManagerCompat.from(ctx).cancel(nid)
            return
        }
        if (intent.action != DonnaNotices.ACTION_REPLY) return
        val answer = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(DonnaNotices.KEY_TEXT)?.toString()?.trim()
        if (answer.isNullOrEmpty()) return
        val store = Store.get(ctx)
        val base = store.deviceUrl?.trimEnd('/') ?: return
        val (title, text) = store.notice(id) ?: ("Donna" to "")
        val pending = goAsync()
        Thread {
            try {
                val r = DonnaNotices.call("$base/reply", "POST", JSONObject().put("id", id).put("text", answer.take(500)).toString())
                // The desk always answers with a short "spoken" line (including "already sorted"); use it whenever present.
                val spoken = runCatching { JSONObject(r.body).optString("spoken") }.getOrNull()
                    ?.takeIf { it.isNotEmpty() && r.code > 0 }
                    ?: if (r.code in 200..299) "Done." else "I couldn't reach your desk just now. Try again when you're parked."
                val lines = buildList {
                    if (text.isNotEmpty()) add(DonnaNotices.donna to text)
                    add(DonnaNotices.me to answer.take(240))
                    add(DonnaNotices.donna to spoken.take(160))
                }
                DonnaNotices.show(ctx, id, title, lines, false)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
