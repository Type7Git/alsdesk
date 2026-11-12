package au.type7.alsdesksync

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Optional "reply reader". WhatsApp never tells other apps about messages Al sends from inside the app,
 * so this reads Al's OWN outgoing bubbles from the chat he has open — nothing else.
 *
 * Limits (by design):
 * - Only WhatsApp (declared in the service config, so Android doesn't send us other apps' screens).
 * - Only while Al has switched "Read my WhatsApp replies" on, sync isn't paused and the desk is linked.
 * - Only chats Al has switched on in this app (matched by the chat name shown at the top).
 * - Only outgoing bubbles (incoming messages already arrive through notifications).
 * - Text is queued for the desk like any other message; nothing is ever typed, tapped or sent.
 */
class ReplyReader : AccessibilityService() {

    private var lastScan = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg !in Sources.whatsappPackages) return
        val store = Store.get(this)
        if (!store.readReplies || store.paused || store.deviceUrl == null) return
        val now = SystemClock.uptimeMillis()
        if (now - lastScan < 1500) return // at most every 1.5 s while scrolling/typing
        lastScan = now
        val root = rootInActiveWindow ?: return
        try {
            scan(root, pkg, store)
        } catch (_: Throwable) {
            // WhatsApp's screen layout changed or a node vanished mid-read: skip quietly.
        }
    }

    override fun onInterrupt() {}

    private fun scan(root: AccessibilityNodeInfo, pkg: String, store: Store) {
        val title = root.findAccessibilityNodeInfosByViewId("$pkg:id/conversation_contact_name")
            .firstOrNull()?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val chatId = store.allowedChatIdForTitle(Sources.WHATSAPP, title.take(120)) ?: return

        val dividers = root.findAccessibilityNodeInfosByViewId("$pkg:id/conversation_row_date_divider")
            .mapNotNull { n -> n.text?.toString()?.let { t -> top(n) to t } }
            .sortedBy { it.first }

        var added = 0
        for (bubble in root.findAccessibilityNodeInfosByViewId("$pkg:id/message_text")) {
            val text = bubble.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) continue
            val row = rowOf(bubble, pkg) ?: continue
            // Only Al's own messages show delivery ticks ("status").
            if (row.findAccessibilityNodeInfosByViewId("$pkg:id/status").isEmpty()) continue
            val timeText = row.findAccessibilityNodeInfosByViewId("$pkg:id/date").firstOrNull()?.text?.toString()
            val dayText = dividers.lastOrNull { it.first <= top(bubble) }?.second
            val ts = timestamp(timeText, dayText) ?: continue
            if (!store.firstSightOfOutgoing(chatId, text, ts)) continue
            val clipped = text.take(2000)
            val key = sha256("${Sources.WHATSAPP}|$chatId|Me|${ts / 60_000}|$clipped")
            store.enqueue(
                QueuedMessage(key, Sources.WHATSAPP, chatId, title.take(120), false, "Me", "out", clipped, ts, "screen")
            )
            added++
        }
        if (added > 0) {
            val at = SimpleDateFormat("d MMM h:mm a", Locale.getDefault()).format(System.currentTimeMillis())
            store.repliesStatus = "Last read $added repl${if (added == 1) "y" else "ies"} from \"$title\" at $at"
            Uploader.kick(this)
        }
    }

    /** Walks up from the text to the message row that also holds the time and ticks. */
    private fun rowOf(node: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        repeat(5) {
            n = n?.parent ?: return null
            val p = n ?: return null
            if (p.findAccessibilityNodeInfosByViewId("$pkg:id/date").isNotEmpty()) return p
        }
        return null
    }

    private fun top(n: AccessibilityNodeInfo): Int = Rect().also { n.getBoundsInScreen(it) }.top

    /** "7:41 pm" / "19:41" plus a divider like "TODAY", "YESTERDAY", "Monday" or "10 November 2026". */
    private fun timestamp(timeText: String?, day: String?): Long? {
        val m = Regex("""(\d{1,2})[:.](\d{2})\s*([ap])?\.?\s*m?\.?""", RegexOption.IGNORE_CASE)
            .find(timeText ?: return null) ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        val ap = m.groupValues[3].lowercase()
        if (ap == "p" && h < 12) h += 12
        if (ap == "a" && h == 12) h = 0
        if (h > 23 || min > 59) return null

        val cal = Calendar.getInstance()
        val d = day?.trim()?.lowercase(Locale.ROOT).orEmpty()
        when {
            d.isEmpty() || d == "today" -> {}
            d == "yesterday" -> cal.add(Calendar.DAY_OF_YEAR, -1)
            else -> {
                val weekdays = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")
                val wd = weekdays.indexOf(d)
                if (wd >= 0) {
                    // Most recent past occurrence of that weekday (WhatsApp uses names for the last week).
                    var back = (cal.get(Calendar.DAY_OF_WEEK) - 1 - wd + 7) % 7
                    if (back == 0) back = 7
                    cal.add(Calendar.DAY_OF_YEAR, -back)
                } else {
                    val parsed = listOf("d MMMM yyyy", "MMMM d, yyyy", "d MMMM", "MMMM d").firstNotNullOfOrNull { f ->
                        runCatching { SimpleDateFormat(f, Locale.ENGLISH).parse(day!!.trim()) }.getOrNull()
                    } ?: return null
                    val p = Calendar.getInstance().apply { time = parsed }
                    val year = if (p.get(Calendar.YEAR) < 2000) cal.get(Calendar.YEAR) else p.get(Calendar.YEAR)
                    cal.set(year, p.get(Calendar.MONTH), p.get(Calendar.DAY_OF_MONTH))
                }
            }
        }
        cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, min)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        var ts = cal.timeInMillis
        // No divider visible and the time is later than now: it was yesterday.
        if (d.isEmpty() && ts > System.currentTimeMillis() + 5 * 60_000) ts -= 86_400_000L
        return ts
    }
}
