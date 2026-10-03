package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.McBlocks;
import dev.moui.galaxycraft.voxel.CubeSphere;
import dev.moui.galaxycraft.voxel.PlanetSession;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3d;

/**
 * Minecraft's blocks on a planet without the game, only with -Dgalaxycraft.blocks=true
 * (./gradlew runClientGameTest -PgalaxycraftBlocks): Minecraft's placement rules put blocks in a
 * row on a small planet, and each must be what Minecraft would make of it.
 */
public final class BlockRulesProbe implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.blocks")) return;
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            ctx.waitTicks(20);
            String[] items = {"oak_stairs", "torch", "poppy", "glass", "oak_fence", "oak_fence", "oak_door", "oak_slab",
                    "oak_log", "furnace", "white_bed", "chest", "oak_leaves", "short_grass", "lantern", "ladder"};
            List<String> out = ctx.computeOnClient(mc -> {
                McBlocks blocks = McBlocks.create(mc);
                PlanetSession s = new PlanetSession(1);
                s.setBlocks(blocks);
                s.spawn(32, new Vector3d(), new Vector3d(0, 1, 0));
                var p = s.planet();
                Vector3d c = s.center();
                java.util.ArrayList<String> lines = new java.util.ArrayList<>();
                int[] cells = new int[items.length];
                for (int k = 0; k < items.length; k++) {
                    var item = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(items[k]));
                    // A row along the surface, a block apart; looking down and a little ahead (+x).
                    double a = (k - 8 + 0.5) / p.surface();
                    Vector3d up = new Vector3d(Math.sin(a), Math.cos(a), 0.02).normalize();
                    Vector3d ahead = new Vector3d(Math.cos(a), -Math.sin(a), 0);
                    Vector3d at = new Vector3d(up).mul(p.surface());
                    Vector3d eye = new Vector3d(up).mul(2.5).add(at).sub(new Vector3d(ahead).mul(0.6)).add(c);
                    Vector3d look = new Vector3d(up).negate().add(new Vector3d(ahead).mul(0.25)).normalize();
                    boolean ok = s.placeBlock(eye, look, blocks.placer(new ItemStack(item)), new Vector3d(0, -100, 0).add(c));
                    cells[k] = p.grid.cellAt(new Vector3d(up).mul(p.surface() + 0.5));
                    if (!ok) lines.add(items[k] + ": REFUSED");
                }
                // Read back once all are down: neighbors placed later have changed some (fences join).
                for (int k = 0; k < items.length; k++)
                    lines.add(items[k] + ": " + blocks.name(p.get(cells[k])) + " under "
                            + blocks.name(p.get(p.grid.neighbor(cells[k], CubeSphere.TOP))));
                return lines;
            });
            for (String l : out) System.out.println("[GalaxyCraft blocks] " + l);
        }
    }
}
