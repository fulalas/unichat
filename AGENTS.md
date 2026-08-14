# UniChat — project instructions

UniChat is a dual-protocol messenger: WhatsApp (Go bridge / whatsmeow, like
WMChat) plus Telegram (TDLib via `tdjson/`, JSON interface, see `Tg.kt`).
Chat ids are namespaced: Telegram rows use a `tg:` prefix in the shared Db.
`./build-tdlib.sh` builds libtdjson/libtdjni into `app/src/main/jniLibs`
(cached; build.sh runs it automatically when the libs are missing).

## Versioning (mandatory)

Every change must bump the version in `app/build.gradle`:

- Increment `versionCode` by 1.
- Bump `versionName` (semver) — decide the level yourself:
  - **patch**: bug fixes, visual tweaks, refactors
  - **minor**: new features
  - **major**: breaking changes or large reworks

Do this before building; `build.sh` names the output APK after `versionName`, so an unbumped version overwrites the previous APK.

## Building

- Once a day update whatsmeow lib from upstream
- Full build (Go bridge + APK): `bash -c 'source ../toolchain/env.sh && ./build.sh'`
- APK only (no Go changes): `bash -c 'source ../toolchain/env.sh && ./build.sh --apk-only'`
- Install: `source ../toolchain/env.sh && adb install -r unichat-<version>.apk`
- Auto-install: `build.sh` installs the APK automatically when a device is connected (adb, `device` state); pass `--no-install` to skip.

The toolchain (JDK, Android SDK/NDK, Go, gradle) lives in a SIBLING directory,
`../toolchain/` — not inside the repo. `build.sh` sources `../toolchain/env.sh`
itself (or `$UNICHAT_ENV` if set), so sourcing it by hand is only needed for
running `adb`/`gradle` directly.
