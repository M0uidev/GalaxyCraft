package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.GalaxyCraftClient;
import dev.moui.galaxycraft.client.PlanetClient;
import dev.moui.galaxycraft.voxel.Blocks;
import dev.moui.galaxycraft.voxel.PlanetBlueprint;
import dev.moui.galaxycraft.voxel.PlanetSession;
import dev.moui.galaxycraft.voxel.VoxelPlanet;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.joml.Vector3d;

/**
 * A teleport onto a planet just made, only with -Dgalaxycraft.tp=true (tools/gxvoxel.sh tp): the
 * planet Mario stands on is removed, another added and P pressed at once, a few times; Mario must
 * end up on its surface, not inside it. Logs his height over the surface while he lands.
 */
public final class TpProbe implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.tp")) return;
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getServer().runCommand("time set day");
            sp.getServer().runCommand("tp @a 0 100 0 0 0");
            ctx.waitFor(mc -> GalaxyCraftClient.galaxyPos().isPresent(), 1200);
            ctx.waitTicks(40);
            ctx.runOnClient(mc -> PlanetClient.remove());
            ctx.runOnClient(mc -> PlanetClient.requestSpawn(blueprint("tp0", 64)));
            ctx.waitFor(mc -> PlanetClient.session().active() && PlanetClient.session().queued() == 0, 6000);
            ctx.runOnClient(mc -> PlanetClient.teleport());
            boolean ok = follow(ctx, "first planet", 200);
            for (int round = 1; round <= 3; round++) {
                ctx.runOnClient(mc -> PlanetClient.remove());
                ctx.waitTicks(20);
                int r = 48 + 16 * round;
                ctx.runOnClient(mc -> PlanetClient.requestAdd(blueprint("tp" + r, r)));
                ctx.waitFor(mc -> PlanetClient.focus().active() && PlanetClient.planets().size() == 1, 2400);
                ctx.runOnClient(mc -> PlanetClient.teleport()); // P at once, while the planet streams in
                ok &= follow(ctx, "round " + round + " radius " + r, 300);
            }
            ctx.runOnClient(mc -> PlanetClient.remove());
            ctx.waitTicks(10);
            log(ok ? "PASS" : "FAIL: Mario ended inside a planet");
        }
    }

    private static PlanetBlueprint blueprint(String name, int r) {
        return PlanetBlueprint.standard(name, r).withMode(PlanetBlueprint.Mode.GENERATED)
                .withBiome(7, "minecraft:forest", 0).withUnderground(50, true, 100).withPlants(100);
    }

    /** Mario's height over the surface (blocks) every 10 ticks; true if he ends on it, not in it. */
    private static boolean follow(ClientGameTestContext ctx, String what, int ticks) {
        String last = "";
        boolean inside = false;
        for (int t = 0; t <= ticks; t += 10) {
            String[] st = ctx.computeOnClient(mc -> state());
            last = st[0];
            inside = st[1] != null;
            log(String.format("%-22s t=%3d %s", what, t, last));
            ctx.waitTicks(10);
        }
        return !inside;
    }

    /** [description, non-null if Mario's feet are in a solid block or under the planet's surface by more than 2 blocks]. */
    private static String[] state() {
        PlanetSession s = PlanetClient.focus();
        Vector3d g = GalaxyCraftClient.galaxyPos().orElse(null);
        if (g == null || !s.active()) return new String[] {"no Mario or planet", "x"};
        VoxelPlanet p = s.planet();
        Vector3d local = s.localOf(g);
        double h = local.length() - p.surface();
        // The top of the column under him.
        int top = -1;
        int c0 = p.grid.cellAt(new Vector3d(local).normalize(p.sphere().core + 0.5));
        if (c0 >= 0)
            for (int k = p.grid.layers - 1; k >= 0; k--)
                if (p.get(c0 + k) != Blocks.AIR) {
                    top = k + 1;
                    break;
                }
        double ground = top < 0 ? p.surface() : p.sphere().radius(top);
        int at = p.grid.cellAt(new Vector3d(local).normalize(local.length() + 0.1));
        boolean solid = at >= 0 && p.get(at) != Blocks.AIR;
        double over = local.length() - ground;
        String d = String.format("id=%d over_surface=%7.1f over_ground=%7.1f in_block=%s queued=%d", s.id(), h, over,
                solid ? PlanetClient.blockName(at) : "-", s.queued());
        return new String[] {d, solid || over < -2 ? "inside" : null};
    }

    private static void log(String msg) {
        System.out.println("[GalaxyCraft tp] " + msg);
    }
}
