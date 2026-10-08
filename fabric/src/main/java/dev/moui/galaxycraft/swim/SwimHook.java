package dev.moui.galaxycraft.swim;

import dev.moui.galaxycraft.shadow.ShadowWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;

/**
 * The planet's water as Minecraft's own movement sees it. The game's overworld around the player
 * is empty (a planet's blocks are only collision to it), so its fluid states are answered from
 * the planet instead, by block position in Minecraft's frame (mixin.PlanetWaterMixin). Minecraft
 * then floats, sinks, swims and drowns the player by its own rules, on the client and on the
 * integrated server alike.
 */
public final class SwimHook {
    /** The planet's fluid at a block of Minecraft's frame, or null: nothing of the planet's there. */
    public interface Source {
        FluidState fluidAt(BlockPos pos);
    }

    private static volatile Source source;

    private SwimHook() {}

    /** The client sets it while a planet is under the player; null: the level answers itself. */
    public static void set(Source s) {
        source = s;
    }

    public static boolean on() {
        return source != null;
    }

    /** The planet's water at pos in this level, or null to let the level answer. */
    public static FluidState fluidAt(Level level, BlockPos pos) {
        Source s = source;
        if (s == null || ShadowWorld.isShadow(level)) return null;
        return s.fluidAt(pos);
    }
}
