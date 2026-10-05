package dev.moui.galaxycraft.voxel;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.kcl.KclParser;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class PlanetMesherTest {
    static List<PlanetMesher.Quad> all(VoxelPlanet p) {
        List<PlanetMesher.Quad> out = new ArrayList<>();
        for (int c = 0; c < p.chunkCount(); c++) out.addAll(PlanetMesher.quads(p, c));
        return out;
    }

    static String key(Vector3d v) {
        return Math.round(v.x * 1e5) + "," + Math.round(v.y * 1e5) + "," + Math.round(v.z * 1e5);
    }

    /** Every edge away from the sealed inner shell is used by exactly two quads, in opposite directions. */
    static void assertClosed(VoxelPlanet p) {
        Map<String, Integer> edges = new HashMap<>();
        for (PlanetMesher.Quad q : all(p))
            for (int k = 0; k < 4; k++) {
                Vector3d a = q.corners()[k], b = q.corners()[(k + 1) % 4];
                if (a.length() < p.grid.core + 0.5 && b.length() < p.grid.core + 0.5) continue;
                edges.merge(key(a) + ">" + key(b), 1, Integer::sum);
            }
        for (var e : edges.entrySet()) {
            String[] ab = e.getKey().split(">");
            assertEquals(1, e.getValue(), "edge used twice the same way " + e.getKey());
            assertEquals(1, edges.getOrDefault(ab[1] + ">" + ab[0], 0), "open edge " + e.getKey());
        }
    }

    @Test void untouchedPlanetIsClosedAndOnlyGrassShows() {
        VoxelPlanet p = VoxelPlanet.standard();
        assertClosed(p);
        List<PlanetMesher.Quad> q = all(p);
        assertEquals(6 * 24 * 24, q.size());
        assertTrue(q.stream().allMatch(x -> x.side() == CubeSphere.TOP && x.tile() == 0)); // grass top
    }

    @Test void diggingAddsTheFacesAroundTheHole() {
        VoxelPlanet p = VoxelPlanet.standard();
        int before = all(p).size();
        int cell = p.grid.index(0, 0, 0, 8); // at a cube corner: three faces meet
        p.set(cell, Material.AIR);
        assertClosed(p);
        List<PlanetMesher.Quad> q = all(p);
        // Grass top gone, dirt top below; a corner cell has 4 side neighbors, all grass.
        assertEquals(before - 1 + 1 + 4, q.size());
        assertEquals(4, q.stream().filter(x -> x.tile() == 1).count()); // grass sides
    }

    @Test void sunlitSideIsBrighterAndHolesAreShaded() {
        VoxelPlanet p = VoxelPlanet.standard();
        CubeSphere g = p.grid;
        // The grass facing the sun and the grass on the far side.
        int day = g.cellAt(new Vector3d(PlanetMesher.SUN).mul(15.5)), night = g.cellAt(new Vector3d(PlanetMesher.SUN).mul(-15.5));
        double dayLight = PlanetMesher.quads(p, p.chunkOf(day)).stream().filter(q -> q.side() == CubeSphere.TOP)
                .mapToDouble(q -> q.light()[0]).max().orElseThrow();
        double nightLight = PlanetMesher.quads(p, p.chunkOf(night)).stream().filter(q -> q.side() == CubeSphere.TOP)
                .mapToDouble(q -> q.light()[0]).max().orElseThrow();
        assertTrue(dayLight > 0.95 && nightLight <= PlanetMesher.AMBIENT + 1e-9, dayLight + " / " + nightLight);
        // A hole: the dirt at its bottom is darker at the corners than an open field.
        int cell = g.index(2, 12, 12, 8);
        p.set(cell, Material.AIR);
        int below = g.neighbor(cell, CubeSphere.BOTTOM);
        var floor = PlanetMesher.quads(p, p.chunkOf(below)).stream()
                .filter(q -> q.side() == CubeSphere.TOP && q.corners()[0].distance(g.corner(below, 0, 0, 1)) < 2).findFirst().orElseThrow();
        double sun = PlanetMesher.AMBIENT + (1 - PlanetMesher.AMBIENT) * Math.max(0, g.center(below).normalize().dot(PlanetMesher.SUN));
        for (double l : floor.light()) assertTrue(l < sun * 0.7, "corner " + l + " vs open " + sun);
    }

    @Test void aDarkCaveIsDrawnOnlyNearMario() {
        VoxelPlanet p = VoxelPlanet.standard();
        // A sealed pocket deep under the grass (k 8), and a shaft from the grass into another.
        int[] pocket = {p.grid.index(2, 12, 12, 2), p.grid.index(2, 12, 13, 2), p.grid.index(2, 13, 12, 2)};
        for (int c : pocket) p.set(c, Material.AIR);
        int shaftChunk = p.chunkOf(p.grid.index(2, 4, 4, 3));
        for (int k = 3; k <= 8; k++) p.set(p.grid.index(2, 4, 4, k), Material.AIR);
        p.set(p.grid.index(2, 4, 5, 3), Material.AIR);
        int chunk = p.chunkOf(pocket[0]);
        int near = PlanetMesher.quads(p, chunk).size(), far = PlanetMesher.quads(p, chunk, new PlanetMesher.Dark(p)).size();
        assertTrue(near > far, near + " near, " + far + " far");
        // What the pocket shows from inside: the faces around its three cells.
        var walls = PlanetMesher.quads(p, chunk).stream().filter(q -> {
            Vector3d c = new Vector3d();
            for (Vector3d v : q.corners()) c.add(v);
            c.div(4);
            return c.distance(p.grid.center(pocket[0])) < 2.5;
        }).count();
        assertEquals(near - far, walls, "only the pocket's walls are left out");
        assertEquals(PlanetMesher.quads(p, shaftChunk).size(), PlanetMesher.quads(p, shaftChunk, new PlanetMesher.Dark(p)).size(),
                "a cave the sky reaches shows from afar");
        // The bytes sent for a chunk far from Mario leave the dark faces out too.
        byte[] dl = PlanetMesher.mesh(p, chunk, 80, false).displayList();
        assertEquals(far * 4, dl.length == 0 ? 0 : ByteBuffer.wrap(dl).getShort(1) & 0xFFFF);
    }

    @Test void meshBytesMatchTheQuads() {
        VoxelPlanet p = VoxelPlanet.standard();
        int chunk = p.chunkOf(p.grid.index(2, 12, 12, 8));
        var m = PlanetMesher.mesh(p, chunk, 80);
        int quads = PlanetMesher.quads(p, chunk).size();
        ByteBuffer dl = ByteBuffer.wrap(m.displayList());
        assertEquals(PlanetMesher.GX_QUADS_FMT7, dl.get(0) & 0xFF);
        assertEquals(quads * 4, dl.getShort(1) & 0xFFFF);
        assertEquals(0, m.displayList().length % 32);
        assertEquals(2 * quads, KclParser.parse(m.kcl()).size());
        // Grass top in galaxy units: about 16 blocks × 80 from the center, facing outward.
        var t = KclParser.parse(m.kcl()).get(0);
        assertEquals(16 * 80, t.a().length(), 1);
        assertTrue(t.n().dot(t.a()) > 0);
    }

    /**
     * A corner that several chunks draw is the same point in each of their display lists (center
     * plus vertex, in 1/8 units), across chunk borders and cube edges alike: no seams to see through.
     */
    @Test void chunksMeetWithoutSeams() {
        VoxelPlanet p = VoxelPlanet.ofRadius(61);
        // A trench across chunk borders and a cube edge, so their sides are drawn too.
        int n = p.grid.n, k = p.depth - 1;
        for (int i = 0; i < n; i++) p.set(p.grid.index(0, i, n / 2, k), Material.AIR);
        for (int j = 0; j < n; j++) p.set(p.grid.index(0, n - 1, j, k), Material.AIR);
        Map<String, String> seen = new HashMap<>();
        int shared = 0;
        for (int c = 0; c < p.chunkCount(); c++) {
            var m = PlanetMesher.mesh(p, c, 80, false);
            if (m.empty()) continue;
            float[] s = m.sphere();
            for (float f : new float[] {s[0], s[1], s[2]}) assertEquals(Math.rint(f), f, 0, "center in whole units");
            ByteBuffer dl = ByteBuffer.wrap(m.displayList());
            int v = 0;
            for (PlanetMesher.Quad q : PlanetMesher.quads(p, c))
                for (Vector3d corner : q.corners()) {
                    int o = 3 + PlanetMesher.LIT_VERTEX_BYTES * v++;
                    String at = "";
                    for (int a = 0; a < 3; a++) at += ((long) s[a] * 8 + dl.getShort(o + 2 * a)) + ",";
                    String before = seen.putIfAbsent(key(corner), at);
                    if (before == null) continue;
                    assertEquals(before, at, "corner " + key(corner) + " drawn apart");
                    shared++;
                }
        }
        assertTrue(shared > 0);
    }

    @Test void cubeEdgesShareTheirDirectionsExactly() {
        CubeSphere g = new CubeSphere(37, 20, 4);
        Map<String, Vector3d> dirs = new HashMap<>();
        for (int f = 0; f < 6; f++)
            for (int i = 0; i <= g.n; i++)
                for (int j = 0; j <= g.n; j++) {
                    if (i != 0 && i != g.n && j != 0 && j != g.n) continue;
                    Vector3d d = g.dir(f, i, j);
                    Vector3d before = dirs.putIfAbsent(key(d), d);
                    if (before != null) assertEquals(before, d, "face " + f + " at " + i + "," + j);
                }
    }

    @Test void emptyChunkHasNoBytes() {
        VoxelPlanet p = VoxelPlanet.standard();
        int top = p.chunkOf(p.grid.index(0, 0, 0, 16));
        assertTrue(PlanetMesher.mesh(p, top, 80).empty());
        assertEquals(0, PlanetMesher.mesh(p, top, 80).kcl().length);
    }
}
