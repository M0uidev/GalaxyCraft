package dev.moui.galaxycraft.voxel.gen;

import java.util.Random;

/**
 * Minecraft 1.7's noise: Ken Perlin's improved noise ({@code NoiseGeneratorImproved}) shuffled and
 * offset by a Random, summed in octaves as {@code NoiseGeneratorOctaves} does: the first octave at
 * the coordinates as given and weight 1, each next one at half the frequency and twice the weight.
 * Safe from several threads once made.
 */
public final class Perlin {
    private static final double[][] GRAD = {{1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0}, {1, 0, 1}, {-1, 0, 1},
            {1, 0, -1}, {-1, 0, -1}, {0, 1, 1}, {0, -1, 1}, {0, 1, -1}, {0, -1, -1}, {1, 1, 0}, {0, -1, 1}, {-1, 1, 0},
            {0, -1, -1}};
    private final int[] perm = new int[512];
    private final double ox, oy, oz;

    Perlin(Random rnd) {
        ox = rnd.nextDouble() * 256;
        oy = rnd.nextDouble() * 256;
        oz = rnd.nextDouble() * 256;
        for (int i = 0; i < 256; i++) perm[i] = i;
        for (int i = 0; i < 256; i++) {
            int j = rnd.nextInt(256 - i) + i, t = perm[i];
            perm[i] = perm[j];
            perm[j] = t;
            perm[i + 256] = perm[i];
        }
    }

    /** About -1..1. */
    double value(double x, double y, double z) {
        x += ox;
        y += oy;
        z += oz;
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y), zi = (int) Math.floor(z);
        x -= xi;
        y -= yi;
        z -= zi;
        xi &= 255;
        yi &= 255;
        zi &= 255;
        double u = fade(x), v = fade(y), w = fade(z);
        int a = perm[xi] + yi, aa = perm[a] + zi, ab = perm[a + 1] + zi, b = perm[xi + 1] + yi, ba = perm[b] + zi,
                bb = perm[b + 1] + zi;
        return lerp(w, lerp(v, lerp(u, grad(perm[aa], x, y, z), grad(perm[ba], x - 1, y, z)),
                        lerp(u, grad(perm[ab], x, y - 1, z), grad(perm[bb], x - 1, y - 1, z))),
                lerp(v, lerp(u, grad(perm[aa + 1], x, y, z - 1), grad(perm[ba + 1], x - 1, y, z - 1)),
                        lerp(u, grad(perm[ab + 1], x, y - 1, z - 1), grad(perm[bb + 1], x - 1, y - 1, z - 1))));
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    private static double grad(int h, double x, double y, double z) {
        double[] g = GRAD[h & 15];
        return g[0] * x + g[1] * y + g[2] * z;
    }

    /** Several octaves, as 1.7's NoiseGeneratorOctaves. */
    public static final class Octaves {
        private final Perlin[] octaves;

        public Octaves(Random rnd, int count) {
            octaves = new Perlin[count];
            for (int i = 0; i < count; i++) octaves[i] = new Perlin(rnd);
        }

        public double sample(double x, double y, double z) {
            double sum = 0, f = 1;
            for (Perlin p : octaves) {
                sum += p.value(x * f, y * f, z * f) / f;
                f /= 2;
            }
            return sum;
        }
    }
}
