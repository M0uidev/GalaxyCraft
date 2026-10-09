package dev.moui.galaxycraft.music;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Picks songs at random, each once per round, never the one that just played. */
public final class ShuffleBag {
    private final Set<String> played = new HashSet<>();
    private String last;

    public Track next(List<Track> pool, Random random) {
        if (pool.isEmpty()) return null;
        List<Track> fresh = pool.stream().filter(t -> !played.contains(t.id())).toList();
        if (fresh.isEmpty()) {
            played.clear();
            fresh = pool;
        }
        List<Track> choices = fresh.stream().filter(t -> !t.id().equals(last)).toList();
        if (choices.isEmpty()) choices = fresh;
        Track pick = choices.get(random.nextInt(choices.size()));
        played.add(pick.id());
        last = pick.id();
        return pick;
    }
}
