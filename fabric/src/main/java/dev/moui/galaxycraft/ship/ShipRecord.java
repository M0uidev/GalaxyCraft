package dev.moui.galaxycraft.ship;

/**
 * A parked ship: its modules, where it is (universe units), how it is turned (a quaternion x, y,
 * z, w: model +x right, +y up, +z the nose) and the stage it was put down in. A landing pad is
 * kept the same way (its id starts with {@link #PAD_PREFIX}; the modules mean nothing there).
 */
public record ShipRecord(String id, ShipModules modules, double[] pos, double[] rot, String stage) {
    public static final String PAD_PREFIX = "pad-";

    public boolean isPad() {
        return id.startsWith(PAD_PREFIX);
    }
}
