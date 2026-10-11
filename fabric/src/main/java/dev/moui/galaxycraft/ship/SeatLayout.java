package dev.moui.galaxycraft.ship;

import java.util.ArrayList;
import java.util.List;

/** Where the cabin's cushions are, in pixels from the cabin's middle (nose +z): the driver first, then behind. */
public final class SeatLayout {
    private static final float[][] SLOTS = {{0, 0, 0}, {-14, 0, -8}, {14, 0, -8}, {0, 0, -22}};

    private SeatLayout() {}

    public static List<float[]> seats(int count) {
        int n = Math.max(1, Math.min(SLOTS.length, count));
        List<float[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(SLOTS[i].clone());
        return out;
    }
}
