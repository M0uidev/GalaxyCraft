#!/usr/bin/env python3
"""Make a panorama capture the title screen's background.

    tools/set_panorama.py <screenshots/panorama_... folder>

Copies its panorama_0..5.png (square, resized to 1024 if they are not already) into the mod's
resources, where they replace Minecraft's. Rebuild or restart the game to see it.
"""
import sys
from pathlib import Path

from PIL import Image

SIZE = 1024
DEST = Path(__file__).resolve().parent.parent / "fabric/src/main/resources/assets/minecraft/textures/gui/title/background"


def main(argv):
    if len(argv) != 2 or not Path(argv[1]).is_dir():
        sys.exit(__doc__)
    src = Path(argv[1])
    faces = [src / f"panorama_{i}.png" for i in range(6)]
    for f in faces:
        if not f.is_file():
            sys.exit(f"missing {f}")
    DEST.mkdir(parents=True, exist_ok=True)
    for i, f in enumerate(faces):
        img = Image.open(f).convert("RGB")
        if img.width != img.height:
            sys.exit(f"{f} is not square")
        if img.width != SIZE:
            img = img.resize((SIZE, SIZE), Image.LANCZOS)
        img.save(DEST / f"panorama_{i}.png", optimize=True)
        print(f"panorama_{i}.png")
    print(f"title screen panorama set from {src} ({DEST})")


main(sys.argv)
