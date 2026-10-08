package dev.moui.galaxycraft.voxel.gen;

import dev.moui.galaxycraft.voxel.Blocks;
import dev.moui.galaxycraft.voxel.CubeBlocks;
import dev.moui.galaxycraft.voxel.Material;
import java.util.List;

/** Stand-ins for Minecraft in generator tests: block ids by name. */
public final class GenFixtures {
    public static final Blocks B = CubeBlocks.INSTANCE;

    private GenFixtures() {}

    /** Sand is cobblestone here, sandstone obsidian; the rest by its kind. */
    public static int id(String name) {
        Material m = name.contains("sandstone") ? Material.OBSIDIAN : name.contains("sand") ? Material.COBBLESTONE
                : name.contains("bedrock") ? Material.BEDROCK : name.contains("dirt") ? Material.DIRT
                : name.contains("grass") ? Material.GRASS : name.contains("snow") ? Material.ICE
                : name.contains("water") ? Material.WATER : name.contains("ice") ? Material.LAVA : name.contains("gravel") ? Material.DIRT
                : Material.STONE;
        return B.id(m);
    }

}
