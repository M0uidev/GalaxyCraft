package dev.moui.galaxycraft.ship;

/** What a ship does, from its modules only. */
public record ShipStats(double speed, double turn, int durability, int seats, int storage) {
    private static final int[] SEATS = {1, 1, 2, 4};
    private static final int[] STORAGE = {0, 0, 9, 27};

    public static ShipStats of(ShipModules m) {
        ShipModules r = m.repaired();
        return new ShipStats(
                0.8 + 0.3 * r.engine(),
                r.rudder() == 0 ? 0.5 : 0.7 + 0.25 * r.rudder(),
                r.hull() == 0 ? 20 : 40 * r.hull(),
                SEATS[r.cabin()],
                STORAGE[r.cabin()]);
    }
}
