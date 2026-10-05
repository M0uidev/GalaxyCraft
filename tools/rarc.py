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


def _walk(arc):
    """(path, entry offset, data offset, size) of every file, paths as root/dir/.../name."""
    arc = yaz0_decompress(arc)
    h = _HEADER.unpack_from(arc, 0)
    info = _INFO.unpack_from(arc, 0x20)
    nodes, ents, strings = info[1] + 0x20, info[3] + 0x20, info[5] + 0x20

    def name_at(off):
        return arc[strings + off:arc.index(b"\0", strings + off)].decode()

    def node(i, prefix):
        _, name_off, _, count, first = _NODE.unpack_from(arc, nodes + i * _NODE.size)
        path = prefix + name_at(name_off)
        for e in range(first, first + count):
            at = ents + e * _ENTRY.size
            _, _, flags, n_off, off, size, _ = _ENTRY.unpack_from(arc, at)
            n = name_at(n_off)
            if n in (".", ".."):
                continue
            if flags & _DIR:
                yield from node(off, path + "/")
            else:
                yield path + "/" + n, at, off, size

    return arc, list(node(0, ""))


def list_paths(arc):
    """[(path, contents)] with each file's folder path (root/dir/name)."""
    arc, files = _walk(arc)
    data = _layout(arc)[0]
    return [(p, arc[data + off:data + off + size]) for p, _, off, size in files]


def replace_paths(arc, files, renames=None):
    """The same archive with the files at these paths (as list_paths names them) replaced, and
    the files in renames ({path: new file name}) renamed."""
    arc, walked = _walk(arc)
    arc = bytearray(arc)
    data = _layout(arc)[0]
    known = {p for p, _, _, _ in walked}
    renames = renames or {}
    for p in list(files) + list(renames):
        if p not in known:
            raise KeyError(p)
    if renames:  # new names go at the end of the string table, which then pushes the data on
        info = _INFO.unpack_from(arc, 0x20)
        strings, size = info[5] + 0x20, info[4]
        used = arc.rindex(b"\0", strings, strings + size) + 1  # past the last name: padding
        table = bytearray(arc[strings:used])
        for p, at, _, _ in walked:
            if p in renames:
                name = renames[p]
                struct.pack_into(">H", arc, at + 2, _hash(name))
                struct.pack_into(">H", arc, at + 6, len(table))
                table += name.encode() + b"\0"
        table += bytes(-len(table) % 32)
        rest = arc[strings + size:data]  # nothing in SMG2's archives, kept if present
        arc = arc[:strings] + table + rest + arc[data:]
        struct.pack_into(">I", arc, 0x20 + 16, len(table))
        data = strings + len(table) + len(rest)
        struct.pack_into(">I", arc, 12, data - 0x20)
    blobs = bytearray()
    for p, at, off, size in sorted(walked, key=lambda w: w[2]):
        blob = files.get(p, bytes(arc[data + off:data + off + size]))
        struct.pack_into(">II", arc, at + 8, len(blobs), len(blob))
        blobs += blob + bytes(-len(blob) % 32)
    head = arc[:data]
    struct.pack_into(">I", head, 4, len(head) + len(blobs))
    struct.pack_into(">III", head, 0x10, len(blobs), len(blobs), 0)
    return bytes(head) + bytes(blobs)


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
