package dev.moui.galaxycraft.voxel;

import java.util.ArrayList;
import java.util.List;

/**
 * Blocks for the unit tests, without Minecraft: the planet's own cubes (ids are their ordinals),
 * water and lava at every level, and three shapes Minecraft has many of: a bottom slab, a flower
 * (crossed, no collision) and glass (a full cube that hides nothing). Tiles of a 4×4 atlas.
 */
public final class CubeBlocks implements Blocks {
    public static final int WATER_BASE = 16, LAVA_BASE = 32;
    public static final int SLAB = 48, FLOWER = 49, GLASS = 50;
    private static final int COUNT = 51;
    private static final int GRASS_TINT = 0x91BD59, WATER_TINT = 0x3F76E4, WHITE = 0xFFFFFF;
    /** Tiles: 0 grass top, 1 grass side, 2 dirt, 3 stone, 4 bedrock, 5 ice, 6 water, 7 lava, 8 cobblestone, 9 obsidian, 10 glass, 11 flower. */
    private static final int[] TILE = {-1, 4, 3, 2, 0, 5, 6, 7, 8, 9};
    public static final CubeBlocks INSTANCE = new CubeBlocks();

    private final BlockInfo[] infos = new BlockInfo[COUNT];

    private CubeBlocks() {
        BlockInfo air = new BlockInfo(NO_FLUID, 0, false, false, false, false, false, true, List.of(), List.of(),
                BlockInfo.FULL, 0, WHITE);
        for (int id = 0; id < COUNT; id++) infos[id] = air;
        for (Material m : Material.values()) {
            if (m == Material.AIR || m.fluid()) continue;
            int t = TILE[m.ordinal()];
            List<ModelQuad> q = m == Material.GRASS ? grass() : BoxModel.box(BlockInfo.FULL, t, t, t, WHITE);
            infos[m.ordinal()] = new BlockInfo(NO_FLUID, 0, true, true, true, true, m != Material.BEDROCK, false, q,
                    List.of(BlockInfo.FULL), BlockInfo.FULL, t, WHITE);
        }
        for (int level = 0; level < 16; level++) {
            infos[WATER_BASE + level] = new BlockInfo(WATER, level, false, false, false, false, false, true, List.of(),
                    List.of(), BlockInfo.FULL, 6, WATER_TINT);
            infos[LAVA_BASE + level] = new BlockInfo(LAVA, level, false, false, false, false, false, true, List.of(),
                    List.of(), BlockInfo.FULL, 7, WHITE);
        }
        double[] half = {0, 0, 0, 1, 0.5, 1};
        infos[SLAB] = new BlockInfo(NO_FLUID, 0, true, false, false, true, true, false, BoxModel.box(half, 3, 3, 3, WHITE),
                List.of(half), half, 3, WHITE);
        infos[FLOWER] = new BlockInfo(NO_FLUID, 0, false, false, false, true, true, false, cross(11),
                List.of(), new double[] {0.3, 0, 0.3, 0.7, 0.6, 0.7}, 11, WHITE);
        infos[GLASS] = new BlockInfo(NO_FLUID, 0, true, true, false, true, true, false,
                BoxModel.box(BlockInfo.FULL, 10, 10, 10, WHITE), List.of(BlockInfo.FULL), BlockInfo.FULL, 10, WHITE);
    }

    /** Grass: a tinted top, dirt below, sides of their own. */
    private static List<ModelQuad> grass() {
        List<ModelQuad> out = new ArrayList<>();
        for (ModelQuad q : BoxModel.box(BlockInfo.FULL, 0, 1, 2, WHITE))
            out.add(q.cull() == CubeSphere.TOP ? new ModelQuad(q.pos(), q.uv(), q.tile(), GRASS_TINT, q.cull()) : q);
        return out;
    }

    /** Two crossed planes, both ways round (Minecraft's block/cross). */
    private static List<ModelQuad> cross(int tile) {
        float[][] planes = {{0, 0, 0, 1, 0, 1, 1, 1, 1, 0, 1, 0}, {0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1}};
        float[] uv = {0, 1, 1, 1, 1, 0, 0, 0};
        List<ModelQuad> out = new ArrayList<>();
        for (float[] p : planes) {
            out.add(new ModelQuad(p, uv, tile, WHITE, -1));
            float[] back = {p[3], p[4], p[5], p[0], p[1], p[2], p[9], p[10], p[11], p[6], p[7], p[8]};
            out.add(new ModelQuad(back, uv, tile, WHITE, -1));
        }
        return out;
    }

    @Override public BlockInfo info(int id) {
        return id >= 0 && id < COUNT ? infos[id] : infos[AIR];
    }

    @Override public int id(Material m) {
        return m == Material.WATER ? WATER_BASE : m == Material.LAVA ? LAVA_BASE : m.ordinal();
    }

    @Override public Material material(int id) {
        if (id >= WATER_BASE && id < WATER_BASE + 16) return Material.WATER;
        if (id >= LAVA_BASE && id < LAVA_BASE + 16) return Material.LAVA;
        if (id < Material.values().length && id != 6 && id != 7) return Material.values()[id];
        return null;
    }

    @Override public int fluidState(int fluid, int level) {
        return (fluid == WATER ? WATER_BASE : LAVA_BASE) + level;
    }

    @Override public boolean faceVisible(int id, int neighbor, int side) {
        BlockInfo n = info(neighbor);
        return !n.occludes() && !(id == GLASS && neighbor == GLASS);
    }

    @Override public String name(int id) {
        return "test:" + id;
    }

    @Override public int parse(String name) {
        try {
            return name.startsWith("test:") ? Integer.parseInt(name.substring(5)) : AIR;
        } catch (NumberFormatException e) {
            return AIR;
        }
    }

    @Override public int atlasColumns() {
        return 4;
    }

    @Override public int atlasRows() {
        return 4;
    }
}
