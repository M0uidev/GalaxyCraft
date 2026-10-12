package dev.moui.galaxycraft.toad;

import dev.moui.galaxycraft.voxel.gen.Huts;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** Turns a hut's spot into a Toad record: feet on the floor inside the door, up away from the planet, facing out. */
public final class ToadPlacing {
    private ToadPlacing() {}

    /**
     * center: the planet's center (galaxy units); unitsPerBlock converts the spot's radius (blocks).
     * Model +y is up and +z the way it faces, as ToadClient draws it.
     */
    public static ToadRecord record(String stage, ToadType type, Huts.Placed p, Vector3d center, double unitsPerBlock) {
        Vector3d up = new Vector3d(p.dir()[0], p.dir()[1], p.dir()[2]);
        Vector3d feet = new Vector3d(center).fma(p.radius() * unitsPerBlock, up);
        Vector3d fwd = new Vector3d(p.facing()[0], p.facing()[1], p.facing()[2]);
        Vector3d right = new Vector3d(up).cross(fwd);
        Quaterniond q = new Quaterniond().setFromNormalized(new Matrix3d(right, up, fwd));
        return new ToadRecord(p.id(), type, new double[] {feet.x, feet.y, feet.z}, new double[] {q.x, q.y, q.z, q.w}, stage);
    }
}
