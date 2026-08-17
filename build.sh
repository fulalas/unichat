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

ensure_whatsmeow() {
    local dest="$DIR/gobridge/ext/whatsmeow"
    local stamp="$dest/.wm-commit"
    local patch="$DIR/gobridge/ext/whatsmeow-local.patch"
    local lsout latest
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
    # Fetch into a scratch directory and swap it in only once everything
    # succeeded. Deleting the existing tree FIRST meant any failure after that
    # point (a dropped connection mid-fetch, a patch that no longer applies) left
    # no checkout at all — and if the machine then went offline, the
    # "upstream unreachable and nothing fetched yet" path made the project
    # unbuildable.
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
    rm -rf "$staging/.git"
    rm -rf "$dest"
    mv "$staging" "$dest"
    # upstream may have added module requirements; reconcile go.sum now rather
    # than failing the build with "missing go.sum entry" on every later run.
    # go.mod's replace points at $dest, so tidy needs the tree already in place.
    ( cd "$DIR/gobridge" && go mod tidy ) || {
        echo "whatsmeow: go mod tidy failed after updating to ${latest:0:12}" >&2
        exit 1
    }
    # Stamp LAST: the stamp is what makes later runs skip this function entirely.
    # Writing it before tidy had succeeded marked a half-updated checkout as good
    # forever — a single transient tidy failure then left every later build dying
    # with "missing go.sum entry" until someone deleted the tree by hand.
    echo "$latest" > "$dest/.wm-commit"
}

if [ "$APK_ONLY" != 1 ]; then
    ensure_whatsmeow
    echo "== Building Go bridge (whatsmeow) =="
    cd "$DIR/gobridge"
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
    gomobile bind -target=android/arm64 -androidapi "$MIN_SDK" \
        -ldflags="-extldflags=-Wl,-z,max-page-size=16384" \
        -javapkg=org.unichat -o "$DIR/app/libs/wmbridge-new.aar" .
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
if [ ${#TD_MISSING[@]} -gt 0 ]; then
    echo "== TDLib libs missing for ${TD_MISSING[*]}; running build-tdlib.sh =="
    "$DIR/build-tdlib.sh" "${TD_MISSING[@]}"
fi

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
