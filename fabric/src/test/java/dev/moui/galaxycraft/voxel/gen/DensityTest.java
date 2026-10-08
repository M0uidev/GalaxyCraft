package dev.moui.galaxycraft.voxel.gen;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.voxel.PlanetBlueprint;
import java.util.Arrays;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class DensityTest {
    static PlanetBlueprint bp(int radius, long seed, String biome, int size) {
        return PlanetBlueprint.standard("g", radius).withMode(PlanetBlueprint.Mode.GENERATED).withBiome(seed, biome, size).withWater(true);
    }

    private static double[] tops(Density d, int n) {
        return Arrays.stream(BiomeLayoutTest.spread(n)).mapToDouble(d::top).sorted().toArray();
    }

    @Test void heightsScaleWithThePlanet() {
        Density.Scale small = Density.scale(32, 8), big = Density.scale(256, 8);
        assertTrue(small.v() < big.v() && big.v() == 1);
        assertTrue(big.depth() > small.depth() && big.air() > small.air());
        assertTrue(big.depth() + big.air() <= 76, "layers at radius 256: " + (big.depth() + big.air()));
        assertEquals(40, Density.scale(64, 40).air(), "a blueprint's own air when it asks for more");
    }

    @Test void plainsAreFlatNearTheBaseSurface() {
        double[] t = tops(new Density(bp(64, 1, "minecraft:plains", 0)), 2000);
        long near = Arrays.stream(t).filter(h -> Math.abs(h) <= 4).count();
        assertTrue(near > t.length * 0.85, near + " of " + t.length);
    }

    @Test void hillsRiseAbovePlains() {
        double plains = Arrays.stream(tops(new Density(bp(128, 2, "minecraft:plains", 0)), 1500)).average().orElseThrow();
        double hills = Arrays.stream(tops(new Density(bp(128, 2, "minecraft:windswept_hills", 0)), 1500)).average().orElseThrow();
        assertTrue(hills > plains + 3, "hills " + hills + ", plains " + plains);
    }

    @Test void deepSeasLieDeep() {
        Density d = new Density(bp(128, 3, PlanetBlueprint.RANDOM, -1));
        double v = d.scale().v();
        double[] deep = Arrays.stream(BiomeLayoutTest.spread(6000)).filter(x -> d.layout().at(x) == LegacyBiome.DEEP_OCEAN)
                .mapToDouble(d::top).sorted().toArray();
        assertTrue(deep.length > 20, deep.length + " deep columns");
        assertTrue(deep[deep.length / 2] <= -0.5 * 28 * v, "median " + deep[deep.length / 2]);
    }

    @Test void topIsWhereTheDensityTurnsSolid() {
        Density d = new Density(bp(64, 4, PlanetBlueprint.RANDOM, -1));
        for (Vector3d x : BiomeLayoutTest.spread(300)) {
            Density.Column c = d.column(x);
            int top = d.top(x);
            assertTrue(d.solid(x, c, top), "solid at the top");
            assertTrue(!d.solid(x, c, top + 1) || top + 1 >= d.scale().air() - 4, "air above");
        }
    }

    @Test void sameSeedSameDensity() {
        Vector3d x = new Vector3d(0.3, 0.5, -0.8).normalize();
        Density a = new Density(bp(64, 5, PlanetBlueprint.RANDOM, -1)), b = new Density(bp(64, 5, PlanetBlueprint.RANDOM, -1));
        assertEquals(a.at(x, a.column(x), 2.5), b.at(x, b.column(x), 2.5));
    }
}
