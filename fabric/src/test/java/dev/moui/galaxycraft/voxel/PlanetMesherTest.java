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
        assertTrue(q.stream().allMatch(x -> x.side() == CubeSphere.TOP && x.tile() == Material.GRASS.top));
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
        assertEquals(4, q.stream().filter(x -> x.tile() == Material.GRASS.side).count());
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

    @Test void emptyChunkHasNoBytes() {
        VoxelPlanet p = VoxelPlanet.standard();
        int top = p.chunkOf(p.grid.index(0, 0, 0, 16));
        assertTrue(PlanetMesher.mesh(p, top, 80).empty());
        assertEquals(0, PlanetMesher.mesh(p, top, 80).kcl().length);
    }
}
