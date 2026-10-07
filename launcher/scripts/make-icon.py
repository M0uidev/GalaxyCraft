"""The launcher's icon (build/icon.png, 512x512): SMG2's Power Star (src/renderer/powerstar.png)
over a voxel planet, on a dark rounded tile. Chunky blocks and a big star so it reads small.
Run: python3 scripts/make-icon.py"""
import math
import os
import random

from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(__file__)
S = 1024  # drawn at twice the size, then scaled down
GRASS = [(93, 156, 51), (106, 173, 58), (79, 138, 43), (123, 189, 69)]
WATER = [(52, 98, 196), (60, 110, 210)]
SAND = [(219, 207, 142), (206, 193, 128)]
TREE = [(46, 96, 30), (38, 82, 24)]
SKY = [(5, 6, 15), (13, 20, 48), (27, 35, 80)]


def noise(x, y, seed, scale=3.0):
    """Smooth value noise: random values on a coarse grid, blended."""
    gx, gy = x / scale, y / scale
    x0, y0 = math.floor(gx), math.floor(gy)
    fx, fy = gx - x0, gy - y0
    v = lambda a, b: random.Random(a * 7919 + b * 104729 + seed * 31).random()
    fx, fy = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy)
    top = v(x0, y0) * (1 - fx) + v(x0 + 1, y0) * fx
    bot = v(x0, y0 + 1) * (1 - fx) + v(x0 + 1, y0 + 1) * fx
    return top * (1 - fy) + bot * fy


img = Image.new("RGBA", (S, S))
d = ImageDraw.Draw(img)
for y in range(S):
    t = y / (S - 1)
    a, b, k = (SKY[0], SKY[1], t / 0.55) if t < 0.55 else (SKY[1], SKY[2], (t - 0.55) / 0.45)
    d.line((0, y, S, y), fill=tuple(int(a[i] + (b[i] - a[i]) * k) for i in range(3)))
rnd = random.Random(3)
for _ in range(40):
    x, y, s = rnd.randrange(S), rnd.randrange(S), rnd.choice((8, 8, 16))
    d.rectangle((x, y, x + s - 1, y + s - 1), fill=(255, 255, 255, rnd.randint(120, 240)))

# The planet, with a soft blue glow.
cx, cy, R, B = S * 0.5, S * 0.98, S * 0.46, 64
glow = Image.new("RGBA", img.size, (0, 0, 0, 0))
ImageDraw.Draw(glow).ellipse((cx - R * 1.12, cy - R * 1.12, cx + R * 1.12, cy + R * 1.12), fill=(90, 150, 255, 90))
img.alpha_composite(glow.filter(ImageFilter.GaussianBlur(R * 0.08)))
d = ImageDraw.Draw(img)
n = int(R // B) + 1
for j in range(-n, n + 1):
    for i in range(-n, n + 1):
        x, y = (i + 0.5) * B, (j + 0.5) * B
        dist = math.hypot(x, y)
        if dist > R:
            continue
        h = noise(i, j, 11)
        col = rnd.choice(WATER) if h < 0.34 else rnd.choice(SAND) if h < 0.41 else rnd.choice(TREE) if rnd.random() < 0.12 else rnd.choice(GRASS)
        nz = math.sqrt(max(0, 1 - (dist / R) ** 2))
        lit = 0.35 + 0.8 * max(0, (-(x / R) * 0.55 - (y / R) * 0.6) * 0.6 + nz * 0.6)
        col = tuple(min(255, int(c * lit)) for c in col)
        x0, y0 = int(cx + x - B / 2), int(cy + y - B / 2)
        d.rectangle((x0, y0, x0 + B - 1, y0 + B - 1), fill=col)

# The Power Star, glowing, a little tilted.
sx, sy, size = S * 0.5, S * 0.42, int(S * 0.62)
glow = Image.new("RGBA", img.size, (0, 0, 0, 0))
r = size * 0.42
ImageDraw.Draw(glow).ellipse((sx - r, sy - r, sx + r, sy + r), fill=(255, 220, 120, 110))
img.alpha_composite(glow.filter(ImageFilter.GaussianBlur(size * 0.12)))
star = Image.open(os.path.join(HERE, "..", "src", "renderer", "powerstar.png")).convert("RGBA")
star = star.resize((size, size), Image.LANCZOS).rotate(-8, Image.BICUBIC, expand=True)
img.alpha_composite(star, (int(sx - star.width / 2), int(sy - star.height / 2)))

# Cut to a rounded tile.
mask = Image.new("L", img.size, 0)
ImageDraw.Draw(mask).rounded_rectangle((32, 32, S - 32, S - 32), radius=192, fill=255)
tile = Image.new("RGBA", img.size, (0, 0, 0, 0))
tile.paste(img, (0, 0), mask)

out = os.path.join(HERE, "..", "build", "icon.png")
tile.resize((S // 2, S // 2), Image.LANCZOS).save(out)
print("wrote", os.path.normpath(out))
