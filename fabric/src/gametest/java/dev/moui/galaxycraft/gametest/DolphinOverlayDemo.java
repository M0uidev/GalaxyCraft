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
            sp.getServer().runCommand("tp @a 0 100 0 0 0");
            ctx.waitFor(mc -> GalaxyCraftClient.exportingOverlay(), 600);
            System.out.println("[GalaxyCraft demo] linked to Dolphin; overlay exporting");
            ctx.waitTicks(Integer.getInteger("galaxycraft.demoTicks", 1200));
            System.out.println("[GalaxyCraft demo] done");
        }
    }
}
