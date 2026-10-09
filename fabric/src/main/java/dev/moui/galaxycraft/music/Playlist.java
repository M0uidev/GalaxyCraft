package dev.moui.galaxycraft.music;

import java.util.ArrayList;
import java.util.List;

/** Next and previous in the library: which songs to try, in order, so a broken one is stepped over. */
public final class Playlist {
    private Playlist() {}

    /**
     * The enabled songs in the order to try them, starting next to the one with id currentId (null or
     * unknown: the first when going forward, the last going back) and wrapping round, the current
     * song itself last. A song that is disabled still anchors the position.
     */
    public static List<Track> candidates(List<Track> all, String currentId, int dir) {
        int n = all.size();
        int at = -1;
        for (int i = 0; i < n; i++) if (all.get(i).id().equals(currentId)) at = i;
        List<Track> out = new ArrayList<>();
        int step = dir >= 0 ? 1 : -1;
        int first = at < 0 ? (step > 0 ? 0 : n - 1) : Math.floorMod(at + step, n == 0 ? 1 : n);
        for (int k = 0; k < n; k++) {
            Track t = all.get(Math.floorMod(first + k * step, n));
            if (t.enabled()) out.add(t);
        }
        return out;
    }
}
