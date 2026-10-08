package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.GalaxyCraftClient;
import dev.moui.galaxycraft.client.PendingGalaxy;
import dev.moui.galaxycraft.client.PlanetClient;
import dev.moui.galaxycraft.client.UniverseClient;
import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.universe.UPos;
import dev.moui.galaxycraft.universe.Universe;
import dev.moui.galaxycraft.voxel.GalaxyCatalog;
import dev.moui.galaxycraft.voxel.PlanetSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import org.joml.Vector3d;

/**
 * Planets seen from afar in the real game (tools/gxvoxel.sh approach, the dev Dolphin booting by
 * itself into GalaxyCraftSpace): in a new world (wide layout), Mario 5000 blocks from a planet of
 * the galaxy, then nearer by 250 blocks a step, looking at it: at every step it must show (a dot,
 * a far view, complete), never nothing. Then toward another star from 24,000 blocks: it opens into
 * its planets. Screenshots approach-*.png; each step's level logged.
 */
public final class ApproachProbe implements FabricClientGameTest {
    private static final Pattern FLAGS = Pattern.compile("flags=[0-9a-f]+/([0-9a-f]+) .*stage=(\\S+)");
    private static final double U = 80;
    private boolean ok = true;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.approachProbe")) return;
        ctx.waitForScreen(TitleScreen.class);
        waitReal(ctx, mc -> stage().equals("GalaxyCraftSpace"), 120);
        ctx.runOnClient(mc -> CreateWorldScreen.openFresh(mc, () -> mc.gui.setScreen(new TitleScreen())));
        ctx.waitForScreen(CreateWorldScreen.class);
        PendingGalaxy.set(new GalaxyCatalog.Options(8, 48, 96, GalaxyCatalog.First.generated("random", 48),
                GalaxyCatalog.Spacing.NORMAL, 0));
        ctx.clickScreenButton("selectWorld.create");
        waitReal(ctx, mc -> mc.player != null && mc.level != null, 60);
        waitReal(ctx, mc -> PlanetClient.standingOn() != null, 180);
        ctx.waitTicks(100);
        // From the home planet, the others (780+ blocks off) on the sky all around.
        for (int yaw = 0; yaw < 360; yaw += 60) {
            int y = yaw;
            ctx.runOnClient(mc -> {
                mc.player.setXRot(-20);
                mc.player.setYRot(y);
            });
            ctx.waitTicks(30);
            gxdev("ctl", "shot approach-sky-" + yaw);
        }
        log("  module from home: " + stats());
        ctx.runOnClient(mc -> mc.player.connection.sendCommand("fly"));
        ctx.waitTicks(40);

        var target = ctx.computeOnClient(mc -> PlanetClient.catalog().get(PlanetClient.catalog().size() - 1));
        Vector3d center = target.center();
        double nearest = Double.MAX_VALUE;
        for (var e : ctx.computeOnClient(mc -> PlanetClient.catalog()))
            if (e.index() != 0) nearest = Math.min(nearest, e.center().length() / U);
        log(String.format("galaxy: %d planets, the nearest to home %.0f blocks off; target planet %d (radius %d) %.0f blocks off",
                ctx.computeOnClient(mc -> PlanetClient.catalog().size()), nearest, target.index(), target.radius(), center.length() / U));
        check(nearest > 600, "planets are far apart (wide layout)");
        // Come at it from away from home, so home is not in the way.
        Vector3d out = new Vector3d(center).normalize();
        double stop = PlanetSession.gravityRadius(target.radius()) + 40;
        int steps = 0, shown = 0;
        for (double d = 5000; d >= stop; d -= 250) {
            Vector3d eye = new Vector3d(out).mul(d * U).add(center);
            look(ctx, eye, center);
            String as = ctx.computeOnClient(mc -> PlanetClient.shownAs(target.index()));
            log(String.format("%5.0f blocks: %s (stars and dots sent %d)", d, as, ctx.computeOnClient(mc -> UniverseClient.starsSent())));
            steps++;
            if (!as.equals("none")) shown++;
            gxdev("ctl", "shot approach-" + (int) d);
        }
        check(shown == steps, "the planet shows at every step (" + shown + " of " + steps + ")");

        // Another star, from 24,000 blocks down to 8,000: it opens into its planets.
        Universe.Star star = ctx.computeOnClient(mc -> UniverseClient.universe().around(UPos.ZERO, 3).stream()
                .filter(s -> !s.home()).findFirst().orElse(null));
        if (star == null) log("no other star near home");
        else {
            Vector3d sc = star.center().minus(UPos.ZERO);
            Vector3d away = new Vector3d(sc).normalize();
            for (double d = 24_000; d >= 8_000; d -= 2_000) {
                Vector3d eye = new Vector3d(away).mul(-d * U).add(sc);
                look(ctx, eye, sc);
                ctx.waitTicks(60);
                log(String.format("star %5.0f blocks: points sent %d", d, ctx.computeOnClient(mc -> UniverseClient.starsSent())));
                gxdev("ctl", "shot approach-star-" + (int) d);
            }
        }
        ctx.runOnClient(mc -> mc.disconnectWithSavingScreen());
        waitReal(ctx, mc -> mc.level == null, 60);
        ctx.setScreen(TitleScreen::new);
        log(ok ? "PASS" : "FAIL");
    }

    /** The free camera (/fly) to eye (universe units) looking at at; then time for far views to come. */
    private static void look(ClientGameTestContext ctx, Vector3d eye, Vector3d at) {
        ctx.runOnClient(mc -> {
            GravityFrame f = GalaxyCraftClient.frame();
            Vector3d mcEye = f.toMc(dev.moui.galaxycraft.universe.GameOrigin.toGame(eye));
            Vector3d dir = f.dirToMc(new Vector3d(at).sub(eye).normalize());
            mc.player.setPos(mcEye.x, mcEye.y - mc.player.getEyeHeight(), mcEye.z);
            mc.player.setDeltaMovement(0, 0, 0);
            mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dir.x, dir.z)));
            mc.player.setXRot((float) Math.toDegrees(-Math.asin(Math.clamp(dir.y, -1, 1))));
        });
        ctx.waitTicks(60);
        log("  module: " + stats());
    }

    private static final Pattern MBX = Pattern.compile("^at=([0-9a-f]+)", Pattern.MULTILINE);
    private static final int DBG_VOXEL_STATS = 4052 + 56;

    /** VoxelStats: chunks the module has, drawn last frame, and parts of far views drawn. */
    private static String stats() {
        // The control channel misses a reply now and then: a few tries before giving up.
        Matcher m = MBX.matcher(gxdev("ctl", "mbx"));
        for (int tries = 1; !m.find(); tries++) {
            if (tries == 5) return "no mailbox";
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            m = MBX.matcher(gxdev("ctl", "mbx"));
        }
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

    private static void waitReal(ClientGameTestContext ctx, java.util.function.Predicate<net.minecraft.client.Minecraft> what,
            int seconds) {
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        while (!ctx.computeOnClient(what::test)) {
            if (System.nanoTime() > end) throw new AssertionError("Timed out after " + seconds + " s");
            ctx.waitTicks(5);
        }
    }

    private void check(boolean that, String what) {
        log((that ? "ok " : "FAILED ") + what);
        ok &= that;
    }

    private static String stage() {
        Matcher m = FLAGS.matcher(gxdev("ctl", "mbx"));
        return m.find() ? m.group(2) : "-";
    }

    private static void log(String msg) {
        System.out.println("[GalaxyCraft approach] " + msg);
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
