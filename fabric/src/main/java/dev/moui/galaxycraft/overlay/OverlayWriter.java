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

    /** rgba holds width*height*4 bytes from its position; flipRows if its first row is the bottom one. */
    public boolean write(int width, int height, ByteBuffer rgba, boolean flipRows) {
        if (width <= 0 || height <= 0 || width > MAX_W || height > MAX_H) return false;
        long hdr = Layout.OFF_OVERLAY;
        int latest = (int) INT_VH.getAcquire(seg, hdr);
        int next = latest >= 0 && latest <= 2 ? (latest + 1) % 3 : 0;
        long dst = hdr + 32 + next * Layout.OVERLAY_FRAME_BYTES;
        MemorySegment src = MemorySegment.ofBuffer(rgba);
        long row = width * 4L;
        for (int y = 0; y < height; y++) {
            int srcRow = flipRows ? height - 1 - y : y;
            MemorySegment.copy(src, srcRow * row, seg, dst + y * row, row);
        }
        seg.set(INT, hdr + 4, width);
        seg.set(INT, hdr + 8, height);
        seg.set(INT, hdr + 12 + 4L * next, ++frameId);
        INT_VH.setRelease(seg, hdr, next);
        return true;
    }
}
