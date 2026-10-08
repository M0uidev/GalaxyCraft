#!/bin/sh
# End to end for the voxel planet: a fresh boot of the dev Dolphin with the current module walks
# to the prologue (savestates hold the module's code), then Minecraft's VoxelPlanetTest spawns a
# planet, lands Mario on it, digs under him and builds next to him. Exits non-zero on failure.
#   GXC_GUI=1 tools/gxvoxel.sh ...   any of these in a Dolphin window, to watch it
#   tools/gxvoxel.sh          (captures: ~/.local/share/galaxycraft-dev/ScreenShots/SB4E01/voxel-*.png)
#   tools/gxvoxel.sh held     HeldItemTest instead: Steve holds the hotbar's items (held-*.png)
#   tools/gxvoxel.sh entities EntityTest instead: mobs, TNT and drops drawn by the game (entities-*.png)
#   tools/gxvoxel.sh perf     PerfProbe instead: what each kind of planet costs the emulator (perf-*.png);
#                             GXC_PERF_ARGS="-PperfOnly=caves -PperfRadius=128" narrows or changes it
#   tools/gxvoxel.sh lod      LodProbe instead: a planet seen from 40 to 1200 blocks off (lod-*.png)
#   tools/gxvoxel.sh tp       TpProbe instead: P onto a planet just made lands on it, not inside
#   tools/gxvoxel.sh biomes   BiomeProbe instead: generated planets' biome colors and water (biomes-*.png)
#   tools/gxvoxel.sh walk     WalkProbe instead: Mario walks where the camera looks, also while it turns
#   tools/gxvoxel.sh movement MovementProbe instead: Esc's menu, Minecraft's movement, /skin (move-*.png)
#   tools/gxvoxel.sh memory   MemoryProbe instead: the game's memory with several big generated planets
#   tools/gxvoxel.sh mining   MiningProbe instead: holding to break, cracks, the stairs' outline (mining-*.png)
#   tools/gxvoxel.sh drawn    DrawnBlocksProbe instead: chests, beds, signs and the like on a planet (drawn-*.png)
#   tools/gxvoxel.sh elytra   ElytraProbe instead: elytra from planet to planet, the void, the wind (elytra-*.png)
#   tools/gxvoxel.sh station  StationDolphinProbe instead: a flat station in space, Mario on it, packed (station-*.png)
#   tools/gxvoxel.sh boot     GalaxyCraft's own boot, no Minecraft: SMG2 reaches GalaxyCraftSpace by
#                             itself and Mario waits there (tools/gxboot.py; --fresh-nand: no save file)
#   tools/gxvoxel.sh launch   LauncherProbe: title screen, Create World, the home planet, leave and come
#                             back (launch-*.png in fabric/build/run/clientGameTest/screenshots)
#   tools/gxvoxel.sh universe UniverseProbe: the floating origin a million blocks out and following Mario
#                             through the void (universe-*.png)
#   tools/gxvoxel.sh galaxy   GalaxyProbe: Create World's tab, a galaxy of 64 planets, 8 complete and the
#                             rest far, an edit on the farthest kept (galaxy-*.png)
set -u
# GXC_GUI=1 runs Dolphin in a window, to watch the test as it plays.
GUI="${GXC_GUI:+--gui}"
# Under gdb (crash backtraces in the log) the windowed Dolphin quits at once: not with GXC_GUI.
GDB="--gdb"; [ -z "$GUI" ] || GDB=""
if [ "${1:-}" = launch ] || [ "${1:-}" = galaxy ] || [ "${1:-}" = universe ]; then
  # LauncherProbe (or GalaxyProbe, a world of many planets): the dev Dolphin boots by itself (no
  # savestate), Minecraft starts at its title.
  G="python3 tools/gxdev.py"
  : "${JAVA_HOME:=$(ls -d "$HOME"/.local/opt/jdk-25* 2>/dev/null | head -1)}"
  export JAVA_HOME
  if [ "$1" = galaxy ]; then NAME=GalaxyProbe GPROP=galaxycraftGalaxy GTAG=galaxy
  elif [ "$1" = universe ]; then NAME=UniverseProbe GPROP=galaxycraftUniverse GTAG=universe
  else NAME=LauncherProbe GPROP=galaxycraftLauncher GTAG=launch; fi
  LOG="$HOME/.local/share/galaxycraft-dev/$GTAG-minecraft.log"
  syati/build.sh > /dev/null 2>&1 || { echo "gxvoxel: FAILED: syati/build.sh" >&2; exit 1; }
  $G stop > /dev/null
  GALAXYCRAFT_BOOT=space $G start $GUI --speed 1 || { echo "gxvoxel: FAILED: dolphin did not start" >&2; exit 1; }
  echo "gxvoxel: running $NAME (log: $LOG)"
  (cd fabric && ./gradlew runClientGameTest -P$GPROP ${GXC_ARGS:-} --console=plain) > "$LOG" 2>&1
  grep "\[GalaxyCraft $GTAG\]" "$LOG"
  [ -n "${GXC_KEEP:-}" ] || $G stop > /dev/null
  grep -q "\[GalaxyCraft $GTAG\] PASS" "$LOG" || { echo "gxvoxel: FAILED: $NAME (see $LOG)" >&2; exit 1; }
  echo "gxvoxel: PASS"
  exit 0
fi
if [ "${1:-}" = boot ]; then
  shift
  "$(dirname "$0")/../syati/build.sh" > /dev/null 2>&1 || { echo "gxvoxel: FAILED: syati/build.sh" >&2; exit 1; }
  exec python3 "$(dirname "$0")/gxboot.py" "$@"
fi
: "${JAVA_HOME:=$(ls -d "$HOME"/.local/opt/jdk-25* 2>/dev/null | head -1)}"
export JAVA_HOME
cd "$(dirname "$0")/.." || exit 1
G="python3 tools/gxdev.py"
if [ "${1:-}" = held ]; then TEST=HeldItemTest PROP=galaxycraftHeld TAG=held
elif [ "${1:-}" = entities ]; then TEST=EntityTest PROP=galaxycraftEntities TAG=entities
elif [ "${1:-}" = perf ]; then TEST=PerfProbe PROP=galaxycraftPerf TAG=perf
elif [ "${1:-}" = walk ]; then TEST=WalkProbe PROP=galaxycraftWalk TAG=walk
elif [ "${1:-}" = movement ]; then TEST=MovementProbe PROP=galaxycraftMovement TAG=movement
elif [ "${1:-}" = mining ]; then TEST=MiningProbe PROP=galaxycraftMining TAG=mining
elif [ "${1:-}" = drawn ]; then TEST=DrawnBlocksProbe PROP=galaxycraftDrawn TAG=drawn
elif [ "${1:-}" = elytra ]; then TEST=ElytraProbe PROP=galaxycraftElytra TAG=elytra
elif [ "${1:-}" = station ]; then TEST=StationDolphinProbe PROP=galaxycraftStationGame TAG=station
elif [ "${1:-}" = memory ]; then TEST=MemoryProbe PROP=galaxycraftMemory TAG=memory
elif [ "${1:-}" = lod ]; then TEST=LodProbe PROP=galaxycraftLod TAG=lod
elif [ "${1:-}" = tp ]; then TEST=TpProbe PROP=galaxycraftTp TAG=tp
elif [ "${1:-}" = biomes ]; then TEST=BiomeProbe PROP=galaxycraftBiomes TAG=biomes
else TEST=VoxelPlanetTest PROP=galaxycraftVoxel TAG=voxel; fi
# GXC_SAV=<savestate> starts from another one instead (made with the current module; not remade).
SAV="${GXC_SAV:-$HOME/.local/share/galaxycraft-dev/voxel-intro.sav}"
LOG="$HOME/.local/share/galaxycraft-dev/$TAG-minecraft.log"
# GXC_KEEP=1 leaves Dolphin running after a failure, for peeking.
fail() { echo "gxvoxel: FAILED: $*" >&2; [ -n "${GXC_KEEP:-}" ] || $G stop > /dev/null; exit 1; }

# GXC_REUSE=1 reuses the last prologue savestate (only if syati/ has not changed since).
if [ -n "${GXC_SAV:-}" ]; then
  [ -f "$SAV" ] || fail "no savestate $SAV"
elif [ -z "${GXC_REUSE:-}" ] || [ ! -f "$SAV" ]; then
  syati/build.sh > /dev/null 2>&1 || fail "syati/build.sh"
  $G stop > /dev/null
  # Headless even with GXC_GUI: the route's inputs do not reach a windowed Dolphin (it stays at the title).
  $G start --speed 0 || fail "dolphin did not start"
  sleep 8
  python3 tools/gxroute.py new-game || fail "route to the prologue"
  $G ctl "save $SAV" --wait 30 | grep -q "ok save" || fail "save $SAV"
fi
$G stop > /dev/null
$G start $GUI --speed 1 $GDB || fail "dolphin did not start"
for _ in $(seq 60); do
  $G ctl mbx 2> /dev/null | grep -q '^at=' && break
  sleep 1
done
$G ctl "load $SAV" --wait 60 | grep -q "ok load" || fail "load $SAV"
sleep 2

echo "gxvoxel: running $TEST (log: $LOG)"
(cd fabric && ./gradlew runClientGameTest -P$PROP ${GXC_PERF_ARGS:-} ${GXC_ARGS:-} --console=plain) > "$LOG" 2>&1
grep "\[GalaxyCraft $TAG\]" "$LOG"
grep -q "\[GalaxyCraft $TAG\] PASS" "$LOG" || fail "$TEST (see $LOG)"
[ -n "${GXC_KEEP:-}" ] || $G stop > /dev/null
echo "gxvoxel: PASS"
