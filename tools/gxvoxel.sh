#!/bin/sh
# End to end for the voxel planet: a fresh boot of the dev Dolphin with the current module walks
# to the prologue (savestates hold the module's code), then Minecraft's VoxelPlanetTest spawns a
# planet, lands Mario on it, digs under him and builds next to him. Exits non-zero on failure.
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
#   tools/gxvoxel.sh elytra   ElytraProbe instead: elytra from planet to planet, the void, the wind (elytra-*.png)
set -u
: "${JAVA_HOME:=$(ls -d "$HOME"/.local/opt/jdk-25* 2>/dev/null | head -1)}"
export JAVA_HOME
cd "$(dirname "$0")/.." || exit 1
G="python3 tools/gxdev.py"
if [ "${1:-}" = held ]; then TEST=HeldItemTest PROP=galaxycraftHeld TAG=held
elif [ "${1:-}" = entities ]; then TEST=EntityTest PROP=galaxycraftEntities TAG=entities
elif [ "${1:-}" = perf ]; then TEST=PerfProbe PROP=galaxycraftPerf TAG=perf
elif [ "${1:-}" = walk ]; then TEST=WalkProbe PROP=galaxycraftWalk TAG=walk
elif [ "${1:-}" = movement ]; then TEST=MovementProbe PROP=galaxycraftMovement TAG=movement
elif [ "${1:-}" = elytra ]; then TEST=ElytraProbe PROP=galaxycraftElytra TAG=elytra
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
  $G start --speed 0 || fail "dolphin did not start"
  sleep 8
  python3 tools/gxroute.py new-game || fail "route to the prologue"
  $G ctl "save $SAV" --wait 30 | grep -q "ok save" || fail "save $SAV"
fi
$G stop > /dev/null
$G start --speed 1 --gdb || fail "dolphin did not start"
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
