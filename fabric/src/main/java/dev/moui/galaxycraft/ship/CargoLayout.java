package dev.moui.galaxycraft.ship;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the hold's chests are drawn: in pixels from the cabin's middle (16 a block, nose +z), standing on the
 * cabin's floor against its two side walls, fronts to the middle. Bays 0 and 1 are the left wall (one behind the
 * other, so they join into a double chest along z), 2 and 3 the right wall. The chest model draws about 44 of the
 * Starshroom's pixels a block, hence the small scale. Tune the numbers by looking at the ship.
 */
public final class CargoLayout {
    /** Chest scale: a block drawn at this fraction of its size. */
    public static final float SCALE = 0.52f;
    private static final float SIDE = 25f, FLOOR = -3f, FRONT = -6f, BACK = -29f;
    private static final float[][] BAYS = {
        {-SIDE, FLOOR, FRONT}, {-SIDE, FLOOR, BACK}, {SIDE, FLOOR, FRONT}, {SIDE, FLOOR, BACK}};

    /**
     * One chest to draw: its middle in pixels, whether it is a double one (then `center` is between its two halves)
     * and the turn about the vertical axis, in degrees, from facing the nose to facing the middle of the cabin.
     */
    public record Placed(float[] center, boolean large, float yaw) {}

    private CargoLayout() {}

    public static List<Placed> chests(ShipCargo cargo) {
        List<Placed> out = new ArrayList<>();
        for (int i = 0; i < ShipCargo.BAYS; i++) {
            ShipCargo.Bay b = cargo.bay(i);
            float yaw = i < 2 ? 90f : -90f;
            if (b == ShipCargo.Bay.SMALL) {
                out.add(new Placed(BAYS[i].clone(), false, yaw));
            } else if (b == ShipCargo.Bay.LARGE_HEAD) {
                float[] a = BAYS[i], c = BAYS[i + 1];
                out.add(new Placed(new float[] {a[0], a[1], (a[2] + c[2]) / 2}, true, yaw));
            }
        }
        return out;
    }
}
