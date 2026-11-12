package au.type7.alsdesksync

import android.Manifest
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat

/** One simple screen: link to the desk, permissions, which chats to include, pause and sync. */
class MainActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout
    private val store by lazy { Store.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 80) }
        setContentView(ScrollView(this).apply { addView(root) })
        Uploader.schedule(this)
        DonnaNotices.ensureChannel(this)
        if (store.donnaNotices) DonnaNotices.schedule(this)
    }

    override fun onResume() {
        super.onResume()
        if (store.smsEnabled) SmsReader.discover(this)
        render()
    }

    private fun render() {
        root.removeAllViews()
        heading("Donna · phone settings")
        note("Sends messages from the chats you switch on to your private desk, and lets Donna tap you on the shoulder. Nothing is shared from chats you leave off, and nothing is ever sent to anyone from this app.")
        button("Open my desk") {
            startActivity(Intent(this, com.google.androidbrowserhelper.trusted.LauncherActivity::class.java))
        }

        // 1. Device link
        heading("1. Link to your desk")
        val linked = store.deviceUrl != null
        note(if (linked) "Linked ✓" else "In the desk, open Settings → Devices → Generate device link, then paste it here.")
        if (!linked) {
            val input = EditText(this).apply {
                hint = "https://…/api/public/device/…"
                inputType = InputType.TYPE_TEXT_VARIATION_URI
            }
            root.addView(input)
            button("Save link") {
                val v = input.text.toString().trim()
                if (!v.startsWith("https://") || !v.contains("/api/public/device/")) {
                    toast("That doesn't look like a device link from the desk.")
                } else {
                    store.deviceUrl = v; render()
                }
            }
        } else {
            button("Remove link") { store.deviceUrl = null; store.wipeQueue(); render() }
        }

        // 2. Permissions
        heading("2. Permissions")
        val listening = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        note(
            if (listening) "Notification access: on ✓"
            else "Notification access: off. If Android says it's a restricted setting, open App info for Donna → ⋮ → Allow restricted settings, then try again."
        )
        if (!listening) button("Open notification access") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        button("Open app info") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
        }

        root.addView(Switch(this).apply {
            text = "Include SMS history (sent and received) for numbers I choose"
            isChecked = store.smsEnabled
            setOnCheckedChangeListener { _, on ->
                store.smsEnabled = on
                if (on && !SmsReader.hasPermission(this@MainActivity)) {
                    ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.READ_SMS), 7)
                } else if (on) {
                    SmsReader.discover(this@MainActivity); render()
                }
            }
        })

        // 2b. WhatsApp replies (optional)
        heading("Your WhatsApp replies (optional)")
        note("WhatsApp doesn't tell other apps about messages you send inside it, so your desk can't tell you've replied. Turn this on and, while you have a switched-on WhatsApp chat open, the app reads only YOUR sent messages. It ignores other apps and chats, and never types or sends anything.")
        val readerOn = replyReaderEnabled()
        note(
            if (readerOn) "Reply reader permission: on ✓"
            else "Reply reader permission: off. Open Accessibility → Installed apps → Donna — my WhatsApp replies → On. If it's greyed out, use App info → ⋮ → Allow restricted settings first."
        )
        button(if (readerOn) "Accessibility settings" else "Open accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        root.addView(Switch(this).apply {
            text = "Read my WhatsApp replies"
            isChecked = store.readReplies
            setOnCheckedChangeListener { _, on -> store.readReplies = on; render() }
        })
        if (store.readReplies) note(store.repliesStatus + (if (!readerOn) " · waiting for the permission above" else ""))

        // 2c. Donna's notices (phone + Android Auto)
        heading("Donna's notices (phone and car)")
        note("Short heads-ups from Donna, like a pencilled event or someone waiting on you. In the car, Android Auto reads them aloud and you can answer by voice. Your answer goes to Donna only; she never sends anything to anyone without your tap on the desk.")
        val canNotify = DonnaNotices.canNotify(this)
        note(if (canNotify) "Notifications: on ✓" else "Notifications: off.")
        if (!canNotify) button("Allow notifications") {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 8)
            } else {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }
        }
        root.addView(Switch(this).apply {
            text = "Show Donna's notices"
            isChecked = store.donnaNotices
            setOnCheckedChangeListener { _, on ->
                store.donnaNotices = on
                if (on) DonnaNotices.schedule(this@MainActivity) else DonnaNotices.cancel(this@MainActivity)
                render()
            }
        })
        note("Android Auto (one-off): open Android Auto settings on this phone, tap Version about ten times until developer settings unlock, then ⋮ → Developer settings → tick Unknown sources. Donna's notices will then show and be read out in the car.")
        note("Hands-free on the phone: long-press the Donna icon → Talk to Donna (drag it to your home screen), or say \"Hey Google, open Donna\".")

        // 3. Chats
        heading("3. Chats to include")
        note("New chats appear here when a message arrives (or from your SMS list). Only switched-on chats are sent to the desk.")
        val chats = store.chats()
        if (chats.isEmpty()) note("No chats seen yet. Leave this app installed; chats appear as messages come in.")
        chats.forEach { c ->
            root.addView(CheckBox(this).apply {
                text = "${Sources.label(c.source)} · ${c.title}"
                isChecked = c.allowed
                setOnCheckedChangeListener { _, on -> store.setAllowed(c.chatId, on) }
            })
        }

        // 4. Sync
        heading("4. Sync")
        root.addView(Switch(this).apply {
            text = "Pause sync"
            isChecked = store.paused
            setOnCheckedChangeListener { _, on -> store.paused = on; render() }
        })
        note("Waiting to send: ${store.pendingCount()} · ${store.lastResult}")
        button("Sync now") { Uploader.kick(this); toast("Syncing shortly") }
        button("Clear waiting messages") { store.wipeQueue(); render() }

        note("What this can't see: muted chats, messages you read on another device before the notification arrived, media, and your own Messenger replies typed inside Messenger. Your WhatsApp replies are read only if \"Read my WhatsApp replies\" is on, and only from chats you open on this phone. SMS history (if on) includes your sent texts. From Facebook, only birthday notifications are passed on.")
    }

    private fun replyReaderEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { it.equals("$packageName/${ReplyReader::class.java.name}", true) ||
            it.equals("$packageName/.ReplyReader", true) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 8) { render(); return }
        if (requestCode == 7) {
            if (SmsReader.hasPermission(this)) SmsReader.discover(this) else store.smsEnabled = false
            render()
        }
    }

    private fun heading(t: String) = root.addView(TextView(this).apply {
        text = t; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setPadding(0, 36, 0, 8)
    })

    private fun note(t: String) = root.addView(TextView(this).apply { text = t; textSize = 14f; setPadding(0, 4, 0, 8) })

    private fun button(label: String, onClick: (View) -> Unit) = root.addView(Button(this).apply {
        text = label; setOnClickListener(onClick)
    })

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}
