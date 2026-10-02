#!/bin/sh
# End to end against the real game: the dev Dolphin (tools/gxdev.py, under gdb so a crash leaves
# a backtrace in its log) loads the Sky Station savestate, and Minecraft's MarioPerspectivesTest
# goes through the F5 perspectives in Mario mode. Afterwards Mario must be back in the game's
# hands. Exits non-zero on any failure.
#   tools/gxe2e.sh            (needs ~/.local/share/galaxycraft-dev/sky.sav: tools/gxroute.py)
set -u
: "${JAVA_HOME:=$(ls -d "$HOME"/.local/opt/jdk-25* 2>/dev/null | head -1)}"
export JAVA_HOME
cd "$(dirname "$0")/.." || exit 1
G="python3 tools/gxdev.py"
SAV="$HOME/.local/share/galaxycraft-dev/sky.sav"
LOG="$HOME/.local/share/galaxycraft-dev/e2e-minecraft.log"
fail() { echo "gxe2e: FAILED: $*" >&2; $G stop > /dev/null; exit 1; }

[ -f "$SAV" ] || fail "no $SAV (tools/gxroute.py new-game && tools/gxroute.py sky)"
syati/build.sh > /dev/null 2>&1 || fail "syati/build.sh"
$G stop > /dev/null
$G start --speed 1 --gdb || fail "dolphin did not start"
for _ in $(seq 60); do
  $G ctl mbx 2> /dev/null | grep -q '^at=' && break
  sleep 1
done
$G ctl "load $SAV" --wait 60 | grep -q "ok load" || fail "load $SAV"
sleep 2

echo "gxe2e: running MarioPerspectivesTest (log: $LOG)"
(cd fabric && ./gradlew runClientGameTest -PgalaxycraftGalaxy --console=plain) > "$LOG" 2>&1
grep "\[GalaxyCraft e2e\]" "$LOG"
grep -q "\[GalaxyCraft e2e\] PASS" "$LOG" || fail "MarioPerspectivesTest (see $LOG)"

# Minecraft is gone: within the 2 s heartbeat timeout plus a margin Mario is the game's again.
sleep 3
MBX=$($G ctl mbx)
echo "gxe2e: after Minecraft: $MBX"
echo "$MBX" | grep -Eq 'flags=[02]/0 ' || fail "host still follows Mario"
AT=$(echo "$MBX" | sed -n 's/^at=\([0-9a-f]*\).*/\1/p')
# Debug.following, right after the mailbox (sizeof(GxcMailbox) = 0xFA8).
FOLLOWING=$($G ctl "peek 0x$(printf '%x' $((0x$AT + 0xFA8 + 0x1C))) 4" | cut -d' ' -f2-5)
[ "$FOLLOWING" = "00 00 00 00" ] || fail "module still hides Mario ($FOLLOWING)"
$G ctl "shot e2e-after" | tail -1
$G stop > /dev/null
echo "gxe2e: PASS"
