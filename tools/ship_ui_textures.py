#!/usr/bin/env python3
"""Draws the Toad ship's GUI sprites, pixel by pixel, into the mod's gui atlas folder.

Run from the repository root:  python3 tools/ship_ui_textures.py
The sprites are used 1:1 (one texture pixel is one GUI pixel), so every edge here is exact. Palette: blued steel
for the frame, a Toad-cap red for the header, gold for what is selected.
"""
from pathlib import Path

from PIL import Image

OUT_DIR = Path(__file__).resolve().parent.parent / "fabric/src/main/resources/assets/galaxycraft/textures/gui/sprites/ship"

CLEAR = (0, 0, 0, 0)


def c(r, g, b, a=255):
    return (r, g, b, a)


OUTLINE = c(20, 21, 31)
S0 = c(30, 32, 45)     # darkest steel (recesses)
S1 = c(52, 56, 76)     # shadow
S2 = c(84, 90, 116)    # body
S3 = c(118, 126, 156)  # raised
S4 = c(168, 176, 204)  # highlight
S5 = c(214, 220, 238)  # glint
RED = c(216, 40, 47)
RED_D = c(143, 26, 34)
RED_L = c(255, 107, 111)
WHITE = c(244, 244, 248)
GOLD = c(255, 214, 102)
GOLD_D = c(196, 142, 38)
GREEN = c(96, 255, 96)
SLOT_IN = c(26, 28, 40)
SLOT_DK = c(12, 12, 20)
GHOST = c(150, 158, 190, 150)


class Img:
    def __init__(self, w, h, fill=CLEAR):
        self.w, self.h = w, h
        self.im = Image.new("RGBA", (w, h), fill)

    def px(self, x, y, col):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.im.putpixel((x, y), col)

    def rect(self, x, y, w, h, col):
        for j in range(h):
            for i in range(w):
                self.px(x + i, y + j, col)

    def hline(self, x, y, w, col):
        self.rect(x, y, w, 1, col)

    def vline(self, x, y, h, col):
        self.rect(x, y, 1, h, col)

    def bevel(self, x, y, w, h, fill, light, dark):
        """A raised (light top-left) or sunken (dark top-left) box, 1 pixel edges."""
        self.rect(x, y, w, h, fill)
        self.hline(x, y, w - 1, light)
        self.vline(x, y, h - 1, light)
        self.hline(x + 1, y + h - 1, w - 1, dark)
        self.vline(x + w - 1, y + 1, h - 1, dark)

    def save(self, name):
        OUT_DIR.mkdir(parents=True, exist_ok=True)
        self.im.save(OUT_DIR / f"{name}.png")


def panel():
    """The frame: 176 x 220, red cap header with its spots, a tab strip, a recess for the grid, rivets."""
    w, h = 176, 220
    i = Img(w, h)
    i.rect(0, 0, w, h, OUTLINE)
    for (x, y) in ((0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)):
        i.px(x, y, CLEAR)  # rounded corners
    i.bevel(1, 1, w - 2, h - 2, S2, S4, S1)
    # Header: a Toad cap. Spots at both ends, the middle left clear for the title.
    i.rect(2, 2, w - 4, 12, RED)
    i.hline(2, 2, w - 4, RED_L)
    i.hline(2, 13, w - 4, RED_D)
    for cx in (9, 20, w - 10, w - 21):
        for dy, half in ((-2, 1), (-1, 2), (0, 2), (1, 2), (2, 1)):
            i.hline(cx - half, 8 + dy, 2 * half + 1, WHITE)
        i.px(cx - 1, 6, S5)
    # Tab strip.
    i.rect(2, 14, w - 4, 15, S1)
    i.hline(2, 28, w - 4, OUTLINE)
    # The recess behind the chest grid / the readout.
    i.bevel(6, 67, w - 12, 57, S0, OUTLINE, S3)
    # A groove above the inventory.
    i.hline(6, 128, w - 12, S1)
    i.hline(6, 129, w - 12, S3)
    # Rivets.
    for (x, y) in ((4, 31), (w - 6, 31), (4, h - 6), (w - 6, h - 6)):
        i.rect(x, y, 2, 2, S4)
        i.px(x + 1, y + 1, S1)
    i.save("panel")


def slot():
    i = Img(18, 18)
    i.bevel(0, 0, 18, 18, SLOT_IN, SLOT_DK, S3)
    i.save("slot")


def slot_locked():
    i = Img(18, 18)
    i.bevel(0, 0, 18, 18, S0, SLOT_DK, S1)
    for y in range(1, 17):
        for x in range(1, 17):
            if (x + y) % 4 == 0:
                i.px(x, y, S1)
    i.save("slot_locked")


def bay():
    """A bay: a slot with gold brackets in its corners, the mark of a place a chest goes."""
    i = Img(18, 18)
    i.bevel(0, 0, 18, 18, SLOT_IN, SLOT_DK, S3)
    for (x0, y0, dx, dy) in ((1, 1, 1, 1), (16, 1, -1, 1), (1, 16, 1, -1), (16, 16, -1, -1)):
        for k in range(3):
            i.px(x0 + dx * k, y0, GOLD_D)
            i.px(x0, y0 + dy * k, GOLD_D)
    i.save("bay")


def bay_selected():
    """The chest being looked at: a green ring around the bay (drawn 2 pixels outside it)."""
    i = Img(22, 22)
    i.hline(1, 0, 20, GREEN)
    i.hline(1, 21, 20, GREEN)
    i.vline(0, 1, 20, GREEN)
    i.vline(21, 1, 20, GREEN)
    for (x, y) in ((1, 1), (20, 1), (1, 20), (20, 20)):
        i.px(x, y, GREEN)
    i.save("bay_selected")


def link():
    """The bar that joins two bays into one large chest (20 wide: the gap between two bays)."""
    i = Img(20, 6)
    i.rect(0, 0, 20, 6, S2)
    i.hline(0, 0, 20, S5)
    i.hline(0, 1, 20, S4)
    i.hline(0, 4, 20, S1)
    i.hline(0, 5, 20, OUTLINE)
    i.rect(9, 2, 2, 2, GOLD)
    i.save("link")


def glyph(i, x, y, shape, col):
    """A bold + or -, two pixels thick, centred in the 10 x 10 button."""
    i.rect(x + 2, y + 4, 6, 2, col)
    if shape == "+":
        i.rect(x + 4, y + 2, 2, 6, col)


def small_button(name, shape, hover):
    i = Img(10, 10)
    i.bevel(0, 0, 10, 10, S3 if hover else S2, S5 if hover else S4, S1)
    glyph(i, 0, 0, shape, GOLD if hover else WHITE)
    i.save(name)


def plate(name, fill, light, dark, w=26, h=12, ring=None):
    i = Img(w, h)
    i.bevel(0, 0, w, h, fill, light, dark)
    if ring:
        i.hline(0, 0, w, ring)
        i.hline(0, h - 1, w, ring)
        i.vline(0, 0, h, ring)
        i.vline(w - 1, 0, h, ring)
    i.save(name)


def tab(name, active, hover=False):
    w, h = 78, 14
    i = Img(w, h)
    if active:
        i.bevel(0, 0, w, h, S3, S5, S1)
        i.rect(1, h - 2, w - 2, 2, S3)  # merges with the body below
        i.hline(3, 1, w - 6, GOLD)
    else:
        i.bevel(0, 1, w, h - 1, S1 if not hover else S2, S2 if not hover else S4, OUTLINE)
        i.hline(0, h - 1, w, OUTLINE)
    for x in (0, w - 1):
        i.px(x, 0 if active else 1, CLEAR)
    i.save(name)


def icon(name, rows):
    """16 x 16 ghost silhouette from a picture of '#' (and '.' empty), 16 chars a row."""
    assert len(rows) == 16 and all(len(r) == 16 for r in rows), name
    i = Img(16, 16)
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch == "#":
                i.px(x, y, GHOST)
    i.save(name)


ICONS = {
    # The Starshroom's cap and stem.
    "icon_hull": [
        "................",
        "................",
        ".....######.....",
        "...##########...",
        "..############..",
        ".##############.",
        ".##############.",
        "################",
        "################",
        "................",
        "......####......",
        ".....######.....",
        ".....######.....",
        ".....######.....",
        "......####......",
        "................",
    ],
    # A nozzle with its flame.
    "icon_engine": [
        "................",
        "......####......",
        ".....######.....",
        "....########....",
        "....########....",
        "....########....",
        ".....######.....",
        "................",
        ".....#.##.#.....",
        ".....#.##.#.....",
        "......######....",
        ".......####.....",
        ".......####.....",
        "........##......",
        "........##......",
        "................",
    ],
    # A rudder fin.
    "icon_rudder": [
        "................",
        "......##........",
        "......###.......",
        "......####......",
        "......#####.....",
        "......######....",
        "......#######...",
        "......########..",
        "......########..",
        "......#######...",
        "......######....",
        "......#####.....",
        "......####......",
        "......###.......",
        "......##........",
        "................",
    ],
    # A window of the cabin.
    "icon_cabin": [
        "................",
        "................",
        "....########....",
        "...##########...",
        "..############..",
        "..##........##..",
        "..#..........#..",
        "..#..........#..",
        "..#..........#..",
        "..#..........#..",
        "..#..........#..",
        "..############..",
        "..############..",
        "................",
        "................",
        "................",
    ],
    # A chest.
    "icon_chest": [
        "................",
        "................",
        "..############..",
        "..############..",
        "..#..........#..",
        "..#..........#..",
        "..############..",
        "..#....##....#..",
        "..#....##....#..",
        "..#..........#..",
        "..#..........#..",
        "..#..........#..",
        "..############..",
        "................",
        "................",
        "................",
    ],
}


def hud():
    """The flight panel: a small plate with the cap's red end, a gauge slot, and a larger one for the details."""
    # Compact: 92 x 22.
    w, h = 92, 22
    i = Img(w, h)
    i.rect(0, 0, w, h, OUTLINE)
    for (x, y) in ((0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)):
        i.px(x, y, CLEAR)
    i.bevel(1, 1, w - 2, h - 2, S1, S3, S0)
    i.rect(1, 1, 5, h - 2, RED)
    i.vline(1, 1, h - 2, RED_L)
    i.vline(5, 1, h - 2, RED_D)
    i.rect(2, 3, 2, 2, WHITE)
    i.rect(2, 9, 2, 2, WHITE)
    i.rect(2, 15, 2, 2, WHITE)
    i.bevel(9, 13, w - 14, 6, S0, OUTLINE, S3)  # the gauge slot (inside: x 10..85, y 14..17)
    i.save("hud_plate")
    # Detail: 100 x 68.
    w, h = 100, 68
    i = Img(w, h)
    i.rect(0, 0, w, h, OUTLINE)
    for (x, y) in ((0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)):
        i.px(x, y, CLEAR)
    i.bevel(1, 1, w - 2, h - 2, S1, S3, S0)
    i.rect(1, 1, 5, h - 2, RED)
    i.vline(1, 1, h - 2, RED_L)
    i.vline(5, 1, h - 2, RED_D)
    for y in range(3, h - 4, 6):
        i.rect(2, y, 2, 2, WHITE)
    i.save("hud_detail")
    for name, col, edge in (("hud_pip_on", RED, RED_L), ("hud_pip_off", S0, OUTLINE)):
        p = Img(6, 6)
        p.rect(0, 0, 6, 6, col)
        p.hline(0, 0, 6, edge)
        p.save(name)


def main():
    panel()
    slot()
    slot_locked()
    bay()
    bay_selected()
    link()
    small_button("btn_join", "+", False)
    small_button("btn_join_hover", "+", True)
    small_button("btn_split", "-", False)
    small_button("btn_split_hover", "-", True)
    plate("plate", S2, S4, S1)
    plate("plate_hover", S3, S5, S1)
    plate("plate_active", RED_D, RED, OUTLINE, ring=GOLD_D)
    plate("page", S2, S4, S1, h=10)
    plate("page_hover", S3, S5, S1, h=10)
    tab("tab", False)
    tab("tab_hover", False, True)
    tab("tab_active", True)
    for name, rows in ICONS.items():
        icon(name, rows)
    hud()
    print("wrote", len(list(OUT_DIR.glob("*.png"))), "sprites to", OUT_DIR)


if __name__ == "__main__":
    main()
