package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.GalaxyCraftClient;
import dev.moui.galaxycraft.client.GalaxyOptions;
import dev.moui.galaxycraft.client.PlanetClient;
import dev.moui.galaxycraft.client.StationClient;
import dev.moui.galaxycraft.voxel.PlanetSession;
import dev.moui.galaxycraft.voxel.Station;
import dev.moui.galaxycraft.client.ShipClient;
import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.settings.Movement;
import dev.moui.galaxycraft.ship.ShipHooks;
import dev.moui.galaxycraft.ship.ShipModules;
import dev.moui.galaxycraft.ship.ShipRecord;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.InteractionHand;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * The Toad ship in the real game, only with -Dgalaxycraft.shipGame=true (tools/gxvoxel.sh ship): in
 * empty space a ship goes down ahead of the player and is drawn from the disc's models (screenshots
 * ship-placed, ship-side, ship-top); right click boards it, W flies it, Shift leaves (ship-flying).
 * -Dgalaxycraft.shipDir has the five arcs.
 */
public final class ShipDolphinProbe implements FabricClientGameTest {
    private static final double SPACE_UP = Integer.getInteger("galaxycraft.shipUp", 3000);
    private boolean failed;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.shipGame")) return;
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getServer().runCommand("gamemode survival @a");
            sp.getServer().runCommand("gamerule fall_damage false");
            sp.getServer().runCommand("effect give @a resistance infinite 255 true");
            sp.getServer().runCommand("time set day");
            sp.getServer().runCommand("tp @a 0 100 0 0 0");
            ctx.waitFor(mc -> GalaxyCraftClient.galaxyPos().isPresent(), 1200);
            ctx.waitTicks(40);

            ctx.runOnClient(mc -> GalaxyOptions.MOVEMENT.set(Movement.MINECRAFT));
            ctx.runOnClient(mc -> PlanetClient.remove());
            ctx.waitTicks(10);
            Vector3d home = ctx.computeOnClient(mc -> new Vector3d(GalaxyCraftClient.galaxyPos().orElseThrow()));
            Vector3d space = new Vector3d(0, 1, 0).mul(units(SPACE_UP)).add(home);
            ctx.runOnClient(mc -> GalaxyCraftClient.moveTo(space));
            ctx.waitTicks(60);
            check(ctx.computeOnClient(mc -> GalaxyCraftClient.inVoid()), "far above the stage is space");

            ctx.runOnClient(mc -> ShipClient.clearAll());
            // 1. A full ship goes down ahead of the player.
            boolean placed = ctx.computeOnClient(mc ->
                    ShipHooks.get().place(mc.player, InteractionHand.MAIN_HAND, new ShipModules(2, 2, 2, 2)));
            check(placed, "the ship goes down in empty space");
            ShipRecord r = ctx.computeOnClient(mc -> ShipClient.ships().isEmpty() ? null : ShipClient.ships().get(0));
            if (r == null) {
                log("FAIL");
                return;
            }
            Vector3d center = new Vector3d(r.pos()[0], r.pos()[1], r.pos()[2]);
            Quaterniond q = new Quaterniond(r.rot()[0], r.rot()[1], r.rot()[2], r.rot()[3]);
            ctx.waitTicks(40);
            aimAt(ctx, center);
            ctx.waitTicks(60);
            shot(ctx, "ship-placed");

            // 1b. A full hold (a large chest and two small), drawn behind the seats.
            ctx.runOnClient(mc -> {
                var hold = ShipClient.cargoForTest(r);
                hold.setCapacity(4);
                for (int i = 0; i < 4; i++) hold.install(i);
                hold.join(0);
            });
            ctx.waitTicks(40);
            shot(ctx, "ship-hold-placed");

            // 2. From its side and from above, 14 blocks off.
            Vector3d side = new Vector3d(center).fma(units(14), q.transform(new Vector3d(1, 0, 0)));
            ctx.runOnClient(mc -> GalaxyCraftClient.moveTo(side));
            ctx.waitTicks(40);
            aimAt(ctx, center);
            ctx.waitTicks(60);
            aimAt(ctx, center);
            shot(ctx, "ship-side");
            Vector3d top = new Vector3d(center).fma(units(14), q.transform(new Vector3d(0, 1, 0)));
            ctx.runOnClient(mc -> GalaxyCraftClient.moveTo(top));
            ctx.waitTicks(40);
            aimAt(ctx, center);
            ctx.waitTicks(60);
            aimAt(ctx, center);
            shot(ctx, "ship-top");

            Vector3d close = new Vector3d(center).fma(units(5), q.transform(new Vector3d(0.7, 0.3, 0.7)));
            ctx.runOnClient(mc -> GalaxyCraftClient.moveTo(close));
            ctx.waitTicks(40);
            aimAt(ctx, center);
            ctx.waitTicks(40);
            aimAt(ctx, center);
            shot(ctx, "ship-near");
            String[] names = {"ship-hold-px", "ship-hold-nx", "ship-hold-top", "ship-hold-back", "ship-hold-low"};
            double[][] dirs = {{1, 0.15, 0}, {-1, 0.15, 0}, {0, 1, 0.05}, {0, 0.15, -1}, {1, -0.25, 0.1}};
            for (int k = 0; k < names.length; k++) {
                Vector3d at = new Vector3d(center).fma(units(k == 4 ? 5 : 3.6), q.transform(new Vector3d(dirs[k][0], dirs[k][1], dirs[k][2]).normalize()));
                ctx.runOnClient(mc -> GalaxyCraftClient.moveTo(at));
                ctx.waitTicks(40);
                aimAt(ctx, center);
                ctx.waitTicks(40);
                aimAt(ctx, center);
                shot(ctx, names[k]);
            }
            ctx.runOnClient(mc -> GalaxyCraftClient.moveTo(close));
            ctx.waitTicks(40);
            aimAt(ctx, center);
            ctx.waitTicks(40);

            // 3a. Solid: the galaxy's collision has boxes at the hull, and none far from it.
            ctx.runOnClient(mc -> GalaxyCraftClient.moveTo(side));
            ctx.waitTicks(40);
            int solidNear = ctx.computeOnClient(mc -> boxesAround(center, 3));
            int far = ctx.computeOnClient(mc -> boxesAround(new Vector3d(center).fma(units(40), q.transform(new Vector3d(1, 0, 0))), 3));
            log("collision boxes within 3 blocks of the ship: " + solidNear + ", of empty space 40 blocks off: " + far);
            check(solidNear > 10 && far == 0, "the ship is solid: its hull is in the collision");

            // 3b. Right click boards, W flies, Shift leaves.
            Vector3d near = new Vector3d(center).fma(units(4), q.transform(new Vector3d(0, 0, -1)));
            ctx.runOnClient(mc -> GalaxyCraftClient.moveTo(near));
            ctx.waitTicks(30);
            aimAt(ctx, center);
            ctx.waitTicks(5);
            gxdev("ctl", "keys rmb"); // the game's input, as the player's mouse
            waitReal(ctx, 300);
            gxdev("ctl", "keys");
            waitReal(ctx, 500);
            check(ctx.computeOnClient(mc -> ShipClient.aboard().isPresent()), "right click boards the ship");
            // E aboard opens the ship's screen.
            ctx.getInput().pressKey(o -> o.keyInventory);
            ctx.waitTicks(20);
            String opened = ctx.computeOnClient(mc -> mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName());
            check(opened.equals("ShipScreen"), "E aboard opens the ship screen (got " + opened + ")");
            shot(ctx, "ship-screen-e");
            ctx.runOnClient(mc -> mc.player.closeContainer());
            ctx.waitTicks(10);
            Vector3d before = ctx.computeOnClient(mc -> new Vector3d(GalaxyCraftClient.galaxyPos().orElseThrow()));
            ctx.getInput().holdKey(o -> o.keyUp);
            waitReal(ctx, 5000);
            Vector3d after = ctx.computeOnClient(mc -> new Vector3d(GalaxyCraftClient.galaxyPos().orElseThrow()));
            log(String.format("flew %.1f blocks in 5 s", after.distance(before) * GravityFrame.SCALE));
            check(after.distance(before) * GravityFrame.SCALE > 20, "W flies the ship");
            // Smooth: the player's old position is the one before this tick's move (the view is drawn between).
            ctx.getInput().holdKey(o -> o.keyUp);
            waitReal(ctx, 500);
            double stride = ctx.computeOnClient(mc -> Math.sqrt(Math.pow(mc.player.getX() - mc.player.xo, 2)
                    + Math.pow(mc.player.getY() - mc.player.yo, 2) + Math.pow(mc.player.getZ() - mc.player.zo, 2)));
            log(String.format("last tick's step, seen between ticks: %.3f blocks", stride));
            check(stride > 0.05, "the flight is drawn between ticks (old position kept)");
            ctx.getInput().releaseKey(o -> o.keyUp);
            // Turbo: Ctrl with W is faster than W alone.
            Vector3d t0 = ctx.computeOnClient(mc -> new Vector3d(GalaxyCraftClient.galaxyPos().orElseThrow()));
            ctx.getInput().holdKey(o -> o.keyUp);
            waitReal(ctx, 3000);
            Vector3d t1 = ctx.computeOnClient(mc -> new Vector3d(GalaxyCraftClient.galaxyPos().orElseThrow()));
            ctx.getInput().holdKey(o -> o.keySprint);
            waitReal(ctx, 3000);
            Vector3d t2 = ctx.computeOnClient(mc -> new Vector3d(GalaxyCraftClient.galaxyPos().orElseThrow()));
            ctx.getInput().releaseKey(o -> o.keySprint);
            ctx.getInput().releaseKey(o -> o.keyUp);
            double plain = t1.distance(t0) * GravityFrame.SCALE, turbo = t2.distance(t1) * GravityFrame.SCALE;
            log(String.format("3 s of W: %.1f blocks; 3 s with the turbo: %.1f", plain, turbo));
            check(turbo > plain * 1.3, "the turbo is faster");
            // Space climbs, S goes back, A strafes.
            Vector3d up = ctx.computeOnClient(mc -> GalaxyCraftClient.frame().upGal().normalize());
            Vector3d a0 = t2;
            ctx.getInput().releaseKey(o -> o.keyUp);
            ctx.getInput().holdKey(o -> o.keyJump);
            waitReal(ctx, 2000);
            ctx.getInput().releaseKey(o -> o.keyJump);
            Vector3d a1 = ctx.computeOnClient(mc -> new Vector3d(GalaxyCraftClient.galaxyPos().orElseThrow()));
            double climbed = new Vector3d(a1).sub(a0).dot(up) * GravityFrame.SCALE;
            log(String.format("Space climbed %.1f blocks in 2 s", climbed));
            check(climbed > 4, "Space climbs");
            ctx.getInput().holdKey(o -> o.keyLeft);
            waitReal(ctx, 2000);
            ctx.getInput().releaseKey(o -> o.keyLeft);
            Vector3d a2 = ctx.computeOnClient(mc -> new Vector3d(GalaxyCraftClient.galaxyPos().orElseThrow()));
            log(String.format("A strafed %.1f blocks in 2 s", a2.distance(a1) * GravityFrame.SCALE));
            check(a2.distance(a1) * GravityFrame.SCALE > 4, "A strafes");
            shot(ctx, "ship-flying");
            waitReal(ctx, 300);
            String aboardId = ctx.computeOnClient(mc -> ShipClient.aboard().orElseThrow().id());
            ctx.getInput().holdKey(o -> o.keyShift);
            waitReal(ctx, 600);
            ctx.getInput().releaseKey(o -> o.keyShift);
            waitReal(ctx, 500);
            check(ctx.computeOnClient(mc -> ShipClient.aboard().isEmpty()), "Shift leaves the ship");
            Vector3d outside = ctx.computeOnClient(mc -> new Vector3d(GalaxyCraftClient.galaxyPos().orElseThrow()));
            ShipRecord parked = ctx.computeOnClient(mc -> ShipClient.ships().stream().filter(x -> x.id().equals(aboardId)).findFirst().orElseThrow());
            double gap = outside.distance(new Vector3d(parked.pos()[0], parked.pos()[1], parked.pos()[2])) * GravityFrame.SCALE;
            log(String.format("after leaving: %.1f blocks from the ship's middle", gap));
            check(gap > 3.0, "leaving puts the player outside the hull");

            // 5. A landing pad: board again, fly to 7 blocks above it, hold descend: docked on it.
            Vector3d padAt = new Vector3d(outside).fma(units(30), q.transform(new Vector3d(1, 0, 0)));
            ctx.runOnClient(mc -> ShipClient.addPad(padAt, q));
            ctx.waitTicks(20);
            Vector3d lookPad = new Vector3d(padAt).fma(units(10), q.transform(new Vector3d(0.5, 0.5, -1)));
            ctx.runOnClient(mc -> GalaxyCraftClient.moveTo(lookPad));
            ctx.waitTicks(40);
            aimAt(ctx, padAt);
            ctx.waitTicks(40);
            aimAt(ctx, padAt);
            shot(ctx, "pad-placed");
            Vector3d aboveIt = new Vector3d(padAt).fma(units(7), q.transform(new Vector3d(0, 1, 0)));
            Vector3d ship0 = ctx.computeOnClient(mc -> {
                ShipRecord s0 = ShipClient.ships().stream().filter(x -> x.id().equals(aboardId)).findFirst().orElseThrow();
                return new Vector3d(s0.pos()[0], s0.pos()[1], s0.pos()[2]);
            });
            ctx.runOnClient(mc -> GalaxyCraftClient.moveTo(new Vector3d(ship0).fma(units(1.5), q.transform(new Vector3d(1, 0, 0)))));
            ctx.waitTicks(20);
            aimAt(ctx, ship0);
            ctx.waitTicks(5);
            gxdev("ctl", "keys rmb");
            waitReal(ctx, 300);
            gxdev("ctl", "keys");
            waitReal(ctx, 500);
            check(ctx.computeOnClient(mc -> ShipClient.aboard().isPresent()), "boarded again for the pad");
            ctx.runOnClient(mc -> GalaxyCraftClient.moveTo(aboveIt));
            waitReal(ctx, 800);
            ctx.runOnClient(mc -> ShipClient.forceDescend(true));
            boolean docked = waitUntilReal(ctx, 15000, () -> ctx.computeOnClient(mc -> ShipClient.dockedPad() != null));
            ctx.runOnClient(mc -> ShipClient.forceDescend(false));
            check(docked, "holding descend over the pad docks the ship on it");
            shot(ctx, "pad-docked");
            ctx.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK));
            ctx.waitTicks(40);
            shot(ctx, "pad-docked-3p");
            ctx.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON));
            Vector3d at = ctx.computeOnClient(mc -> new Vector3d(GalaxyCraftClient.galaxyPos().orElseThrow()));
            log(String.format("docked: %.2f blocks from the point above the pad's centre", at.distance(new Vector3d(padAt).fma(units(1.325), q.transform(new Vector3d(0, 1, 0)))) * GravityFrame.SCALE));
            ctx.getInput().holdKey(o -> o.keyJump);
            waitReal(ctx, 1200);
            ctx.getInput().releaseKey(o -> o.keyJump);
            check(ctx.computeOnClient(mc -> ShipClient.dockedPad() == null), "Space lifts off from the pad");
            ctx.runOnClient(mc -> ShipClient.leave());

            // 6. On a space station: the pad lies on its slab, the ship rests on its keel, both seen as a ghost first.
            ctx.runOnClient(mc -> ShipClient.clearAll());
            ctx.runOnClient(mc -> GalaxyCraftClient.moveTo(space));
            ctx.waitTicks(40);
            sp.getServer().runCommand("item replace entity @a hotbar.0 with galaxycraft:station_core");
            ctx.waitTicks(5);
            PlanetSession st0 = ctx.computeOnClient(mc -> {
                StationClient.Spot at0 = StationClient.spot(mc.player);
                return at0 == null ? null : StationClient.place(mc.player, InteractionHand.MAIN_HAND, at0);
            });
            check(st0 != null, "a station goes up for the pad and the ship");
            if (st0 != null) {
                ctx.waitFor(mc -> st0.queued() == 0, 1200);
                ctx.waitTicks(40);
                Station stn = ctx.computeOnClient(mc -> StationClient.of(st0).orElseThrow());
                Vector3d over = ctx.computeOnClient(mc -> st0.galOf(stn.rotation.transform(new Vector3d(2, 5, 2))));
                Vector3d slab = ctx.computeOnClient(mc -> st0.galOf(stn.rotation.transform(new Vector3d(3, 0.5, 4.5))));
                ctx.runOnClient(mc -> GalaxyCraftClient.moveTo(over));
                ctx.waitTicks(60);
                sp.getServer().runCommand("item replace entity @a hotbar.0 with galaxycraft:landing_pad");
                ctx.waitTicks(10);
                aimAt(ctx, slab);
                ctx.waitTicks(20);
                shot(ctx, "pad-ghost");
                log(ctx.computeOnClient(mc -> {
                    var bodies = PlanetClient.bodies();
                    var f = GalaxyCraftClient.frame();
                    Vector3d eye = f.toGal(new Vector3d(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ()));
                    Vector3d look = f.dirToGal(dev.moui.galaxycraft.gravity.LookMath.direction(mc.player.getYRot(), mc.player.getXRot()));
                    StringBuilder sb = new StringBuilder("bodies " + bodies.size() + ", player y " + mc.player.getY() + " onGround " + mc.player.onGround());
                    for (var b : bodies) {
                        var h = b.surfaceAt(eye, look);
                        var d = b.surfaceAlong(eye, f.upGal().negate(), 14);
                        sb.append(" [active " + b.active() + " ahead " + (h != null) + " down " + (d != null) + " center " + b.center() + "]");
                    }
                    return sb.toString();
                }));
                boolean padOk = ctx.computeOnClient(mc -> dev.moui.galaxycraft.ship.ShipHooks.get().placePad(mc.player, InteractionHand.MAIN_HAND));
                check(padOk, "a landing pad goes down on a station's slab");
                ShipRecord padRec = ctx.computeOnClient(mc -> ShipClient.ships().stream().filter(ShipRecord::isPad).findFirst().orElse(null));
                if (padRec != null) {
                    double h = slabHeight(ctx, st0, new Vector3d(padRec.pos()[0], padRec.pos()[1], padRec.pos()[2]));
                    log(String.format("the pad's middle is %.2f blocks above the slab's top", h));
                    check(h > 0.05 && h < 0.4, "the pad lies flat on the slab");
                }
                sp.getServer().runCommand("item replace entity @a hotbar.0 with galaxycraft:toad_ship");
                ctx.waitTicks(10);
                Vector3d over2 = ctx.computeOnClient(mc -> st0.galOf(stn.rotation.transform(new Vector3d(-1, 5, -1))));
                Vector3d slab2 = ctx.computeOnClient(mc -> st0.galOf(stn.rotation.transform(new Vector3d(-1, 0.5, -4))));
                ctx.runOnClient(mc -> GalaxyCraftClient.moveTo(over2));
                ctx.waitTicks(60);
                aimAt(ctx, slab2);
                ctx.waitTicks(20);
                shot(ctx, "ship-ghost");
                boolean shipOk = ctx.computeOnClient(mc -> dev.moui.galaxycraft.ship.ShipHooks.get().place(mc.player, InteractionHand.MAIN_HAND,
                        new ShipModules(1, 1, 1, 1)));
                check(shipOk, "a ship goes down on a station's slab");
                ShipRecord shipRec = ctx.computeOnClient(mc -> ShipClient.ships().stream().filter(x -> !x.isPad()).findFirst().orElse(null));
                if (shipRec != null) {
                    double h = slabHeight(ctx, st0, new Vector3d(shipRec.pos()[0], shipRec.pos()[1], shipRec.pos()[2]));
                    log(String.format("the ship's middle is %.2f blocks above the slab's top", h));
                    check(h > 1.7 && h < 2.6, "the ship rests on its keel on the slab, not under it");
                }
            }

            ctx.runOnClient(mc -> GalaxyCraftClient.moveTo(home));
            ctx.waitTicks(100);
            ctx.runOnClient(mc -> GalaxyOptions.MOVEMENT.set(Movement.MARIO));
            ctx.waitTicks(60);
            log(failed ? "FAIL" : "PASS");
        }
    }

    // ---- helpers ----

    /** Blocks above the station's slab top (y = 0.5) at a galaxy point, along its up. */
    private static double slabHeight(ClientGameTestContext ctx, PlanetSession s, Vector3d gal) {
        return ctx.computeOnClient(mc -> {
            Station st = StationClient.of(s).orElseThrow();
            Vector3d base = s.galOf(st.rotation.transform(new Vector3d(0, 0.5, 0)));
            Vector3d up = s.galOf(st.rotation.transform(new Vector3d(0, 1.5, 0))).sub(base);
            return new Vector3d(gal).sub(base).dot(up) / up.lengthSquared();
        });
    }

    /** Collision boxes within r blocks of a galaxy point. */
    private static int boxesAround(Vector3d gal, double r) {
        GravityFrame f = GalaxyCraftClient.frame();
        Vector3d mc = f.toMc(gal);
        return dev.moui.galaxycraft.GalaxyCraft.FIELD.boxesFor(new double[] {mc.x - r, mc.y - r, mc.z - r, mc.x + r, mc.y + r, mc.z + r}).size();
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

    private static boolean waitUntilReal(ClientGameTestContext ctx, long ms, java.util.function.BooleanSupplier ok) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
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

    private static void log(String msg) {
        System.out.println("[GalaxyCraft ship] " + msg);
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
