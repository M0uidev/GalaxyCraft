package dev.moui.galaxycraft.view;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * What the player holds in the main hand, as the game draws it in Steve's right hand (GxcHeld in
 * GXC_MSG_HELD): a block of the planet's atlas, or a sprite the game makes a cube or Minecraft's
 * flat item of.
 * Sent when it changes and again to every new scene or host, which start with empty hands. No
 * Minecraft types, so it is unit tested.
 */
public final class HeldItem {
    public static final int NONE = 0, BLOCK = 1, CUBE = 2, ITEM = 3, TOOL = 4;
    public static final int SPRITE = 16;
    public static final int BYTES = 16 + SPRITE * SPRITE * 2;

    private byte[] sent;
    private int scene = Integer.MIN_VALUE, host = Integer.MIN_VALUE;

    /** Nothing in hand. */
    public static byte[] none() {
        return payload(NONE, new int[3], null);
    }

    /** A block of the atlas: its tiles on the top, the sides and the bottom. */
    public static byte[] block(int top, int side, int bottom) {
        return payload(BLOCK, new int[] {top, side, bottom}, null);
    }

    /** CUBE, ITEM or TOOL with a 16×16 ARGB sprite (row 0 at the top). */
    public static byte[] sprite(int kind, int[] argb) {
        if (argb.length != SPRITE * SPRITE) throw new IllegalArgumentException("sprite must be 16x16");
        return payload(kind, new int[3], argb);
    }

    /** GxcHeld: the words little-endian (the host swaps them), the sprite as GX RGB5A3. */
    static byte[] payload(int kind, int[] tiles, int[] argb) {
        ByteBuffer b = ByteBuffer.allocate(BYTES).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(kind).putInt(tiles[0]).putInt(tiles[1]).putInt(tiles[2]);
        if (argb != null) {
            b.order(ByteOrder.BIG_ENDIAN);
            // 4×4 texel blocks, left to right and top to bottom; texels row by row in a block.
            for (int by = 0; by < SPRITE; by += 4)
                for (int bx = 0; bx < SPRITE; bx += 4)
                    for (int y = by; y < by + 4; y++)
                        for (int x = bx; x < bx + 4; x++) b.putShort(rgb5a3(argb[y * SPRITE + x]));
        }
        return b.array();
    }

    /** GX RGB5A3: opaque as 1RRRRRGGGGGBBBBB, else 0AAARRRRGGGGBBBB; alpha 0 stays a hole. */
    static short rgb5a3(int argb) {
        int a = argb >>> 24, r = argb >> 16 & 0xFF, g = argb >> 8 & 0xFF, bl = argb & 0xFF;
        if (a >= 0xE0) return (short) (0x8000 | (r >> 3) << 10 | (g >> 3) << 5 | bl >> 3);
        int a3 = a == 0 ? 0 : Math.max(1, a >> 5);
        return (short) (a3 << 12 | (r >> 4) << 8 | (g >> 4) << 4 | bl >> 4);
    }

    /** Whether this payload must go to the game now: what is held changed, or the game is new. */
    public boolean due(byte[] held, int sceneId, int hostPid) {
        return sceneId != scene || hostPid != host || !Arrays.equals(held, sent);
    }

    /** The game took it (the ring had room). */
    public void sent(byte[] held, int sceneId, int hostPid) {
        sent = held;
        scene = sceneId;
        host = hostPid;
    }
}
