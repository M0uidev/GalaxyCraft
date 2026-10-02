package dev.moui.galaxycraft.gravity;

import org.joml.Vector3d;

/** Mario mode: SMG2 moves Mario by its own physics and the Minecraft player stands where he is. */
public final class Follow {
    private Follow() {}

    /** Where the player goes: Mario's galaxy position (his feet) in Minecraft space. */
    public static Vector3d target(GravityFrame frame, Vector3d marioGal) {
        return frame.toMc(marioGal);
    }
}
