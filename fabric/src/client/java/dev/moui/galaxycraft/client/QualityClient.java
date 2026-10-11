package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.bridge.BridgeClient;
import dev.moui.galaxycraft.proto.Layout;
import dev.moui.galaxycraft.settings.VideoQuality;
import java.nio.ByteBuffer;

/**
 * Picture Quality changed while playing: tells Dolphin (GXC_MSG_QUALITY), which applies the internal
 * resolution, MSAA and anisotropy at once. The run started with the setting as it was (gxplay.sh and
 * the launcher pass it), so only a change after that is sent; a new Dolphin starts with the current one.
 */
final class QualityClient {
    private QualityClient() {}

    private static VideoQuality applied; // what the running Dolphin has (null: not seen yet)
    private static int appliedHost;

    /** Render thread, each frame. */
    static void frame(BridgeClient bridge) {
        if (!bridge.linked()) return;
        VideoQuality q = GalaxyOptions.VIDEO_QUALITY.get();
        int host = bridge.hostPid();
        if (applied == null || host != appliedHost) { // a Dolphin just seen started with the setting as it is now
            applied = q;
            appliedHost = host;
            return;
        }
        if (q == applied) return;
        ByteBuffer b = ByteBuffer.allocate(12); // big-endian
        b.putInt(q.scale()).putInt(q.msaa()).putInt(q.anisotropy());
        if (bridge.send(Layout.MSG_QUALITY, b.array())) applied = q;
    }
}
