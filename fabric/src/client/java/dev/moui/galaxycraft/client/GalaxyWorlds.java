package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.voxel.GalaxySave;
import java.io.IOException;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * A Minecraft world is a galaxy: entering a single player world opens its galaxy (its planets and
 * where the player stood, in the world's folder), leaving it saves and closes it. A world entered
 * for the first time gets Mario's rules (no fall damage, the world as Minecraft runs it) and the
 * starter hotbar.
 */
final class GalaxyWorlds {
    /** Blocks and buckets for the planets; slot 1 stays empty (the empty hand spins and presses B). */
    private static final String[] STARTER = {"iron_pickaxe", "grass_block 64", "dirt 64", "stone 64", "cobblestone 64",
            "ice 64", "water_bucket", "lava_bucket"};

    private GalaxyWorlds() {}

    static void joined(Minecraft client) {
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) return; // another's server: no galaxy of ours
        GalaxySave galaxy = GalaxySave.of(server.getWorldPath(LevelResource.ROOT));
        if (galaxy.isNew()) {
            server.execute(() -> firstVisit(server));
            try {
                galaxy.markMade();
            } catch (IOException e) {
                GalaxyCraft.LOG.warn("Could not make the world's galaxy folder: {}", e.toString());
            }
        }
        PlanetClient.enterWorld(galaxy);
    }

    private static void firstVisit(MinecraftServer server) {
        String[] commands = {"gamerule fall_damage false", "gamerule spawn_mobs true", "gamerule advance_weather true",
                "gamerule advance_time true"};
        for (String c : commands) run(server, c);
        for (int i = 0; i < STARTER.length; i++) run(server, "item replace entity @a hotbar." + (i + 1) + " with " + STARTER[i]);
    }

    private static void run(MinecraftServer server, String command) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
    }
}
