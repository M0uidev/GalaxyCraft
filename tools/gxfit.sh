#!/bin/sh
# Does Mario fit where Steve would? MarioFitTest (fabric gametest) in the dev Dolphin: holes, a
# closed 1x1x2 cell, a jump under its ceiling, a shaft down a small planet, and that he stays still
# in all of them, frame by frame (tools/gxshake.py). Overrides, in blocks: GXC_RADIUS (his radius
# on planets), GXC_INSET (walls' collision inside their block), GXC_GROW (floors under walls),
# GXC_CRUST (crust depth of new planets). Shares /dev/shm with tools/gxplay.sh: not while playing.
#   tools/gxfit.sh   (captures: ~/.local/share/galaxycraft-dev/ScreenShots/SB4E01/fit-*.png)
set -u
: "${JAVA_HOME:=$(ls -d "$HOME"/.local/opt/jdk-25* 2>/dev/null | head -1)}"
export JAVA_HOME
cd "$(dirname "$0")/.." || exit 1
G="python3 tools/gxdev.py"
SAV="$HOME/.local/share/galaxycraft-dev/fit-intro.sav"
LOG="$HOME/.local/share/galaxycraft-dev/fit-minecraft.log"
# GXC_KEEP=1 leaves Dolphin running after a failure, for peeking.
fail() { echo "gxfit: FAILED: $*" >&2; [ -n "${GXC_KEEP:-}" ] || $G stop > /dev/null; exit 1; }

# GXC_REUSE=1 reuses the last prologue savestate (only if syati/ has not changed since).
if [ -z "${GXC_REUSE:-}" ] || [ ! -f "$SAV" ]; then
  syati/build.sh > /dev/null 2>&1 || fail "syati/build.sh"
  $G stop > /dev/null
  $G start --speed 0 || fail "dolphin did not start"
  sleep 8
  python3 tools/gxroute.py new-game || fail "route to the prologue"
  # Mario sleeps in the storybook until the text goes and the stick wakes him.
  for _ in 1 2 3 4; do $G press A 0.15; sleep 1.5; done
  $G stick 0 1 1; sleep 3
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

echo "gxfit: running MarioFitTest (log: $LOG)"
(cd fabric && ./gradlew runClientGameTest -PgalaxycraftFit ${GXC_RADIUS:+-PgalaxycraftMarioRadius=$GXC_RADIUS} ${GXC_INSET:+-PgalaxycraftWallInset=$GXC_INSET} ${GXC_CRUST:+-PgalaxycraftCrust=$GXC_CRUST} ${GXC_GROW:+-PgalaxycraftFloorGrow=$GXC_GROW} --console=plain) > "$LOG" 2>&1
grep "\[GalaxyCraft fit\]" "$LOG"
grep -q "\[GalaxyCraft fit\] DONE" "$LOG" || fail "MarioFitTest (see $LOG)"
grep -q "\[GalaxyCraft fit\] FAIL" "$LOG" && fail "MarioFitTest: Mario does not fit somewhere (see $LOG)"
[ -n "${GXC_KEEP:-}" ] || $G stop > /dev/null
echo "gxfit: PASS"
