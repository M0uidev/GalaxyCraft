package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.GalaxyCraftClient;
import dev.moui.galaxycraft.client.GalaxyOptions;
import dev.moui.galaxycraft.client.PlanetClient;
import dev.moui.galaxycraft.client.StationClient;
import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.settings.Movement;
import dev.moui.galaxycraft.voxel.PlanetSession;
import dev.moui.galaxycraft.voxel.Station;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.InteractionHand;
import org.joml.Vector3d;

/**
 * A station in the real game, only with -Dgalaxycraft.stationGame=true (tools/gxvoxel.sh station):
 * in empty space a Station Core makes the slab, a row of blocks regrows it, Mario lands on it and
 * stands in its flat gravity, a player off its edge is in the void and one above it comes down
 * onto it, it shows from afar, and packing it (Mario on it) takes it out of the game. Screenshots:
 * station-*.png.
 */
public final class StationDolphinProbe implements FabricClientGameTest {
    private static final Pattern MBX = Pattern.compile("^at=([0-9a-f]+)", Pattern.MULTILINE);
    private static final Pattern IN_GAME = Pattern.compile("in_game=(\\w+)");
    private static final Pattern SPEED = Pattern.compile("max_speed=(\\S+)");
    private static final Pattern PARTS = Pattern.compile("parts=(\\d+)");
    /** GxcMailbox.anchor_pos: Mario's position. */
    private static final int MBX_MARIO = 36;
    /** Empty space: this many blocks above the stage. */
    private static final double SPACE_UP = 3000;
    private boolean failed;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.stationGame")) return;
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getServer().runCommand("gamemode survival @a");
            sp.getServer().runCommand("gamerule fall_damage false");
            sp.getServer().runCommand("effect give @a resistance infinite 255 true");
            sp.getServer().runCommand("effect give @a saturation infinite 255 true");
            sp.getServer().runCommand("time set day");
            sp.getServer().runCommand("tp @a 0 100 0 0 0");
            ctx.waitFor(mc -> GalaxyCraftClient.galaxyPos().isPresent(), 1200);
            ctx.waitTicks(40);
            String speedBefore = group(SPEED, gxdev("ctl", "status"));

            // Out to empty space, far above the stage.
            ctx.runOnClient(mc -> GalaxyOptions.MOVEMENT.set(Movement.MINECRAFT));
            ctx.runOnClient(mc -> PlanetClient.remove());
            ctx.waitTicks(10);
            Vector3d home = ctx.computeOnClient(mc -> new Vector3d(GalaxyCraftClient.galaxyPos().orElseThrow()));
            Vector3d space = new Vector3d(0, 1, 0).mul(units(SPACE_UP)).add(home);
            ctx.runOnClient(mc -> GalaxyCraftClient.moveTo(space));
            ctx.waitTicks(60);
            check(ctx.computeOnClient(mc -> GalaxyCraftClient.inVoid()), "far above the stage is space");
            int partsEmpty = parts();

            // 1. The core ahead of the player: the slab.
            sp.getServer().runCommand("item replace entity @a hotbar.0 with galaxycraft:station_core");
            ctx.waitTicks(5);
            PlanetSession s = ctx.computeOnClient(mc -> {
                StationClient.Spot at = StationClient.spot(mc.player);
                return at == null ? null : StationClient.place(mc.player, InteractionHand.MAIN_HAND, at);
            });
            check(s != null, "a station goes up in empty space");
            if (s == null) {
                log("FAIL");
                return;
            }
            Station st = ctx.computeOnClient(mc -> StationClient.of(s).orElseThrow());

            // 2. A row of 20 blocks outward: the grid regrows (and the game gets it again).
            int regrows = StationClient.regrows();
            for (int x = 5; x < 25; x++) {
                int bx = x;
                ctx.runOnClient(mc -> {
                    int c = st.grid().cellOf(bx, 0, 0);
                    if (c < 0) return;
                    s.planet().set(c, st.planet.blocks.parse("minecraft:stone"));
                    StationClient.afterPlace(s, c);
                });
                ctx.waitTicks(1);
            }
            check(StationClient.regrows() > regrows, "the row regrew the station");
            ctx.waitFor(mc -> s.queued() == 0, 1200);
            ctx.waitTicks(40);
            check(ctx.computeOnClient(mc -> PlanetClient.focus() == s), "the station is in focus");

            // 3. Mario lands on it and stands there in its flat gravity.
            ctx.runOnClient(mc -> GalaxyOptions.MOVEMENT.set(Movement.MARIO));
            ctx.waitTicks(20);
            ctx.runOnClient(mc -> PlanetClient.teleport());
            ctx.waitTicks(200);
            shot(ctx, "station-built");
            boolean stood = true;
            double low = Double.MAX_VALUE, high = -Double.MAX_VALUE;
            for (int i = 0; i < 5; i++) {
                waitReal(ctx, 1000);
                double h = height(ctx, s, mario());
                low = Math.min(low, h);
                high = Math.max(high, h);
                if (!"true".equals(group(IN_GAME, gxdev("ctl", "status")))) stood = false;
            }
            log(String.format("Mario on the station: %.2f to %.2f blocks above its slab", low, high));
            check(stood, "Mario stays in the game on the station");
            check(low > -0.3 && high < 3, "Mario stands on the slab, no fall");
            String speedOn = group(SPEED, gxdev("ctl", "status"));
            log("max_speed: " + speedBefore + " at the start, " + speedOn + " on the station");

            // 4. Off the edge: the void. Above it: down onto it.
            ctx.runOnClient(mc -> GalaxyOptions.MOVEMENT.set(Movement.MINECRAFT));
            ctx.waitTicks(20);
            Vector3d edge = ctx.computeOnClient(mc -> s.galOf(st.rotation.transform(new Vector3d(st.bounds.x1() + 40, 1, 0))));
            ctx.runOnClient(mc -> GalaxyCraftClient.moveTo(edge));
            boolean off = waitUntil(ctx, 60, () -> ctx.computeOnClient(mc -> GalaxyCraftClient.inVoid()));
            check(off, "past the edge is the void within 3 s");
            Vector3d over = ctx.computeOnClient(mc -> s.galOf(st.rotation.transform(new Vector3d(2, 6, 2))));
            ctx.runOnClient(mc -> GalaxyCraftClient.moveTo(over));
            boolean down = waitUntil(ctx, 200, () -> ctx.computeOnClient(mc -> mc.player.onGround()));
            double h = height(ctx, s, ctx.computeOnClient(mc -> new Vector3d(GalaxyCraftClient.galaxyPos().orElseThrow())));
            log(String.format("from 6 above: on ground %s, %.2f blocks above the slab", down, h));
            check(down && h > -0.3 && h < 2, "above the station falls down onto it");

            // 5. From 300 blocks off: its far view.
            Vector3d far = ctx.computeOnClient(mc -> s.galOf(st.rotation.transform(new Vector3d(0, 120, -300))));
            ctx.runOnClient(mc -> GalaxyCraftClient.moveTo(far));
            ctx.waitTicks(20);
            aimAt(ctx, s.center());
            ctx.waitTicks(100);
            aimAt(ctx, s.center());
            shot(ctx, "station-far");

            // 6. Packed while Mario stands on it: out of the game, Mario still in it.
            ctx.runOnClient(mc -> GalaxyCraftClient.moveTo(over));
            ctx.waitTicks(100);
            ctx.runOnClient(mc -> GalaxyOptions.MOVEMENT.set(Movement.MARIO));
            ctx.waitTicks(20);
            ctx.runOnClient(mc -> PlanetClient.teleport());
            ctx.waitTicks(200);
            int partsOn = parts();
            ctx.runOnClient(mc -> StationClient.pack(s));
            ctx.waitTicks(200);
            int partsPacked = parts();
            log("game parts: " + partsEmpty + " in empty space, " + partsOn + " with the station, " + partsPacked + " packed");
            check(ctx.computeOnClient(mc -> StationClient.sessions().isEmpty()), "packed: no station in space");
            check(partsOn > partsEmpty && partsPacked <= partsEmpty, "the game dropped the station");
            check("true".equals(group(IN_GAME, gxdev("ctl", "status"))), "Mario is still in the game");
            shot(ctx, "station-packed");

            // Back to the stage, where the other tests expect Mario.
            ctx.runOnClient(mc -> GalaxyOptions.MOVEMENT.set(Movement.MINECRAFT));
            ctx.runOnClient(mc -> GalaxyCraftClient.moveTo(home));
            ctx.waitTicks(100);
            ctx.runOnClient(mc -> GalaxyOptions.MOVEMENT.set(Movement.MARIO));
            ctx.waitTicks(60);
            log(failed ? "FAIL" : "PASS");
        }
    }

    // ---- helpers ----

    /** Blocks above the station's slab top (y = 0.5) at a galaxy point, along its up. */
    private static double height(ClientGameTestContext ctx, PlanetSession s, Vector3d gal) {
        return ctx.computeOnClient(mc -> {
            Station st = StationClient.of(s).orElseThrow();
            Vector3d base = s.galOf(st.rotation.transform(new Vector3d(0, 0.5, 0)));
            Vector3d up = s.galOf(st.rotation.transform(new Vector3d(0, 1.5, 0))).sub(base);
            return new Vector3d(gal).sub(base).dot(up) / up.lengthSquared();
        });
    }

    /** Looks at a galaxy point. */
    private static void aimAt(ClientGameTestContext ctx, Vector3d targetGal) {
        ctx.runOnClient(mc -> {
            GravityFrame f = GalaxyCraftClient.frame();
            Vector3d eye = f.toGal(new Vector3d(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ()));
            Vector3d d = f.dirToMc(new Vector3d(targetGal).sub(eye).normalize());
            mc.player.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
            mc.player.setXRot((float) Math.toDegrees(-Math.asin(Math.max(-1, Math.min(1, d.y)))));
        });
    }

    private static boolean waitUntil(ClientGameTestContext ctx, int ticks, java.util.function.BooleanSupplier ok) {
        for (int i = 0; i < ticks; i++) {
            if (ok.getAsBoolean()) return true;
            ctx.waitTicks(1);
        }
        return ok.getAsBoolean();
    }

    private static void waitReal(ClientGameTestContext ctx, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) ctx.waitTicks(1);
    }

    /** A Dolphin screenshot of the game a moment from now (test ticks outrun the game). */
    private static void shot(ClientGameTestContext ctx, String name) {
        waitReal(ctx, 500);
        log(gxdev("ctl", "shot " + name).strip());
    }

    private void check(boolean ok, String what) {
        log((ok ? "ok   " : "FAIL ") + what);
        if (!ok) failed = true;
    }

    private static double units(double blocks) {
        return blocks / GravityFrame.SCALE;
    }

    private static String group(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : "?";
    }

    private static long mailbox() {
        Matcher m = MBX.matcher(gxdev("ctl", "mbx"));
        if (!m.find()) throw new AssertionError("no mailbox");
        return Long.parseLong(m.group(1), 16);
    }

    private static Vector3d mario() {
        String[] parts = gxdev("ctl", "peek 0x" + Long.toHexString(mailbox() + MBX_MARIO) + " 12").strip().split("\\s+");
        float[] f = new float[3];
        for (int k = 0; k < 3; k++) {
            int v = 0;
            for (int i = 0; i < 4; i++) v = (v << 8) | Integer.parseInt(parts[parts.length - 12 + 4 * k + i], 16);
            f[k] = Float.intBitsToFloat(v);
        }
        return new Vector3d(f[0], f[1], f[2]);
    }

    /** The game's parts (the module's drawn pieces: chunks, far views) from the mailbox; -1 unknown. */
    private static int parts() {
        String g = group(PARTS, gxdev("ctl", "mbx"));
        return g.equals("?") ? -1 : Integer.parseInt(g);
    }

    private static void log(String msg) {
        System.out.println("[GalaxyCraft station] " + msg);
    }

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
