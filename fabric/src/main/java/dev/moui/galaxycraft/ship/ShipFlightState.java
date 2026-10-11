package dev.moui.galaxycraft.ship;

/**
 * The ship the player is aboard, if any: the client sets it, and the glide check (PlayerGlideMixin)
 * reads it on both the client's and the integrated server's player, which share this JVM.
 */
public final class ShipFlightState {
    private static volatile ShipStats boarded;

    private ShipFlightState() {}

    public static ShipStats boarded() {
        return boarded;
    }

    public static void set(ShipStats stats) {
        boarded = stats;
    }
}
