package dev.moui.galaxycraft.voxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class PlanetDropsTest {
    private final VoxelPlanet p = VoxelPlanet.standard();
    private final Vector3d up = new Vector3d(0.3, 1, 0.2).normalize();

    @Test
    void aDropFallsOntoTheGroundAndStays() {
        PlanetDrops<String> drops = new PlanetDrops<>();
        var d = drops.add(new Vector3d(up).mul(p.surface() + 3), new Vector3d(), "stone", 10);
        for (int t = 0; t < 100; t++) drops.tick(p);
        double h = d.pos.length() - p.surface();
        assertTrue(d.onGround && h >= 0 && h < 0.1, "rests on the grass, " + h + " above");
    }

    @Test
    void aBlockPutOnADropPushesItOut() {
        PlanetDrops<String> drops = new PlanetDrops<>();
        var d = drops.add(new Vector3d(up).mul(p.surface() + 0.05), new Vector3d(), "stone", 0);
        p.set(p.grid.cellAt(d.pos), Material.STONE);
        for (int t = 0; t < 30; t++) drops.tick(p);
        assertTrue(d.pos.length() >= p.surface() + 1, "on top of the new block");
    }

    @Test
    void picksUpOnlyWithinReachAndAfterTheDelay() {
        PlanetDrops<String> drops = new PlanetDrops<>();
        Vector3d feet = new Vector3d(up).mul(p.surface());
        drops.add(new Vector3d(feet), new Vector3d(), "near", 5);
        drops.add(new Vector3d(up).mul(p.surface() + 4), new Vector3d(), "far", 0);
        assertEquals(0, drops.pickUp(feet, up).size(), "still waiting");
        for (int t = 0; t < 5; t++) drops.tick(p);
        var got = drops.pickUp(feet, up);
        assertEquals(1, got.size());
        assertEquals("near", got.get(0).item);
    }
}
