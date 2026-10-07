package dev.moui.galaxycraft.universe;

import java.nio.ByteBuffer;
import org.joml.Vector3d;

/**
 * Where the mod's positions meet the game's. The mod keeps every position in universe units
 * (galaxy units from the universe's (0, 0, 0), doubles: exact to a 1/80 of a block past 10^11
 * blocks); the game (SMG2's floats, Dolphin's GPU) gets them relative to the floating origin
 * ({@link Origin}), so its numbers stay small wherever the player is. Everything sent to the game
 * goes through toGame, everything read from it through fromGame with the epoch it was in.
 *
 * One per Minecraft client (the game has one origin); reset when a world is left.
 */
public final class GameOrigin {
    private static Origin origin = new Origin();

    private GameOrigin() {}

    /** The origin, units: what the game's (0, 0, 0) is in the universe. */
    public static Vector3d offset() {
        UPos c = origin.cell();
        return new Vector3d(c.cx() * UPos.CELL, c.cy() * UPos.CELL, c.cz() * UPos.CELL);
    }

    public static int epoch() {
        return origin.epoch();
    }

    /** A universe position as the game has it now. */
    public static Vector3d toGame(Vector3d universe) {
        return new Vector3d(universe).sub(offset());
    }

    /**
     * A position the game reported in that epoch, in universe units; null if the epoch is older than
     * the moves remembered (no reading this time).
     */
    public static Vector3d fromGame(Vector3d game, int epoch) {
        Vector3d now = origin.local(game, epoch);
        return now == null ? null : now.add(offset());
    }

    /** The origin itself (OriginPolicy decides from it). */
    public static Origin origin() {
        return origin;
    }

    /**
     * Moves the origin to the cell corner nearest to at, if send takes the move's GXC_MSG_ORIGIN
     * (the game must have it before anything sent from the new origin). The move, or null.
     */
    public static Origin.Shift moveTo(UPos at, java.util.function.Predicate<byte[]> send) {
        Origin.Shift s = origin.peek(at);
        if (s == null || !send.test(message(s))) return null;
        origin.apply(s);
        return s;
    }

    /** GXC_MSG_ORIGIN for a move (or, with no move, to tell a new scene the epoch): big-endian. */
    public static byte[] message(Origin.Shift s) {
        return ByteBuffer.allocate(16).putInt(s.epoch()).putInt((int) s.dx()).putInt((int) s.dy()).putInt((int) s.dz())
                .array();
    }

    /** The epoch as it is, with no move: what a new scene is told. */
    public static Origin.Shift now() {
        return new Origin.Shift(origin.epoch(), 0, 0, 0);
    }

    /** The world is left: the next one starts at the universe's (0, 0, 0), epoch 0. */
    public static void reset() {
        origin = new Origin();
    }
}
