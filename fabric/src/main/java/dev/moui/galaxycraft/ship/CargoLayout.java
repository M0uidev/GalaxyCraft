package dev.moui.galaxycraft.ship;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the hold's chests are drawn: in pixels from the cabin's middle (16 a block, nose +z), a 2x2 grid behind
 * the seats at a fifth of a block (the Starshroom is about 80 pixels across; a block drew about 44 of them with the game's chest model). Bays 0 and 1 are side by side
 * (so they join into a double chest across x), 2 and 3 a row behind. Tune the numbers by looking at the ship.
 */
public final class CargoLayout {
    /** Chest scale: a block drawn at this fraction of its size. */
    public static final float SCALE = 0.2f;
    private static final float GAP = 9f; // a scaled chest is about 9 pixels wide in the Starshroom's pixels
    private static final float[][] BAYS = {
        {GAP / 2, 10, -30}, {-GAP / 2, 10, -30}, {GAP / 2, 10, -38.5f}, {-GAP / 2, 10, -38.5f}};

    /** One chest to draw: its middle in pixels and whether it is a double one (then `center` is between its two halves). */
    public record Placed(float[] center, boolean large) {}

    private CargoLayout() {}

    public static List<Placed> chests(ShipCargo cargo) {
        List<Placed> out = new ArrayList<>();
        for (int i = 0; i < ShipCargo.BAYS; i++) {
            ShipCargo.Bay b = cargo.bay(i);
            if (b == ShipCargo.Bay.SMALL) {
                out.add(new Placed(BAYS[i].clone(), false));
            } else if (b == ShipCargo.Bay.LARGE_HEAD) {
                float[] a = BAYS[i], c = BAYS[i + 1];
                out.add(new Placed(new float[] {(a[0] + c[0]) / 2, a[1], a[2]}, true));
            }
        }
        return out;
    }
}
