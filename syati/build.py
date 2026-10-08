#!/usr/bin/env python3
"""Builds the GalaxyCraft module for SMG2 (SB4E) and the files Dolphin needs to load it:
  build/CustomCode/CustomCode_SB4E.bin   module, linked by Kamek as a dynamic binary
  build/galaxycraft.xml                  Riivolution patch: Syati loader + CustomCode folder
  build/galaxycraft.json                 Dolphin game mod descriptor: Dolphin -e it
  build/ObjectData/*.arc                 Steve in place of Mario (tools/steve/build.py)
The game image is $GXC_GAME, or the SMG2 .rvz in ~/Documents/Games/Dolphin Games. The same on
Linux and Windows: CodeWarrior and Kamek are Windows programs, which the toolchain runs with wine
on Linux (its mwcceppc is a script that does it) and which run directly on Windows.
  python3 syati/build.py   (python on Windows)
"""
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, os.path.join(ROOT, "tools"))
import hostexe  # noqa: E402

TC = os.environ.get("GXC_TOOLCHAIN") or os.path.expanduser("~/.local/opt/gxc-toolchain")
SYATI = os.path.join(TC, "Syati")
CC = os.path.join(SYATI, "deps/CodeWarrior", "mwcceppc.exe" if hostexe.WINDOWS else "mwcceppc")
KAMEK = os.path.join(SYATI, "deps/Kamek/Kamek.exe")
SYMBOLS = os.path.join(SYATI, "symbols/SB4E.txt")
LOADER = os.path.join(SYATI, "loader/loader.cpp")
FLAGS = ("-c -Cpp_exceptions off -nodefaults -proc gekko -fp hard -lang=c++ -O4,s -inline on "
         "-rtti off -sdata 0 -sdata2 0 -align powerpc -func_align 4 -str pool -enum int -DGEKKO -DSB4E").split()
SOURCES = sorted(f"src/core/{n}" for n in os.listdir(os.path.join(HERE, "src/core")) if n.endswith(".cpp")) + [
    "src/GalaxyCraft.cpp", "src/VoxelPlanet.cpp", "src/HeldItem.cpp", "src/EntityDraw.cpp", "src/Boot.cpp"]


def die(msg):
    sys.exit(f"build.py: {msg}")


def run(cmd, env=None):
    if subprocess.run(cmd, cwd=HERE, env=env).returncode != 0:
        sys.exit(1)


def riivolution_xml(steve=True):
    with open(os.path.join(HERE, "build/loader_patches.xml")) as f:
        patches = f.read().strip()
    # Without the Steve arcs the game loads its own Mario (Player Model: Mario).
    for arc in sorted(os.listdir(os.path.join(HERE, "build/ObjectData"))) if steve else []:
        if arc.endswith(".arc"):
            patches += f'\n\t\t<file disc="/ObjectData/{arc}" external="/ObjectData/{arc}" />'
    patches += '\n\t\t<folder disc="/StageData/GalaxyCraftSpace" external="/StageData/GalaxyCraftSpace" create="true" />'
    with open(os.path.join(HERE, "riivolution/galaxycraft.xml.in")) as f:
        return f.read().replace("@LOADER_PATCHES@", patches)


def descriptor(game, xml="galaxycraft.xml"):
    build = os.path.join(HERE, "build")
    return {
        "type": "dolphin-game-mod-descriptor",
        "version": 1,
        "base-file": game,
        "display-name": "Super Mario Galaxy 2 (Super Minecraft Galaxy)",
        "riivolution": {"patches": [{
            "xml": os.path.join(build, xml),
            "root": build,
            "options": [{"section-name": "GalaxyCraft", "option-name": "GalaxyCraft", "choice": 1}],
        }]},
    }


def main():
    for f in (CC, KAMEK, SYMBOLS, LOADER):
        if not os.path.exists(f):
            die(f"missing {f} (toolchain expected in {TC}, see docs/PHASE3.md)")
    game = hostexe.default_game()
    if not game or not os.path.isfile(game):
        die("no game image; set GXC_GAME")
    os.makedirs(os.path.join(HERE, "build/obj"), exist_ok=True)
    os.makedirs(os.path.join(HERE, "build/CustomCode"), exist_ok=True)
    env = dict(os.environ, GXC_GAME=game, WINEDEBUG="-all")
    py = sys.executable

    # Steve: Mario's model (and his gloves) replaced, built from the disc into build/ObjectData, and
    # where what he holds goes on his skeleton (build/gen/held.h).
    run([py, os.path.join(ROOT, "tools/steve/build.py"), "--out", "build"], env)
    # GalaxyCraft's own galaxy, empty space under SMG2's sky (build/StageData/GalaxyCraftSpace).
    run([py, os.path.join(ROOT, "tools/space_galaxy.py"), "--out", "build"], env)
    # Module: core/ (also tested with g++) plus the game glue.
    objs = []
    for src in SOURCES:
        obj = f"build/obj/{os.path.splitext(os.path.basename(src))[0]}.o"
        run([CC, *FLAGS, "-i", "src", "-i", "src/core", "-i", "build/gen", "-i", "../protocol",
             "-i", os.path.join(SYATI, "include"), "-I-", "-i", "src/shim", src, "-o", obj], env)
        objs.append(obj)
    run([KAMEK, *objs, "-dynamic", f"-externals={SYMBOLS}", "-externals=symbols_extra.txt", "-quiet",
         "-output-kamek=build/CustomCode/CustomCode_SB4E.bin"], env)

    # Loader: same build as Syati's buildloader.py, patches only.
    run([CC, *FLAGS, "-i", os.path.join(SYATI, "include"), "-I-", "-i", os.path.join(SYATI, "loader"),
         LOADER, "-o", "build/obj/loader.o"], env)
    run([KAMEK, "build/obj/loader.o", "-static=0x80001800", f"-externals={SYMBOLS}", "-quiet",
         "-output-riiv=build/loader_patches.xml"], env)
    with open(os.path.join(HERE, "build/galaxycraft.xml"), "w", newline="\n") as f:
        f.write(riivolution_xml())
    with open(os.path.join(HERE, "build/galaxycraft.json"), "w", newline="\n") as f:
        json.dump(descriptor(game), f, indent=2)
    # The same with the game's own Mario instead of Steve's body (Player Model: Mario).
    with open(os.path.join(HERE, "build/galaxycraft-mario.xml"), "w", newline="\n") as f:
        f.write(riivolution_xml(steve=False))
    with open(os.path.join(HERE, "build/galaxycraft-mario.json"), "w", newline="\n") as f:
        json.dump(descriptor(game, "galaxycraft-mario.xml"), f, indent=2)
    size = os.path.getsize(os.path.join(HERE, "build/CustomCode/CustomCode_SB4E.bin"))
    print(f"built build/CustomCode/CustomCode_SB4E.bin ({size} bytes)")


if __name__ == "__main__":
    main()
