#!/bin/bash
# Play GalaxyCraft: Minecraft's menu opens in the patched Dolphin's window while Super Mario Galaxy
# 2 boots behind it by itself, into GalaxyCraftSpace (an empty galaxy under SMG2's sky). Each
# Minecraft world is a galaxy: entering one puts you on its planets, where you left off.
# Keyboard and mouse are Minecraft's in its menus; in a world they play Mario (WASD stick, Space A,
# Shift Z, Ctrl C, Esc Minecraft's pause menu, right click B, left click spin) with the camera in
# his eyes. Closing either side closes the other.
#
# Everything is kept in $XDG_DATA_HOME/galaxycraft (~/.local/share/galaxycraft):
#   minecraft/  Minecraft's game folder: worlds (saves/<world>/galaxycraft holds its planets),
#               options.txt, config/galaxycraft.properties (GalaxyCraft's settings)
#   dolphin/    Dolphin's own folder: an emulated Wii Remote only GalaxyCraft drives, your usual
#               Dolphin's video settings and hotkeys, and SMG2's save copied from it if you have
#               one (without one the game makes a file by itself)
#   blueprints/ planet blueprints, shared by every world
# Dolphin runs with 256 MiB of MEM2 (RAM override), dual core.
#   tools/gxplay.sh            (build first: dolphin/build.sh, syati/build.sh)
set -u
cd "$(dirname "$0")/.." || exit 1
: "${JAVA_HOME:=$(ls -d "$HOME"/.local/opt/jdk-25* 2>/dev/null | head -1)}"
export JAVA_HOME
[ -x dolphin/build/Binaries/dolphin-emu ] || { echo "gxplay: run dolphin/build.sh first" >&2; exit 1; }
[ -f syati/build/galaxycraft.json ] || { echo "gxplay: run syati/build.sh first" >&2; exit 1; }
DATA="${XDG_DATA_HOME:-$HOME/.local/share}/galaxycraft"
GAME_DIR="$DATA/minecraft"
DOLPHIN_DIR="$DATA/dolphin"
SMG2_SAVE=Wii/title/00010000/53423445

# Dolphin's own folder: an emulated Wii Remote + Nunchuk that only GalaxyCraft drives
# (tools/dolphin-play), with the player's own video settings and hotkeys if they have a Dolphin.
if [ ! -d "$DOLPHIN_DIR/Config" ]; then
  mkdir -p "$DOLPHIN_DIR/Config"
  cp tools/dolphin-play/*.ini "$DOLPHIN_DIR/Config/"
  for ini in GFX.ini Hotkeys.ini; do
    [ -f "$HOME/.config/dolphin-emu/$ini" ] && cp "$HOME/.config/dolphin-emu/$ini" "$DOLPHIN_DIR/Config/"
  done
fi
# SMG2's save, from the player's usual Dolphin while this folder has none (without one, the game
# makes a file by itself).
if [ ! -d "$DOLPHIN_DIR/$SMG2_SAVE/data" ]; then
  for from in "$HOME/.local/share/dolphin-emu" "$HOME/.dolphin-emu"; do
    if [ -d "$from/$SMG2_SAVE/data" ]; then
      mkdir -p "$DOLPHIN_DIR/$SMG2_SAVE"
      cp -r "$from/$SMG2_SAVE/." "$DOLPHIN_DIR/$SMG2_SAVE/"
      break
    fi
  done
fi
mkdir -p "$GAME_DIR"

# A hidden Minecraft left over from an earlier run (Gradle's daemon outlives this script) would
# still be linked to the new Dolphin through the shared memory, and two of them feeding one game
# make it lag: stop any first. The game test ignores SIGTERM, so KILL follows. (This also stops
# a test of tools/gxvoxel.sh, whose Minecraft is hidden too: never play while one runs.)
MC_MATCH="galaxycraft.hidden=true"
stop_minecraft() {
  pkill -f "$MC_MATCH" 2> /dev/null || return 0
  for _ in 1 2 3 4 5; do sleep 1; pgrep -f "$MC_MATCH" > /dev/null || return 0; done
  pkill -KILL -f "$MC_MATCH" 2> /dev/null
}
stop_minecraft

# Dual core (CPUThread): this Dolphin defaults to single core on desktop, which puts the CPU and
# the GPU on one thread and leaves no headroom on planets.
# Background input / hotkeys without focus, for this run only (-C is not saved): on Hyprland,
# Dolphin's window can hold the compositor's focus without Qt noticing.
# The Player Model setting (Mario or Steve) picks which Mario the game loads, so it is read here, at
# the start: change it in Super Minecraft Galaxy's settings and play again.
MODEL=galaxycraft
grep -qi '^playerModel=mario' "$GAME_DIR/config/galaxycraft.properties" 2> /dev/null && MODEL=galaxycraft-mario
GALAXYCRAFT=1 GALAXYCRAFT_BOOT=space dolphin/build/Binaries/dolphin-emu -u "$DOLPHIN_DIR" \
  -e "syati/build/$MODEL.json" \
  -C Dolphin.Input.BackgroundInput=True -C Dolphin.General.HotkeysRequireFocus=False \
  -C Dolphin.Core.RAMOverrideEnable=True -C Dolphin.Core.MEM2Size=268435456 -C Dolphin.Core.CPUThread=True \
  -C Dolphin.Interface.ConfirmStop=False &
DOLPHIN=$!
# Minecraft itself (not a game test): its title screen, its worlds, its options, kept between runs.
(cd fabric && exec ./gradlew runClient -PgalaxycraftHidden -PgalaxycraftGameDir="$GAME_DIR" ${GXC_TRACE_BODIES:+-PgalaxycraftTraceBodies} \
  --console=plain -q) &
MINECRAFT=$!
trap 'kill $DOLPHIN $MINECRAFT 2> /dev/null' INT TERM
wait -n $DOLPHIN $MINECRAFT
kill $DOLPHIN $MINECRAFT 2> /dev/null
stop_minecraft  # Gradle's Minecraft child, if Gradle was killed first
wait
