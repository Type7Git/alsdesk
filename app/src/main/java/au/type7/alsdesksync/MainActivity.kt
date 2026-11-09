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
    }

    override fun onResume() {
        super.onResume()
        if (store.smsEnabled) SmsReader.discover(this)
        render()
    }

    private fun render() {
        root.removeAllViews()
        heading("Al's Desk Sync")
        note("Sends messages from the chats you switch on to your private desk. Nothing is shared from chats you leave off, and nothing is ever sent to anyone from this app.")

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
            else "Notification access: off. If Android says it's a restricted setting, open App info for Al's Desk Sync → ⋮ → Allow restricted settings, then try again."
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

        note("What this can't see: muted chats, messages you read on another device before the notification arrived, media, and your own WhatsApp/Messenger replies typed inside those apps. SMS history (if on) includes your sent texts.")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
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
