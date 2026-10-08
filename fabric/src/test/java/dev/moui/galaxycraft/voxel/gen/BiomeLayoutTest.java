package dev.moui.galaxycraft.voxel.gen;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class BiomeLayoutTest {
    /** n directions spread evenly over the sphere (a Fibonacci spiral). */
    static Vector3d[] spread(int n) {
        Vector3d[] out = new Vector3d[n];
        double golden = Math.PI * (3 - Math.sqrt(5));
        for (int i = 0; i < n; i++) {
            double y = 1 - 2 * (i + 0.5) / n, r = Math.sqrt(1 - y * y);
            out[i] = new Vector3d(Math.cos(golden * i) * r, y, Math.sin(golden * i) * r);
        }
        return out;
    }

    /** A direction about `blocks` away from d on a planet of that radius. */
    static Vector3d step(Vector3d d, double blocks, int radius) {
        Vector3d side = new Vector3d(d).cross(0.3, 0.8, 0.5).normalize();
        return side.mul(blocks / radius).add(d).normalize();
    }

    @Test void sameSeedSameBiomes() {
        BiomeLayout a = new BiomeLayout(7, 64, null, -1), b = new BiomeLayout(7, 64, null, -1);
        for (Vector3d d : spread(500)) assertEquals(a.at(d), b.at(d));
    }

    @Test void warmNeverTouchesSnowy() {
        for (int seed = 0; seed < 25; seed++) {
            BiomeLayout l = new BiomeLayout(seed, 96, null, -1);
            for (Vector3d d : spread(6000)) {
                int a = l.region(d), b = l.region(step(d, 1, 96));
                if (a == b) continue;
                LegacyBiome.Zone za = l.zone(a), zb = l.zone(b);
                assertFalse(za == LegacyBiome.Zone.WARM && zb == LegacyBiome.Zone.SNOWY || zb == LegacyBiome.Zone.WARM && za == LegacyBiome.Zone.SNOWY,
                        "seed " + seed);
            }
        }
    }

    @Test void autoPlanetsHoldSeveralLandBiomesAndSea() {
        for (int radius : new int[] {32, 64, 128, 256})
            for (int seed = 0; seed < 8; seed++) {
                BiomeLayout l = new BiomeLayout(seed, radius, null, -1);
                Set<String> land = new HashSet<>();
                boolean sea = false;
                for (Vector3d d : spread(8000)) {
                    LegacyBiome b = l.at(d);
                    if (b.zone() == LegacyBiome.Zone.WATER) sea |= b.ocean();
                    else if (!b.shore()) land.add(b.id());
                }
                assertTrue(land.size() >= 3, "r " + radius + " seed " + seed + ": " + land);
                assertTrue(sea, "r " + radius + " seed " + seed + " has sea");
            }
    }

    @Test void aOneBiomePlanetIsThatBiomeAndItsRivers() {
        BiomeLayout l = new BiomeLayout(3, 64, "minecraft:desert", 0);
        Set<String> ids = new HashSet<>();
        for (Vector3d d : spread(5000)) ids.add(l.at(d).id());
        assertTrue(ids.contains("minecraft:desert"));
        ids.removeAll(Set.of("minecraft:desert", "minecraft:river"));
        assertEquals(Set.of(), ids);
    }

    @Test void anOceanPlanetHasIslands() {
        BiomeLayout l = new BiomeLayout(4, 64, "minecraft:ocean", 0);
        int land = 0, all = 0;
        for (Vector3d d : spread(5000)) {
            all++;
            if (l.at(d).zone() != LegacyBiome.Zone.WATER) land++;
        }
        assertTrue(land > 0 && land < all / 2, land + " of " + all);
    }

    @Test void blendStaysBetweenTheBiomesItMixes() {
        // Like 1.7's, the blend may step where the column's own biome changes: the density's
        // lattice smooths that out. It never leaves the range of the biomes around.
        BiomeLayout l = new BiomeLayout(5, 64, null, -1);
        for (Vector3d d : spread(3000)) {
            double[] a = l.blend(d, 4);
            assertTrue(a[0] >= -1.8 - 1e-9 && a[0] <= 1.5 + 1e-9, "root " + a[0]);
            assertTrue(a[1] >= 0 && a[1] <= 0.8, "variation " + a[1]);
            assertArrayEquals(a, l.blend(d, 4));
        }
    }

    @Test void everyBiomeHasItsBlocks() {
        for (LegacyBiome b : LegacyBiome.values()) assertNotNull(BiomeSurface.of(b.id()));
        assertTrue(LegacyBiome.land().contains("minecraft:plains"));
        assertTrue(LegacyBiome.all().containsAll(LegacyBiome.land()));
        assertEquals(LegacyBiome.DESERT, LegacyBiome.of("minecraft:desert"));
    }
}
