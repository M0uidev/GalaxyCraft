package dev.moui.galaxycraft.launchstar;

import java.util.function.Supplier;

/**
 * What the host does with a friend's request about a Launch Star: it checks the numbers, changes its
 * {@link StarStore} (the one truth) and says what to tell everyone. Pure: no Minecraft in here.
 */
public final class StarOps {
    public static final int PLACE = 0, PICKUP = 1, CODE = 2;
    /** The most stars a world keeps, and the longest names for the stage, the body and the id. */
    public static final int MAX_STARS = 256, MAX_NAME = 96;
    /** Coordinates beyond this (universe units) are nonsense. */
    public static final double MAX_COORD = 1e12;

    /** A request: for PLACE the position, up, stage, body and (optional) code; for PICKUP and CODE the id (CODE: the code too). */
    public record Request(int kind, String id, double[] pos, double[] up, String stage, String body, String code) {}

    public enum Kind { PUT, GONE, REJECT }

    /** PUT: tell everyone this star; GONE: tell everyone this (removed) star is gone; REJECT: nothing changed. */
    public record Result(Kind kind, StarRecord star) {
        static final Result REJECT = new Result(Kind.REJECT, null);
    }

    private StarOps() {}

    private static boolean finite(double[] v, double max) {
        if (v == null || v.length != 3) return false;
        for (double d : v) if (!Double.isFinite(d) || Math.abs(d) > max) return false;
        return true;
    }

    private static boolean name(String s) {
        return s != null && s.length() <= MAX_NAME;
    }

    /**
     * Applies the request to the store. newId makes the id of a placed star (the host's, never the guest's).
     * crowdUnits: a star closer than this to another of the stage is refused (universe units).
     */
    public static Result apply(StarStore store, Request r, Supplier<String> newId, double crowdUnits) {
        switch (r.kind()) {
            case PLACE -> {
                if (!finite(r.pos(), MAX_COORD) || !finite(r.up(), 1e3) || !name(r.stage()) || !name(r.body())
                        || r.code() != null && !name(r.code()) || store.all().size() >= MAX_STARS)
                    return Result.REJECT;
                double len = Math.sqrt(r.up()[0] * r.up()[0] + r.up()[1] * r.up()[1] + r.up()[2] * r.up()[2]);
                if (len < 1e-6) return Result.REJECT;
                double[] up = {r.up()[0] / len, r.up()[1] / len, r.up()[2] / len};
                for (StarRecord o : store.all())
                    if (o.stage().equals(r.stage()) && dist(o.pos(), r.pos()) < crowdUnits) return Result.REJECT;
                StarRecord stored = store.place(new StarRecord(newId.get(), r.pos().clone(), up, r.stage(), r.body(),
                        r.code() == null || r.code().isBlank() ? null : r.code()));
                return new Result(Kind.PUT, stored);
            }
            case PICKUP -> {
                if (!name(r.id())) return Result.REJECT;
                return store.remove(r.id()).map(x -> new Result(Kind.GONE, x)).orElse(Result.REJECT);
            }
            case CODE -> {
                if (!name(r.id()) || r.code() == null || StarStore.clean(r.code()).isEmpty() || store.get(r.id()) == null) return Result.REJECT;
                return new Result(Kind.PUT, store.setCode(r.id(), r.code()));
            }
            default -> {
                return Result.REJECT;
            }
        }
    }

    static double dist(double[] a, double[] b) {
        double x = a[0] - b[0], y = a[1] - b[1], z = a[2] - b[2];
        return Math.sqrt(x * x + y * y + z * z);
    }
}
