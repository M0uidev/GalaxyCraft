package dev.moui.galaxycraft.view;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * What the player holds in a hand, as the game draws it in Steve's (GxcHeld in GXC_MSG_HELD; the
 * main hand's in his right, the off hand's in his left, see {@link #inHand}): a block by the sprites of its top, sides and bottom, or one sprite the game makes
 * a cube or Minecraft's flat item of. The sprites go in bands of a 16×64 texture.
 * Sent when it changes and again to every new scene or host, which start with empty hands. No
 * Minecraft types, so it is unit tested.
 */
public final class HeldItem {
    public static final int NONE = 0, BLOCK = 1, CUBE = 2, ITEM = 3, TOOL = 4;
    public static final int SPRITE = 16, BANDS = 4;
    public static final int BYTES = 16 + SPRITE * SPRITE * BANDS * 2;

    private byte[] sent;
    private int scene = Integer.MIN_VALUE, host = Integer.MIN_VALUE;

    /** Nothing in hand. */
    public static byte[] none() {
        return payload(NONE, new int[0][]);
    }

    /** A block: 16×16 ARGB sprites (row 0 at the top) of its top, its sides and its bottom. */
    public static byte[] block(int[] top, int[] side, int[] bottom) {
        return payload(BLOCK, new int[][] {top, side, bottom});
    }

    /** CUBE, ITEM or TOOL with a 16×16 ARGB sprite (row 0 at the top). */
    public static byte[] sprite(int kind, int[] argb) {
        return payload(kind, new int[][] {argb});
    }

    /** The main hand. */
    public static final int MAIN = 0, OFF = 1;

    /** payload for that hand (MAIN, as made, or OFF): GxcHeld's second word. */
    public static byte[] inHand(byte[] payload, int hand) {
        return inHand(payload, hand, POSE_NONE);
    }

    /** GxcHeld's third word: the arm as it hangs, or raised to block (a shield). */
    public static final int POSE_NONE = 0, POSE_BLOCK = 1;

    /** payload for that hand with that arm pose (GxcHeld's second and third words). */
    public static byte[] inHand(byte[] payload, int hand, int pose) {
        byte[] out = payload.clone();
        ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).putInt(4, hand).putInt(8, pose);
        return out;
    }

    /** GxcHeld: the words little-endian (the host swaps them), the sprites as GX RGB5A3 bands. */
    static byte[] payload(int kind, int[][] bands) {
        for (int[] band : bands)
            if (band.length != SPRITE * SPRITE) throw new IllegalArgumentException("sprites must be 16x16");
        ByteBuffer b = ByteBuffer.allocate(BYTES).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(kind).putInt(0).putInt(0).putInt(0);
        b.order(ByteOrder.BIG_ENDIAN);
        // 4×4 texel blocks, left to right and top to bottom; texels row by row in a block.
        for (int by = 0; by < SPRITE * BANDS; by += 4)
            for (int bx = 0; bx < SPRITE; bx += 4)
                for (int y = by; y < by + 4; y++)
                    for (int x = bx; x < bx + 4; x++) {
                        int band = y / SPRITE;
                        b.putShort(band < bands.length ? rgb5a3(bands[band][y % SPRITE * SPRITE + x]) : 0);
                    }
        return b.array();
    }

    /** GX RGB5A3: opaque as 1RRRRRGGGGGBBBBB, else 0AAARRRRGGGGBBBB; alpha 0 stays a hole. */
    public static short rgb5a3(int argb) {
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
