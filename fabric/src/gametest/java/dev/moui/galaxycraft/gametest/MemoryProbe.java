package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.GalaxyCraftClient;
import dev.moui.galaxycraft.client.PlanetClient;
import dev.moui.galaxycraft.voxel.PlanetBlueprint;
import dev.moui.galaxycraft.voxel.PlanetSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * The game's memory with several big generated planets, only with -Dgalaxycraft.memory=true
 * (tools/gxvoxel.sh memory): a radius-150 planet, replaced by another, then a radius-100 one
 * added, landed on, and all removed; after each step the module's VoxelStats (free MEM2, live
 * parts, failed allocations). Fails if allocations fail or memory does not come back.
 */
public final class MemoryProbe implements FabricClientGameTest {
    private static final Pattern MBX = Pattern.compile("^at=([0-9a-f]+)", Pattern.MULTILINE);
    private static final int DBG_VOXEL_STATS = 4048 + 56;
    private boolean failed;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.memory")) return;
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getServer().runCommand("time set day");
            sp.getServer().runCommand("tp @a 0 100 0 0 0");
            ctx.waitFor(mc -> GalaxyCraftClient.galaxyPos().isPresent(), 1200);
            ctx.waitTicks(40);
            ctx.runOnClient(mc -> PlanetClient.remove());
            ctx.waitTicks(60);
            long[] start = stats("empty stage");

            int big = Integer.getInteger("galaxycraft.memoryRadius", 150);
            ctx.runOnClient(mc -> PlanetClient.requestSpawn(generated("a", big)));
            settle(ctx, ctx.computeOnClient(mc -> PlanetClient.focus()));
            stats("radius " + big);
            ctx.runOnClient(mc -> PlanetClient.requestSpawn(generated("b", big)));
            settle(ctx, ctx.computeOnClient(mc -> PlanetClient.focus()));
            stats("replaced by another radius " + big);
            ctx.runOnClient(mc -> PlanetClient.requestAdd(generated("c", 100)));
            ctx.waitFor(mc -> PlanetClient.planets().size() >= 2, 4000);
            PlanetSession c = ctx.computeOnClient(mc -> PlanetClient.planets().get(PlanetClient.planets().size() - 1));
            settle(ctx, c);
            stats("radius 100 added");
            ctx.runOnClient(mc -> PlanetClient.teleport());
            ctx.waitTicks(400);
            long[] landed = stats("landed on the nearest");
            check(landed[6] == 0, "no allocation failed (" + landed[6] + ")");
            check(landed[9] > 0, "parts are drawn (" + landed[9] + ")");

            for (int i = 0; i < 3; i++) ctx.runOnClient(mc -> PlanetClient.remove());
            ctx.waitTicks(400);
            long[] end = stats("all removed");
            ctx.waitTicks(600);
            stats("all removed, 30 s later");
            // The same planet made and removed again and again: what each round keeps.
            long before = end[17];
            for (int round = 1; round <= 3; round++) {
                ctx.runOnClient(mc -> PlanetClient.requestSpawn(PlanetBlueprint.standard("round", 64)));
                settle(ctx, ctx.computeOnClient(mc -> PlanetClient.focus()));
                ctx.runOnClient(mc -> PlanetClient.teleport());
                ctx.waitTicks(200);
                long[] made = stats("round " + round + ": radius 64");
                ctx.runOnClient(mc -> PlanetClient.remove());
                ctx.waitTicks(200);
                long[] gone = stats("round " + round + ": removed");
                log(String.format("  round %d kept %.2f MB; collision parts made so far %d", round, (gone[17] - before) / 1e6, gone[3]));
                before = gone[17];
            }
            end = stats("after the rounds");
            // The module's own heap: what it holds goes back to what it held with no planet (the atlas,
            // the game's entity and held-item buffers stay).
            check(end[17] < start[17] + 2_000_000, String.format("the module's memory comes back: %.2f MB with no planet, %.2f at the end",
                    start[17] / 1e6, end[17] / 1e6));
            check(end[6] == 0, "no allocation failed (" + end[6] + ")");
            replaceAndCreate(ctx);
            log(failed ? "FAIL" : "PASS");
        }
    }

    /** The editor's Replace (where it is, the player back on it) and Create (where the player looks). */
    private void replaceAndCreate(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> PlanetClient.requestSpawn(PlanetBlueprint.standard("home", 48)));
        settle(ctx, ctx.computeOnClient(mc -> PlanetClient.focus()));
        ctx.runOnClient(mc -> PlanetClient.teleport());
        ctx.waitTicks(300);
        PlanetSession home = ctx.computeOnClient(mc -> PlanetClient.standingOn());
        check(home != null, "standing on the planet: Replace is offered");
        if (home == null) return;
        org.joml.Vector3d center = ctx.computeOnClient(mc -> new org.joml.Vector3d(home.center()));
        org.joml.Vector3d dir = ctx.computeOnClient(mc -> GalaxyCraftClient.galaxyPos().orElseThrow().sub(center, new org.joml.Vector3d()));
        check(ctx.computeOnClient(mc -> PlanetClient.requestReplaceHere(PlanetBlueprint.standard("smaller", 40))), "Replace asked");
        ctx.waitTicks(5);
        settle(ctx, home);
        ctx.waitTicks(300);
        org.joml.Vector3d now = ctx.computeOnClient(mc -> GalaxyCraftClient.galaxyPos().orElseThrow().sub(home.center(), new org.joml.Vector3d()));
        double off = home.center().distance(center) / units(1), height = now.length() / units(1),
                turn = Math.toDegrees(now.angle(dir));
        log(String.format("replaced: center moved %.2f blocks; the player %.1f blocks from it (surface 40), %.1f degrees from before",
                off, height, turn));
        check(off < 0.01, "Replace keeps the planet where it was");
        check(Math.abs(height - 40) < 3, "the player stands on the new planet");
        check(turn < 10, "where the player was on it");

        int before = ctx.computeOnClient(mc -> PlanetClient.planets().size());
        org.joml.Vector3d up = ctx.computeOnClient(mc -> GalaxyCraftClient.galaxyUp().orElseThrow());
        org.joml.Vector3d feet = ctx.computeOnClient(mc -> GalaxyCraftClient.galaxyPos().orElseThrow());
        ctx.runOnClient(mc -> mc.player.setXRot(-90)); // straight up
        check(ctx.computeOnClient(mc -> PlanetClient.requestCreateAhead(PlanetBlueprint.standard("ahead", 32))), "Create asked");
        waitUntil(ctx, 200, () -> ctx.computeOnClient(mc -> PlanetClient.planets().size() > before));
        PlanetSession made = ctx.computeOnClient(mc -> PlanetClient.planets().get(PlanetClient.planets().size() - 1));
        org.joml.Vector3d to = ctx.computeOnClient(mc -> made.center().sub(feet, new org.joml.Vector3d()));
        log(String.format("created: %.0f blocks away, %.1f degrees off the look", to.length() / units(1), Math.toDegrees(to.angle(up))));
        check(ctx.computeOnClient(mc -> PlanetClient.planets().size() > before && home.active()), "Create adds one, the others kept");
        check(Math.toDegrees(to.angle(up)) < 3, "where the player looks");
    }

    private static double units(double blocks) {
        return blocks / dev.moui.galaxycraft.gravity.GravityFrame.SCALE;
    }

    private static PlanetBlueprint generated(String name, int radius) {
        return PlanetBlueprint.standard(name, radius).withMode(PlanetBlueprint.Mode.GENERATED);
    }

    /** Until the planet is generated, made and all its chunks sent (or 6000 ticks). */
    private static void settle(ClientGameTestContext ctx, PlanetSession s) {
        waitUntil(ctx, 6000, () -> ctx.computeOnClient(mc -> s.active() && s.queued() == 0));
        ctx.waitTicks(200);
    }

    private static void waitUntil(ClientGameTestContext ctx, int ticks, BooleanSupplier ok) {
        for (int i = 0; i < ticks && !ok.getAsBoolean(); i += 20) ctx.waitTicks(20);
    }

    /** VoxelStats words (batches, records, chunks, parts_made, last_slot, last_version, alloc_failed, free_mem2, free_mem1, parts_live, ...), logged. */
    private static long[] stats(String when) {
        long vs = 0;
        for (int tries = 0; tries < 5 && vs == 0; tries++) {
            Matcher m = MBX.matcher(gxdev("ctl", "mbx"));
            if (m.find()) vs = word(Long.parseLong(m.group(1), 16) + DBG_VOXEL_STATS);
        }
        long[] w = new long[18];
        for (int i = 0; i < w.length && vs != 0; i++) w[i] = word(vs + 4L * i);
        log(String.format("%-30s module heap free %7.2f MB, scene MEM2 left %7.2f MB, module holds %6.2f MB; live parts %4d, chunks %5d, parts made %5d, failed %d",
                when, w[7] / 1e6, w[16] / 1e6, w[17] / 1e6, w[9], w[2], w[3], w[6]));
        return w;
    }

    private static long word(long at) {
        String[] parts = gxdev("ctl", "peek 0x" + Long.toHexString(at) + " 4").strip().split("\\s+");
        long v = 0;
        for (int i = parts.length - 4; i < parts.length; i++) v = (v << 8) | Integer.parseInt(parts[i], 16);
        return v;
    }

    private void check(boolean ok, String what) {
        log((ok ? "ok   " : "FAIL ") + what);
        if (!ok) failed = true;
    }

    private static void log(String msg) {
        System.out.println("[GalaxyCraft memory] " + msg);
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
