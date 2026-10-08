package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.GalaxyCraftClient;
import dev.moui.galaxycraft.client.PlanetClient;
import dev.moui.galaxycraft.voxel.PlanetBlueprint;
import dev.moui.galaxycraft.voxel.PlanetSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * What planets cost the game, only with -Dgalaxycraft.perf=true (tools/gxvoxel.sh perf): Mario on
 * a planet of each kind (layers; generated bare, with caves, with plants, with everything), in
 * first person, and how fast the dev Dolphin could emulate then (max_speed, percent; no planet is
 * about 290 on the dev machine), with the module's VoxelStats. -PperfOnly=caves measures only the
 * kinds whose name has that; -PperfRadius=128 another radius. Screenshots: perf-<kind>-<yaw>.png.
 */
public final class PerfProbe implements FabricClientGameTest {
    private static final Pattern MBX = Pattern.compile("^at=([0-9a-f]+)", Pattern.MULTILINE);
    private static final Pattern MAX_SPEED = Pattern.compile("max_speed=(\\S+)");
    /** Debug.voxel_stats: after the mailbox (4052 bytes), 14 words into the debug block. */
    private static final int DBG_VOXEL_STATS = 4052 + 56;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.perf")) return;
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getServer().runCommand("time set day");
            sp.getServer().runCommand("tp @a 0 100 0 0 0");
            ctx.waitFor(mc -> GalaxyCraftClient.galaxyPos().isPresent(), 1200);
            ctx.waitTicks(40);
            sample(ctx, "no planet");
            String only = System.getProperty("galaxycraft.perfOnly", "");
            int r = Integer.getInteger("galaxycraft.perfRadius", 64);
            PlanetBlueprint g = PlanetBlueprint.standard("gen", r).withMode(PlanetBlueprint.Mode.GENERATED).withBiome(7, "minecraft:forest", 0);
            Object[][] kinds = {
                    {"layers", PlanetBlueprint.standard("lay", r)},
                    {"gen bare", g.withUnderground(0, false, 0).withPlants(0)},
                    {"gen caves50", g.withUnderground(50, false, 0).withPlants(0)},
                    {"gen plants100", g.withUnderground(0, false, 0).withPlants(100)},
                    {"gen full", g.withUnderground(50, true, 100).withPlants(100)},
            };
            for (Object[] k : kinds) {
                String name = (String) k[0];
                if (!name.contains(only)) continue;
                PlanetSession s = PlanetClient.session();
                ctx.runOnClient(mc -> PlanetClient.remove());
                ctx.waitTicks(20);
                ctx.runOnClient(mc -> PlanetClient.requestSpawn((PlanetBlueprint) k[1]));
                ctx.waitFor(mc -> s.active() && s.queued() == 0, 6000);
                ctx.runOnClient(mc -> PlanetClient.teleport());
                ctx.waitTicks(200);
                sample(ctx, name + " standing");
                ctx.runOnClient(mc -> mc.player.setYRot(mc.player.getYRot() + 90));
                ctx.waitTicks(100);
                sample(ctx, name + " turned");
                for (int yaw = 0; yaw < 360; yaw += 120) {
                    int y = yaw;
                    ctx.runOnClient(mc -> {
                        mc.player.setXRot(5);
                        mc.player.setYRot(y);
                    });
                    ctx.waitTicks(15);
                    gxdev("ctl", "shot perf-" + name.replace(' ', '-') + "-" + yaw);
                }
            }
            ctx.runOnClient(mc -> PlanetClient.remove());
            ctx.waitTicks(10);
            log("PASS");
        }
    }

    /**
     * Measured while Minecraft keeps running: a gametest pauses it during its own calls, and the
     * first-person view it sends every frame then lapses to the game's camera.
     */
    private static void sample(ClientGameTestContext ctx, String what) {
        CompletableFuture<String> m = CompletableFuture.supplyAsync(() -> {
            // Noisy: the median of 9 readings, and the spread of the middle ones.
            double[] max = new double[9];
            for (int i = 0; i < max.length; i++) {
                Matcher p = MAX_SPEED.matcher(gxdev("ctl", "status"));
                max[i] = p.find() ? Double.parseDouble(p.group(1)) : -1;
                try {
                    Thread.sleep(400);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            Arrays.sort(max);
            return String.format("max=%.0f%% [%.0f..%.0f] ", max[4], max[1], max[7]) + stats();
        });
        while (!m.isDone()) ctx.waitTicks(5);
        log(String.format("%-22s %s", what, m.join()));
    }

    /** VoxelStats: batches, records, chunks, parts_made, ..., parts_live, drawn_last. */
    private static String stats() {
        Matcher m = MBX.matcher(gxdev("ctl", "mbx"));
        if (!m.find()) return "no mailbox";
        long vs = word(Long.parseLong(m.group(1), 16) + DBG_VOXEL_STATS);
        if (vs == 0) return "no stats";
        return "batches=" + word(vs) + " records=" + word(vs + 4) + " chunks=" + word(vs + 8) + " parts_made=" + word(vs + 12)
                + " parts_live=" + word(vs + 36) + " drawn=" + word(vs + 40);
    }

    /** A big-endian word of guest memory. */
    private static long word(long at) {
        String[] parts = gxdev("ctl", "peek 0x" + Long.toHexString(at) + " 4").strip().split("\\s+");
        long v = 0;
        for (int i = parts.length - 4; i < parts.length; i++) v = (v << 8) | Integer.parseInt(parts[i], 16);
        return v;
    }

    private static void log(String msg) {
        System.out.println("[GalaxyCraft perf] " + msg);
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
