package dev.moui.galaxycraft.universe;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import org.joml.Vector3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GameOriginTest {
    static final double U = 80;

    @AfterEach void reset() {
        GameOrigin.reset();
    }

    @Test void aMoveTheGameDidNotTakeIsNotMade() {
        Vector3d far = new Vector3d(1_000_000 * U, 0, -2_000_000 * U);
        assertNull(GameOrigin.moveTo(UPos.of(far), m -> false), "the ring was full");
        assertEquals(0, GameOrigin.epoch());
        assertEquals(new Vector3d(), GameOrigin.offset());
        byte[][] sent = new byte[1][];
        Origin.Shift s = GameOrigin.moveTo(UPos.of(far), m -> (sent[0] = m) != null);
        assertNotNull(s);
        assertEquals(1, GameOrigin.epoch());
        ByteBuffer b = ByteBuffer.wrap(sent[0]); // big-endian, as the module reads it
        assertEquals(16, sent[0].length);
        assertEquals(1, b.getInt());
        assertEquals(s.dx(), b.getInt());
        assertEquals(s.dy(), b.getInt());
        assertEquals(s.dz(), b.getInt());
    }

    @Test void whatGoesToTheGameAndComesBackIsTheSamePoint() {
        Vector3d mario = new Vector3d(1_000_000 * U + 12.5, 3, -2_000_000 * U - 0.25);
        GameOrigin.moveTo(UPos.of(mario), m -> true);
        Vector3d game = GameOrigin.toGame(mario);
        assertTrue(game.length() < UPos.CELL, "the game's numbers stay small: " + game);
        assertEquals(mario, GameOrigin.fromGame(game, GameOrigin.epoch()));
        // Small enough that a float keeps 1/64 of a unit.
        assertEquals(game.x, (float) game.x, 1 / 64.0);
    }

    @Test void aReadingFromBeforeTheMoveIsPlacedByItsEpoch() {
        Vector3d mario = new Vector3d(5 * UPos.CELL + 100, 0, 0);
        Vector3d before = GameOrigin.toGame(mario); // epoch 0: the game has not moved yet
        GameOrigin.moveTo(UPos.of(mario), m -> true);
        assertEquals(mario, GameOrigin.fromGame(before, 0), "an old frame, read after the move");
        assertEquals(mario, GameOrigin.fromGame(GameOrigin.toGame(mario), 1));
        for (int i = 0; i < Origin.HISTORY + 1; i++)
            GameOrigin.moveTo(UPos.of(new Vector3d((i + 7) * 4 * UPos.CELL, 0, 0)), m -> true);
        assertNull(GameOrigin.fromGame(before, 0), "older than the moves remembered");
    }

    @Test void theEpochNowIsAMoveOfNothing() {
        GameOrigin.moveTo(UPos.of(new Vector3d(3 * UPos.CELL, 0, 0)), m -> true);
        Origin.Shift now = GameOrigin.now();
        assertEquals(1, now.epoch());
        assertEquals(0, now.dx() | now.dy() | now.dz());
    }
}
