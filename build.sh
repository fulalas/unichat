#!/bin/bash
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"

ARGS=("$@")
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

ENV_FILE="$DIR/../toolchain/env.sh"
[ -n "$UNICHAT_ENV" ] && [ -f "$UNICHAT_ENV" ] && ENV_FILE="$UNICHAT_ENV"
[ -f "$ENV_FILE" ] && source "$ENV_FILE"
export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
TOOLCHAIN="$(mkdir -p "$(dirname "$ENV_FILE")" && cd "$(dirname "$ENV_FILE")" && pwd)"

WHATSMEOW_REPO="https://github.com/tulir/whatsmeow.git"
WHATSMEOW_BRANCH="main"
SIGNALMEOW_REPO="https://github.com/mautrix/signal.git"

UPDATE_DAYS="${UNICHAT_UPDATE_DAYS:-7}"
UPDATE_STAMP="$DIR/gobridge/ext/.upstream-checked"
update_due() {
    local last
    last=$(cat "$UPDATE_STAMP" 2>/dev/null)
    [[ "$last" =~ ^[0-9]+$ ]] || return 0
    [ $(( $(date +%s) - last )) -ge $(( UPDATE_DAYS * 86400 )) ]
}
mark_checked() { mkdir -p "$(dirname "$UPDATE_STAMP")"; date +%s > "$UPDATE_STAMP"; }

VERSIONS="$DIR/versions.properties"
VERSIONS_GOOD="$DIR/versions.properties.good"
version_of() { sed -n "s#^$1=##p" "$VERSIONS" | tail -1; }

maven_latest() {
    local g="${1%%:*}" a="${1##*:}" repo v
    for repo in https://dl.google.com/android/maven2 https://repo1.maven.org/maven2; do
        v=$(curl -fsSL "$repo/${g//.//}/$a/maven-metadata.xml" 2>/dev/null |
            grep -oE '<version>[0-9]+(\.[0-9]+)*</version>' | sed 's/<[^>]*>//g' | sort -V | tail -1)
        [ -n "$v" ] && { echo "$v"; return; }
    done
}

resolve_versions() {
    local out list coord
    echo "== Resolving the latest Gradle, Android SDK and library versions =="
    yes | sdkmanager --install "cmdline-tools;latest" >/dev/null 2>&1 || true
    if [ -d "$ANDROID_HOME/cmdline-tools/latest-2" ]; then
        rm -rf "$ANDROID_HOME/cmdline-tools/latest"
        mv "$ANDROID_HOME/cmdline-tools/latest-2" "$ANDROID_HOME/cmdline-tools/latest"
    fi
    list=$(android sdk list --all 2>/dev/null) || true
    out=$(mktemp)
    {
        echo "gradle=$(curl -fsSL https://services.gradle.org/versions/current 2>/dev/null |
            sed -n 's/.*"version" *: *"\([^"]*\)".*/\1/p')"
        echo "agp=$(maven_latest com.android.tools.build:gradle)"
        echo "kotlin=$(maven_latest org.jetbrains.kotlin:kotlin-gradle-plugin)"
        echo "jdk=$(curl -fsSL https://api.adoptium.net/v3/info/available_releases 2>/dev/null | tr -d '\n ' |
            sed -n 's/.*"most_recent_feature_release":\([0-9]*\).*/\1/p')"
        echo "protoc=$(curl -fsSL https://api.github.com/repos/protocolbuffers/protobuf/releases/latest 2>/dev/null |
            sed -n 's/.*"tag_name": *"v\([0-9.]*\)".*/\1/p')"
        echo "ndk=$(printf '%s\n' "$list" | grep -oE '^\s*ndk/[0-9.]+\s' | sed 's#.*/##; s/\s*$//' | sort -V | tail -1)"
        echo "compileSdk=$(printf '%s\n' "$list" | grep -oE '^\s*platforms/android-[0-9]+' | sed 's/.*-//' | sort -n | tail -1)"
        for coord in $(grep -oE "lib\('[^']+'\)" "$DIR/app/build.gradle" | sed "s/lib('\(.*\)')/\1/"); do
            echo "${coord/:/\/}=$(maven_latest "$coord")"
        done
    } > "$out"
    if grep -q '=$' "$out"; then
        rm -f "$out"
        [ -f "$VERSIONS" ] || { echo "could not resolve tool and library versions (offline?)" >&2; exit 1; }
        echo "   WARNING: could not resolve every version; keeping the previous set" >&2
        return
    fi
    mv "$out" "$VERSIONS"
}

install_versions() {
    local gradle ndk protoc jdk
    jdk=$(version_of jdk)
    if [ -n "$jdk" ]; then
        if [ ! -x "$TOOLCHAIN/jdk-$jdk/bin/java" ]; then
            echo "== Installing JDK $jdk =="
            rm -rf "$TOOLCHAIN/jdk-$jdk.new"
            mkdir -p "$TOOLCHAIN/jdk-$jdk.new"
            curl -fsSL "https://api.adoptium.net/v3/binary/latest/$jdk/ga/linux/x64/jdk/hotspot/normal/eclipse" |
                tar -xz -C "$TOOLCHAIN/jdk-$jdk.new" --strip-components=1
            mv "$TOOLCHAIN/jdk-$jdk.new" "$TOOLCHAIN/jdk-$jdk"
        fi
        export JAVA_HOME="$TOOLCHAIN/jdk-$jdk"
        export PATH="$JAVA_HOME/bin:$PATH"
    fi
    gradle=$(version_of gradle)
    ndk=$(version_of ndk)
    if [ ! -d "$ANDROID_HOME/ndk/$ndk" ]; then
        echo "== Installing Android NDK $ndk =="
        yes | android sdk install "ndk/$ndk" >/dev/null
    fi
    export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$ndk"
    if [ ! -x "$TOOLCHAIN/gradle-$gradle/bin/gradle" ]; then
        echo "== Installing Gradle $gradle =="
        curl -fsSL -o "$TOOLCHAIN/gradle-$gradle.zip.part" \
            "https://services.gradle.org/distributions/gradle-$gradle-bin.zip"
        unzip -q -o "$TOOLCHAIN/gradle-$gradle.zip.part" -d "$TOOLCHAIN"
        rm -f "$TOOLCHAIN/gradle-$gradle.zip.part"
    fi
    GRADLE="$TOOLCHAIN/gradle-$gradle/bin/gradle"
    protoc=$(version_of protoc)
    if [ "$(cat "$TOOLCHAIN/protoc/.version" 2>/dev/null)" != "$protoc" ]; then
        echo "== Installing protoc $protoc =="
        rm -rf "$TOOLCHAIN/protoc.new"
        mkdir -p "$TOOLCHAIN/protoc.new"
        curl -fsSL -o "$TOOLCHAIN/protoc.zip.part" \
            "https://github.com/protocolbuffers/protobuf/releases/download/v$protoc/protoc-$protoc-linux-x86_64.zip"
        unzip -q "$TOOLCHAIN/protoc.zip.part" -d "$TOOLCHAIN/protoc.new"
        rm -f "$TOOLCHAIN/protoc.zip.part"
        echo "$protoc" > "$TOOLCHAIN/protoc.new/.version"
        rm -rf "$TOOLCHAIN/protoc"
        mv "$TOOLCHAIN/protoc.new" "$TOOLCHAIN/protoc"
    fi
    export PATH="$TOOLCHAIN/protoc/bin:$PATH"
}

prune_old_tools() {
    local d sdk
    sdk=$(version_of compileSdk)
    for d in "$TOOLCHAIN"/gradle-[0-9]*; do
        [ -x "$d/bin/gradle" ] && [ "$d" != "$TOOLCHAIN/gradle-$(version_of gradle)" ] && rm -rf "$d"
    done
    for d in "$TOOLCHAIN"/jdk "$TOOLCHAIN"/jdk-[0-9]*; do
        [ -x "$d/bin/java" ] && [ "$d" != "$JAVA_HOME" ] && rm -rf "$d"
    done
    for d in "$ANDROID_HOME"/ndk/*; do
        [ "$d" = "$ANDROID_NDK_HOME" ] || rm -rf "$d"
    done
    for d in "$ANDROID_HOME"/platforms/*; do
        case "${d##*/}" in android-"$sdk"|android-"$sdk".*) ;; *) rm -rf "$d" ;; esac
    done
}

retry_with_good_versions() {
    local rc=$?
    [ "$rc" = 0 ] && return
    if [ -n "$UNICHAT_RETRY" ] || [ ! -f "$VERSIONS_GOOD" ] || cmp -s "$VERSIONS" "$VERSIONS_GOOD"; then
        exit "$rc"
    fi
    echo "== Build failed with the new versions; retrying with the last working ones ==" >&2
    cp "$VERSIONS_GOOD" "$VERSIONS"
    UNICHAT_RETRY=1 exec "$0" "${ARGS[@]}"
}
trap retry_with_good_versions EXIT

if [ -z "$UNICHAT_RETRY" ] && { [ ! -f "$VERSIONS" ] || { [ "$APK_ONLY" != 1 ] && update_due; }; }; then
    resolve_versions
fi
install_versions

# Plain version tags only: signalmeow also publishes -rc and helper tags, and
# sort -V happily ranks those above the release we want.
latest_tag() {
    git ls-remote --tags --refs "$1" "$2*" 2>/dev/null |
        sed "s#.*refs/tags/$2##" | grep -E '^[0-9]+(\.[0-9]+)*$' | sort -V | tail -1 | sed "s#^#$2#"
}

newer_tag() {
    printf '%s\n%s\n' "$1" "$2" | grep -v '^$' | sort -V | tail -1
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

refresh_go_deps() {
    local gb="$DIR/gobridge" upgrade=0 bak
    if [ ! -f "$gb/go.mod" ] || update_due; then
        upgrade=1
    elif [ ! "$gb/ext/whatsmeow/.wm-commit" -nt "$gb/go.mod" ] &&
         [ ! "$gb/ext/signal/.sg-tag" -nt "$gb/go.mod" ]; then
        return
    fi
    echo "== Resolving Go dependencies =="
    bak=$(mktemp -d)
    cp "$gb/go.mod" "$gb/go.sum" "$bak/" 2>/dev/null || true
    if ( cd "$gb" &&
         { [ -f go.mod ] || {
             go mod init unichat/gobridge 2>/dev/null &&
             go mod edit -require=go.mau.fi/whatsmeow@v0.0.0 -replace=go.mau.fi/whatsmeow=./ext/whatsmeow \
                         -require=go.mau.fi/mautrix-signal@v0.0.0 -replace=go.mau.fi/mautrix-signal=./ext/signal &&
             go get -tool golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind; }; } &&
         { [ "$upgrade" = 0 ] || go get -u ./... tool go@latest; } &&
         go mod tidy && touch go.mod &&
         go install golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind ); then
        rm -rf "$bak"
        return
    fi
    if [ -f "$bak/go.mod" ]; then
        echo "   WARNING: could not resolve Go dependencies; keeping the previous set" >&2
        cp "$bak"/go.* "$gb/"
        rm -rf "$bak"
        return
    fi
    rm -rf "$bak"
    rm -f "$gb/go.mod" "$gb/go.sum"
    echo "Go dependencies could not be resolved" >&2
    exit 1
}

ensure_signalmeow() {
    local dest="$DIR/gobridge/ext/signal"
    local stamp="$dest/.sg-tag"
    local patch="$DIR/gobridge/ext/signal-local.patch"
    local have tag
    have=$(cat "$stamp" 2>/dev/null || true)
    [ -d "$dest" ] || have=""
    tag="$have"
    if [ -z "$have" ] || update_due; then
        tag=$(newer_tag "$(latest_tag "$SIGNALMEOW_REPO" v)" "$have")
    fi
    if [ -z "$tag" ]; then
        echo "signalmeow: upstream unreachable and nothing fetched yet" >&2
        exit 1
    fi
    if [ "$tag" = "$have" ]; then
        echo "== signalmeow up to date @ $tag =="
        return
    fi
    echo "== Fetching signalmeow @ $tag =="
    local staging="$dest.new"
    rm -rf "$staging"
    if ! git clone -q --depth 1 --branch "$tag" "$SIGNALMEOW_REPO" "$staging"; then
        echo "signalmeow: fetch of $tag failed" >&2
    elif ! git -C "$staging" apply "$patch"; then
        echo "signalmeow: signal-local.patch did not apply against $tag" >&2
    else
        swap_in_checkout "$staging" "$dest"
        echo "$tag" > "$stamp"
        return
    fi
    rm -rf "$staging"
    if [ -n "$have" ]; then
        echo "   keeping $have; refresh gobridge/ext/signal-local.patch for $tag" >&2
        return
    fi
    echo "   refresh gobridge/ext/signal-local.patch; the Go bridge cannot build without a usable checkout" >&2
    exit 1
}

ensure_whatsmeow() {
    local dest="$DIR/gobridge/ext/whatsmeow"
    local stamp="$dest/.wm-commit"
    local patch="$DIR/gobridge/ext/whatsmeow-local.patch"
    local lsout latest
    if [ -d "$dest" ] && ! update_due; then
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
    echo "$latest" > "$stamp"
}

if [ "$APK_ONLY" != 1 ]; then
    ensure_whatsmeow
    ensure_signalmeow
    refresh_go_deps
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
    MIN_SDK=$(sed -n 's/^[[:space:]]*minSdk[[:space:]]*=\{0,1\}[[:space:]]*\([0-9]*\).*/\1/p' \
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
    # Asserted, not assumed: with ANDROID_NDK_HOME/ROOT unset this path is
    # "/toolchains/..." and readelf simply does not run — stderr goes to
    # /dev/null, the pipeline's status comes from sort, BAD is empty, and the
    # check below passes on exactly the aar it exists to stop.
    if [ ! -x "$NDK_LLVM/bin/llvm-readelf" ]; then
        rm -f "$DIR/app/libs/wmbridge-new.aar"
        echo "llvm-readelf not found at $NDK_LLVM/bin — set ANDROID_NDK_HOME" >&2
        echo "   refusing to ship an unverified libgojni.so" >&2
        exit 1
    fi
    SO_TMP=$(mktemp -d)
    # `|| true`: unzip exits non-zero when the entry is absent (or the aar is
    # corrupt), and under set -e that killed the script HERE, skipping the
    # tailored error branch below and leaving $SO_TMP and wmbridge-new.aar behind.
    unzip -q -o -j "$DIR/app/libs/wmbridge-new.aar" jni/arm64-v8a/libgojni.so -d "$SO_TMP" || true
    if [ ! -f "$SO_TMP/libgojni.so" ]; then
        rm -rf "$SO_TMP" "$DIR/app/libs/wmbridge-new.aar"
        echo "gomobile: no jni/arm64-v8a/libgojni.so in the aar" >&2
        exit 1
    fi
    BAD=$("$NDK_LLVM/bin/llvm-readelf" -d "$SO_TMP/libgojni.so" \
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
TD_REBUILD=0
if [ ${#TD_MISSING[@]} -gt 0 ]; then
    echo "== TDLib libs missing for ${TD_MISSING[*]} =="
    TD_REBUILD=1
elif [ "$APK_ONLY" != 1 ] && update_due; then
    TD_LATEST=$(git ls-remote https://github.com/tdlib/td.git refs/heads/master 2>/dev/null | cut -f1)
    OSSL_LATEST=$(latest_tag https://github.com/openssl/openssl.git openssl-)
    OSSL_LATEST="${OSSL_LATEST#openssl-}"
    OSSL_BUILT=$(sed 's/-android.*//' "$DIR/tdjson/ext/openssl-${TD_ABIS[0]}/.build-key" 2>/dev/null || true)
    if [ -n "$TD_LATEST" ] && [ "$TD_LATEST" != "$(cat "$DIR/tdjson/td/.td-commit" 2>/dev/null)" ]; then
        echo "== TDLib: upstream master moved to ${TD_LATEST:0:12}; rebuilding =="
        TD_REBUILD=1
    fi
    if [ -n "$OSSL_LATEST" ] && [ "$OSSL_LATEST" != "$OSSL_BUILT" ]; then
        echo "== OpenSSL: $OSSL_LATEST released (built with ${OSSL_BUILT:-none}); rebuilding TDLib =="
        TD_REBUILD=1
    fi
    if [ "$TD_REBUILD" = 1 ]; then
        TD_UPDATE_ONLY=1
        TD_MISSING=("${TD_ABIS[@]}")
        [ -n "$TD_LATEST" ] && export TD_COMMIT="$TD_LATEST"
        [ -n "$OSSL_LATEST" ] && export OPENSSL_VER="$OSSL_LATEST"
    fi
fi
if [ "$TD_REBUILD" = 1 ] && ! "$DIR/build-tdlib.sh" "${TD_MISSING[@]}"; then
    [ "$TD_UPDATE_ONLY" = 1 ] || exit 1
    echo "   WARNING: TDLib update failed; keeping the TDLib already built" >&2
fi

[ "$APK_ONLY" != 1 ] && mark_checked

echo "== Building release APK =="
cd "$DIR"
"$GRADLE" assembleRelease --no-daemon
cp "$VERSIONS" "$VERSIONS_GOOD"
prune_old_tools

VERSION=$(sed -n "s/^[[:space:]]*versionName[[:space:]]*=\{0,1\}[[:space:]]*[\"']\([^\"']*\)[\"'].*/\1/p" \
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
