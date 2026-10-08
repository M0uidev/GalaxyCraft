package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.GalaxyCraftClient;
import dev.moui.galaxycraft.client.GalaxyOptions;
import dev.moui.galaxycraft.client.PlanetClient;
import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.settings.Movement;
import dev.moui.galaxycraft.voxel.Blocks;
import dev.moui.galaxycraft.voxel.CellGrid;
import dev.moui.galaxycraft.voxel.PlanetBlueprint;
import dev.moui.galaxycraft.voxel.PlanetSession;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.joml.Vector3d;

/**
 * Sneaking to an edge on a small planet in Minecraft's movement, only with -Dgalaxycraft.sneak=true
 * (tools/gxvoxel.sh sneak): next to a trench, the player sneaks at it; it must stay on top of its
 * block, as in Minecraft, never stepping down or off. Logs the player's height over the planet's
 * center at each step of the ticks where it changed (GalaxyCraftClient.trace).
 */
public final class SneakProbe implements FabricClientGameTest {
    private static final double SPACE_UP = 3000;
    private boolean failed;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.sneak")) return;
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getServer().runCommand("gamemode survival @a");
            sp.getServer().runCommand("gamerule fall_damage false");
            sp.getServer().runCommand("time set day");
            sp.getServer().runCommand("tp @a 0 100 0 0 0");
            ctx.waitFor(mc -> GalaxyCraftClient.galaxyPos().isPresent(), 1200);
            ctx.waitTicks(40);
            ctx.runOnClient(mc -> GalaxyOptions.MOVEMENT.set(Movement.MINECRAFT));
            ctx.runOnClient(mc -> PlanetClient.remove());
            ctx.waitTicks(10);
            Vector3d home = ctx.computeOnClient(mc -> new Vector3d(GalaxyCraftClient.galaxyPos().orElseThrow()));
            ctx.runOnClient(mc -> GalaxyCraftClient.moveTo(new Vector3d(0, SPACE_UP / GravityFrame.SCALE, 0).add(home)));
            ctx.waitTicks(60);
            PlanetSession s = PlanetClient.session();
            ctx.runOnClient(mc -> PlanetClient.requestSpawn(PlanetBlueprint.standard("sneak", 32)));
            ctx.waitFor(mc -> s.active() && s.queued() == 0, 2000);
            ctx.runOnClient(mc -> PlanetClient.teleport());
            ctx.waitTicks(200);

            // A trench two columns ahead of the one stood on, three deep and five wide.
            int[] at = ctx.computeOnClient(mc -> {
                CellGrid g = s.planet().grid;
                Vector3d feet = s.localOf(GalaxyCraftClient.galaxyPos().orElseThrow());
                int under = g.cellAt(new Vector3d(feet).mul(1 - 0.5 / feet.length()));
                int f = g.face(under), i = g.i(under), j = g.j(under), k = g.k(under);
                for (int di = 2; di <= 3; di++)
                    for (int dj = -2; dj <= 2; dj++)
                        for (int dk = 0; dk < 3; dk++) {
                            int c = g.cellBeyond(f, i + di, j + dj, k - dk);
                            if (c >= 0) s.planet().set(c, Blocks.AIR);
                        }
                return new int[] {f, i, j, k};
            });
            ctx.waitTicks(40);
            // Stand in the middle of the block, then sneak at the trench.
            Vector3d target = ctx.computeOnClient(mc -> {
                CellGrid g = s.planet().grid;
                return s.galOf(g.center(g.cellBeyond(at[0], at[1] + 6, at[2], at[3] + 1)));
            });
            Vector3d start = ctx.computeOnClient(mc -> {
                CellGrid g = s.planet().grid;
                return s.galOf(g.center(g.cellBeyond(at[0], at[1], at[2], at[3] + 1)));
            });
            ctx.runOnClient(mc -> GalaxyCraftClient.moveTo(start));
            ctx.waitTicks(60);
            double before = height(ctx, s);
            log(String.format("standing %.4f blocks from the center", before));
            aimAt(ctx, target);
            GalaxyCraftClient.traced.clear();
            ctx.getInput().holdKey(o -> o.keyShift);
            ctx.getInput().holdKey(o -> o.keyUp);
            double low = before;
            for (int t = 0; t < 100; t++) {
                aimAt(ctx, target);
                ctx.waitTicks(1);
                low = Math.min(low, height(ctx, s));
            }
            // On along the edge's corner: diagonally at the trench's end.
            Vector3d corner = ctx.computeOnClient(mc -> {
                CellGrid g = s.planet().grid;
                return s.galOf(g.center(g.cellBeyond(at[0], at[1] + 6, at[2] + 6, at[3] + 1)));
            });
            for (int t = 0; t < 80; t++) {
                aimAt(ctx, corner);
                ctx.waitTicks(1);
                low = Math.min(low, height(ctx, s));
            }
            ctx.getInput().releaseKey(o -> o.keyUp);
            double after = height(ctx, s);
            ctx.getInput().releaseKey(o -> o.keyShift);
            String last = "";
            for (String line : GalaxyCraftClient.traced) {
                String h = line.split(" ")[1];
                if (!h.equals(last)) log("  " + line);
                last = h;
            }
            log(String.format("sneaked: lowest %.4f, at the edge %.4f (started %.4f)", low, after, before));
            check(before - low < 0.01, "never lower than the block's top while sneaking (dropped " + String.format("%.4f", before - low) + ")");
            check(Math.abs(after - before) < 0.01, "still on top of the block at the edge");
            ctx.runOnClient(mc -> PlanetClient.remove());
            ctx.runOnClient(mc -> GalaxyCraftClient.moveTo(home));
            ctx.waitTicks(100);
            ctx.runOnClient(mc -> GalaxyOptions.MOVEMENT.set(Movement.MARIO));
            ctx.waitTicks(60);
            log(failed ? "FAIL" : "PASS");
        }
    }

    private static double height(ClientGameTestContext ctx, PlanetSession s) {
        return ctx.computeOnClient(mc -> GalaxyCraftClient.galaxyPos().orElseThrow().distance(s.center()) * GravityFrame.SCALE);
    }

    private static void aimAt(ClientGameTestContext ctx, Vector3d targetGal) {
        ctx.runOnClient(mc -> {
            GravityFrame f = GalaxyCraftClient.frame();
            Vector3d eye = f.toGal(new Vector3d(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ()));
            Vector3d d = f.dirToMc(new Vector3d(targetGal).sub(eye).normalize());
            mc.player.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
            mc.player.setXRot((float) Math.toDegrees(-Math.asin(Math.max(-1, Math.min(1, d.y)))));
        });
    }

    private void check(boolean ok, String what) {
        log((ok ? "ok   " : "FAIL ") + what);
        if (!ok) failed = true;
    }

    private static void log(String msg) {
        System.out.println("[GalaxyCraft sneak] " + msg);
    }
}
