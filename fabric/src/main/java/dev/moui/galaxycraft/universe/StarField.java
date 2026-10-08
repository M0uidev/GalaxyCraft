package dev.moui.galaxycraft.universe;

import java.nio.ByteBuffer;
import java.util.List;
import org.joml.Vector3d;

/**
 * The other solar systems as points of light on the sky (GXC_MSG_STARS): each by its direction from
 * Mario only, so it does not matter where the floating origin is, and drawn at the far plane like
 * the sky. A system's star is bigger with more planets and nearer, its color from its seed (red
 * dwarfs to blue giants, most of them white and yellow). No Minecraft types.
 */
public final class StarField {
    /** Bytes per star: f32 dir[3], f32 size (pixels), u32 rgba. */
    public static final int STAR_BYTES = 20;
    public static final int MAX = 4096;
    /** Pixels across, the smallest and the biggest. */
    public static final float MIN_PX = 1, MAX_PX = 5;
    private static final int[] COLORS = {0xFFB070, 0xFFD8A0, 0xFFF4E0, 0xFFFFFF, 0xE0ECFF, 0xB8D0FF};

    private StarField() {}

    /** A star's light: color by its seed, alpha (brightness) by how far it is. */
    public static int rgba(Universe.Star s, double blocks) {
        int c = COLORS[(int) Math.floorMod(s.seed() >>> 7, (long) COLORS.length)];
        double fade = Math.max(0.35, Math.min(1, 20000 / Math.max(1, blocks)));
        return c << 8 | (int) Math.round(255 * fade);
    }

    /** Pixels across: more planets and nearer is bigger. */
    public static float size(Universe.Star s, double blocks) {
        double px = (0.8 + s.planets() / 6.0) * Math.sqrt(16000 / Math.max(1, blocks));
        return (float) Math.max(MIN_PX, Math.min(MAX_PX, px));
    }

    /**
     * GXC_MSG_STARS (big-endian) for the stars seen from from (universe units), leaving out those
     * nearer than skipBlocks (their planets are drawn themselves). At most MAX, nearest first.
     */
    public static byte[] message(List<Universe.Star> stars, UPos from, double skipBlocks, double unitsPerBlock) {
        int n = 0;
        ByteBuffer b = ByteBuffer.allocate(4 + Math.min(stars.size(), MAX) * STAR_BYTES);
        b.putInt(0);
        for (Universe.Star s : stars) {
            if (n >= MAX) break;
            Vector3d d = s.center().minus(from);
            double blocks = d.length() / unitsPerBlock;
            if (blocks < skipBlocks) continue;
            d.normalize();
            b.putFloat((float) d.x).putFloat((float) d.y).putFloat((float) d.z).putFloat(size(s, blocks)).putInt(rgba(s, blocks));
            n++;
        }
        b.putInt(0, n);
        byte[] out = new byte[4 + n * STAR_BYTES];
        System.arraycopy(b.array(), 0, out, 0, out.length);
        return out;
    }

    /**
     * A planet too small on screen for its far view, as a point of light: where it is, its radius
     * (blocks), its ground's color (0xRRGGBB) and how much of it shows (its system's opening, 0..1).
     */
    public record Dot(UPos center, double radius, int rgb, double weight) {}

    private record Point(double distance, Vector3d dir, float px, int rgba) {}

    /**
     * GXC_MSG_STARS (big-endian) with planets' dots too, nearest first, at most MAX. A star shows
     * as much as its system has not opened into its planets (opened 1: left out).
     */
    public static byte[] message(List<Universe.Star> stars, java.util.function.ToDoubleFunction<Universe.Star> opened, List<Dot> dots,
            UPos from, double unitsPerBlock) {
        java.util.List<Point> points = new java.util.ArrayList<>(stars.size() + dots.size());
        for (Universe.Star st : stars) {
            double shown = 1 - opened.applyAsDouble(st);
            if (shown <= 0) continue;
            Vector3d d = st.center().minus(from);
            double blocks = d.length() / unitsPerBlock;
            int rgba = rgba(st, blocks);
            points.add(new Point(blocks, d.normalize(), size(st, blocks), rgba & ~0xFF | (int) Math.round((rgba & 0xFF) * shown)));
        }
        for (Dot dot : dots) {
            if (dot.weight() <= 0) continue;
            Vector3d d = dot.center().minus(from);
            double blocks = d.length() / unitsPerBlock;
            if (blocks <= dot.radius()) continue;
            // About 0.1 degree a pixel on the game's screen: a dot as wide as the planet looks, 1 to 3 pixels.
            double angle = dev.moui.galaxycraft.voxel.FarSight.angle(dot.radius(), blocks);
            float px = (float) Math.clamp(angle / 0.1, MIN_PX, 3);
            double bright = Math.clamp(0.35 + angle / 0.2 * 0.65, 0.35, 1) * Math.min(1, dot.weight());
            points.add(new Point(blocks, d.normalize(), px, (dot.rgb() & 0xFFFFFF) << 8 | (int) Math.round(255 * bright)));
        }
        points.sort(java.util.Comparator.comparingDouble(Point::distance));
        int n = Math.min(points.size(), MAX);
        ByteBuffer b = ByteBuffer.allocate(4 + n * STAR_BYTES);
        b.putInt(n);
        for (int i = 0; i < n; i++) {
            Point p = points.get(i);
            b.putFloat((float) p.dir().x).putFloat((float) p.dir().y).putFloat((float) p.dir().z).putFloat(p.px()).putInt(p.rgba());
        }
        return b.array();
    }
}
