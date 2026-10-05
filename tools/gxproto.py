"""Python mirror of protocol/galaxycraft_protocol.h (shared memory layout v3)."""
import mmap
import os
import struct
import time
from collections import namedtuple

SHM_PATH = "/dev/shm/galaxycraft_v1"
MAGIC = 0x52435847  # "GXCR"
VERSION = 10

OFF_HEADER = 0
OFF_WORLD = 64
OFF_PLAYER = 128
OFF_INPUT = 224
OFF_GAMECAM = 320
OFF_POINTER = 448  # GxcPointerState: the mouse over the host's window while a Minecraft screen is open
OFF_TEXT = 512  # GxcTextState: characters typed in the host's window
RING_S2M_OFF = 4096
RING_S2M_CAP = 4 * 1024 * 1024
RING_M2S_OFF = RING_S2M_OFF + 16 + RING_S2M_CAP
RING_M2S_CAP = 1024 * 1024
OFF_OVERLAY = 5251072
OVERLAY_FRAME_BYTES = 1920 * 1080 * 4
TOTAL_SIZE = OFF_OVERLAY + 32 + 3 * OVERLAY_FRAME_BYTES

HEARTBEAT_TIMEOUT_MS = 2000
KCL_CHUNK_MAX = 65536

MSG_SCENE_CHANGE = 1
MSG_PART_UPSERT = 2
MSG_PART_REMOVE = 3
MSG_KCL_CHUNK = 4
MSG_HELLO = 101
MSG_PLANET, MSG_CHUNK, MSG_PLANET_TP = 102, 103, 104  # voxel planet, M -> S
MSG_OUTLINE, MSG_HELD, MSG_ATLAS = 105, 106, 107
MSG_SKIN, MSG_MODEL, MSG_ENTITIES, MSG_HURT, MSG_SEAT = 108, 109, 110, 111, 112
MSG_PAD = 0xFFFF

PLAYER_ON_GROUND = 1
PLAYER_ITEM_ACTIVE = 2  # something in the main hand: clicks break and place blocks
WORLD_ANCHOR = 1  # WorldState.flags: query_pos is the host's anchor (where the player is)
WORLD_FOLLOW = 2  # WorldState.flags: Minecraft mode, the player follows Mario (query_pos)
VIEW_FIRST, VIEW_BACK, VIEW_FRONT, VIEW_GALAXY = 0, 1, 2, 3  # PlayerState.view (F5)
GAMECAM_VALID = 1
GAMECAM_DEMO = 2

_HEADER = struct.Struct("<IIIIQQII24x")
_WORLD = struct.Struct("<IIQ3f3fI20x")
_PLAYER = struct.Struct("<IIQ3f3f3fff3fII16x")
_GAMECAM = struct.Struct("<IIQ3f3f3ff3f3f16x")
_RING = struct.Struct("<IIII")
_MSG = struct.Struct("<HHI")
PART_UPSERT = struct.Struct("<II12f")
KCL_CHUNK = struct.Struct("<III")

Header = namedtuple("Header", "magic version host_pid mod_pid host_heartbeat_ms mod_heartbeat_ms host_flags mod_flags")
WorldState = namedtuple("WorldState", "scene_id frame_id gravity query_pos flags")
PlayerState = namedtuple("PlayerState", "frame_id pos look up fov_y eye_height on_ground cam_offset view scene_id")
GameCamera = namedtuple("GameCamera", "flags frame_id cam_pos cam_dir cam_up fov_y mario_pos mario_front")


def now_ms():
    """CLOCK_MONOTONIC milliseconds, the clock both sides use for heartbeats."""
    return time.monotonic_ns() // 1_000_000


class Shm:
    """The shared memory file, mapped read/write."""

    def __init__(self, path=SHM_PATH, create=False):
        flags = os.O_RDWR | (os.O_CREAT if create else 0)
        fd = os.open(path, flags, 0o600)
        try:
            if create:
                os.ftruncate(fd, TOTAL_SIZE)
            self.buf = mmap.mmap(fd, TOTAL_SIZE)
        finally:
            os.close(fd)
        if create:
            self.buf[0:RING_S2M_OFF] = bytes(RING_S2M_OFF)  # header and slots: drop stale state
            _RING.pack_into(self.buf, RING_S2M_OFF, 0, 0, RING_S2M_CAP, 0)
            _RING.pack_into(self.buf, RING_M2S_OFF, 0, 0, RING_M2S_CAP, 0)
            struct.pack_into("<I", self.buf, OFF_OVERLAY, 0xFFFFFFFF)

    def close(self):
        self.buf.close()


def init_host(shm):
    h = read_header(shm)
    _HEADER.pack_into(shm.buf, OFF_HEADER, MAGIC, VERSION, os.getpid(), h.mod_pid,
                      now_ms(), h.mod_heartbeat_ms, h.host_flags, h.mod_flags)


def read_header(shm):
    return Header(*_HEADER.unpack_from(shm.buf, OFF_HEADER))


def heartbeat_host(shm, flags=None):
    struct.pack_into("<Q", shm.buf, OFF_HEADER + 16, now_ms())
    if flags is not None:
        struct.pack_into("<I", shm.buf, OFF_HEADER + 32, flags)


def _seq_write(shm, off, st, *values):
    seq = struct.unpack_from("<I", shm.buf, off)[0]
    struct.pack_into("<I", shm.buf, off, (seq + 1) & 0xFFFFFFFF)
    st.pack_into(shm.buf, off, (seq + 1) & 0xFFFFFFFF, *values)
    struct.pack_into("<I", shm.buf, off, (seq + 2) & 0xFFFFFFFF)


def _seq_read(shm, off, st):
    """Returns the unpacked fields without seq, or None if never written or torn."""
    for _ in range(100):
        s1 = struct.unpack_from("<I", shm.buf, off)[0]
        if s1 & 1:
            continue
        fields = st.unpack_from(shm.buf, off)
        if struct.unpack_from("<I", shm.buf, off)[0] == s1:
            return None if s1 == 0 else fields[1:]
    return None


def write_world(shm, scene_id, frame_id, gravity, query_pos, flags=0):
    _seq_write(shm, OFF_WORLD, _WORLD, scene_id, frame_id, *gravity, *query_pos, flags)


def read_world(shm):
    f = _seq_read(shm, OFF_WORLD, _WORLD)
    if f is None:
        return None
    return WorldState(f[0], f[1], tuple(f[2:5]), tuple(f[5:8]), f[8])


def write_player(shm, frame_id, pos, look, up, fov_y, eye, on_ground, cam_offset=(0, 0, 0), view=VIEW_FIRST, scene_id=0):
    flags = PLAYER_ON_GROUND if on_ground else 0
    _seq_write(shm, OFF_PLAYER, _PLAYER, flags, frame_id, *pos, *look, *up, fov_y, eye, *cam_offset, view, scene_id)


def read_player(shm):
    f = _seq_read(shm, OFF_PLAYER, _PLAYER)
    if f is None:
        return None
    flags = f[0]
    return PlayerState(f[1], tuple(f[2:5]), tuple(f[5:8]), tuple(f[8:11]), f[11], f[12],
                       bool(flags & PLAYER_ON_GROUND), tuple(f[13:16]), f[16], f[17])


def write_game_camera(shm, flags, frame_id, cam_pos, cam_dir, cam_up, fov_y, mario_pos, mario_front):
    _seq_write(shm, OFF_GAMECAM, _GAMECAM, flags, frame_id, *cam_pos, *cam_dir, *cam_up, fov_y,
               *mario_pos, *mario_front)


def read_game_camera(shm):
    f = _seq_read(shm, OFF_GAMECAM, _GAMECAM)
    if f is None:
        return None
    return GameCamera(f[0], f[1], tuple(f[2:5]), tuple(f[5:8]), tuple(f[8:11]), f[11],
                      tuple(f[12:15]), tuple(f[15:18]))


def _align8(n):
    return (n + 7) & ~7


class Ring:
    """Single-producer single-consumer byte ring (see GxcRingHeader)."""

    def __init__(self, shm, offset):
        self.buf = shm.buf
        self.off = offset
        self.cap = _RING.unpack_from(self.buf, offset)[2]
        self.data = offset + 16

    def _head(self):
        return struct.unpack_from("<I", self.buf, self.off)[0]

    def _tail(self):
        return struct.unpack_from("<I", self.buf, self.off + 4)[0]

    def push(self, msg_type, payload):
        need = 8 + _align8(len(payload))
        head, tail = self._head(), self._tail()
        free = self.cap - ((head - tail) & 0xFFFFFFFF)
        pos = head % self.cap
        rem = self.cap - pos
        skip = rem if need > rem else 0
        if skip + need > free:
            return False
        if skip:
            if rem >= 8:
                _MSG.pack_into(self.buf, self.data + pos, MSG_PAD, 0, rem - 8)
            pos = 0
        _MSG.pack_into(self.buf, self.data + pos, msg_type, 0, len(payload))
        self.buf[self.data + pos + 8:self.data + pos + 8 + len(payload)] = payload
        struct.pack_into("<I", self.buf, self.off, (head + skip + need) & 0xFFFFFFFF)
        return True

    def pop(self):
        while True:
            head, tail = self._head(), self._tail()
            if head == tail:
                return None
            pos = tail % self.cap
            rem = self.cap - pos
            if rem < 8:
                self._set_tail(tail + rem)
                continue
            msg_type, _, length = _MSG.unpack_from(self.buf, self.data + pos)
            if msg_type == MSG_PAD:
                self._set_tail(tail + rem)
                continue
            payload = bytes(self.buf[self.data + pos + 8:self.data + pos + 8 + length])
            self._set_tail(tail + 8 + _align8(length))
            return msg_type, payload

    def _set_tail(self, value):
        struct.pack_into("<I", self.buf, self.off + 4, value & 0xFFFFFFFF)
