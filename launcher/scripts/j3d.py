"""For powerstar.py. Minimal J3D (.bdl) reader: positions, normals and triangles per shape, plus joint transforms."""
import struct, sys, math
import numpy as np

def chunks(d):
    o = 0x20; out = {}
    while o < len(d):
        tag, sz = d[o:o+4].decode(), struct.unpack('>I', d[o+4:o+8])[0]
        out[tag] = o; o += sz
    return out

CT = {0: ('B', 1), 1: ('b', 1), 2: ('H', 2), 3: ('h', 2), 4: ('f', 4)}

def load(path):
    d = open(path, 'rb').read(); c = chunks(d)
    # VTX1
    v = c['VTX1']; fmt_off = struct.unpack('>I', d[v+8:v+12])[0]
    offs = struct.unpack('>13I', d[v+12:v+12+52])
    secsize = struct.unpack('>I', d[v+4:v+8])[0]
    arrays = {}; fmts = []
    o = v + fmt_off
    while True:
        attr, cnt, ctype, frac = struct.unpack('>IIIB', d[o:o+13]); o += 16
        if attr == 0xFF: break
        fmts.append((attr, cnt, ctype, frac))
    def idx(attr):
        return {9: 0, 10: 1, 11: 3, 12: 4, 13: 5}.get(attr, None)
    # data offsets order: pos, nrm, nbt, col0, col1, tex0..7
    present = [(i, x) for i, x in enumerate(offs) if x]
    for attr, cnt, ctype, frac in fmts:
        k = idx(attr)
        if k is None: continue
        start = offs[k]
        nxt = min([x for i, x in present if x > start] + [secsize])
        if attr in (9, 10, 13):
            ncomp = {9: 3 if cnt else 2, 10: 3, 13: 2 if cnt else 1}[attr]
            ch, sz = CT[ctype]
            n = (nxt - start) // (sz * ncomp)
            a = np.array(struct.unpack('>%d%s' % (n * ncomp, ch), d[v+start:v+start+n*ncomp*sz]), float).reshape(n, ncomp)
            if ctype != 4: a /= (1 << frac)
            arrays[attr] = a
    # JNT1
    j = c['JNT1']; nj = struct.unpack('>H', d[j+8:j+10])[0]
    eo = struct.unpack('>I', d[j+12:j+16])[0]
    joints = []
    for i in range(nj):
        e = j + eo + i * 0x40
        sx, sy, sz = struct.unpack('>3f', d[e+4:e+16])
        rx, ry, rz = struct.unpack('>3h', d[e+16:e+22])
        tx, ty, tz = struct.unpack('>3f', d[e+24:e+36])
        joints.append(((sx, sy, sz), (rx, ry, rz), (tx, ty, tz)))
    # SHP1
    s = c['SHP1']
    ns = struct.unpack('>H', d[s+8:s+10])[0]
    (init_o, remap_o, name_o, desc_o, mtxtab_o, dl_o, mtxdata_o, pkt_o) = struct.unpack('>8I', d[s+12:s+44])
    shapes = []
    for si in range(ns):
        e = s + init_o + si * 0x28
        mtype, mgc, desc_i, first_md, first_pkt = d[e], *struct.unpack('>HHHH', d[e+2:e+10])
        desc = []; o = s + desc_o + desc_i
        while True:
            attr, typ = struct.unpack('>II', d[o:o+8]); o += 8
            if attr == 0xFF: break
            desc.append((attr, typ))
        tris = []; pmidx = []
        for p in range(mgc):
            psz, poff = struct.unpack('>II', d[s+pkt_o+(first_pkt+p)*8: s+pkt_o+(first_pkt+p)*8+8])
            o = s + dl_o + poff; end = o + psz
            while o < end:
                op = d[o]; o += 1
                if op == 0: break
                cnt = struct.unpack('>H', d[o:o+2])[0]; o += 2
                verts = []
                for _ in range(cnt):
                    vv = {}
                    for attr, typ in desc:
                        if typ == 1:
                            if attr == 0: vv[attr] = d[o]; o += 1
                            else: raise Exception('direct attr %d' % attr)
                        elif typ == 2: vv[attr] = d[o]; o += 1
                        elif typ == 3: vv[attr] = struct.unpack('>H', d[o:o+2])[0]; o += 2
                    verts.append(vv)
                prim = op & 0xF8
                if prim == 0x90:
                    tl = [verts[i:i+3] for i in range(0, cnt - 2, 3)]
                elif prim == 0x98:
                    tl = [(verts[i], verts[i+1], verts[i+2]) if i % 2 == 0 else (verts[i+1], verts[i], verts[i+2]) for i in range(cnt - 2)]
                elif prim == 0xA0:
                    tl = [(verts[0], verts[i], verts[i+1]) for i in range(1, cnt - 1)]
                else:
                    raise Exception('prim %x' % op)
                tris += tl
        shapes.append((desc, tris))
    return arrays, joints, shapes

if __name__ == '__main__':
    arrays, joints, shapes = load(sys.argv[1])
    for k, a in arrays.items(): print('attr', k, a.shape, a.min(0), a.max(0))
    for jt in joints: print('joint', jt)
    for i, (desc, tris) in enumerate(shapes):
        P = arrays[9][[v[9] for t in tris for v in t]]
        print('shape', i, desc, len(tris), P.min(0).round(1), P.max(0).round(1))
