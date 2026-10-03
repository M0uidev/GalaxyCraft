package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.McBlocks;
import dev.moui.galaxycraft.client.ShadowLink;
import dev.moui.galaxycraft.shadow.ShadowWorld;
import dev.moui.galaxycraft.voxel.CubeSphere;
import dev.moui.galaxycraft.voxel.PlanetSession;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/**
 * Minecraft running a planet's blocks, without the game (with BlockRulesProbe, -PgalaxycraftBlocks):
 * a lever flipped by a right click lights the lamp beside it, a door opens with both halves, and
 * a lever beside a piston pushes out its head, and a block item used on grass is not used up by it (it falls through to placing).
 */
public final class ShadowProbe implements FabricClientGameTest {
    private PlanetSession s;
    private McBlocks blocks;
    private ShadowLink link;
    private Vector3d mario;
    private final List<String> fails = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.blocks")) return;
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            ctx.waitTicks(20);
            int[] cells = ctx.computeOnClient(mc -> {
                blocks = McBlocks.create(mc);
                s = new PlanetSession(1);
                s.setBlocks(blocks);
                s.spawn(32, new Vector3d(), new Vector3d(0, 1, 0));
                link = new ShadowLink(s);
                mario = new Vector3d(0, s.planet().surface() + 0.5, 0).add(s.center());
                return new int[] {place("lever", 2), place("redstone_lamp", 3), place("oak_door", 5), place("piston", 8), place("lever", 9)};
            });
            run(ctx, 60); // the shadow takes the planet in
            check(ctx, cells[1], "lit=false");
            use(ctx, cells[0], true);
            run(ctx, 10);
            check(ctx, cells[0], "powered=true");
            check(ctx, cells[1], "lit=true");
            use(ctx, cells[2], true);
            run(ctx, 10);
            check(ctx, cells[2], "open=true");
            int upper = s.planet().grid.neighbor(cells[2], CubeSphere.TOP);
            check(ctx, upper, "open=true");
            use(ctx, cells[4], true);
            run(ctx, 20);
            check(ctx, cells[3], "extended=true");
            check(ctx, s.planet().grid.neighbor(cells[3], CubeSphere.TOP), "piston_head");
            use(ctx, s.planet().grid.neighbor(cells[1], CubeSphere.BOTTOM), false); // grass: nothing to use
            System.out.println("[GalaxyCraft shadow] " + (fails.isEmpty() ? "PASS" : "FAIL " + fails));
        }
    }

    /** Places an item on the ground k blocks along a row (as BlockRulesProbe does); the cell it went in. */
    private int place(String name, int k) {
        var p = s.planet();
        var item = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(name));
        double a = (k - 8 + 0.5) / p.surface();
        Vector3d up = new Vector3d(Math.sin(a), Math.cos(a), 0.02).normalize();
        Vector3d ahead = new Vector3d(Math.cos(a), -Math.sin(a), 0);
        Vector3d at = new Vector3d(up).mul(p.surface());
        Vector3d eye = new Vector3d(up).mul(2.5).add(at).sub(new Vector3d(ahead).mul(0.6)).add(s.center());
        Vector3d look = new Vector3d(up).negate().add(new Vector3d(ahead).mul(0.25)).normalize();
        if (!s.placeBlock(eye, look, blocks.placer(new ItemStack(item)), new Vector3d(0, -100, 0).add(s.center())))
            fails.add(name + " not placed");
        return p.grid.cellAt(new Vector3d(up).mul(p.surface() + 0.5));
    }

    private void use(ClientGameTestContext ctx, int cell, boolean expectUsed) {
        AtomicBoolean passed = new AtomicBoolean();
        ctx.runOnClient(mc -> ShadowWorld.use(s.planet(), cell, CubeSphere.TOP, new Vec3(0.5, 0.5, 0.5),
                mc.player.getUUID(), () -> passed.set(true)));
        run(ctx, 3);
        if (passed.get() == expectUsed) fails.add(blocks.name(s.planet().get(cell)) + (expectUsed ? " not used" : " used"));
    }

    private void run(ClientGameTestContext ctx, int ticks) {
        for (int t = 0; t < ticks; t++) {
            ctx.runOnClient(mc -> link.tick("probe", mario));
            ctx.waitTick();
        }
    }

    private void check(ClientGameTestContext ctx, int cell, String want) {
        String name = ctx.computeOnClient(mc -> blocks.name(s.planet().get(cell)));
        System.out.println("[GalaxyCraft shadow] " + name);
        if (!name.contains(want)) fails.add(name + " lacks " + want);
    }
}
