package dev.moui.galaxycraft.voxel;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/** The voxel pipeline on a station's flat grid: meshing, raycast, light, collision, far view. */
class FlatPlanetTest {
    static final char STONE = (char) CubeBlocks.INSTANCE.id(Material.STONE);

    static VoxelPlanet slab() {
        FlatGrid g = new FlatGrid(16, 8, -8, -2, -8, new Quaterniond().rotateY(0.7));
        char[] cells = new char[g.cellCount()];
        for (int x = -4; x <= 4; x++)
            for (int z = -4; z <= 4; z++) cells[g.cellOf(x, 0, z)] = STONE;
        return VoxelPlanet.flat(g, cells, CubeBlocks.INSTANCE);
    }

    @Test void meshesOnlyTheSlabsOutside() {
        VoxelPlanet p = slab();
        int quads = 0;
        for (int ch = 0; ch < p.chunkCount(); ch++) quads += PlanetMesher.quads(p, ch).size();
        assertEquals(81 * 2 + 9 * 4, quads); // top and bottom of 81 cells, 36 edge sides
    }

    @Test void raycastHitsTheTopFromAbove() {
        VoxelPlanet p = slab();
        FlatGrid g = (FlatGrid) p.grid;
        Vector3d eye = new Vector3d(g.up()).mul(3), look = new Vector3d(g.up()).negate();
        PlanetRaycast.Hit h = PlanetRaycast.cast(p, eye, look, 6);
        assertNotNull(h);
        assertEquals(g.cellOf(0, 0, 0), h.hit());
        assertEquals(CellGrid.TOP, h.face());
    }

    @Test void skyLightsOverTheSlab() {
        VoxelPlanet p = slab();
        FlatGrid g = (FlatGrid) p.grid;
        assertEquals(15, p.light().sky(g.cellOf(0, 1, 0)));
        assertEquals(15, p.light().sky(g.cellOf(6, -1, 0))); // beside it, below its level: open sky too
    }

    @Test void slabCollides() {
        VoxelPlanet p = slab();
        FlatGrid g = (FlatGrid) p.grid;
        int ch = p.chunkOf(g.cellOf(0, 0, 0));
        assertFalse(PlanetMesher.collision(p, ch).isEmpty());
    }

    @Test void farViewIsOneFace() {
        VoxelPlanet p = slab();
        assertEquals(1, PlanetLod.parts(p, 80).length);
        assertEquals(1, PlanetLod.tileCount(p));
        assertNotNull(PlanetLod.tile(p, 0, 80));
    }

    @Test void farViewIsTheSlabNotTheSpaceAroundIt() {
        VoxelPlanet p = slab();
        FlatGrid g = (FlatGrid) p.grid;
        LodSource.Patch all = LodSource.of(p).patch(0, 0, g.n, 0, g.n);
        assertEquals(STONE, all.block()); // not air, though most columns are empty
        assertEquals(3, all.height()); // the slab's top (y = 0 is layer 2), not lowered by the empty ones
        assertEquals(Blocks.AIR, LodSource.of(p).patch(0, 0, 2, 0, 2).block());
        // Patches of empty space draw nothing: the far view is no wider than the slab (±4.5 blocks).
        float[] sphere = PlanetLod.coarse(LodSource.of(p), 8, 80)[0].sphere();
        assertTrue(sphere[3] < 80 * 8, "far view radius " + sphere[3] / 80 + " blocks");
    }

    @Test void aSolidChunkAtTheBoxsEdgeShows() {
        FlatGrid g = new FlatGrid(16, 16, -8, -2, -8, new Quaterniond());
        char[] cells = new char[g.cellCount()];
        java.util.Arrays.fill(cells, STONE); // only the box's own sides are open
        VoxelPlanet p = VoxelPlanet.flat(g, cells, CubeBlocks.INSTANCE);
        assertTrue(p.mayShow(p.chunkOf(g.index(0, 0, 0, 0))));
    }
}
