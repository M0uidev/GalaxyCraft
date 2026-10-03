package dev.moui.galaxycraft.view;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class HeldItemTest {
    @Test void blockCarriesItsTilesLittleEndian() {
        ByteBuffer b = ByteBuffer.wrap(HeldItem.block(0, 1, 2)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(HeldItem.BYTES, b.capacity());
        assertEquals(HeldItem.BLOCK, b.getInt(0));
        assertEquals(2, b.getInt(12));
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
        byte[] dirt = HeldItem.block(2, 2, 2);
        assertTrue(h.due(dirt, 1, 100));
        h.sent(dirt, 1, 100);
        assertFalse(h.due(HeldItem.block(2, 2, 2), 1, 100));
        assertTrue(h.due(HeldItem.block(3, 3, 3), 1, 100));
        assertTrue(h.due(dirt, 2, 100));  // a new scene has nothing in hand
        assertTrue(h.due(dirt, 1, 101));  // nor a new host
    }
}
