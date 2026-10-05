package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.GalaxyCraftClient;
import dev.moui.galaxycraft.client.PlanetClient;
import dev.moui.galaxycraft.voxel.PlanetBlueprint;
import dev.moui.galaxycraft.voxel.PlanetSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * How generated planets look in the dev Dolphin (tools/gxvoxel.sh biomes): several biomes with
 * water, and a swamp, in first person all around and looking down. Screenshots:
 * biomes-<kind>-<view>.png. The biomes of each planet are logged.
 */
public final class BiomeProbe implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.biomes")) return;
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getServer().runCommand("time set day");
            sp.getServer().runCommand("tp @a 0 100 0 0 0");
            ctx.waitFor(mc -> GalaxyCraftClient.galaxyPos().isPresent(), 1200);
            ctx.waitTicks(40);
            PlanetBlueprint g = PlanetBlueprint.standard("gen", 64).withMode(PlanetBlueprint.Mode.GENERATED).withPlants(100)
                    .withWater(true);
            Object[][] kinds = {
                    {"mixed", g.withBiome(11, PlanetBlueprint.RANDOM, 40)},
                    {"mixed2", g.withBiome(5, PlanetBlueprint.RANDOM, 40)},
                    {"swamp", g.withBiome(3, "minecraft:swamp", 0)},
            };
            String only = System.getProperty("galaxycraft.biomesOnly", "");
            for (Object[] k : kinds) {
                String name = (String) k[0];
                if (!name.contains(only)) continue;
                PlanetSession s = PlanetClient.session();
                ctx.runOnClient(mc -> PlanetClient.remove());
                ctx.waitTicks(20);
                ctx.runOnClient(mc -> PlanetClient.requestSpawn((PlanetBlueprint) k[1]));
                ctx.waitFor(mc -> s.active() && s.queued() == 0, 6000);
                log(name + " biomes " + ctx.computeOnClient(mc -> s.planet().biomes().names()));
                ctx.runOnClient(mc -> PlanetClient.teleport());
                ctx.waitTicks(200);
                for (int yaw = 0; yaw < 360; yaw += 90) {
                    int y = yaw;
                    ctx.runOnClient(mc -> {
                        mc.player.setXRot(8);
                        mc.player.setYRot(y);
                    });
                    ctx.waitTicks(15);
                    gxdev("ctl", "shot biomes-" + name + "-" + yaw);
                }
                ctx.runOnClient(mc -> mc.player.setXRot(45));
                ctx.waitTicks(15);
                gxdev("ctl", "shot biomes-" + name + "-down");
                // On a shore: land beside water, looking at it.
                Object[] shore = ctx.computeOnClient(mc -> shore(s));
                if (shore == null) {
                    log(name + " has no shore");
                    continue;
                }
                ctx.runOnClient(mc -> s.teleportToward((org.joml.Vector3d) shore[0]));
                ctx.waitTicks(200);
                for (int yaw = 0; yaw < 360; yaw += 90) {
                    int y = yaw;
                    ctx.runOnClient(mc -> {
                        mc.player.setXRot(30);
                        mc.player.setYRot(y);
                    });
                    ctx.waitTicks(15);
                    gxdev("ctl", "shot biomes-" + name + "-shore-" + yaw);
                }
            }
            ctx.runOnClient(mc -> PlanetClient.remove());
            ctx.waitTicks(10);
            log("PASS");
        }
    }

    /** A land column (its direction, planet space) with water in a column 2 cells off; null if none. */
    private static Object[] shore(PlanetSession s) {
        var p = s.planet();
        var g = p.grid;
        for (int col = 0; col < 6 * g.n * g.n; col += 7) {
            int top = top(p, col * g.layers);
            if (top < 0 || p.info(top).isFluid() || p.blocks.leaves(p.get(top))) continue;
            int c = col * g.layers;
            for (int side = dev.moui.galaxycraft.voxel.CubeSphere.I_MINUS; side <= dev.moui.galaxycraft.voxel.CubeSphere.J_PLUS; side++) {
                int nb = g.neighbor(c, side);
                nb = nb < 0 ? -1 : g.neighbor(nb, side);
                if (nb < 0) continue;
                int t = top(p, nb);
                if (t >= 0 && p.fluid(t) == dev.moui.galaxycraft.voxel.Blocks.WATER)
                    return new Object[] {g.center(top)};
            }
        }
        return null;
    }

    /** The highest non-air cell of the column of cell (any layer); -1 if none. */
    private static int top(dev.moui.galaxycraft.voxel.VoxelPlanet p, int cell) {
        int base = cell - p.grid.k(cell);
        for (int k = p.grid.layers - 1; k >= 0; k--)
            if (p.get(base + k) != dev.moui.galaxycraft.voxel.Blocks.AIR) return base + k;
        return -1;
    }

    private static void log(String msg) {
        System.out.println("[GalaxyCraft biomes] " + msg);
    }

    /** Runs tools/gxdev.py (the dev Dolphin's control channel) and returns its output. */
    static String gxdev(String... args) {
        String[] cmd = new String[args.length + 2];
        cmd[0] = "python3";
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
            return "";
        }
    }
}
