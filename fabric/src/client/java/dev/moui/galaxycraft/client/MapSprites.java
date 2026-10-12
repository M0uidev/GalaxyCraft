package dev.moui.galaxycraft.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * The map's pictures, made once in white and tinted when drawn: a soft glow, a shaded sphere, a
 * thin ring and a field of dust. One textured quad each, instead of the many rectangles a disc or a
 * ring would take (Minecraft compares every rectangle with the ones before it).
 */
final class MapSprites {
    static final int N = 32;
    private static final int DUST_W = 256, DUST_H = 128;
    private static Identifier glow, sphere, ring, dust;

    private MapSprites() {}

    private static void ensure() {
        if (glow != null) return;
        glow = make("map_glow", N, N, (x, y, d, w, h) -> {
            double a = Math.max(0, 1 - d);
            return argb(a * a, 1);
        });
        // Lit from the upper left: dark side lower right, a small highlight.
        sphere = make("map_sphere", N, N, (x, y, d, w, h) -> {
            double edge = Math.min(1, Math.max(0, (1 - d) * N / 2.0)); // one pixel of soft edge
            if (edge <= 0) return 0;
            double nx = (x + 0.5 - w / 2.0) / (w / 2.0), ny = (y + 0.5 - h / 2.0) / (h / 2.0);
            double nz = Math.sqrt(Math.max(0, 1 - nx * nx - ny * ny));
            double lit = Math.max(0, (-0.42 * nx - 0.5 * ny + 0.76 * nz));
            double shade = Math.min(1, 0.22 + 0.78 * lit + 0.25 * Math.pow(lit, 24));
            return argb(edge, shade);
        });
        ring = make("map_ring", N, N, (x, y, d, w, h) -> {
            double a = 1 - Math.abs(d - 0.9) * N / 3.0;
            return a <= 0 ? 0 : argb(Math.min(1, a), 1);
        });
        dust = make("map_dust", DUST_W, DUST_H, (x, y, d, w, h) -> {
            long s = (x * 73856093L) ^ (y * 19349663L) ^ 0x5bd1e995L;
            s = (s ^ (s >>> 13)) * 0x2545F4914F6CDD1DL;
            double r = ((s >>> 11) & 0xFFFF) / 65536.0;
            return r < 0.012 ? argb(0.15 + 6 * r * 8, 1) : 0;
        });
    }

    private interface Pixel {
        /** d: 0 at the middle, 1 at the edge of the image's inscribed circle; returns ARGB. */
        int at(int x, int y, double d, int w, int h);
    }

    private static int argb(double alpha, double grey) {
        int a = (int) Math.round(Math.min(1, Math.max(0, alpha)) * 255), v = (int) Math.round(Math.min(1, Math.max(0, grey)) * 255);
        return a << 24 | v << 16 | v << 8 | v;
    }

    private static Identifier make(String name, int w, int h, Pixel f) {
        Identifier id = Identifier.fromNamespaceAndPath("galaxycraft", name);
        DynamicTexture t = new DynamicTexture(() -> name, w, h, false);
        var px = t.getPixels();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double dx = (x + 0.5 - w / 2.0) / (w / 2.0), dy = (y + 0.5 - h / 2.0) / (h / 2.0);
                px.setPixel(x, y, f.at(x, y, Math.sqrt(dx * dx + dy * dy), w, h));
            }
        }
        t.upload();
        Minecraft.getInstance().getTextureManager().register(id, t);
        return id;
    }

    /** ARGB with the alpha scaled (0..1). */
    static int tint(int argb, double alpha) {
        int a = (int) Math.round((argb >>> 24) * Math.min(1, Math.max(0, alpha)));
        return a << 24 | (argb & 0xFFFFFF);
    }

    private static void quad(GuiGraphicsExtractor g, Identifier id, int cx, int cy, int size, int w, int h, int color) {
        if (size < 1) return;
        g.blit(RenderPipelines.GUI_TEXTURED, id, cx - size / 2, cy - size / 2, 0f, 0f, size, size, w, h, w, h, color);
    }

    static void glow(GuiGraphicsExtractor g, int cx, int cy, int size, int color) {
        ensure();
        quad(g, glow, cx, cy, size, N, N, color);
    }

    static void sphere(GuiGraphicsExtractor g, int cx, int cy, int size, int color) {
        ensure();
        quad(g, sphere, cx, cy, size, N, N, color);
    }

    static void ring(GuiGraphicsExtractor g, int cx, int cy, int size, int color) {
        ensure();
        quad(g, ring, cx, cy, size, N, N, color);
    }

    /** The dust over a rectangle (gui units). */
    static void dust(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        ensure();
        g.blit(RenderPipelines.GUI_TEXTURED, dust, x, y, 0f, 0f, w, h, DUST_W, DUST_H, DUST_W, DUST_H, color);
    }
}
