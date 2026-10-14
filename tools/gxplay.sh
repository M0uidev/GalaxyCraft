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
# With GXC_KEEP_OTHERS (a second game beside this one, for a multiplayer test) only the Minecraft that
# runs in this game's folder is ours to stop.
ours() {
  for p in $(pgrep -f "$MC_MATCH"); do
    [ -z "${GXC_KEEP_OTHERS:-}" ] || [ "$(readlink "/proc/$p/cwd")" = "$(readlink -f "$GAME_DIR")" ] && echo "$p"
  done
}
stop_minecraft() {
  local pids; pids=$(ours)
  [ -n "$pids" ] || return 0
  kill $pids 2> /dev/null
  for _ in 1 2 3 4 5; do sleep 1; [ -n "$(ours)" ] || return 0; done
  kill -KILL $(ours) 2> /dev/null
}
[ -n "${GXC_KEEP_OTHERS:-}" ] || stop_minecraft  # GXC_KEEP_OTHERS=1: a second game for a multiplayer test

# Dual core (CPUThread): this Dolphin defaults to single core on desktop, which puts the CPU and
# the GPU on one thread and leaves no headroom on planets.
# Background input / hotkeys without focus, for this run only (-C is not saved): on Hyprland,
# Dolphin's window can hold the compositor's focus without Qt noticing.
# The Mario Model setting (Steve's body or the original Mario) picks which Mario the game loads, so it
# is read here, at the start: change it in Super Minecraft Galaxy's settings and play again.
MODEL=galaxycraft
grep -qi '^marioModel=original' "$GAME_DIR/config/galaxycraft.properties" 2> /dev/null && MODEL=galaxycraft-mario
# Picture Quality (a Super Minecraft Galaxy setting; the same table as launcher/src/core/videoquality.js):
# Dolphin's internal resolution, MSAA and anisotropic filtering, for this run only.
QUALITY=$(sed -n 's/^[[:space:]]*videoQuality[[:space:]]*=[[:space:]]*\([A-Za-z]*\).*/\1/p' "$GAME_DIR/config/galaxycraft.properties" 2> /dev/null | tr a-z A-Z)
case "$QUALITY" in
  LOW) VQ="1 1 0" ;;
  MEDIUM) VQ="2 2 2" ;;
  ULTRA) VQ="4 4 3" ;;
  *) VQ="3 4 3" ;; # High, the default
esac
read -r VRES VMSAA VANISO <<< "$VQ"
VIDEO=(-C "Graphics.Settings.InternalResolution=$VRES" -C "Graphics.Settings.MSAA=$VMSAA" -C "Graphics.Enhancements.MaxAnisotropy=$VANISO")
# GXC_FOCUS_INPUT=1 (two games on one machine, a multiplayer test): each Dolphin takes the keyboard and the
# mouse only while its own window is focused, so one game can be played without moving the other.
FOCUS=(-C Dolphin.Input.BackgroundInput=True -C Dolphin.General.HotkeysRequireFocus=False)
[ -n "${GXC_FOCUS_INPUT:-}" ] && FOCUS=(-C Dolphin.Input.BackgroundInput=False -C Dolphin.General.HotkeysRequireFocus=True)
GALAXYCRAFT=1 GALAXYCRAFT_BOOT=space dolphin/build/Binaries/dolphin-emu -u "$DOLPHIN_DIR" \
  -e "syati/build/$MODEL.json" \
  "${FOCUS[@]}" \
  -C Dolphin.Core.RAMOverrideEnable=True -C Dolphin.Core.MEM2Size=268435456 -C Dolphin.Core.CPUThread=True \
  -C Dolphin.Interface.ConfirmStop=False "${VIDEO[@]}" &
DOLPHIN=$!
# Minecraft itself (not a game test): its title screen, its worlds, its options, kept between runs.
(cd fabric && exec ./gradlew runClient -PgalaxycraftHidden -PgalaxycraftGameDir="$GAME_DIR" ${GXC_TRACE_BODIES:+-PgalaxycraftTraceBodies} \
  ${GXC_MC_ARGS:+"--args=$GXC_MC_ARGS"} --console=plain -q) &  # GXC_MC_ARGS: Minecraft's own arguments (--quickPlaySingleplayer <world>, --quickPlayMultiplayer host:port)
MINECRAFT=$!
trap 'kill $DOLPHIN $MINECRAFT 2> /dev/null' INT TERM
wait -n $DOLPHIN $MINECRAFT
kill $DOLPHIN $MINECRAFT 2> /dev/null
stop_minecraft  # Gradle's Minecraft child, if Gradle was killed first
wait
