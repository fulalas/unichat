# UniChat — project instructions

UniChat is a three-protocol messenger:

- **WhatsApp** — Go bridge / whatsmeow (`gobridge/`, see `Bridge.kt`)
- **Telegram** — TDLib via `tdjson/`, JSON interface (see `Tg.kt`)
- **Signal** — Go bridge / signalmeow over libsignal (see `Signal.kt`)

## Comments (mandatory)

Write a comment only when it records a real failure, or a rule from WhatsApp,
Telegram, Signal or Android that would bring one back. Do not write file or
function descriptions, section headers, field notes, layout labels, or anything
that restates the code.

## Shared database

Chat ids are namespaced in the shared Db: Telegram rows use a `tg:` prefix,
Signal rows use `sg:`, and WhatsApp rows keep their bare JID.

## Native libraries

- `./build-tdlib.sh` builds libtdjson/libtdjni into `app/src/main/jniLibs`
- `./build-libsignal.sh` cross-compiles `libsignal_ffi.a` for the Go bridge

Both are cached, and `build.sh` runs them automatically when their output is
missing.

## Versioning (mandatory)

Every change must bump the version in `app/build.gradle`:

- Increment `versionCode` by 1.
- Bump `versionName` (semver) — decide the level yourself:
  - **patch**: bug fixes, visual tweaks, refactors
  - **minor**: new features
  - **major**: breaking changes or large reworks

Do this before building; `build.sh` names the output APK after `versionName`, so an unbumped version overwrites the previous APK.

## Building

- Full build (Go bridge + APK): `bash -c 'source ../toolchain/env.sh && ./build.sh'`
- APK only (no Go changes): `bash -c 'source ../toolchain/env.sh && ./build.sh --apk-only'`
- Install: `source ../toolchain/env.sh && adb install -r unichat-<version>.apk`
- Auto-install: `build.sh` installs the APK automatically when a device is connected (adb, `device` state); pass `--no-install` to skip.

The toolchain (JDK, Android SDK/NDK, Go, gradle) lives in a SIBLING directory,
`../toolchain/` — not inside the repo. `build.sh` sources `../toolchain/env.sh`
itself (or `$UNICHAT_ENV` if set), so sourcing it by hand is only needed for
running `adb`/`gradle` directly.

`build.sh` polls whatsmeow, signalmeow and TDLib once a week on its own (ISO
week stamp in `gobridge/ext/.upstream-checked`) — no manual update step.

Telegram needs `telegram.properties` with your own `api_id`/`api_hash`; it is
gitignored, and `build.sh` refuses to build without it.
