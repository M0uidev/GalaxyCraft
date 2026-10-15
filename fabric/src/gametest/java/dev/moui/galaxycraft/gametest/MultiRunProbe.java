package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.McBlocks;
import dev.moui.galaxycraft.client.ShadowLink;
import dev.moui.galaxycraft.shadow.ShadowColumns;
import dev.moui.galaxycraft.shadow.ShadowMap;
import dev.moui.galaxycraft.shadow.ShadowWorld;
import dev.moui.galaxycraft.voxel.CubeSphere;
import dev.moui.galaxycraft.voxel.PlanetSession;
import dev.moui.galaxycraft.voxel.VoxelPlanet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/**
 * The shadow running two planets at once (with ShadowProbe, -PgalaxycraftBlocks): the host's planet A and a planet B
 * that runs only for a guest. Both run in their own strips; a lever lights a lamp on B while A stays as it is; a block
 * broken on B drops on B; the entities of each stand in its strip; and B stops running when the guest leaves.
 */
public final class MultiRunProbe implements FabricClientGameTest {
    private PlanetSession a, b;
    private McBlocks blocks;
    private ShadowLink link;
    private Vector3d mario;
    private UUID guest;
    private final List<String> fails = new ArrayList<>();
    private final List<ShadowWorld.Drop> drops = new ArrayList<>();
    private final int[] changes = new int[2]; // changes the shadow made, by planet: A, B

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.blocks")) return;
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            ctx.waitTicks(20);
            int[] cells = ctx.computeOnClient(mc -> {
                blocks = McBlocks.create(mc);
                a = new PlanetSession(1);
                a.setBlocks(blocks);
                a.spawn(32, new Vector3d(), new Vector3d(0, 1, 0));
                b = new PlanetSession(1);
                b.setBlocks(blocks);
                b.spawn(24, new Vector3d(), new Vector3d(0, 1, 0));
                guest = mc.player.getUUID();
                link = new ShadowLink(() -> a);
                mario = new Vector3d(0, a.planet().surface() + 0.5, 0).add(a.center());
                return new int[] {place(a, "lever", 2), place(a, "redstone_lamp", 3), place(b, "lever", 2), place(b, "redstone_lamp", 3),
                        place(b, "oak_planks", 5)};
            });
            VoxelPlanet pA = a.planet(), pB = b.planet();
            for (int t = 0; t < 60; t++) { // the host's planet is taken in
                ctx.runOnClient(mc -> link.tick("probe", mario));
                ctx.waitTick();
            }
            // Planet B runs for the guest, as HostPlanets.guestWhere makes it.
            Vector3d feetB = new Vector3d(0, pB.surface() + 0.5, 0);
            ctx.runOnClient(mc -> {
                ShadowWorld.runFor(pB, "mp1");
                pB.addObserver((cell, id) -> ShadowWorld.setQuiet(pB, cell, id));
                ShadowWorld.guest(guest, new ShadowWorld.MarioAt(pB, feetB, new Vector3d(0, -1, 0), guest));
            });
            run(ctx, 3); // the run exists and has its map
            ctx.runOnClient(mc -> {
                ShadowMap m = ShadowWorld.mapOf(pB);
                if (m != null) ShadowWorld.follow(pB, guest, ShadowColumns.near(pB, m, feetB, 48), pB.grid.cellAt(feetB));
            });
            run(ctx, 80);

            // (1) both run, in strips that do not overlap
            boolean ra = ctx.computeOnClient(mc -> ShadowWorld.runs(pA)), rb = ctx.computeOnClient(mc -> ShadowWorld.runs(pB));
            ShadowMap mA = ShadowWorld.mapOf(pA), mB = ShadowWorld.mapOf(pB);
            System.out.println("[GalaxyCraft multirun] runs A=" + ra + " B=" + rb + " z0 " + (mA == null ? null : mA.z0) + " "
                    + (mB == null ? null : mB.z0));
            if (!ra || !rb || mA == null || mB == null) {
                System.out.println("[GalaxyCraft multirun] FAIL [not both running]");
                return;
            }
            if (Math.abs(mA.z0 - mB.z0) < ShadowMap.STRIDE) fails.add("strips overlap");

            // (2) a lever lights the lamp on B, A untouched
            check(ctx, b, cells[3], "lit=false");
            use(ctx, pB, cells[2]);
            run(ctx, 15);
            check(ctx, b, cells[2], "powered=true");
            check(ctx, b, cells[3], "lit=true");
            check(ctx, a, cells[1], "lit=false");
            check(ctx, a, cells[0], "powered=false");
            // A changes by itself too (trees grow, leaves fall): only its own lever and lamp, checked above, show B did not leak.
            System.out.println("[GalaxyCraft multirun] natural changes of A meanwhile: " + changes[0]);
            if (changes[1] == 0) fails.add("no change of B came out of the shadow");

            // (3) a block broken on B drops on B
            ctx.runOnClient(mc -> {
                var server = mc.getSingleplayerServer();
                server.execute(() -> {
                    ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                    p.setGameMode(GameType.SURVIVAL);
                    p.getInventory().clearContent();
                });
            });
            run(ctx, 5);
            ctx.runOnClient(mc -> ShadowWorld.destroy(pB, cells[4], guest));
            run(ctx, 30);
            check(ctx, b, cells[4], "air");
            ShadowWorld.Drop d = drops.isEmpty() ? null : drops.get(0);
            System.out.println("[GalaxyCraft multirun] drop " + (d == null ? "none" : d.stack() + " on " + (d.planet() == pB ? "B" : d.planet() == pA ? "A" : "?")));
            if (d == null) fails.add("no drop");
            for (ShadowWorld.Drop x : drops) if (x.planet() != pB) fails.add("a drop on another planet than B");

            // (4) entities of A and B are separate
            spawnPig(ctx, pA, cells[1]);
            spawnPig(ctx, pB, cells[3]);
            run(ctx, 20);
            ShadowWorld.Entities eA = ShadowWorld.entitiesOf(pA), eB = ShadowWorld.entitiesOf(pB);
            int nA = eA == null ? -1 : eA.list().size(), nB = eB == null ? -1 : eB.list().size();
            System.out.println("[GalaxyCraft multirun] entities A=" + nA + " B=" + nB);
            if (nA < 1 || nB < 1) fails.add("entities missing: A=" + nA + " B=" + nB);
            if (eA != null) for (var e : eA.list()) if (!mA.inStrip((int) Math.floor(e.getZ()))) fails.add("an entity of A out of A's strip");
            if (eB != null) for (var e : eB.list()) if (!mB.inStrip((int) Math.floor(e.getZ()))) fails.add("an entity of B out of B's strip");

            // (5) the guest leaves: B stops, A runs on
            ctx.runOnClient(mc -> {
                ShadowWorld.guest(guest, null);
                ShadowWorld.follow(pB, guest, java.util.Set.of(), -1);
            });
            run(ctx, 40);
            boolean ra2 = ctx.computeOnClient(mc -> ShadowWorld.runs(pA)), rb2 = ctx.computeOnClient(mc -> ShadowWorld.runs(pB));
            System.out.println("[GalaxyCraft multirun] after leaving A=" + ra2 + " B=" + rb2);
            if (!ra2) fails.add("A stopped when the guest left B");
            if (rb2) fails.add("B still runs after the guest left");
            System.out.println("[GalaxyCraft multirun] " + (fails.isEmpty() ? "PASS" : "FAIL " + fails));
        }
    }

    private void spawnPig(ClientGameTestContext ctx, VoxelPlanet p, int cell) {
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                ShadowMap m = ShadowWorld.mapOf(p);
                var level = server.getLevel(ShadowWorld.KEY);
                if (m == null || level == null) return;
                var pig = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace("pig")).create(level, EntitySpawnReason.COMMAND);
                if (pig == null) return;
                pig.snapTo(m.x(cell) + 0.5, m.y(cell) + 1, m.z(cell) + 0.5, 0, 0);
                if (pig instanceof net.minecraft.world.entity.Mob mob) mob.setPersistenceRequired();
                level.addFreshEntity(pig);
            });
        });
    }

    private int place(PlanetSession s, String name, int k) {
        var p = s.planet();
        var item = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(name));
        double ang = (k - 8 + 0.5) / p.surface();
        Vector3d up = new Vector3d(Math.sin(ang), Math.cos(ang), 0.02).normalize();
        Vector3d ahead = new Vector3d(Math.cos(ang), -Math.sin(ang), 0);
        Vector3d at = new Vector3d(up).mul(p.surface());
        Vector3d eye = new Vector3d(up).mul(2.5).add(at).sub(new Vector3d(ahead).mul(0.6)).add(s.center());
        Vector3d look = new Vector3d(up).negate().add(new Vector3d(ahead).mul(0.25)).normalize();
        if (!s.placeBlock(eye, look, blocks.placer(new ItemStack(item)), new Vector3d(0, -100, 0).add(s.center())))
            fails.add(name + " not placed");
        return p.grid.cellAt(new Vector3d(up).mul(p.surface() + 0.5));
    }

    private void use(ClientGameTestContext ctx, VoxelPlanet p, int cell) {
        ctx.runOnClient(mc -> ShadowWorld.use(p, cell, CubeSphere.TOP, new Vec3(0.5, 0.5, 0.5), mc.player.getUUID(),
                net.minecraft.world.InteractionHand.MAIN_HAND, () -> fails.add("use passed")));
        run(ctx, 3);
    }

    /** Ticks: what the shadow changed goes to its planet, drops are collected. */
    private void run(ClientGameTestContext ctx, int ticks) {
        for (int t = 0; t < ticks; t++) {
            ctx.runOnClient(mc -> {
                for (ShadowWorld.Change c; (c = ShadowWorld.pollChange()) != null; ) {
                    if (c.planet() == a.planet()) changes[0]++;
                    else if (c.planet() == b.planet()) changes[1]++;
                    (c.planet() == a.planet() ? a : b).planet().set(c.cell(), c.id());
                }
                for (ShadowWorld.Drop d; (d = ShadowWorld.pollDrop()) != null; ) drops.add(d);
                for (Runnable r; (r = ShadowWorld.pollClient()) != null; ) r.run();
            });
            ctx.waitTick();
        }
    }

    private void check(ClientGameTestContext ctx, PlanetSession s, int cell, String want) {
        String name = ctx.computeOnClient(mc -> blocks.name(s.planet().get(cell)));
        System.out.println("[GalaxyCraft multirun] " + name);
        if (!name.contains(want)) fails.add(name + " lacks " + want);
    }
}
