import os
import sys
import unittest
import xml.etree.ElementTree as ET

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "steve"))
import steve_model  # noqa: E402

# Mario's bind pose (world positions of SuperBMD's export of Mario.bdl), flattened under All_Root.
JOINTS = [("All_Root", (0, 0, 0)), ("LegL1", (11.5, 50, -1.7)), ("LegL2", (11.5, 30.3, -1.7)),
          ("LegR1", (-11.5, 50, -1.7)), ("LegR2", (-11.5, 30.3, -1.7)), ("Spine2", (0, 72, -4)),
          ("Head", (0, 96, -4)), ("ArmL1", (17, 80, -3)), ("ArmL2", (32.6, 80, -3)), ("HandL", (50.6, 80, -3)),
          ("ArmR1", (-17, 80, -3)), ("ArmR2", (-32.6, 80, -3)), ("HandR", (-50.6, 80, -3))]


def skeleton_dae():
    nodes = "".join(
        f'<node id="{n}" name="{n}" sid="{n}" type="JOINT"><matrix sid="matrix">1 0 0 {x} 0 1 0 {y} 0 0 1 {z} 0 0 0 1</matrix></node>\n'
        for n, (x, y, z) in JOINTS[1:])
    return f'''<COLLADA xmlns="http://www.collada.org/2005/11/COLLADASchema"><library_visual_scenes><visual_scene id="s">
      <node id="skeleton_root" name="skeleton_root">
        <matrix sid="matrix">1 0 0 0 0 1 0 0 0 0 1 0 0 0 0 1</matrix>
        <node id="All_Root" name="All_Root" sid="All_Root" type="JOINT"><matrix sid="matrix">1 0 0 0 0 1 0 0 0 0 1 0 0 0 0 1</matrix>
{nodes}</node>
      </node>
</visual_scene></library_visual_scenes></COLLADA>'''


class SteveModelTest(unittest.TestCase):
    def setUp(self):
        self.sk = steve_model.Skeleton(skeleton_dae())

    def test_skeleton_keeps_joint_order(self):
        self.assertEqual(self.sk.joints, [n for n, _ in JOINTS])
        np.testing.assert_allclose(self.sk.pos("Head"), (0, 96, -4))

    def test_steve_boxes(self):
        m = steve_model.steve(self.sk)
        self.assertEqual(len(m.verts) // 3, 104)  # body and head 6 faces, each limb half 5
        uv = np.array(m.uvs)
        self.assertTrue(((uv >= 0) & (uv <= 1)).all())
        v = np.array(m.verts)
        px = 50 / 12
        self.assertAlmostEqual(v[:, 1].min(), 0)                  # feet on the ground
        self.assertAlmostEqual(v[:, 1].max(), 96 + 8 * px)        # top of the head
        self.assertAlmostEqual(v[:, 0].max(), 17 + 10 * px)       # left hand, T pose along +x
        self.assertAlmostEqual(v[:, 0].min(), -17 - 10 * px)
        head = self.sk.joints.index("Head")
        self.assertTrue(all(v[i, 1] >= 96 for i, j in enumerate(m.joint_of) if j == head))

    def test_collada_binds_every_vertex(self):
        m = steve_model.steve(self.sk)
        root = ET.fromstring(steve_model.collada(m))
        ns = {"c": "http://www.collada.org/2005/11/COLLADASchema"}
        names = root.find(".//c:Name_array", ns).text.split()
        self.assertEqual(names, self.sk.joints)
        v = [int(x) for x in root.find(".//c:vertex_weights/c:v", ns).text.split()]
        self.assertEqual(len(v), 2 * len(m.verts))
        self.assertTrue(all(0 <= j < len(names) for j in v[0::2]))

    def test_held_block_at_the_end_of_the_right_arm(self):
        m = steve_model.held_transforms(self.sk)
        px = 50 / 12
        world = self.sk.world[steve_model.HELD_JOINT]
        # The block's center: at the arm's end (T pose, along -x), 2 + 2.5 pixels in front of it.
        center = (world @ m["block"] @ np.array([8, 8, 8, 1.0]))[:3]
        np.testing.assert_allclose(center, (-17 - 10 * px, 80, -3 + 4.5 * px), atol=1e-6)
        for kind, k in (("block", 0.375), ("item", 0.55), ("tool", 0.85)):
            a = m[kind][:3, :3]  # rigid, scaled from texels to a Minecraft model pixel's size
            np.testing.assert_allclose(a.T @ a, np.eye(3) * (k * px) ** 2, atol=1e-9)
            self.assertGreater(np.linalg.det(a), 0)
        # The tool's blade lies in the plane of the arm and of where Steve faces.
        normal = world[:3, :3] @ m["tool"][:3, :3] @ np.array([0, 0, 1.0])
        np.testing.assert_allclose(normal / np.linalg.norm(normal), (0, -1, 0), atol=1e-9)

    def test_held_header(self):
        h = steve_model.held_header(self.sk)
        self.assertIn('#define GXC_HELD_JOINT "ArmR2"', h)
        self.assertEqual(h.count("f, "), 4 * 11)

    def test_empty_draws_nothing(self):
        m = steve_model.empty(self.sk)
        self.assertEqual(len(m.verts), 3)
        self.assertTrue((np.array(m.verts) == 0).all())


if __name__ == "__main__":
    unittest.main()
