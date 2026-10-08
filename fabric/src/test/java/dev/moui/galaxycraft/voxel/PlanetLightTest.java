package dev.moui.galaxycraft.voxel;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PlanetLightTest {
    // standard(): grass at layer 8, air from 9 up to 16.
    private final VoxelPlanet p = VoxelPlanet.standard();
    private final CubeSphere g = p.sphere();

    private int at(int i, int j, int k) {
        return g.index(0, i, j, k);
    }

    private int sky(int i, int j, int k) {
        return p.light().sky(at(i, j, k));
    }

    @Test
    void skyLightsTheAirAndNotTheGround() {
        assertEquals(15, sky(12, 12, 9));
        assertEquals(15, sky(12, 12, 16));
        assertEquals(0, sky(12, 12, 5));
        assertEquals(0, p.light().block(at(12, 12, 9)));
    }

    @Test
    void aShaftIsLitStraightDownAndATunnelDarkensAwayFromIt() {
        for (int k = 4; k <= 8; k++) p.set(at(12, 12, k), Material.AIR);
        for (int k = 4; k <= 8; k++) assertEquals(15, sky(12, 12, k), "shaft at " + k);
        for (int i = 13; i <= 16; i++) p.set(at(i, 12, 4), Material.AIR);
        for (int i = 13; i <= 16; i++) assertEquals(15 - (i - 12), sky(i, 12, 4), "tunnel at " + i);
        // A roof over the shaft: no sky gets in any more.
        p.set(at(12, 12, 8), Material.STONE);
        for (int k = 4; k <= 7; k++) assertEquals(0, sky(12, 12, k), "covered shaft at " + k);
        assertEquals(0, sky(16, 12, 4));
        // Open again: as it was.
        p.set(at(12, 12, 8), Material.AIR);
        assertEquals(15, sky(12, 12, 4));
        assertEquals(11, sky(16, 12, 4));
    }

    @Test
    void lavaLightsACaveAndTakesItsLightAlong() {
        for (int i = 10; i <= 16; i++) p.set(at(i, 12, 4), Material.AIR);
        p.set(at(10, 12, 4), Material.LAVA);
        assertEquals(15, p.light().block(at(10, 12, 4)));
        assertEquals(14, p.light().block(at(11, 12, 4)));
        assertEquals(9, p.light().block(at(16, 12, 4)));
        assertEquals(0, p.light().block(at(11, 12, 5)), "not into the stone");
        p.set(at(10, 12, 4), Material.STONE);
        for (int i = 10; i <= 16; i++) assertEquals(0, p.light().block(at(i, 12, 4)), "dark again at " + i);
    }

    @Test
    void waterDampensSkyLight() {
        p.set(at(12, 12, 8), Material.AIR);
        p.set(at(12, 12, 7), Material.AIR);
        p.set(at(12, 12, 8), Material.WATER, Fluids.SOURCE);
        p.set(at(12, 12, 7), Material.WATER, Fluids.SOURCE);
        assertEquals(14, sky(12, 12, 8));
        assertEquals(13, sky(12, 12, 7));
    }

    @Test
    void lightChangesDrawTheirChunksAgain() {
        p.takeDirty();
        for (int i = 10; i <= 20; i++) p.set(at(i, 12, 4), Material.AIR); // dark tunnel: its own chunks
        p.takeDirty();
        p.set(at(10, 12, 4), Material.LAVA);
        int far = p.chunkOf(at(20, 12, 4));
        assertTrue(java.util.Arrays.stream(p.takeDirty()).anyMatch(c -> c == far), "the far end of the tunnel is lit now");
    }

    @Test
    void facesAreDrawnWithTheirLight() {
        int a = PlanetMesher.lightRGBA(15, 0), dark = PlanetMesher.lightRGBA(0, 0), torch = PlanetMesher.lightRGBA(0, 14);
        assertEquals(255, a & 0xFF, "full sky");
        assertTrue((dark & 0xFF) == 0 && (dark >>> 24) <= 16, "a dark cave is near black: " + Integer.toHexString(dark));
        assertTrue((torch >>> 24) > 240 && (torch >>> 8 & 0xFF) < (torch >>> 24), "torch light is warm: " + Integer.toHexString(torch));
        // A cave floor lit by lava: its top faces carry the lava's light, the sky's none.
        for (int i = 10; i <= 14; i++) p.set(at(i, 12, 4), Material.AIR);
        p.set(at(10, 12, 4), Material.LAVA);
        int floor = at(13, 12, 3);
        var q = PlanetMesher.quads(p, p.chunkOf(floor)).stream()
                .filter(x -> x.side() == CubeSphere.TOP && x.levels() != null && java.util.Arrays.stream(x.corners()).anyMatch(v -> v.distance(g.corner(floor, 0, 0, 1)) < 1e-6) && java.util.Arrays.stream(x.corners()).anyMatch(v -> v.distance(g.corner(floor, 1, 1, 1)) < 1e-6))
                .findFirst().orElseThrow();
        for (int v = 0; v < 4; v++) {
            assertEquals(0, q.levels()[v], 1e-9, "no sky in the cave");
            assertTrue(q.levels()[4 + v] > 9 && q.levels()[4 + v] < 14, "lava's light " + q.levels()[4 + v]);
        }
    }
}
