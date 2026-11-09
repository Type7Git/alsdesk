package au.type7.alsdesksync

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat

/**
 * Reads message notifications from WhatsApp, Messages and Messenger.
 * - Unknown chats are only remembered by title so Al can choose them; their text is never kept.
 * - Text is queued only for chats Al has switched on, and only while sync isn't paused.
 * - Only what the notification itself shows is available: incoming messages (and replies sent from the
 *   notification). Muted chats, messages read elsewhere first, and media are not seen.
 */
class CaptureListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val source = Sources.byPackage[sbn.packageName] ?: return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val store = Store.get(this)

        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        val extras = n.extras
        val title = (style?.conversationTitle ?: extras.getCharSequence(Notification.EXTRA_TITLE))?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() } ?: return
        // Skip app housekeeping notifications ("Checking for new messages", backups, calls).
        if (n.category == Notification.CATEGORY_CALL || n.category == Notification.CATEGORY_PROGRESS) return

        val stableKey = n.shortcutId ?: title
        val chatId = "$source:" + sha256("${sbn.packageName}|$stableKey").take(24)
        val allowed = store.seeChat(chatId, source, title.take(120))
        if (!allowed || store.paused || store.deviceUrl == null) return

        val isGroup = style?.isGroupConversation ?: false
        val messages = style?.messages
        if (!messages.isNullOrEmpty()) {
            for (m in messages) {
                val text = m.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) continue
                val person = m.person
                val direction = if (person == null) "out" else "in"
                val sender = person?.name?.toString()?.trim().takeUnless { it.isNullOrEmpty() }
                    ?: if (direction == "out") "Me" else title
                val ts = if (m.timestamp > 0) m.timestamp else sbn.postTime
                enqueue(store, source, chatId, title, isGroup, sender, direction, text, ts)
            }
        } else {
            // Older-style notification: a single line of text.
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
            if (text.isEmpty() || looksLikeSummary(text)) return
            enqueue(store, source, chatId, title, isGroup, title, "in", text, sbn.postTime)
        }
        Uploader.kick(this)
    }

    private fun enqueue(
        store: Store, source: String, chatId: String, title: String, isGroup: Boolean,
        sender: String, direction: String, text: String, ts: Long,
    ) {
        val clipped = text.take(2000)
        val key = sha256("$source|$chatId|$sender|$ts|$clipped")
        store.enqueue(
            QueuedMessage(key, source, chatId, title.take(120), isGroup, sender.take(120), direction, clipped, ts, "notification")
        )
    }

    // e.g. "4 new messages", "2 messages from 3 chats"
    private fun looksLikeSummary(text: String) =
        Regex("""^\d+\s+(new\s+)?messages?(\s+from\s+\d+\s+chats?)?$""", RegexOption.IGNORE_CASE).matches(text)
}
