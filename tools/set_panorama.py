#!/usr/bin/env python3
"""Make a panorama capture the title screen's background.

    tools/set_panorama.py [--no-smooth] <screenshots/panorama_... folder>

Copies its panorama_0..5.png (square, resized to 1024 if they are not already) into the mod's
resources, where they replace Minecraft's. Rebuild or restart the game to see it.

SMG2's starry sky is not the same from one view to the next, so the four side faces do not meet
exactly (the blocks do; the nebula jumps). Unless --no-smooth is given, the colour step along each
of the four vertical borders is spread over the next SPREAD pixels on both sides, which hides it.
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter

SIZE = 1024
SPREAD = 0.31  # of a face's width: how far from a border its colour step is spread out
STRIP = 4      # columns either side of a border averaged to measure the step
BLUR = 0.013   # of a face's height: blurs the measure along the border so stars do not count
DEST = Path(__file__).resolve().parent.parent / "fabric/src/main/resources/assets/minecraft/textures/gui/title/background"


def smooth_seams(faces):
    """The four side faces (0..3, each meeting the next on its right edge) with the colour step
    along every shared border spread over SPREAD of a face's width on both sides."""
    out = [np.asarray(f, dtype=np.float32).copy() for f in faces]
    n = out[0].shape[1]
    spread = max(2, int(n * SPREAD))
    ramp = (1 - np.arange(spread) / spread) ** 2  # 1 at the border, 0 far from it
    for i in range(4):
        left, right = out[i], out[(i + 1) % 4]
        step = left[:, n - STRIP:, :].mean(axis=1) - right[:, :STRIP, :].mean(axis=1)  # rows x RGB
        blurred = Image.fromarray(np.clip(step + 128, 0, 255).astype(np.uint8)[:, None, :].repeat(3, axis=1))
        blurred = blurred.filter(ImageFilter.GaussianBlur(n * BLUR))
        step = np.asarray(blurred, dtype=np.float32)[:, 1, :] - 128
        left[:, n - spread:, :] -= (step / 2)[:, None, :] * ramp[::-1][None, :, None]
        right[:, :spread, :] += (step / 2)[:, None, :] * ramp[None, :, None]
    return [Image.fromarray(np.clip(a, 0, 255).astype(np.uint8)) for a in out]


def edge_steps(faces):
    """How far each side border is from continuous: the mean difference of the two edge columns."""
    a = [np.asarray(f, dtype=np.float32) for f in faces]
    return [float(np.mean(np.abs(a[i][:, -1, :] - a[(i + 1) % 4][:, 0, :]))) for i in range(4)]


def main(argv):
    args = [a for a in argv[1:] if not a.startswith("--")]
    if len(args) != 1 or not Path(args[0]).is_dir():
        sys.exit(__doc__)
    src = Path(args[0])
    faces = [src / f"panorama_{i}.png" for i in range(6)]
    for f in faces:
        if not f.is_file():
            sys.exit(f"missing {f}")
    DEST.mkdir(parents=True, exist_ok=True)
    imgs = []
    for f in faces:
        img = Image.open(f).convert("RGB")
        if img.width != img.height:
            sys.exit(f"{f} is not square")
        if img.width != SIZE:
            img = img.resize((SIZE, SIZE), Image.LANCZOS)
        imgs.append(img)
    if "--no-smooth" not in argv:
        before = edge_steps(imgs)
        imgs = smooth_seams(imgs[:4]) + imgs[4:]
        print("border steps (mean difference): " + ", ".join(f"{b:.1f} -> {a:.1f}" for b, a in zip(before, edge_steps(imgs))))
    for i, img in enumerate(imgs):
        img.save(DEST / f"panorama_{i}.png", optimize=True)
        print(f"panorama_{i}.png")
    print(f"title screen panorama set from {src} ({DEST})")


main(sys.argv)
