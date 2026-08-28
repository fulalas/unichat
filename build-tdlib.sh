#!/bin/bash
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
[ -f "$DIR/../toolchain/env.sh" ] && source "$DIR/../toolchain/env.sh"

SDK="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
# Check the SDK/NDK up front: `ls | tail` hides ls's failure (a pipeline's status
# is the last command's), so with no SDK set NDK silently became "" and the
# script only died much later inside cmake/Configure with an unrelated message.
if [ -z "$SDK" ] || [ ! -d "$SDK/ndk" ]; then
    echo "no Android SDK with an ndk/ directory: set ANDROID_HOME or ANDROID_SDK_ROOT" >&2
    exit 1
fi
NDK=$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1)
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
    echo "no NDK installed under $SDK/ndk" >&2
    exit 1
fi
API=29   # must match minSdk in app/build.gradle
JOBS=$(nproc)
ABIS=("${@:-arm64-v8a}")
[ $# -eq 0 ] && ABIS=(arm64-v8a)
# Reject unknown ABIs here: openssl_target/clang_target are called inside command
# substitutions, where an abort would only kill the subshell and hand the caller
# an empty target string.
for abi in "${ABIS[@]}"; do
    case "$abi" in
        arm64-v8a|armeabi-v7a|x86_64|x86) ;;
        *) echo "unknown abi: $abi" >&2; exit 1 ;;
    esac
done

EXT="$DIR/tdjson/ext"
TD_SRC="$DIR/tdjson/td"
TD_URL=https://github.com/tdlib/td.git
TD_BRANCH=master
TD_STAMP="$TD_SRC/.td-commit"

# TDLib tracks upstream's tip, like whatsmeow, rather than a hash written down
# here: a pinned commit only ever moved when someone remembered to edit it, and
# it had to be kept in step with a git submodule that a downloaded zip does not
# carry at all. build.sh polls weekly and only calls this script when the tip
# has actually moved.
command -v git >/dev/null || { echo "TDLib: git is required to fetch it" >&2; exit 1; }
TD_WANT="${TD_COMMIT:-}"
[ -n "$TD_WANT" ] || TD_WANT=$(git ls-remote "$TD_URL" "refs/heads/$TD_BRANCH" 2>/dev/null | cut -f1)
if [ -z "$TD_WANT" ]; then
    # Offline is only fatal with nothing to fall back on; otherwise build what
    # is already checked out rather than refusing to build at all.
    [ -f "$TD_SRC/CMakeLists.txt" ] || { echo "TDLib: upstream unreachable and nothing fetched yet" >&2; exit 1; }
    echo "== TDLib: upstream unreachable; using the existing checkout =="
    TD_WANT=$(cat "$TD_STAMP" 2>/dev/null)
elif [ "$(cat "$TD_STAMP" 2>/dev/null)" != "$TD_WANT" ] || [ ! -f "$TD_SRC/CMakeLists.txt" ]; then
    echo "== Fetching TDLib @ ${TD_WANT:0:12} (upstream $TD_BRANCH) =="
    rm -rf "$TD_SRC"
    mkdir -p "$TD_SRC"
    git -C "$TD_SRC" init -q
    git -C "$TD_SRC" remote add origin "$TD_URL"
    # by SHA and shallow, so this costs one commit rather than TDLib's history;
    # a server that refuses a SHA fetch falls back to a full clone
    if git -C "$TD_SRC" fetch -q --depth 1 origin "$TD_WANT" 2>/dev/null; then
        git -C "$TD_SRC" checkout -q FETCH_HEAD
    else
        rm -rf "$TD_SRC"
        git clone -q "$TD_URL" "$TD_SRC" || { echo "TDLib: clone failed" >&2; exit 1; }
        git -C "$TD_SRC" checkout -q "$TD_WANT" || { echo "TDLib: checkout failed" >&2; exit 1; }
    fi
    [ -f "$TD_SRC/CMakeLists.txt" ] || { echo "TDLib: fetch failed" >&2; exit 1; }
    echo "$TD_WANT" > "$TD_STAMP"
fi

mkdir -p "$EXT"

if ! command -v gperf >/dev/null && [ ! -x "$EXT/hosttools/bin/gperf" ]; then
    echo "== Building gperf (host) =="
    cd "$EXT"
    curl -fsLO https://ftp.gnu.org/gnu/gperf/gperf-3.1.tar.gz
    rm -rf gperf-3.1
    tar xf gperf-3.1.tar.gz
    (cd gperf-3.1 && ./configure --prefix="$EXT/hosttools" -q && make -s -j"$JOBS" && make -s install)
    rm -rf gperf-3.1 gperf-3.1.tar.gz
fi
export PATH="$EXT/hosttools/bin:$PATH"

OPENSSL_VER=3.3.2
openssl_target() {
    case "$1" in
        arm64-v8a) echo android-arm64 ;;
        armeabi-v7a) echo android-arm ;;
        x86_64) echo android-x86_64 ;;
        x86) echo android-x86 ;;
        # unreachable: the ABI list is validated above. A bare `exit` here would
        # only end the command substitution anyway.
        *) return 1 ;;
    esac
}
build_openssl() {
    local abi="$1" prefix="$EXT/openssl-$1"
    [ -f "$prefix/lib/libcrypto.a" ] && return
    # separate assignment: `./Configure "$(openssl_target ...)"` would swallow a
    # non-zero status and configure OpenSSL for an empty target
    local target
    target=$(openssl_target "$abi")
    echo "== Building OpenSSL $OPENSSL_VER for $abi =="
    cd "$EXT"
    if [ ! -d "openssl-$OPENSSL_VER" ]; then
        # Download to a .part name and extract into a scratch directory, renaming
        # both only on success. tar creates openssl-$OPENSSL_VER/ the moment it
        # starts, so an interrupted extract (or an HTTP error page saved as the
        # tarball, which -f now prevents) used to leave a truncated tree that
        # every later run happily reused and copied into the per-ABI build.
        rm -rf "openssl-$OPENSSL_VER.tmp"
        mkdir -p "openssl-$OPENSSL_VER.tmp"
        curl -fsL -o "openssl-$OPENSSL_VER.tar.gz.part" \
            "https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_VER/openssl-$OPENSSL_VER.tar.gz"
        mv "openssl-$OPENSSL_VER.tar.gz.part" "openssl-$OPENSSL_VER.tar.gz"
        tar xf "openssl-$OPENSSL_VER.tar.gz" -C "openssl-$OPENSSL_VER.tmp" --strip-components=1
        mv "openssl-$OPENSSL_VER.tmp" "openssl-$OPENSSL_VER"
    fi
    rm -rf "openssl-build-$abi"
    cp -r "openssl-$OPENSSL_VER" "openssl-build-$abi"
    (
        cd "openssl-build-$abi"
        export ANDROID_NDK_ROOT="$NDK"
        export PATH="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"
        ./Configure "$target" -D__ANDROID_API__=$API no-shared no-tests \
            --prefix="$prefix" --libdir=lib >/dev/null
        make -s -j"$JOBS" build_libs >/dev/null
        make -s install_dev >/dev/null
    )
    rm -rf "openssl-build-$abi"
}

# Stamped with the TDLib commit, not just touched: this stage GENERATES
# td_api.h/telegram_api.h from the .tl scheme, so keeping it across a source
# update left the new C++ compiling against the old generated API and the build
# died on "no member named ... in namespace td::td_api". The per-ABI tree goes
# too, since its objects were compiled against those same headers.
TD_HAVE=$(cat "$TD_STAMP" 2>/dev/null || git -C "$TD_SRC" rev-parse HEAD)
if [ "$(cat "$EXT/build-host/.prepared" 2>/dev/null)" != "$TD_HAVE" ]; then
    echo "== TDLib host stage (prepare_cross_compiling) @ ${TD_HAVE:0:12} =="
    rm -rf "$EXT/build-host"
    for abi in "${ABIS[@]}"; do rm -rf "$EXT/build-$abi"; done
    cmake -S "$TD_SRC" -B "$EXT/build-host" -DCMAKE_BUILD_TYPE=Release >/dev/null
    cmake --build "$EXT/build-host" --target prepare_cross_compiling -j "$JOBS"
    echo "$TD_HAVE" > "$EXT/build-host/.prepared"
fi

STRIP="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"

# MinSizeRel alone emits one section per translation unit, so the linker can
# only keep or drop whole objects. Per-function sections plus --gc-sections let
# it drop the unreached parts of TDLib's generated API.
#
# No -fvisibility=hidden: libtdjson.so already exports only 14 symbols, so
# TDLib is managing visibility itself and forcing it globally could only hide
# the td_json_client_* entry points the app resolves at runtime.
#
# No LTO either. CMAKE_INTERPROCEDURAL_OPTIMIZATION=ON fails at link against
# the NDK's own prebuilt libz.a: "module flags 'EnableSplitLTOUnit': IDs have
# conflicting values". That archive is not ours to rebuild, so the two cannot
# be made to agree.
TD_SIZE_CFLAGS="-ffunction-sections -fdata-sections -fno-ident"
TD_SIZE_LDFLAGS="-Wl,--gc-sections -Wl,--as-needed"
for abi in "${ABIS[@]}"; do
    build_openssl "$abi"
    echo "== Building TDLib (tdjson) for $abi =="
    cmake -S "$TD_SRC" -B "$EXT/build-$abi" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-$API \
        -DCMAKE_BUILD_TYPE=MinSizeRel \
        -DOPENSSL_ROOT_DIR="$EXT/openssl-$abi" \
        -DOPENSSL_INCLUDE_DIR="$EXT/openssl-$abi/include" \
        -DOPENSSL_CRYPTO_LIBRARY="$EXT/openssl-$abi/lib/libcrypto.a" \
        -DOPENSSL_SSL_LIBRARY="$EXT/openssl-$abi/lib/libssl.a" \
        -DOPENSSL_USE_STATIC_LIBS=ON \
        -DCMAKE_C_FLAGS="$TD_SIZE_CFLAGS" \
        -DCMAKE_CXX_FLAGS="$TD_SIZE_CFLAGS -fvisibility-inlines-hidden" \
        -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384 $TD_SIZE_LDFLAGS" >/dev/null
    cmake --build "$EXT/build-$abi" --target tdjson -j "$JOBS"
    out="$DIR/app/src/main/jniLibs/$abi"
    mkdir -p "$out"
    cp "$EXT/build-$abi/libtdjson.so" "$out/libtdjson.so"
    "$STRIP" --strip-unneeded "$out/libtdjson.so"
    echo "   -> $out/libtdjson.so ($(du -h "$out/libtdjson.so" | cut -f1))"
done

CLANG="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
clang_target() {
    case "$1" in
        arm64-v8a) echo aarch64-linux-android$API ;;
        armeabi-v7a) echo armv7a-linux-androideabi$API ;;
        x86_64) echo x86_64-linux-android$API ;;
        x86) echo i686-linux-android$API ;;
        # unreachable (see the ABI validation above); never emit an empty target
        *) return 1 ;;
    esac
}
for abi in "${ABIS[@]}"; do
    out="$DIR/app/src/main/jniLibs/$abi"
    target=$(clang_target "$abi")
    "$CLANG" --target="$target" -shared -fPIC -O2 \
        -Wl,-z,max-page-size=16384 \
        -o "$out/libtdjni.so" "$DIR/tdjson/jni/tdjni.c" -L"$out" -ltdjson
    "$STRIP" --strip-unneeded "$out/libtdjni.so"
    echo "   -> $out/libtdjni.so"
done
echo "== TDLib build done =="
