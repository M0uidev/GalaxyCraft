#!/bin/sh
# Builds the patched Dolphin for players on Windows: Dolphin.exe and DolphinTool.exe with
# everything they need next to them (Qt and its plugins, Dolphin's Sys folder, the MSVC runtime),
# so it runs on any Windows 10 or 11 without installing anything. Output, in ../dist:
#   dolphin-win32-x64/          the folder (Dolphin.exe runs from it as it is)
#   dolphin-win32-x64.tar.gz    the same, for releases (the launcher installs it)
# Built with CMake + Ninja + MSVC and the Qt that Dolphin keeps in Externals/Qt. Run by CI from
# Git Bash with MSVC's environment loaded (ilammy/msvc-dev-cmd), as on a Visual Studio prompt.
set -e
cd "$(dirname "$0")"
PIN=5390a61
if [ ! -d src ]; then
  git clone --depth 50 https://github.com/dolphin-emu/dolphin.git src
  git -C src checkout -q "$PIN"
  git -C src submodule update --init --recursive --depth 1 --jobs 8
fi
# A cached tree keeps its file times while the patch is the same one, so the build stays
# incremental; a new patch starts it again from the pin.
if ! cmp -s patches/0001-galaxycraft.patch src/.galaxycraft.patch; then
  git -C src checkout -q -- .
  git -C src clean -fdq -e .galaxycraft.patch
  git -C src apply ../patches/0001-galaxycraft.patch
  cp patches/0001-galaxycraft.patch src/.galaxycraft.patch
fi
cmake -S src -B build-win -G Ninja -DCMAKE_BUILD_TYPE=Release -DENABLE_TESTS=OFF \
  -DENABLE_ANALYTICS=OFF -DUSE_DISCORD_PRESENCE=OFF -DUSE_RETRO_ACHIEVEMENTS=OFF \
  -DENABLE_AUTOUPDATE=OFF -DUSE_UPNP=OFF -DENCODE_FRAMEDUMPS=OFF
ninja -C build-win dolphin-emu dolphin-tool

OUT=../dist/dolphin-win32-x64
rm -rf "$OUT" ../dist/dolphin-win32-x64.tar.gz
mkdir -p "$OUT"
# Binaries holds Dolphin.exe, DolphinTool.exe, the Qt libraries and QtPlugins (windeployqt), and
# symlinks to Sys, COPYING, Licenses and qt.conf: those are copied for real.
for f in build-win/Binaries/*; do
  case "$(basename "$f")" in
    Sys|COPYING|Licenses|qt.conf|*.pdb|*.ilk|*.exp|*.lib) ;;
    *) cp -r "$f" "$OUT/" ;;
  esac
done
cp -r src/Data/Sys "$OUT/Sys"
cp src/COPYING "$OUT/COPYING"
cp -r src/LICENSES "$OUT/Licenses"
cp src/Source/Core/DolphinQt/qt.conf.win "$OUT/qt.conf"
# The MSVC runtime next to the executables, so a Windows without the Visual C++ Redistributable
# runs it too (windeployqt is told --no-compiler-runtime).
CRT=$(cygpath -u "$VCToolsRedistDir")/x64/Microsoft.VC143.CRT
[ -d "$CRT" ] || CRT=$(ls -d "$(cygpath -u "$VCToolsRedistDir")"/x64/Microsoft.VC*.CRT | head -1)
cp "$CRT"/*.dll "$OUT/"
for exe in Dolphin.exe DolphinTool.exe; do
  [ -f "$OUT/$exe" ] || { echo "package-windows.sh: $exe missing" >&2; exit 1; }
done
# Nothing it loads may be missing: every DLL it names is next to it or part of Windows.
for exe in "$OUT"/Dolphin.exe "$OUT"/DolphinTool.exe; do
  dumpbin //nologo //dependents "$(cygpath -w "$exe")" | tr -d '\r' | sed -n 's/^ *\([^ ]*\.dll\)$/\1/Ip' |
    while read -r dll; do
      [ -f "$OUT/$dll" ] || [ -f "$(cygpath -u "$SYSTEMROOT")/System32/$dll" ] ||
        case "$dll" in api-ms-win-*|ext-ms-*) ;; *) echo "package-windows.sh: $(basename "$exe") needs $dll" >&2; exit 1 ;; esac
    done
done
tar -czf ../dist/dolphin-win32-x64.tar.gz -C "$OUT" $(ls "$OUT")
echo "built dist/dolphin-win32-x64 ($(du -sh "$OUT" | cut -f1)) and dist/dolphin-win32-x64.tar.gz"
