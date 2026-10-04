package dev.moui.galaxycraft.kcl;

import dev.moui.galaxycraft.geom.Tri;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3d;

/**
 * Writes triangles (counter-clockwise seen from the solid side's outside) as KCL that SMG2 can
 * collide with: same layout as {@link KclParser} reads, prism thickness 40 like the game's, and an
 * octree over one root cube. The game walks it from the root (searchBlock): a node with the top bit
 * set is a leaf whose prism list starts 2 bytes after the offset it stores, any other is the
 * offset of its 8 children; both offsets count from the start of the block of nodes it is in.
 * A collision test then reads only the prisms of the cubes it touches, not all of the KCL's.
 * Started as a port of tools/kcl.py (whose octree is one leaf).
 */
public final class KclWriter {
    /** A cube listing at most this many prisms is not split. */
    static final int LEAF_PRISMS = 12;
    /** Cubes are not split below this side (units; a block is 80). */
    static final int MIN_CUBE = 64;
    /**
     * A prism is listed in every cube it comes this close to (units): its thickness behind the face
     * and a quarter block more, so a test near a cube's side finds what is just across it.
     */
    static final double MARGIN = 40 + 20;

    private KclWriter() {}

    public static byte[] write(List<Tri> tris) {
        List<Vector3d> positions = new ArrayList<>(), normals = new ArrayList<>();
        List<float[]> prisms = new ArrayList<>(); // height, pos, face, e1, e2, e3
        List<Vector3d[]> corners = new ArrayList<>();
        Vector3d min = new Vector3d(Double.MAX_VALUE), max = new Vector3d(-Double.MAX_VALUE);
        for (Tri t : tris) {
            Vector3d v1 = t.a(), v2 = t.b(), v3 = t.c();
            Vector3d f = new Vector3d(v2).sub(v1).cross(new Vector3d(v3).sub(v1));
            if (f.lengthSquared() < 1e-12) continue;
            f.normalize();
            Vector3d ea = new Vector3d(f).cross(new Vector3d(v3).sub(v1)).normalize();
            Vector3d eb = new Vector3d(v2).sub(v1).cross(f).normalize();
            Vector3d ec = new Vector3d(f).cross(new Vector3d(v2).sub(v3)).normalize();
            double height = new Vector3d(v2).sub(v1).dot(ec);
            int base = normals.size();
            normals.add(f);
            normals.add(ea);
            normals.add(eb);
            normals.add(ec);
            prisms.add(new float[] {(float) height, positions.size(), base, base + 1, base + 2, base + 3});
            positions.add(v1);
            corners.add(new Vector3d[] {v1, v2, v3});
            for (Vector3d v : new Vector3d[] {v1, v2, v3}) {
                min.min(v);
                max.max(v);
            }
        }
        if (prisms.size() > 0xFFFE) throw new IllegalArgumentException("too many triangles for one KCL");
        if (prisms.isEmpty()) {
            min.set(0);
            max.set(0);
        }
        min.sub(1, 1, 1);
        double extent = Math.max(max.x - min.x, Math.max(max.y - min.y, max.z - min.z)) + 1;
        int shift = Math.max(0, (int) Math.ceil(Math.log(extent) / Math.log(2)));
        int mask = ~((1 << shift) - 1);

        // The octree, as blocks of 8 nodes after the root's one; then the prism lists.
        List<int[]> blocks = new ArrayList<>(); // per block: 8 nodes, a child block's index or ~list index
        List<List<Integer>> lists = new ArrayList<>();
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < prisms.size(); i++) all.add(i);
        int root = node(corners, all, new Vector3d(min), 1 << shift, blocks, lists);
        int posOff = 0x38, nrmOff = posOff + 12 * positions.size(), prismData = nrmOff + 12 * normals.size();
        int octOff = prismData + 0x10 * prisms.size();
        int listsOff = octOff + 4 + 32 * blocks.size();
        int[] listAt = new int[lists.size()];
        int size = listsOff;
        for (int i = 0; i < lists.size(); i++) {
            listAt[i] = size;
            size += 2 * (lists.get(i).size() + 2);
        }
        ByteBuffer b = ByteBuffer.allocate((size + 3) & ~3).order(ByteOrder.BIG_ENDIAN);
        b.putInt(posOff).putInt(nrmOff).putInt(prismData - 0x10).putInt(octOff).putFloat(40f);
        b.putFloat((float) min.x).putFloat((float) min.y).putFloat((float) min.z);
        b.putInt(mask).putInt(mask).putInt(mask).putInt(shift).putInt(0).putInt(0);
        for (Vector3d v : positions) putVec(b, v);
        for (Vector3d v : normals) putVec(b, v);
        for (float[] p : prisms) {
            b.putFloat(p[0]);
            for (int k = 1; k < 6; k++) b.putShort((short) p[k]);
            b.putShort((short) 0); // attribute: entry 0 of the .pa
        }
        b.putInt(nodeValue(root, octOff, octOff, listAt));
        for (int i = 0; i < blocks.size(); i++) {
            int at = octOff + 4 + 32 * i;
            for (int child : blocks.get(i)) b.putInt(nodeValue(child, at, octOff, listAt));
        }
        for (List<Integer> list : lists) {
            b.putShort((short) 0); // the 2 bytes the game skips
            for (int k : list) b.putShort((short) (k + 1));
            b.putShort((short) 0);
        }
        return b.array();
    }

    /** A node's word in the block starting at blockAt: a child block's offset, or a leaf's. */
    private static int nodeValue(int node, int blockAt, int octOff, int[] listAt) {
        if (node < 0) return 0x80000000 | (listAt[~node] - blockAt);
        return octOff + 4 + 32 * node - blockAt;
    }

    /** The node of a cube (corner o, side size) holding these prisms: a block's index, or ~ a list's. */
    private static int node(List<Vector3d[]> corners, List<Integer> in, Vector3d o, int size, List<int[]> blocks,
            List<List<Integer>> lists) {
        List<Integer> here = new ArrayList<>();
        for (int i : in) if (touches(corners.get(i), o, size)) here.add(i);
        if (here.size() <= LEAF_PRISMS || size <= MIN_CUBE) {
            lists.add(here);
            return ~(lists.size() - 1);
        }
        int index = blocks.size();
        int[] children = new int[8];
        blocks.add(children);
        int half = size / 2;
        // Child order as the game picks it: x in bit 0, y in bit 1, z in bit 2.
        for (int c = 0; c < 8; c++)
            children[c] = node(corners, here, new Vector3d(o).add((c & 1) * half, (c >> 1 & 1) * half, (c >> 2 & 1) * half),
                    half, blocks, lists);
        return index;
    }

    /** Whether a triangle comes within MARGIN of a cube (conservative: its box, and its plane). */
    static boolean touches(Vector3d[] t, Vector3d o, double size) {
        double[] lo = {o.x - MARGIN, o.y - MARGIN, o.z - MARGIN}, hi = {o.x + size + MARGIN, o.y + size + MARGIN, o.z + size + MARGIN};
        for (int a = 0; a < 3; a++) {
            double tmin = Math.min(t[0].get(a), Math.min(t[1].get(a), t[2].get(a)));
            double tmax = Math.max(t[0].get(a), Math.max(t[1].get(a), t[2].get(a)));
            if (tmax < lo[a] || tmin > hi[a]) return false;
        }
        Vector3d n = new Vector3d(t[1]).sub(t[0]).cross(new Vector3d(t[2]).sub(t[0])).normalize();
        Vector3d center = new Vector3d(o).add(size / 2, size / 2, size / 2);
        double reach = (Math.abs(n.x) + Math.abs(n.y) + Math.abs(n.z)) * size / 2 + MARGIN;
        return Math.abs(new Vector3d(center).sub(t[0]).dot(n)) <= reach;
    }

    private static void putVec(ByteBuffer b, Vector3d v) {
        b.putFloat((float) v.x).putFloat((float) v.y).putFloat((float) v.z);
    }
}
