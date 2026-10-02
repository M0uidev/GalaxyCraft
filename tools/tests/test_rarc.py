import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import rarc  # noqa: E402


class RarcTest(unittest.TestCase):
    FILES = [("Mario.bdl", b"J3D2bdl4" + bytes(range(50))), ("FooLine.bti", b"\x01\x02\x03")]

    def test_build_then_list(self):
        self.assertEqual(rarc.list_files(rarc.build("mario", self.FILES)), self.FILES)

    def test_replace_keeps_the_others(self):
        arc = rarc.build("mario", self.FILES)
        new = rarc.replace(arc, {"Mario.bdl": b"steve" * 9})
        self.assertEqual(rarc.list_files(new), [("Mario.bdl", b"steve" * 9), ("FooLine.bti", b"\x01\x02\x03")])

    def test_replace_unknown_name_fails(self):
        with self.assertRaises(KeyError):
            rarc.replace(rarc.build("mario", self.FILES), {"Luigi.bdl": b""})

    def test_yaz0(self):
        # Header, then: 8 literals (code 0xFF), then a back-reference of 6 bytes, distance 4.
        data = b"Yaz0" + (14).to_bytes(4, "big") + bytes(8) + b"\xff" + b"abcdefgh" + b"\x00" + b"\x40\x03"
        self.assertEqual(rarc.yaz0_decompress(data), b"abcdefghefghef")
        self.assertEqual(rarc.yaz0_decompress(b"RARC1234"), b"RARC1234")


if __name__ == "__main__":
    unittest.main()
