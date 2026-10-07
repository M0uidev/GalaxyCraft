"""The Power Star picture (src/renderer/powerstar.png) the launcher draws: SMG2's own model
(ObjectData/PowerStar.arc -> PowerStar.bdl, from your disc) rendered with Galaxy-style gold shading.
   python3 tools/rarc.py extract PowerStar.arc ps && python3 scripts/powerstar.py ps/PowerStar.bdl src/renderer/powerstar.png 512"""
import sys, math
import numpy as np
from PIL import Image
from j3d import load

def render(bdl, size, yaw=-18, pitch=8, roll=0, ss=3):
    arrays, joints, shapes = load(bdl)
    P, N = arrays[9], arrays[10]
    a, b, r = map(math.radians, (yaw, pitch, roll))
    Ry = np.array([[math.cos(a), 0, math.sin(a)], [0, 1, 0], [-math.sin(a), 0, math.cos(a)]])
    Rx = np.array([[1, 0, 0], [0, math.cos(b), -math.sin(b)], [0, math.sin(b), math.cos(b)]])
    Rz = np.array([[math.cos(r), -math.sin(r), 0], [math.sin(r), math.cos(r), 0], [0, 0, 1]])
    R = Rz @ Rx @ Ry
    W = size * ss
    body = shapes[0][1]
    allp = (P[[v[9] for t in body for v in t]] @ R.T)
    lo, hi = allp[:, :2].min(0), allp[:, :2].max(0)
    scale = 0.9 * W / (hi - lo).max(); ctr = (lo + hi) / 2
    img = np.zeros((W, W, 4)); zb = np.full((W, W), -1e9)
    L = np.array([-0.45, 0.6, 0.66]); L /= np.linalg.norm(L)
    V = np.array([0, 0, 1.0]); H = (L + V) / np.linalg.norm(L + V)
    mats = {0: 'gold', 1: 'eye', 2: None, 3: None}  # shapes 2 and 3 repeat 0 and 1 for another material
    for si, (desc, tris) in enumerate(shapes):
        m = mats[si]
        if m is None: continue
        for t in tris:
            p = P[[v[9] for v in t]] @ R.T
            n = N[[v[10] for v in t]] @ R.T
            xy = (p[:, :2] - ctr) * scale * np.array([1, -1]) + W / 2
            x0, y0 = np.floor(xy.min(0)).astype(int); x1, y1 = np.ceil(xy.max(0)).astype(int)
            x0, y0 = max(x0, 0), max(y0, 0); x1, y1 = min(x1, W - 1), min(y1, W - 1)
            if x1 < x0 or y1 < y0: continue
            gx, gy = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
            (ax, ay), (bx, by), (cx, cy) = xy
            den = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
            if abs(den) < 1e-9: continue
            w0 = ((by - cy) * (gx - cx) + (cx - bx) * (gy - cy)) / den
            w1 = ((cy - ay) * (gx - cx) + (ax - cx) * (gy - cy)) / den
            w2 = 1 - w0 - w1
            inside = (w0 >= 0) & (w1 >= 0) & (w2 >= 0)
            if not inside.any(): continue
            z = w0 * p[0, 2] + w1 * p[1, 2] + w2 * p[2, 2] + (0.5 if m == 'eye' else 0)
            sub = zb[y0:y1 + 1, x0:x1 + 1]
            vis = inside & (z > sub)
            if not vis.any(): continue
            nn = w0[..., None] * n[0] + w1[..., None] * n[1] + w2[..., None] * n[2]
            nn /= np.linalg.norm(nn, axis=-1, keepdims=True) + 1e-9
            dif = np.clip(nn @ L, 0, 1)
            spec = np.clip(nn @ H, 0, 1) ** 40
            rim = (1 - np.clip(nn @ V, 0, 1)) ** 2.5
            if m == 'gold':
                base = np.array([1.0, 0.80, 0.12]); shade = np.array([0.95, 0.45, 0.05])
                k = 0.35 + 0.75 * dif
                col = base * k[..., None] + shade * (1 - k[..., None]) * 0.6
                col = col + 0.55 * spec[..., None] + np.array([1.0, 0.95, 0.6]) * 0.35 * rim[..., None]
            else:
                # Near-black eyes with a small highlight.
                col = np.array([0.04, 0.03, 0.02]) + np.array([0.25, 0.25, 0.3]) * (spec[..., None] > 0.6)
            sub[vis] = z[vis]
            region = img[y0:y1 + 1, x0:x1 + 1]
            region[vis, :3] = np.clip(col[vis], 0, 1); region[vis, 3] = 1
    im = Image.fromarray((img * 255).astype(np.uint8), 'RGBA')
    return im.resize((size, size), Image.LANCZOS)

if __name__ == '__main__':
    render(sys.argv[1], int(sys.argv[3]) if len(sys.argv) > 3 else 512).save(sys.argv[2])
