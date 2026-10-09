package dev.moui.galaxycraft.proto;

import java.nio.ByteBuffer;

/**
 * GXC_MSG_SKY's 32 bytes: the sky's light (r, g, b, 0..1), then Minecraft's underwater fog (r, g,
 * b, start, end; zeros out of water), big-endian floats. The message has no spare field and the
 * host passes it on as is, so a panorama capture says "no gravity shells in the picture" by
 * sending the red 2 over its 0..1 (syati/src/core/Shell.h, ShellsHidden).
 */
public final class SkyMessage {
    private SkyMessage() {}

    public static byte[] pack(double[] light, float[] fog, boolean hideShells) {
        ByteBuffer msg = ByteBuffer.allocate(32);
        for (int i = 0; i < 3; i++) {
            float v = (float) Math.min(1, Math.max(0, light[i]));
            msg.putFloat(i == 0 && hideShells ? v + 2f : v);
        }
        for (int i = 0; i < 5; i++) msg.putFloat(fog != null && i < fog.length ? fog[i] : 0f);
        return msg.array();
    }
}
