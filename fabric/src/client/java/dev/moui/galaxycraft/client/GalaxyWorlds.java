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

    /** A world younger than this (game ticks) was just made. */
    private static final long NEW_WORLD_TICKS = 200;

    private GalaxyWorlds() {}

    static void joined(Minecraft client) {
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) { // another's server: no galaxy of ours
            EnteringScreen.show(client);
            return;
        }
        GalaxySave galaxy = GalaxySave.of(server.getWorldPath(LevelResource.ROOT));
        if (galaxy.isNew()) {
            // Only a world just made gets Mario's rules and the starter hotbar; one made elsewhere
            // (copied in from Minecraft) keeps its own.
            if (server.overworld().getGameTime() < NEW_WORLD_TICKS) server.execute(() -> firstVisit(server));
            try {
                galaxy.markMade();
            } catch (IOException e) {
                GalaxyCraft.LOG.warn("Could not make the world's galaxy folder: {}", e.toString());
            }
        }
        server.execute(() -> clearStartPlatform(server));
        PlanetClient.enterWorld(galaxy);
        EnteringScreen.show(client);
    }

    /**
     * Worlds made before the GalaxyCraft preset turned features off have Minecraft's void start
     * platform at the origin (stone, cobblestone in the middle; VoidStartPlatformFeature): drawn on
     * top of the game, it stood where no planet is. Taken away once, on the server's thread.
     */
    private static void clearStartPlatform(MinecraftServer server) {
        java.nio.file.Path mark = server.getWorldPath(LevelResource.ROOT).resolve("galaxycraft").resolve("start-platform-gone");
        if (java.nio.file.Files.exists(mark)) return;
        var level = server.overworld();
        if (!(level.getChunkSource().getGenerator() instanceof net.minecraft.world.level.levelgen.FlatLevelSource)) return;
        var pos = new net.minecraft.core.BlockPos.MutableBlockPos();
        int gone = 0;
        for (int x = -8; x <= 24; x++)
            for (int z = -8; z <= 24; z++)
                for (int y = level.getMinY(); y < level.getMaxY(); y++) {
                    var b = level.getBlockState(pos.set(x, y, z));
                    if (b.is(net.minecraft.world.level.block.Blocks.STONE) || b.is(net.minecraft.world.level.block.Blocks.COBBLESTONE)) {
                        level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
                        gone++;
                    }
                }
        try {
            java.nio.file.Files.createDirectories(mark.getParent());
            java.nio.file.Files.writeString(mark, "");
        } catch (IOException e) {
            GalaxyCraft.LOG.warn("Could not mark the start platform gone: {}", e.toString());
        }
        GalaxyCraft.LOG.info("Minecraft's start platform taken away ({} blocks)", gone);
    }

    private static void firstVisit(MinecraftServer server) {
        String[] commands = {"gamerule fall_damage false", "gamerule spawn_mobs true", "gamerule advance_weather true",
                "gamerule advance_time true"};
        for (String c : commands) run(server, c);
        for (int i = 0; i < STARTER.length; i++) run(server, "item replace entity @a hotbar." + (i + 1) + " with " + STARTER[i]);
    }

    private static void run(MinecraftServer server, String command) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
    }
}
