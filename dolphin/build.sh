#!/bin/sh
# Builds Dolphin with GalaxyCraft: clones upstream at the pinned commit (first run), applies
# patches/0001-galaxycraft.patch, and compiles build/Binaries/dolphin-emu.
#   GALAXYCRAFT=1 build/Binaries/dolphin-emu  turns the bridge on.
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
if [ ! -f build/build.ninja ]; then
  cmake -S src -B build -G Ninja -DCMAKE_BUILD_TYPE=Release -DENABLE_TESTS=OFF \
    -DENABLE_ANALYTICS=OFF -DUSE_DISCORD_PRESENCE=OFF -DUSE_RETRO_ACHIEVEMENTS=OFF \
    -DENABLE_AUTOUPDATE=OFF -DUSE_UPNP=OFF
fi
ninja -C build dolphin-emu
