#!/bin/sh
# Builds the patched Dolphin for players on Linux: dolphin-emu and dolphin-tool with everything
# they need next to them (Qt and its plugins, the libraries, Dolphin's Sys folder), so it runs on
# any recent distribution without installing anything. Output: dist/dolphin-linux-x64.tar.gz,
# with usr/bin/dolphin-emu and usr/bin/dolphin-tool inside. Run by CI for releases (it needs
# Qt 6 in $QT_ROOT_DIR or on the system, and linuxdeploy, downloaded if missing).
set -e
cd "$(dirname "$0")"
PIN=5390a61
if [ ! -d src ]; then
  git clone --depth 50 https://github.com/dolphin-emu/dolphin.git src
  git -C src checkout -q "$PIN"
  git -C src submodule update --init --recursive --depth 1 --jobs 8
fi
if git -C src apply --check ../patches/0001-galaxycraft.patch 2>/dev/null; then
  git -C src apply ../patches/0001-galaxycraft.patch
fi
cmake -S src -B build-release -G Ninja -DCMAKE_BUILD_TYPE=Release -DENABLE_TESTS=OFF \
  -DENABLE_ANALYTICS=OFF -DUSE_DISCORD_PRESENCE=OFF -DUSE_RETRO_ACHIEVEMENTS=OFF \
  -DENABLE_AUTOUPDATE=OFF -DUSE_UPNP=OFF -DENCODE_FRAMEDUMPS=OFF -DENABLE_NOGUI=OFF \
  -DCMAKE_INSTALL_PREFIX=/usr ${QT_ROOT_DIR:+-DCMAKE_PREFIX_PATH="$QT_ROOT_DIR"} \
  ${CMAKE_C_COMPILER_LAUNCHER:+-DCMAKE_C_COMPILER_LAUNCHER="$CMAKE_C_COMPILER_LAUNCHER"} \
  ${CMAKE_CXX_COMPILER_LAUNCHER:+-DCMAKE_CXX_COMPILER_LAUNCHER="$CMAKE_CXX_COMPILER_LAUNCHER"}
ninja -C build-release dolphin-emu dolphin-tool

TOOLS="$PWD/build-release/tools"
mkdir -p "$TOOLS"
for t in linuxdeploy linuxdeploy-plugin-qt; do
  [ -x "$TOOLS/$t" ] && continue
  curl -sSfL -o "$TOOLS/$t" "https://github.com/linuxdeploy/$t/releases/download/continuous/$t-x86_64.AppImage"
  chmod +x "$TOOLS/$t"
done
APPDIR="$PWD/build-release/AppDir"
rm -rf "$APPDIR"
mkdir -p "$APPDIR/usr/bin"
export PATH="$TOOLS:$PATH" APPIMAGE_EXTRACT_AND_RUN=1 NO_STRIP=1
export QMAKE="${QT_ROOT_DIR:+$QT_ROOT_DIR/bin/}qmake6"
[ -x "$QMAKE" ] || QMAKE="${QT_ROOT_DIR:+$QT_ROOT_DIR/bin/}qmake"
EXTRA_QT_PLUGINS="waylandcompositor;wayland-shell-integration;wayland-decoration-client;wayland-graphics-integration-client" \
linuxdeploy --appdir "$APPDIR" \
  --executable build-release/Binaries/dolphin-emu --executable build-release/Binaries/dolphin-tool \
  --desktop-file src/Data/dolphin-emu.desktop --icon-file src/Data/dolphin-emu.png \
  --plugin qt
# Dolphin finds Sys next to its executable.
cp -r src/Data/Sys "$APPDIR/usr/bin/Sys"
# No library left out.
if ldd "$APPDIR/usr/bin/dolphin-emu" "$APPDIR/usr/bin/dolphin-tool" | grep "not found"; then
  echo "package-linux.sh: libraries missing from the bundle" >&2
  exit 1
fi
mkdir -p ../dist
tar -czf ../dist/dolphin-linux-x64.tar.gz -C "$APPDIR" usr
echo "built dist/dolphin-linux-x64.tar.gz ($(du -h ../dist/dolphin-linux-x64.tar.gz | cut -f1))"
