#!/bin/bash
# Play GalaxyCraft by hand: the patched Dolphin (window, your usual Dolphin config and controllers)
# runs SMG2 with the module, and Minecraft runs hidden in a test world, drawn over Dolphin's
# picture. In Dolphin's window, Ctrl+G (hotkey "GalaxyCraft: Toggle Minecraft Link") hands the
# game to Minecraft (keyboard + mouse) or back to the Wii Remote for menus. Closing either side closes the other.
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
GALAXYCRAFT=1 dolphin/build/Binaries/dolphin-emu -e syati/build/galaxycraft.json \
  -C Dolphin.Input.BackgroundInput=True -C Dolphin.General.HotkeysRequireFocus=False &
DOLPHIN=$!
# The overlay demo joins a peaceful adventure world and stays there; ~14 h of ticks.
(cd fabric && exec ./gradlew runClientGameTest -PgalaxycraftDemo -PgalaxycraftHidden \
  -PgalaxycraftDemoTicks=1000000 --console=plain -q) &
MINECRAFT=$!
trap 'kill $DOLPHIN $MINECRAFT 2> /dev/null' INT TERM
wait -n $DOLPHIN $MINECRAFT
kill $DOLPHIN $MINECRAFT 2> /dev/null
pkill -f "galaxycraft.demo=true" 2> /dev/null  # Gradle's Minecraft child, if Gradle was killed first
wait
