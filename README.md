# UniChat

Free lightweight native Android client for **Telegram, Signal and WhatsApp**.

Link one account or all three: their chats share one list, one local database
and the same screens, with a different accent color for each.

How each one signs in:

- **Telegram** — standalone via [TDLib](https://github.com/tdlib/td)
- **Signal** — standalone or linked as a second device via [signalmeow](https://github.com/mautrix/signal)
- **WhatsApp** — **companion device** (the official WhatsApp app must be
  installed on the device) via [whatsmeow](https://github.com/tulir/whatsmeow)

## Features

- Every account in one chat list, with avatars, snippets, timestamps and
  unread badges
- Text, photos, videos, voice messages (play and record), documents, stickers
  (view only), locations and contact cards; **bold** and *italic* for text
- Voice messages play in sequence even when the screen is off
- Search chats from the list; search messages inside a chat, going further back
  on demand
- Link previews with title, description and picture
- Swipe through a chat's photos in the viewer, pinch to zoom
- Voice notes play at 1x, 1.5x or 2x, and keep their place when you scroll away
- Your own notes-to-self sit at the top when you pick where to forward
- Tap a message for its actions: reply, react, copy, forward, share, save to
  Downloads, edit, delete. Tap a reply's quote to jump to the original
- Forward across services — a WhatsApp photo into a Telegram chat, and back
- Select several messages (long-press, then drag or tap) to forward, copy or
  delete them together
- View-once photos and videos, where the service allows them
- Appears in Android's share sheet: send text, photos or any file from other
  apps straight into a chat
- Notifications per chat for all three services, with sender, avatar and
  preview, quiet for the chat on screen and for muted chats
- Export a chat to a text file; mute, delete or open a chat on your other
  account
- Edit your profile — picture, name and About — from the ⋮ menu. With more than
  one account linked, account actions ask which one
- Privacy settings per account: last seen and online, profile picture, About,
  read receipts, and being findable by your number
- Presence on both screens: the chat list puts a green dot on the avatar of
  whoever is online and swaps the snippet for "typing… / recording voice…" as
  it happens; the chat itself adds last seen
- Share contact's avatar
- Light and dark themes, or follow the device; the dark one is true black for
  OLED. Font size is yours to set too
- Local SQLite history shared by the three services, and a foreground service
  to stay connected, with a hidden status-bar icon
- No ads, no telemetry, no bullshit

## Limitations

- No voice or video calls; a call you get shows as a line in the chat
- No creating groups, and no group admin
- No stickers or pre-selection of GIFs
- No internal video player — when tapping a video, the external player
  installed on the device will open the video
- Polls, events, group invites and live location show as labels — you can't
  create or answer them
- Telegram: no secret chats
- Signal: no avatars, and a location goes as a map link, since Signal has no
  location message of its own
- WhatsApp: needs the official app on your other device, and it has to be
  online the
  first time you load old messages

## Logging in

The login screen has one tab per service. One is enough; the others can be added
later from ⋮ → **Link account**.

### Telegram

1. Type your phone number with country code and tap **Send code**.
2. Enter the code Telegram sends you, in the Telegram app on another device or
   by SMS.
3. If your account has two-step verification, type its password.

### Signal

The Signal tab opens **Set up Signal**. Either:

- **Standalone** — type your number, tap **Send code**, confirm **Replace
  Signal?**, then enter the code from the SMS and tap **Verify and register**.
  No Signal app needed, but that number is signed out of the official app.
- **With the Signal app on this device** — tap **Or link to the Signal app on
  this device**, then in Signal open **Settings → Linked devices**, tap **+**
  and scan the code UniChat shows.

Signing up here only shows chats with people from your device's contacts who
let others find them by number on Signal. For the rest, tap ⋮ → **Manage
accounts**, tap the Signal row and type your Signal PIN. Old messages never come
back, and that's by Signal's design.

### WhatsApp

1. Scan the QR code with the device that runs your WhatsApp (WhatsApp →
   Settings → Linked devices → Link a device), or type your phone number with
   country code, tap **Get pairing code** and enter it in the WhatsApp
   notification.
2. Codes refresh on their own, so whatever is on screen is always valid.
3. Recent messages sync in seconds; opening a chat pulls older ones from your
   other device, which has to be online.

## Building

Clone the repo and run:

```sh
./build.sh                   # full build: Go bridge + aar + release APK
./build.sh --apk-only        # Kotlin/resource changes only (much faster)
./build.sh --no-install      # don't install onto a connected device afterwards
```

Everything else is fetched and built automatically. The first run is slow — it
compiles TDLib, OpenSSL and libsignal from source — but only once; after that
the results are cached. Every build is a release build and drops the APK in the
repo root as `unichat-<version>.apk`.

**Third-party libraries are checked once a week** and picked up automatically.

**A build installs automatically** when a device is attached.

**Telegram needs an app id of your own, which is not in this repo**. Get one at
[my.telegram.org](https://my.telegram.org) → API development tools (platform
Android), then:

```sh
cp telegram.properties.template telegram.properties   # then fill it in
```

## Donate

Please consider donating to the UniChat project:

[https://paypal.me/fulalas](https://paypal.me/fulalas)