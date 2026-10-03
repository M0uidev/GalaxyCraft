package dev.moui.galaxycraft.voxel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class VoxelPlanetTest {
    @Test void standardLayers() {
        VoxelPlanet p = VoxelPlanet.standard();
        CubeSphere g = p.grid;
        assertEquals(Material.BEDROCK, p.get(g.index(0, 3, 3, 0)));
        assertEquals(Material.STONE, p.get(g.index(0, 3, 3, 5)));
        assertEquals(Material.DIRT, p.get(g.index(0, 3, 3, 7)));
        assertEquals(Material.GRASS, p.get(g.index(0, 3, 3, 8)));
        assertEquals(Material.AIR, p.get(g.index(0, 3, 3, 9)));
        assertEquals(16.0, p.surface());
    }

    @Test void chunksPartitionTheCells() {
        VoxelPlanet p = VoxelPlanet.standard();
        assertEquals(162, p.chunkCount());
        Set<Integer> seen = new HashSet<>();
        for (int ch = 0; ch < p.chunkCount(); ch++)
            for (int c : p.cellsOf(ch)) {
                assertEquals(ch, p.chunkOf(c));
                assertTrue(seen.add(c));
            }
        assertEquals(p.grid.cellCount(), seen.size());
    }

    @Test void editingMarksTheChunkAndBumpsItsVersion() {
        VoxelPlanet p = VoxelPlanet.standard();
        assertEquals(162, p.takeDirty().length);
        int cell = p.grid.index(0, 0, 0, 8); // a cube corner: neighbors in other faces' chunks
        p.set(cell, Material.AIR);
        int[] dirty = p.takeDirty();
        assertTrue(dirty.length >= 3, "own chunk and the other faces' chunks");
        assertTrue(java.util.Arrays.stream(dirty).anyMatch(c -> c == p.chunkOf(cell)));
        assertEquals(1, p.bump(p.chunkOf(cell)));
        assertEquals(1, p.version(p.chunkOf(cell)));
        assertEquals(0, p.takeDirty().length);
        p.set(cell, Material.AIR);
        assertEquals(0, p.takeDirty().length, "no change, nothing to send");
    }

    @Test void sizedPlanetsKeepBlocksAboutABlockWide() {
        for (int r : new int[] {10, 16, 64, 128}) {
            VoxelPlanet p = VoxelPlanet.ofRadius(r);
            assertEquals(r, p.surface(), 1e-9);
            assertEquals(Material.GRASS, p.get(p.grid.cellAt(new Vector3d(0, r - 0.5, 0))));
            assertEquals(Material.AIR, p.get(p.grid.cellAt(new Vector3d(0, r + 0.5, 0))));
            assertEquals(Material.BEDROCK, p.get(p.grid.index(1, 3, 3, 0)));
            int top = p.grid.index(2, p.grid.n / 2, p.grid.n / 2, p.depth - 1);
            double w = p.grid.corner(top, 0, 0, 1).distance(p.grid.corner(top, 1, 0, 1));
            assertTrue(w > 0.8 && w < 1.25, "radius " + r + ": surface cells " + w + " wide");
        }
        assertThrows(IllegalArgumentException.class, () -> VoxelPlanet.ofRadius(300));
    }

    @Test void onlySurfaceChunksMayShow() {
        VoxelPlanet p = VoxelPlanet.ofRadius(64);
        int shows = 0;
        for (int ch = 0; ch < p.chunkCount(); ch++) {
            boolean faces = !PlanetMesher.quads(p, ch).isEmpty();
            if (faces) assertTrue(p.mayShow(ch), "chunk " + ch + " has faces");
            if (p.mayShow(ch)) shows++;
        }
        assertTrue(shows < p.chunkCount() / 2, shows + " of " + p.chunkCount());
    }

    @Test void chunkSpheresHoldTheirCells() {
        VoxelPlanet p = VoxelPlanet.ofRadius(32);
        Vector3d c = new Vector3d();
        double[] r = new double[1];
        for (int ch = 0; ch < p.chunkCount(); ch += 7) {
            p.sphere(ch, c, r);
            for (int cell : p.cellsOf(ch))
                for (int m = 0; m < 8; m++)
                    assertTrue(p.grid.corner(cell, m & 1, m >> 1 & 1, m >> 2).distance(c) <= r[0] + 1e-6, "chunk " + ch);
        }
    }

    @Test void raycastHitsGrassFromAbove() {
        VoxelPlanet p = VoxelPlanet.standard();
        var hit = PlanetRaycast.cast(p, new Vector3d(0, 18, 0), new Vector3d(0, -1, 0), 4.5);
        assertNotNull(hit);
        assertEquals(Material.GRASS, p.get(hit.hit()));
        assertEquals(p.grid.neighbor(hit.hit(), CubeSphere.TOP), hit.before());
        assertNull(PlanetRaycast.cast(p, new Vector3d(0, 21, 0), new Vector3d(0, -1, 0), 4.5), "out of reach");
        assertNull(PlanetRaycast.cast(p, new Vector3d(0, 18, 0), new Vector3d(0, 1, 0), 4.5));
    }
}
