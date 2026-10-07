"""The launcher's icon (build/icon.png, 512x512): a voxel planet with a star, on a dark tile.
Drawn by code like the rest of the launcher's art. Run: python3 scripts/make-icon.py"""
import math
import os
import random

from PIL import Image, ImageDraw

S = 512
img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
d.rounded_rectangle((16, 16, S - 16, S - 16), radius=96, fill=(18, 22, 44, 255))
rnd = random.Random(7)
for _ in range(70):
    x, y = rnd.randint(40, S - 40), rnd.randint(40, S - 40)
    s = rnd.choice((4, 4, 8))
    d.rectangle((x, y, x + s, y + s), fill=(255, 255, 255, rnd.randint(90, 230)))

cx, cy, R, B = 256, 290, 170, 20
grass = [(93, 156, 51), (106, 173, 58), (79, 138, 43), (123, 189, 69)]
water = [(52, 98, 196), (60, 110, 210)]
sand = [(219, 207, 142), (206, 193, 128)]
tree = [(46, 96, 30), (38, 82, 24)]
n = R // B + 1
lattice = {}


def noise(x, y, scale=3.5):
    """Smooth value noise: random values on a coarse grid, blended."""
    gx, gy = x / scale, y / scale
    x0, y0 = math.floor(gx), math.floor(gy)
    fx, fy = gx - x0, gy - y0

    def v(a, b):
        if (a, b) not in lattice:
            lattice[(a, b)] = random.Random(a * 7919 + b * 104729).random()
        return lattice[(a, b)]

    fx, fy = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy)
    top = v(x0, y0) * (1 - fx) + v(x0 + 1, y0) * fx
    bot = v(x0, y0 + 1) * (1 - fx) + v(x0 + 1, y0 + 1) * fx
    return top * (1 - fy) + bot * fy


for j in range(-n, n + 1):
    for i in range(-n, n + 1):
        x, y = (i + 0.5) * B, (j + 0.5) * B
        dist = math.hypot(x, y)
        if dist > R:
            continue
        h = noise(i, j)
        col = rnd.choice(water) if h < 0.32 else rnd.choice(sand) if h < 0.38 else rnd.choice(tree) if rnd.random() < 0.12 else rnd.choice(grass)
        nz = math.sqrt(max(0, 1 - (dist / R) ** 2))
        lit = 0.35 + 0.8 * max(0, (-(x / R) * 0.55 - (y / R) * 0.6) * 0.6 + nz * 0.6)
        col = tuple(min(255, int(c * lit)) for c in col)
        x0, y0 = int(cx + x - B / 2), int(cy + y - B / 2)
        d.rectangle((x0, y0, x0 + B - 1, y0 + B - 1), fill=col)

# A five-pointed star over the planet.
sx, sy, sr = 256, 104, 70
pts = []
for k in range(10):
    a = -math.pi / 2 + k * math.pi / 5
    r = sr if k % 2 == 0 else sr * 0.45
    pts.append((sx + math.cos(a) * r, sy + math.sin(a) * r))
d.polygon(pts, fill=(255, 216, 74, 255), outline=(201, 138, 0, 255), width=8)
d.rectangle((sx - 15, sy - 9, sx - 7, sy + 9), fill=(58, 42, 0))
d.rectangle((sx + 7, sy - 9, sx + 15, sy + 9), fill=(58, 42, 0))

out = os.path.join(os.path.dirname(__file__), "..", "build", "icon.png")
img.save(out)
print("wrote", os.path.normpath(out))
