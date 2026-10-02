package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.GalaxyCraftClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.level.gamerules.GameRules;
import org.joml.Vector3d;

/**
 * End to end against the real game, only with -Dgalaxycraft.galaxy=true (tools/gxe2e.sh): the dev
 * Dolphin (tools/gxdev.py) runs SMG2 at the Sky Station savestate. The player must link, land on
 * the planet, puppet Mario, and walk around it with the galaxy's gravity turning under them.
 */
public final class WalkOnGalaxyTest implements FabricClientGameTest {
    private static final Pattern MBX = Pattern.compile(
            "flags=(\\d+)/(\\d+) player=\\(([-\\d.]+),([-\\d.]+),([-\\d.]+)\\)");

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.galaxy")) return;
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getServer().runCommand("gamemode adventure @a");
            sp.getServer().runCommand("difficulty peaceful");
            sp.getServer().runCommand("gamerule fall_damage false"); // as in the play world
            check(!sp.getServer().computeOnServer(s -> s.getGlobalGameRules().get(GameRules.FALL_DAMAGE)),
                    "no fall damage in the galaxy world");
            sp.getServer().runCommand("tp @a 0 100 0 0 0");
            ctx.waitFor(mc -> GalaxyCraftClient.galaxyPos().isPresent(), 400);
            ctx.waitFor(mc -> mc.player.onGround(), 200);
            Vector3d gal0 = galaxyPos(ctx);
            log("landed at galaxy (%.0f, %.0f, %.0f)", gal0.x, gal0.y, gal0.z);

            ctx.waitTicks(10);
            Matcher m = mbx();
            check((Integer.parseInt(m.group(2)) & 1) == 1, "the host drives Mario (host_flags " + m.group(2) + ")");
            check((Integer.parseInt(m.group(1)) & 1) == 1, "the game has Mario as a puppet (game_flags " + m.group(1) + ")");
            Vector3d puppet = new Vector3d(Double.parseDouble(m.group(3)), Double.parseDouble(m.group(4)),
                    Double.parseDouble(m.group(5)));
            Vector3d now = galaxyPos(ctx);
            check(puppet.distance(now) < 50, "Mario follows the player: " + puppet + " vs " + now);
            gxdev("ctl", "shot e2e-standing");

            Vector3d up0 = ctx.computeOnClient(mc -> GalaxyCraftClient.galaxyUp()).orElseThrow();
            int samples = 0, grounded = 0;
            double travelled = 0, maxTurn = 0;
            Vector3d prev = galaxyPos(ctx);
            ctx.getInput().holdKey(o -> o.keyUp);
            for (int s = 0; s < 20; s++) {
                for (int k = 0; k < 4; k++) {
                    ctx.waitTicks(5);
                    samples++;
                    if (ctx.computeOnClient(mc -> mc.player.onGround())) grounded++;
                }
                Vector3d gal = galaxyPos(ctx);
                double step = prev.distance(gal);
                travelled += step;
                prev = gal;
                Vector3d up = ctx.computeOnClient(mc -> GalaxyCraftClient.galaxyUp()).orElseThrow();
                double turn = Math.toDegrees(up0.angle(up));
                maxTurn = Math.max(maxTurn, turn);
                log("t=%2ds gal=(%.0f, %.0f, %.0f) travelled=%.0f grounded=%d/%d up turned %.0f deg", s + 1,
                        gal.x, gal.y, gal.z, travelled, grounded, samples, turn);
                // Like a person would: blocked by a wall, turn and keep walking.
                if (step < 50) ctx.runOnClient(mc -> mc.player.setYRot(mc.player.getYRot() + 90));
            }
            ctx.getInput().releaseKey(o -> o.keyUp);
            gxdev("ctl", "shot e2e-walked");

            check(grounded >= 0.8 * samples, "on the ground while walking: " + grounded + "/" + samples);
            check(travelled > 1500, "walked across the galaxy: " + travelled + " units");
            // The walk may come back to where it started, so look at the largest turn on the way.
            check(maxTurn > 20, "the galaxy's up turned under the player: up to " + maxTurn + " degrees");
            log("PASS");
        }
    }

    private static Vector3d galaxyPos(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> GalaxyCraftClient.galaxyPos()).orElseThrow();
    }

    private static Matcher mbx() {
        String out = gxdev("ctl", "mbx");
        Matcher m = MBX.matcher(out);
        check(m.find(), "mailbox summary from gxdev: " + out.strip());
        return m;
    }

    /** Runs tools/gxdev.py (the dev Dolphin's control channel) and returns its output. */
    private static String gxdev(String... args) {
        Path root = Path.of(System.getProperty("galaxycraft.repoRoot", "."));
        String[] cmd = new String[args.length + 2];
        cmd[0] = "python3";
        cmd[1] = root.resolve("tools/gxdev.py").toString();
        System.arraycopy(args, 0, cmd, 2, args.length);
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            check(p.waitFor() == 0, "gxdev " + String.join(" ", args) + ": " + out.strip());
            return out;
        } catch (IOException e) {
            throw new AssertionError("could not run gxdev.py", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        log("ok: %s", what);
    }

    private static void log(String fmt, Object... args) {
        System.out.println("[GalaxyCraft e2e] " + String.format(fmt, args));
    }
}
