package dev.moui.galaxycraft.voxel;

/**
 * The blocks a planet's cells can hold, by id: Minecraft's block states in the game
 * (client/McBlocks), a few cubes in the unit tests ({@link CubeBlocks}). Id 0 is air. Ids fit in
 * 16 bits (a cell is a char).
 */
public interface Blocks {
    int AIR = 0;
    int NO_FLUID = 0, WATER = 1, LAVA = 2;

    BlockInfo info(int id);

    /** The id of one of the planet's own blocks (a fluid: its source). */
    int id(Material m);

    /** The planet's own block this id is (WATER and LAVA at any level), or null. */
    Material material(int id);

    /** Water or lava at this level (0 source, 1..7 flowing, 8 falling). */
    int fluidState(int fluid, int level);

    /** Whether id's face toward side shows next to neighbor (Minecraft's Block.shouldRenderFace). */
    boolean faceVisible(int id, int neighbor, int side);

    /** Minecraft's text for the state (BlockStateParser), to save planets by. */
    String name(int id);

    /** The id of a saved state's text; air if this game has no such block. */
    int parse(String name);

    /** The block atlas: tiles of 16 texels across and down. */
    int atlasColumns();

    int atlasRows();

    /**
     * The state cell should have now, given its neighbors (Minecraft's updateShape over the six of
     * them: fences join, a door's upper half goes with its lower one). Its own id if nothing changes.
     */
    default int updateShape(VoxelPlanet p, int cell) {
        return p.get(cell);
    }

    /** Whether id can stay in cell (a torch needs something to stand on). */
    default boolean canSurvive(VoxelPlanet p, int cell, int id) {
        return true;
    }
}
