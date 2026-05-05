#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Cross-compile a minimal FFmpeg 8.0 for Android (arm64-v8a, armeabi-v7a,
# x86, x86_64) producing static libraries (.a) suitable for linking into
# libUVCCamera.so.
#
# Scope is strictly "demux/mux + codec_id constants": NO encoders, NO decoders
# except the few we need for verification, NO swscale, NO avfilter, NO
# avdevice. This keeps each ABI's payload to a handful of MB.
#
# Run from inside WSL (Ubuntu/Debian) or any Linux shell. Requires:
#   - Android NDK (Linux-x86_64 host build) with a working clang toolchain
#   - make, pkg-config, yasm OR nasm, python3, patch, diffutils, tar
#
# Usage:
#   FFMPEG_SRC=/abs/path/to/FFmpeg-master \
#   NDK=/abs/path/to/android-ndk-r26b \
#   OUT=/abs/path/to/UVCAndroid/libuvccamera/src/main/jni/ffmpeg \
#   API=21 \
#   ./build-android.sh [abi]
#
# If [abi] is omitted, all four ABIs are built in sequence.
# -----------------------------------------------------------------------------

set -euo pipefail

: "${FFMPEG_SRC:?set FFMPEG_SRC to the FFmpeg source tree}"
: "${NDK:?set NDK to an Android NDK r23+ (Linux-x86_64 host)}"
: "${OUT:?set OUT to libuvccamera/src/main/jni/ffmpeg}"
: "${API:=21}"
# When FFMPEG_SRC lives on a slow mount (e.g. WSL /mnt/c/...), keeping the
# object-file build tree on a native filesystem shrinks build time from
# ~60 min per ABI to a few minutes. Override BUILD_ROOT to put that tree
# somewhere fast; only the final .a/.h files are installed back to OUT.
: "${BUILD_ROOT:=$HOME/ffmpeg-android-build}"

JOBS="$(nproc 2>/dev/null || echo 4)"
HOST_TAG="linux-x86_64"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"

if [[ ! -x "$TOOLCHAIN/bin/clang" ]]; then
    echo "ERROR: NDK clang not found at $TOOLCHAIN/bin/clang" >&2
    exit 1
fi

# Common FFmpeg configure flags: disable everything we don't need, enable only
# what is required to mux the formats an UVC capture card produces (MJPEG
# bitstream + rawvideo) into the common delivery containers.
COMMON_CONFIG=(
    --disable-everything
    --disable-doc
    --disable-programs
    --disable-avdevice
    --disable-avfilter
    --disable-swscale
    --disable-network
    --disable-debug
    --disable-iconv
    --disable-bzlib
    --disable-zlib
    --disable-lzma
    --disable-sdl2
    --disable-autodetect
    --enable-static
    --disable-shared
    --enable-small
    --enable-pic
    --enable-cross-compile
    --target-os=android

    # Muxers we want to support end-to-end
    --enable-muxer=avi
    --enable-muxer=mov
    --enable-muxer=mp4
    --enable-muxer=matroska

    # Demuxers (useful for on-device probe / verification)
    --enable-demuxer=avi
    --enable-demuxer=mov
    --enable-demuxer=matroska
    --enable-demuxer=mjpeg
    --enable-demuxer=rawvideo

    # Parsers needed to chop MJPEG/raw bitstreams into frames when reading back
    --enable-parser=mjpeg

    # Codec IDs we attach to streams. No encoders/decoders are pulled in; we
    # only need the codec descriptor tables so avformat knows the stream kind.
    # mjpeg decoder/encoder pulled in defensively so ffprobe-style tools work.
    --enable-decoder=mjpeg
    --enable-decoder=rawvideo

    # Protocol: only local file. No network.
    --enable-protocol=file

    # BSFs: none required for -c copy of MJPEG/raw. Leave disabled.
)

build_one() {
    local abi="$1"
    local triple arch cpu extra_cflags extra_ldflags
    case "$abi" in
        arm64-v8a)
            triple="aarch64-linux-android"
            arch="aarch64"
            cpu="armv8-a"
            extra_cflags=""
            extra_ldflags=""
            ;;
        armeabi-v7a)
            triple="armv7a-linux-androideabi"
            arch="arm"
            cpu="armv7-a"
            extra_cflags="-mfpu=neon -mfloat-abi=softfp"
            extra_ldflags=""
            ;;
        x86)
            triple="i686-linux-android"
            arch="x86"
            cpu="i686"
            # FFmpeg's text-relocation safety on 32-bit x86 with PIC is iffy;
            # keep asm disabled for x86 to avoid relocation errors in the
            # Android dynamic linker.
            extra_cflags="-mssse3"
            extra_ldflags=""
            ;;
        x86_64)
            triple="x86_64-linux-android"
            arch="x86_64"
            cpu="x86-64"
            extra_cflags=""
            extra_ldflags=""
            ;;
        *)
            echo "unsupported abi: $abi" >&2
            return 1
            ;;
    esac

    local cc="$TOOLCHAIN/bin/${triple}${API}-clang"
    local cxx="$TOOLCHAIN/bin/${triple}${API}-clang++"
    local ar="$TOOLCHAIN/bin/llvm-ar"
    local ranlib="$TOOLCHAIN/bin/llvm-ranlib"
    local strip="$TOOLCHAIN/bin/llvm-strip"
    local nm="$TOOLCHAIN/bin/llvm-nm"

    if [[ ! -x "$cc" ]]; then
        echo "ERROR: cross-compiler not found: $cc" >&2
        return 1
    fi

    local build_dir="$BUILD_ROOT/$abi"
    local prefix="$OUT/$abi"
    rm -rf "$build_dir"
    mkdir -p "$build_dir" "$prefix"

    echo "=============================================================="
    echo "  Building FFmpeg for $abi  (API $API, cpu=$cpu)"
    echo "  prefix = $prefix"
    echo "=============================================================="

    pushd "$build_dir" >/dev/null

    local disable_asm=""
    if [[ "$abi" == "x86" ]]; then
        disable_asm="--disable-asm"
    fi

    "$FFMPEG_SRC/configure" \
        --prefix="$prefix" \
        --arch="$arch" \
        --cpu="$cpu" \
        --sysroot="$TOOLCHAIN/sysroot" \
        --cc="$cc" \
        --cxx="$cxx" \
        --ar="$ar" \
        --ranlib="$ranlib" \
        --strip="$strip" \
        --nm="$nm" \
        --extra-cflags="-Os -fPIC $extra_cflags" \
        --extra-ldflags="$extra_ldflags" \
        $disable_asm \
        "${COMMON_CONFIG[@]}"

    make -j"$JOBS"
    make install

    popd >/dev/null

    echo "-- built $abi, artifacts under $prefix"
}

abis=("$@")
if [[ ${#abis[@]} -eq 0 ]]; then
    abis=(arm64-v8a armeabi-v7a x86 x86_64)
fi

for a in "${abis[@]}"; do
    build_one "$a"
done

echo
echo "All requested ABIs built. Output layout:"
for a in "${abis[@]}"; do
    echo "  $OUT/$a/{include,lib}"
done
