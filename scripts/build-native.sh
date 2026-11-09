#!/usr/bin/env bash
# Builds the two executables the app ships as "native libraries"
# (Android only lets apps exec files from their nativeLibraryDir):
#
#   libp2pnode.so     - our Go libp2p/WebRTC node (from ./go)
#   libfilebrowser.so - FileBrowser Quantum release binary (static Linux build)
#
# Usage: scripts/build-native.sh [abi...]   (default: arm64-v8a armeabi-v7a x86_64)
# Needs: Go (auto-downloads the toolchain from go.mod), curl, and for
#        armeabi-v7a/x86_64 the Android NDK (ANDROID_NDK_HOME or $ANDROID_HOME/ndk/*).
set -euo pipefail

FILEBROWSER_VERSION="${FILEBROWSER_VERSION:-v2.0.2-stable}"
UPX_VERSION="5.2.1"
API=24

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/android/app/src/main/jniLibs"
CACHE="$ROOT/.build-cache"
mkdir -p "$CACHE"

ABIS=("$@")
[ ${#ABIS[@]} -eq 0 ] && ABIS=(arm64-v8a armeabi-v7a x86_64)

find_ndk() {
  if [ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "$ANDROID_NDK_HOME" ]; then echo "$ANDROID_NDK_HOME"; return; fi
  local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
  ls -d "$sdk"/ndk/* 2>/dev/null | sort -V | tail -1
}

upx_bin() {
  if command -v upx >/dev/null; then command -v upx; return; fi
  local d="$CACHE/upx-$UPX_VERSION-amd64_linux"
  if [ ! -x "$d/upx" ]; then
    echo "  fetching upx $UPX_VERSION (to unpack filebrowser)" >&2
    curl -fsSL "https://github.com/upx/upx/releases/download/v$UPX_VERSION/upx-$UPX_VERSION-amd64_linux.tar.xz" | tar xJ -C "$CACHE"
  fi
  echo "$d/upx"
}

for abi in "${ABIS[@]}"; do
  case "$abi" in
    arm64-v8a)   goarch=arm64; goarm="";  cc_triple="";                     fb=linux-arm64-filebrowser ;;
    armeabi-v7a) goarch=arm;   goarm=7;   cc_triple="armv7a-linux-androideabi"; fb=linux-armv7-filebrowser ;;
    x86_64)      goarch=amd64; goarm="";  cc_triple="x86_64-linux-android"; fb=linux-amd64-filebrowser ;;
    *) echo "unsupported ABI $abi" >&2; exit 1 ;;
  esac
  echo "==> $abi"
  mkdir -p "$OUT/$abi"

  # --- p2p node ----------------------------------------------------------
  # -checklinkname=0 is required by github.com/wlynxg/anet (Android 11+
  # network interface workaround used by pion and by us).
  env_vars=(GOOS=android GOARCH="$goarch")
  [ -n "$goarm" ] && env_vars+=(GOARM="$goarm")
  if [ -n "$cc_triple" ]; then
    ndk="$(find_ndk)"
    [ -z "$ndk" ] && { echo "Android NDK not found (needed for $abi). Set ANDROID_NDK_HOME." >&2; exit 1; }
    env_vars+=(CGO_ENABLED=1 CC="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/${cc_triple}${API}-clang")
  else
    env_vars+=(CGO_ENABLED=0)
  fi
  (cd "$ROOT/go" && env "${env_vars[@]}" go build -trimpath -ldflags "-s -w -checklinkname=0" -o "$OUT/$abi/libp2pnode.so" .)
  echo "  built libp2pnode.so"

  # --- filebrowser -------------------------------------------------------
  dl="$CACHE/filebrowser-$FILEBROWSER_VERSION-$fb"
  if [ ! -f "$dl" ]; then
    echo "  downloading filebrowser $FILEBROWSER_VERSION ($fb)"
    curl -fsSL -o "$dl.part" "https://github.com/gtsteffaniak/filebrowser/releases/download/$FILEBROWSER_VERSION/$fb"
    mv "$dl.part" "$dl"
  fi
  cp "$dl" "$OUT/$abi/libfilebrowser.so"
  chmod 755 "$OUT/$abi/libfilebrowser.so"
  # Release binaries are UPX-packed; unpack so Android maps them normally.
  if grep -q "UPX!" "$OUT/$abi/libfilebrowser.so"; then
    "$(upx_bin)" -q -d "$OUT/$abi/libfilebrowser.so" >/dev/null
    echo "  unpacked libfilebrowser.so"
  fi
done
echo "Done: $OUT"
