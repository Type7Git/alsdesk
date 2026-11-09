package au.type7.alsdesksync

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest

/** Sources the app understands. Anything else is ignored. */
object Sources {
    const val WHATSAPP = "whatsapp"
    const val GOOGLE_MESSAGES = "google_messages"
    const val MESSENGER = "messenger"
    const val SMS = "sms"

    val byPackage = mapOf(
        "com.whatsapp" to WHATSAPP,
        "com.whatsapp.w4b" to WHATSAPP,
        "com.google.android.apps.messaging" to GOOGLE_MESSAGES,
        "com.facebook.orca" to MESSENGER,
    )

    fun label(source: String) = when (source) {
        WHATSAPP -> "WhatsApp"
        GOOGLE_MESSAGES -> "Messages"
        MESSENGER -> "Messenger"
        SMS -> "SMS history"
        else -> source
    }
}

data class QueuedMessage(
    val key: String,
    val source: String,
    val chatId: String,
    val chatTitle: String,
    val isGroup: Boolean,
    val sender: String,
    val direction: String, // "in" | "out"
    val text: String,
    val sentAtMillis: Long,
    val capturedVia: String, // "notification" | "sms_provider"
)

data class Chat(
    val chatId: String,
    val source: String,
    val title: String,
    val allowed: Boolean,
    val lastSeen: Long,
)

fun sha256(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

/**
 * Local store. Chats are discovered by title only; message text is queued ONLY for chats Al has allowed.
 * Queued messages are deleted as soon as the desk accepts them.
 */
class Store private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "sync.db", null, 1) {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        ctx,
        "secure_prefs",
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE chats (chat_id TEXT PRIMARY KEY, source TEXT NOT NULL, title TEXT NOT NULL,
               allowed INTEGER NOT NULL DEFAULT 0, last_seen INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE queue (key TEXT PRIMARY KEY, source TEXT, chat_id TEXT, chat_title TEXT, is_group INTEGER,
               sender TEXT, direction TEXT, text TEXT, sent_at INTEGER, captured_via TEXT, queued_at INTEGER)"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ---------- settings ----------
    var deviceUrl: String?
        get() = prefs.getString("device_url", null)
        set(v) = prefs.edit().apply { if (v == null) remove("device_url") else putString("device_url", v) }.apply()

    var paused: Boolean
        get() = prefs.getBoolean("paused", false)
        set(v) = prefs.edit().putBoolean("paused", v).apply()

    var smsEnabled: Boolean
        get() = prefs.getBoolean("sms_enabled", false)
        set(v) = prefs.edit().putBoolean("sms_enabled", v).apply()

    var smsLastId: Long
        get() = prefs.getLong("sms_last_id", 0L)
        set(v) = prefs.edit().putLong("sms_last_id", v).apply()

    /** Numbers whose 30-day history has already been sent once. */
    var smsBackfilled: Set<String>
        get() = prefs.getStringSet("sms_backfilled", emptySet())?.toSet() ?: emptySet()
        set(v) = prefs.edit().putStringSet("sms_backfilled", v).apply()

    var lastResult: String
        get() = prefs.getString("last_result", "Not synced yet") ?: ""
        set(v) = prefs.edit().putString("last_result", v).apply()

    // ---------- chats ----------
    fun seeChat(chatId: String, source: String, title: String): Boolean {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.rawQuery("SELECT allowed FROM chats WHERE chat_id=?", arrayOf(chatId)).use { c ->
            if (c.moveToFirst()) {
                db.execSQL("UPDATE chats SET title=?, last_seen=? WHERE chat_id=?", arrayOf<Any>(title, now, chatId))
                return c.getInt(0) == 1
            }
        }
        db.insert("chats", null, ContentValues().apply {
            put("chat_id", chatId); put("source", source); put("title", title)
            put("allowed", 0); put("last_seen", now)
        })
        return false
    }

    fun setAllowed(chatId: String, allowed: Boolean) {
        writableDatabase.execSQL("UPDATE chats SET allowed=? WHERE chat_id=?", arrayOf<Any>(if (allowed) 1 else 0, chatId))
        if (!allowed) writableDatabase.delete("queue", "chat_id=?", arrayOf(chatId))
    }

    fun chats(): List<Chat> = readableDatabase.rawQuery(
        "SELECT chat_id, source, title, allowed, last_seen FROM chats ORDER BY allowed DESC, last_seen DESC", null
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(Chat(c.getString(0), c.getString(1), c.getString(2), c.getInt(3) == 1, c.getLong(4)))
        }
    }

    fun allowedSmsAddresses(): Set<String> = readableDatabase.rawQuery(
        "SELECT chat_id FROM chats WHERE source=? AND allowed=1", arrayOf(Sources.SMS)
    ).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0).removePrefix("sms:")) } }

    // ---------- queue ----------
    fun enqueue(m: QueuedMessage) {
        writableDatabase.insertWithOnConflict("queue", null, ContentValues().apply {
            put("key", m.key); put("source", m.source); put("chat_id", m.chatId); put("chat_title", m.chatTitle)
            put("is_group", if (m.isGroup) 1 else 0); put("sender", m.sender); put("direction", m.direction)
            put("text", m.text); put("sent_at", m.sentAtMillis); put("captured_via", m.capturedVia)
            put("queued_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_IGNORE)
        // Hard cap so a long outage can't grow the queue without bound.
        writableDatabase.execSQL(
            "DELETE FROM queue WHERE key NOT IN (SELECT key FROM queue ORDER BY queued_at DESC LIMIT 2000)"
        )
    }

    fun pending(limit: Int): List<QueuedMessage> = readableDatabase.rawQuery(
        "SELECT key, source, chat_id, chat_title, is_group, sender, direction, text, sent_at, captured_via FROM queue ORDER BY sent_at LIMIT ?",
        arrayOf(limit.toString())
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                QueuedMessage(
                    c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4) == 1,
                    c.getString(5), c.getString(6), c.getString(7), c.getLong(8), c.getString(9)
                )
            )
        }
    }

    fun pendingCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM queue", null).use { c -> c.moveToFirst(); c.getInt(0) }

    fun remove(keys: Collection<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            keys.forEach { db.delete("queue", "key=?", arrayOf(it)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun wipeQueue() { writableDatabase.delete("queue", null, null) }

    companion object {
        @Volatile private var instance: Store? = null
        fun get(ctx: Context): Store = instance ?: synchronized(this) {
            instance ?: Store(ctx.applicationContext).also { instance = it }
        }
    }
}
