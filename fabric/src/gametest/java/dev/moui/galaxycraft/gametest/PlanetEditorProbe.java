package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.McBlocks;
import dev.moui.galaxycraft.client.PlanetEditorScreen;
import dev.moui.galaxycraft.voxel.PlanetBlueprint;
import dev.moui.galaxycraft.voxel.VoxelPlanet;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * The planet editor without the game, only with -Dgalaxycraft.editor=true
 * (./gradlew runClientGameTest -PgalaxycraftEditor): /galaxycraft opens it, and a blueprint of
 * Minecraft's blocks builds a planet with them, top to bottom.
 */
public final class PlanetEditorProbe implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.editor")) return;
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            ctx.waitTicks(20);
            ctx.runOnClient(mc -> mc.player.connection.sendCommand("galaxycraft"));
            ctx.waitTicks(5);
            boolean open = ctx.computeOnClient(mc -> mc.gui.screen() instanceof PlanetEditorScreen);
            if (!open) throw new AssertionError("/galaxycraft did not open the editor");
            ctx.takeScreenshot("galaxycraft-planet-editor");
            String result = ctx.computeOnClient(mc -> {
                McBlocks blocks = McBlocks.create(mc);
                PlanetBlueprint bp = new PlanetBlueprint("probe", 20, 8, List.of(
                        new PlanetBlueprint.Layer("minecraft:sand", 2), new PlanetBlueprint.Layer("minecraft:oak_log[axis=y]", 1)));
                VoxelPlanet p = bp.build(blocks);
                int d = p.depth;
                return blocks.name(p.get(p.grid.index(0, 3, 3, d - 1))) + "|" + blocks.name(p.get(p.grid.index(0, 3, 3, d - 3)))
                        + "|" + blocks.name(p.get(p.grid.index(0, 3, 3, 0)));
            });
            if (!result.equals("minecraft:sand|minecraft:oak_log[axis=y]|minecraft:bedrock"))
                throw new AssertionError("built " + result);
            System.out.println("PlanetEditorProbe passed: " + result);
        }
    }
}
