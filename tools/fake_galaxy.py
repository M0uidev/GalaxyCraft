#!/usr/bin/env python3
"""Stand-in for Dolphin + Super Mario Galaxy 2 on the GalaxyCraft protocol.

Publishes two spherical planets as KCL collision parts and answers the mod's
PlayerState with radial gravity towards the nearest planet surface.

    python3 tools/fake_galaxy.py            # run until Ctrl+C
    python3 tools/fake_galaxy.py --once     # publish, serve for 1 s, exit
"""
import argparse
import math
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gxproto  # noqa: E402
import kcl  # noqa: E402

# (center, radius) in galaxy units (100 units = 1 block). Spawn on top of planet 1.
PLANETS = [((0.0, 0.0, 0.0), 800.0), ((0.0, 2600.0, 0.0), 600.0)]
SPAWN = (0.0, 820.0, 0.0)
SCENE_ID = 1
IDENTITY = (1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0)


def sub(a, b):
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


def dot(a, b):
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def _norm(a):
    n = math.sqrt(dot(a, a))
    return (a[0] / n, a[1] / n, a[2] / n)


def icosphere(radius, subdiv, center=(0.0, 0.0, 0.0)):
    """Triangles of an icosphere, counter-clockwise seen from outside."""
    t = (1 + math.sqrt(5)) / 2
    verts = [_norm(v) for v in [(-1, t, 0), (1, t, 0), (-1, -t, 0), (1, -t, 0), (0, -1, t), (0, 1, t),
                                (0, -1, -t), (0, 1, -t), (t, 0, -1), (t, 0, 1), (-t, 0, -1), (-t, 0, 1)]]
    faces = [(0, 11, 5), (0, 5, 1), (0, 1, 7), (0, 7, 10), (0, 10, 11), (1, 5, 9), (5, 11, 4),
             (11, 10, 2), (10, 7, 6), (7, 1, 8), (3, 9, 4), (3, 4, 2), (3, 2, 6), (3, 6, 8),
             (3, 8, 9), (4, 9, 5), (2, 4, 11), (6, 2, 10), (8, 6, 7), (9, 8, 1)]
    tris = [tuple(verts[i] for i in f) for f in faces]
    for _ in range(subdiv):
        nxt = []
        for a, b, c in tris:
            ab, bc, ca = (_norm(tuple((x + y) / 2 for x, y in zip(p, q))) for p, q in ((a, b), (b, c), (c, a)))
            nxt += [(a, ab, ca), (b, bc, ab), (c, ca, bc), (ab, bc, ca)]
        tris = nxt
    return [tuple(tuple(center[i] + radius * v[i] for i in range(3)) for v in tri) for tri in tris]


def gravity_at(pos, planets):
    """Unit vector towards the planet whose surface is nearest to pos."""
    center, _ = min(planets, key=lambda p: abs(math.dist(pos, p[0]) - p[1]))
    d = sub(center, pos)
    n = math.sqrt(dot(d, d))
    return (0.0, 0.0, 0.0) if n < 1e-6 else (d[0] / n, d[1] / n, d[2] / n)


def _push(ring, msg_type, payload):
    while not ring.push(msg_type, payload):
        time.sleep(0.001)  # mod is behind; wait for it to drain


def publish_scene(shm, planets):
    """Sends SCENE_CHANGE, then one PART_UPSERT + KCL chunks per planet."""
    ring = gxproto.Ring(shm, gxproto.RING_S2M_OFF)
    _push(ring, gxproto.MSG_SCENE_CHANGE, SCENE_ID.to_bytes(4, "little"))
    for part_id, (center, radius) in enumerate(planets, start=1):
        data = kcl.write(icosphere(radius, 3))  # local coordinates; the matrix places it
        mtx = (1, 0, 0, center[0], 0, 1, 0, center[1], 0, 0, 1, center[2])
        _push(ring, gxproto.MSG_PART_UPSERT, gxproto.PART_UPSERT.pack(part_id, len(data), *mtx))
        for off in range(0, len(data), gxproto.KCL_CHUNK_MAX):
            chunk = data[off:off + gxproto.KCL_CHUNK_MAX]
            _push(ring, gxproto.MSG_KCL_CHUNK, gxproto.KCL_CHUNK.pack(part_id, off, len(data)) + chunk)


class Host:
    """One host frame per step(): heartbeat, HELLO handling, gravity at the player.

    Until the mod reports a fresh PlayerState, WorldState carries the WORLD_ANCHOR flag and
    query_pos is the host's idea of where the player is (SPAWN, or the last fresh position),
    so the mod knows where to anchor its gravity frame.
    """

    def __init__(self, shm, log=print):
        self.shm, self.log = shm, log
        gxproto.init_host(shm)
        gxproto.heartbeat_host(shm, flags=1)
        publish_scene(shm, PLANETS)
        self.m2s = gxproto.Ring(shm, gxproto.RING_M2S_OFF)
        self.frame = 0
        self.query = SPAWN
        self.anchored = True
        self.seen_frame = self._player_frame()
        self.last_log = 0.0

    def _player_frame(self):
        p = gxproto.read_player(self.shm)
        return p.frame_id if p else 0

    def step(self):
        self.frame += 1
        gxproto.heartbeat_host(self.shm, flags=1)
        while (msg := self.m2s.pop()) is not None:
            if msg[0] == gxproto.MSG_HELLO:
                self.log("mod said hello; resending scene")
                self.anchored, self.seen_frame = True, self._player_frame()
                publish_scene(self.shm, PLANETS)
        player = gxproto.read_player(self.shm)
        if player is not None and player.frame_id != self.seen_frame:
            self.seen_frame, self.anchored, self.query = player.frame_id, False, player.pos
        flags = gxproto.WORLD_ANCHOR if self.anchored else 0
        gxproto.write_world(self.shm, SCENE_ID, self.frame, gravity_at(self.query, PLANETS), self.query, flags)
        if player is not None and not self.anchored and time.monotonic() - self.last_log > 1.0:
            self.last_log = time.monotonic()
            alt = min(math.dist(player.pos, c) - r for c, r in PLANETS)
            self.log(f"pos=({player.pos[0]:8.1f},{player.pos[1]:8.1f},{player.pos[2]:8.1f}) "
                     f"alt={alt:7.1f}u on_ground={int(player.on_ground)}")


def serve(shm, seconds=None, log=print):
    host = Host(shm, log)
    start = time.monotonic()
    while seconds is None or time.monotonic() - start < seconds:
        host.step()
        time.sleep(1 / 60)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--shm", default=gxproto.SHM_PATH)
    ap.add_argument("--once", action="store_true", help="serve for one second and exit")
    args = ap.parse_args(argv)
    shm = gxproto.Shm(path=args.shm, create=True)
    try:
        serve(shm, seconds=1.0 if args.once else None)
    except KeyboardInterrupt:
        pass
    finally:
        shm.close()


if __name__ == "__main__":
    main()
