package dev.moui.galaxycraft.universe;

import dev.moui.galaxycraft.voxel.GalaxyCatalog;
import dev.moui.galaxycraft.voxel.PlanetSession;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * An endless universe of solar systems, decided by the world's seed and nothing else: space is cut
 * into sectors (cubes of SECTOR_CELLS cells, 8192 blocks), and each holds at most one system, its
 * center somewhere inside, with as much room around it that two systems' gravities never meet,
 * whatever their sectors. Whether a sector has one, where, and its planets come from a hash of
 * the seed and the sector's coordinates (longs: no far lands, no edge), so any sector is made
 * without looking at another one, and the same one is always the same. Nothing is stored for a
 * sector nobody changed.
 *
 * The world's own galaxy (the Create World catalog, galaxy.json) is the system of sector (0, 0, 0),
 * centered on the universe's (0, 0, 0) as it always was: worlds of before carry over as they are.
 * No Minecraft types, so it is unit tested.
 */
public final class Universe {
    /** A sector's side, cells (8192 blocks). Sector s spans s * SECTOR_CELLS ± SECTOR_CELLS / 2 cells. */
    public static final long SECTOR_CELLS = 10;
    public static final double SECTOR = SECTOR_CELLS * UPos.CELL;
    /** A system's planets and their gravities reach this far from its center, blocks at most. */
    public static final double SYSTEM_BLOCKS = 2560;
    /** Empty space between two systems' reaches, blocks at least. */
    public static final double GAP_BLOCKS = 512;
    /** Share of the sectors that hold a system. */
    public static final double DENSITY = 0.6;
    /** Planets of a generated system, and their radius (blocks). */
    public static final int MIN_PLANETS = 2, MAX_PLANETS = 12;
    /** Systems' contents kept made (each a few dozen entries). */
    static final int CACHE = 32;

    public record Sector(long x, long y, long z) {
        public static final Sector HOME = new Sector(0, 0, 0);

        /** The sector's center: the cell corner its system's center is jittered around. */
        public UPos center() {
            return new UPos(x * SECTOR_CELLS, y * SECTOR_CELLS, z * SECTOR_CELLS, 0, 0, 0);
        }
    }

    /**
     * A system as seen from afar, made from the sector's hash alone: where it is, how many planets
     * it holds, and their sizes. Enough to draw it as a star and to pick where to travel.
     * seed: its planets' (GalaxyCatalog's) seed.
     */
    public record Star(Sector sector, UPos center, long seed, int planets, int minRadius, int maxRadius,
            GalaxyCatalog.Spacing spacing) {
        public boolean home() {
            return sector.equals(Sector.HOME);
        }
    }

    private final long seed;
    private final double unitsPerBlock;
    /**
     * How far the world's own galaxy reaches from (0, 0, 0), blocks: its catalog may be bigger than a
     * generated system (64 planets, far apart). No other system comes within GAP_BLOCKS of it.
     */
    private double homeReach = SYSTEM_BLOCKS;
    private final Map<Sector, GalaxyCatalog.Result> systems = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Sector, GalaxyCatalog.Result> e) {
            return size() > CACHE;
        }
    };

    public Universe(long seed, double unitsPerBlock) {
        this.seed = seed;
        this.unitsPerBlock = unitsPerBlock;
    }

    /** The world's galaxy reaches this far (blocks; at least a system's reach). */
    public Universe withHome(double reachBlocks) {
        homeReach = Math.max(SYSTEM_BLOCKS, reachBlocks);
        systems.clear();
        return this;
    }

    /** How far a system reaches from its center, blocks. */
    public double reach(Star s) {
        return s.home() ? homeReach : SYSTEM_BLOCKS;
    }

    /** How far a system's center strays from its sector's, units on each axis at most. */
    double jitter() {
        return SECTOR / 2 - (SYSTEM_BLOCKS + GAP_BLOCKS / 2) * unitsPerBlock;
    }

    /** The sector p is in. */
    public static Sector sectorOf(UPos p) {
        long h = SECTOR_CELLS / 2;
        return new Sector(Math.floorDiv(p.cx() + h, SECTOR_CELLS), Math.floorDiv(p.cy() + h, SECTOR_CELLS),
                Math.floorDiv(p.cz() + h, SECTOR_CELLS));
    }

    /** The sector's system, if it has one. Home always has one, at the universe's (0, 0, 0). */
    public Optional<Star> star(Sector s) {
        Random rnd = new Random(hash(seed, s.x(), s.y(), s.z()));
        boolean home = s.equals(Sector.HOME);
        if (!home && rnd.nextDouble() >= DENSITY) return Optional.empty();
        double j = jitter();
        double ox = home ? 0 : (2 * rnd.nextDouble() - 1) * j, oy = home ? 0 : (2 * rnd.nextDouble() - 1) * j,
                oz = home ? 0 : (2 * rnd.nextDouble() - 1) * j;
        UPos c = s.center();
        UPos center = UPos.of(c.cx(), c.cy(), c.cz(), ox, oy, oz);
        // A big world's galaxy takes its neighbors' room.
        if (!home && center.minus(UPos.ZERO).length() < (homeReach + SYSTEM_BLOCKS + GAP_BLOCKS) * unitsPerBlock)
            return Optional.empty();
        int planets = MIN_PLANETS + rnd.nextInt(MAX_PLANETS - MIN_PLANETS + 1);
        int a = GalaxyCatalog.MIN_RADIUS + rnd.nextInt(GalaxyCatalog.MAX_RADIUS - GalaxyCatalog.MIN_RADIUS + 1);
        int b = GalaxyCatalog.MIN_RADIUS + rnd.nextInt(GalaxyCatalog.MAX_RADIUS - GalaxyCatalog.MIN_RADIUS + 1);
        GalaxyCatalog.Spacing[] sp = GalaxyCatalog.Spacing.values();
        return Optional.of(new Star(s, center, rnd.nextLong(), planets, Math.min(a, b), Math.max(a, b), sp[rnd.nextInt(sp.length)]));
    }

    /**
     * The systems whose sectors are within radius sectors of p's (a cube), nearest first: the
     * stars to draw, the places to travel to. (2 radius + 1)^3 hashes, made again only when the
     * player changes sector.
     */
    public List<Star> around(UPos p, int radius) {
        Sector at = sectorOf(p);
        List<Star> out = new ArrayList<>();
        for (long x = at.x() - radius; x <= at.x() + radius; x++)
            for (long y = at.y() - radius; y <= at.y() + radius; y++)
                for (long z = at.z() - radius; z <= at.z() + radius; z++) star(new Sector(x, y, z)).ifPresent(out::add);
        out.sort(Comparator.comparingDouble(s -> s.center().minus(p).lengthSquared()));
        return out;
    }

    /**
     * The system p is in (within its reach plus margin blocks of its center), if any: the world's
     * galaxy, or the system of p's own sector (every generated system stays inside its sector).
     */
    public Optional<Star> systemAt(UPos p, double marginBlocks) {
        // The world's galaxy may reach past its own sector; every other system stays inside its own.
        java.util.function.Predicate<Star> in = s -> {
            double reach = (reach(s) + marginBlocks) * unitsPerBlock;
            return s.center().minus(p).lengthSquared() <= reach * reach;
        };
        Optional<Star> home = star(Sector.HOME).filter(in);
        return home.isPresent() ? home : star(sectorOf(p)).filter(in);
    }

    /**
     * A generated system's planets: the world's catalog recipe (GalaxyCatalog) with the star's
     * count, sizes, spacing and seed, centers relative to the star's center (units); planets that
     * would reach past SYSTEM_BLOCKS are left out. The first is at the center. Not for home (its
     * planets are the world's galaxy.json). Kept made for the last CACHE systems asked (one thread
     * at a time: the cache is not synchronized).
     */
    public GalaxyCatalog.Result system(Star star, List<String> land) {
        if (star.home()) throw new IllegalArgumentException("home's planets are the world's catalog");
        return systems.computeIfAbsent(star.sector(), k -> {
            Random rnd = new Random(star.seed());
            int first = star.minRadius() + rnd.nextInt(star.maxRadius() - star.minRadius() + 1);
            var options = new GalaxyCatalog.Options(star.planets(), star.minRadius(), star.maxRadius(),
                    GalaxyCatalog.First.generated("random", first), star.spacing(), star.seed());
            var made = GalaxyCatalog.make(options, first, land, unitsPerBlock);
            double reach = SYSTEM_BLOCKS * unitsPerBlock;
            List<GalaxyCatalog.Entry> kept = new ArrayList<>();
            for (var e : made.entries())
                if (e.center().length() + PlanetSession.gravityRadius(e.radius()) * unitsPerBlock <= reach) kept.add(e);
            return new GalaxyCatalog.Result(List.copyOf(kept), made.asked());
        });
    }

    /** SplitMix64's finalizer: every bit of the input moves about half the output's. */
    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** The sector's own seed: integers only, the same on every machine and at any distance. */
    static long hash(long seed, long x, long y, long z) {
        final long golden = 0x9E3779B97F4A7C15L;
        return mix(seed + golden * mix(x + golden * mix(y + golden * mix(z + golden))));
    }
}
