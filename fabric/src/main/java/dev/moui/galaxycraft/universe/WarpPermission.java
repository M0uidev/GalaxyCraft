package dev.moui.galaxycraft.universe;

/**
 * Who may warp, for now: only in creative mode or with cheats on (permission level 2 or more). The
 * client asks Minecraft for the two flags (Warp.allowed); this class holds the rule. No Minecraft
 * types.
 */
public final class WarpPermission {
    private WarpPermission() {}

    public static boolean allowed(boolean creative, boolean cheats) {
        return creative || cheats;
    }

    /** The line shown when a warp is refused. */
    public static String refusal() {
        return "Warping is only for creative mode or worlds with cheats on, for now";
    }
}
