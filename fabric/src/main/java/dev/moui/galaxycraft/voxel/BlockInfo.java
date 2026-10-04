package dev.moui.galaxycraft.voxel;

import java.util.List;

/**
 * What the planet needs to know about one block state.
 *
 * @param fluid        {@link Blocks#WATER}, {@link Blocks#LAVA} or {@link Blocks#NO_FLUID}
 * @param level        a fluid's level (0 source, 1..7 flowing, 8 falling), else 0
 * @param collides     it has a collision shape: Mario stands on it, fluids do not flow into it
 * @param fullCollision that shape is the whole cell (it collides as the planet's cubes always did)
 * @param occludes     an opaque full cube: hides its neighbors' faces and darkens their corners
 * @param targetable   the crosshair stops on it (it has an outline: not air, not a fluid)
 * @param breakable    the player can break it (not bedrock, which seals the hollow center)
 * @param replaceable  a block placed against it takes its place (short grass, fluids, air)
 * @param quads        its model; empty for air and fluids (drawn from tile and tint)
 * @param boxes        collision boxes in block space: minX, minY, minZ, maxX, maxY, maxZ each
 * @param outline      the bounds of its outline shape in block space, the same six numbers
 * @param shape        the boxes of that outline shape, the same six numbers each: what the crosshair meets
 * @param tile         a fluid's atlas tile (its still texture), or the block's particle tile
 * @param tint         that tile's tint, 0xRRGGBB
 */
public record BlockInfo(int fluid, int level, boolean collides, boolean fullCollision, boolean occludes,
        boolean targetable, boolean breakable, boolean replaceable, List<ModelQuad> quads, List<double[]> boxes,
        double[] outline, List<double[]> shape, int tile, int tint) {
    public static final double[] FULL = {0, 0, 0, 1, 1, 1};

    /** Its outline shape is one box, outline. */
    public BlockInfo(int fluid, int level, boolean collides, boolean fullCollision, boolean occludes,
            boolean targetable, boolean breakable, boolean replaceable, List<ModelQuad> quads, List<double[]> boxes,
            double[] outline, int tile, int tint) {
        this(fluid, level, collides, fullCollision, occludes, targetable, breakable, replaceable, quads, boxes,
                outline, List.of(outline), tile, tint);
    }

    public boolean air() {
        return fluid == Blocks.NO_FLUID && !targetable && quads.isEmpty() && !collides;
    }

    public boolean isFluid() {
        return fluid != Blocks.NO_FLUID;
    }
}
