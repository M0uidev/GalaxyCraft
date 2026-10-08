package dev.moui.galaxycraft.voxel.gen;

import dev.moui.galaxycraft.voxel.gen.LegacyBiome.Zone;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.joml.Vector3d;

/**
 * Where a planet's biomes lie, by 1.7's rules on a sphere. The planet is cut into regions (the
 * nearest of a few points spread over it, borders bent by noise): climate zones run from a snowy
 * pole to a warm one and warm never meets snowy; about a third of the regions are sea, deep away
 * from the coast; each land region is one of its zone's biomes, with hills patches; rivers wind
 * across the land and beaches line the sea. A one-biome planet is that biome with its rivers and
 * hills (a sea planet gets islands). Every answer comes from 3D noise at a point on the sphere, so
 * nothing changes at the cube's edges. Safe from several threads once made.
 */
public final class BiomeLayout {
    /** Beach width, blocks. */
    static final double BEACH = 4;
    /** River half width, blocks. */
    static final double RIVER = 2.5;
    /** Deep sea this far into a sea region, in region sizes. */
    static final double DEEP = 0.22;

    private final int radius;
    private final LegacyBiome fixed;
    private final double[][] seeds;
    private final boolean[] ocean;
    private final Zone[] zone;
    private final LegacyBiome[] biome;
    /** A region's size, blocks across. */
    private final double size;
    private final Perlin.Octaves warpX, warpY, warpZ, rivers, hills, islands;

    /** fixed: a one-biome planet's biome id (null: several). biomeSize: -1 Auto (by the radius), else blocks across a region. */
    public BiomeLayout(long seed, int radius, String fixed, int biomeSize) {
        this.radius = radius;
        this.fixed = fixed == null ? null : LegacyBiome.of(fixed);
        if (fixed != null && this.fixed == null) throw new IllegalArgumentException("no biome " + fixed);
        Random rnd = new Random(seed * 0x9E3779B97F4A7C15L + 0x51);
        warpX = new Perlin.Octaves(rnd, 3);
        warpY = new Perlin.Octaves(rnd, 3);
        warpZ = new Perlin.Octaves(rnd, 3);
        rivers = new Perlin.Octaves(rnd, 4);
        hills = new Perlin.Octaves(rnd, 3);
        islands = new Perlin.Octaves(rnd, 3);
        double area = 4 * Math.PI * radius * radius;
        int count = biomeSize < 0 ? Math.clamp(5 + radius / 48, 5, 10)
                : biomeSize == 0 ? 6 : (int) Math.clamp(Math.round(area / ((double) biomeSize * biomeSize)), 2, 64);
        size = Math.sqrt(area / count);
        seeds = spread(rnd, count);
        ocean = new boolean[count];
        zone = new Zone[count];
        biome = new LegacyBiome[count];
        if (count >= 3)
            for (int left = Math.max(1, (int) Math.round(count * 0.3)); left > 0; ) {
                int r = rnd.nextInt(count);
                if (!ocean[r]) {
                    ocean[r] = true;
                    left--;
                }
            }
        Vector3d axis = new Vector3d(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()).normalize();
        for (int r = 0; r < count; r++) {
            double t = axis.x * seeds[r][0] + axis.y * seeds[r][1] + axis.z * seeds[r][2];
            zone[r] = t > 0.5 ? Zone.SNOWY : t > 0.05 ? Zone.COLD : t > -0.45 ? Zone.TEMPERATE : Zone.WARM;
        }
        for (int[] pair : neighbors())
            for (int k = 0; k < 2; k++)
                if (zone[pair[k]] == Zone.SNOWY && zone[pair[1 - k]] == Zone.WARM) zone[pair[k]] = Zone.COLD;
        Set<LegacyBiome> used = new HashSet<>();
        for (int r = 0; r < count; r++) {
            if (ocean[r]) continue;
            List<LegacyBiome> picks = new ArrayList<>(LegacyBiome.PICKS.get(zone[r].ordinal()));
            Collections.shuffle(picks, rnd);
            biome[r] = picks.stream().filter(b -> !used.contains(b)).findFirst().orElse(picks.getFirst());
            used.add(biome[r]);
        }
    }

    /** count points over the sphere, each the farthest of a few tries from those before (Mitchell's best candidate). */
    private static double[][] spread(Random rnd, int count) {
        double[][] out = new double[count][];
        for (int i = 0; i < count; i++) {
            double[] best = null;
            double bestDot = 2;
            for (int t = 0; t < 12; t++) {
                Vector3d c = new Vector3d(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()).normalize();
                double near = -2;
                for (int j = 0; j < i; j++) near = Math.max(near, c.x * out[j][0] + c.y * out[j][1] + c.z * out[j][2]);
                if (near < bestDot) {
                    bestDot = near;
                    best = new double[] {c.x, c.y, c.z};
                }
            }
            out[i] = best;
        }
        return out;
    }

    /** Pairs of regions that touch. */
    private List<int[]> neighbors() {
        Set<Long> seen = new HashSet<>();
        List<int[]> out = new ArrayList<>();
        int n = 6000;
        double golden = Math.PI * (3 - Math.sqrt(5));
        for (int i = 0; i < n; i++) {
            double y = 1 - 2 * (i + 0.5) / n, r = Math.sqrt(1 - y * y);
            Vector3d d = new Vector3d(Math.cos(golden * i) * r, y, Math.sin(golden * i) * r);
            int[] two = nearest(warped(d));
            if (two[2] > size * 0.15) continue; // not near a border
            int a = Math.min(two[0], two[1]), b = Math.max(two[0], two[1]);
            if (seen.add((long) a << 32 | b)) out.add(new int[] {a, b});
        }
        return out;
    }

    /** d bent by the warp noise, unit length. */
    private Vector3d warped(Vector3d d) {
        double s = 4 / size, x = d.x * radius * s, y = d.y * radius * s, z = d.z * radius * s, amp = 0.3 * size / 7;
        return new Vector3d(d).mul(radius).add(warpX.sample(x, y, z) * amp, warpY.sample(x, y, z) * amp, warpZ.sample(x, y, z) * amp)
                .normalize();
    }

    /** The nearest region, the next nearest, and how far w is from their border (blocks, rounded). */
    private int[] nearest(Vector3d w) {
        int a = -1, b = -1;
        double da = -2, db = -2;
        for (int r = 0; r < seeds.length; r++) {
            double dot = w.x * seeds[r][0] + w.y * seeds[r][1] + w.z * seeds[r][2];
            if (dot > da) {
                b = a;
                db = da;
                a = r;
                da = dot;
            } else if (dot > db) {
                b = r;
                db = dot;
            }
        }
        if (b < 0) return new int[] {a, a, Integer.MAX_VALUE};
        double gap = Math.sqrt(sq(seeds[a][0] - seeds[b][0]) + sq(seeds[a][1] - seeds[b][1]) + sq(seeds[a][2] - seeds[b][2]));
        return new int[] {a, b, (int) Math.min(Integer.MAX_VALUE, Math.round((da - db) / gap * radius))};
    }

    private static double sq(double v) {
        return v * v;
    }

    /** The region a direction falls in. */
    int region(Vector3d d) {
        return nearest(warped(d))[0];
    }

    Zone zone(int region) {
        return zone[region];
    }

    /** Noise at a point of the sphere, with features about `across` blocks wide, about -1..1. */
    private double noise(Perlin.Octaves o, int octaves, Vector3d d, double across) {
        double s = radius * (1 << (octaves - 1)) / across;
        return o.sample(d.x * s, d.y * s, d.z * s) / ((1 << octaves) - 1);
    }

    /** The biome in a direction (unit length). */
    public LegacyBiome at(Vector3d d) {
        LegacyBiome base;
        int[] two = null;
        if (fixed != null) {
            if (fixed.ocean()) {
                double n = noise(islands, 3, d, Math.min(size, 48));
                if (n < 0.25) return fixed;
                return n < 0.3 ? fixed.frozen() ? LegacyBiome.SNOWY_BEACH : LegacyBiome.BEACH
                        : fixed.frozen() ? LegacyBiome.SNOWY_PLAINS : LegacyBiome.PLAINS;
            }
            base = fixed;
        } else {
            two = nearest(warped(d));
            int r = two[0];
            if (ocean[r]) {
                boolean deep = two[2] > DEEP * size, frozen = zone[r] == Zone.SNOWY;
                return deep ? frozen ? LegacyBiome.DEEP_FROZEN_OCEAN : LegacyBiome.DEEP_OCEAN
                        : frozen ? LegacyBiome.FROZEN_OCEAN : LegacyBiome.OCEAN;
            }
            base = biome[r];
        }
        double river = noise(rivers, 4, d, size * 1.2);
        if (Math.abs(river) < RIVER / (size * 0.35)) return base.zone() == Zone.SNOWY ? LegacyBiome.FROZEN_RIVER : LegacyBiome.RIVER;
        if (two != null && ocean[two[1]] && two[2] < BEACH)
            return base == LegacyBiome.WINDSWEPT_HILLS ? LegacyBiome.STONY_SHORE
                    : base.zone() == Zone.SNOWY ? LegacyBiome.SNOWY_BEACH : LegacyBiome.BEACH;
        return noise(hills, 3, d, size * 0.5) > 0.3 ? base.hills() : base;
    }

    private static final double[][] RING;

    static {
        // 1.7 blends a 5×5 square of columns around one; a ring of 8 and one of 16 at about the
        // same distances weigh the same and do not depend on which way the square is turned.
        RING = new double[25][];
        RING[0] = new double[] {0, 0, 10 / Math.sqrt(0.2)};
        for (int i = 0; i < 24; i++) {
            boolean inner = i < 8;
            double rho = inner ? 1.2 : 2.3, a = 2 * Math.PI * (inner ? i / 8.0 : (i - 8) / 16.0);
            RING[i + 1] = new double[] {rho * Math.cos(a), rho * Math.sin(a), 10 / Math.sqrt(rho * rho + 0.2)};
        }
    }

    /** 1.7's blended {root, variation} around a direction, its neighbors step blocks apart. */
    public double[] blend(Vector3d d, double step) {
        Vector3d e1 = Math.abs(d.y) < 0.9 ? new Vector3d(d).cross(0, 1, 0).normalize() : new Vector3d(d).cross(1, 0, 0).normalize();
        Vector3d e2 = new Vector3d(d).cross(e1);
        double center = at(d).root(), f = 0, f1 = 0, f2 = 0, s = step / radius;
        Vector3d p = new Vector3d();
        for (double[] o : RING) {
            p.set(d).fma(o[0] * s, e1).fma(o[1] * s, e2).normalize();
            LegacyBiome b = at(p);
            double w = o[2] / (b.root() + 2);
            if (b.root() > center) w /= 2;
            f += b.variation() * w;
            f1 += b.root() * w;
            f2 += w;
        }
        return new double[] {f1 / f2, f / f2};
    }
}
