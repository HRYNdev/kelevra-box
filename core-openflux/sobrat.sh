#!/usr/bin/env bash
# Клиент OpenFlux (носитель комнаты «Телемост») для APK: app/src/main/jniLibs/<abi>/libopenflux.so.
#
# Чужой код — только по фиксированной точке: тег v0.5.1, коммит ниже сверяется после клона.
# GPL-3.0 (как и sing-box): исходник — https://github.com/p1neappleXpress/OpenFlux/tree/v0.5.1.
# Собирается под GOOS=android с NDK (cgo): bionic-резолвер и системные сертификаты. Статический
# linux-бинарь из релиза на Android не годится: без /etc/resolv.conf его резолвер идёт на [::1]:53,
# а yandex-носитель держит ссылку на резолвер, снятую до OPENFLUX_DNS (transport/yandex/dial.go:33).
#
# Нужны: go >= 1.26.6 (GO=...), NDK (ANDROID_NDK_HOME=...), git. Сеть только на клон и go mod.
# Пример (Windows, Git Bash):
#   GO=~/go/pkg/mod/golang.org/toolchain@v0.0.1-go1.26.7.windows-amd64/bin/go.exe \
#   ANDROID_NDK_HOME=D:/Android/ndk/28.0.13004108 bash core-openflux/sobrat.sh
set -euo pipefail

TAG=v0.5.1
COMMIT=1adc1fe1f4ac8180952779d4d580ee82a34abe98
ABIS=${ABIS:-"arm64-v8a x86_64 armeabi-v7a"}
GO=${GO:-go}
NDK=${ANDROID_NDK_HOME:?ANDROID_NDK_HOME не задан}

HERE=$(cd "$(dirname "$0")" && pwd)
OUT="$HERE/../app/src/main/jniLibs"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

git clone -q --depth 1 --branch "$TAG" https://github.com/p1neappleXpress/OpenFlux "$WORK/src"
got=$(git -C "$WORK/src" rev-parse HEAD)
[ "$got" = "$COMMIT" ] || { echo "тег $TAG указывает на $got, ждали $COMMIT — стоп" >&2; exit 1; }

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) HOST=windows-x86_64; EXT=.cmd ;;
  Darwin) HOST=darwin-x86_64; EXT= ;;
  *) HOST=linux-x86_64; EXT= ;;
esac
TC="$NDK/toolchains/llvm/prebuilt/$HOST/bin"

for abi in $ABIS; do
  case "$abi" in
    arm64-v8a)   arch=arm64; tri=aarch64-linux-android24; extra= ;;
    x86_64)      arch=amd64; tri=x86_64-linux-android24; extra= ;;
    armeabi-v7a) arch=arm;   tri=armv7a-linux-androideabi24; extra=GOARM=7 ;;
    x86)         arch=386;   tri=i686-linux-android24; extra= ;;
    *) echo "неизвестный ABI $abi" >&2; exit 1 ;;
  esac
  mkdir -p "$OUT/$abi"
  (cd "$WORK/src" && env GOTOOLCHAIN=local GOOS=android GOARCH=$arch $extra CGO_ENABLED=1 \
     CC="$TC/$tri-clang$EXT" CXX="$TC/$tri-clang++$EXT" \
     "$GO" build -trimpath -buildvcs=false -ldflags="-s -w -checklinkname=0" \
     -o "$OUT/$abi/libopenflux.so" .)
  echo "$abi: $(sha256sum "$OUT/$abi/libopenflux.so" | cut -d' ' -f1)"
done
