# UniChat

Minimal native Android client for **WhatsApp and Telegram in one app**.
WhatsApp goes over the real multi-device protocol through
[whatsmeow](https://github.com/tulir/whatsmeow) (the same Go library that powers
[nchat](https://github.com/d99kris/nchat)), compiled for Android with
`gomobile`. Telegram goes over the official
[TDLib](https://github.com/tdlib/td) JSON interface (vendored in `tdjson/td`,
built for Android by `build-tdlib.sh`). Both feed one Kotlin app built with
classic XML Views — no Compose, no bloat.

Link either account alone or both at once: their chats share one list, one
local database and the same screens, with a small badge marking which service a
chat belongs to.

WhatsApp links as a **companion device** (like WhatsApp Web): your phone's
official WhatsApp keeps working normally. Telegram signs in as a normal
Telegram client with your phone number (no secret chats).

## Features

- WhatsApp login by QR code **and** pairing code (pairing codes auto-refresh,
  so the code on screen is always valid); Telegram login by phone number →
  login code → two-step password when your account has one
- Both accounts in one chat list, with avatars, snippets, timestamps, unread
  badges, a per-service badge and live "typing… / recording voice…" indicators
- Search box to filter chats
- Edit your own profile — avatar, name and About — from the ⋮ menu; with both
  accounts linked, account actions (profile, privacy, logout) ask which one
- Link the second account later from ⋮ → Link account
- Conversations with text, images (uncropped, tap for fullscreen), voice
  messages (play and record) and documents (tap to open)
- Presence in the chat header: online / last seen / typing / recording
- Tap a message for its actions (reply, forward, edit, copy, react, share); tap
  a reply's quote to jump to the original — fetched from the server (or your
  phone, on WhatsApp) if it isn't synced yet
- Select several messages (long-press, then tap) to forward, copy or delete
  them together (forwards keep their original order; delete only when all yours)
- Appears in Android's share sheet: share text, images or any file from other
  apps straight into a chat
- Per-chat incoming-message notifications for both services (sender, avatar,
  preview, grouped; suppressed for the chat currently on screen, for muted
  chats and during history backfill)
- Lean history sync: WhatsApp syncs the last 20 messages per chat at link time
  (older ones come from your phone, which must be online); Telegram pulls the
  newest 60 messages when you open a chat. Both fetch older pages on demand
- Local SQLite history shared by the two services, foreground service to stay
  connected (with a hidden, transparent status-bar icon), true-black OLED dark
  theme

## Prerequisites

- Go 1.25+, `gomobile` (`go install golang.org/x/mobile/cmd/gomobile@latest && gomobile init`)
- JDK 17
- Android SDK: platform 35, build-tools 35, NDK r27
- Gradle 8.13 (or set `$GRADLE` to your gradle binary)
- For the TDLib step (`build-tdlib.sh`, run automatically on a fresh clone):
  CMake 3.x plus a host C/C++ toolchain (`make`, `gcc`/`g++`) — it builds
  TDLib's host stage and, if `gperf` is not installed, gperf itself
- Network access on that first build: it downloads OpenSSL 3.3.2 and gperf 3.1
  (whatsmeow is fetched from GitHub too — it is not vendored)

If you keep the toolchain in a sibling `../toolchain/env.sh`, `build.sh` sources
it automatically; otherwise point `$UNICHAT_ENV` at your own env script or just
have the tools on `PATH` (with `ANDROID_HOME` set, or a `local.properties`
containing `sdk.dir`).

## Building

TDLib is a git submodule (`tdjson/td`), not vendored — check it out first:

```sh
git submodule update --init --recursive
```

whatsmeow is not committed either: `build.sh` fetches it into
`gobridge/ext/whatsmeow` and applies `gobridge/ext/whatsmeow-local.patch`.

```sh
./build.sh                   # full build: Go bridge + aar + release APK
./build.sh --apk-only        # Kotlin/resource changes only (much faster)
./build.sh --no-install      # don't install onto a connected device afterwards
```

Every invocation produces a release build and drops the final APK in the
repo root as `unichat-<version>.apk` (version from `versionName` in
`app/build.gradle`, e.g. `unichat-0.6.0.apk`).

The Telegram native libs are not in git: on a fresh clone `build.sh` notices
they are missing and runs `./build-tdlib.sh` first, which compiles OpenSSL and
TDLib from source for arm64-v8a. That takes a long time — but only once:
intermediates are cached in `tdjson/ext/` and the libs land in
`app/src/main/jniLibs/<abi>/`. It can also be run on its own, optionally for a
subset of ABIs:

```sh
./build-tdlib.sh                          # arm64-v8a armeabi-v7a x86_64
./build-tdlib.sh arm64-v8a                # just one (the APK then needs the rest)
```

**A build installs automatically** whenever a device is attached and in `device`
state — pass `--no-install` to skip that. With no device connected the build
just prints the command to run later:

```sh
source ../toolchain/env.sh
adb install -r unichat-<version>.apk
```

Updates install in place and keep both sessions — no need to link again — as
long as the APK is signed with `keystore/unichat.keystore` (build.sh does this
automatically). Bump `versionCode`/`versionName` in `app/build.gradle` for each
release.

## Logging in

The login screen has one tab per service. Linking just one is enough; the other
can be added later from ⋮ → **Link account**.

### WhatsApp

1. Install and open UniChat.
2. Either scan the QR code with the phone that runs your official WhatsApp
   (WhatsApp → Settings → Linked devices → Link a device), or type your phone
   number (international format, e.g. `34612345678`) and tap
   **Get pairing code**, then enter the code in the WhatsApp notification
   "Enter code to link new device".
3. Enter the code within ~2 minutes; if it rotates, use whatever code the app
   currently shows — it is always valid.
4. After linking, recent history syncs in seconds; opening a chat pulls older
   messages from your phone.

### Telegram

1. Open the **Telegram** tab and type your phone number in international
   format, then tap **Send code**.
2. Enter the login code Telegram sends you (in the Telegram app on another
   device, or by SMS).
3. If your account has two-step verification, type its password when asked.
4. Chats appear as TDLib loads them; opening a chat fetches its recent
   messages.

## Architecture notes

- `gobridge/bridge.go` exposes a small gomobile-friendly API
  (`Init/StartLogin/RequestPairCode/SendTextMessage/SendImageMessage/
  SendAudioMessage/SendDocumentMessage/DownloadFile/RequestChatHistory/
  SubscribePresence/SetMyName/SetAbout/SetProfilePicture/...`) plus an
  `EventListener` callback interface implemented in Kotlin (`Bridge.kt`).
- Media tokens: incoming media messages carry an opaque `fileId`
  (`img:`/`vid:`/`aud:`/`doc:`/`stk:` + the marshalled protobuf); `DownloadFile`
  reconstructs the proto and downloads/decrypts via whatsmeow. Location messages
  reuse the same field for their `lat,lng` coordinates instead.
- Voice notes are recorded as Opus in Ogg (`MediaRecorder`, API 29+) and sent
  as WhatsApp push-to-talk messages.
- The WhatsApp login socket dies ~160 s after showing unscanned QR codes;
  the bridge restarts it automatically and re-issues pairing codes.
- Telegram: `Tg.kt` drives TDLib over its JSON interface — one thread runs the
  receive loop for every update, blocking request/response pairs are matched by
  `"@extra"`. `tdjson/jni/tdjni.c` is the JNI shim behind `TdJson.kt`; it passes
  byte arrays in both directions because JNI's modified UTF-8 (CESU-8 for emoji)
  is not valid UTF-8 for TDLib.
- Shared store: Telegram rows live in the same tables as WhatsApp ones under
  `tg:`-prefixed ids, so the chat list, chat screen and notifications work for
  both without protocol-specific screens.
- Local storage: `unichat.db` (chats/contacts/messages for both services),
  `files/wm/` (whatsmeow session, avatars, media) and `files/tg/` (TDLib
  database and downloaded Telegram files).
