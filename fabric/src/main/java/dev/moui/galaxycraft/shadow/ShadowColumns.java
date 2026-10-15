package dev.moui.galaxycraft.shadow;

import dev.moui.galaxycraft.voxel.VoxelPlanet;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.world.level.ChunkPos;
import org.joml.Vector3d;

/**
 * The shadow's columns (ChunkPos longs) under the planet's chunks near a point, with a block more around each
 * for the halo: what Minecraft must run for a player standing there. Pure, so the host can ask it for any player.
 */
public final class ShadowColumns {
    private ShadowColumns() {}

    /** at: the point in the planet's space (blocks from its center). */
    public static Set<Long> near(VoxelPlanet p, ShadowMap map, Vector3d at, double range) {
        Set<Long> columns = new HashSet<>();
        Vector3d center = new Vector3d();
        double[] radius = new double[1];
        for (int chunk = 0; chunk < p.chunkCount(); chunk++) {
            p.sphere(chunk, center, radius);
            double dx = center.x - at.x, dy = center.y - at.y, dz = center.z - at.z;
            if (Math.sqrt(dx * dx + dy * dy + dz * dz) - radius[0] >= range) continue;
            int[] cells = p.cellsOf(chunk);
            int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
            for (int c : cells) {
                x0 = Math.min(x0, map.x(c));
                x1 = Math.max(x1, map.x(c));
                z0 = Math.min(z0, map.z(c));
                z1 = Math.max(z1, map.z(c));
            }
            for (int cx = (x0 - 1) >> 4; cx <= (x1 + 1) >> 4; cx++)
                for (int cz = (z0 - 1) >> 4; cz <= (z1 + 1) >> 4; cz++) columns.add(ChunkPos.pack(cx, cz));
        }
        return columns;
    }
}
