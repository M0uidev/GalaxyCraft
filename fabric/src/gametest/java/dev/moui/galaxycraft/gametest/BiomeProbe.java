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
                    // The same view a moment later: water and lava move (animated tiles).
                    ctx.waitTicks(4);
                    gxdev("ctl", "shot biomes-" + name + "-shore-" + yaw + "b");
                }
                if (name.equals("mixed")) light(ctx, sp, s);
            }
            ctx.runOnClient(mc -> PlanetClient.remove());
            ctx.waitTicks(10);
            log("PASS");
        }
    }

    /**
     * Light: Mario walled in under a roof (dark), then with a torch (warm light), then the box gone
     * at midnight. Screenshots biomes-light-*.png.
     */
    private static void light(ClientGameTestContext ctx, TestSingleplayerContext sp, PlanetSession s) {
        int[] box = ctx.computeOnClient(mc -> {
            var p = s.planet();
            var g = p.grid;
            int feet = s.cellAt(GalaxyCraftClient.galaxyPos().orElseThrow());
            if (feet < 0) return new int[0];
            int stone = p.blocks.parse("minecraft:stone"), torch = p.blocks.parse("minecraft:torch");
            java.util.List<Integer> cells = new java.util.ArrayList<>();
            for (int a = -3; a <= 3; a++)
                for (int b = -3; b <= 3; b++)
                    for (int up = 0; up <= 4; up++) {
                        int c = step(g, step(g, step(g, feet, a < 0 ? dev.moui.galaxycraft.voxel.CubeSphere.I_MINUS
                                : dev.moui.galaxycraft.voxel.CubeSphere.I_PLUS, Math.abs(a)), b < 0
                                ? dev.moui.galaxycraft.voxel.CubeSphere.J_MINUS : dev.moui.galaxycraft.voxel.CubeSphere.J_PLUS, Math.abs(b)),
                                dev.moui.galaxycraft.voxel.CubeSphere.TOP, up);
                        if (c < 0) continue;
                        boolean wall = Math.abs(a) == 3 || Math.abs(b) == 3 || up == 4;
                        if (wall) {
                            p.set(c, stone);
                            cells.add(c);
                        } else if (p.get(c) != dev.moui.galaxycraft.voxel.Blocks.AIR) {
                            p.set(c, dev.moui.galaxycraft.voxel.Blocks.AIR);
                        }
                    }
            int t = step(g, feet, dev.moui.galaxycraft.voxel.CubeSphere.I_PLUS, 2);
            log("light: in the box sky " + p.light().sky(feet) + " block " + p.light().block(feet));
            int[] out = new int[2 + cells.size()];
            out[0] = t;
            out[1] = torch;
            for (int i = 0; i < cells.size(); i++) out[2 + i] = cells.get(i);
            return out;
        });
        if (box.length == 0) {
            log("light: Mario is not on the planet");
            return;
        }
        ctx.waitTicks(60);
        shots(ctx, "light-box");
        ctx.runOnClient(mc -> {
            s.planet().set(box[0], box[1]);
            log("light: torch at " + s.planet().blocks.name(s.planet().get(box[0])) + ", block light there "
                    + s.planet().light().block(box[0]));
        });
        ctx.waitTicks(60);
        shots(ctx, "light-torch");
        ctx.runOnClient(mc -> {
            for (int i = 2; i < box.length; i++) s.planet().set(box[i], dev.moui.galaxycraft.voxel.Blocks.AIR);
        });
        sp.getServer().runCommand("time set midnight");
        ctx.waitTicks(60);
        shots(ctx, "light-night");
    }

    private static void shots(ClientGameTestContext ctx, String tag) {
        for (int yaw = 0; yaw < 360; yaw += 180) {
            int y = yaw;
            ctx.runOnClient(mc -> {
                mc.player.setXRot(10);
                mc.player.setYRot(y);
            });
            ctx.waitTicks(15);
            gxdev("ctl", "shot biomes-" + tag + "-" + yaw);
        }
    }

    private static int step(dev.moui.galaxycraft.voxel.CubeSphere g, int c, int side, int n) {
        for (int i = 0; i < n && c >= 0; i++) c = g.neighbor(c, side);
        return c;
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
