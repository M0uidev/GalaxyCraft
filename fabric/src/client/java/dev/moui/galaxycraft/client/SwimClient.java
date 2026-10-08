package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.swim.SwimHook;
import dev.moui.galaxycraft.voxel.Blocks;
import dev.moui.galaxycraft.voxel.PlanetSession;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.joml.Vector3d;

/** Answers Minecraft's fluid questions around the player from the planet in focus (swim/SwimHook). */
final class SwimClient {
    private SwimClient() {}

    /** Once per tick: the planet in focus is the water around the player, while there is one. */
    static void tick(PlanetSession session, GravityFrame frame) {
        if (session == null || !session.active() || frame == null) {
            SwimHook.set(null);
            return;
        }
        SwimHook.set(pos -> fluidAt(session, frame, pos));
    }

    /** The planet's water in the block of Minecraft's frame at pos, as Minecraft's own fluid state. */
    static FluidState fluidAt(PlanetSession s, GravityFrame frame, BlockPos pos) {
        int cell = s.cellAt(frame.toGal(new Vector3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)));
        if (cell < 0 || s.planet().fluid(cell) != Blocks.WATER) return null;
        int level = s.planet().level(cell);
        if (level == 0) return Fluids.WATER.getSource(false);
        boolean falling = level >= 8;
        return Fluids.FLOWING_WATER.getFlowing(falling ? 8 : 8 - level, falling);
    }
}
