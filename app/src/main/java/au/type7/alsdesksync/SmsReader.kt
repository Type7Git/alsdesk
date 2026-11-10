package au.type7.alsdesksync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat

/**
 * Optional SMS history (sent and received) for numbers Al has switched on.
 * Uses Android's SMS store, so it includes Al's own sent texts — which notifications can't see.
 * RCS chats in Google Messages are not in this store; those come only via notifications.
 */
object SmsReader {
    private val SMS_URI: Uri = Uri.parse("content://sms")
    private const val THIRTY_DAYS = 30L * 24 * 60 * 60 * 1000

    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /** Australian-default E.164, matching the desk's normaliser. Service/short numbers are left as they are. */
    fun normalise(address: String): String {
        var d = address.filter { it.isDigit() || it == '+' }
        if (d.isEmpty()) return address.trim()
        if (d.startsWith("0011")) d = "+" + d.drop(4)
        else if (d.startsWith("00")) d = "+" + d.drop(2)
        if (d.startsWith("+610")) d = "+61" + d.drop(4)
        val digits = d.removePrefix("+")
        return when {
            d.startsWith("+") -> d
            digits.startsWith("61") && digits.length == 11 -> "+$digits"
            digits.startsWith("0") && digits.length == 10 -> "+61" + digits.drop(1) // 04 mobiles, 02/03/07/08 landlines
            digits.startsWith("4") && digits.length == 9 -> "+61$digits"
            else -> digits // 13xx, 1300, 1800, 19xx and other short numbers
        }
    }

    /** Lists recent SMS numbers so Al can choose which to include. Stores the number only, no text. */
    fun discover(ctx: Context) {
        if (!hasPermission(ctx)) return
        val store = Store.get(ctx)
        val since = System.currentTimeMillis() - THIRTY_DAYS
        ctx.contentResolver.query(SMS_URI, arrayOf("address", "date"), "date > ?", arrayOf(since.toString()), "date DESC")
            ?.use { c ->
                val seen = HashSet<String>()
                while (c.moveToNext() && seen.size < 200) {
                    val addr = c.getString(0) ?: continue
                    val n = normalise(addr)
                    if (seen.add(n)) store.seeChat("sms:$n", Sources.SMS, n)
                }
            }
    }

    /** Queues new SMS for allowed numbers. First run for a number backfills 30 days. */
    fun collect(ctx: Context) {
        val store = Store.get(ctx)
        if (!store.smsEnabled || store.paused || store.deviceUrl == null || !hasPermission(ctx)) return
        val allowed = store.allowedSmsAddresses()
        if (allowed.isEmpty()) return
        val since = System.currentTimeMillis() - THIRTY_DAYS
        val lastId = store.smsLastId
        val backfilled = store.smsBackfilled
        var maxId = lastId
        ctx.contentResolver.query(
            SMS_URI, arrayOf("_id", "address", "body", "date", "type"),
            "date > ?", arrayOf(since.toString()), "_id ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (id > maxId) maxId = id
                val addr = normalise(c.getString(1) ?: continue)
                if (addr !in allowed) continue
                // Already-backfilled numbers only need messages newer than the last run.
                if (addr in backfilled && id <= lastId) continue
                val body = (c.getString(2) ?: "").trim().take(2000)
                if (body.isEmpty()) continue
                val ts = c.getLong(3)
                val direction = when (c.getInt(4)) { 2, 4, 5, 6 -> "out"; else -> "in" } // sent/outbox/failed/queued
                val sender = if (direction == "out") "Me" else addr
                val chatId = "sms:$addr"
                val key = sha256("${Sources.SMS}|$chatId|$direction|$ts|$body")
                store.enqueue(QueuedMessage(key, Sources.SMS, chatId, addr, false, sender, direction, body, ts, "sms_provider"))
            }
        }
        store.smsLastId = maxId
        store.smsBackfilled = backfilled + allowed
    }
}
