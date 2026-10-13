package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.McBlocks;
import dev.moui.galaxycraft.client.ShadowLink;
import dev.moui.galaxycraft.shadow.ShadowToads;
import dev.moui.galaxycraft.shadow.ShadowWorld;
import dev.moui.galaxycraft.voxel.PlanetSession;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.entity.Entity;
import org.joml.Vector3d;

/**
 * Toads as villagers in the shadow (with -PgalaxycraftToadEntity, Minecraft only): one tagged
 * persistent entity per wanted Toad on free ground, its position published, unloading kills nobody.
 */
public final class ToadEntityProbe implements FabricClientGameTest {
    private PlanetSession s;
    private McBlocks blocks;
    private ShadowLink link;
    private Vector3d mario;
    private final List<String> fails = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.toadEntity")) return;
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            ctx.waitTicks(20);
            ctx.runOnClient(mc -> {
                blocks = McBlocks.create(mc);
                s = new PlanetSession(1);
                s.setBlocks(blocks);
                s.spawn(32, new Vector3d(), new Vector3d(0, 1, 0));
                link = new ShadowLink(() -> s);
                mario = new Vector3d(0, s.planet().surface() + 0.5, 0).add(s.center());
            });
            run(ctx, 60); // the shadow takes the planet in
            want(ctx, new double[] {0, 0, 0}, 0.5);
            run(ctx, 60);
            Entity toad = find(ctx, "t1");
            check(toad != null, "a villager tagged t1 exists");
            if (toad != null) {
                check(ctx.computeOnClient(mc -> toad.getBoundingBox().getXsize() > 0.3), "it has a box");
                check(ctx.computeOnClient(mc -> toad instanceof net.minecraft.world.entity.Mob m && m.isPersistenceRequired()), "it is persistent");
            }
            want(ctx, new double[] {0, 0, 0}, 0.5); // asked twice: still one
            run(ctx, 40);
            check(count(ctx, "t1") == 1, "exactly one entity for t1, found " + count(ctx, "t1"));
            run(ctx, 30);
            check(ctx.computeOnClient(mc -> ShadowToads.positions().containsKey("t1")), "its position is published");
            ctx.runOnClient(mc -> ShadowWorld.attach(null, "probe")); // unload
            run(ctx, 10);
            check(ctx.computeOnClient(mc -> ShadowToads.pollDeath() == null), "unloading kills nobody");
            System.out.println("[GalaxyCraft toadentity] " + (fails.isEmpty() ? "PASS" : "FAIL " + fails));
        }
    }

    /** t1 wanted at the planet's top, plus offset blocks along the planet's up. */
    private void want(ClientGameTestContext ctx, double[] sideways, double up) {
        ctx.runOnClient(mc -> ShadowWorld.wantToads(s.planet(), List.of(new ShadowToads.Wanted("t1",
                new double[] {sideways[0], s.planet().surface() + up, sideways[2]}, 0f))));
    }

    private Entity find(ClientGameTestContext ctx, String id) {
        return ctx.computeOnClient(mc -> {
            var es = ShadowWorld.entities();
            if (es == null) return null;
            for (Entity e : es.list()) if (id.equals(ShadowToads.idOf(e))) return e;
            return null;
        });
    }

    private int count(ClientGameTestContext ctx, String id) {
        return ctx.computeOnClient(mc -> {
            var es = ShadowWorld.entities();
            int n = 0;
            if (es != null) for (Entity e : es.list()) if (id.equals(ShadowToads.idOf(e)) && !e.isRemoved()) n++;
            return n;
        });
    }

    private void run(ClientGameTestContext ctx, int ticks) {
        for (int t = 0; t < ticks; t++) {
            ctx.runOnClient(mc -> link.tick("probe", mario));
            ctx.waitTick();
        }
    }

    private void check(boolean ok, String what) {
        System.out.println("[GalaxyCraft toadentity] " + (ok ? "ok: " : "NOT: ") + what);
        if (!ok) fails.add(what);
    }
}
