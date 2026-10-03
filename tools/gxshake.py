#!/usr/bin/env python3
"""How much Mario shakes, frame by frame, from the shared memory (WorldState: gravity and Mario's
position, written by Dolphin every frame). Minecraft's ticks see one frame in three: a bounce
that repeats every three frames looks still from there.

  tools/gxshake.py SECONDS [--dump]   (--dump: also each frame's position) prints "up=<spread> side=<bounce> frames=<n>", units:
    up: how far apart his highest and lowest positions along gravity are
    side: the largest step sideways right after one the other way (a bounce, not a walk)
"""
import mmap
import struct
import sys
import time

OFF_WORLD = 64


def main(secs):
    with open("/dev/shm/galaxycraft_v1", "rb") as f:
        m = mmap.mmap(f.fileno(), 4096, prot=mmap.PROT_READ)
    frames = []
    last = None
    end = time.monotonic() + secs
    while time.monotonic() < end:
        seq = struct.unpack_from("<I", m, OFF_WORLD)[0]
        if seq & 1:
            continue
        frame = struct.unpack_from("<Q", m, OFF_WORLD + 8)[0]
        g = struct.unpack_from("<3f", m, OFF_WORLD + 16)
        p = struct.unpack_from("<3f", m, OFF_WORLD + 28)
        if struct.unpack_from("<I", m, OFF_WORLD)[0] != seq:
            continue
        if frame != last:
            last = frame
            frames.append((g, p, struct.unpack_from("<Q", m, 128 + 8)[0]))
        time.sleep(0.002)
    ups, side, prev = [], 0.0, None
    for i in range(1, len(frames)):
        g, p = frames[i][:2]
        n = sum(x * x for x in g) ** 0.5 or 1.0
        up = [-x / n for x in g]
        d = [p[k] - frames[i - 1][1][k] for k in range(3)]
        h = sum(d[k] * up[k] for k in range(3))
        step = [d[k] - h * up[k] for k in range(3)]
        ups.append(sum((p[k] - frames[0][1][k]) * up[k] for k in range(3)))
        if prev is not None and sum(step[k] * prev[k] for k in range(3)) < 0:
            side = max(side, min(sum(x * x for x in step) ** 0.5, sum(x * x for x in prev) ** 0.5))
        prev = step
    if "--dump" in sys.argv:
        for i, (g, p, player) in enumerate(frames):
            print(f"  {i} up={ups[i - 1] - ups[0] if i else 0:.2f} pos={p[0]:.2f},{p[1]:.2f},{p[2]:.2f} player_frame={player}")
    spread = max(ups) - min(ups) if ups else 0.0
    print(f"up={spread:.2f} side={side:.2f} frames={len(frames)}")


if __name__ == "__main__":
    main(float(sys.argv[1]) if len(sys.argv) > 1 else 1.5)
