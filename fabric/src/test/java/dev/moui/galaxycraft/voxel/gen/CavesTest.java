package dev.moui.galaxycraft.voxel.gen;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.voxel.Blocks;
import dev.moui.galaxycraft.voxel.CubeSphere;
import dev.moui.galaxycraft.voxel.VoxelPlanet;
import java.util.ArrayDeque;
import org.junit.jupiter.api.Test;

class CavesTest {
    static final char BEDROCK = 7, STONE = 1, WATER = 9, LAVA = 10;
    static final int R = 64, DEPTH = 16, AIR = 16;
    final CubeSphere g = new CubeSphere(VoxelPlanet.gridSize(R), R - DEPTH, DEPTH + AIR);

    /** Stone up to the base surface, a sea two deep on part of face 0. */
    char[] ground() {
        char[] c = new char[g.cellCount()];
        for (int col = 0; col < g.columns(); col++) {
            int base = col * g.layers;
            c[base] = BEDROCK;
            for (int k = 1; k < DEPTH; k++) c[base + k] = STONE;
            if (g.face(base) == 0 && g.i(base) < g.n / 2) {
                c[base + DEPTH - 1] = WATER;
                c[base + DEPTH - 2] = WATER;
            }
        }
        return c;
    }

    int[] tops() {
        int[] t = new int[g.columns()];
        java.util.Arrays.fill(t, DEPTH - 1);
        return t;
    }

    char[] carved(long seed) {
        char[] c = ground();
        Caves.carve(g, DEPTH, c, tops(), seed, 50, true, 3, WATER, LAVA);
        return c;
    }

    @Test void sameSeedSameCaves() {
        assertArrayEquals(carved(1), carved(1));
        assertFalse(java.util.Arrays.equals(carved(1), carved(2)));
    }

    @Test void onlyGroundIsCarvedNeverBedrockNorBesideWater() {
        char[] before = ground(), after = carved(3);
        int carved = 0, ground = 0;
        for (int cell = 0; cell < before.length; cell++) {
            if (before[cell] == STONE) ground++;
            if (before[cell] == after[cell]) continue;
            carved++;
            assertEquals(STONE, before[cell], "only stone is carved");
            assertTrue(after[cell] == Blocks.AIR || after[cell] == LAVA);
            assertTrue(after[cell] != LAVA || g.k(cell) < 3, "lava only deep down");
            for (int side = 0; side < 6; side++) {
                int nb = g.neighbor(cell, side);
                assertTrue(nb < 0 || before[nb] != WATER, "nothing carved beside water");
            }
        }
        assertTrue(carved > ground * 0.02 && carved < ground * 0.3, carved + " of " + ground);
    }

    @Test void tunnelsMakeALabyrinth() {
        char[] before = ground(), after = carved(4);
        boolean[] seen = new boolean[after.length];
        int all = 0, biggest = 0;
        for (int cell = 0; cell < after.length; cell++) {
            if (before[cell] == after[cell] || seen[cell]) continue;
            int size = 0;
            ArrayDeque<Integer> q = new ArrayDeque<>();
            q.add(cell);
            seen[cell] = true;
            while (!q.isEmpty()) {
                int c = q.poll();
                size++;
                for (int side = 0; side < 6; side++) {
                    int nb = g.neighbor(c, side);
                    if (nb >= 0 && !seen[nb] && before[nb] != after[nb]) {
                        seen[nb] = true;
                        q.add(nb);
                    }
                }
            }
            all += size;
            biggest = Math.max(biggest, size);
        }
        assertTrue(biggest > all * 0.3, "largest cave " + biggest + " of " + all);
        assertTrue(Caves.TUNNEL_RADIUS <= 2 && Caves.ROOM_RADIUS <= 3.5, "narrow tunnels, small rooms");
    }

    @Test void withoutEntrancesTheSurfaceStays() {
        char[] c = ground(), was = ground();
        Caves.carve(g, DEPTH, c, tops(), 5, 100, false, 3, WATER, LAVA);
        for (int col = 0; col < g.columns(); col++)
            for (int k = DEPTH - Caves.ROOF; k < DEPTH; k++) {
                assertEquals(was[col * g.layers + k], c[col * g.layers + k], "roof kept");
            }
    }
}
