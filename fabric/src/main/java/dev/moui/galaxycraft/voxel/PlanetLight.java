package dev.moui.galaxycraft.voxel;

import java.util.function.IntConsumer;

/**
 * Minecraft's two light levels in every cell of a planet, 0 to 15: sky light, coming in from
 * above the planet's layers ("up" is away from its center), and block light, from blocks that
 * glow (torches, lava, glowstone). Both spread as Minecraft's light engine spreads them: one less
 * per cell, more through blocks that dampen it (water, leaves), not through opaque ones; full sky
 * light goes straight down without loss. Kept up to date as cells change, so a cave is dark, a
 * torch lights it, and a roof shades what is under it.
 */
public final class PlanetLight {
    public static final int MAX = 15;
    private static final int SKY = 0, BLOCK = 1;

    private final VoxelPlanet p;
    private final CellGrid g;
    /** Per cell: sky light in the high nibble, block light in the low one. */
    private final byte[] light;
    /** Per block id, once looked up (0: not yet): 1 << 8 | opacity << 4 | emission, one write (threads share it). */
    private final char[] props = new char[65536];
    private IntConsumer changed = c -> {};

    // Work queues, reused: cells, and levels for the darkening pass.
    private int[] queue = new int[4096], levels = new int[4096];
    private int head, tail;

    PlanetLight(VoxelPlanet p) {
        this.p = p;
        this.g = p.grid;
        this.light = new byte[g.cellCount()];
    }

    /** Told of every cell whose light changes after {@link #computeAll()} (to draw it again). */
    void onChange(IntConsumer changed) {
        this.changed = changed;
    }

    public int sky(int cell) {
        return cell < 0 ? MAX : (light[cell] >> 4) & 0xF;
    }

    public int block(int cell) {
        return cell < 0 ? 0 : light[cell] & 0xF;
    }

    private int get(int ch, int cell) {
        return ch == SKY ? (light[cell] >> 4) & 0xF : light[cell] & 0xF;
    }

    private void put(int ch, int cell, int v) {
        int b = light[cell];
        light[cell] = (byte) (ch == SKY ? (b & 0x0F) | v << 4 : (b & 0xF0) | v);
    }

    private int props(int id) {
        int v = props[id];
        if (v == 0) {
            v = 1 << 8 | Math.clamp(p.blocks.lightBlock(id), 0, MAX) << 4 | Math.clamp(p.blocks.lightEmission(id), 0, MAX);
            props[id] = (char) v;
        }
        return v;
    }

    int opacity(int cell) {
        return props(p.get(cell)) >> 4 & 0xF;
    }

    int emission(int cell) {
        return props(p.get(cell)) & 0xF;
    }

    /** What a cell gets of a channel from its own source: the sky over the top layer, its own glow. */
    private int source(int ch, int cell) {
        if (ch == BLOCK) return emission(cell);
        if (g.k(cell) != g.layers - 1) return 0;
        int op = opacity(cell);
        return op >= MAX ? 0 : MAX - op;
    }

    /** Light from scratch: sky down every column, glowing blocks, then both spread. */
    void computeAll() {
        java.util.Arrays.fill(light, (byte) 0);
        int layers = g.layers;
        head = tail = 0;
        int perFace = light.length / g.faces();
        // Faces apart on their own threads: a big planet has tens of millions of cells.
        java.util.stream.IntStream.range(0, g.faces()).parallel().forEach(f -> {
            for (int base = f * perFace; base < (f + 1) * perFace; base += layers) {
                int l = MAX;
                for (int k = layers - 1; k >= 0 && l > 0; k--) {
                    int op = opacity(base + k);
                    l = op >= MAX ? 0 : l == MAX && op == 0 ? MAX : Math.max(0, l - Math.max(1, op));
                    light[base + k] = (byte) (l << 4);
                }
            }
        });
        // Spread sideways where a lit cell has a darker open neighbor beside it. Full sky light
        // reaches down each column to its "bottom": a cell above its own bottom but below a
        // neighbor column's lights that column's side. Dimmer cells (under water, leaves) are
        // checked one by one.
        int columns = light.length / layers;
        int[] bottom = new int[columns];
        java.util.stream.IntStream.range(0, columns).parallel().forEach(col -> {
            int base = col * layers, k = layers;
            while (k > 0 && get(SKY, base + k - 1) == MAX) k--;
            bottom[col] = k;
        });
        for (int col = 0; col < columns; col++) {
            int base = col * layers, top = bottom[col];
            for (int s = CubeSphere.I_MINUS; s <= CubeSphere.J_PLUS; s++) {
                int nb = g.neighbor(base, s);
                if (nb >= 0) top = Math.max(top, bottom[nb / layers]);
            }
            for (int k = bottom[col]; k < top; k++) push(base + k, MAX);
            for (int k = bottom[col] - 1; k >= 0; k--) {
                int c = base + k, l = get(SKY, c);
                if (l <= 1) break;
                push(c, l);
            }
        }
        spread(SKY, false);
        head = tail = 0;
        int[] glowing = java.util.stream.IntStream.range(0, light.length).parallel().filter(c -> emission(c) > 0).toArray();
        for (int c : glowing) {
            put(BLOCK, c, emission(c));
            push(c, emission(c));
        }
        spread(BLOCK, false);
    }

    /** A cell's block changed (its light passes or glows differently now): light around it anew. */
    void update(int cell) {
        for (int ch = SKY; ch <= BLOCK; ch++) {
            head = tail = 0;
            int old = get(ch, cell);
            if (old > 0) {
                put(ch, cell, 0);
                changed.accept(cell);
                push(cell, old);
                darken(ch);
            }
            head = 0; // darken left the cells to light again (edges of what it cleared) in the queue
            int src = source(ch, cell);
            if (src > get(ch, cell)) {
                put(ch, cell, src);
                changed.accept(cell);
                push(cell, src);
            }
            // Its neighbors shine into it (or no longer can through it): they spread again.
            for (int s = 0; s < 6; s++) {
                int nb = g.neighbor(cell, s);
                if (nb >= 0 && get(ch, nb) > 0) push(nb, get(ch, nb));
            }
            spread(ch, true);
        }
    }

    /**
     * Clears what depended on the light taken out (queued with its old level): neighbors darker
     * than it (or below it, lit straight down by full sky) are cleared in turn; brighter ones, lit
     * from elsewhere, are queued after head to spread back in.
     */
    private void darken(int ch) {
        int[] lit = new int[64];
        int litCount = 0;
        while (head < tail) {
            int c = queue[head], old = levels[head];
            head++;
            for (int s = 0; s < 6; s++) {
                int nb = g.neighbor(c, s);
                if (nb < 0) continue;
                int l = get(ch, nb);
                if (l == 0) continue;
                boolean fed = l < old || ch == SKY && s == CubeSphere.BOTTOM && old == MAX && l == MAX;
                if (fed) {
                    put(ch, nb, 0);
                    changed.accept(nb);
                    push(nb, l);
                    int src = source(ch, nb);
                    if (src > 0) { // a source of its own: back on after the clearing
                        if (litCount == lit.length) lit = java.util.Arrays.copyOf(lit, litCount * 2);
                        lit[litCount++] = nb;
                    }
                } else {
                    if (litCount == lit.length) lit = java.util.Arrays.copyOf(lit, litCount * 2);
                    lit[litCount++] = nb;
                }
            }
        }
        head = tail = 0;
        for (int i = 0; i < litCount; i++) {
            int c = lit[i], src = source(ch, c);
            if (src > get(ch, c)) {
                put(ch, c, src);
                changed.accept(c);
            }
            if (get(ch, c) > 0) push(c, get(ch, c));
        }
    }

    /** Spreads the queued cells' light outward, one less per cell (more through dampening blocks). */
    private void spread(int ch, boolean tell) {
        while (head < tail) {
            int c = queue[head++];
            int l = get(ch, c);
            if (l <= 1) continue;
            for (int s = 0; s < 6; s++) {
                int nb = g.neighbor(c, s);
                if (nb < 0) continue;
                int op = opacity(nb);
                if (op >= MAX) continue;
                int nl = ch == SKY && s == CubeSphere.BOTTOM && l == MAX && op == 0 ? MAX : l - Math.max(1, op);
                if (nl > get(ch, nb)) {
                    put(ch, nb, nl);
                    if (tell) changed.accept(nb);
                    push(nb, nl);
                }
            }
        }
        head = tail = 0;
    }

    private void push(int cell, int level) {
        if (tail == queue.length) {
            if (head > 0) { // reuse the front
                System.arraycopy(queue, head, queue, 0, tail - head);
                System.arraycopy(levels, head, levels, 0, tail - head);
                tail -= head;
                head = 0;
            }
            if (tail == queue.length) {
                queue = java.util.Arrays.copyOf(queue, queue.length * 2);
                levels = java.util.Arrays.copyOf(levels, levels.length * 2);
            }
        }
        queue[tail] = cell;
        levels[tail++] = level;
    }
}
