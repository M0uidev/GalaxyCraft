package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.GalaxyCraftClient;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Manual demo, only with -Dgalaxycraft.demo=true (./gradlew runClientGameTest -PgalaxycraftDemo):
 * with the GalaxyCraft Dolphin already running, joins a world and keeps Minecraft alive so its
 * hand and hotbar can be seen composited over the game in Dolphin's window.
 */
public final class DolphinOverlayDemo implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.demo")) return;
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getServer().runCommand("gamemode adventure @a");
            sp.getServer().runCommand("difficulty peaceful");
            sp.getServer().runCommand("gamerule fall_damage false"); // like Mario, no fall damage
            // Blocks for the voxel planet (slot 1 stays empty: the empty hand spins and presses B).
            String[] items = {"iron_pickaxe", "grass_block 64", "dirt 64", "stone 64"};
            for (int i = 0; i < items.length; i++)
                sp.getServer().runCommand("item replace entity @a hotbar." + (i + 1) + " with " + items[i]);
            // No teleport up: linking can be minutes away (SMG2's menus), and a fall from y=100 to
            // the flat world kills the player first. Linking re-bases them wherever they stand.
            ctx.waitFor(mc -> GalaxyCraftClient.exportingOverlay(), 600);
            System.out.println("[GalaxyCraft demo] linked to Dolphin; overlay exporting");
            ctx.waitTicks(Integer.getInteger("galaxycraft.demoTicks", 1200));
            System.out.println("[GalaxyCraft demo] done");
        }
    }
}
