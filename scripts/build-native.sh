#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to Android NDK r28}"
GO=${GO:-go}
mkdir -p "$ROOT/vendor"
if [[ ! -d "$ROOT/vendor/MasterDnsVPN/.git" ]]; then git clone https://github.com/masterking32/MasterDnsVPN.git "$ROOT/vendor/MasterDnsVPN"; fi
git -C "$ROOT/vendor/MasterDnsVPN" checkout 5f83ee9a1690a968f9edf495d3a7e00a2043b0bd
python3 "$ROOT/scripts/patch-core.py" "$ROOT/vendor/MasterDnsVPN"
if [[ ! -d "$ROOT/vendor/hev/.git" ]]; then git clone https://github.com/heiher/hev-socks5-tunnel.git "$ROOT/vendor/hev"; fi
git -C "$ROOT/vendor/hev" checkout 0166c4765e9e42eee7d18923026f88fda91bd9fa
git -C "$ROOT/vendor/hev" submodule update --init --recursive
# Nava supplies its own JNI bridge. The upstream sample JNI_OnLoad expects
# hev.htproxy.TProxyService, which is not part of this application.
python3 - "$ROOT/vendor/hev/Android.mk" <<'PY'
import pathlib,sys
p=pathlib.Path(sys.argv[1]); s=p.read_text()
s=s.replace('LOCAL_SRC_FILES := $(HEV_SOCKS5_TUNNEL_SRC)',
            'LOCAL_SRC_FILES := $(filter-out src/hev-jni.c,$(HEV_SOCKS5_TUNNEL_SRC))')
p.write_text(s)
PY
mkdir -p "$ROOT/vendor/MasterDnsVPN/cmd/navabridge"
cp "$ROOT/corebridge/main.go" "$ROOT/vendor/MasterDnsVPN/cmd/navabridge/main.go"
cp "$ROOT/corebridge/client/"*.go "$ROOT/vendor/MasterDnsVPN/internal/client/"
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
for abi in ${ABIS:-arm64-v8a armeabi-v7a x86_64}; do
    case "$abi" in
        arm64-v8a) arch=arm64; target=aarch64-linux-android26; extra=() ;;
        armeabi-v7a) arch=arm; target=armv7a-linux-androideabi26; extra=(GOARM=7) ;;
        x86_64) arch=amd64; target=x86_64-linux-android26; extra=() ;;
        *) echo "Unsupported ABI: $abi" >&2; exit 1 ;;
    esac
    out="$ROOT/app/src/main/jniLibs/$abi"; mkdir -p "$out"
    (cd "$ROOT/vendor/MasterDnsVPN"; env GOOS=android GOARCH="$arch" CGO_ENABLED=1 "${extra[@]}" CC="$TOOLCHAIN/$target-clang" "$GO" build -trimpath -buildmode=c-shared -ldflags='-s -w -extldflags=-Wl,-z,max-page-size=16384' -o "$out/libmasterdns.so" ./cmd/navabridge)
    # Removing a source from a make target does not itself make an existing
    # shared library out-of-date. Force relinking after excluding hev-jni.c.
    rm -f "$ROOT/vendor/hev/obj/$abi/local/$abi/libhev-socks5-tunnel.so"
    "$ANDROID_NDK_HOME/ndk-build" -C "$ROOT/vendor/hev" NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=Android.mk APP_ABI="$abi" APP_PLATFORM=android-26 APP_SUPPORT_FLEXIBLE_PAGE_SIZES=true NDK_OUT="obj/$abi" NDK_LIBS_OUT="libs/$abi" -j4
    cp "$ROOT/vendor/hev/libs/$abi/$abi/libhev-socks5-tunnel.so" "$out/"
    if "$TOOLCHAIN/llvm-readelf" --dyn-syms "$out/libhev-socks5-tunnel.so" | grep -q JNI_OnLoad; then
        echo 'Unexpected upstream JNI_OnLoad in Nava tunnel library' >&2; exit 1
    fi
    "$TOOLCHAIN/$target-clang" -shared -fPIC -O2 -Wl,-z,max-page-size=16384 -I"$out" "$ROOT/native/bridge.c" -L"$out" -lmasterdns -lhev-socks5-tunnel -o "$out/libbridgedns.so"
    rm "$out/libmasterdns.h"
done
