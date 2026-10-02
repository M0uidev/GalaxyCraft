import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import fake_galaxy  # noqa: E402
import kcl  # noqa: E402


class KclTest(unittest.TestCase):
    def assertTriAlmostEqual(self, got, want):
        for a, b in zip(got, want):
            for x, y in zip(a, b):
                self.assertAlmostEqual(x, y, places=2)

    def test_roundtrip_triangle(self):
        tri = ((0, 0, 0), (100, 0, 0), (0, 0, -100))
        out = kcl.read(kcl.write([tri]))
        self.assertEqual(len(out), 1)
        self.assertTriAlmostEqual(out[0], tri)

    def test_roundtrip_skewed_triangle(self):
        tri = ((10, 5, -3), (130, 40, 20), (-20, 80, -150))
        self.assertTriAlmostEqual(kcl.read(kcl.write([tri]))[0], tri)

    def test_roundtrip_icosphere(self):
        tris = fake_galaxy.icosphere(800, 2)
        out = kcl.read(kcl.write(tris))
        self.assertEqual(len(out), len(tris))
        for got, want in zip(out, tris):
            self.assertTriAlmostEqual(got, want)

    def test_degenerate_dropped(self):
        self.assertEqual(kcl.write([((0, 0, 0), (1, 0, 0), (2, 0, 0))]), kcl.write([]))

    def test_header_is_big_endian(self):
        data = kcl.write([((0, 0, 0), (100, 0, 0), (0, 0, -100))])
        self.assertEqual(data[0:4], (0x38).to_bytes(4, "big"))  # positions start right after header


if __name__ == "__main__":
    unittest.main()
