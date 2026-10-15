package dev.moui.galaxycraft.net;

import java.util.Optional;

/** Multiplayer constants shared by both sides. */
public final class Protocol {
    /** The protocol version. -Dgalaxycraft.mpVersion overrides it, only so a test can be a guest that is out of date. */
    public static final int VERSION = Integer.getInteger("galaxycraft.mpVersion", 6);
    public static final int MODE_STEVE = 0, MODE_MARIO = 1;
    public static final int F_SNEAK = 1, F_SPRINT = 2, F_SWIM = 4, F_GLIDE = 8, F_GROUND = 16, F_SHIP = 32;
    public static final int MAX_PLAYERS = 8;
    /** A custom skin as sent: a PNG of at most this many bytes (Minecraft caps what a client sends at 32 KB). */
    public static final int SKIN_MAX = 24 * 1024;
    /** The universe's reach in units; anything beyond is not a position. */
    public static final double MAX_COORD = 1e15;
    /** Blocks per second. */
    public static final float MAX_SPEED = 1e5f;

    private Protocol() {}

    public static Optional<String> refusal(int serverVersion, int clientVersion) {
        if (serverVersion == clientVersion) return Optional.empty();
        return Optional.of("Super Minecraft Galaxy multiplayer version differs: the host has " + serverVersion
                + ", you have " + clientVersion + ". Update the mod so both match.");
    }
}
