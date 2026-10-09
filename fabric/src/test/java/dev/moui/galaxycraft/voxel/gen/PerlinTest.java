package dev.moui.galaxycraft.voxel.gen;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class PerlinTest {
    @Test void sameSeedSameNoiseOtherSeedOther() {
        Perlin.Octaves a = new Perlin.Octaves(new Random(5), 16), b = new Perlin.Octaves(new Random(5), 16),
                c = new Perlin.Octaves(new Random(6), 16);
        assertEquals(a.sample(12.3, 4.5, -6.7), b.sample(12.3, 4.5, -6.7));
        assertNotEquals(a.sample(12.3, 4.5, -6.7), c.sample(12.3, 4.5, -6.7));
    }

    @Test void smoothAndWithinItsOctavesReach() {
        Perlin.Octaves o = new Perlin.Octaves(new Random(1), 16);
        Random r = new Random(2);
        double max = 0;
        for (int n = 0; n < 2000; n++) {
            double x = r.nextDouble() * 1000, y = r.nextDouble() * 1000, z = r.nextDouble() * 1000, v = o.sample(x, y, z);
            max = Math.max(max, Math.abs(v));
            assertTrue(Math.abs(v - o.sample(x + 1e-6, y, z)) < 1e-2, "smooth");
        }
        assertTrue(max < 65536 && max > 100, "reach " + max);
    }

    @Test void oneOctaveIsAboutMinusOneToOne() {
        Perlin.Octaves o = new Perlin.Octaves(new Random(3), 1);
        for (int n = 0; n < 1000; n++) assertTrue(Math.abs(o.sample(n * 0.37, n * 0.11, n * 0.53)) <= 1.1);
    }
}
