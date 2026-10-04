package dev.moui.galaxycraft.voxel;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.kcl.KclParser;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Blocks other than cubes: model space in a cell, their faces and collision, placing, saving, the atlas. */
class BlockShapesTest {
    private final VoxelPlanet p = VoxelPlanet.standard(); // grass at layer 8, air from 9
    private final CubeSphere g = p.grid;

    private int at(int i, int j, int k) {
        return g.index(0, i, j, k);
    }

    @Test void modelSpaceSitsOnTheCellUnmirrored() {
        int c = at(5, 7, 9);
        for (int m = 0; m < 8; m++) {
            int di = m & 1, dj = m >> 1 & 1, dk = m >> 2;
            // x along j, y outward, z along i.
            assertTrue(CellSpace.point(g, c, dj, dk, di).distance(g.corner(c, di, dj, dk)) < 1e-9);
        }
        Vector3d inside = CellSpace.point(g, c, 0.2, 0.7, 0.4);
        Vector3d back = CellSpace.local(g, c, inside);
        assertEquals(0.2, back.x, 1e-6);
        assertEquals(0.7, back.y, 1e-6);
        assertEquals(0.4, back.z, 1e-6);
        // Model axes: +y is outward, +x toward J_PLUS, +z toward I_PLUS, and x × y = z as in Minecraft.
        Vector3d mid = g.center(c);
        assertEquals(1, CellSpace.direction(g, c, mid).y, 1e-3);
        assertTrue(CellSpace.direction(g, c, new Vector3d(g.center(g.neighbor(c, CubeSphere.J_PLUS))).sub(mid)).x > 0.99);
        assertTrue(CellSpace.direction(g, c, new Vector3d(g.center(g.neighbor(c, CubeSphere.I_PLUS))).sub(mid)).z > 0.99);
        for (int s = 0; s < 6; s++)
            assertEquals(s, CellSpace.SIDE_OF_DIRECTION[CellSpace.DIRECTION_OF_SIDE[s]]);
    }

    @Test void facesPointOutOfTheirBlock() {
        int c = at(12, 12, 9);
        p.set(c, Material.STONE);
        for (PlanetMesher.Quad q : PlanetMesher.quads(p, p.chunkOf(c))) {
            Vector3d n = new Vector3d(q.corners()[2]).sub(q.corners()[0]).cross(new Vector3d(q.corners()[3]).sub(q.corners()[1]));
            Vector3d mid = new Vector3d();
            for (Vector3d v : q.corners()) mid.add(v);
            mid.mul(0.25);
            int owner = g.cellAt(new Vector3d(mid).sub(new Vector3d(n).normalize(0.01)));
            assertTrue(owner >= 0 && p.get(owner) != Blocks.AIR, "a face is wound counter-clockwise seen from outside");
        }
    }

    @Test void aSlabShowsItsTopAndCollidesHalfHigh() {
        int c = at(12, 12, 9);
        int chunk = p.chunkOf(c);
        int before = PlanetMesher.quads(p, chunk).size();
        p.set(c, CubeBlocks.SLAB);
        List<PlanetMesher.Quad> q = PlanetMesher.quads(p, chunk);
        // The grass under it still shows (a slab hides nothing) and the slab's bottom lies on the
        // grass: the slab adds its top and four sides.
        assertEquals(before + 5, q.size());
        double r0 = g.radius(9);
        assertTrue(q.stream().anyMatch(x -> x.side() == -1 && Math.abs(x.corners()[0].length() - (r0 + 0.5)) < 0.05),
                "its top, half a block up and never culled");
        // Collision: its top, half a block up (its bottom lies on full grass and is left out).
        List<Vector3d[]> col = PlanetMesher.collision(p, chunk);
        assertTrue(col.stream().anyMatch(k -> Math.abs(k[0].length() - (r0 + 0.5)) < 0.05));
        double[] outline = p.info(c).outline();
        assertEquals(0.5, outline[4], 1e-9);
    }

    @Test void theCrosshairMeetsASlabWhereItIs() {
        int c = at(12, 12, 9), side = g.neighbor(c, CubeSphere.J_MINUS);
        p.set(c, CubeBlocks.SLAB);
        // Across the cell's empty upper half, from beside it: on past the slab.
        Vector3d from = CellSpace.point(g, side, 0.5, 0.75, 0.5);
        Vector3d dir = CellSpace.point(g, c, 0.5, 0.75, 0.5).sub(from).normalize();
        PlanetRaycast.Hit over = PlanetRaycast.cast(p, from, dir, 1.5);
        assertTrue(over == null || over.hit() != c, "the slab's empty half is not hit");
        // Through its lower half: its side, and from above its top, half a block up.
        from = CellSpace.point(g, side, 0.5, 0.25, 0.5);
        dir = CellSpace.point(g, c, 0.5, 0.25, 0.5).sub(from).normalize();
        assertEquals(CubeSphere.J_MINUS, PlanetRaycast.cast(p, from, dir, 1.5).face());
        from = CellSpace.point(g, c, 0.5, 2, 0.5);
        PlanetRaycast.Hit top = PlanetRaycast.cast(p, from, CellSpace.point(g, c, 0.5, 0, 0.5).sub(from).normalize(), 4);
        assertEquals(c, top.hit());
        assertEquals(CubeSphere.TOP, top.face());
        assertEquals(0.5, CellSpace.local(g, c, top.point()).y, 1e-3);
    }

    @Test void flowersAreDrawnButDoNotCollide() {
        int c = at(12, 12, 9);
        int chunk = p.chunkOf(c);
        int col = PlanetMesher.collision(p, chunk).size();
        p.set(c, CubeBlocks.FLOWER);
        assertEquals(4, PlanetMesher.quads(p, chunk).stream().filter(x -> x.tile() == 11).count());
        assertEquals(col, PlanetMesher.collision(p, chunk).size());
        assertNotNull(PlanetRaycast.cast(p, g.center(c).mul(1.3), new Vector3d(g.center(c)).normalize().negate(), 10),
                "the crosshair stops on it");
    }

    @Test void glassHidesNothingButItsOwnSharedFaces() {
        int a = at(12, 12, 9), b = at(13, 12, 9);
        p.set(a, CubeBlocks.GLASS);
        long one = PlanetMesher.quads(p, p.chunkOf(a)).stream().filter(x -> x.tile() == 10).count();
        assertEquals(5, one, "five faces: its bottom lies on grass");
        int grassTops = (int) PlanetMesher.quads(p, p.chunkOf(a)).stream().filter(x -> x.tile() == 0).count();
        p.set(b, CubeBlocks.GLASS);
        long two = PlanetMesher.quads(p, p.chunkOf(a)).stream().filter(x -> x.tile() == 10).count();
        assertEquals(8, two, "the faces between them are gone");
        assertEquals(grassTops, PlanetMesher.quads(p, p.chunkOf(a)).stream().filter(x -> x.tile() == 0).count(),
                "the grass under glass still shows through it");
    }

    @Test void placingFollowsThePlacerAndRefusesTakenCells() {
        PlanetSession s = new PlanetSession(1);
        s.spawn(16, new Vector3d(0, 0, 0), new Vector3d(0, 1, 0));
        s.update(1, 1, null);
        VoxelPlanet planet = s.planet();
        double surface = planet.surface();
        Vector3d center = s.center();
        Vector3d eye = new Vector3d(1.3, surface + 2.2, 0.4).add(center), down = new Vector3d(0, -1, 0);
        Vector3d far = new Vector3d(0, 0, 0); // Mario far away
        // A two-cell block (like a door): the cell and the one above.
        List<int[]> asked = new ArrayList<>();
        Placer tall = (pl, cell, face, hit, look) -> {
            asked.add(new int[] {face});
            assertTrue(hit.y < 0.05, "clicked on the top face: the bottom of the new block");
            assertTrue(look.y < -0.99, "looking straight down");
            return List.of(new int[] {cell, CubeBlocks.GLASS}, new int[] {pl.grid.neighbor(cell, CubeSphere.TOP), CubeBlocks.GLASS});
        };
        assertTrue(s.placeBlock(eye, down, tall, far));
        assertEquals(CubeSphere.TOP, asked.get(0)[0]);
        int placed = planet.grid.cellAt(new Vector3d(1.3, surface + 0.5, 0.4));
        assertEquals(CubeBlocks.GLASS, planet.get(placed));
        assertEquals(CubeBlocks.GLASS, planet.get(planet.grid.neighbor(placed, CubeSphere.TOP)));
        // Refused: nothing given, or a cell it needs is taken.
        assertFalse(s.placeBlock(eye, down, (pl, cell, face, hit, look) -> List.of(), far));
        Placer blocked = (pl, cell, face, hit, look) -> List.of(new int[] {cell, CubeBlocks.SLAB},
                new int[] {pl.grid.neighbor(cell, CubeSphere.BOTTOM), CubeBlocks.SLAB});
        assertFalse(s.placeBlock(eye, down, blocked, far));
    }

    @Test void placingIntoAFlowerReplacesNothingButAir() {
        PlanetSession s = new PlanetSession(1);
        s.spawn(16, new Vector3d(0, 0, 0), new Vector3d(0, 1, 0));
        s.update(1, 1, null);
        VoxelPlanet planet = s.planet();
        Vector3d c = s.center();
        int top = planet.grid.cellAt(new Vector3d(0.3, planet.surface() + 0.5, 0.3));
        planet.set(top, CubeBlocks.FLOWER);
        // The flower is targetable but not replaceable: the block goes on top of it (aimed at its stem).
        Vector3d up = planet.grid.center(top).normalize();
        assertTrue(s.placeBlock(planet.grid.center(top).fma(2.5, up).add(c), new Vector3d(up).negate(),
                Placer.of(planet.blocks.id(Material.STONE)), new Vector3d()));
        assertEquals(CubeBlocks.FLOWER, planet.get(top));
        assertEquals(Material.STONE, planet.material(planet.grid.neighbor(top, CubeSphere.TOP)));
    }

    @Test void neighborsSettleAfterAnEdit() {
        // A palette where a slab turns into glass whenever it has stone beside it.
        Blocks rules = new Blocks() {
            final CubeBlocks b = CubeBlocks.INSTANCE;
            public BlockInfo info(int id) { return b.info(id); }
            public int id(Material m) { return b.id(m); }
            public Material material(int id) { return b.material(id); }
            public int fluidState(int fluid, int level) { return b.fluidState(fluid, level); }
            public boolean faceVisible(int id, int n, int side) { return b.faceVisible(id, n, side); }
            public String name(int id) { return b.name(id); }
            public int parse(String name) { return b.parse(name); }
            public int atlasColumns() { return 4; }
            public int atlasRows() { return 4; }
            @Override public int updateShape(VoxelPlanet pl, int cell) {
                if (pl.get(cell) != CubeBlocks.SLAB) return pl.get(cell);
                for (int s = 2; s < 6; s++) if (pl.material(pl.grid.neighbor(cell, s)) == Material.STONE) return CubeBlocks.GLASS;
                return CubeBlocks.SLAB;
            }
        };
        VoxelPlanet q = VoxelPlanet.standard(rules);
        int a = q.grid.index(0, 12, 12, 9), b = q.grid.index(0, 13, 12, 9);
        q.set(a, CubeBlocks.SLAB);
        q.set(b, Material.STONE);
        q.settle(b);
        assertEquals(CubeBlocks.GLASS, q.get(a));
    }

    @Test void savesKeepAPaletteAndReadOldOnes(@TempDir Path dir) throws Exception {
        PlanetStore store = new PlanetStore(dir);
        int c = at(12, 12, 9);
        p.set(c, CubeBlocks.FLOWER);
        p.set(at(13, 12, 9), Material.WATER, 3);
        CubeSphere grid = p.grid;
        store.write("G", new PlanetStore.Saved(grid.n, grid.core, grid.layers, p.depth, new Vector3d(1, 2, 3), p.cells()),
                CubeBlocks.INSTANCE);
        PlanetStore.Saved back = store.read("G", CubeBlocks.INSTANCE).orElseThrow();
        assertArrayEquals(p.cells(), back.cells());
        // A GXP1 file: a byte per cell, Material ordinal and a fluid's level in the high nibble.
        Path old = store.file("Old");
        int n = 2, layers = 3, len = 6 * n * n * layers;
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(old)))) {
            out.writeInt(0x47585031);
            out.writeInt(n);
            out.writeDouble(5);
            out.writeInt(layers);
            out.writeInt(2);
            out.writeDouble(0);
            out.writeDouble(0);
            out.writeDouble(0);
            out.writeInt(len);
            byte[] cells = new byte[len];
            cells[0] = (byte) Material.BEDROCK.ordinal();
            cells[1] = (byte) (Material.WATER.ordinal() | 5 << 4);
            cells[2] = (byte) Material.GRASS.ordinal();
            out.write(cells);
        }
        char[] got = store.read("Old", CubeBlocks.INSTANCE).orElseThrow().cells();
        assertEquals(CubeBlocks.INSTANCE.id(Material.BEDROCK), got[0]);
        assertEquals(CubeBlocks.INSTANCE.fluidState(Blocks.WATER, 5), got[1]);
        assertEquals(CubeBlocks.INSTANCE.id(Material.GRASS), got[2]);
        assertEquals(Blocks.AIR, got[3]);
    }

    @Test void atlasIsAPowerOfTwoWithMipmapsThatKeepColorsOutOfHoles() {
        assertArrayEquals(new int[] {4, 4}, Atlas.size(1));
        assertArrayEquals(new int[] {8, 4}, Atlas.size(17));
        assertArrayEquals(new int[] {64, 32}, Atlas.size(1100));
        List<int[]> tiles = new ArrayList<>();
        int[] red = new int[256];
        java.util.Arrays.fill(red, 0xFFFF0000);
        int[] holes = new int[256];
        for (int i = 0; i < 256; i++) holes[i] = i % 2 == 0 ? 0xFF00FF00 : 0x00000000; // green, every other texel a hole
        tiles.add(red);
        tiles.add(holes);
        Atlas a = Atlas.of(tiles);
        assertEquals(64, a.width());
        assertEquals((64 * 64 + 32 * 32 + 16 * 16 + 8 * 8) * 2, a.data.length);
        int[] half = Atlas.half(new int[] {0xFF00FF00, 0, 0xFF00FF00, 0}, 2, 2);
        assertEquals(0x7F00FF00, half[0], "half covered, still pure green");
    }

    @Test void atlasGoesOutInPiecesAgainInEveryScene() {
        List<int[]> tiles = new ArrayList<>();
        for (int t = 0; t < 70; t++) tiles.add(new int[256]);
        Atlas a = Atlas.of(tiles);
        AtlasLink link = new AtlasLink(a, 9);
        int pieces = 0, bytes = 0;
        for (byte[] b; (b = link.peek(1, 100)) != null; link.sent()) {
            ByteBuffer le = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(9, le.getInt(0));
            assertEquals(a.width(), le.getInt(4));
            assertEquals(a.data.length, le.getInt(16));
            assertEquals(bytes, le.getInt(20));
            bytes += b.length - 24;
            pieces++;
        }
        assertEquals(a.data.length, bytes);
        assertTrue(pieces > 1);
        assertTrue(link.done());
        assertNull(link.peek(1, 100));
        assertEquals(0, ByteBuffer.wrap(link.peek(2, 100)).order(ByteOrder.LITTLE_ENDIAN).getInt(20), "a new scene starts over");
    }

    @Test void meshTextureCoordinatesStayInsideTheirTile() {
        // A quarter texel past the tile's edge is pulled half a texel inside it.
        assertEquals((short) Math.round(0.5 / 64 * 32768), PlanetMesher.st(0, -0.01, 4));
        assertEquals((short) Math.round((16 + 15.5) / 64 * 32768), PlanetMesher.st(1, 1.0, 4));
        var m = PlanetMesher.mesh(p, p.chunkOf(at(12, 12, 8)), 80);
        assertFalse(KclParser.parse(m.kcl()).isEmpty());
    }
}
