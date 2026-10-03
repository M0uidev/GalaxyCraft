import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from PIL import Image  # noqa: E402

import voxel_atlas  # noqa: E402


class AtlasTest(unittest.TestCase):
    def test_rgb565_blocks_are_4x4_row_major(self):
        img = Image.new("RGB", (8, 4))
        img.putpixel((1, 0), (255, 0, 0))   # block 0, texel 1
        img.putpixel((4, 0), (0, 0, 255))   # block 1, texel 0
        data = voxel_atlas.rgb565_tiled(img)
        self.assertEqual(len(data), 8 * 4 * 2)
        self.assertEqual(data[2:4], bytes([0xF8, 0x00]))
        self.assertEqual(data[32:34], bytes([0x00, 0x1F]))

    def test_tiles_go_in_material_order(self):
        tiles = [Image.new("RGBA", (16, 16), (n * 40, 0, 0, 255)) for n in range(5)]
        img = voxel_atlas.atlas(tiles)
        self.assertEqual(img.getpixel((16 * 4 % 64, 16 * (4 // 4)))[0], 160)  # bedrock: tile 4 -> (0, 16)
        self.assertEqual(img.getpixel((17, 0))[0], 40)

    def test_tint_multiplies(self):
        t = voxel_atlas.tinted(Image.new("RGBA", (1, 1), (255, 128, 0, 255)), (0x91, 0xBD, 0x59))
        self.assertEqual(t.getpixel((0, 0)), (0x91, 0xBD * 128 // 255, 0, 255))


if __name__ == "__main__":
    unittest.main()
