package dev.moui.galaxycraft.kcl;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.geom.Tri;
import java.nio.ByteBuffer;
import java.util.List;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class KclWriterTest {
    @Test void roundTripsThroughTheParser() {
        List<Tri> in = List.of(Tri.of(new Vector3d(0, 0, 0), new Vector3d(100, 0, 0), new Vector3d(0, 0, -100)),
                Tri.of(new Vector3d(10, 5, -3), new Vector3d(130, 40, 20), new Vector3d(-20, 80, -150)));
        List<Tri> out = KclParser.parse(KclWriter.write(in));
        assertEquals(2, out.size());
        for (int i = 0; i < 2; i++) {
            assertEquals(0, in.get(i).a().distance(out.get(i).a()), 1e-3);
            assertEquals(0, in.get(i).b().distance(out.get(i).b()), 1e-3);
            assertEquals(0, in.get(i).c().distance(out.get(i).c()), 1e-3);
        }
    }

    @Test void aFewPrismsAreOneLeafTheGameCanWalk() {
        byte[] k = KclWriter.write(List.of(Tri.of(new Vector3d(0, 0, 0), new Vector3d(100, 0, 0), new Vector3d(0, 0, -100))));
        ByteBuffer b = ByteBuffer.wrap(k);
        int oct = b.getInt(12);
        assertEquals(40f, b.getFloat(16));
        assertEquals(0x80000004, b.getInt(oct));
        assertEquals(1, b.getShort(oct + 4 + 2), "list: node + 4 + 2 = after the node and the 2 skipped bytes");
        assertEquals(0, b.getShort(oct + 8));
        // One root block: the masks leave no bits below the area's size.
        int mask = b.getInt(32), shift = b.getInt(44);
        assertEquals(~((1 << shift) - 1), mask);
        assertTrue((1 << shift) >= 101);
        assertEquals(0, k.length % 4);
    }

    /** The prisms (1-based) the game reads for a point, walking the octree as its searchBlock does. */
    static List<Integer> search(byte[] k, Vector3d p) {
        ByteBuffer b = ByteBuffer.wrap(k);
        int oct = b.getInt(12), mask = b.getInt(32), shift = b.getInt(44);
        long x = (long) Math.floor(p.x - b.getFloat(20)), y = (long) Math.floor(p.y - b.getFloat(24)), z = (long) Math.floor(p.z - b.getFloat(28));
        if (x < 0 || y < 0 || z < 0 || (x & mask) != 0 || (y & mask) != 0 || (z & mask) != 0) return List.of();
        int block = oct, index = 0, node;
        while (((node = b.getInt(block + 4 * index)) & 0x80000000) == 0) {
            shift--;
            block += node;
            index = (int) ((z >> shift & 1) << 2 | (y >> shift & 1) << 1 | (x >> shift & 1));
        }
        List<Integer> out = new java.util.ArrayList<>();
        for (int at = block + (node & 0x7FFFFFFF) + 2; b.getShort(at) != 0; at += 2) out.add((int) b.getShort(at));
        return out;
    }

    @Test void theGameFindsEveryPrismNearAPoint() {
        // A rough floor of a chunk (8 blocks of 80 units), with a ridge of steps: many prisms.
        List<Tri> tris = new java.util.ArrayList<>();
        java.util.Random r = new java.util.Random(3);
        for (int i = 0; i < 8; i++)
            for (int j = 0; j < 8; j++) {
                double h = 80 * r.nextInt(4), x = 80 * i, z = 80 * j;
                tris.add(Tri.of(new Vector3d(x, h, z), new Vector3d(x, h, z + 80), new Vector3d(x + 80, h, z + 80)));
                tris.add(Tri.of(new Vector3d(x, h, z), new Vector3d(x + 80, h, z + 80), new Vector3d(x + 80, h, z)));
                tris.add(Tri.of(new Vector3d(x, 0, z), new Vector3d(x, h + 1, z), new Vector3d(x, h + 1, z + 80)));
            }
        byte[] k = KclWriter.write(tris);
        ByteBuffer b = ByteBuffer.wrap(k);
        assertNotEquals(0, b.getInt(b.getInt(12)) & 0x80000000 ^ 0x80000000, "the root is split");
        int longest = 0, total = 0;
        for (int n = 0; n < 5000; n++) {
            Vector3d p = new Vector3d(r.nextDouble() * 640, r.nextDouble() * 320, r.nextDouble() * 640);
            List<Integer> found = search(k, p);
            longest = Math.max(longest, found.size());
            total += found.size();
            for (int t = 0; t < tris.size(); t++) {
                Tri q = tris.get(t);
                if (q.a().equals(q.b()) || distance(p, q) > KclWriter.MARGIN - 1) continue;
                assertTrue(found.contains(t + 1), "prism " + (t + 1) + " near " + p);
            }
        }
        assertTrue(longest < tris.size() / 4, "a point reads at most " + longest + " of " + tris.size() + " prisms");
        assertTrue(total / 5000.0 < tris.size() / 10.0, "a point reads " + total / 5000.0 + " of " + tris.size() + " prisms");
    }

    /** Distance from p to a triangle (to its box, enough here: within it, it is the triangle's). */
    private static double distance(Vector3d p, Tri t) {
        double d = 0;
        for (int a = 0; a < 3; a++) {
            double lo = Math.min(t.a().get(a), Math.min(t.b().get(a), t.c().get(a))), hi = Math.max(t.a().get(a), Math.max(t.b().get(a), t.c().get(a)));
            double e = Math.max(0, Math.max(lo - p.get(a), p.get(a) - hi));
            d += e * e;
        }
        return Math.sqrt(d);
    }
}
