#!/bin/bash
# Cross-compiles libsignal_ffi.a for Android and caches it in
# gobridge/ext/libsignal. signalmeow (gobridge/ext/signal) is cgo bindings over
# this archive, so the Go bridge cannot link without it.
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
[ -f "$DIR/../toolchain/env.sh" ] && source "$DIR/../toolchain/env.sh"

API=29   # must match minSdk in app/build.gradle
ABI=arm64-v8a
RUST_TARGET=aarch64-linux-android

OUT="$DIR/gobridge/ext/libsignal"
ARCHIVE="$OUT/libsignal_ffi.a"
STAMP="$OUT/.version"
SRC="${LIBSIGNAL_SRC:-$DIR/../toolchain/libsignal-src}"

# The libsignal version is not ours to choose: libsignalgo's Go declarations
# mirror a specific libsignal_ffi ABI, and a mismatched archive links fine and
# then corrupts memory at the first FFI call. signalmeow records the version it
# was generated against, so read it rather than pinning a second copy here.
VERSION_FILE="$DIR/gobridge/ext/signal/pkg/libsignalgo/version.go"
[ -f "$VERSION_FILE" ] || { echo "libsignal: $VERSION_FILE not found (fetch signalmeow first)" >&2; exit 1; }
VERSION=$(sed -n 's/.*const Version = "\(.*\)".*/\1/p' "$VERSION_FILE")
[ -n "$VERSION" ] || { echo "libsignal: could not parse version from $VERSION_FILE" >&2; exit 1; }

NDK="${ANDROID_NDK_HOME:-$ANDROID_NDK_ROOT}"
[ -d "$NDK" ] || { echo "libsignal: ANDROID_NDK_HOME is not set to an NDK" >&2; exit 1; }
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"

# $TC/bin first: boring-sys shells out to clang/ar/ranlib by bare name, and the
# libclang bindgen loads resolves its builtin headers (stddef.h and friends)
# relative to the toolchain it finds. Without it the BoringSSL bindings fail to
# generate — invisibly at first, because a cached boring-sys output makes the
# build skip bindgen entirely.
export PATH="$TC/bin:$PATH"
export ANDROID_NDK_HOME="$NDK" ANDROID_NDK_ROOT="$NDK"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TC/bin/aarch64-linux-android$API-clang"
export CC_aarch64_linux_android="$TC/bin/aarch64-linux-android$API-clang"
export CXX_aarch64_linux_android="$TC/bin/aarch64-linux-android$API-clang++"
export AR_aarch64_linux_android="$TC/bin/llvm-ar"
export RANLIB_aarch64_linux_android="$TC/bin/llvm-ranlib"
# bindgen (via boring-sys) dlopens libclang to parse the BoringSSL headers. The
# NDK's copy is the musl-linked one under musl/lib; there is no other libclang
# in the toolchain, and without LIBCLANG_PATH the build dies in boring-sys.
export LIBCLANG_PATH="$TC/musl/lib"
# Those headers must be parsed as Android, not as the host: the default target
# picks up glibc headers that the NDK sysroot does not have.
export BINDGEN_EXTRA_CLANG_ARGS="--target=aarch64-linux-android$API --sysroot=$TC/sysroot"
# -crt-static keeps rustc off the fully-static libc path, which does not link
# into a shared .so.
#
# The rest is size: this archive is ~165 MB unoptimised and every byte of it
# that survives the link ships in the APK. Only codegen flags are listed —
# libsignal-ffi is a staticlib, so there is no Rust link step and any
# -Clink-arg would be silently ignored; --gc-sections runs later, on the
# gomobile link, and is what actually drops the unreached code.
#
# Deliberately NOT set:
#   -Cpanic=abort         libsignal catches unwinds at the FFI boundary to turn
#                         Rust panics into error codes; aborting there would
#                         take the whole app down instead.
#   -Cforce-unwind-tables=no  panic=unwind needs those tables.
#   -Ctarget-cpu=<x>      the APK is one arm64 build for every arm64 phone;
#                         tuning past baseline armv8-a would crash older ones.
# -Z flags are fine here: libsignal's rust-toolchain pins a nightly.
export RUSTFLAGS="-Ctarget-feature=-crt-static \
    -Zlocation-detail=none -Zfmt-debug=shallow"
# LTO goes through the cargo profile, not RUSTFLAGS: cargo compiles
# dependencies with -Cembed-bitcode=no whenever its own profile says LTO is
# off, and rustc rejects that combination outright ("options -C embed-bitcode=no
# and -C lto are incompatible"). Setting it here keeps cargo and rustc agreeing.
export CARGO_PROFILE_RELEASE_LTO=fat
export CARGO_PROFILE_RELEASE_CODEGEN_UNITS=1
export CARGO_PROFILE_RELEASE_OPT_LEVEL=s
export CARGO_PROFILE_RELEASE_STRIP=symbols
export CARGO_PROFILE_RELEASE_DEBUG=0
# BoringSSL is built by cmake through boring-sys, so RUSTFLAGS misses it.
# Per-function/data sections are what let --gc-sections drop it by the piece
# rather than all-or-nothing.
#
# Target-scoped, not plain CFLAGS/CXXFLAGS: those are also read by the clang
# that bindgen runs over BoringSSL's headers, and replacing its flag set drops
# the compiler's own include dir — the build then dies on a missing stddef.h.
CFLAGS_TARGET="-Os -ffunction-sections -fdata-sections -fno-ident"
export CFLAGS_aarch64_linux_android="$CFLAGS_TARGET"
export CXXFLAGS_aarch64_linux_android="$CFLAGS_TARGET -fvisibility-inlines-hidden"

# Key the cache on the flags too, not just the version: editing RUSTFLAGS above
# and getting the previous archive back is a silent no-op that reads as "the
# flags did nothing".
KEY="$VERSION $RUST_TARGET $(printf '%s|%s|%s' "$RUSTFLAGS" "$CFLAGS_TARGET" \
    "$CARGO_PROFILE_RELEASE_LTO/$CARGO_PROFILE_RELEASE_CODEGEN_UNITS/$CARGO_PROFILE_RELEASE_OPT_LEVEL/$CARGO_PROFILE_RELEASE_STRIP" \
    | md5sum | cut -c1-12)"
if [ -f "$ARCHIVE" ] && [ "$(cat "$STAMP" 2>/dev/null)" = "$KEY" ]; then
    echo "== libsignal_ffi up to date @ $VERSION ($ABI) =="
    exit 0
fi

# Only past the cache check: a correct archive is already here on most builds,
# and demanding a Rust toolchain (and a network fetch of the source) to hand it
# back made an APK-only change impossible on a machine without cargo.
for CMD in cargo git protoc; do
    command -v "$CMD" >/dev/null || { echo "libsignal: missing '$CMD'" >&2; exit 1; }
done

if [ ! -d "$SRC/.git" ]; then
    git clone --depth 1 --branch "$VERSION" https://github.com/signalapp/libsignal.git "$SRC"
else
    git -C "$SRC" fetch --depth 1 origin tag "$VERSION"
    git -C "$SRC" checkout -q "$VERSION"
fi

echo "== Building libsignal_ffi $VERSION for $ABI (LTO, this takes a few minutes) =="
# libsignal pins its own nightly via rust-toolchain; rustup installs that on
# first use, but the Android target has to be added to it explicitly or the
# build fails on a missing std for the target.
( cd "$SRC" && rustup target add "$RUST_TARGET" >/dev/null 2>&1 || true )
( cd "$SRC" && cargo build -p libsignal-ffi --release --target "$RUST_TARGET" )

mkdir -p "$OUT"
cp "$SRC/target/$RUST_TARGET/release/libsignal_ffi.a" "$ARCHIVE"
# Stamp LAST, and include the target: a stamp written before the copy, or one
# that ignored the ABI, would mark a host-arch archive as a good Android build.
echo "$KEY" > "$STAMP"
echo "== libsignal_ffi.a $VERSION ready ($(du -h "$ARCHIVE" | cut -f1)) =="
