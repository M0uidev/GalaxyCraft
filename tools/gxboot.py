#!/usr/bin/env python3
"""GalaxyCraft's own boot in the dev Dolphin (GALAXYCRAFT_BOOT=space), with no Minecraft: the game
must reach GalaxyCraftSpace by itself, with no input, and Mario must wait at the origin there.

  gxboot.py               with the dev NAND's save files
  gxboot.py --fresh-nand  with none (the autopilot makes a file); the NAND is put back after

Exits non-zero on failure; leaves ScreenShots/SB4E01/boot-*.png.
"""
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GXDEV = [sys.executable, str(ROOT / "tools/gxdev.py")]
SAVE = Path.home() / ".local/share/galaxycraft-dev/Wii/title/00010000/53423445/data"
STAGE = "GalaxyCraftSpace"
BOOT_TIMEOUT_S = 90
HOLD_S = 20
# Debug words after the mailbox: sizeof(GxcMailbox) + Debug::boot's offset (syati/src/GalaxyCraft.cpp).
BOOT_WORDS = 4048 + 108 * 4


def gx(*args):
    return subprocess.run([*GXDEV, *args], text=True, capture_output=True).stdout


def mbx():
    line = gx("ctl", "mbx")
    m = re.search(r"at=([0-9a-f]+) .*scene=(\d+) .*anchor=\(([-\d.]+),([-\d.]+),([-\d.]+)\).* stage=(\S+)", line)
    if not m:
        return None
    return {"at": int(m.group(1), 16), "scene": int(m.group(2)),
            "anchor": tuple(float(m.group(i)) for i in (3, 4, 5)), "stage": m.group(6), "line": line.strip()}


def boot_words(at):
    out = gx("ctl", f"peek {at + BOOT_WORDS:x} 16")
    hexes = "".join(re.findall(r"[0-9a-f]{2}", out.split(":", 1)[-1]))
    return [int(hexes[i:i + 8], 16) for i in range(0, 32, 8)] if len(hexes) >= 32 else []


def run():
    gx("stop")
    env = dict(os.environ, GALAXYCRAFT_BOOT="space")
    if subprocess.run([*GXDEV, "start", "--speed", "0"], env=env).returncode:
        return "dolphin did not start"
    start = time.time()
    m = None
    while time.time() - start < BOOT_TIMEOUT_S:
        m = mbx()
        if m and m["stage"] == STAGE:
            break
        time.sleep(1)
    if not m or m["stage"] != STAGE:
        words = boot_words(m["at"]) if m else []
        gx("ctl", "shot boot-stuck")
        return f"not in {STAGE} after {BOOT_TIMEOUT_S} s: {m and m['line']} boot={[hex(w) for w in words]}"
    print(f"gxboot: {STAGE} after {time.time() - start:.0f} s")
    scene = m["scene"]
    time.sleep(HOLD_S)
    m = mbx()
    gx("ctl", "shot boot-space")
    if m["scene"] != scene:
        return f"the stage restarted (scene {scene} -> {m['scene']}): Mario was not held"
    if max(abs(c) for c in m["anchor"]) > 1:
        return f"Mario moved away from the origin: {m['anchor']}"
    print(f"gxboot: Mario held at {m['anchor']} for {HOLD_S} s")
    return None


def main(argv):
    fresh = "--fresh-nand" in argv
    aside = SAVE.with_name("data.gxboot-aside")
    if fresh:
        if aside.exists():
            sys.exit(f"gxboot: {aside} exists (a run was interrupted): put it back first")
        shutil.move(SAVE, aside)
    try:
        error = run()
        if fresh and not error:  # again, with the file the first boot made
            print("gxboot: again with the file just made")
            error = run()
    finally:
        gx("stop")
        if fresh:
            shutil.rmtree(SAVE, ignore_errors=True)
            shutil.move(aside, SAVE)
    if error:
        sys.exit(f"gxboot: FAILED: {error}")
    print("gxboot: PASS")


if __name__ == "__main__":
    main(sys.argv)
