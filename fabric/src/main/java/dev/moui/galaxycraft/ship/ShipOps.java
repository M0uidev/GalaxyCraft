package dev.moui.galaxycraft.ship;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * What the host does with a friend's request to put a ship or a landing pad down: it checks the numbers,
 * changes its {@link ShipStore} (the one truth) and says what to tell everyone. Pure: no Minecraft in here.
 */
public final class ShipOps {
    public static final int PLACE_SHIP = 0, PLACE_PAD = 1;
    /** The most ships and pads a world keeps, and the longest stage name. */
    public static final int MAX_SHIPS = 256, MAX_NAME = 96;
    /** Coordinates beyond this (universe units) are nonsense. */
    public static final double MAX_COORD = 1e12;

    /** A request: where, how turned (a quaternion x y z w), in which stage, and (for a ship) with which modules. */
    public record Request(int kind, double[] pos, double[] rot, String stage, ShipModules modules) {}

    private ShipOps() {}

    private static boolean finite(double[] v, int n, double max) {
        if (v == null || v.length != n) return false;
        for (double d : v) if (!Double.isFinite(d) || Math.abs(d) > max) return false;
        return true;
    }

    /**
     * Puts the ship or pad down if the request is sound; the stored record, or empty when refused. newId makes
     * the id (the host's, never the guest's). crowdUnits: another of the stage closer than this refuses it.
     */
    public static Optional<ShipRecord> apply(ShipStore store, Request r, Supplier<String> newId, double crowdUnits) {
        if (r.kind() != PLACE_SHIP && r.kind() != PLACE_PAD) return Optional.empty();
        if (!finite(r.pos(), 3, MAX_COORD) || !finite(r.rot(), 4, 2) || r.stage() == null || r.stage().length() > MAX_NAME
                || store.all().size() >= MAX_SHIPS)
            return Optional.empty();
        double len = Math.sqrt(r.rot()[0] * r.rot()[0] + r.rot()[1] * r.rot()[1] + r.rot()[2] * r.rot()[2] + r.rot()[3] * r.rot()[3]);
        if (len < 1e-6) return Optional.empty();
        double[] rot = {r.rot()[0] / len, r.rot()[1] / len, r.rot()[2] / len, r.rot()[3] / len};
        for (ShipRecord o : store.all()) {
            if (!o.stage().equals(r.stage())) continue;
            double x = o.pos()[0] - r.pos()[0], y = o.pos()[1] - r.pos()[1], z = o.pos()[2] - r.pos()[2];
            if (Math.sqrt(x * x + y * y + z * z) < crowdUnits) return Optional.empty();
        }
        ShipRecord rec;
        if (r.kind() == PLACE_PAD) {
            rec = new ShipRecord(ShipRecord.PAD_PREFIX + newId.get(), new ShipModules(0, 1, 0, 1), r.pos().clone(), rot, r.stage());
        } else {
            if (r.modules() == null || !r.modules().valid()) return Optional.empty();
            rec = new ShipRecord(newId.get(), r.modules(), r.pos().clone(), rot, r.stage());
        }
        store.put(rec);
        return Optional.of(rec);
    }
}
