package dev.moui.galaxycraft.voxel.gen;

import dev.moui.galaxycraft.voxel.PlanetBlueprint;
import java.util.Random;
import org.joml.Vector3d;

/**
 * Minecraft 1.7's terrain density ({@code ChunkProviderGenerate}) on a planet: ground where it is
 * positive. A point of the planet (a direction, and a height in blocks above the base surface) is
 * a point of a virtual 1.7 world: its distance along the surface, divided by the horizontal scale,
 * is 1.7's x and z, and its height, divided by the vertical scale, is 1.7's y above the sea (63).
 * The noises are sampled at the direction times a length that grows with the height, so they are
 * 3D noise at a point in space (no seams on the cube's edges) whose radial frequency is 1.7's
 * vertical one and tangential frequency its horizontal one. Safe from several threads.
 */
public final class Density {
    /**
     * How a planet fits 1.7's heights. v: blocks of planet per block of 1.7's height; hs: the same
     * along the ground; depth: ground under the base surface, bedrock included; air: room above.
     */
    public record Scale(double v, double hs, int depth, int air) {}

    /** The base of 1.7's sea, y. */
    static final double SEA = 63;
    private static final double MAIN = 684.412;

    private final int radius;
    private final Scale scale;
    private final BiomeLayout layout;
    private final Perlin.Octaves min, max, main, depthNoise;

    public static Scale scale(int radius, int air) {
        double v = Math.clamp(radius / 256.0, 0.1, 1);
        int depth = Math.min(radius - 2, Math.clamp((int) Math.ceil(30 * v) + Math.max(6, radius / 8), 3, 40));
        return new Scale(v, Math.sqrt(v), depth, Math.max(air, Math.clamp((int) Math.ceil(60 * v) + 8, 8, 48)));
    }

    public Density(PlanetBlueprint bp) {
        radius = bp.radius();
        scale = scale(radius, bp.air());
        Random rnd = new Random(bp.seed());
        min = new Perlin.Octaves(rnd, 16);
        max = new Perlin.Octaves(rnd, 16);
        main = new Perlin.Octaves(rnd, 8);
        depthNoise = new Perlin.Octaves(rnd, 16);
        layout = new BiomeLayout(bp.seed(), radius, bp.biomeSize() == 0 ? PlanetGenerator.biome(bp) : null, bp.biomeSize());
    }

    public Scale scale() {
        return scale;
    }

    public BiomeLayout layout() {
        return layout;
    }

    /** What 1.7 works out once per column: where its ground lies (in its lattice's y, 8 blocks) and how far it spreads. */
    public record Column(double base, double spread) {}

    /** 1.7's column values for a direction (unit length). */
    public Column column(Vector3d d) {
        double[] blend = layout.blend(d, 4 * scale.hs());
        double spread = blend[1] * 0.9 + 0.1, root = (blend[0] * 4 - 1) / 8;
        // 1.7 samples its depth noise at x·200 on a lattice 4 blocks apart.
        double s = radius / scale.hs() * 200 / 4;
        double n = depthNoise.sample(d.x * s, d.y * s, d.z * s) / 8000;
        if (n < 0) n = -n * 0.3;
        n = n * 3 - 2;
        if (n < 0) n = Math.max(n / 2, -1) / 1.4 / 2;
        else n = Math.min(n, 1) / 8;
        double base = (root + n * 0.2) * 8.5 / 8;
        return new Column(8.5 + base * 4, spread);
    }

    /** The density at hb blocks above the base surface: ground where positive. */
    public double at(Vector3d d, Column c, double hb) {
        if (hb < 2 - scale.depth()) return 1; // the bedrock and the block over it
        double y = SEA + hb / scale.v(), j = y / 8;
        double falloff = (j - c.base()) * 6 / c.spread();
        if (falloff < 0) falloff *= 4;
        double along = radius / scale.hs(), up = hb / scale.v();
        double t = (sample(main, d, along * MAIN / 80 / 4, up * MAIN / 160 / 8) / 10 + 1) / 2, v;
        if (t <= 0) v = sample(min, d, along * MAIN / 4, up * MAIN / 8) / 512;
        else if (t >= 1) v = sample(max, d, along * MAIN / 4, up * MAIN / 8) / 512;
        else {
            double lo = sample(min, d, along * MAIN / 4, up * MAIN / 8) / 512, hi = sample(max, d, along * MAIN / 4, up * MAIN / 8) / 512;
            v = lo + (hi - lo) * t;
        }
        v -= falloff;
        // The sky's room runs out: the ground fades away over the last blocks below it, as 1.7's
        // does at the top of the world.
        double fade = (hb - (scale.air() - 8)) / 4;
        if (fade > 0) v = fade >= 1 ? -10 : v * (1 - fade) - 10 * fade;
        return v;
    }

    private static double sample(Perlin.Octaves o, Vector3d d, double along, double up) {
        double r = along + up;
        return o.sample(d.x * r, d.y * r, d.z * r);
    }

    /** Whether the cell whose top is height blocks above the base surface is ground. */
    public boolean solid(Vector3d d, Column c, int height) {
        return at(d, c, height - 0.5) > 0;
    }

    /** The height of the highest ground in a direction (blocks above the base surface, negative below). */
    public int top(Vector3d d) {
        Column c = column(d);
        int h = scale.air() - 4;
        while (h > 2 - scale.depth() && !solid(d, c, h)) h -= 4;
        int up = Math.min(h + 3, scale.air() - 4);
        while (up > h && !solid(d, c, up)) up--;
        return Math.max(up, 2 - scale.depth());
    }
}
