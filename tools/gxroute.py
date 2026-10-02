#!/usr/bin/env python3
"""Scripted menu routes for the GalaxyCraft dev harness (tools/gxdev.py must be running Dolphin).

Savestates hold the module's code, so after rebuilding syati/ the dev savestates are stale and
the game has to be walked to the test stage again; these routes do that.

  gxroute.py new-game   title -> file 2 -> Start -> storybook intro (Mario controllable)
  gxroute.py sky        from the intro to Sky Station, then save <userdir>/sky.sav

Every step prints the mailbox summary and leaves ScreenShots/SB4E01/route-N.png.
"""
import re
import subprocess
import sys
import time
from pathlib import Path

GXDEV = Path(__file__).resolve().parent / "gxdev.py"
USER_DIR = Path.home() / ".local/share/galaxycraft-dev"
_step = 0


def gx(*args, capture=False):
    out = subprocess.run([sys.executable, str(GXDEV), *args], check=True, text=True,
                         stdout=subprocess.PIPE if capture else None)
    return out.stdout if capture else None


def press(button, secs=0.15, wait=1.0):
    gx("press", button, str(secs))
    time.sleep(wait)


def stick(x, y, secs):
    gx("stick", str(x), str(y), str(secs))


def point(x, y):
    gx("point", str(x), str(y))
    time.sleep(0.3)


def mbx():
    line = gx("ctl", "mbx", capture=True).strip()
    m = re.search(r"scene=(\d+) grav=\(([-\d.]+),([-\d.]+),([-\d.]+)\)", line)
    return line, (int(m.group(1)), tuple(float(m.group(i)) for i in (2, 3, 4))) if m else (0, None)


def checkpoint(label):
    global _step
    _step += 1
    line, _ = mbx()
    gx("ctl", f"shot route-{_step}")
    print(f"[{_step}] {label}: {line}")


def new_game():
    gx("pad", "PRESS A; PRESS B")
    time.sleep(0.3)
    gx("pad", "RELEASE A; RELEASE B")
    time.sleep(4)
    point(0, 0)  # file 2 (already created by a previous run)
    press("A", 0.2, 3)
    point(0.52, -0.78)  # Start
    press("A", 0.2, 5)
    for _ in range(18):
        press("A", 0.15, 1.0)
    checkpoint("intro")


def sky():
    """Recorded 2026-10-02 at --speed 0 from the intro checkpoint of new-game."""
    _, (start_scene, _) = mbx()
    for _ in range(8):  # storybook: walk right
        stick(1, 0, 3)
        press("A", 0.1, 0)
    for _ in range(12):  # spin tutorial
        press("Y", 0.3, 0)
        stick(1, 0, 2.5)
        press("A", 0.1, 0)
    for _ in range(3):  # Luma crystal, when the walk ends next to it
        press("Y", 0.3, 0.5)
        stick(1, 0, 0.4)
    for _ in range(20):
        press("A", 0.1, 1)
    for _ in range(8):  # castle gate -> Bowser cutscene
        stick(1, 0, 2.5)
        press("A", 0.1, 0)
    for _ in range(40):
        press("A", 0.1, 1)
    checkpoint("courtyard")
    stick(0.7, 0.7, 4)  # to the low ledge
    gx("pad", "SET MAIN 0.3 0.0")  # jump it, forward-left
    time.sleep(0.5)
    for _ in range(4):
        press("A", 0.3, 0.8)
    time.sleep(2)
    gx("pad", "SET MAIN 0.5 0.5")
    stick(1, 0.2, 4)  # along the railing
    stick(-0.5, 1, 3)  # to the Lumas at the door
    for _ in range(46):
        press("A", 0.1, 1)
    checkpoint("launch star")
    stick(0, 1, 2)
    press("Y", 0.3, 3)
    press("Y", 0.3, 0)
    for _ in range(25):
        press("A", 0.1, 1)
    point(0, -0.1)  # star select: the only star
    press("A", 0.2, 2)
    press("A", 0.2, 15)
    for _ in range(25):
        press("A", 0.1, 1)
    checkpoint("sky station")
    _, (scene, grav) = mbx()
    if scene <= start_scene:
        raise SystemExit(f"sky: still in scene {scene}; check the route-N.png captures")
    gx("ctl", f"save {USER_DIR / 'sky.sav'}", "--wait", "30")


def main(argv):
    routes = {"new-game": new_game, "sky": sky}
    if len(argv) != 2 or argv[1] not in routes:
        raise SystemExit(__doc__)
    routes[argv[1]]()


if __name__ == "__main__":
    main(sys.argv)
