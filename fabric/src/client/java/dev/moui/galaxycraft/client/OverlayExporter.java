package dev.moui.galaxycraft.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.moui.galaxycraft.overlay.OverlayWriter;
import java.lang.foreign.MemorySegment;

/**
 * Reads Minecraft's finished frame back from the GPU (any backend) and hands it to the
 * {@link OverlayWriter}. One readback in flight at a time; frames that arrive meanwhile are skipped.
 */
final class OverlayExporter {
    private final OverlayWriter writer;
    private GpuBuffer buffer;
    private long bufferSize;
    private boolean pending;

    OverlayExporter(MemorySegment shm) {
        this.writer = new OverlayWriter(shm);
    }

    void capture(RenderTarget target) {
        GpuTexture tex = target.getColorTexture();
        if (pending || tex == null || tex.isClosed() || tex.getFormat().blockSize() != 4) return;
        int width = tex.getWidth(0), height = tex.getHeight(0);
        long size = (long) width * height * 4;
        if (buffer == null || bufferSize != size) {
            if (buffer != null) buffer.close();
            buffer = RenderSystem.getDevice().createBuffer(() -> "GalaxyCraft overlay readback",
                    GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, size);
            bufferSize = size;
        }
        GpuBuffer buf = buffer;
        pending = true;
        RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(tex, buf, 0L, () -> {
            try (var view = buf.map(true, false)) {
                writer.write(width, height, view.data(), true); // readback rows are bottom-up
            } finally {
                pending = false;
            }
        }, 0);
    }
}
