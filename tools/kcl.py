"""Nintendo KCL collision codec (big-endian), as used by Super Mario Galaxy 2.

Layout: 0x38-byte header, positions (f32[3]), normals (f32[3]), prisms (0x10 each,
1-based: the header's prism offset points 0x10 before the first one), octree.
The octree written here is a single leaf listing every prism. A leaf's list starts 2 bytes
after the offset it stores (checked on SMG2's own KCL), so the game can walk it; readers in this
project ignore the octree and build their own spatial index.
"""
import math
import struct

_HEADER = struct.Struct(">IIIIf3f3I3I")
_VEC = struct.Struct(">3f")
_PRISM = struct.Struct(">fHHHHHH")


def _sub(a, b):
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


def _add(a, b):
    return (a[0] + b[0], a[1] + b[1], a[2] + b[2])


def _scale(a, s):
    return (a[0] * s, a[1] * s, a[2] * s)


def _dot(a, b):
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def _cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def _unit(a):
    n = math.sqrt(_dot(a, a))
    return None if n < 1e-9 else _scale(a, 1.0 / n)


def write(tris, attribute=0):
    positions, normals, prisms, kept = [], [], [], []
    for v1, v2, v3 in tris:
        f = _unit(_cross(_sub(v2, v1), _sub(v3, v1)))
        if f is None:
            continue
        ea = _unit(_cross(f, _sub(v3, v1)))
        eb = _unit(_cross(_sub(v2, v1), f))
        ec = _unit(_cross(f, _sub(v2, v3)))
        height = _dot(_sub(v2, v1), ec)
        base = len(normals)
        normals += [f, ea, eb, ec]
        prisms.append((height, len(positions), base, base + 1, base + 2, base + 3, attribute))
        positions.append(v1)
        kept.append((v1, v2, v3))

    pos_off = _HEADER.size
    nrm_off = pos_off + 12 * len(positions)
    prism_data = nrm_off + 12 * len(normals)
    oct_off = prism_data + 0x10 * len(prisms)

    all_v = [v for t in kept for v in t] or [(0.0, 0.0, 0.0)]
    area_min = tuple(min(v[i] for v in all_v) - 1.0 for i in range(3))
    extent = max(max(v[i] for v in all_v) - area_min[i] + 1.0 for i in range(3))
    shift = max(0, math.ceil(math.log2(extent)))
    mask = (~((1 << shift) - 1)) & 0xFFFFFFFF

    out = bytearray(_HEADER.pack(pos_off, nrm_off, prism_data - 0x10, oct_off, 40.0,
                                 *area_min, mask, mask, mask, shift, 0, 0))
    for v in positions:
        out += _VEC.pack(*v)
    for n in normals:
        out += _VEC.pack(*n)
    for p in prisms:
        out += _PRISM.pack(*p)
    out += struct.pack(">I", 0x80000000 | 2)  # root: leaf, list right after the node
    out += struct.pack(f">{len(prisms) + 1}H", *range(1, len(prisms) + 1), 0)
    while len(out) % 4:
        out += b"\0"
    return bytes(out)


def read(data):
    pos_off, nrm_off, prism_off, oct_off = struct.unpack_from(">IIII", data, 0)
    positions = [_VEC.unpack_from(data, o) for o in range(pos_off, nrm_off, 12)]
    normals = [_VEC.unpack_from(data, o) for o in range(nrm_off, prism_off + 0x10 - 11, 12)]
    tris = []
    for o in range(prism_off + 0x10, oct_off, 0x10):
        h, pi, fi, e1, e2, e3, _ = _PRISM.unpack_from(data, o)
        v1, f = positions[pi], normals[fi]
        ca = _cross(normals[e1], f)
        cb = _cross(normals[e2], f)
        ec = normals[e3]
        v2 = _add(v1, _scale(cb, h / _dot(cb, ec)))
        v3 = _add(v1, _scale(ca, h / _dot(ca, ec)))
        tris.append((v1, v2, v3))
    return tris
