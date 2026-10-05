#!/usr/bin/env python3
"""SMG2's JMap tables (.bcsv, and the extensionless placement files in Map.arc): read and write.

A table is (fields, rows): fields are (name_or_hash, mask, offset, shift, type) and rows are dicts
keyed by field name (or "0x<hash>" when the name is unknown).

    bcsv.py dump FILE.bcsv
"""
import struct
import sys

# type: 0 int, 2 float, 4 short, 5 byte, 6 string offset (the last two of SMG, 1 old string too)
_SIZE = {0: 4, 2: 4, 3: 4, 4: 2, 5: 1, 6: 4}
KNOWN = """name Name type l_id l_type pos_x pos_y pos_z dir_x dir_y dir_z scale_x scale_y scale_z
Obj_arg0 Obj_arg1 Obj_arg2 Obj_arg3 Obj_arg4 Obj_arg5 Obj_arg6 Obj_arg7 SW_APPEAR SW_DEAD SW_A SW_B
SW_SLEEP SW_AWAKE SW_PARAM SW_DEMO Camera_id CommonPath_ID ClippingGroupId GroupId DemoGroupId
MapParts_ID Obj_ID MessageId CastId ViewGroupId ShapeModelNo AreaShapeNo ParentID ParamScale
MarioNo Number Mode Camera_id ScenarioNo ScenarioName PowerStarId AppearPowerStarObj Comet
LuigiModeTimer ZoneName StageName ParentStage IsHidden SecretPowerStarNo WorldNo PowerStarType
CometLimitTimer MarioPosX MarioPosY MarioPosZ StarPieceNum ScenarioIdx Scenario Galaxy GalaxyName
FileName PowerStar Type ID Id Area AreaName LayerName Layer Value Comment""".split()


def jhash(name):
    h = 0
    for c in name.encode():
        c = c - 256 if c > 127 else c
        h = (h * 31 + c) & 0xFFFFFFFF
    return h


_NAMES = {jhash(n): n for n in KNOWN}


def read(data):
    count, nfields, data_off, entry_size = struct.unpack_from(">IIII", data, 0)
    fields = []
    for i in range(nfields):
        h, mask, off, shift, typ = struct.unpack_from(">IIHBB", data, 16 + i * 12)
        fields.append((_NAMES.get(h, "0x%08x" % h), mask, off, shift, typ))
    strings = data_off + count * entry_size
    rows = []
    for r in range(count):
        base = data_off + r * entry_size
        row = {}
        for name, mask, off, shift, typ in fields:
            at = base + off
            if typ == 2:
                v = struct.unpack_from(">f", data, at)[0]
            elif typ == 6:
                s = strings + struct.unpack_from(">I", data, at)[0]
                v = data[s:data.index(b"\0", s)].decode("shift_jis", "replace")
            else:
                raw = {4: ">I", 1: ">I", 0: ">I", 3: ">I", 2: ">I"}.get(typ)
                if typ == 4:
                    raw = struct.unpack_from(">H", data, at)[0]
                elif typ == 5:
                    raw = data[at]
                else:
                    raw = struct.unpack_from(">I", data, at)[0]
                v = (raw & mask) >> shift
                if typ == 0 and v & 0x80000000:
                    v -= 1 << 32
                if typ == 4 and v & 0x8000:
                    v -= 1 << 16
            row[name] = v
        rows.append(row)
    return fields, entry_size, rows


def write(fields, entry_size, rows):
    """A table with these fields (as read) and rows; strings are pooled."""
    pool, at = bytearray(), {}
    body = bytearray(entry_size * len(rows))
    for r, row in enumerate(rows):
        base = r * entry_size
        for name, mask, off, shift, typ in fields:
            v = row[name]
            o = base + off
            if typ == 2:
                struct.pack_into(">f", body, o, float(v))
            elif typ == 6:
                if v not in at:
                    at[v] = len(pool)
                    pool += v.encode("shift_jis") + b"\0"
                struct.pack_into(">I", body, o, at[v])
            elif typ == 4:
                old = struct.unpack_from(">H", body, o)[0]
                struct.pack_into(">H", body, o, (old & ~mask & 0xFFFF) | ((v << shift) & mask))
            elif typ == 5:
                body[o] = (body[o] & ~mask & 0xFF) | ((v << shift) & mask)
            else:
                old = struct.unpack_from(">I", body, o)[0]
                struct.pack_into(">I", body, o, (old & ~mask & 0xFFFFFFFF) | ((v << shift) & mask & 0xFFFFFFFF))
    head = struct.pack(">IIII", len(rows), len(fields), 16 + 12 * len(fields), entry_size)
    for name, mask, off, shift, typ in fields:
        h = int(name, 16) if name.startswith("0x") else jhash(name)
        head += struct.pack(">IIHBB", h, mask, off, shift, typ)
    out = head + body + pool
    return bytes(out + b"@" * (-len(out) % 32))


def main(argv):
    if len(argv) != 3 or argv[1] != "dump":
        raise SystemExit(__doc__)
    fields, _, rows = read(open(argv[2], "rb").read())
    print([f[0] for f in fields])
    for row in rows:
        print(row)


if __name__ == "__main__":
    main(sys.argv)
