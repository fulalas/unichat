#!/bin/bash
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"

APK_ONLY=0
INSTALL=1
for arg in "$@"; do
    case "$arg" in
        --apk-only) APK_ONLY=1 ;;
        --no-install) INSTALL=0 ;;
        *) echo "unknown argument: $arg" >&2; exit 1 ;;
    esac
done

if [ ! -f "$DIR/telegram.properties" ]; then
    echo "telegram.properties not found." >&2
    echo "  cp telegram.properties.template telegram.properties" >&2
    echo "  then fill in your own api_id and api_hash from https://my.telegram.org" >&2
    exit 1
fi

adb_bin() {
    local sdk="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
    local adb="${sdk:+$sdk/platform-tools/adb}"
    [ -x "$adb" ] && { echo "$adb"; return 0; }
    command -v adb 2>/dev/null
}
# Serial of the single ready device, or empty. With several attached, `adb
# install` fails outright ("more than one device/emulator") and set -e then
# aborted the script AFTER a successful build; pick one explicitly instead.
adb_device_serial() {
    local adb; adb=$(adb_bin) || return 0
    [ -n "$adb" ] || return 0
    "$adb" devices 2>/dev/null | awk 'NR>1 && $2=="device"{print $1}' | head -1
}

if [ -n "$UNICHAT_ENV" ] && [ -f "$UNICHAT_ENV" ]; then
    source "$UNICHAT_ENV"
elif [ -f "$DIR/../toolchain/env.sh" ]; then
    source "$DIR/../toolchain/env.sh"
fi

GRADLE="${GRADLE:-gradle}"

# whatsmeow is not vendored; build.sh tracks the tip of upstream main, fetching
# it into gobridge/ext/whatsmeow (mapped via a replace directive in go.mod) and
# applying a small local patch on top. The checked-out SHA is stamped in the
# (gitignored) tree so a refetch happens only when upstream main has moved;
# delete gobridge/ext/whatsmeow to force one.
WHATSMEOW_REPO="https://github.com/tulir/whatsmeow.git"
WHATSMEOW_BRANCH="main"

# signalmeow (Signal) rides in the same way, but pinned to a tag rather than
# tracking main: pkg/libsignalgo is hand-written cgo against one libsignal ABI,
# and build-libsignal.sh reads the required libsignal version out of the
# checkout. Moving this pin means rebuilding libsignal_ffi.a, which the stamp in
# gobridge/ext/libsignal handles automatically.
SIGNALMEOW_REPO="https://github.com/mautrix/signal.git"
SIGNALMEOW_TAG="v0.2608.0"

# Upstream is polled once a week, not once a build: whatsmeow used to hit the
# network on every run while signalmeow and TDLib sat on their pins forever, so
# the two libraries carrying the Signal protocol drifted behind while only
# WhatsApp stayed current. ISO year-week, so the window rolls over on a Monday
# rather than 7 days after whenever the last build happened to be.
UPDATE_STAMP="$DIR/gobridge/ext/.upstream-checked"
week_due() { [ "$(cat "$UPDATE_STAMP" 2>/dev/null)" != "$(date +%G-W%V)" ]; }
mark_checked() { mkdir -p "$(dirname "$UPDATE_STAMP")"; date +%G-W%V > "$UPDATE_STAMP"; }

# Plain version tags only: signalmeow also publishes -rc and helper tags, and
# sort -V happily ranks those above the release we want.
latest_tag() {
    git ls-remote --tags --refs "$1" 2>/dev/null |
        sed 's#.*refs/tags/##' | grep -E '^v[0-9]+(\.[0-9]+)*$' | sort -V | tail -1
}

# Both ensure_* helpers fetch into "$dest.new" and swap it in only on full
# success. Deleting the existing tree FIRST meant any failure after that point
# (a dropped connection mid-fetch, a patch that no longer applies) left no
# checkout at all — and if the machine then went offline, the project became
# unbuildable.
swap_in_checkout() {
    local staging="$1" dest="$2"
    rm -rf "$staging/.git"
    rm -rf "$dest"
    mv "$staging" "$dest"
}

# go.mod replaces both modules with the trees under gobridge/ext, so tidy only
# resolves them once the tree is in place. Upstream may also have added module
# requirements; reconciling go.sum here beats failing the build with "missing
# go.sum entry" on every later run.
tidy_gobridge() {
    ( cd "$DIR/gobridge" && go mod tidy ) || { echo "$1" >&2; exit 1; }
}

ensure_signalmeow() {
    local dest="$DIR/gobridge/ext/signal"
    local stamp="$dest/.sg-tag"
    local patch="$DIR/gobridge/ext/signal-local.patch"
    local pinned="$SIGNALMEOW_TAG" have
    have=$(cat "$stamp" 2>/dev/null)
    # Whatever is already checked out wins over the hardcoded pin for the rest
    # of the week: the adopted tag was only ever held in this variable, so the
    # next build of the same week re-read the pin, saw the stamp disagree, and
    # re-cloned the OLDER tag — an upgrade on Monday, a silent downgrade on
    # Tuesday, every week.
    [ -n "$have" ] && SIGNALMEOW_TAG="$have"
    if week_due; then
        local newest
        newest=$(latest_tag "$SIGNALMEOW_REPO")
        if [ -n "$newest" ] && [ "$newest" != "$SIGNALMEOW_TAG" ]; then
            echo "== signalmeow: upstream has $newest (was $SIGNALMEOW_TAG) =="
            SIGNALMEOW_TAG="$newest"
        fi
    fi
    if [ -d "$dest" ] && [ "$(cat "$stamp" 2>/dev/null)" = "$SIGNALMEOW_TAG" ]; then
        echo "== signalmeow up to date @ $SIGNALMEOW_TAG =="
        return
    fi
    echo "== Fetching signalmeow @ $SIGNALMEOW_TAG =="
    local staging="$dest.new"
    rm -rf "$staging"
    if ! git clone -q --depth 1 --branch "$SIGNALMEOW_TAG" "$SIGNALMEOW_REPO" "$staging"; then
        rm -rf "$staging"
        echo "signalmeow: fetch of $SIGNALMEOW_TAG failed" >&2
        [ -d "$dest" ] && { echo "   keeping the existing checkout" >&2; return; }
        exit 1
    fi
    # Unlike whatsmeow's, this patch is not optional: it is what makes the cgo
    # link flags Android-correct (the NDK has no libstdc++), so an unpatched tree
    # fails at link, not at runtime. Fail loudly instead of building it.
    if ! git -C "$staging" apply "$patch"; then
        rm -rf "$staging"
        echo "signalmeow: signal-local.patch did not apply against $SIGNALMEOW_TAG" >&2
        # Only fatal for a tag someone chose. An automatic weekly bump landing on
        # a release the patch predates would otherwise kill every build until
        # someone refreshed it by hand — the calendar must not break the build.
        if [ "$SIGNALMEOW_TAG" != "$pinned" ]; then
            echo "   staying on $pinned; refresh the patch to take $SIGNALMEOW_TAG" >&2
            SIGNALMEOW_TAG="$pinned"
            [ "$have" = "$pinned" ] && return
        else
            echo "   refresh gobridge/ext/signal-local.patch; the Go bridge cannot link without it" >&2
            exit 1
        fi
        staging="$dest.new"
        rm -rf "$staging"
        git clone -q --depth 1 --branch "$SIGNALMEOW_TAG" "$SIGNALMEOW_REPO" "$staging" || exit 1
        git -C "$staging" apply "$patch" || { echo "signalmeow: $pinned no longer patches" >&2; exit 1; }
    fi
    swap_in_checkout "$staging" "$dest"
    # Re-assert the replace before tidying. Without it tidy happily resolves
    # mautrix-signal to the published v0.2608.0 in the module cache instead of
    # this patched checkout — the build still succeeds, so the only symptom is
    # the unpatched cgo flags coming back and the app dying at startup on a
    # missing libc++_shared.so. Cost an afternoon once; not relying on go.mod
    # keeping the line.
    ( cd "$DIR/gobridge" &&
      go mod edit -require=go.mau.fi/mautrix-signal@v0.0.0 \
                  -replace=go.mau.fi/mautrix-signal=./ext/signal ) || {
        echo "signalmeow: go mod edit failed for $SIGNALMEOW_TAG" >&2
        exit 1
    }
    tidy_gobridge "signalmeow: go mod tidy failed after updating to $SIGNALMEOW_TAG"
    echo "$SIGNALMEOW_TAG" > "$stamp"
}

ensure_whatsmeow() {
    local dest="$DIR/gobridge/ext/whatsmeow"
    local stamp="$dest/.wm-commit"
    local patch="$DIR/gobridge/ext/whatsmeow-local.patch"
    local lsout latest
    if [ -d "$dest" ] && ! week_due; then
        echo "== whatsmeow up to date @ $(cut -c1-12 "$stamp" 2>/dev/null) =="
        return
    fi
    # Separate ls-remote's exit status from its output: a non-zero exit means the
    # remote is unreachable (offline); a zero exit with empty output means the
    # branch doesn't exist — a hard error, never a reason to build something stale.
    if lsout=$(git ls-remote "$WHATSMEOW_REPO" "refs/heads/$WHATSMEOW_BRANCH" 2>/dev/null); then
        latest=$(printf '%s' "$lsout" | cut -f1)
        if [ -z "$latest" ]; then
            echo "whatsmeow: branch '$WHATSMEOW_BRANCH' not found at $WHATSMEOW_REPO" >&2
            exit 1
        fi
    else
        [ -d "$dest" ] && { echo "== whatsmeow: upstream unreachable; using existing checkout =="; return; }
        echo "whatsmeow: upstream unreachable and nothing fetched yet" >&2
        exit 1
    fi
    if [ -d "$dest" ] && [ "$(cat "$stamp" 2>/dev/null)" = "$latest" ]; then
        echo "== whatsmeow up to date @ ${latest:0:12} =="
        return
    fi
    if [ -d "$dest" ]; then
        echo "== Updating whatsmeow -> ${latest:0:12} (replacing existing checkout) =="
    else
        echo "== Fetching whatsmeow @ ${latest:0:12} (upstream $WHATSMEOW_BRANCH) =="
    fi
    local staging="$dest.new"
    rm -rf "$staging"
    mkdir -p "$staging"
    if ! (
        git -C "$staging" init -q &&
        git -C "$staging" remote add origin "$WHATSMEOW_REPO" &&
        git -C "$staging" fetch -q --depth 1 origin "$latest" &&
        git -C "$staging" checkout -q FETCH_HEAD
    ); then
        rm -rf "$staging"
        echo "whatsmeow: fetch of ${latest:0:12} failed; keeping the existing checkout" >&2
        [ -d "$dest" ] && return
        exit 1
    fi
    if [ -f "$patch" ]; then
        # The patch records the commit it was written against; surface the drift
        # instead of leaving it to be discovered by a failed apply.
        local base
        base=$(sed -n 's/^Base-Commit:[[:space:]]*\([0-9a-f]*\).*/\1/p' "$patch" | head -1)
        if [ -n "$base" ] && [ "$base" != "$latest" ]; then
            echo "   note: patch base ${base:0:12} != upstream ${latest:0:12}"
        fi
        if git -C "$staging" apply "$patch"; then
            echo "   applied whatsmeow-local.patch"
        else
            # NOT fatal: an upstream rework of a patched file would otherwise
            # block every build until someone hand-refreshed the patch. The
            # unpatched tree still works, but setting the own About and profile
            # picture stops working, so the warning has to be acted on.
            echo "   WARNING: whatsmeow-local.patch did not apply against ${latest:0:12}" >&2
            echo "   building an unpatched tree: setting About / profile picture will fail" >&2
            echo "   refresh the patch against ${latest:0:12}" >&2
        fi
    fi
    swap_in_checkout "$staging" "$dest"
    tidy_gobridge "whatsmeow: go mod tidy failed after updating to ${latest:0:12}"
    # Stamp LAST: the stamp is what makes later runs skip this function entirely.
    # Writing it before tidy had succeeded marked a half-updated checkout as good
    # forever — a single transient tidy failure then left every later build dying
    # with "missing go.sum entry" until someone deleted the tree by hand.
    echo "$latest" > "$dest/.wm-commit"
}

if [ "$APK_ONLY" != 1 ]; then
    ensure_whatsmeow
    ensure_signalmeow
    NDK_LLVM="${ANDROID_NDK_HOME:-$ANDROID_NDK_ROOT}/toolchains/llvm/prebuilt/linux-x86_64"
    # Must run after ensure_signalmeow: it reads the required libsignal version
    # out of the signalmeow checkout.
    "$DIR/build-libsignal.sh"
    echo "== Building Go bridge (whatsmeow + signalmeow) =="
    cd "$DIR/gobridge"
    # libsignalgo asks the linker for -lsignal_ffi; only this build knows where
    # the cross-compiled archive landed. Exported rather than passed inline
    # because gomobile shells out to go build and forwards the environment.
    # --gc-sections here rather than in -extldflags: gomobile splits -ldflags on
    # spaces before go build sees it, so a multi-flag -extldflags never survives
    # as one argument. CGO_LDFLAGS reaches the same external link intact.
    # Worth 46 MB -> 43 MB on libgojni.so, because most of libsignal_ffi.a is
    # never reached from the bridge.
    export CGO_LDFLAGS="-L$DIR/gobridge/ext/libsignal -Wl,--gc-sections"
    go build .
    # -androidapi must match minSdk in app/build.gradle, and the target list must
    # match its abiFilters — an APK whose native lib needs a newer API than
    # minSdk installs fine and then dies with UnsatisfiedLinkError at Bridge.init.
    MIN_SDK=$(sed -n 's/^[[:space:]]*minSdk[[:space:]]*\([0-9]*\).*/\1/p' \
        "$DIR/app/build.gradle" | head -1)
    : "${MIN_SDK:?could not read minSdk from app/build.gradle}"
    mkdir -p "$DIR/app/libs"
    # write to a temp path and move on success: an interrupted bind used to leave
    # a truncated aar that --apk-only would then happily build against
    # max-page-size=16384: devices launching with Android 15+ can use 16 KB
    # memory pages, and a .so whose LOAD segments are 4 KB-aligned (gomobile's
    # default) fails to load there — the APK installs and then dies with
    # UnsatisfiedLinkError at Bridge.init. build-tdlib.sh passes the same flag
    # for the TDLib libs, so both halves of the app stay loadable.
    # -s -w drops the Go symbol table and DWARF, which gradle otherwise fails to
    # strip ("Unable to strip the following libraries") and ships whole:
    # 82 MB -> 46 MB of libgojni.so on its own.
    gomobile bind -target=android/arm64 -androidapi "$MIN_SDK" \
        -ldflags="-s -w -extldflags=-Wl,-z,max-page-size=16384" \
        -javapkg=org.unichat -o "$DIR/app/libs/wmbridge-new.aar" .
    # libsignal drags in BoringSSL's C++ half, and the NDK clang driver will
    # happily satisfy it with libc++_shared.so. gomobile packages only
    # libgojni.so, so such an aar builds and installs and then dies at startup
    # with "dlopen failed: library libc++_shared.so not found" — caught here
    # once already, after it had been installed on a phone. Fail the build
    # instead: the fix is -static-libstdc++ in signal-local.patch.
    # Read the real dynamic section, not `strings`: the name of a library the
    # binary merely mentions is not the same as one it will dlopen.
    SO_TMP=$(mktemp -d)
    unzip -q -o -j "$DIR/app/libs/wmbridge-new.aar" jni/arm64-v8a/libgojni.so -d "$SO_TMP"
    BAD=$("$NDK_LLVM/bin/llvm-readelf" -d "$SO_TMP/libgojni.so" 2>/dev/null \
        | grep -oE "libc\+\+_shared\.so|libstdc\+\+\.so" | sort -u)
    rm -rf "$SO_TMP"
    if [ -n "$BAD" ]; then
        rm -f "$DIR/app/libs/wmbridge-new.aar"
        echo "gomobile: libgojni.so needs $BAD, which is not packaged in the aar" >&2
        echo "   the app would install and then die at Bridge.init" >&2
        echo "   the C++ runtime libsignal pulls in must be linked statically:" >&2
        echo "   check that gobridge/go.mod still replaces mautrix-signal with ./ext/signal," >&2
        echo "   and that signal-local.patch applied (-lc++_static -lc++abi)" >&2
        exit 1
    fi
    mv "$DIR/app/libs/wmbridge-new.aar" "$DIR/app/libs/wmbridge.aar"
    if [ -f "$DIR/app/libs/wmbridge-new-sources.jar" ]; then
        mv -f "$DIR/app/libs/wmbridge-new-sources.jar" "$DIR/app/libs/wmbridge-sources.jar"
    fi
fi

TD_ABIS=(arm64-v8a)
TD_MISSING=()
for abi in "${TD_ABIS[@]}"; do
    for lib in libtdjson.so libtdjni.so; do
        if [ ! -f "$DIR/app/src/main/jniLibs/$abi/$lib" ]; then
            TD_MISSING+=("$abi")
            break
        fi
    done
done
# The tip is resolved here and handed down, so the weekly stamp governs TDLib
# the same way it governs the other two — build-tdlib.sh would otherwise poll
# again on its own.
TD_REBUILD=0
if [ ${#TD_MISSING[@]} -gt 0 ]; then
    echo "== TDLib libs missing for ${TD_MISSING[*]} =="
    TD_REBUILD=1
elif [ "$APK_ONLY" != 1 ] && week_due; then
    TD_LATEST=$(git ls-remote https://github.com/tdlib/td.git refs/heads/master 2>/dev/null | cut -f1)
    if [ -n "$TD_LATEST" ] && [ "$TD_LATEST" != "$(cat "$DIR/tdjson/td/.td-commit" 2>/dev/null)" ]; then
        echo "== TDLib: upstream master moved to ${TD_LATEST:0:12}; rebuilding =="
        TD_MISSING=("${TD_ABIS[@]}")
        TD_REBUILD=1
        export TD_COMMIT="$TD_LATEST"
    fi
fi
if [ "$TD_REBUILD" = 1 ]; then
    "$DIR/build-tdlib.sh" "${TD_MISSING[@]}"
fi

[ "$APK_ONLY" != 1 ] && mark_checked

echo "== Building release APK =="
cd "$DIR"
"$GRADLE" assembleRelease --no-daemon

VERSION=$(sed -n "s/^[[:space:]]*versionName[[:space:]]*[\"']\([^\"']*\)[\"'].*/\1/p" \
    "$DIR/app/build.gradle" | head -1)
if [ -z "$VERSION" ]; then
    echo "error: could not read versionName from app/build.gradle" >&2
    exit 1
fi
APK="$DIR/unichat-${VERSION}.apk"
cp "$DIR/app/build/outputs/apk/release/app-release.apk" "$APK"
echo
echo "APK: $APK"

SERIAL=$(adb_device_serial)
# `|| true`: adb_bin ends in `command -v adb`, which exits non-zero when adb is
# nowhere to be found, and under `set -e` an unguarded assignment killed the
# script right here — after a successful build and copy, and before the else
# branch below that exists precisely for machines without adb.
ADB=$(adb_bin) || true
if [ "$INSTALL" = 1 ] && [ -n "$SERIAL" ] && [ -n "$ADB" ]; then
    echo "== Device $SERIAL detected — installing (adb install -r) =="
    # don't abort the whole script on a failed install: the APK is already built
    # and copied, and the usual causes (signature mismatch, no space) are
    # actionable on their own
    if ! "$ADB" -s "$SERIAL" install -r "$APK"; then
        echo "install failed; the built APK is still at $APK" >&2
    fi
else
    echo "Install with: adb install -r $APK"
fi
