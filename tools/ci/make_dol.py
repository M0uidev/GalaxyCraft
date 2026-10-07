#!/usr/bin/env python3
"""Writes a tiny GameCube program (a .dol that loops forever), for CI to boot the patched Dolphin
without a game disc: the GalaxyCraft bridge starts with any emulation, so this is enough to see it
create the shared memory, write its log and answer the dev control channel.

  python tools/ci/make_dol.py out.dol
"""
import struct
import sys

LOAD = 0x80003100  # where GameCube programs usually start
CODE = struct.pack(">8I", 0x60000000, 0x60000000, 0x60000000, 0x4BFFFFF4,  # nop x3; b -12
                   0, 0, 0, 0)


def dol():
    header = bytearray(0x100)
    struct.pack_into(">I", header, 0x00, 0x100)  # text 0: file offset
    struct.pack_into(">I", header, 0x48, LOAD)  # text 0: address
    struct.pack_into(">I", header, 0x90, len(CODE))  # text 0: size
    struct.pack_into(">I", header, 0xE0, LOAD)  # entry point
    return bytes(header) + CODE


if __name__ == "__main__":
    with open(sys.argv[1], "wb") as f:
        f.write(dol())
