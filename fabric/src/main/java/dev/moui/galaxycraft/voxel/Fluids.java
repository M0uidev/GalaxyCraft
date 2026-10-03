package dev.moui.galaxycraft.voxel;

import java.util.LinkedHashSet;

/**
 * Water and lava flowing as Minecraft's FlowingFluid does, with "down" toward the planet's center.
 * A fluid cell's level (the high nibble of the cell): 0 a source, 1..7 flowing (amount 8 - level),
 * FALLING fed from above. Each tick a fluid cell recomputes itself from its neighbors, then flows
 * down into air or, if it cannot, sideways toward the nearest drop (within 4 blocks for water, 2
 * for lava). Water ticks every 5 game ticks, lava every 30.
 *
 * Where they meet: lava with water beside or above it turns into obsidian (a source) or
 * cobblestone (flowing); lava falling onto water turns it into stone. Two water sources beside an
 * empty cell over ground fill it with a new source.
 */
public final class Fluids {
    public static final int SOURCE = 0, FALLING = 8;
    public static final int WATER_TICKS = 5, LAVA_TICKS = 30;
    /** Cells ticked per step at most; the rest wait for the next one. */
    public static final int MAX_PER_STEP = 8192;
    private static final int[] SIDES = {CubeSphere.I_MINUS, CubeSphere.I_PLUS, CubeSphere.J_MINUS, CubeSphere.J_PLUS};

    private final VoxelPlanet p;
    private LinkedHashSet<Integer> water = new LinkedHashSet<>(), lava = new LinkedHashSet<>();
    private long ticks;

    Fluids(VoxelPlanet p) {
        this.p = p;
    }

    /** One game tick. True if some cell changed. */
    public boolean tick() {
        ticks++;
        boolean changed = false;
        if (ticks % WATER_TICKS == 0 && !water.isEmpty()) changed |= step(Material.WATER);
        if (ticks % LAVA_TICKS == 0 && !lava.isEmpty()) changed |= step(Material.LAVA);
        return changed;
    }

    /** Cells waiting for their fluid's next step. */
    public int scheduled() {
        return water.size() + lava.size();
    }

    /** A cell changed: it and its fluid neighbors tick next, and lava reacts to water at once. */
    void touched(int cell) {
        schedule(cell);
        for (int s = 0; s < 6; s++) schedule(p.grid.neighbor(cell, s));
        react(cell);
        for (int s = 0; s < 6; s++) react(p.grid.neighbor(cell, s));
    }

    void schedule(int cell) {
        if (cell < 0) return;
        Material m = p.get(cell);
        if (m == Material.WATER) water.add(cell);
        else if (m == Material.LAVA) lava.add(cell);
    }

    private boolean step(Material f) {
        LinkedHashSet<Integer> due = f == Material.WATER ? water : lava;
        LinkedHashSet<Integer> next = new LinkedHashSet<>();
        if (f == Material.WATER) water = next;
        else lava = next;
        boolean changed = false;
        int n = 0;
        for (int cell : due) {
            if (n++ >= MAX_PER_STEP) next.add(cell);
            else changed |= tickCell(f, cell);
        }
        return changed;
    }

    /** Minecraft's FlowingFluid.tick: the new state of a flowing cell, then its spread. */
    private boolean tickCell(Material f, int cell) {
        if (p.get(cell) != f) return false;
        boolean changed = false;
        if (p.level(cell) != SOURCE) {
            int next = newLevel(f, cell);
            if (next != p.level(cell)) {
                changed = true;
                if (next < 0) {
                    p.set(cell, Material.AIR);
                    return true;
                }
                p.set(cell, f, next);
                if (p.get(cell) != f) return true; // reacted with water
            }
        }
        return spread(f, cell) | changed;
    }

    /** FlowingFluid.getNewLiquid: a level from the neighbors, or -1 for nothing. */
    private int newLevel(Material f, int cell) {
        int most = 0, sources = 0;
        for (int s : SIDES) {
            int nb = p.grid.neighbor(cell, s);
            if (p.get(nb) != f) continue;
            if (p.level(nb) == SOURCE) sources++;
            most = Math.max(most, amount(nb));
        }
        if (f == Material.WATER && sources >= 2) {
            int below = p.grid.neighbor(cell, CubeSphere.BOTTOM);
            if (p.get(below).solid() || (p.get(below) == f && p.level(below) == SOURCE)) return SOURCE;
        }
        if (p.get(p.grid.neighbor(cell, CubeSphere.TOP)) == f) return FALLING;
        int a = most - drop(f);
        return a <= 0 ? -1 : 8 - a;
    }

    /** FlowingFluid.spread: down if it can; sideways if it cannot, or if it is a source. */
    private boolean spread(Material f, int cell) {
        int below = p.grid.neighbor(cell, CubeSphere.BOTTOM);
        if (canSpreadInto(f, below, true)) {
            spreadInto(f, below, FALLING, true);
            int sources = 0;
            for (int s : SIDES) {
                int nb = p.grid.neighbor(cell, s);
                if (p.get(nb) == f && p.level(nb) == SOURCE) sources++;
            }
            if (sources >= 3) spreadToSides(f, cell);
            return true;
        }
        if (p.level(cell) == SOURCE || !hole(f, cell)) return spreadToSides(f, cell);
        return false;
    }

    private boolean spreadToSides(Material f, int cell) {
        int a = p.level(cell) == FALLING ? 7 : amount(cell) - drop(f);
        if (a <= 0) return false;
        int best = Integer.MAX_VALUE;
        int[] slope = new int[SIDES.length];
        for (int d = 0; d < SIDES.length; d++) {
            int t = p.grid.neighbor(cell, SIDES[d]);
            slope[d] = Integer.MAX_VALUE;
            if (!passable(f, t)) continue;
            slope[d] = hole(f, t) ? 0 : slopeDistance(f, t, cell, 1);
            best = Math.min(best, slope[d]);
        }
        boolean changed = false;
        for (int d = 0; d < SIDES.length; d++) {
            if (slope[d] != best) continue;
            int t = p.grid.neighbor(cell, SIDES[d]);
            if (canSpreadInto(f, t, false)) {
                spreadInto(f, t, 8 - a, false);
                changed = true;
            }
        }
        return changed;
    }

    /** Steps to the nearest drop from t, coming from prev; 1000 if none within reach. */
    private int slopeDistance(Material f, int t, int prev, int depth) {
        int best = 1000;
        for (int s : SIDES) {
            int u = p.grid.neighbor(t, s);
            if (u == prev || !passable(f, u)) continue;
            if (hole(f, u)) return depth;
            if (depth < slopeReach(f)) best = Math.min(best, slopeDistance(f, u, t, depth + 1));
        }
        return best;
    }

    /** Whether below the cell is somewhere f falls: its own fluid or air. */
    private boolean hole(Material f, int cell) {
        int below = p.grid.neighbor(cell, CubeSphere.BOTTOM);
        if (below < 0) return false;
        Material m = p.get(below);
        return m == f || m == Material.AIR;
    }

    /** Where f can flow through: air, or itself but not a source. */
    private boolean passable(Material f, int cell) {
        if (cell < 0) return false;
        Material m = p.get(cell);
        return m == Material.AIR || (m == f && p.level(cell) != SOURCE);
    }

    /** Fluids push only into air (or lava down into water); existing cells pull on their own tick. */
    private boolean canSpreadInto(Material f, int cell, boolean down) {
        if (cell < 0) return false;
        Material m = p.get(cell);
        return m == Material.AIR || (down && f == Material.LAVA && m == Material.WATER);
    }

    private void spreadInto(Material f, int cell, int level, boolean down) {
        if (down && f == Material.LAVA && p.get(cell) == Material.WATER) p.set(cell, Material.STONE);
        else p.set(cell, f, level);
    }

    /** LiquidBlock.shouldSpreadLiquid: lava touching water (not below it) hardens. */
    private void react(int cell) {
        if (cell < 0 || p.get(cell) != Material.LAVA) return;
        for (int s = 0; s < 6; s++) {
            if (s == CubeSphere.BOTTOM) continue;
            if (p.get(p.grid.neighbor(cell, s)) == Material.WATER) {
                p.set(cell, p.level(cell) == SOURCE ? Material.OBSIDIAN : Material.COBBLESTONE);
                return;
            }
        }
    }

    private int amount(int cell) {
        int l = p.level(cell);
        return l == SOURCE || l == FALLING ? 8 : 8 - l;
    }

    private static int drop(Material f) {
        return f == Material.LAVA ? 2 : 1;
    }

    private static int slopeReach(Material f) {
        return f == Material.LAVA ? 2 : 4;
    }

    /** Height of a fluid cell's surface, blocks (Minecraft's: a source 8/9, full under its own fluid). */
    public static double height(VoxelPlanet p, int cell) {
        Material m = p.get(cell);
        if (!m.fluid()) return 0;
        if (p.get(p.grid.neighbor(cell, CubeSphere.TOP)) == m) return 1;
        int l = p.level(cell);
        return l == FALLING ? 1 : (l == SOURCE ? 8 : 8 - l) / 9.0;
    }
}
