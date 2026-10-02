package dev.moui.galaxycraft.kcl;

import dev.moui.galaxycraft.geom.Tri;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3d;

/**
 * Reads Nintendo KCL collision (big-endian) into triangles. Prisms are 0x10 bytes, 1-based
 * (the header's prism offset points 0x10 before the first). The octree is ignored.
 */
public final class KclParser {
    private static final int HEADER_SIZE = 0x38;
    private static final int PRISM_SIZE = 0x10;

    private KclParser() {}

    public static List<Tri> parse(byte[] data) {
        if (data.length < HEADER_SIZE) throw new IllegalArgumentException("KCL shorter than its header");
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        int posOff = b.getInt(0), nrmOff = b.getInt(4), prismOff = b.getInt(8), octOff = b.getInt(12);
        if (posOff < HEADER_SIZE || nrmOff < posOff || prismOff + PRISM_SIZE < nrmOff
                || octOff < prismOff + PRISM_SIZE || octOff > data.length) {
            throw new IllegalArgumentException("KCL offsets out of range");
        }
        int nPos = (nrmOff - posOff) / 12;
        int nNrm = (prismOff + PRISM_SIZE - nrmOff) / 12;
        List<Tri> out = new ArrayList<>();
        try {
            for (int o = prismOff + PRISM_SIZE; o + PRISM_SIZE <= octOff; o += PRISM_SIZE) {
                float height = b.getFloat(o);
                int pi = Short.toUnsignedInt(b.getShort(o + 4));
                int fi = Short.toUnsignedInt(b.getShort(o + 6));
                int e1 = Short.toUnsignedInt(b.getShort(o + 8));
                int e2 = Short.toUnsignedInt(b.getShort(o + 10));
                int e3 = Short.toUnsignedInt(b.getShort(o + 12));
                if (pi >= nPos || fi >= nNrm || e1 >= nNrm || e2 >= nNrm || e3 >= nNrm) continue;
                Tri t = prism(height, vec(b, posOff + 12 * pi), vec(b, nrmOff + 12 * fi),
                        vec(b, nrmOff + 12 * e1), vec(b, nrmOff + 12 * e2), vec(b, nrmOff + 12 * e3));
                if (t != null) out.add(t);
            }
        } catch (IndexOutOfBoundsException | BufferUnderflowException e) {
            throw new IllegalArgumentException("KCL truncated", e);
        }
        return out;
    }

    private static Tri prism(double h, Vector3d v1, Vector3d f, Vector3d ea, Vector3d eb, Vector3d ec) {
        Vector3d ca = new Vector3d(ea).cross(f);
        Vector3d cb = new Vector3d(eb).cross(f);
        double db = cb.dot(ec), da = ca.dot(ec);
        if (Math.abs(db) < 1e-9 || Math.abs(da) < 1e-9) return null;
        Vector3d v2 = new Vector3d(v1).fma(h / db, cb);
        Vector3d v3 = new Vector3d(v1).fma(h / da, ca);
        if (!v2.isFinite() || !v3.isFinite()) return null;
        Vector3d n = new Vector3d(v2).sub(v1).cross(new Vector3d(v3).sub(v1));
        if (n.lengthSquared() < 1e-12) return null;
        return new Tri(v1, v2, v3, n.normalize());
    }

    private static Vector3d vec(ByteBuffer b, int o) {
        return new Vector3d(b.getFloat(o), b.getFloat(o + 4), b.getFloat(o + 8));
    }
}
