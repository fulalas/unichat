# UniChat

Minimal native Android client for **Telegram, Signal and WhatsApp in one app**.

- Telegram uses the official [TDLib](https://github.com/tdlib/td) (submodule
  `tdjson/td`, built by `build-tdlib.sh`).
- Signal uses [signalmeow](https://github.com/mautrix/signal) over Signal's own
  `libsignal` (built by `build-libsignal.sh`).
- WhatsApp uses the real multi-device protocol through
  [whatsmeow](https://github.com/tulir/whatsmeow), compiled for Android with
  `gomobile`.

One Kotlin app with classic XML Views — no Compose, no bloat.

Link one account or all three: their chats share one list, one local database
and the same screens, with a small badge marking which service a chat belongs
to.

WhatsApp and Signal link as a **second device**, so the app on your phone keeps
working. Telegram signs in as a normal client with your phone number (no secret
chats).

## Features

- Every account in one chat list, with avatars, snippets, timestamps, unread
  badges, a per-service badge and live "typing… / recording voice…" indicators
- Search chats from the list; search messages inside a chat, going further back
  on demand
- Text, photos, videos, voice messages (play and record), documents, stickers,
  locations and contact cards; **bold** and *italic* when you select text
- Link previews with title, description and picture
- Tap a message for its actions: reply, react, copy, forward, share, save to
  Downloads, edit, delete. Tap a reply's quote to jump to the original
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
- Presence in the chat header: online, last seen, typing, recording
- History: Telegram loads the newest messages when you open a chat; WhatsApp
  syncs the last 20 per chat at link time and pulls older ones from your phone;
  Signal starts empty and fills up as messages arrive
- Local SQLite history shared by the three services, a foreground service to
  stay connected (with a hidden status-bar icon), font size setting and a
  true-black OLED dark theme

Signal is the youngest of the three: no message history from before you linked,
no chat pictures, and locations and contact cards can be received but not sent.

## Prerequisites

- Go 1.25+, `gomobile` (`go install golang.org/x/mobile/cmd/gomobile@latest && gomobile init`)
- JDK 17
- Android SDK: platform 35, build-tools 35, NDK r27
- Gradle 8.13 (or set `$GRADLE` to your gradle binary)
- `git` and `protoc`, for the Signal step
- CMake 3.x and a host C/C++ toolchain (`make`, `gcc`/`g++`), for the Telegram
  step — it also builds `gperf` if you don't have it
- Network access on the first build: it downloads OpenSSL, gperf, whatsmeow,
  signalmeow and libsignal, none of which are committed here

Rust is needed for Signal too, but `build-libsignal.sh` installs it with rustup
if `cargo` is missing.

If you keep the toolchain in a sibling `../toolchain/env.sh`, `build.sh` sources
it automatically; otherwise point `$UNICHAT_ENV` at your own env script or just
have the tools on `PATH` (with `ANDROID_HOME` set, or a `local.properties`
containing `sdk.dir`).

## Building

TDLib is a submodule — check it out first:

```sh
git submodule update --init --recursive
```

Telegram needs an app id of your own, which is not in this repo. Get one at
[my.telegram.org](https://my.telegram.org) → API development tools (platform
Android), then:

```sh
cp telegram.properties.template telegram.properties   # then fill it in
```

The build stops and tells you so if that file is missing. Keep it out of git —
Telegram bans an id that shows up as several apps.

```sh
./build.sh                   # full build: Go bridge + aar + release APK
./build.sh --apk-only        # Kotlin/resource changes only (much faster)
./build.sh --no-install      # don't install onto a connected device afterwards
```

Every build is a release build and drops the APK in the repo root as
`unichat-<version>.apk`, named after `versionName` in `app/build.gradle`.

The first build is slow: it compiles OpenSSL, TDLib and libsignal from source
for arm64. That happens only once — the results are cached in `tdjson/ext/` and
`gobridge/ext/libsignal/`, and the Telegram libs land in
`app/src/main/jniLibs/`. Both steps can also be run on their own:

```sh
./build-tdlib.sh                          # arm64-v8a armeabi-v7a x86_64
./build-tdlib.sh arm64-v8a                # just one (the APK then needs the rest)
./build-libsignal.sh
```

**A build installs automatically** when a device is attached — pass
`--no-install` to skip it. With no device connected it prints the command to run
later:

```sh
source ../toolchain/env.sh
adb install -r unichat-<version>.apk
```

Updates install in place and keep your sessions, as long as the APK is signed
with `keystore/unichat.keystore` (build.sh does this). Bump
`versionCode`/`versionName` in `app/build.gradle` for each release.

## Logging in

The login screen has one tab per service. One is enough; the others can be added
later from ⋮ → **Link account**.

### Telegram

1. Type your phone number with country code and tap **Send code**.
2. Enter the code Telegram sends you, in the Telegram app on another device or
   by SMS.
3. If your account has two-step verification, type its password.

### Signal

1. Open Signal on this phone, go to **Settings → Linked devices**, tap **+** and
   scan the code UniChat shows. Your contacts and groups come from your account.
2. No Signal app on this phone? Tap **Register the number here instead** and
   verify by SMS. This signs that number out of the official Signal app.
3. To pull your contact list from your account, use **Restore contacts (PIN)**
   and type your Signal PIN.

### WhatsApp

1. Scan the QR code with the phone that runs your WhatsApp (WhatsApp →
   Settings → Linked devices → Link a device), or type your phone number with
   country code, tap **Get pairing code** and enter it in the WhatsApp
   notification.
2. Codes refresh on their own, so whatever is on screen is always valid.
3. Recent messages sync in seconds; opening a chat pulls older ones from your
   phone, which has to be online.
