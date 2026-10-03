#!/bin/bash
# Play GalaxyCraft by hand: the patched Dolphin (window, your usual Dolphin config and controllers)
# runs SMG2 with the module, and Minecraft runs hidden in a test world, drawn over Dolphin's
# picture. The title screen and file select are played with your Wii Remote mapping; once a save is
# picked, Mario mode: keyboard and mouse play Mario (WASD stick, Space A, Shift Z, Ctrl C, Esc +,
# Tab -, right click B, left click spin) while the camera sits in his eyes; in SMG2's menus the
# mouse is the pointer and left click is A. Ctrl+G (hotkey "GalaxyCraft: Toggle Minecraft Link")
# switches between the two by hand; with the Wii Remote, Esc stops Dolphin again. Back on the title
# screen, the Wii Remote has the game again.
# A voxel planet appears above Mario once linked in a level (one per galaxy, saved and loaded
# again; /galaxycraft planet spawn <radius> makes a new one, up to 256): P lands on it; with a
# block or the pickaxe in hand (slots 2-5) left click breaks and right click places, F spins.
# Dolphin runs with 256 MiB of MEM2 (RAM override): big planets live in the extra memory, and
# savestates of a normal Dolphin do not load in it (nor the other way around).
# Closing either side closes the other.
#   tools/gxplay.sh            (build first: dolphin/build.sh, syati/build.sh)
set -u
cd "$(dirname "$0")/.." || exit 1
: "${JAVA_HOME:=$(ls -d "$HOME"/.local/opt/jdk-25* 2>/dev/null | head -1)}"
export JAVA_HOME
[ -x dolphin/build/Binaries/dolphin-emu ] || { echo "gxplay: run dolphin/build.sh first" >&2; exit 1; }
[ -f syati/build/galaxycraft.json ] || { echo "gxplay: run syati/build.sh first" >&2; exit 1; }

# Background input / hotkeys without focus, for this run only (-C is not saved): on Hyprland,
# Dolphin's window can hold the compositor's focus without Qt noticing, and then Dolphin would
# ignore the Wii Remote and the link hotkey. XWayland only shows keys to a focused X window, so
# typing in other programs still does not reach the game.
GALAXYCRAFT=1 GALAXYCRAFT_LINK_ON_SAVE=1 dolphin/build/Binaries/dolphin-emu -e syati/build/galaxycraft.json \
  -C Dolphin.Input.BackgroundInput=True -C Dolphin.General.HotkeysRequireFocus=False \
  -C Dolphin.Core.RAMOverrideEnable=True -C Dolphin.Core.MEM2Size=268435456 &
DOLPHIN=$!
# The overlay demo joins a peaceful adventure world and stays there; ~14 h of ticks.
(cd fabric && exec ./gradlew runClientGameTest -PgalaxycraftDemo -PgalaxycraftHidden -PgalaxycraftPlanet \
  -PgalaxycraftDemoTicks=1000000 --console=plain -q) &
MINECRAFT=$!
trap 'kill $DOLPHIN $MINECRAFT 2> /dev/null' INT TERM
wait -n $DOLPHIN $MINECRAFT
kill $DOLPHIN $MINECRAFT 2> /dev/null
pkill -f "galaxycraft.demo=true" 2> /dev/null  # Gradle's Minecraft child, if Gradle was killed first
wait
