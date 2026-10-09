package dev.moui.galaxycraft.overlay;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.proto.Layout;
import dev.moui.galaxycraft.proto.Shm;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OverlayWriterTest {
    static final ValueLayout.OfInt I = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    @TempDir Path dir;

    static ByteBuffer image(int w, int h) {
        ByteBuffer b = ByteBuffer.allocateDirect(w * h * 4);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) b.put((byte) y).put((byte) x).put((byte) 0).put((byte) 255);
        return b.flip();
    }

    static long pixels(int index) {
        return Layout.OFF_OVERLAY + 32 + index * Layout.OVERLAY_FRAME_BYTES;
    }

    @Test void rotatesBuffersAndNeverWritesLatest() throws Exception {
        MemorySegment s = Shm.create(dir.resolve("shm")).seg();
        var w = new OverlayWriter(s);
        int[] expected = {0, 1, 2, 0};
        for (int i = 0; i < expected.length; i++) {
            int before = s.get(I, Layout.OFF_OVERLAY);
            assertTrue(w.write(4, 2, image(4, 2), false));
            int latest = s.get(I, Layout.OFF_OVERLAY);
            assertEquals(expected[i], latest);
            assertNotEquals(before, latest);
            assertEquals(i + 1, s.get(I, Layout.OFF_OVERLAY + 12 + 4L * latest), "frame id");
        }
        assertEquals(4, s.get(I, Layout.OFF_OVERLAY + 4));
        assertEquals(2, s.get(I, Layout.OFF_OVERLAY + 8));
    }

    @Test void flipsRowsWhenAsked() throws Exception {
        MemorySegment s = Shm.create(dir.resolve("shm")).seg();
        new OverlayWriter(s).write(4, 2, image(4, 2), true);
        assertEquals(1, s.get(ValueLayout.JAVA_BYTE, pixels(0)), "first row came from the source's last row");
        assertEquals(0, s.get(ValueLayout.JAVA_BYTE, pixels(0) + 4 * 4));
    }

    @Test void keepsRowsWhenNotFlipping() throws Exception {
        MemorySegment s = Shm.create(dir.resolve("shm")).seg();
        new OverlayWriter(s).write(4, 2, image(4, 2), false);
        assertEquals(0, s.get(ValueLayout.JAVA_BYTE, pixels(0)));
        assertEquals(3, s.get(ValueLayout.JAVA_BYTE, pixels(0) + 3 * 4 + 1), "x of 4th pixel");
    }

    @Test void scalesOversizedFramesDownToFit() throws Exception {
        MemorySegment s = Shm.create(dir.resolve("shm")).seg();
        assertTrue(new OverlayWriter(s).write(3840, 2160, ByteBuffer.allocateDirect(3840 * 2160 * 4), false));
        assertEquals(1920, s.get(I, Layout.OFF_OVERLAY + 4));
        assertEquals(1080, s.get(I, Layout.OFF_OVERLAY + 8));
        assertEquals(0, s.get(I, Layout.OFF_OVERLAY));
    }

    @Test void scalingKeepsAspectRatio() throws Exception {
        MemorySegment s = Shm.create(dir.resolve("shm")).seg();
        assertTrue(new OverlayWriter(s).write(2560, 1600, ByteBuffer.allocateDirect(2560 * 1600 * 4), false));
        assertEquals(1728, s.get(I, Layout.OFF_OVERLAY + 4));
        assertEquals(1080, s.get(I, Layout.OFF_OVERLAY + 8));
    }

    @Test void rejectsEmptyFrames() throws Exception {
        MemorySegment s = Shm.create(dir.resolve("shm")).seg();
        assertFalse(new OverlayWriter(s).write(0, 10, ByteBuffer.allocateDirect(0), false));
        assertEquals(-1, s.get(I, Layout.OFF_OVERLAY));
    }
}
