"""Nintendo RARC archives (as SMG2's .arc files), optionally Yaz0-compressed.
  rarc.py extract ARC DIR    write every file of ARC under DIR
The game reads uncompressed archives too, so replace() and build() never compress.
"""
import os
import struct
import sys

_HEADER = struct.Struct(">4sIIIIIII")  # magic, size, header size (0x20), data offset, data size, mram, aram, dvd
_INFO = struct.Struct(">IIIIIIH?x")     # nodes, node off, entries, entry off, strings size, string off, files, sync ids
_NODE = struct.Struct(">4sIHHI")        # id, name offset, name hash, entry count, first entry
_ENTRY = struct.Struct(">HHHHIII")      # id, name hash, flags << 8, name offset, data offset / node, size, pad
_FILE, _DIR = 0x1100, 0x0200


def yaz0_decompress(data):
    if data[:4] != b"Yaz0":
        return data
    size = struct.unpack(">I", data[4:8])[0]
    out = bytearray()
    i = 16
    while len(out) < size:
        code = data[i]
        i += 1
        for bit in range(8):
            if len(out) >= size:
                break
            if code & (0x80 >> bit):
                out.append(data[i])
                i += 1
                continue
            b1, b2 = data[i], data[i + 1]
            i += 2
            dist = ((b1 & 0xF) << 8 | b2) + 1
            n = b1 >> 4
            if n == 0:
                n = data[i] + 0x12
                i += 1
            else:
                n += 2
            for _ in range(n):
                out.append(out[-dist])
    return bytes(out)


def _layout(arc):
    h = _HEADER.unpack_from(arc, 0)
    assert h[0] == b"RARC", "not a RARC archive"
    info = _INFO.unpack_from(arc, 0x20)
    return h[3] + 0x20, info[3] + 0x20, info[2], info[5] + 0x20


def _entries(arc):
    """(entry offset, name, flags, data offset, size) of every file entry, in order."""
    data, ents, count, strings = _layout(arc)
    for e in range(count):
        at = ents + e * _ENTRY.size
        _, _, flags, name_off, off, size, _ = _ENTRY.unpack_from(arc, at)
        name = arc[strings + name_off:arc.index(b"\0", strings + name_off)].decode()
        if not (flags & _DIR) and name not in (".", ".."):
            yield at, name, flags, off, size


def list_files(arc):
    arc = yaz0_decompress(arc)
    data = _layout(arc)[0]
    return [(name, arc[data + off:data + off + size]) for _, name, _, off, size in _entries(arc)]


def replace(arc, files):
    """The same archive with the named files' contents replaced (data re-laid out)."""
    arc = bytearray(yaz0_decompress(arc))
    data = _layout(arc)[0]
    known = {name for _, name, _, _, _ in _entries(arc)}
    for name in files:
        if name not in known:
            raise KeyError(name)
    blobs = bytearray()
    for at, name, _, off, size in list(_entries(arc)):
        blob = files.get(name, bytes(arc[data + off:data + off + size]))
        struct.pack_into(">II", arc, at + 8, len(blobs), len(blob))
        blobs += blob + bytes(-len(blob) % 32)
    head = arc[:data]
    struct.pack_into(">I", head, 4, len(head) + len(blobs))
    struct.pack_into(">III", head, 0x10, len(blobs), len(blobs), 0)
    return bytes(head) + bytes(blobs)


def _hash(name):
    h = 0
    for c in name.encode():
        h = (h * 3 + c) & 0xFFFF
    return h


def build(root, files):
    """A one-folder archive (root/ holding files), for tests and new archives."""
    strings, offsets = bytearray(), {}
    for n in [".", ".."] + [root] + [n for n, _ in files]:
        if n not in offsets:
            offsets[n] = len(strings)
            strings += n.encode() + b"\0"
    strings += bytes(-len(strings) % 32)
    entries, blobs = bytearray(), bytearray()
    for i, (n, blob) in enumerate(files):
        entries += _ENTRY.pack(i, _hash(n), _FILE, offsets[n], len(blobs), len(blob), 0)
        blobs += blob + bytes(-len(blob) % 32)
    entries += _ENTRY.pack(0xFFFF, _hash("."), _DIR, offsets["."], 0, 0x10, 0)
    entries += _ENTRY.pack(0xFFFF, _hash(".."), _DIR, offsets[".."], 0xFFFFFFFF, 0x10, 0)
    entries += bytes(-len(entries) % 32)
    node = _NODE.pack(b"ROOT", offsets[root], _hash(root), len(files) + 2, 0)
    node += bytes(-len(node) % 32)
    info_size = 0x20
    node_off = info_size
    ent_off = node_off + len(node)
    str_off = ent_off + len(entries)
    data_off = str_off + len(strings)
    info = _INFO.pack(1, node_off, len(files) + 2, ent_off, len(strings), str_off, len(files), True)
    info += bytes(info_size - len(info))
    body = info + node + entries + strings
    head = _HEADER.pack(b"RARC", 0x20 + len(body) + len(blobs), 0x20, data_off, len(blobs), len(blobs), 0, 0)
    return head + body + bytes(blobs)


def main(argv):
    if len(argv) != 4 or argv[1] != "extract":
        raise SystemExit(__doc__)
    with open(argv[2], "rb") as f:
        arc = f.read()
    for name, blob in list_files(arc):
        os.makedirs(argv[3], exist_ok=True)
        with open(os.path.join(argv[3], name), "wb") as f:
            f.write(blob)
        print(name, len(blob))


if __name__ == "__main__":
    main(sys.argv)
