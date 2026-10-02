import math
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import fake_galaxy  # noqa: E402
import gxproto  # noqa: E402
from test_gxproto import tmp_path  # noqa: E402


class FakeGalaxyTest(unittest.TestCase):
    def test_gravity_points_to_center(self):
        g = fake_galaxy.gravity_at((0, 1000, 0), [((0, 0, 0), 800)])
        self.assertAlmostEqual(g[1], -1.0)

    def test_gravity_picks_nearest_surface(self):
        planets = [((0, 0, 0), 800), ((0, 2600, 0), 600)]
        g = fake_galaxy.gravity_at((0, 1900, 0), planets)  # 1100 above A, 100 below B's surface
        self.assertAlmostEqual(g[1], 1.0)

    def test_icosphere_vertices_on_radius(self):
        for tri in fake_galaxy.icosphere(800, 2):
            for v in tri:
                self.assertAlmostEqual(math.dist(v, (0, 0, 0)), 800, places=3)

    def test_icosphere_faces_outward(self):
        for a, b, c in fake_galaxy.icosphere(800, 1):
            n = fake_galaxy.cross(fake_galaxy.sub(b, a), fake_galaxy.sub(c, a))
            self.assertGreater(fake_galaxy.dot(n, a), 0)

    def test_publish_sends_scene_parts_and_chunks(self):
        path = tmp_path()
        shm = gxproto.Shm(path=path, create=True)
        try:
            fake_galaxy.publish_scene(shm, fake_galaxy.PLANETS)
            ring = gxproto.Ring(shm, gxproto.RING_S2M_OFF)
            msgs = []
            while (m := ring.pop()) is not None:
                msgs.append(m)
            self.assertEqual(msgs[0][0], gxproto.MSG_SCENE_CHANGE)
            ups = [m for m in msgs if m[0] == gxproto.MSG_PART_UPSERT]
            self.assertEqual(len(ups), 2)
            part_id, size = gxproto.PART_UPSERT.unpack(ups[0][1])[:2]
            got = sum(len(p) - gxproto.KCL_CHUNK.size for t, p in msgs
                      if t == gxproto.MSG_KCL_CHUNK and gxproto.KCL_CHUNK.unpack_from(p)[0] == part_id)
            self.assertEqual(got, size)
        finally:
            shm.close()
            os.unlink(path)


if __name__ == "__main__":
    unittest.main()
