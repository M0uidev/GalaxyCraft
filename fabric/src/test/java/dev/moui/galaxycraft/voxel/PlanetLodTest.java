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

    @Test void tilesShareOutEveryChunkOnce() {
        for (int radius : new int[] {10, 32, 128, 256}) {
            VoxelPlanet p = VoxelPlanet.ofRadius(radius, CubeBlocks.INSTANCE);
            int tiles = PlanetLod.tileCount(p);
            assertTrue(PlanetLod.tilesPerEdge(p) <= PlanetLod.MAX_TILES_PER_EDGE, "radius " + radius);
            int[] seen = new int[p.chunkCount()];
            for (int t = 0; t < tiles; t++)
                for (int c : PlanetLod.chunksOfTile(p, t)) {
                    seen[c]++;
                    assertEquals(t, PlanetLod.tileOfChunk(p, c));
                }
            for (int c = 0; c < seen.length; c++) assertEquals(1, seen[c], "radius " + radius + " chunk " + c);
        }
    }

    @Test void aFlatPlanetsTilesLieOnItsSurface() {
        VoxelPlanet p = VoxelPlanet.ofRadius(128, CubeBlocks.INSTANCE);
        int quads = 0;
        for (int t = 0; t < PlanetLod.tileCount(p); t++) {
            Vector3d[] v = vertices(PlanetLod.tile(p, t, 80));
            assertTrue(v.length > 0, "tile " + t);
            int onSurface = 0;
            for (Vector3d x : v) if (Math.abs(x.length() - p.surface()) < 0.05) onSurface++;
            assertTrue(onSurface >= v.length / 2, "tops (and the tops of its skirts) on the surface");
            quads += v.length / 4;
        }
        assertTrue(quads < 20_000, quads + " quads for the whole planet");
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

    /** CubeBlocks, with its glass taken for leaves. */
    record LeafyBlocks(Blocks b) implements Blocks {
        public BlockInfo info(int id) { return b.info(id); }
        public int id(Material m) { return b.id(m); }
        public Material material(int id) { return b.material(id); }
        public int fluidState(int fluid, int level) { return b.fluidState(fluid, level); }
        public boolean faceVisible(int id, int neighbor, int side) { return b.faceVisible(id, neighbor, side); }
        public String name(int id) { return b.name(id); }
        public int parse(String name) { return b.parse(name); }
        public int atlasColumns() { return b.atlasColumns(); }
        public int atlasRows() { return b.atlasRows(); }
        @Override public boolean leaves(int id) { return id == CubeBlocks.GLASS; }
    }

    @Test void treetopsDoNotRaiseTheFarView() {
        VoxelPlanet p = VoxelPlanet.standard(new LeafyBlocks(CubeBlocks.INSTANCE));
        int before = vertices(PlanetLod.face(p, 2, 80)).length;
        int s = PlanetLod.patchColumns(p.grid.n), top = p.grid.layers - 1;
        while (p.get(p.grid.index(2, s, s, top)) == Blocks.AIR) top--;
        // Leaves two blocks over the grass of the patch at (1, 1): a crown seen from above.
        for (int i = s; i < 2 * s; i++)
            for (int j = s; j < 2 * s; j++) p.set(p.grid.index(2, i, j, top + 3), CubeBlocks.GLASS);
        Vector3d[] v = vertices(PlanetLod.face(p, 2, 80));
        assertEquals(before, v.length, "no walls: the patch stays at the ground");
        for (Vector3d x : v) assertTrue(x.length() <= p.surface() + 0.05, "nothing over the surface: " + x.length());
    }
}
