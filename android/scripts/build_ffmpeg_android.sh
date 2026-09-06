#!/usr/bin/env bash
# =============================================================================
# Cross-compile a static FFmpeg binary for Android arm64-v8a with the NDK.
# Output: ffmpeg binary → copied as libffmpeg_exec.so into jniLibs (W^X-safe exec).
#
# Enabled external libraries (all static):
#   libx264  (GPL encoder — parity with upstream codec chain)
#   libass + freetype + fribidi + harfbuzz (subtitle burn-in, Unicode + shaping)
#
# Host toolchain auto-selected: linux-x86_64 / linux-aarch64 / darwin-*
# =============================================================================
set -euo pipefail

FFMPEG_VERSION="${FFMPEG_VERSION:-7.1.1}"
X264_VERSION="${X264_VERSION:-stable}"
ROOT="$(mktemp -d)"
JOBS="$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)"
# Make OUT_DIR absolute NOW (before any cd) — the script cd's into build dirs later.
mkdir -p "$1"
OUT_DIR="$(cd "$1" && pwd)"
PREFIX="$ROOT/sysroot"
API=29

echo "==> workdir: $ROOT"
mkdir -p "$PREFIX"

# ---------- Host tools ----------
HOST_TAG="$(uname -s)-$(uname -m)"
case "$HOST_TAG" in
    Linux-x86_64)  HOST_TAG="linux-x86_64" ;;
    Linux-aarch64) HOST_TAG="linux-aarch64" ;;
    Darwin-arm64)  HOST_TAG="darwin-x86_64" ;;
    Darwin-x86_64) HOST_TAG="darwin-x86_64" ;;
    *) echo "Unsupported host: $HOST_TAG"; exit 1 ;;
esac

# ---------- NDK ----------
NDK_DIR="${ANDROID_NDK_HOME:-}"
if [ -z "$NDK_DIR" ] || [ ! -d "$NDK_DIR" ]; then
    NDK_DIR="$ROOT/ndk"
    NDK_VER="${NDK_VERSION:-r27c}"
    case "$HOST_TAG" in
        linux-*) NDK_ZIP="android-ndk-$NDK_VER-linux.zip" ;;
        *)       NDK_ZIP="android-ndk-$NDK_VER-darwin.zip" ;;
    esac
    echo "==> downloading NDK $NDK_VER"
    curl -sSL -o "$ROOT/ndk.zip" "https://dl.google.com/android/repository/$NDK_ZIP"
    unzip -q "$ROOT/ndk.zip" -d "$ROOT"
    mv "$ROOT/android-ndk-$NDK_VER" "$NDK_DIR"
fi
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/$HOST_TAG"
SYSROOT="$TOOLCHAIN/sysroot"
CC="$TOOLCHAIN/bin/aarch64-linux-android$API-clang"
CXX="$TOOLCHAIN/bin/aarch64-linux-android$API-clang++"
AR="$TOOLCHAIN/bin/llvm-ar"
RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
STRIP="$TOOLCHAIN/bin/llvm-strip"
export PATH="$TOOLCHAIN/bin:$PATH"
echo "==> NDK toolchain: $TOOLCHAIN"

COMMON="--host=aarch64-linux-android --enable-static --disable-shared --prefix=$PREFIX"
CFLAGS="-fPIC -O2 -DANDROID"

fetch() { # fetch <url> <dir>
    curl -sSL -o "$ROOT/$(basename "$1")" "$1"
    tar -xf "$ROOT/$(basename "$1")" -C "$ROOT"
    echo "==> fetched $(basename "$1")"
}

# ---------- 1. x264 ----------
cd "$ROOT"
if [ ! -d x264 ]; then
    git clone --depth 1 https://github.com/mirror/x264.git 2>/dev/null \
        || git clone --depth 1 https://code.videolan.org/videolan/x264.git
fi
cd x264
# x264's configure expects tools via cross-prefix; NDK clang names don't fit, so pass
# env-style overrides (CC etc. as exported vars) which its configure honors.
export CC="$CC" CXX="$CXX" AR="$AR" STRIP="$STRIP" RANLIB="$RANLIB"
./configure --host=aarch64-linux --enable-static --enable-pic --disable-cli \
    --cross-prefix="$TOOLCHAIN/bin/llvm-" --sysroot="$SYSROOT" --prefix="$PREFIX"
make -j"$JOBS" && make install

# ---------- 2. freetype ----------
# GitHub tag-archive lacks the dlg submodule (make check_out_submodule fails),
# so use the official release tarball with mirror fallbacks.
cd "$ROOT"
FT_OK=0
for FT_URL in \
    "https://github.com/freetype/freetype/releases/download/VER-2-13-3/ft-2.13.3.tar.xz" \
    "https://mirror.netcologne.de/savannah/freetype/freetype-2.13.3.tar.gz" \
    "https://download.savannah.gnu.org/releases/freetype/freetype-2.13.3.tar.gz"; do
    echo "==> trying $FT_URL"
    curl -sSfL --max-time 120 -o ft.src "$FT_URL" && tar -tf ft.src >/dev/null 2>&1 && FT_OK=1 && break
done
[ "$FT_OK" = "1" ] || { echo "freetype download failed"; exit 1; }
mkdir ft-src && tar -xf ft.src -C ft-src --strip-components=1
cd ft-src
./configure $COMMON --with-zlib=no --with-bzip2=no --with-png=no --with-harfbuzz=no --with-brotli=no \
    CC="$CC" CFLAGS="$CFLAGS" AR="$AR" RANLIB="$RANLIB"
make -j"$JOBS" && make install

# ---------- 3. fribidi ----------
cd "$ROOT"
fetch "https://github.com/fribidi/fribidi/releases/download/v1.0.16/fribidi-1.0.16.tar.xz" .
cd fribidi-1.0.16
./configure $COMMON --disable-debug --disable-docs \
    CC="$CC" CFLAGS="$CFLAGS" AR="$AR" RANLIB="$RANLIB"
make -j"$JOBS" && make install

# ---------- 4. harfbuzz ----------
cd "$ROOT"
fetch "https://github.com/harfbuzz/harfbuzz/releases/download/10.2.0/harfbuzz-10.2.0.tar.xz" .
cd harfbuzz-10.2.0
# meson cross file (harfbuzz dropped autotools)
cat > cross_mpt.ini <<EOF
[binaries]
c = '$CC'
cpp = '$CXX'
ar = '$AR'
strip = '$STRIP'
pkg-config = 'pkg-config'
[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
EOF
meson setup build --prefix="$PREFIX" --default-library=static --buildtype=release \
    -Dtests=disabled -Ddocs=disabled -Dbenchmark=disabled -Dintrospection=disabled \
    -Dutilities=disabled -Dicu=disabled -Dglib=disabled -Dgobject=disabled -Dcairo=disabled \
    --cross-file cross_mpt.ini
ninja -C build -j"$JOBS" && ninja -C build install

# ---------- 5. libass ----------
# Built with meson + fontprovider=none: no fontconfig/expat needed at all —
# fonts are memory-loaded from the app's fontsdir (libass load_fonts_from_dir).
cd "$ROOT"
fetch "https://github.com/libass/libass/releases/download/0.17.3/libass-0.17.3.tar.xz" .
cd libass-0.17.3
cat > cross_mpt.ini <<EOF
[binaries]
c = '$CC'
cpp = '$CXX'
ar = '$AR'
strip = '$STRIP'
pkg-config = 'pkg-config'
[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
EOF
export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig"
meson setup build --prefix="$PREFIX" --default-library=static --buildtype=release \
    -Drequire-system-font-provider=false \
    --cross-file cross_mpt.ini
ninja -C build -j"$JOBS" && ninja -C build install

# ---------- 6. FFmpeg ----------
cd "$ROOT"
curl -sSL -o ffmpeg.tar.gz "https://github.com/FFmpeg/FFmpeg/archive/refs/tags/n$FFMPEG_VERSION.tar.gz"
tar -xf ffmpeg.tar.gz
cd FFmpeg-n$FFMPEG_VERSION
export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig"
./configure \
    --prefix="$PREFIX" \
    --enable-cross-compile \
    --target-os=android \
    --arch=aarch64 \
    --cpu=armv8-a \
    --cc="$CC" --cxx="$CXX" --ar="$AR" --ranlib="$RANLIB" --strip="$STRIP" \
    --sysroot="$SYSROOT" \
    --extra-cflags="$CFLAGS" \
    --extra-ldflags="-lm" \
    --enable-static --disable-shared \
    --enable-pic \
    --enable-gpl \
    --enable-libx264 \
    --enable-libass \
    --enable-libfreetype \
    --enable-libfribidi \
    --enable-libharfbuzz \
    --enable-small \
    --enable-pthreads \
    --disable-ffprobe --disable-ffplay \
    --disable-doc \
    --disable-debug \
    --disable-avdevice \
    --disable-postproc \
    --disable-network \
    --disable-autodetect \
    --disable-everything \
    --disable-zlib --disable-lzma --disable-iconv --disable-bzlib \
    --disable-sdl2 --disable-xlib --disable-libxcb --disable-vaapi --disable-vdpau \
    --enable-protocol=file,pipe,concat \
    --enable-demuxer=mov,mp4,m4a,3gp,3g2,mj2,matroska,webm,image2,concat,mp3,ogg,wav,aac,flac \
    --enable-muxer=mp4,matroska,mp3,adts \
    --enable-filter=scale,crop,pad,fps,format,setsar,fade,subtitles,amix,volume,afade,apad,anull,anullsrc,aresample,concat,copy \
    --enable-parser=h264,hevc,aac,mp3,mpegaudio,vp9,opus,flac \
    --enable-decoder=h264,hevc,mpeg4,vp8,vp9,av1,aac,mp3,opus,flac,vorbis,pcm_s16le,mjpeg,png,webp \
    --enable-encoder=libx264,aac,mpeg4
make -j"$JOBS"

# ---------- 7. Package the single executable ----------
mkdir -p "$OUT_DIR"
cp "$ROOT/FFmpeg-n$FFMPEG_VERSION/ffmpeg" "$OUT_DIR/libffmpeg_exec.so"
"$STRIP" "$OUT_DIR/libffmpeg_exec.so"
chmod +x "$OUT_DIR/libffmpeg_exec.so"

echo "==> built: $OUT_DIR/libffmpeg_exec.so ($(du -h "$OUT_DIR/libffmpeg_exec.so" | cut -f1))"
file "$OUT_DIR/libffmpeg_exec.so" || true
