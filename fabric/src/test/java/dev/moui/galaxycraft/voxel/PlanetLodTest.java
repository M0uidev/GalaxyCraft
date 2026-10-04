package dev.moui.galaxycraft.voxel;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class PlanetLodTest {
    /** Positions of a part's vertices (blocks from the planet's center). */
    static Vector3d[] vertices(PlanetLod.Part part) {
        ByteBuffer b = ByteBuffer.wrap(part.displayList());
        int total = 0;
        java.util.List<Vector3d> out = new java.util.ArrayList<>();
        while (b.remaining() >= 3 && (b.get(b.position()) & 0xFF) == PlanetLod.GX_QUADS_FMT5) {
            b.get();
            int n = b.getShort() & 0xFFFF;
            for (int v = 0; v < n; v++) {
                out.add(new Vector3d(b.getShort(), b.getShort(), b.getShort()).div(80));
                b.getShort();
                b.getInt();
            }
            total += n;
        }
        assertEquals(total, out.size());
        return out.toArray(new Vector3d[0]);
    }

    @Test void aFlatPlanetIsOneShellOfPatchesAtItsSurface() {
        VoxelPlanet p = VoxelPlanet.standard();
        int s = PlanetLod.patchColumns(p.grid.n), m = (p.grid.n + s - 1) / s;
        for (int f = 0; f < 6; f++) {
            PlanetLod.Part part = PlanetLod.face(p, f, 80);
            Vector3d[] v = vertices(part);
            // Tops, and skirts along the face's four edges; no walls inside (all one height).
            assertEquals(4 * (m * m + 4 * m), v.length, "face " + f);
            int onSurface = 0;
            for (Vector3d x : v) if (Math.abs(x.length() - p.surface()) < 0.05) onSurface++;
            assertTrue(onSurface >= 4 * m * m, "every top is on the surface");
            assertTrue(part.sphere()[3] > 0);
        }
    }

    @Test void aWholePlanetIsAFewThousandQuads() {
        VoxelPlanet p = VoxelPlanet.ofRadius(256, CubeBlocks.INSTANCE);
        int quads = 0;
        for (PlanetLod.Part part : PlanetLod.parts(p, 80)) {
            quads += vertices(part).length / 4;
            assertTrue(part.displayList().length < 200_000, "a face fits the inbox: " + part.displayList().length);
        }
        assertTrue(quads <= 6 * (PlanetLod.PATCHES * PlanetLod.PATCHES * 3 + 4 * PlanetLod.PATCHES), quads + " quads");
    }

    @Test void aPitLowersItsPatchAndGetsWalls() {
        VoxelPlanet p = VoxelPlanet.standard();
        int s = PlanetLod.patchColumns(p.grid.n);
        int before = vertices(PlanetLod.face(p, 2, 80)).length;
        // Dig the patch at (1, 1) of face 2 three blocks down.
        int top = p.grid.layers - 1;
        while (p.get(p.grid.index(2, s, s, top)) == Blocks.AIR) top--;
        for (int i = s; i < 2 * s; i++)
            for (int j = s; j < 2 * s; j++)
                for (int k = top; k > top - 3; k--) p.set(p.grid.index(2, i, j, k), Material.AIR);
        Vector3d[] v = vertices(PlanetLod.face(p, 2, 80));
        assertEquals(before + 4 * 4, v.length, "four walls around the pit");
        int floor = 0;
        for (Vector3d x : v) if (Math.abs(x.length() - (p.surface() - 3)) < 0.05) floor++;
        assertTrue(floor >= 4, "the pit's floor is three blocks down: " + floor + " corners there");
    }
}
