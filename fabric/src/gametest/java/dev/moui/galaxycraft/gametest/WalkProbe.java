package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.GalaxyCraftClient;
import dev.moui.galaxycraft.client.PlanetClient;
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
 * Mario walks where the camera looks, only with -Dgalaxycraft.walk=true (tools/gxvoxel.sh walk):
 * on a planet in first person, W, S, D and A each go their way from the look, and with W held the
 * view turns (fast, back, slowly) and Mario must turn with it. Each leg logs the angle of his steps
 * along the ground from our look (and of the game's own camera and of where he faces).
 *
 * Needs a savestate where Mario walks freely: the prologue's (gxvoxel.sh's own) is a 2D path, its
 * stick moves him along one axis only. GXC_SAV=<a savestate in Starship Mario, say> tools/gxvoxel.sh walk.
 */
public final class WalkProbe implements FabricClientGameTest {
    private static final Pattern MBX = Pattern.compile("^at=([0-9a-f]+)", Pattern.MULTILINE);
    private static final int MBX_GRAVITY = 24, MBX_LOOK = 68, MBX_CAM_DIR = 124, MBX_FRONT = 152;
    private boolean failed;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.walk")) return;
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getServer().runCommand("gamemode adventure @a");
            sp.getServer().runCommand("gamerule fall_damage false");
            sp.getServer().runCommand("tp @a 0 100 0 0 0");
            ctx.waitFor(mc -> GalaxyCraftClient.galaxyPos().isPresent(), 1200);
            ctx.waitTicks(40);

            PlanetSession s = PlanetClient.session();
            ctx.runOnClient(mc -> PlanetClient.remove());
            ctx.runOnClient(mc -> PlanetClient.requestSpawn(PlanetBlueprint.standard("walk", 48)));
            ctx.waitFor(mc -> s.active() && s.queued() == 0, 2000);
            ctx.runOnClient(mc -> PlanetClient.teleport());
            ctx.waitTicks(200);
            walk(ctx, "planet", 25, true);
            ctx.runOnClient(mc -> PlanetClient.remove());
            ctx.waitTicks(10);
            log(failed ? "FAIL" : "PASS");
        }
    }

    /**
     * Each key alone (W ahead, S back, D right, A left of the look; short legs, so Mario stays on a
     * small level's ground), then, with turn, W held while the view turns 90° right, then 180° left.
     */
    private void walk(ClientGameTestContext ctx, String where, int ticks, boolean turn) {
        ctx.runOnClient(mc -> mc.player.setXRot(10));
        String[] keys = {"w", "s", "d", "a"};
        int[] want = {0, 180, 90, -90};
        for (int k = 0; k < keys.length; k++) {
            gxdev("ctl", "keys " + keys[k]);
            ctx.waitTicks(8);
            leg(ctx, where + " " + keys[k], ticks, want[k]);
            gxdev("ctl", "keys");
            ctx.waitTicks(20);
        }
        if (!turn) return;
        gxdev("ctl", "keys w");
        ctx.waitTicks(10);
        leg(ctx, where + " w again", ticks, 0);
        for (int i = 0; i < 9; i++) {
            ctx.runOnClient(mc -> mc.player.setYRot(mc.player.getYRot() + 10));
            ctx.waitTicks(1);
        }
        ctx.waitTicks(10);
        leg(ctx, where + " w, turned right", ticks, 0);
        for (int i = 0; i < 18; i++) {
            ctx.runOnClient(mc -> mc.player.setYRot(mc.player.getYRot() - 10));
            ctx.waitTicks(1);
        }
        ctx.waitTicks(10);
        leg(ctx, where + " w, turned left", ticks, 0);
        // W let go for a moment; a slow turn.
        gxdev("ctl", "keys");
        gxdev("ctl", "keys w");
        ctx.waitTicks(10);
        leg(ctx, where + " w, re-pressed", ticks, 0);
        for (int i = 0; i < 45; i++) {
            ctx.runOnClient(mc -> mc.player.setYRot(mc.player.getYRot() + 2));
            ctx.waitTicks(1);
        }
        ctx.waitTicks(5);
        leg(ctx, where + " w, slow turn right", ticks, 0);
        gxdev("ctl", "keys");
        ctx.waitTicks(20);
    }

    /** Mario's steps over some ticks, as an angle from our look (right positive); want: expected. */
    private void leg(ClientGameTestContext ctx, String what, int ticks, double want) {
        Vector3d[] at = whileRunning(ctx, () -> new Vector3d[] {peekVec(MBX_GRAVITY).negate(), peekVec(MBX_LOOK), peekVec(MBX_CAM_DIR)});
        Vector3d up = at[0], look = at[1], cam = at[2];
        Vector3d p0 = pos(ctx);
        ctx.waitTicks(ticks);
        Vector3d p1 = pos(ctx), front = whileRunning(ctx, () -> peekVec(MBX_FRONT));
        Vector3d step = new Vector3d(p1).sub(p0);
        double moved = flatLength(step, up);
        double a = angle(step, look, up), off = Math.abs(Math.IEEEremainder(a - want, 360));
        boolean bad = moved < 50 || off > 25;
        log(String.format("%-20s moved=%5.0f  step=%5.0f° (want %3.0f°)  game_cam=%5.0f°  front=%5.0f°  look_up=%5.2f%s", what,
                moved, a, want, angle(cam, look, up), angle(front, look, up), new Vector3d(look).normalize().dot(new Vector3d(up).normalize()),
                bad ? "  <-- off" : ""));
        if (bad) failed = true;
    }

    /** v's direction along the ground, in degrees from ref's, right of it positive. */
    private static double angle(Vector3d v, Vector3d ref, Vector3d up) {
        Vector3d f = flat(ref, up), r = new Vector3d(f).cross(up).normalize(), w = flat(v, up);
        return Math.toDegrees(Math.atan2(w.dot(r), w.dot(f)));
    }

    private static double flatLength(Vector3d v, Vector3d up) {
        Vector3d u = new Vector3d(up).normalize();
        return new Vector3d(v).sub(new Vector3d(u).mul(v.dot(u))).length();
    }

    private static Vector3d pos(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> new Vector3d(GalaxyCraftClient.galaxyPos().orElseThrow()));
    }

    /** v along the ground (perpendicular to up), unit. */
    private static Vector3d flat(Vector3d v, Vector3d up) {
        Vector3d u = new Vector3d(up).normalize();
        return new Vector3d(v).sub(new Vector3d(u).mul(v.dot(u))).normalize();
    }

    private static long mailbox() {
        Matcher m = MBX.matcher(gxdev("ctl", "mbx"));
        if (!m.find()) throw new AssertionError("no mailbox");
        return Long.parseLong(m.group(1), 16);
    }



    /**
     * Reads the dev Dolphin while Minecraft keeps ticking: between a test's ticks Minecraft stands
     * still, and the game waits for a stalled Minecraft (HostBridge::WaitForMod), past the
     * heartbeat timeout it stops following it and the mailbox's look is zero.
     */
    private static <T> T whileRunning(ClientGameTestContext ctx, java.util.function.Supplier<T> read) {
        java.util.concurrent.CompletableFuture<T> f = java.util.concurrent.CompletableFuture.supplyAsync(read);
        while (!f.isDone()) ctx.waitTicks(1);
        return f.join();
    }

    private static Vector3d peekVec(int offset) {
        return peekAt(mailbox() + offset);
    }

    private static Vector3d peekAt(long at) {
        String[] parts = gxdev("ctl", "peek 0x" + Long.toHexString(at) + " 12").strip().split("\\s+");
        float[] f = new float[3];
        for (int k = 0; k < 3; k++) {
            int v = 0;
            for (int i = 0; i < 4; i++) v = (v << 8) | Integer.parseInt(parts[parts.length - 12 + 4 * k + i], 16);
            f[k] = Float.intBitsToFloat(v);
        }
        return new Vector3d(f[0], f[1], f[2]);
    }

    private static void log(String msg) {
        System.out.println("[GalaxyCraft walk] " + msg);
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
