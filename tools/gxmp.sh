#!/bin/bash
# Two full Super Minecraft Galaxy games on one machine (a host and a guest) for multiplayer tests.
# Each is Minecraft + its own Dolphin running SMG2, started by tools/gxplay.sh. Opens windows on the
# player's desktop: only run it when the user can see them (or has said it is fine).
#
#   tools/gxmp.sh prepare   the worktree's own syati build (+ link a Dolphin build) if missing
#   tools/gxmp.sh both      prepare, host, wait until the host stands on its planets, guest, wait until it joined
#   tools/gxmp.sh host      only the host (opens World 'New World (13)' to LAN on port 25599)
#   tools/gxmp.sh guest     only the guest ("Amigo"; its own data folder under /tmp/g2)
#   tools/gxmp.sh status    what is running and the last multiplayer lines of both logs
#   tools/gxmp.sh stop      closes both games (what the host built is saved in its world)
#
# Settings (environment): GXC_WORLD (host's world, default 'New World (13)'), GXC_PORT (25599),
# GXC_GUEST_HOME (/tmp/g2), GXC_FOCUS_INPUT=0 to let both games take the keyboard and mouse at once (default:
# only the window that has the focus), GXC_MP_TRACE=0 to silence the position trace (default on: `[mp trace]`
# lines each second with where each player says it is and where the others are).
#
# The mistakes this script exists to avoid (all of them cost hours once):
#  - syati/build/galaxycraft.json holds ABSOLUTE paths of the checkout that built it. Linking another
#    checkout's syati/build makes Dolphin boot SMG2 without the mod: it sits at SMG2's title, "Press A and B",
#    and the host falls out of the world. `prepare` builds syati in THIS checkout (about 80 s). Only the
#    Dolphin binary (dolphin/build) can be shared by a link.
#  - The guest's shared memory folder (/tmp/g2/shm) must exist, or its Dolphin cannot make the bridge.
#  - The guest must start only after the host's planets are complete: booting the second game at the same
#    time starves the first and Mario falls through the planet before its chunks arrive.
#  - Never run `pkill -f` with a pattern that is also in your own command line (it kills your shell, exit 144):
#    `stop` does it from this file, whose command line has no such pattern.
set -u
cd "$(dirname "$0")/.." || exit 1
ROOT=$PWD
WORLD=${GXC_WORLD:-New World (13)}
PORT=${GXC_PORT:-25599}
GUEST_HOME=${GXC_GUEST_HOME:-/tmp/g2}
HOST_LOG="${XDG_DATA_HOME:-$HOME/.local/share}/galaxycraft/minecraft/logs/latest.log"
GUEST_LOG="$GUEST_HOME/galaxycraft/minecraft/logs/latest.log"
OUT=${TMPDIR:-/tmp}
[ "${GXC_FOCUS_INPUT:-1}" = 0 ] && unset GXC_FOCUS_INPUT || export GXC_FOCUS_INPUT=1
[ "${GXC_MP_TRACE:-1}" = 0 ] && unset GXC_MP_TRACE || export GXC_MP_TRACE=1

say() { echo "gxmp: $*"; }

prepare() {
  if ! grep -q "\"root\": \"$ROOT/syati/build\"" syati/build/galaxycraft.json 2> /dev/null; then
    [ -L syati/build ] && rm syati/build # a link to someone else's build is the trap above
    say "building syati for this checkout (about 80 s)"
    syati/build.sh > "$OUT/gxmp-syati.log" 2>&1 || { say "syati build failed, see $OUT/gxmp-syati.log"; exit 1; }
  fi
  if [ ! -x dolphin/build/Binaries/dolphin-emu ]; then
    for other in "$ROOT"/../*/dolphin/build; do
      if [ -x "$other/Binaries/dolphin-emu" ]; then
        ln -sfn "$(readlink -f "$other")" dolphin/build
        say "Dolphin linked from $(readlink -f "$other")"
        break
      fi
    done
  fi
  [ -x dolphin/build/Binaries/dolphin-emu ] || { say "no Dolphin build: run dolphin/build.sh"; exit 1; }
}

running() { pgrep -f "dolphin/build/Binaries/dolphin-emu" > /dev/null; }

host() {
  say "host: '$WORLD', LAN port $PORT"
  GXC_OFFLINE_LAN=1 GXC_LAN_PORT=$PORT GXC_MC_ARGS="--quickPlaySingleplayer '$WORLD'" \
    setsid nohup tools/gxplay.sh > "$OUT/gxmp-host.log" 2>&1 < /dev/null &
}

guest() {
  mkdir -p "$GUEST_HOME/shm"
  say "guest: Amigo, data folder $GUEST_HOME"
  GXC_KEEP_OTHERS=1 GXC_SHM_DIR="$GUEST_HOME/shm" XDG_DATA_HOME="$GUEST_HOME" GXC_OFFLINE_LAN=1 \
    GXC_MC_ARGS="--username Amigo --quickPlayMultiplayer localhost:$PORT" \
    setsid nohup nice -n 5 tools/gxplay.sh > "$OUT/gxmp-guest.log" 2>&1 < /dev/null &
}

# The host's log is fresh (this run's) and its planets stopped completing for 30 s.
wait_host() {
  local end=$((SECONDS + 600)) last=-1 since=$SECONDS n
  say "waiting for the host to stand on its planets"
  sleep 20 # Minecraft starts a new log in its first seconds; do not read the last run's
  while [ $SECONDS -lt $end ]; do
    sleep 4
    [ -n "$(find "$HOST_LOG" -mmin -5 2> /dev/null)" ] || continue
    n=$(grep -c "of the galaxy is complete" "$HOST_LOG" 2> /dev/null)
    [ "$n" != "$last" ] && { last=$n; since=$SECONDS; }
    [ "${n:-0}" -ge 1 ] && [ $((SECONDS - since)) -ge 30 ] && return 0
  done
  say "the host did not settle in 10 minutes: see $HOST_LOG"
  return 1
}

wait_guest() {
  local end=$((SECONDS + 420))
  while [ $SECONDS -lt $end ]; do
    grep -q "Amigo entered the galaxy" "$HOST_LOG" 2> /dev/null && { say "Amigo is in the host's galaxy"; return 0; }
    sleep 5
  done
  say "the guest did not join in 7 minutes: see $GUEST_LOG and $OUT/gxmp-guest.log"
  return 1
}

stop() {
  pkill -f "dolphin/build/Binaries/dolphin-emu"
  pkill -f "galaxycraft.hidden=true"
  pkill -f "tools/gxplay.sh"
  sleep 3
  pkill -KILL -f "galaxycraft.hidden=true"
  pkill -KILL -f "dolphin/build/Binaries/dolphin-emu"
  say "stopped"
}

status() {
  say "Dolphin processes: $(pgrep -fc "dolphin/build/Binaries/dolphin-emu")"
  for l in "$HOST_LOG" "$GUEST_LOG"; do
    echo "== $l"
    grep -E "\[GalaxyCraft mp\]|is complete|mp trace|Exception in|fell out|under the ground" "$l" 2> /dev/null | cut -c1-200 | tail -6
  done
}

case "${1:-}" in
  prepare) prepare ;;
  host) prepare; running && { say "a game is already running: tools/gxmp.sh stop first"; exit 1; }; host ;;
  guest) prepare; guest ;;
  both) prepare; running && { say "a game is already running: tools/gxmp.sh stop first"; exit 1; }; host; wait_host && guest && wait_guest ;;
  status) status ;;
  stop) stop ;;
  *) sed -n 2,12p "$0" | cut -c3-; exit 1 ;;
esac
