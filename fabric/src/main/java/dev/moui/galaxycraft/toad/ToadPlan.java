package dev.moui.galaxycraft.toad;

import dev.moui.galaxycraft.universe.StarType;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Where a planet's Toad outposts are: what a planet of a tier and star gets, decided by its seed
 * alone, so the same planet always gets the same Toads. No Minecraft types.
 *
 * Tier 0 (home and what is near it) has none (the home Guide and station Technicians are fixed
 * elsewhere). Tiers 1 and 2: 0 or 1 outpost, a Miner or an Explorer. Tier 3 and up: 0 to 2, the
 * Mailman too. Blue and lava stars lean to Miners (ores), red dwarfs to Explorers. First draft,
 * tuned by playing.
 */
public final class ToadPlan {
    /** Planets under this radius (blocks) are too small for a hut. */
    public static final int MIN_RADIUS = 48;
    private static final long SALT = 0x7A0AD5E11C0FFEE5L;

    /** One outpost: a stable id, its type, and a unit direction from the planet's center (the hut and the Toad stand there). */
    public record Spot(String id, ToadType type, double[] dir) {}

    private ToadPlan() {}

    /**
     * The outposts of a planet. planetKey names it for good (a sector and the planet's index) and
     * goes into the ids; seed is the planet's own seed; radius in blocks.
     */
    public static List<Spot> outposts(String planetKey, long seed, int radius, int tier, StarType star) {
        List<Spot> out = new ArrayList<>();
        if (tier < 1 || radius < MIN_RADIUS) return out;
        Random rnd = new Random(seed * 0x9E3779B97F4A7C15L ^ SALT);
        int count = rnd.nextInt(tier >= 3 ? 3 : 2);
        for (int n = 0; n < count; n++) {
            out.add(new Spot("toad-" + planetKey + "-" + n, pick(rnd, tier, star), direction(rnd)));
        }
        return out;
    }

    private static ToadType pick(Random rnd, int tier, StarType star) {
        int miner = star == StarType.BLUE || star == StarType.LAVA ? 2 : 1;
        int explorer = star == StarType.RED_DWARF ? 2 : 1;
        int mailman = tier >= 3 ? 1 : 0;
        int roll = rnd.nextInt(miner + explorer + mailman);
        if (roll < miner) return ToadType.MINER;
        if (roll < miner + explorer) return ToadType.EXPLORER;
        return ToadType.MAILMAN;
    }

    /** A uniform direction on the sphere. */
    private static double[] direction(Random rnd) {
        double z = rnd.nextDouble() * 2 - 1, a = rnd.nextDouble() * 2 * Math.PI, r = Math.sqrt(1 - z * z);
        return new double[] {r * Math.cos(a), z, r * Math.sin(a)};
    }
}
