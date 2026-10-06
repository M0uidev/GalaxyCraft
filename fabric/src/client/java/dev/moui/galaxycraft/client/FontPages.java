package dev.moui.galaxycraft.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.moui.galaxycraft.GalaxyCraft;
import java.nio.ByteBuffer;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Minecraft's font pages as the game's textures. A page lives only on the GPU (glyphs are added to
 * it as text first uses them), so it is read back once, and again after a glyph was added to it.
 * Render thread.
 */
public final class FontPages {
    private static final class Page {
        int version, read = -1, reading = -1, skin = -1;
    }

    private static final Map<GpuTexture, Page> pages = new IdentityHashMap<>();

    private FontPages() {}

    /** A glyph was added to the page (FontTextureMixin). */
    public static void changed(GpuTexture texture) {
        pages.computeIfAbsent(texture, t -> new Page()).version++;
    }

    /**
     * The game's texture of a page (made by addSkin from its width, height and ARGB pixels), -1
     * while it is being read; an older copy is used meanwhile once there is one.
     */
    static int skin(GpuTexture texture, BiFunction<int[], int[], Integer> addSkin) {
        Page p = pages.computeIfAbsent(texture, t -> new Page());
        if (p.read != p.version && p.reading < 0) read(texture, p, addSkin); // one read at a time
        return p.skin;
    }

    private static void read(GpuTexture texture, Page p, BiFunction<int[], int[], Integer> addSkin) {
        int version = p.version, w = texture.getWidth(0), h = texture.getHeight(0), bytes = texture.getFormat().blockSize();
        p.reading = version;
        try {
            var device = RenderSystem.getDevice();
            GpuBuffer buffer = device.createBuffer(() -> "galaxycraft font page", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                    (long) w * h * bytes);
            device.createCommandEncoder().copyTextureToBuffer(texture, buffer, 0, () -> {
                try (var view = buffer.map(true, false)) {
                    int[] argb = argb(view.data(), w, h, bytes);
                    int skin = addSkin.apply(new int[] {w, h}, argb);
                    if (skin >= 0) p.skin = skin;
                    GalaxyCraft.LOG.debug("Font page read for the game: {}x{}, {} bytes a texel, skin {}", w, h, bytes, skin);
                } catch (RuntimeException e) {
                    GalaxyCraft.LOG.warn("Cannot read a font page for the game: {}", e.toString());
                } finally {
                    p.read = version;
                    p.reading = -1;
                    buffer.close();
                }
            }, 0);
        } catch (RuntimeException e) {
            GalaxyCraft.LOG.warn("Cannot read a font page for the game: {}", e.toString());
            p.read = version; // not again
            p.reading = -1;
        }
    }

    /** RGBA8 pages (colored glyphs: emoji) as they are, R8 pages (all the rest) as white with that alpha. */
    static int[] argb(ByteBuffer data, int w, int h, int bytes) {
        int[] out = new int[w * h];
        for (int i = 0; i < out.length; i++)
            if (bytes == 1) out[i] = (data.get(i) & 0xFF) << 24 | 0xFFFFFF;
            else {
                int r = data.get(4 * i) & 0xFF, g = data.get(4 * i + 1) & 0xFF, b = data.get(4 * i + 2) & 0xFF, a = data.get(4 * i + 3) & 0xFF;
                out[i] = a << 24 | r << 16 | g << 8 | b;
            }
        return out;
    }
}
