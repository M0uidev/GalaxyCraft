package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.GalaxyCraftClient;
import dev.moui.galaxycraft.client.PlanetClient;
import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.voxel.PlanetBlueprint;
import dev.moui.galaxycraft.voxel.PlanetSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.joml.Vector3d;

/**
 * A planet seen from afar, only with -Dgalaxycraft.lod=true (tools/gxvoxel.sh lod): a generated
 * planet, then /fly to a few distances from its surface, looking at its center, with the dev
 * Dolphin's max speed and the module's VoxelStats at each, and screenshots lod-<blocks>.png.
 */
public final class LodProbe implements FabricClientGameTest {
    private static final Pattern MBX = Pattern.compile("^at=([0-9a-f]+)", Pattern.MULTILINE);
    private static final Pattern MAX_SPEED = Pattern.compile("max_speed=(\\S+)");
    private static final int DBG_VOXEL_STATS = 4048 + 56;
    private static final double UNITS = 80;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.lod")) return;
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getServer().runCommand("time set day");
            sp.getServer().runCommand("tp @a 0 100 0 0 0");
            ctx.waitFor(mc -> GalaxyCraftClient.galaxyPos().isPresent(), 1200);
            ctx.waitTicks(40);
            int r = Integer.getInteger("galaxycraft.lodRadius", 64);
            PlanetSession s = PlanetClient.session();
            ctx.runOnClient(mc -> PlanetClient.remove());
            ctx.runOnClient(mc -> PlanetClient.requestSpawn(PlanetBlueprint.standard("lod", r).withMode(PlanetBlueprint.Mode.GENERATED)
                    .withBiome(7, "minecraft:forest", 0).withUnderground(50, true, 100).withPlants(100)));
            ctx.waitFor(mc -> s.active() && s.queued() == 0, 6000);
            // A second planet beside it: only its far view goes to the game (Mario is far from it).
            ctx.runOnClient(mc -> PlanetClient.requestAdd(32));
            ctx.waitFor(mc -> PlanetClient.planets().size() == 2 && PlanetClient.planets().get(1).active()
                    && PlanetClient.planets().get(1).queued() == 0, 2000);
            PlanetSession b = ctx.computeOnClient(mc -> PlanetClient.planets().get(1));
            check(b.id() != s.id(), "the second planet has an id of its own: " + s.id() + ", " + b.id());
            check(ctx.computeOnClient(mc -> s.detail() && !b.detail()), "the first in full, the second its far view only");
            Vector3d bCenter = ctx.computeOnClient(mc -> new Vector3d(b.center()));
            double apart = ctx.computeOnClient(mc -> s.center().distance(b.center())) / UNITS;
            check(apart * UNITS > s.gravityUnits() + b.gravityUnits(), "their gravity apart: centers " + apart + " blocks apart");
            log(ctx.computeOnClient(mc -> PlanetClient.status()));
            ctx.waitTicks(100);
            ctx.runOnClient(mc -> mc.player.connection.sendCommand("fly"));
            ctx.waitTicks(40);
            Vector3d center = ctx.computeOnClient(mc -> new Vector3d(s.center()));
            double surface = ctx.computeOnClient(mc -> s.planet().surface());
            for (int blocks : new int[] {40, 150, 300, 600, 1200}) {
                // Beside the planet, level with its center, looking at it.
                Vector3d eye = new Vector3d(1, 0, 0.3).normalize().mul((surface + blocks) * UNITS).add(center);
                look(ctx, eye, center);
                String stats = stats();
                gxdev("ctl", "shot lod-" + blocks);
                log(String.format("%5d blocks off: max=%s %s", blocks, maxSpeed(ctx), stats));
            }
            // Both from afar: the second one is drawn as its far view.
            look(ctx, new Vector3d(center).add(bCenter).mul(0.5).add(new Vector3d(1, 0, 0.3).normalize().mul(1500 * UNITS)),
                    new Vector3d(center).add(bCenter).mul(0.5));
            String both = stats();
            gxdev("ctl", "shot lod-both");
            log("both planets from 1500 blocks: " + both);
            check(both.contains("far_parts=") && !both.endsWith("far_parts=0"), "far views drawn");
            ctx.runOnClient(mc -> mc.player.connection.sendCommand("fly"));
            ctx.waitTicks(40);
            ctx.runOnClient(mc -> PlanetClient.remove());
            ctx.waitTicks(5);
            ctx.runOnClient(mc -> PlanetClient.remove());
            ctx.waitTicks(10);
            log("PASS");
        }
    }

    /** The flying player's eye to eye (galaxy units), looking at at; then a few ticks. */
    private static void look(ClientGameTestContext ctx, Vector3d eye, Vector3d at) {
        ctx.runOnClient(mc -> {
            GravityFrame f = GalaxyCraftClient.frame();
            Vector3d mcEye = f.toMc(eye);
            Vector3d dir = f.dirToMc(new Vector3d(at).sub(eye).normalize());
            mc.player.setPos(mcEye.x, mcEye.y - mc.player.getEyeHeight(), mcEye.z);
            mc.player.setDeltaMovement(0, 0, 0);
            mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dir.x, dir.z)));
            mc.player.setXRot((float) Math.toDegrees(-Math.asin(dir.y)));
        });
        ctx.waitTicks(60);
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            log("FAIL: " + what);
            throw new AssertionError(what);
        }
        log("ok " + what);
    }

    /** The median of a few readings, taken while Minecraft keeps running (see PerfProbe). */
    private static String maxSpeed(ClientGameTestContext ctx) {
        java.util.concurrent.CompletableFuture<String> m = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            double[] v = new double[5];
            for (int i = 0; i < v.length; i++) {
                Matcher p = MAX_SPEED.matcher(gxdev("ctl", "status"));
                v[i] = p.find() ? Double.parseDouble(p.group(1)) : -1;
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            java.util.Arrays.sort(v);
            return String.format("%.0f%%", v[2]);
        });
        while (!m.isDone()) ctx.waitTicks(5);
        return m.join();
    }

    /** VoxelStats: chunks the module has, drawn last frame, and parts of far views drawn. */
    private static String stats() {
        Matcher m = MBX.matcher(gxdev("ctl", "mbx"));
        if (!m.find()) return "no mailbox";
        long vs = word(Long.parseLong(m.group(1), 16) + DBG_VOXEL_STATS);
        if (vs == 0) return "no stats";
        return "chunks=" + word(vs + 8) + " drawn=" + word(vs + 40) + " far_parts=" + word(vs + 60);
    }

    private static long word(long at) {
        String[] parts = gxdev("ctl", "peek 0x" + Long.toHexString(at) + " 4").strip().split("\\s+");
        long v = 0;
        for (int i = parts.length - 4; i < parts.length; i++) v = (v << 8) | Integer.parseInt(parts[i], 16);
        return v;
    }

    private static void log(String msg) {
        System.out.println("[GalaxyCraft lod] " + msg);
    }

    private static String gxdev(String... args) {
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
            throw new RuntimeException(e);
        }
    }
}
