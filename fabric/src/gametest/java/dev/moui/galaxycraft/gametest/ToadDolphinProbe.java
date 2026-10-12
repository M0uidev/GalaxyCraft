package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.GalaxyCraftClient;
import dev.moui.galaxycraft.client.GalaxyOptions;
import dev.moui.galaxycraft.client.PlanetClient;
import dev.moui.galaxycraft.client.ToadClient;
import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.gravity.LookMath;
import dev.moui.galaxycraft.settings.Movement;
import dev.moui.galaxycraft.toad.ToadType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * The Toads in the real game, only with -Dgalaxycraft.toadGame=true (tools/gxvoxel.sh toad): in empty
 * space a Toad goes down ahead of the player and is drawn by the game from the disc's Kinopio
 * (screenshots toad-front, toad-side); a right click on it talks (ToadClient.talks goes up) and a
 * right click on nothing does not. -Dgalaxycraft.shipDir has Kinopio.arc.
 */
public final class ToadDolphinProbe implements FabricClientGameTest {
    private static final double SPACE_UP = Integer.getInteger("galaxycraft.shipUp", 3000);
    private boolean failed;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.toadGame")) return;
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

            ctx.runOnClient(mc -> ToadClient.clearAll());
            // 1. The five types go down in a row 4 blocks ahead, the Guide in the middle, facing the player.
            ToadType[] row = {ToadType.MINER, ToadType.EXPLORER, ToadType.GUIDE, ToadType.TECHNICIAN, ToadType.MAILMAN};
            Vector3d feet = ctx.computeOnClient(mc -> {
                GravityFrame f = GalaxyCraftClient.frame();
                Vector3d eye = f.toGal(new Vector3d(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ()));
                Vector3d look = f.dirToGal(LookMath.direction(mc.player.getYRot(), mc.player.getXRot()));
                Vector3d up = f.upGal().normalize();
                Vector3d flat = new Vector3d(look).fma(-look.dot(up), up).normalize();
                Vector3d at = new Vector3d(eye).fma(units(4), flat).fma(-units(1.6), up);
                Vector3d back = new Vector3d(flat).negate();
                Vector3d right = new Vector3d(up).cross(back);
                Quaterniond q = new Quaterniond().setFromNormalized(new Matrix3d(right, up, back));
                for (int i = 0; i < row.length; i++)
                    ToadClient.put(row[i], new Vector3d(at).fma(units((i - 2) * 1.5), right), q);
                return at;
            });
            check(ctx.computeOnClient(mc -> ToadClient.toads().size()) == 5, "five Toads are in the world");
            ctx.waitTicks(40);
            Vector3d centre = ctx.computeOnClient(mc -> new Vector3d(feet).fma(units(0.75), GalaxyCraftClient.frame().upGal().normalize()));
            aimAt(ctx, centre);
            ctx.waitTicks(60);
            aimAt(ctx, centre);
            shot(ctx, "toad-front");
            waitReal(ctx, 1200);
            shot(ctx, "toad-front-later"); // animated: not the same frame

            // 2. From its side, 3 blocks off.
            Vector3d side = ctx.computeOnClient(mc -> {
                GravityFrame f = GalaxyCraftClient.frame();
                Vector3d up = f.upGal().normalize();
                Vector3d any = new Vector3d(up.y, up.z, up.x).cross(up).normalize();
                return new Vector3d(centre).fma(units(3), any);
            });
            ctx.runOnClient(mc -> GalaxyCraftClient.moveTo(side));
            ctx.waitTicks(40);
            aimAt(ctx, centre);
            ctx.waitTicks(60);
            aimAt(ctx, centre);
            shot(ctx, "toad-side");

            // 3. Right click on nothing talks to nobody; on the Toad it talks and opens its trade.
            int before = ctx.computeOnClient(mc -> ToadClient.talks());
            Vector3d away = ctx.computeOnClient(mc -> new Vector3d(centre).fma(units(30), GalaxyCraftClient.frame().upGal().normalize()));
            aimAt(ctx, away);
            ctx.waitTicks(5);
            gxdev("ctl", "keys rmb");
            waitReal(ctx, 300);
            gxdev("ctl", "keys");
            waitReal(ctx, 500);
            check(ctx.computeOnClient(mc -> ToadClient.talks()) == before, "a right click on nothing talks to nobody");
            aimAt(ctx, centre);
            ctx.waitTicks(5);
            gxdev("ctl", "keys rmb");
            waitReal(ctx, 300);
            gxdev("ctl", "keys");
            waitReal(ctx, 1500);
            check(ctx.computeOnClient(mc -> ToadClient.talks()) > before, "a right click on the Toad talks to it");
            int offers = ctx.computeOnClient(mc -> mc.player.containerMenu instanceof net.minecraft.world.inventory.MerchantMenu m
                    ? m.getOffers().size() : -1);
            log("the Toad's trade has " + offers + " offers");
            check(offers == 4, "the Guide's trade opens (2 things, 2 payments each)");
            shot(ctx, "toad-trade");
            ctx.runOnClient(mc -> mc.player.closeContainer());
            ctx.waitTicks(10);

            ctx.runOnClient(mc -> ToadClient.clearAll());
            ctx.runOnClient(mc -> GalaxyCraftClient.moveTo(home));
            ctx.waitTicks(100);
            ctx.runOnClient(mc -> GalaxyOptions.MOVEMENT.set(Movement.MARIO));
            ctx.waitTicks(60);
            log(failed ? "FAIL" : "PASS");
        }
    }

    private static void aimAt(ClientGameTestContext ctx, Vector3d targetGal) {
        ctx.runOnClient(mc -> {
            GravityFrame f = GalaxyCraftClient.frame();
            Vector3d eye = f.toGal(new Vector3d(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ()));
            Vector3d d = f.dirToMc(new Vector3d(targetGal).sub(eye).normalize());
            mc.player.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
            mc.player.setXRot((float) Math.toDegrees(-Math.asin(Math.max(-1, Math.min(1, d.y)))));
        });
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
        System.out.println("[GalaxyCraft toad] " + msg);
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
