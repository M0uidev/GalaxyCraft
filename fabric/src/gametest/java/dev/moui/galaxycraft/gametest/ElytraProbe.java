package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.GalaxyCraftClient;
import dev.moui.galaxycraft.client.GalaxyOptions;
import dev.moui.galaxycraft.client.PlanetClient;
import dev.moui.galaxycraft.gravity.CosmicWind;
import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.settings.Movement;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import org.joml.Vector3d;

/**
 * The elytra in the real game, only with -Dgalaxycraft.elytra=true (tools/gxvoxel.sh elytra): two
 * planets; from the first, a glide with rockets out of its gravity, across the void and down onto
 * the second, in Minecraft's movement and in Mario's (Mario keeps up); then the cosmic wind brings
 * a player far out back. Screenshots: elytra-*.png.
 */
public final class ElytraProbe implements FabricClientGameTest {
    private static final Pattern MBX = Pattern.compile("^at=([0-9a-f]+)", Pattern.MULTILINE);
    private static final Pattern IN_GAME = Pattern.compile("in_game=(\\w+)");
    /** GxcMailbox.anchor_pos: Mario's position. */
    private static final int MBX_MARIO = 36;
    private static final int ROCKETS = 64;
    /** Empty space: this many blocks above the stage. */
    private static final double SPACE_UP = 3000;
    private boolean failed;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.elytra")) return;
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getServer().runCommand("gamemode survival @a");
            sp.getServer().runCommand("gamerule fall_damage false");
            sp.getServer().runCommand("effect give @a resistance infinite 255 true");
            sp.getServer().runCommand("effect give @a saturation infinite 255 true");
            sp.getServer().runCommand("time set day");
            sp.getServer().runCommand("tp @a 0 100 0 0 0");
            ctx.waitFor(mc -> GalaxyCraftClient.galaxyPos().isPresent(), 1200);
            ctx.waitTicks(40);

            // Out to empty space, far above the stage (the prologue): nothing there but the planets.
            ctx.runOnClient(mc -> GalaxyOptions.MOVEMENT.set(Movement.MINECRAFT));
            ctx.runOnClient(mc -> PlanetClient.remove());
            ctx.waitTicks(10);
            Vector3d home = ctx.computeOnClient(mc -> new Vector3d(GalaxyCraftClient.galaxyPos().orElseThrow()));
            Vector3d space = new Vector3d(0, 1, 0).mul(units(SPACE_UP)).add(home);
            log("before space: SMG2 in game " + group(IN_GAME, gxdev("ctl", "status")));
            ctx.runOnClient(mc -> GalaxyCraftClient.moveTo(space));
            for (int i = 0; i < 6; i++) {
                ctx.waitTicks(10);
                log("  in space +" + (i * 10 + 10) + " ticks: SMG2 in game " + group(IN_GAME, gxdev("ctl", "status")));
            }
            log(String.format("in empty space %.0f blocks above the stage: space %s, on ground %s", SPACE_UP,
                    ctx.computeOnClient(mc -> GalaxyCraftClient.inVoid()), ctx.computeOnClient(mc -> mc.player.onGround())));
            check(ctx.computeOnClient(mc -> GalaxyCraftClient.inVoid()), "far above the stage is space");
            gxdev("ctl", "shot elytra-space");

            PlanetSession a = PlanetClient.session();
            ctx.runOnClient(mc -> PlanetClient.requestSpawn(PlanetBlueprint.standard("elytra", 48)));
            ctx.waitFor(mc -> a.active() && a.queued() == 0, 2000);
            ctx.runOnClient(mc -> PlanetClient.requestAdd(32));
            ctx.waitFor(mc -> PlanetClient.planets().size() == 2 && PlanetClient.planets().get(1).active()
                    && PlanetClient.planets().get(1).queued() == 0, 2000);
            PlanetSession b = ctx.computeOnClient(mc -> PlanetClient.planets().get(1));
            log(String.format("planets %.0f blocks apart; gravity reaches %.0f and %.0f", a.center().distance(b.center()) / units(1),
                    a.gravityUnits() / units(1), b.gravityUnits() / units(1)));

            fly(sp, ctx, Movement.MINECRAFT, a, b, "mc");
            fly(sp, ctx, Movement.MARIO, b, a, "mario");
            wind(sp, ctx, a);

            // Back to the stage, where the other tests expect Mario.
            ctx.runOnClient(mc -> PlanetClient.remove());
            ctx.runOnClient(mc -> PlanetClient.remove());
            ctx.runOnClient(mc -> GalaxyOptions.MOVEMENT.set(Movement.MINECRAFT));
            ctx.runOnClient(mc -> GalaxyCraftClient.moveTo(home));
            ctx.waitTicks(100);
            ctx.runOnClient(mc -> GalaxyOptions.MOVEMENT.set(Movement.MARIO));
            ctx.waitTicks(60);
            log(failed ? "FAIL" : "PASS");
        }
    }

    /** From one planet to the other: take off, rockets toward it, land on it. */
    private void fly(TestSingleplayerContext sp, ClientGameTestContext ctx, Movement mode, PlanetSession from,
            PlanetSession to, String tag) {
        log("---- " + mode.label() + ": " + tag + ", SMG2 in game " + group(IN_GAME, gxdev("ctl", "status")));
        sp.getServer().runCommand("item replace entity @a armor.chest with elytra");
        sp.getServer().runCommand("item replace entity @a hotbar.0 with firework_rocket " + ROCKETS);
        ctx.runOnClient(mc -> GalaxyOptions.MOVEMENT.set(mode));
        if (mode == Movement.MINECRAFT) ctx.runOnClient(mc -> PlanetClient.teleport()); // later legs start where the last landed
        ctx.waitTicks(200);
        double start = height(ctx, from);
        log(String.format("on the planet: %.1f blocks from its center (surface %.0f)", start, from.planet().surface()));

        boolean open = false;
        for (int attempt = 0; attempt < 3 && !open; attempt++) {
            takeOff(ctx, mode);
            open = waitUntil(ctx, 40, () -> ctx.computeOnClient(mc -> mc.player.isFallFlying()));
            if (!open) {
                log(String.format("take-off %d: no elytra; %.2f above the ground, on ground %s, following %s", attempt,
                        height(ctx, from) - from.planet().surface(), ctx.computeOnClient(mc -> mc.player.onGround()),
                        ctx.computeOnClient(mc -> !GalaxyCraftClient.ownPhysics())));
                log("  " + gxdev("ctl", "status").replace('\n', ' '));
                gxdev("ctl", "shot elytra-" + tag + "-notakeoff" + attempt);
            }
            ctx.waitTicks(20);
        }
        int retries = 3;
        check(open, "the elytra open on a jump in the air");
        if (!open) return;
        gxdev("ctl", "shot elytra-" + tag + "-takeoff");

        // Rockets toward the other planet until in its gravity.
        int tick = 0;
        boolean wasVoid = false, upFrozen = true, marioClose = true;
        Vector3d voidUp = null;
        double worstMario = 0;
        for (; tick < 1200; tick++) {
            // Straight up out of this planet's gravity first (toward the other one would go through
            // the planet), then toward the other one.
            boolean out = ctx.computeOnClient(mc -> GalaxyCraftClient.inVoid())
                    || playerGal(ctx).distance(from.center()) > from.gravityUnits();
            if (out) aimAt(ctx, to.center());
            else aimAt(ctx, new Vector3d(playerGal(ctx)).sub(from.center()).normalize().mul(units(1000)).add(playerGal(ctx)));
            if (tick < 12) {
                net.minecraft.world.phys.Vec3 serverPos = sp.getServer().computeOnServer(server ->
                        server.getPlayerList().getPlayers().get(0).position());
                log(String.format("  t=%d gliding %s ground %s h=%.2f v=%.2f rockets seen %d, server player %.1f off", tick,
                        ctx.computeOnClient(mc -> mc.player.isFallFlying()), ctx.computeOnClient(mc -> mc.player.onGround()),
                        height(ctx, from), ctx.computeOnClient(mc -> mc.player.getDeltaMovement().length()),
                        ctx.computeOnClient(mc -> { int n = 0; for (var e : mc.level.entitiesForRendering())
                            if (e instanceof net.minecraft.world.entity.projectile.FireworkRocketEntity) n++; return n; }),
                        ctx.computeOnClient(mc -> mc.player.position().distanceTo(serverPos))));
            }
            if (tick < 30 ? tick % 3 == 0 : tick % 25 == 0) {
                String used = ctx.computeOnClient(mc -> mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND).toString());
                if (tick % 100 == 0) log(String.format("t=%d rocket %s; gliding %s, space %s, %.0f from here, %.0f to go, speed %.2f",
                        tick, used, ctx.computeOnClient(mc -> mc.player.isFallFlying()), ctx.computeOnClient(mc -> GalaxyCraftClient.inVoid()),
                        height(ctx, from), height(ctx, to), ctx.computeOnClient(mc -> mc.player.getDeltaMovement().length())));
            }
            if (!ctx.computeOnClient(mc -> mc.player.isFallFlying()) && retries-- > 0) {
                log(String.format("t=%d the elytra closed (ground %s): again", tick, ctx.computeOnClient(mc -> mc.player.onGround())));
                ctx.waitTicks(10);
                takeOff(ctx, mode);
                continue;
            }
            if (!ctx.computeOnClient(mc -> mc.player.isFallFlying())) {
                log(String.format("t=%d the elytra closed, on ground %s, %.0f from here", tick,
                        ctx.computeOnClient(mc -> mc.player.onGround()), height(ctx, from)));
                break;
            }
            ctx.waitTicks(1);
            if (tick == 60) ctx.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK));
            if (tick == 90) gxdev("ctl", "shot elytra-" + tag + "-flying");
            if (tick % 60 == 0) log("  t=" + tick + " SMG2 in game " + group(IN_GAME, gxdev("ctl", "status")));
            boolean v = ctx.computeOnClient(mc -> GalaxyCraftClient.inVoid());
            Vector3d up = ctx.computeOnClient(mc -> GalaxyCraftClient.galaxyUp().orElseThrow());
            if (v) {
                if (voidUp == null) voidUp = up;
                else if (voidUp.angle(up) > Math.toRadians(1)) upFrozen = false;
                if (!wasVoid) gxdev("ctl", "shot elytra-" + tag + "-void");
                wasVoid = true;
            }
            if (tick % 20 == 0 && mode != Movement.MINECRAFT) {
                double off = marioToPlayer(ctx) / units(1);
                worstMario = Math.max(worstMario, off);
                if (off > 3) marioClose = false;
            }
            if (playerGal(ctx).distance(to.center()) < to.gravityUnits() * 0.9) break;
        }
        log(String.format("reached the other planet's gravity after %d ticks; void %s", tick, wasVoid));
        check(tick < 1200, "flew into the other planet's gravity");
        if (wasVoid) check(upFrozen, "up stays as it was in the void");
        else log("note: no void between them (the stage's own gravity fills the space)");
        if (mode != Movement.MINECRAFT) {
            log(String.format("Mario at most %.1f blocks from the player in flight", worstMario));
            check(marioClose, "Mario keeps up with the flight");
        }

        // Down onto it: look at its center, no more rockets; up turns to it on the way.
        boolean landed = false;
        for (int i = 0; i < 1200 && !landed; i++) {
            aimAt(ctx, to.center());
            ctx.waitTicks(1);
            landed = ctx.computeOnClient(mc -> mc.player.onGround() && !mc.player.isFallFlying());
        }
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON));
        log("landed; SMG2 in game " + group(IN_GAME, gxdev("ctl", "status")));
        gxdev("ctl", "shot elytra-" + tag + "-landed-game");
        double h = height(ctx, to);
        Vector3d up = ctx.computeOnClient(mc -> GalaxyCraftClient.galaxyUp().orElseThrow());
        Vector3d out = playerGal(ctx).sub(to.center()).normalize();
        double tilt = Math.toDegrees(up.angle(out));
        log(String.format("landed %s: %.1f blocks from its center (surface %.0f), up %.1f° off", landed, h,
                to.planet().surface(), tilt));
        check(landed, "lands");
        check(Math.abs(h - to.planet().surface()) < 3, "on the other planet's surface");
        check(tilt < 10, "standing up on it");
        gxdev("ctl", "shot elytra-" + tag + "-landed");
        int rockets = ctx.computeOnClient(mc -> mc.player.getInventory().getItem(0).getCount());
        int wear = ctx.computeOnClient(mc -> mc.player.getItemBySlot(EquipmentSlot.CHEST).getDamageValue());
        log("rockets left " + rockets + ", elytra wear " + wear);
        check(rockets < ROCKETS, "rockets are spent");
        check(wear > 0, "the elytra wear");
        if (mode != Movement.MINECRAFT) {
            ctx.waitTicks(40);
            check(ctx.computeOnClient(mc -> !GalaxyCraftClient.ownPhysics()), "Mario's mode again after landing");
            check(marioToPlayer(ctx) < units(2), "the player is with Mario");
            check("true".equals(group(IN_GAME, gxdev("ctl", "status"))), "Mario is still in the game");
        }
    }

    /** Far past every gravity, with nothing: the wind brings the player back. */
    private void wind(TestSingleplayerContext sp, ClientGameTestContext ctx, PlanetSession a) {
        log("---- the cosmic wind");
        sp.getServer().runCommand("item replace entity @a armor.chest with air");
        ctx.runOnClient(mc -> GalaxyOptions.MOVEMENT.set(Movement.MINECRAFT));
        ctx.waitTicks(20);
        double reach = a.gravityUnits() / units(1);
        double far = ctx.computeOnClient(mc -> {
            double max = 0;
            for (PlanetSession s : PlanetClient.planets()) max = Math.max(max, s.center().distance(a.center()) / units(1)
                    + s.gravityUnits() / units(1));
            return max;
        });
        Vector3d up = ctx.computeOnClient(mc -> GalaxyCraftClient.galaxyUp().orElseThrow());
        Vector3d spot = new Vector3d(up).mul(units(far + CosmicWind.FREE + 200)).add(a.center());
        ctx.runOnClient(mc -> GalaxyCraftClient.moveTo(spot));
        ctx.waitTicks(5);
        double first = (playerGal(ctx).distance(a.center()) / units(1)) - reach;
        boolean back = false;
        int t = 0;
        for (; t < 3000 && !back; t += 10) {
            ctx.waitTicks(10);
            if (t % 150 == 0) log(String.format("  t=%d past %.0f, v %.2f, noGravity %s, gliding %s, own physics %s, void %s", t,
                    playerGal(ctx).distance(a.center()) / units(1) - reach, ctx.computeOnClient(mc -> mc.player.getDeltaMovement().length()),
                    ctx.computeOnClient(mc -> mc.player.isNoGravity()), ctx.computeOnClient(mc -> mc.player.isFallFlying()),
                    ctx.computeOnClient(mc -> GalaxyCraftClient.ownPhysics()), ctx.computeOnClient(mc -> GalaxyCraftClient.inVoid())));
            back = !ctx.computeOnClient(mc -> GalaxyCraftClient.inVoid());
        }
        log(String.format("from %.0f blocks past the gravity, back in a planet's gravity after %d ticks: %s", first, t, back));
        check(ctx.computeOnClient(mc -> GalaxyCraftClient.inVoid()) || back, "far out is the void");
        check(back, "the wind brings the player back");
    }

    // ---- helpers ----

    /** A jump (Mario's own in his mode), then a second press in the air opens the elytra. */
    private static void takeOff(ClientGameTestContext ctx, Movement mode) {
        if (mode == Movement.MINECRAFT) {
            ctx.getInput().holdKeyFor(o -> o.keyJump, 2);
            ctx.waitTicks(5);
        } else {
            gxdev("ctl", "keys space");
            waitReal(ctx, 350);
            gxdev("ctl", "keys");
        }
        ctx.getInput().holdKeyFor(o -> o.keyJump, 2);
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

    private void check(boolean ok, String what) {
        log((ok ? "ok   " : "FAIL ") + what);
        if (!ok) failed = true;
    }

    private static double units(double blocks) {
        return blocks / GravityFrame.SCALE;
    }

    private static double height(ClientGameTestContext ctx, PlanetSession s) {
        return playerGal(ctx).distance(s.center()) / units(1);
    }

    private static Vector3d playerGal(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> new Vector3d(GalaxyCraftClient.galaxyPos().orElseThrow()));
    }

    private static double marioToPlayer(ClientGameTestContext ctx) {
        return peekAt(mailbox() + MBX_MARIO).distance(playerGal(ctx));
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
        System.out.println("[GalaxyCraft elytra] " + msg);
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
