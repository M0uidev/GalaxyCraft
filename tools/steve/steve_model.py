"""Steve for SMG2: Minecraft-skin boxes skinned rigidly onto Mario's skeleton, as COLLADA for
SuperBMD. The skeleton comes from SuperBMD's own export of Mario.bdl, so joint names and order
(what Mario's animations address) are unchanged.
"""
import re
import xml.etree.ElementTree as ET

import numpy as np

_NS = {"c": "http://www.collada.org/2005/11/COLLADASchema"}
SKIN_SIZE = 64.0


class Skeleton:
    def __init__(self, dae_text):
        root = ET.fromstring(dae_text)
        self.joints, self.world = [], {}

        def walk(node, parent):
            for child in node.findall("c:node", _NS):
                m = child.find("c:matrix", _NS)
                local = np.array([float(x) for x in m.text.split()]).reshape(4, 4) if m is not None else np.eye(4)
                w = parent @ local
                if child.get("type") == "JOINT":
                    self.joints.append(child.get("name"))
                    self.world[child.get("name")] = w
                walk(child, w)

        for scene in root.findall(".//c:visual_scene", _NS):
            walk(scene, np.eye(4))
        self.xml = re.search(r'( *)<node id="skeleton_root".*?\n\1</node>\n', dae_text, re.S).group(0)

    def pos(self, joint):
        return self.world[joint][:3, 3]

    def inverse_binds(self):
        # Mario has helper joints with zero scale; nothing is bound to them.
        return [np.linalg.inv(w) if abs(np.linalg.det(w)) > 1e-9 else np.eye(4) for w in (self.world[j] for j in self.joints)]


class Mesh:
    def __init__(self, skeleton):
        self.skeleton = skeleton
        self.verts, self.norms, self.uvs, self.joint_of = [], [], [], []

    def box(self, joint, lo, hi, uv, size, rows=(0.0, 1.0), turn=None):
        """Box lo..hi (character frame: y up, facing +z) textured as a Minecraft model box at uv
        with size (w, h, d) skin pixels. rows: the slice of the sides' height it shows (0 = top),
        for limbs split at the knee or elbow. turn: (3x3 rotation, pivot) applied to the box."""
        (u, v), (w, h, d) = uv, size
        lo, hi = np.array(lo, float), np.array(hi, float)
        r0, r1 = rows
        side_v, side_h = v + d + h * r0, h * (r1 - r0)
        faces = [  # normal, uv rect (u, v, w, h), corners bl br tr tl seen from outside
            ((0, 0, 1), (u + d, side_v, w, side_h), [(0, 0, 1), (1, 0, 1), (1, 1, 1), (0, 1, 1)]),
            ((0, 0, -1), (u + 2 * d + w, side_v, w, side_h), [(1, 0, 0), (0, 0, 0), (0, 1, 0), (1, 1, 0)]),
            ((-1, 0, 0), (u + d + w, side_v, d, side_h), [(0, 0, 0), (0, 0, 1), (0, 1, 1), (0, 1, 0)]),
            ((1, 0, 0), (u, side_v, d, side_h), [(1, 0, 1), (1, 0, 0), (1, 1, 0), (1, 1, 1)]),
        ]
        if r0 == 0:
            faces.append(((0, 1, 0), (u + d, v, w, d), [(0, 1, 1), (1, 1, 1), (1, 1, 0), (0, 1, 0)]))
        if r1 == 1:
            faces.append(((0, -1, 0), (u + d + w, v, w, d), [(0, 0, 0), (1, 0, 0), (1, 0, 1), (0, 0, 1)]))
        rot, pivot = (np.eye(3), np.zeros(3)) if turn is None else (turn[0], np.array(turn[1], float))
        j = self.skeleton.joints.index(joint)
        for normal, (ru, rv, rw, rh), corners in faces:
            pts = [rot @ (lo + (hi - lo) * np.array(c) - pivot) + pivot for c in corners]
            tex = [(ru, rv + rh), (ru + rw, rv + rh), (ru + rw, rv), (ru, rv)]
            for k in (0, 1, 2, 0, 2, 3):
                self.verts.append(pts[k])
                self.norms.append(rot @ np.array(normal, float))
                self.uvs.append((tex[k][0] / SKIN_SIZE, 1 - tex[k][1] / SKIN_SIZE))
                self.joint_of.append(j)

    def degenerate(self, joint):
        """One zero-area triangle: a model that draws nothing."""
        j = self.skeleton.joints.index(joint)
        for _ in range(3):
            self.verts.append(np.zeros(3))
            self.norms.append(np.array([0.0, 1.0, 0.0]))
            self.uvs.append((0.0, 0.0))
            self.joint_of.append(j)


def steve(skeleton):
    """Steve on Mario's joints. Legs reach Mario's hips; boxes keep Minecraft's widths."""
    sk, m = skeleton, Mesh(skeleton)
    hip_y, knee_y = sk.pos("LegL1")[1], sk.pos("LegL2")[1]
    head = sk.pos("Head")
    px = hip_y / 12  # one skin pixel, in model units
    z = head[2]
    m.box("Spine2", (-4 * px, hip_y, z - 2 * px), (4 * px, head[1], z + 2 * px), (16, 16), (8, 12, 4))
    m.box("Head", (-4 * px, head[1], z - 4 * px), (4 * px, head[1] + 8 * px, z + 4 * px), (0, 0), (8, 8, 8))
    knee = (hip_y - knee_y) / hip_y
    # Mario's left is +x (he faces +z); Minecraft's left limbs use the 64x64 skin's lower half.
    for side, sx, leg_uv, arm_uv in (("L", 1, (16, 48), (32, 48)), ("R", -1, (0, 16), (40, 16))):
        x0, x1 = sorted((0.0, sx * 4 * px))
        m.box("Leg%s1" % side, (x0, knee_y, z - 2 * px), (x1, hip_y, z + 2 * px), leg_uv, (4, 12, 4), (0, knee))
        m.box("Leg%s2" % side, (x0, 0, z - 2 * px), (x1, knee_y, z + 2 * px), leg_uv, (4, 12, 4), (knee, 1))
        # Arms hang from the shoulder joint (Minecraft's arm: 12 px, pivot 2 px below its top),
        # then turn out to Mario's T pose; the forearm starts at Mario's elbow.
        pivot = sk.pos("Arm%s1" % side)
        upper = abs(sk.pos("Arm%s2" % side)[0] - pivot[0])
        top = pivot[1] + 2 * px
        elbow_y = pivot[1] - upper
        elbow = (top - elbow_y) / (12 * px)
        angle = sx * np.pi / 2
        turn = (np.array([[np.cos(angle), -np.sin(angle), 0], [np.sin(angle), np.cos(angle), 0], [0, 0, 1]]), pivot)
        ax, az = pivot[0], pivot[2]
        m.box("Arm%s1" % side, (ax - 2 * px, elbow_y, az - 2 * px), (ax + 2 * px, top, az + 2 * px), arm_uv, (4, 12, 4),
              (0, elbow), turn)
        m.box("Arm%s2" % side, (ax - 2 * px, top - 12 * px, az - 2 * px), (ax + 2 * px, elbow_y, az + 2 * px), arm_uv,
              (4, 12, 4), (elbow, 1), turn)
    return m


def empty(skeleton):
    m = Mesh(skeleton)
    m.degenerate(skeleton.joints[0])
    return m


def collada(mesh, texture="steve.png", material="steve"):
    sk = mesh.skeleton
    n = len(mesh.verts)
    f = lambda a: " ".join("%g" % x for x in np.ravel(a))
    acc = lambda src, count, names: (f'<technique_common><accessor source="#{src}" count="{count}" stride="{len(names)}">'
                                     + "".join(f'<param name="{p}" type="float"/>' for p in names)
                                     + "</accessor></technique_common>")
    return f'''<?xml version="1.0"?>
<COLLADA xmlns="http://www.collada.org/2005/11/COLLADASchema" version="1.4.1">
  <asset><unit name="meter" meter="1" /><up_axis>Y_UP</up_axis></asset>
  <library_images><image id="skin"><init_from>{texture}</init_from></image></library_images>
  <library_effects><effect id="fx" name="{material}"><profile_COMMON>
    <newparam sid="surface"><surface type="2D"><init_from>skin</init_from></surface></newparam>
    <newparam sid="sampler"><sampler2D><source>surface</source></sampler2D></newparam>
    <technique sid="standard"><phong><diffuse><texture texture="sampler" texcoord="CHANNEL0" /></diffuse></phong></technique>
  </profile_COMMON></effect></library_effects>
  <library_materials><material id="{material}" name="{material}"><instance_effect url="#fx" /></material></library_materials>
  <library_geometries><geometry id="mesh" name="mesh"><mesh>
    <source id="p"><float_array id="pa" count="{3 * n}">{f(mesh.verts)}</float_array>{acc("pa", n, "XYZ")}</source>
    <source id="nm"><float_array id="na" count="{3 * n}">{f(mesh.norms)}</float_array>{acc("na", n, "XYZ")}</source>
    <source id="t"><float_array id="ta" count="{2 * n}">{f(mesh.uvs)}</float_array>{acc("ta", n, "ST")}</source>
    <vertices id="v"><input semantic="POSITION" source="#p" /><input semantic="NORMAL" source="#nm" /><input semantic="TEXCOORD" source="#t" /></vertices>
    <triangles count="{n // 3}" material="mat"><input offset="0" semantic="VERTEX" source="#v" /><p>{" ".join(map(str, range(n)))}</p></triangles>
  </mesh></geometry></library_geometries>
  <library_controllers><controller id="skin-ctrl"><skin source="#mesh">
    <bind_shape_matrix>1 0 0 0 0 1 0 0 0 0 1 0 0 0 0 1</bind_shape_matrix>
    <source id="sj"><Name_array id="sja" count="{len(sk.joints)}">{" ".join(sk.joints)}</Name_array><technique_common><accessor source="#sja" count="{len(sk.joints)}" stride="1"><param name="JOINT" type="name"/></accessor></technique_common></source>
    <source id="sb"><float_array id="sba" count="{16 * len(sk.joints)}">{f(sk.inverse_binds())}</float_array><technique_common><accessor source="#sba" count="{len(sk.joints)}" stride="16"><param name="TRANSFORM" type="float4x4"/></accessor></technique_common></source>
    <source id="sw"><float_array id="swa" count="1">1</float_array><technique_common><accessor source="#swa" count="1" stride="1"><param name="WEIGHT" type="float"/></accessor></technique_common></source>
    <joints><input semantic="JOINT" source="#sj" /><input semantic="INV_BIND_MATRIX" source="#sb" /></joints>
    <vertex_weights count="{n}"><input semantic="JOINT" source="#sj" offset="0" /><input semantic="WEIGHT" source="#sw" offset="1" />
      <vcount>{" ".join(["1"] * n)}</vcount><v>{" ".join("%d 0" % j for j in mesh.joint_of)}</v></vertex_weights>
  </skin></controller></library_controllers>
  <library_visual_scenes><visual_scene id="scene" name="scene">
{sk.xml}      <node id="model" name="model"><instance_controller url="#skin-ctrl"><skeleton>#skeleton_root</skeleton>
        <bind_material><technique_common><instance_material symbol="mat" target="#{material}" /></technique_common></bind_material>
      </instance_controller></node>
  </visual_scene></library_visual_scenes>
  <scene><instance_visual_scene url="#scene" /></scene>
</COLLADA>
'''
