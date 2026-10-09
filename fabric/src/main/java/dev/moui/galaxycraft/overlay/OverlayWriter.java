package dev.moui.galaxycraft.overlay;

import dev.moui.galaxycraft.proto.Layout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Publishes Minecraft's frame (RGBA8) into the shared overlay triple buffer: writes the buffer
 * after the current "latest", then publishes it, so Dolphin never reads a half-written frame.
 */
public final class OverlayWriter {
    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle INT_VH = INT.varHandle();
    private static final int MAX_W = 1920, MAX_H = 1080;

    private final MemorySegment seg;
    private int frameId;

    public OverlayWriter(MemorySegment seg) {
        this.seg = seg;
    }

    /**
     * rgba holds width*height*4 bytes from its position; flipRows if its first row is the bottom one.
     * A frame bigger than the buffer (fullscreen or a 4K window) is scaled down to fit, keeping its
     * aspect ratio, instead of being dropped (Dolphin would stay on its dark placeholder).
     */
    public boolean write(int width, int height, ByteBuffer rgba, boolean flipRows) {
        if (width <= 0 || height <= 0) return false;
        int outW = width, outH = height;
        if (width > MAX_W || height > MAX_H) {
            double k = Math.min((double) MAX_W / width, (double) MAX_H / height);
            outW = Math.max(1, Math.min(MAX_W, (int) (width * k)));
            outH = Math.max(1, Math.min(MAX_H, (int) (height * k)));
        }
        long hdr = Layout.OFF_OVERLAY;
        int latest = (int) INT_VH.getAcquire(seg, hdr);
        int next = latest >= 0 && latest <= 2 ? (latest + 1) % 3 : 0;
        long dst = hdr + 32 + next * Layout.OVERLAY_FRAME_BYTES;
        MemorySegment src = MemorySegment.ofBuffer(rgba);
        long srcRowBytes = width * 4L, outRowBytes = outW * 4L;
        for (int y = 0; y < outH; y++) {
            if (outW == width && outH == height) {
                int srcRow = flipRows ? height - 1 - y : y;
                MemorySegment.copy(src, srcRow * srcRowBytes, seg, dst + y * outRowBytes, outRowBytes);
                continue;
            }
            // Scaled down: each output pixel is the average of the source pixels it covers (picking
            // one of them made text and thin lines jagged).
            int sy0 = (int) ((long) y * height / outH), sy1 = Math.max(sy0 + 1, (int) ((long) (y + 1) * height / outH));
            for (int x = 0; x < outW; x++) {
                int sx0 = (int) ((long) x * width / outW), sx1 = Math.max(sx0 + 1, (int) ((long) (x + 1) * width / outW));
                int r = 0, g = 0, b = 0, a = 0, n = 0;
                for (int yy = sy0; yy < sy1; yy++) {
                    int srcRow = flipRows ? height - 1 - yy : yy;
                    for (int xx = sx0; xx < sx1; xx++) {
                        int px = src.get(INT, srcRow * srcRowBytes + xx * 4L);
                        r += px & 0xFF;
                        g += (px >>> 8) & 0xFF;
                        b += (px >>> 16) & 0xFF;
                        a += px >>> 24;
                        n++;
                    }
                }
                seg.set(INT, dst + y * outRowBytes + x * 4L, (r / n) | (g / n) << 8 | (b / n) << 16 | (a / n) << 24);
            }
        }
        seg.set(INT, hdr + 4, outW);
        seg.set(INT, hdr + 8, outH);
        seg.set(INT, hdr + 12 + 4L * next, ++frameId);
        INT_VH.setRelease(seg, hdr, next);
        return true;
    }
}
