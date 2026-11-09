package au.type7.alsdesksync

import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Sends queued messages to the desk in small batches. Contract (owner's desk):
 *   POST {deviceUrl}/messages  application/json
 *   { device: {model, app_version}, messages: [{key, source, chat_id, chat_title, is_group, sender,
 *     direction, text, sent_at, captured_via}] }
 *   200 → { accepted: [keys], duplicates: [keys] }   401/403/404 → link revoked, stop.
 * Keys make retries safe: the desk ignores a key it has already stored.
 */
class Uploader(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val store = Store.get(applicationContext)
        SmsReader.collect(applicationContext)
        val base = store.deviceUrl ?: return Result.success()
        if (store.paused) return Result.success()

        repeat(10) { // at most 10 batches per run
            val batch = store.pending(100)
            if (batch.isEmpty()) {
                store.lastResult = "Up to date · ${stamp()}"
                return Result.success()
            }
            val outcome = post(base.trimEnd('/') + "/messages", body(batch))
            when {
                outcome.code in 200..299 -> {
                    val done = HashSet<String>()
                    runCatching {
                        val j = JSONObject(outcome.body)
                        listOf("accepted", "duplicates").forEach { k ->
                            j.optJSONArray(k)?.let { a -> for (i in 0 until a.length()) done.add(a.getString(i)) }
                        }
                    }
                    if (done.isEmpty()) {
                        store.lastResult = "Desk replied without confirming receipt · ${stamp()}"
                        return Result.retry()
                    }
                    store.remove(done)
                }
                outcome.code == 401 || outcome.code == 403 || outcome.code == 404 -> {
                    store.lastResult = "Device link no longer works. Generate a new one in the desk and paste it here."
                    return Result.failure()
                }
                outcome.code == 413 -> {
                    store.lastResult = "Batch too large; will retry smaller."
                    return Result.retry()
                }
                else -> {
                    store.lastResult = "Couldn't reach the desk (${if (outcome.code < 0) "offline" else outcome.code}) · will retry"
                    return Result.retry()
                }
            }
        }
        return Result.success()
    }

    private fun body(batch: List<QueuedMessage>) = JSONObject().apply {
        put("device", JSONObject().apply {
            put("model", "${Build.MANUFACTURER} ${Build.MODEL}".take(80))
            put("app_version", BuildConfigVersion.name(applicationContext))
        })
        put("messages", JSONArray().apply {
            batch.forEach { m ->
                put(JSONObject().apply {
                    put("key", m.key); put("source", m.source); put("chat_id", m.chatId); put("chat_title", m.chatTitle)
                    put("is_group", m.isGroup); put("sender", m.sender); put("direction", m.direction)
                    put("text", m.text); put("sent_at", iso(m.sentAtMillis)); put("captured_via", m.capturedVia)
                })
            }
        })
    }.toString()

    private data class Outcome(val code: Int, val body: String)

    private fun post(url: String, json: String): Outcome = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000; readTimeout = 30000
            doOutput = true
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/json")
        }
        c.outputStream.use { it.write(json.toByteArray()) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText().take(100_000) } ?: ""
        c.disconnect()
        Outcome(code, text)
    } catch (e: Exception) {
        Outcome(-1, "")
    }

    companion object {
        private const val ONE_OFF = "upload-now"
        private const val PERIODIC = "upload-periodic"
        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun kick(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<Uploader>()
                .setConstraints(online)
                .setInitialDelay(20, TimeUnit.SECONDS) // gather a few messages per send
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(ONE_OFF, ExistingWorkPolicy.KEEP, req)
        }

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<Uploader>(30, TimeUnit.MINUTES).setConstraints(online).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun iso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

        fun stamp(): String = SimpleDateFormat("d MMM h:mm a", Locale("en", "AU")).format(Date())
    }
}

object BuildConfigVersion {
    fun name(ctx: Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")
}
