package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.GalaxyCraftClient;
import dev.moui.galaxycraft.client.GalaxyOptions;
import dev.moui.galaxycraft.client.PendingGalaxy;
import dev.moui.galaxycraft.client.PlanetClient;
import dev.moui.galaxycraft.client.ToadClient;
import dev.moui.galaxycraft.client.UniverseClient;
import dev.moui.galaxycraft.client.Warp;
import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.settings.Movement;
import dev.moui.galaxycraft.toad.ToadPlan;
import dev.moui.galaxycraft.toad.ToadRecord;
import dev.moui.galaxycraft.universe.SystemIndex;
import dev.moui.galaxycraft.universe.Universe;
import dev.moui.galaxycraft.voxel.GalaxyCatalog;
import dev.moui.galaxycraft.voxel.PlanetSession;
import dev.moui.galaxycraft.voxel.gen.LegacyBiome;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Toad outposts through the real path, only with -Dgalaxycraft.outpostProbe=true (tools/gxvoxel.sh
 * outpost, the dev Dolphin booting by itself into GalaxyCraftSpace): a world is created as when
 * playing, a star of tier 1 or more nearby is picked whose system has a planet with outposts
 * (ToadPlan, as GalaxyStream asks it), Mario warps there and goes to that planet, and its Toads must
 * stand in the world, each in a hut. Screenshots outpost-*.png.
 */
public final class OutpostProbe implements FabricClientGameTest {
    private static final Pattern FLAGS = Pattern.compile("flags=[0-9a-f]+/([0-9a-f]+) .*stage=(\\S+)");
    private static final double U = 80;
    private boolean ok = true;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.outpostProbe")) return;
        ctx.waitForScreen(TitleScreen.class);
        waitReal(ctx, mc -> stage().equals("GalaxyCraftSpace"), 120);
        ctx.runOnClient(mc -> CreateWorldScreen.openFresh(mc, () -> mc.gui.setScreen(new TitleScreen())));
        ctx.waitForScreen(CreateWorldScreen.class);
        PendingGalaxy.set(new GalaxyCatalog.Options(3, 32, 48, GalaxyCatalog.First.generated("minecraft:plains", 40),
                GalaxyCatalog.Spacing.NORMAL, 0));
        ctx.clickScreenButton("selectWorld.create");
        waitReal(ctx, mc -> mc.player != null && mc.level != null, 60);
        waitReal(ctx, mc -> onSurface(), 180);
        ctx.waitTicks(100);
        check(ctx.computeOnClient(mc -> UniverseClient.universe() != null), "the world has its universe");

        // The home planet has its Guide, near where Mario stands (the world's first visit).
        waitReal(ctx, mc -> ToadClient.toads().stream().anyMatch(t -> t.type() == dev.moui.galaxycraft.toad.ToadType.GUIDE), 30);
        check(ctx.computeOnClient(mc -> ToadClient.toads().stream().anyMatch(t -> t.type() == dev.moui.galaxycraft.toad.ToadType.GUIDE)),
                "the home planet has its Guide");
        ctx.runOnClient(mc -> GalaxyOptions.MOVEMENT.set(Movement.MINECRAFT));

        // A star of tier >= 1 near enough, with a planet that has outposts.
        record Pick(Universe.Star star, int index, int radius, List<ToadPlan.Spot> spots) {}
        Pick pick = ctx.computeOnClient(mc -> {
            Universe u = UniverseClient.universe();
            for (Universe.Star star : Warp.nearby()) {
                if (star.home() || u.tier(star) < 1) continue;
                for (GalaxyCatalog.Entry e : u.system(star, LegacyBiome.land()).entries()) {
                    int index = SystemIndex.index(star.sector(), e.index());
                    List<ToadPlan.Spot> spots = ToadPlan.outposts("p" + index, e.seed(), e.radius(), u.tier(star), u.starType(star));
                    if (!spots.isEmpty()) return new Pick(star, index, e.radius(), spots);
                }
            }
            return null;
        });
        log("stars near: " + ctx.computeOnClient(mc -> Warp.nearby().stream().filter(s -> !s.home())
                .map(s -> SystemIndex.name(s.sector()) + " tier " + UniverseClient.universe().tier(s)).toList()));
        check(pick != null, "a tier >= 1 star nearby has a planet with outposts");
        if (pick == null) {
            end(ctx);
            return;
        }
        log("going to " + SystemIndex.name(pick.star().sector()) + ", planet " + pick.index() + " (radius " + pick.radius()
                + "), outposts " + pick.spots().stream().map(s -> s.id() + ":" + s.type().id()).toList());
        ctx.runOnClient(mc -> Warp.to(mc, pick.star()));
        waitReal(ctx, mc -> !PlanetClient.waitingToLand() && PlanetClient.standingOn() != null, 240);
        ctx.waitTicks(60);
        ctx.runOnClient(mc -> PlanetClient.travelTo(pick.index()));
        waitReal(ctx, mc -> !PlanetClient.waitingToLand() && on(pick.index()), 240);
        // The planet's cells were made (huts built with them); its Toads arrive on a frame.
        long end = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < end && ctx.computeOnClient(mc -> outposts().isEmpty())) ctx.waitTicks(10);
        List<ToadRecord> toads = ctx.computeOnClient(mc -> outposts());
        for (ToadRecord t : toads) log("toad " + t.id() + " " + t.type().id());
        check(!toads.isEmpty(), "the planet's outposts stand in the world (" + toads.size() + " of " + pick.spots().size() + " asked)");
        if (!toads.isEmpty()) {
            ToadRecord t = toads.get(0);
            Quaterniond q = new Quaterniond(t.rot()[0], t.rot()[1], t.rot()[2], t.rot()[3]);
            Vector3d at = new Vector3d(t.pos()[0], t.pos()[1], t.pos()[2]);
            Vector3d up = new Vector3d(0, 1, 0).rotate(q), fwd = new Vector3d(0, 0, 1).rotate(q), right = new Vector3d(1, 0, 0).rotate(q);
            Vector3d head = new Vector3d(at).fma(units(1.2), up);
            for (Object[] view : new Object[][] {
                    {"front", new Vector3d(at).fma(units(7), fwd).fma(units(0.3), up)},
                    {"side", new Vector3d(at).fma(units(9), right).fma(units(6), up)}}) {
                Vector3d eye = (Vector3d) view[1];
                ctx.runOnClient(mc -> GalaxyCraftClient.moveTo(eye));
                ctx.waitTicks(120);
                ctx.runOnClient(mc -> aim(mc, head));
                ctx.waitTicks(20);
                gxdev("ctl", "shot outpost-" + view[0]);
            }
        }
        end(ctx);
    }

    private void end(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> mc.disconnectWithSavingScreen());
        waitReal(ctx, mc -> mc.level == null, 60);
        ctx.setScreen(TitleScreen::new);
        log(ok ? "PASS" : "FAIL");
    }

    /** The outposts the world put (not the home Guide). */
    private static List<ToadRecord> outposts() {
        return ToadClient.toads().stream().filter(t -> t.id().startsWith("toad-p")).toList();
    }

    private static void aim(net.minecraft.client.Minecraft mc, Vector3d targetGal) {
        GravityFrame f = GalaxyCraftClient.frame();
        Vector3d eye = f.toGal(new Vector3d(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ()));
        Vector3d d = f.dirToMc(new Vector3d(targetGal).sub(eye).normalize());
        mc.player.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
        mc.player.setXRot((float) Math.toDegrees(-Math.asin(Math.max(-1, Math.min(1, d.y)))));
    }

    private static double units(double blocks) {
        return blocks / GravityFrame.SCALE;
    }

    private static boolean on(int index) {
        PlanetSession s = PlanetClient.standingOn();
        return s != null && PlanetClient.entry(index).map(e -> e.center().distance(s.center()) < 1).orElse(false);
    }

    private static boolean onSurface() {
        PlanetSession s = PlanetClient.standingOn();
        Vector3d feet = GalaxyCraftClient.galaxyPos().orElse(null);
        if (s == null || feet == null) return false;
        double h = s.localOf(feet).length() - s.planet().surface();
        return h > -40 && h < 64;
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
        System.out.println("[GalaxyCraft outpost] " + msg);
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
