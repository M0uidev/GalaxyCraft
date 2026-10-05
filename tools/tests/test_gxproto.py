import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import gxproto  # noqa: E402


def tmp_path():
    fd, path = tempfile.mkstemp(prefix="gxc_test_")
    os.close(fd)
    os.unlink(path)
    return path


class LayoutTest(unittest.TestCase):
    def test_offsets_match_header(self):
        self.assertEqual(gxproto.OFF_WORLD, 64)
        self.assertEqual(gxproto.OFF_PLAYER, 128)
        self.assertEqual(gxproto.OFF_INPUT, 224)
        self.assertEqual(gxproto.RING_S2M_OFF, 4096)
        self.assertEqual(gxproto.RING_M2S_OFF, 4198416)
        self.assertEqual(gxproto.OFF_OVERLAY, 5251072)
        self.assertEqual(gxproto.TOTAL_SIZE, 30134304)

    def test_v2_follow(self):
        self.assertEqual(gxproto.VERSION, 10)
        self.assertEqual(gxproto.OFF_GAMECAM, 320)
        self.assertEqual(gxproto.WORLD_FOLLOW, 2)


class RingTest(unittest.TestCase):
    def setUp(self):
        self.path = tmp_path()
        self.shm = gxproto.Shm(path=self.path, create=True)
        self.ring = gxproto.Ring(self.shm, gxproto.RING_S2M_OFF)

    def tearDown(self):
        self.shm.close()
        os.unlink(self.path)

    def test_roundtrip(self):
        self.assertTrue(self.ring.push(4, b"abc"))
        self.assertEqual(self.ring.pop(), (4, b"abc"))
        self.assertIsNone(self.ring.pop())

    def test_wraparound(self):
        payload = bytes(range(256)) * 200  # 51200 bytes
        for _ in range(200):  # more than the capacity in total: must wrap
            self.assertTrue(self.ring.push(4, payload))
            self.assertEqual(self.ring.pop(), (4, payload))

    def test_full_returns_false(self):
        big = b"x" * gxproto.KCL_CHUNK_MAX
        n = 0
        while self.ring.push(4, big):
            n += 1
        self.assertEqual(n, gxproto.RING_S2M_CAP // (gxproto.KCL_CHUNK_MAX + 8))

    def test_reopen_sees_messages(self):
        self.ring.push(1, b"\x01\x00\x00\x00")
        other = gxproto.Shm(path=self.path)
        self.assertEqual(gxproto.Ring(other, gxproto.RING_S2M_OFF).pop(), (1, b"\x01\x00\x00\x00"))
        other.close()


class SeqlockTest(unittest.TestCase):
    def setUp(self):
        self.path = tmp_path()
        self.shm = gxproto.Shm(path=self.path, create=True)

    def tearDown(self):
        self.shm.close()
        os.unlink(self.path)

    def test_player_roundtrip(self):
        gxproto.write_player(self.shm, frame_id=7, pos=(1, 2, 3), look=(0, 0, -1), up=(0, 1, 0),
                             fov_y=70, eye=1.62, on_ground=True)
        p = gxproto.read_player(self.shm)
        self.assertEqual(p.frame_id, 7)
        self.assertEqual(p.pos, (1.0, 2.0, 3.0))
        self.assertTrue(p.on_ground)

    def test_player_camera_fields(self):
        gxproto.write_player(self.shm, frame_id=1, pos=(0, 0, 0), look=(0, 0, 1), up=(0, 1, 0), fov_y=70,
                             eye=1, on_ground=False, cam_offset=(0, 130, -320), view=gxproto.VIEW_BACK)
        p = gxproto.read_player(self.shm)
        self.assertEqual((p.cam_offset, p.view), ((0.0, 130.0, -320.0), gxproto.VIEW_BACK))

    def test_game_camera_roundtrip(self):
        self.assertIsNone(gxproto.read_game_camera(self.shm))
        gxproto.write_game_camera(self.shm, gxproto.GAMECAM_VALID, 5, (1, 2, 3), (0, 0, -1), (0, 1, 0), 45,
                                  (4, 5, 6), (1, 0, 0))
        c = gxproto.read_game_camera(self.shm)
        self.assertEqual((c.flags, c.frame_id, c.cam_pos, c.fov_y, c.mario_front),
                         (gxproto.GAMECAM_VALID, 5, (1.0, 2.0, 3.0), 45.0, (1.0, 0.0, 0.0)))

    def test_player_absent_before_first_write(self):
        self.assertIsNone(gxproto.read_player(self.shm))

    def test_world_roundtrip(self):
        gxproto.write_world(self.shm, scene_id=3, frame_id=9, gravity=(0, -1, 0), query_pos=(1, 2, 3))
        w = gxproto.read_world(self.shm)
        self.assertEqual((w.scene_id, w.frame_id, w.gravity), (3, 9, (0.0, -1.0, 0.0)))

    def test_create_writes_magic_and_heartbeat(self):
        gxproto.init_host(self.shm)
        h = gxproto.read_header(self.shm)
        self.assertEqual(h.magic, gxproto.MAGIC)
        self.assertEqual(h.version, 10)
        self.assertEqual(h.host_pid, os.getpid())
        self.assertGreater(h.host_heartbeat_ms, 0)


if __name__ == "__main__":
    unittest.main()


class CreateTest(unittest.TestCase):
    def test_create_clears_stale_slots(self):
        path = tmp_path()
        shm = gxproto.Shm(path=path, create=True)
        gxproto.write_player(shm, frame_id=3, pos=(1, 2, 3), look=(0, 0, 1), up=(0, 1, 0), fov_y=70, eye=1, on_ground=False)
        shm.close()
        shm = gxproto.Shm(path=path, create=True)
        try:
            self.assertIsNone(gxproto.read_player(shm))
            self.assertIsNone(gxproto.read_world(shm))
        finally:
            shm.close()
            os.unlink(path)
