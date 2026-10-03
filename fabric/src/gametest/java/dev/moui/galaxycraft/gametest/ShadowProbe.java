package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.DropsClient;
import dev.moui.galaxycraft.client.McBlocks;
import dev.moui.galaxycraft.gravity.GravityFrame;
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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
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
    private Vector3d mario, feet;
    private DropsClient drops;
    private GravityFrame frame;
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
                drops = new DropsClient(s);
                mario = new Vector3d(0, s.planet().surface() + 0.5, 0).add(s.center());
                feet = mario;
                frame = new GravityFrame(mario, new Vector3d(mc.player.getX(), mc.player.getY(), mc.player.getZ()),
                        new Vector3d(0, -1, 0));
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
            survival(ctx, cells[1]);
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
            ctx.runOnClient(mc -> {
                link.tick("probe", mario);
                drops.tick(mc, frame, feet);
            });
            ctx.waitTick();
        }
    }

    /**
     * In survival, breaking the lamp drops it on the planet; walking over it puts it in the
     * inventory; an item thrown with Q lands on the planet too.
     */
    private void survival(ClientGameTestContext ctx, int lamp) {
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                ServerPlayer sp = server.getPlayerList().getPlayers().get(0);
                sp.setGameMode(GameType.SURVIVAL);
                sp.getInventory().clearContent();
            });
        });
        run(ctx, 5);
        ctx.runOnClient(mc -> ShadowWorld.destroy(s.planet(), lamp, mc.player.getUUID()));
        run(ctx, 40);
        check(ctx, lamp, "air");
        String dropped = ctx.computeOnClient(mc -> drops.all().isEmpty() ? "none" : drops.all().get(0).item + " on ground "
                + drops.all().get(0).onGround);
        System.out.println("[GalaxyCraft shadow] dropped " + dropped);
        if (!dropped.contains("redstone_lamp") || !dropped.contains("true")) fails.add("lamp drop: " + dropped);
        ctx.runOnClient(mc -> feet = s.galOf(new Vector3d(drops.all().get(0).pos)));
        run(ctx, 10);
        int lamps = ctx.computeOnClient(mc -> mc.player.getInventory().countItem(Items.REDSTONE_LAMP));
        System.out.println("[GalaxyCraft shadow] lamps in inventory " + lamps);
        if (lamps != 1 || !drops.all().isEmpty()) fails.add("lamp not picked up");
        ctx.runOnClient(mc -> {
            feet = mario;
            var server = mc.getSingleplayerServer();
            server.execute(() -> server.getPlayerList().getPlayers().get(0).getInventory().setItem(0, new ItemStack(Items.STONE, 5)));
        });
        run(ctx, 5);
        ctx.runOnClient(mc -> {
            mc.player.getInventory().setSelectedSlot(0);
            mc.gameMode.dropItem(mc.player, false);
        });
        run(ctx, 20);
        String thrown = ctx.computeOnClient(mc -> drops.all().isEmpty() ? "none" : drops.all().get(0).item.toString());
        System.out.println("[GalaxyCraft shadow] thrown " + thrown);
        if (!thrown.contains("stone")) fails.add("Q drop: " + thrown);
    }

    private void check(ClientGameTestContext ctx, int cell, String want) {
        String name = ctx.computeOnClient(mc -> blocks.name(s.planet().get(cell)));
        System.out.println("[GalaxyCraft shadow] " + name);
        if (!name.contains(want)) fails.add(name + " lacks " + want);
    }
}
