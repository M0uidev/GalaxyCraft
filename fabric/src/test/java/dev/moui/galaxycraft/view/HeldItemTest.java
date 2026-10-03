package dev.moui.galaxycraft.view;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class HeldItemTest {
    private static int[] solid(int argb) {
        int[] s = new int[256];
        java.util.Arrays.fill(s, argb);
        return s;
    }

    @Test void blockCarriesItsThreeSpritesInBands() {
        byte[] p = HeldItem.block(solid(0xFFFF0000), solid(0xFF00FF00), solid(0xFF0000FF));
        ByteBuffer le = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(HeldItem.BYTES, p.length);
        assertEquals(16 + 16 * 64 * 2, HeldItem.BYTES);
        assertEquals(HeldItem.BLOCK, le.getInt(0));
        ByteBuffer b = ByteBuffer.wrap(p).order(ByteOrder.BIG_ENDIAN);
        // Each band is 16 rows: 4 rows of 4 blocks of 16 texels (32 bytes) = 512 bytes.
        assertEquals((short) 0xFC00, b.getShort(16));            // top: red
        assertEquals((short) 0x83E0, b.getShort(16 + 512));      // sides: green
        assertEquals((short) 0x801F, b.getShort(16 + 1024));     // bottom: blue
        assertEquals(0, b.getShort(16 + 1536));                  // the fourth band is empty
        assertEquals(HeldItem.NONE, ByteBuffer.wrap(HeldItem.none()).getInt(0));
    }

    @Test void spriteIsTiledRgb5a3BigEndian() {
        int[] argb = new int[256];
        argb[4] = 0xFFFF0000;               // (4, 0): opaque red, block 1, first texel
        argb[16 * 6 + 1] = 0x80_00FF00;     // (1, 6): half-transparent green, block 4, row 2
        byte[] p = HeldItem.sprite(HeldItem.ITEM, argb);
        ByteBuffer b = ByteBuffer.wrap(p).order(ByteOrder.BIG_ENDIAN);
        assertEquals(HeldItem.ITEM, ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).getInt(0));
        assertEquals((short) 0xFC00, b.getShort(16 + 2 * 16));                  // 1 11111 00000 00000
        assertEquals((short) 0x40F0, b.getShort(16 + 2 * (4 * 16 + 2 * 4 + 1))); // 0 100 0000 1111 0000
        assertEquals(0, b.getShort(16));                                       // a hole stays one
    }

    @Test void sentAgainWhenItChangesOrTheGameIsNew() {
        HeldItem h = new HeldItem();
        byte[] dirt = HeldItem.sprite(HeldItem.CUBE, solid(0xFF805020));
        assertTrue(h.due(dirt, 1, 100));
        h.sent(dirt, 1, 100);
        assertFalse(h.due(HeldItem.sprite(HeldItem.CUBE, solid(0xFF805020)), 1, 100));
        assertTrue(h.due(HeldItem.sprite(HeldItem.CUBE, solid(0xFF808080)), 1, 100));
        assertTrue(h.due(dirt, 2, 100));  // a new scene has nothing in hand
        assertTrue(h.due(dirt, 1, 101));  // nor a new host
    }
}
