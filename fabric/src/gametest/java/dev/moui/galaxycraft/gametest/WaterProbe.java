package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.GalaxyCraftClient;
import dev.moui.galaxycraft.client.PlanetClient;
import dev.moui.galaxycraft.shadow.ShadowMap;
import dev.moui.galaxycraft.shadow.ShadowWorld;
import dev.moui.galaxycraft.voxel.Blocks;
import dev.moui.galaxycraft.voxel.CellGrid;
import dev.moui.galaxycraft.voxel.Material;
import dev.moui.galaxycraft.voxel.PlanetSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * End to end against the real game, only with -Dgalaxycraft.water=true (tools/gxvoxel.sh water):
 * how water looks. Mario falls into a pool three blocks deep with a pig on the bottom and another
 * on the bank; screenshots show the pool from outside (the pig in it under the water), and from
 * under the water looking up at the pig on the bank (water must blend over both).
 */
public final class WaterProbe implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.water")) return;
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getServer().runCommand("gamemode survival @a");
            sp.getServer().runCommand("gamerule fall_damage false");
            sp.getServer().runCommand("time set day");
            sp.getServer().runCommand("tp @a 0 100 0 0 0");
            ctx.waitFor(mc -> GalaxyCraftClient.galaxyPos().isPresent(), 1200);
            ctx.waitTicks(40);
            PlanetSession s = PlanetClient.session();
            ctx.runOnClient(mc -> PlanetClient.remove());
            ctx.runOnClient(mc -> PlanetClient.requestSpawn(PlanetClient.DEFAULT_RADIUS));
            ctx.waitFor(mc -> s.active() && s.queued() == 0, 400);
            ctx.waitTicks(60);
            ctx.runOnClient(mc -> PlanetClient.teleport());
            ctx.waitTicks(120);
            ctx.waitFor(mc -> ShadowWorld.entities() != null, 200);
            ShadowMap map = ShadowWorld.entities().map();

            // The grass cell under Mario's feet; the pool is it and two below, 3 wide.
            int[] pool = ctx.computeOnClient(mc -> {
                CellGrid g = s.planet().grid;
                int feet = cellAbove(s, 0.5); // the air above the grass Mario stands on
                int grass = g.neighbor(feet, CellGrid.BOTTOM);
                java.util.Set<Integer> cells = new java.util.TreeSet<>();
                int[] sides = {-1, CellGrid.I_MINUS, CellGrid.I_PLUS, CellGrid.J_MINUS, CellGrid.J_PLUS};
                for (int a : sides)
                    for (int b : sides) {
                        int c = grass;
                        if (a >= 0) c = g.neighbor(c, a);
                        if (b >= 0 && c >= 0) c = g.neighbor(c, b);
                        for (int d = 0; d < 3 && c >= 0; d++) {
                            cells.add(c);
                            c = g.neighbor(c, CellGrid.BOTTOM);
                        }
                    }
                return cells.stream().mapToInt(Integer::intValue).toArray();
            });
            check(pool.length >= 18, "the pool's cells (" + pool.length + ")");
            ctx.runOnClient(mc -> { for (int c : pool) s.planet().set(c, Material.AIR); });
            // Mario falls to the pool's bottom (the game's ticks run faster than its frames: wait for it)
            java.util.Set<Integer> inPool = new java.util.HashSet<>();
            for (int c : pool) inPool.add(c);
            for (int k = 0; k < 4; k++) {
                ctx.waitTicks(60);
                log("t" + k + ": " + ctx.computeOnClient(mc -> GalaxyCraftClient.galaxyPos().get().distance(s.center()) / 80
                        + " blocks from the core, queued " + s.queued() + ", mario cell " + cellAbove(s, 0.5)
                        + " in pool " + inPool.contains(cellAbove(s, 0.5)) + ", pool0 " + pool[0] + ", level k "
                        + s.planet().grid.k(cellAbove(s, 0.5)) + " vs " + s.planet().grid.k(pool[0])));
            }
            ctx.waitFor(mc -> inPool.contains(cellAbove(s, 0.5)), 600);
            ctx.waitTicks(60);
            ctx.runOnClient(mc -> { for (int c : pool) s.planet().set(c, Material.WATER); });
            ctx.waitTicks(30);

            // A pig on the bank, three blocks off (I+), and one on the pool's bottom.
            int[] spots = ctx.computeOnClient(mc -> {
                CellGrid g = s.planet().grid;
                int feet = cellAbove(s, 0.5); // the air above the grass Mario stands on
                int bank = g.neighbor(g.neighbor(g.neighbor(g.neighbor(feet, CellGrid.BOTTOM), CellGrid.I_PLUS), CellGrid.I_PLUS), CellGrid.I_PLUS);
                int top = g.neighbor(g.neighbor(bank, CellGrid.TOP), CellGrid.TOP);
                return new int[] {feet, g.neighbor(top, CellGrid.TOP)};
            });
            for (int c : spots)
                sp.getServer().runCommand(String.format(java.util.Locale.ROOT,
                        "execute in galaxycraft:shadow run summon minecraft:pig %.2f %.2f %.2f {NoAI:1b}", map.x(c) + 0.5, map.y(c) + 0.5, map.z(c) + 0.5));
            ctx.waitTicks(30);
            boolean wet = ctx.computeOnClient(mc ->
                    s.planet().fluid(cellAbove(s, 1.2)) == Blocks.WATER);
            log("Mario's cell is water: " + wet);
            check(wet, "Mario is in the water");
            gxdev("ctl", "shot water-0-under");
            ctx.runOnClient(mc -> mc.player.setXRot(-50));
            ctx.waitTicks(10);
            gxdev("ctl", "shot water-1-look-up");
            ctx.getInput().pressKey(o -> o.keyTogglePerspective);
            ctx.waitTicks(20);
            ctx.runOnClient(mc -> mc.player.setXRot(60)); // the camera behind and above: over the water, looking down
            ctx.waitTicks(10);
            for (int k = 0; k < 4; k++) {
                gxdev("ctl", "shot water-2-third-" + k);
                ctx.runOnClient(mc -> mc.player.setYRot(mc.player.getYRot() + 90));
                ctx.waitTicks(10);
            }
            ctx.getInput().pressKey(o -> o.keyTogglePerspective);
            ctx.getInput().pressKey(o -> o.keyTogglePerspective);
            ctx.waitTicks(10);
            sp.getServer().runCommand("execute in galaxycraft:shadow run kill @e[type=!player]");
            ctx.runOnClient(mc -> PlanetClient.remove());
            ctx.waitTicks(10);
            log("PASS");
        }
    }

    /** The cell this many blocks above Mario's feet, along the planet's up. */
    private static int cellAbove(PlanetSession s, double blocks) {
        org.joml.Vector3d feet = GalaxyCraftClient.galaxyPos().get();
        org.joml.Vector3d up = new org.joml.Vector3d(feet).sub(s.center()).normalize();
        return s.cellAt(up.mul(blocks * 80).add(feet));
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            log("FAIL " + what);
            throw new AssertionError(what);
        }
        log("ok " + what);
    }

    private static void log(String msg) {
        System.out.println("[GalaxyCraft water] " + msg);
    }

    /** Runs tools/gxdev.py (the dev Dolphin's control channel) and returns its output. */
    private static String gxdev(String... args) {
        String[] cmd = new String[args.length + 2];
        cmd[0] = Python.exe();
        cmd[1] = Path.of(System.getProperty("galaxycraft.repoRoot"), "tools/gxdev.py").toString();
        System.arraycopy(args, 0, cmd, 2, args.length);
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();
            return out;
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
