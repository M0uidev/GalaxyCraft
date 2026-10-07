package dev.moui.galaxycraft.universe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Planet indexes for the planets of generated systems. The world's own galaxy numbers its planets
 * 0, 1, 2... (galaxy.json); a generated system's planet n of sector s gets an index from BASE up the
 * first time it is asked for, the same one for the rest of the run. Indexes are not saved: a planet's
 * file and the player's spot name the sector and n (key, Spot), which are the same in every run.
 */
public final class SystemIndex {
    /** Indexes from here up are generated systems' planets (a home catalog holds at most 64). */
    public static final int BASE = 1 << 16;

    /** Planet n of the system of a sector. */
    public record Ref(Universe.Sector sector, int n) {}

    private static final Map<Ref, Integer> byRef = new HashMap<>();
    private static final List<Ref> byIndex = new ArrayList<>();

    private SystemIndex() {}

    public static synchronized int index(Universe.Sector sector, int n) {
        Ref r = new Ref(sector, n);
        Integer i = byRef.get(r);
        if (i != null) return i;
        byIndex.add(r);
        byRef.put(r, BASE + byIndex.size() - 1);
        return BASE + byIndex.size() - 1;
    }

    public static synchronized Optional<Ref> ref(int index) {
        int k = index - BASE;
        return k >= 0 && k < byIndex.size() ? Optional.of(byIndex.get(k)) : Optional.empty();
    }

    public static boolean generated(int index) {
        return index >= BASE;
    }

    /** "sx_sy_sz" (signed decimals): how files and the spot name a sector. */
    public static String name(Universe.Sector s) {
        return s.x() + "_" + s.y() + "_" + s.z();
    }

    /** The sector a name stands for, if it is one. */
    public static Optional<Universe.Sector> parse(String name) {
        if (name == null) return Optional.empty();
        String[] p = name.split("_");
        if (p.length != 3) return Optional.empty();
        try {
            return Optional.of(new Universe.Sector(Long.parseLong(p[0]), Long.parseLong(p[1]), Long.parseLong(p[2])));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** The planet's file key: <stage>.s<sx>_<sy>_<sz>.p<n> (written only once it is changed). */
    public static String key(String stage, int index) {
        Ref r = ref(index).orElseThrow(() -> new IllegalArgumentException("no planet " + index));
        return stage + ".s" + name(r.sector()) + ".p" + r.n();
    }
}
