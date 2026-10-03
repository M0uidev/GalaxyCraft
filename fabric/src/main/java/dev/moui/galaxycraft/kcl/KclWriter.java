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
 * octree of one leaf listing every prism (the list starts 2 bytes after the offset the node stores).
 * Port of tools/kcl.py.
 */
public final class KclWriter {
    private KclWriter() {}

    public static byte[] write(List<Tri> tris) {
        List<Vector3d> positions = new ArrayList<>(), normals = new ArrayList<>();
        List<float[]> prisms = new ArrayList<>(); // height, pos, face, e1, e2, e3
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

        int posOff = 0x38, nrmOff = posOff + 12 * positions.size(), prismData = nrmOff + 12 * normals.size();
        int octOff = prismData + 0x10 * prisms.size();
        int size = octOff + 4 + 2 * (prisms.size() + 1);
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
        b.putInt(0x80000000 | 2); // root: leaf, its list right after the node
        for (int k = 1; k <= prisms.size(); k++) b.putShort((short) k);
        b.putShort((short) 0);
        return b.array();
    }

    private static void putVec(ByteBuffer b, Vector3d v) {
        b.putFloat((float) v.x).putFloat((float) v.y).putFloat((float) v.z);
    }
}
