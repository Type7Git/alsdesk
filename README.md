# Al's Desk Sync (Android companion)

A small private Android app that sends messages from chats Al switches on — WhatsApp, Google Messages, Messenger, and optionally SMS history — to his own Al's Desk. The desk itself stays in the browser; this app has no sign-in and only holds a revocable device link.

## What it does
- **Notification capture** (WhatsApp, Messages, Messenger): reads message notifications as they arrive. Chats are listed by title; message text is queued **only for chats switched on**, and only while sync isn't paused.
- **SMS history** (optional): reads Android's SMS store, including Al's sent texts, for numbers he switches on (30-day backfill, then new messages).
- **Upload**: batches of up to 100 to `POST {deviceLink}/messages`, retried safely with per-message keys. Accepted messages are deleted from the phone.

## What it never does
Send, reply, react, read media, read contacts, back up its data, or run anything other than this upload. It never sees muted chats, messages read on another device first, or WhatsApp/Messenger replies typed inside those apps.

## Desk contract
`POST https://<desk>/api/public/device/<token>/messages`
```json
{ "device": {"model": "...", "app_version": "0.1.N"},
  "messages": [{ "key": "sha256", "source": "whatsapp|google_messages|messenger|sms",
    "chat_id": "whatsapp:ab12…", "chat_title": "Erin", "is_group": false, "sender": "Erin",
    "direction": "in|out", "text": "…", "sent_at": "2026-11-10T01:23:45Z",
    "captured_via": "notification|sms_provider" }] }
```
Response `200 {"accepted": [keys], "duplicates": [keys]}`; `401/403/404` means the link was revoked.

## Build
GitHub Actions builds on every push to `main` and publishes `als-desk-sync.apk` to the `latest` release. A release-signed build needs repository secrets `SYNC_KEYSTORE_B64`, `SYNC_KEYSTORE_PASSWORD`, `SYNC_KEY_ALIAS`, `SYNC_KEY_PASSWORD`; without them a debug build is produced (updates then require uninstalling first).

## Install (Android 13+)
1. Download the APK from the `latest` release and allow installing from your browser.
2. Open App info for **Al's Desk Sync** → ⋮ → **Allow restricted settings** (Android blocks notification access for side-loaded apps until you do this).
3. In the app: paste the device link from the desk (Settings → Devices), turn on notification access, then switch on the chats you want.
