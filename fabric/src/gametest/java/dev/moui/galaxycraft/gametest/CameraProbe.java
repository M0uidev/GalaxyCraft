package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.GalaxyCraftClient;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/** TEMP: third person, walk and turn, log the camera clip. */
public final class CameraProbe implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getenv("GXC_CAMPROBE") == null) return;
        System.setProperty("galaxycraft.camlog", "false");
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getServer().runCommand("gamemode adventure @a");
            sp.getServer().runCommand("gamerule fall_damage false");
            sp.getServer().runCommand("tp @a 0 100 0 0 0");
            ctx.waitFor(mc -> GalaxyCraftClient.galaxyPos().isPresent(), 600);
            ctx.waitTicks(40);
            ctx.getInput().pressKey(o -> o.keyTogglePerspective);
            ctx.waitTicks(20);
            System.out.println("[camlog] === still, turning");
            System.setProperty("galaxycraft.camlog", "true");
            for (int i = 0; i < 80; i++) { ctx.getInput().moveCursor(20, i % 40 < 20 ? 12 : -12); ctx.waitTicks(1); }
            System.out.println("[camlog] === walking, not turning");
            gx("keys w");
            ctx.waitTicks(60);
            System.out.println("[camlog] === walking and turning");
            for (int i = 0; i < 120; i++) { ctx.getInput().moveCursor(15, i % 60 < 30 ? 10 : -10); ctx.waitTicks(1); }
            gx("keys");
            System.setProperty("galaxycraft.camlog", "false");
            System.out.println("[camlog] === done");
        }
    }

    private static void gx(String c) {
        try {
            new ProcessBuilder("python3", Path.of(System.getProperty("galaxycraft.repoRoot", ".")).resolve("tools/gxdev.py").toString(), "ctl", c)
                    .inheritIO().start().waitFor();
        } catch (Exception e) { throw new AssertionError(e); }
    }
}
