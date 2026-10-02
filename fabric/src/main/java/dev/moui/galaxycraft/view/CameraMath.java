package dev.moui.galaxycraft.view;

import dev.moui.galaxycraft.gravity.GravityFrame;
import org.joml.Vector3d;

/** Camera poses from Minecraft to SMG2, relative to the character. */
public final class CameraMath {
    private CameraMath() {}

    /**
     * Minecraft's camera minus the player's feet, in galaxy units: where SMG2 puts its camera from
     * Mario. partial: how far into the tick it is drawn (see GravityFrame.dirToGal).
     */
    public static Vector3d offsetGal(GravityFrame frame, Vector3d cameraMc, Vector3d feetMc, double partial) {
        return frame.dirToGal(new Vector3d(cameraMc).sub(feetMc), partial).div(GravityFrame.SCALE);
    }
}
