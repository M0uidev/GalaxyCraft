package dev.moui.galaxycraft.voxel;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.joml.Vector3d;

/**
 * A world's planets, decided once when the world is made: from the Create World options and the
 * world's seed, where each planet is, how big, and what it is (generated with a biome, or a
 * blueprint). Nothing of a planet is built here: an entry is its recipe. An entry's index is the
 * planet's for good (its file, PlanetStore.key). No Minecraft types, so it is unit tested.
 */
public final class GalaxyCatalog {
    /** Most planets a galaxy holds. */
    public static final int MAX = 64;
    public static final int MIN_RADIUS = 16, MAX_RADIUS = 256;
    /** Tries to place a planet before it is left out. */
    static final int TRIES = 400;

    /**
     * The world layout new worlds get: 2 spaces planets far apart (and lets other systems reach
     * farther). Worlds saved before keep 1, so their planets stay where their blocks were saved.
     */
    public static final int LAYOUT = 2;

    /** Empty space between two planets' gravities, by layout. */
    public enum Spacing {
        NEAR(24, 150), NORMAL(96, 600), FAR(300, 1500);

        private final int old, wide;

        Spacing(int old, int wide) {
            this.old = old;
            this.wide = wide;
        }

        /** Blocks between gravities in that layout. */
        public int blocks(int layout) {
            return layout >= 2 ? wide : old;
        }
    }

    /** The first planet (where the player starts): generated (biome, or "random"; its radius) or a blueprint by name. */
    public record First(boolean isBlueprint, String name, String biome, int radius) {
        public static First generated(String biome, int radius) {
            return new First(false, null, biome, radius);
        }

        public static First blueprint(String name) {
            return new First(true, name, null, 0);
        }
    }

    public record Options(int count, int minRadius, int maxRadius, First first, Spacing spacing, long seed) {
        public static Options defaults(long seed) {
            return new Options(8, 48, 128, First.generated("random", 48), Spacing.NORMAL, seed);
        }

        /** Within the ranges the tab offers. */
        public Options clamp() {
            int lo = clampRadius(Math.min(minRadius, maxRadius)), hi = clampRadius(Math.max(minRadius, maxRadius));
            First f = first == null ? First.generated("random", 48) : first;
            if (!f.isBlueprint()) f = First.generated(f.biome() == null ? "random" : f.biome(), clampRadius(f.radius()));
            return new Options(Math.max(1, Math.min(MAX, count)), lo, hi, f, spacing == null ? Spacing.NORMAL : spacing, seed);
        }
    }

    public enum Kind { GENERATED, BLUEPRINT }

    /**
     * A planet's recipe: its center (galaxy units), radius (blocks), kind, biome (generated),
     * blueprint name (null for a planet made before catalogs: its file is all there is), seed.
     */
    public record Entry(int index, double x, double y, double z, int radius, Kind kind, String biome, String blueprint, long seed) {
        public Vector3d center() {
            return new Vector3d(x, y, z);
        }
    }

    /** The planets placed, and how many were asked for. */
    public record Result(List<Entry> entries, int asked) {
        public int placed() {
            return entries.size();
        }
    }

    private GalaxyCatalog() {}

    static int clampRadius(int r) {
        return Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, r));
    }

    /**
     * The galaxy: the first planet at the origin (firstRadius: a blueprint's own), then the others
     * in random directions, as near the origin as they fit with spacing between gravities. Biomes
     * of generated ones (and a "random" first) come from land.
     */
    public static Result make(Options options, int firstRadius, List<String> land, double unitsPerBlock) {
        return make(options, firstRadius, land, unitsPerBlock, 1);
    }

    /** As make, in that layout (its spacing). */
    public static Result make(Options options, int firstRadius, List<String> land, double unitsPerBlock, int layout) {
        Options o = options.clamp();
        int spacing = o.spacing().blocks(layout);
        Random rnd = new Random(o.seed());
        List<Entry> out = new ArrayList<>();
        First f = o.first();
        long firstSeed = rnd.nextLong();
        if (f.isBlueprint()) out.add(new Entry(0, 0, 0, 0, clampRadius(firstRadius), Kind.BLUEPRINT, null, f.name(), firstSeed));
        else out.add(new Entry(0, 0, 0, 0, clampRadius(firstRadius), Kind.GENERATED, pick(f.biome(), land, rnd), null, firstSeed));
        double step = (2 * PlanetSession.gravityRadius(o.maxRadius()) + spacing) * unitsPerBlock / 2;
        for (int k = 1; k < o.count(); k++) {
            int radius = o.minRadius() + rnd.nextInt(o.maxRadius() - o.minRadius() + 1);
            String biome = land.get(rnd.nextInt(land.size()));
            long seed = rnd.nextLong();
            double g = PlanetSession.gravityRadius(radius) * unitsPerBlock;
            double base = (PlanetSession.gravityRadius(out.getFirst().radius()) + spacing) * unitsPerBlock + g;
            Vector3d at = null;
            for (int t = 0; t < TRIES && at == null; t++) {
                Vector3d c = direction(rnd).mul(base + t / 25 * step);
                if (fits(out, c, radius, spacing, unitsPerBlock)) at = c;
            }
            if (at != null) out.add(new Entry(out.size(), at.x, at.y, at.z, radius, Kind.GENERATED, biome, null, seed));
        }
        return new Result(List.copyOf(out), o.count());
    }

    /** One more planet (the editor's Create, /galaxycraft planet add): the next index no entry has. */
    public static Entry added(List<Entry> current, Vector3d center, int radius, Kind kind, String biome, String blueprint, long seed) {
        int index = 0;
        for (Entry e : current) index = Math.max(index, e.index() + 1);
        return new Entry(index, center.x, center.y, center.z, radius, kind, biome, blueprint, seed);
    }

    private static String pick(String biome, List<String> land, Random rnd) {
        int r = rnd.nextInt(land.size());
        return biome == null || biome.equals("random") ? land.get(r) : biome;
    }

    private static Vector3d direction(Random rnd) {
        double z = 2 * rnd.nextDouble() - 1, a = 2 * Math.PI * rnd.nextDouble(), s = Math.sqrt(1 - z * z);
        return new Vector3d(s * Math.cos(a), s * Math.sin(a), z);
    }

    private static boolean fits(List<Entry> placed, Vector3d c, int radius, int spacing, double unitsPerBlock) {
        double g = PlanetSession.gravityRadius(radius);
        for (Entry e : placed)
            if (e.center().distance(c) < (PlanetSession.gravityRadius(e.radius()) + g + spacing) * unitsPerBlock) return false;
        return true;
    }
}
