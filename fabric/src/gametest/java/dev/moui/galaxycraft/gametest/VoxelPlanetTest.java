package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.GalaxyCraftClient;
import dev.moui.galaxycraft.client.PlanetClient;
import dev.moui.galaxycraft.voxel.Material;
import dev.moui.galaxycraft.voxel.PlanetSession;
import java.io.IOException;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.joml.Vector3d;

/**
 * End to end against the real game, only with -Dgalaxycraft.voxel=true (tools/gxvoxel.sh): the
 * dev Dolphin runs SMG2 in Mario mode. A voxel planet appears, Mario lands on its grass, the
 * block under him is broken and he drops a block, a block placed next to him stays.
 */
public final class VoxelPlanetTest implements FabricClientGameTest {
    private static final double UNITS = 80;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.voxel")) return;
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getServer().runCommand("gamemode adventure @a");
            sp.getServer().runCommand("difficulty peaceful");
            sp.getServer().runCommand("gamerule fall_damage false");
            sp.getServer().runCommand("tp @a 0 100 0 0 0");
            ctx.waitFor(mc -> GalaxyCraftClient.galaxyPos().isPresent(), 1200);
            ctx.waitTicks(40);
            PlanetSession s = PlanetClient.session();
            ctx.runOnClient(mc -> PlanetClient.requestSpawn());
            ctx.waitFor(mc -> s.active() && s.queued() == 0, 400);
            ctx.waitTicks(60); // Dolphin hands it to the module a few hundred KiB a frame
            gxdev("ctl", "shot voxel-1-spawned");

            ctx.runOnClient(mc -> PlanetClient.teleport());
            ctx.runOnClient(mc -> mc.player.setXRot(50));
            ctx.waitTicks(120);
            double r1 = radius(ctx, s);
            log("on the planet: " + r1 + " blocks from its center");
            check(Math.abs(r1 - s.planet().surface()) < 0.3, "Mario stands on the grass, radius " + r1);
            gxdev("ctl", "shot voxel-2-landed");

            // Mario may stand where cells meet (the teleport lands him on a cube face's center, a
            // corner of four cells): dig everything under his feet, a 2×2 hole.
            int broke = ctx.computeOnClient(mc -> {
                Vector3d feet = GalaxyCraftClient.galaxyPos().orElseThrow();
                Vector3d up = new Vector3d(feet).sub(s.center()).normalize();
                Vector3d a = tangent(up), b = new Vector3d(up).cross(a);
                int n = 0;
                for (double[] o : new double[][] {{-.4, -.4}, {-.4, .4}, {.4, -.4}, {.4, .4}}) {
                    Vector3d eye = new Vector3d(up).mul(0.5).add(new Vector3d(a).mul(o[0])).add(new Vector3d(b).mul(o[1]))
                            .mul(UNITS).add(feet);
                    if (s.breakBlock(eye, new Vector3d(up).negate())) n++;
                }
                return n;
            });
            check(broke >= 1, "the grass under Mario breaks (" + broke + " cells)");
            ctx.waitTicks(10);
            log("queued after the break: " + ctx.computeOnClient(mc -> s.queued()));
            gxdev("ctl", "mbx");
            ctx.waitTicks(80);
            double r2 = radius(ctx, s);
            log("after digging: " + r2);
            check(r1 - r2 > 0.7 && r1 - r2 < 1.3, "Mario drops one block: " + r1 + " -> " + r2);
            gxdev("ctl", "shot voxel-3-dug");

            boolean placed = ctx.computeOnClient(mc -> {
                Vector3d feet = GalaxyCraftClient.galaxyPos().orElseThrow();
                Vector3d up = new Vector3d(feet).sub(s.center()).normalize();
                Vector3d side = tangent(up);
                // Three blocks to the side, out of the hole: on top of the grass there.
                Vector3d eye = new Vector3d(up).mul(2.5).add(new Vector3d(side).mul(3)).mul(UNITS).add(feet);
                if (!s.placeBlock(eye, new Vector3d(up).negate(), Material.STONE, feet)) return false;
                // And never inside Mario.
                Vector3d over = new Vector3d(up).mul(2.5).mul(UNITS).add(feet);
                return !s.placeBlock(over, new Vector3d(up).negate(), Material.STONE, feet);
            });
            check(placed, "a stone block goes on the grass beside the hole, none inside Mario");
            ctx.waitTicks(30);
            gxdev("ctl", "shot voxel-4-placed");
            log("PASS");
        }
    }

    private static Vector3d tangent(Vector3d up) {
        return new Vector3d(up).cross(Math.abs(up.y) < 0.9 ? new Vector3d(0, 1, 0) : new Vector3d(1, 0, 0)).normalize();
    }

    private static double radius(ClientGameTestContext ctx, PlanetSession s) {
        return ctx.computeOnClient(mc -> GalaxyCraftClient.galaxyPos().orElseThrow().distance(s.center()) / UNITS);
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            log("FAIL " + what);
            throw new AssertionError(what);
        }
        log("ok " + what);
    }

    private static void log(String msg) {
        System.out.println("[GalaxyCraft voxel] " + msg);
    }

    private static void gxdev(String... args) {
        String[] cmd = new String[args.length + 2];
        cmd[0] = "python3";
        cmd[1] = Path.of(System.getProperty("galaxycraft.repoRoot"), "tools/gxdev.py").toString();
        System.arraycopy(args, 0, cmd, 2, args.length);
        try {
            new ProcessBuilder(cmd).inheritIO().start().waitFor();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
