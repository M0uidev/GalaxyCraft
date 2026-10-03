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
        assertEquals(2, p.version(p.chunkOf(cell)));
        assertEquals(0, p.takeDirty().length);
        p.set(cell, Material.AIR);
        assertEquals(0, p.takeDirty().length, "no change, nothing to send");
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
