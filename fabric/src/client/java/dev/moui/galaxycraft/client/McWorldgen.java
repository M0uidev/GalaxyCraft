package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.voxel.gen.Worldgen;
import net.minecraft.network.chat.Component;

/**
 * What generated planets take from Minecraft itself: the plants each biome grows (grown on the
 * integrated server), and biomes' names.
 */
public final class McWorldgen implements Worldgen {
    private final McVegetation vegetation;

    public McWorldgen(net.minecraft.server.MinecraftServer server) {
        vegetation = new McVegetation(server);
    }

    @Override
    public dev.moui.galaxycraft.voxel.gen.Vegetation.Library vegetation() {
        return vegetation;
    }

    /** The name a biome has in the game ("Snowy Taiga"), by its id. */
    public static String name(String id) {
        int colon = id.indexOf(':');
        return Component.translatable("biome." + id.substring(0, colon) + "." + id.substring(colon + 1)).getString();
    }
}
