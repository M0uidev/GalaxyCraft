package dev.moui.galaxycraft.view;

import dev.moui.galaxycraft.gravity.GravityFrame;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

/** Camera poses between Minecraft and SMG2, always relative to the character (Steve or Mario). */
public final class CameraMath {
    private CameraMath() {}

    /**
     * Galaxy view: Minecraft's camera sits from Steve (as drawn) where the game's camera sits from
     * Mario, so Steve lands on screen where Mario would be even when the two are a tick apart.
     */
    public static Vector3d galaxyCamera(GravityFrame frame, Vector3d steveMc, Vector3d marioGal, Vector3d camGal) {
        Vector3d rel = new Vector3d(camGal).sub(marioGal).mul(GravityFrame.SCALE);
        return frame.dirToMc(rel).add(steveMc);
    }

    /** Camera rotation taking (0,0,-1) to forward and (0,1,0) to up (made orthogonal to forward). */
    public static Quaternionf rotation(Vector3d forward, Vector3d up) {
        // JOML's lookAlong takes the direction to +z; the camera looks down -z.
        return new Quaternionf().lookAlong(new Vector3f((float) -forward.x, (float) -forward.y, (float) -forward.z),
                new Vector3f((float) up.x, (float) up.y, (float) up.z)).conjugate();
    }

    /**
     * Minecraft's camera minus the player's feet, in galaxy units: where SMG2 puts its camera from
     * Mario. partial: how far into the tick it is drawn (see GravityFrame.dirToGal).
     */
    public static Vector3d offsetGal(GravityFrame frame, Vector3d cameraMc, Vector3d feetMc, double partial) {
        return frame.dirToGal(new Vector3d(cameraMc).sub(feetMc), partial).div(GravityFrame.SCALE);
    }
}
