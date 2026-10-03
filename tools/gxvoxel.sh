#!/bin/sh
# End to end for the voxel planet: a fresh boot of the dev Dolphin with the current module walks
# to the prologue (savestates hold the module's code), then Minecraft's VoxelPlanetTest spawns a
# planet, lands Mario on it, digs under him and builds next to him. Exits non-zero on failure.
#   tools/gxvoxel.sh          (captures: ~/.local/share/galaxycraft-dev/ScreenShots/SB4E01/voxel-*.png)
set -u
: "${JAVA_HOME:=$(ls -d "$HOME"/.local/opt/jdk-25* 2>/dev/null | head -1)}"
export JAVA_HOME
cd "$(dirname "$0")/.." || exit 1
G="python3 tools/gxdev.py"
SAV="$HOME/.local/share/galaxycraft-dev/voxel-intro.sav"
LOG="$HOME/.local/share/galaxycraft-dev/voxel-minecraft.log"
# GXC_KEEP=1 leaves Dolphin running after a failure, for peeking.
fail() { echo "gxvoxel: FAILED: $*" >&2; [ -n "${GXC_KEEP:-}" ] || $G stop > /dev/null; exit 1; }

# GXC_REUSE=1 reuses the last prologue savestate (only if syati/ has not changed since).
if [ -z "${GXC_REUSE:-}" ] || [ ! -f "$SAV" ]; then
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

echo "gxvoxel: running VoxelPlanetTest (log: $LOG)"
(cd fabric && ./gradlew runClientGameTest -PgalaxycraftVoxel --console=plain) > "$LOG" 2>&1
grep "\[GalaxyCraft voxel\]" "$LOG"
grep -q "\[GalaxyCraft voxel\] PASS" "$LOG" || fail "VoxelPlanetTest (see $LOG)"
[ -n "${GXC_KEEP:-}" ] || $G stop > /dev/null
echo "gxvoxel: PASS"
