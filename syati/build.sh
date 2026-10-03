#!/bin/sh
# Builds the GalaxyCraft module for SMG2 (SB4E) and the files Dolphin needs to load it:
#   build/CustomCode/CustomCode_SB4E.bin   module, linked by Kamek as a dynamic binary
#   build/galaxycraft.xml                  Riivolution patch: Syati loader + CustomCode folder
#   build/galaxycraft.json                 Dolphin game mod descriptor: dolphin-emu -e it
#   build/ObjectData/*.arc                 Steve in place of Mario (tools/steve/build.py)
# The game image is $GXC_GAME, or the SMG2 .rvz in ~/Documents/Games/Dolphin Games.
set -e
cd "$(dirname "$0")"
TC="${GXC_TOOLCHAIN:-$HOME/.local/opt/gxc-toolchain}"
SYATI="$TC/Syati"
CC="$SYATI/deps/CodeWarrior/mwcceppc"
KAMEK="$SYATI/deps/Kamek/Kamek.exe"
for f in "$CC" "$KAMEK" "$SYATI/symbols/SB4E.txt" "$SYATI/loader/loader.cpp"; do
  if [ ! -e "$f" ]; then
    echo "build.sh: missing $f (toolchain expected in $TC, see docs/PHASE3.md)" >&2
    exit 1
  fi
done
GAME="${GXC_GAME:-$(ls "$HOME"/Documents/Games/Dolphin\ Games/*.rvz 2>/dev/null | head -1)}"
if [ ! -f "$GAME" ]; then
  echo "build.sh: no game image; set GXC_GAME" >&2
  exit 1
fi

FLAGS="-c -Cpp_exceptions off -nodefaults -proc gekko -fp hard -lang=c++ -O4,s -inline on \
-rtti off -sdata 0 -sdata2 0 -align powerpc -func_align 4 -str pool -enum int -DGEKKO -DSB4E"
mkdir -p build/obj build/CustomCode
export WINEDEBUG=-all

# Block atlas for the voxel planet, from the local Minecraft jar.
python3 ../tools/voxel_atlas.py build/gen/atlas.h
# Module: core/ (also tested with g++) plus the game glue.
OBJS=""
for src in src/core/*.cpp src/GalaxyCraft.cpp src/VoxelPlanet.cpp; do
  obj="build/obj/$(basename "$src" .cpp).o"
  "$CC" $FLAGS -i src -i src/core -i build/gen -i ../protocol -i "$SYATI/include" -I- -i src/shim "$src" -o "$obj"
  OBJS="$OBJS $obj"
done
"$KAMEK" $OBJS -dynamic -externals="$SYATI/symbols/SB4E.txt" -externals=symbols_extra.txt -quiet \
  -output-kamek=build/CustomCode/CustomCode_SB4E.bin

# Loader: same build as Syati's buildloader.py, patches only.
"$CC" $FLAGS -i "$SYATI/include" -I- -i "$SYATI/loader" "$SYATI/loader/loader.cpp" \
  -o build/obj/loader.o
"$KAMEK" build/obj/loader.o -static=0x80001800 -externals="$SYATI/symbols/SB4E.txt" -quiet \
  -output-riiv=build/loader_patches.xml
# Steve: Mario's model (and his gloves) replaced, built from the disc into build/ObjectData.
GXC_GAME="$GAME" python3 ../tools/steve/build.py --out build
python3 - "$GAME" <<'PY'
import json, os, sys
patches = open("build/loader_patches.xml").read().strip()
for arc in sorted(os.listdir("build/ObjectData")):
    if arc.endswith(".arc"):
        patches += f'\n\t\t<file disc="/ObjectData/{arc}" external="/ObjectData/{arc}" />'
xml = open("riivolution/galaxycraft.xml.in").read().replace("@LOADER_PATCHES@", patches)
open("build/galaxycraft.xml", "w").write(xml)
build = os.path.abspath("build")
desc = {
    "type": "dolphin-game-mod-descriptor",
    "version": 1,
    "base-file": sys.argv[1],
    "display-name": "Super Mario Galaxy 2 (GalaxyCraft)",
    "riivolution": {"patches": [{
        "xml": os.path.join(build, "galaxycraft.xml"),
        "root": build,
        "options": [{"section-name": "GalaxyCraft", "option-name": "GalaxyCraft", "choice": 1}],
    }]},
}
json.dump(desc, open("build/galaxycraft.json", "w"), indent=2)
PY
echo "built build/CustomCode/CustomCode_SB4E.bin ($(wc -c < build/CustomCode/CustomCode_SB4E.bin) bytes)"
