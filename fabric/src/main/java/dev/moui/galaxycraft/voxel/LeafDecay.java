package dev.moui.galaxycraft.voxel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Minecraft's leaf decay on a planet's own cells: a leaf stays while a log is within {@link #REACH}
 * steps through leaves (a leaf next to a log is 1 away; 7 falls). Leaves a player placed
 * (persistent) never fall but carry the distance. Only cells near a change are looked at, so an old
 * planet's leaf with no log is left alone until something changes around it.
 */
public final class LeafDecay {
    public static final int REACH = 6;
    private static final int MAX_PER_TICK = 64, MIN_DELAY = 2, SPREAD = 38;

    private LeafDecay() {}

    /** Whether a log is at most REACH steps from the leaf, through leaves. */
    public static boolean held(VoxelPlanet p, int leaf) {
        Set<Integer> seen = new HashSet<>();
        ArrayDeque<int[]> todo = new ArrayDeque<>();
        todo.add(new int[] {leaf, 0});
        seen.add(leaf);
        while (!todo.isEmpty()) {
            int[] at = todo.poll();
            for (int s = 0; s < 6; s++) {
                int n = p.grid.neighbor(at[0], s);
                if (n < 0 || !seen.add(n)) continue;
                int id = p.get(n);
                if (p.blocks.log(id)) return true; // a log next to a leaf at depth d is d + 1 steps
                if (p.blocks.leaves(id) && at[1] + 1 < REACH) todo.add(new int[] {n, at[1] + 1});
            }
        }
        return false;
    }

    /** Falling leaves among those within REACH of the changed cells (not the persistent ones). */
    public static int[] orphans(VoxelPlanet p, int... changed) {
        Set<Integer> seen = new HashSet<>();
        ArrayDeque<int[]> todo = new ArrayDeque<>();
        for (int c : changed) {
            if (c < 0) continue;
            todo.add(new int[] {c, 0});
            seen.add(c);
        }
        List<Integer> out = new ArrayList<>();
        while (!todo.isEmpty()) {
            int[] at = todo.poll();
            for (int s = 0; s < 6; s++) {
                int n = p.grid.neighbor(at[0], s);
                if (n < 0 || !seen.add(n)) continue;
                int id = p.get(n);
                if (!p.blocks.leaves(id)) continue;
                if (!p.blocks.persistentLeaves(id) && !held(p, n)) out.add(n);
                if (at[1] + 1 < REACH) todo.add(new int[] {n, at[1] + 1});
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Leaves due to fall, each after its own few ticks, so a tree thins out instead of vanishing. */
    public static final class Queue {
        private final Map<Integer, Integer> due = new HashMap<>();
        private final Random random = new Random(0x1EAF);
        private int now;

        /** Cells of p changed: the leaves they no longer hold are scheduled. */
        public void touch(VoxelPlanet p, int... changed) {
            for (int c : orphans(p, changed)) due.putIfAbsent(c, now + MIN_DELAY + random.nextInt(SPREAD));
        }

        /** One tick: the leaves whose time has come and that are still orphans. */
        public int[] tick(VoxelPlanet p) {
            now++;
            if (due.isEmpty()) return new int[0];
            List<Integer> go = new ArrayList<>();
            for (var it = due.entrySet().iterator(); it.hasNext() && go.size() < MAX_PER_TICK; ) {
                var e = it.next();
                if (e.getValue() > now) continue;
                it.remove();
                int id = p.get(e.getKey());
                if (p.blocks.leaves(id) && !p.blocks.persistentLeaves(id) && !held(p, e.getKey())) go.add(e.getKey());
            }
            return go.stream().mapToInt(Integer::intValue).toArray();
        }

        public boolean isEmpty() {
            return due.isEmpty();
        }
    }
}
