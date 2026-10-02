package dev.moui.galaxycraft.geom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.joml.Vector3d;

/** Uniform hash grid of triangles in galaxy space, grouped by collision part. */
public final class TriangleIndex {
    private static final int MAX_CELLS_PER_TRI = 4096;

    private final double cell;
    private final Map<Long, List<Tri>> cells = new HashMap<>();
    private final Map<Integer, List<Long>> partCells = new HashMap<>();
    private final Map<Integer, List<Tri>> partTris = new HashMap<>();
    private final List<Tri> oversized = new ArrayList<>(); // spans too many cells: always a candidate

    public TriangleIndex(double cellGal) {
        this.cell = cellGal;
    }

    public void put(int partId, List<Tri> tris) {
        remove(partId);
        List<Long> keys = new ArrayList<>();
        for (Tri t : tris) {
            long ix0 = idx(Math.min(t.a().x, Math.min(t.b().x, t.c().x))), ix1 = idx(Math.max(t.a().x, Math.max(t.b().x, t.c().x)));
            long iy0 = idx(Math.min(t.a().y, Math.min(t.b().y, t.c().y))), iy1 = idx(Math.max(t.a().y, Math.max(t.b().y, t.c().y)));
            long iz0 = idx(Math.min(t.a().z, Math.min(t.b().z, t.c().z))), iz1 = idx(Math.max(t.a().z, Math.max(t.b().z, t.c().z)));
            if ((ix1 - ix0 + 1) * (iy1 - iy0 + 1) * (iz1 - iz0 + 1) > MAX_CELLS_PER_TRI) {
                oversized.add(t);
                continue;
            }
            for (long x = ix0; x <= ix1; x++)
                for (long y = iy0; y <= iy1; y++)
                    for (long z = iz0; z <= iz1; z++) {
                        long k = key(x, y, z);
                        cells.computeIfAbsent(k, kk -> new ArrayList<>()).add(t);
                        keys.add(k);
                    }
        }
        partCells.put(partId, keys);
        partTris.put(partId, tris);
    }

    public void remove(int partId) {
        List<Tri> tris = partTris.remove(partId);
        List<Long> keys = partCells.remove(partId);
        if (tris == null) return;
        Set<Tri> gone = Collections.newSetFromMap(new IdentityHashMap<>());
        gone.addAll(tris);
        for (long k : keys) {
            List<Tri> l = cells.get(k);
            if (l == null) continue;
            l.removeIf(gone::contains);
            if (l.isEmpty()) cells.remove(k);
        }
        oversized.removeIf(gone::contains);
    }

    public void clear() {
        cells.clear();
        partCells.clear();
        partTris.clear();
        oversized.clear();
    }

    public List<Tri> query(Vector3d min, Vector3d max) {
        Set<Tri> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Tri> out = new ArrayList<>(oversized);
        seen.addAll(oversized);
        for (long x = idx(min.x); x <= idx(max.x); x++)
            for (long y = idx(min.y); y <= idx(max.y); y++)
                for (long z = idx(min.z); z <= idx(max.z); z++) {
                    List<Tri> l = cells.get(key(x, y, z));
                    if (l == null) continue;
                    for (Tri t : l) if (seen.add(t)) out.add(t);
                }
        return out;
    }

    private long idx(double v) {
        return (long) Math.floor(v / cell);
    }

    private static long key(long x, long y, long z) {
        return ((x & 0x1FFFFF) << 42) | ((y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }
}
