package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.GalaxyCraftClient;
import dev.moui.galaxycraft.client.PlanetClient;
import dev.moui.galaxycraft.shadow.ShadowMap;
import dev.moui.galaxycraft.shadow.ShadowWorld;
import dev.moui.galaxycraft.voxel.PlanetSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * End to end against the real game, only with -Dgalaxycraft.entities=true (tools/gxvoxel.sh
 * entities): mobs, a primed TNT, falling sand and a dropped item summoned in the shadow around
 * Mario are drawn by the game (its debug block counts the pieces), not by Minecraft. Screenshots
 * show them from behind Steve and from the Galaxy view.
 */
public final class EntityTest implements FabricClientGameTest {
    private static final Pattern MBX = Pattern.compile("^at=([0-9a-f]+)", Pattern.MULTILINE);
    /** Debug.entities_drawn: right after the mailbox (4048 bytes), 103 words into the debug block. */
    private static final int DBG_ENTITIES = 4048 + 4 * 103;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.entities")) return;
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getServer().runCommand("gamemode adventure @a");
            sp.getServer().runCommand("gamerule fall_damage false");
            sp.getServer().runCommand("time set day");
            sp.getServer().runCommand("tp @a 0 100 0 0 0");
            ctx.waitFor(mc -> GalaxyCraftClient.galaxyPos().isPresent(), 1200);
            ctx.waitTicks(40);
            PlanetSession s = PlanetClient.session();
            ctx.runOnClient(mc -> PlanetClient.remove());
            ctx.runOnClient(mc -> PlanetClient.requestSpawn(PlanetClient.DEFAULT_RADIUS));
            ctx.waitFor(mc -> s.active() && s.queued() == 0, 400);
            ctx.waitTicks(60);
            ctx.runOnClient(mc -> PlanetClient.teleport());
            ctx.waitTicks(120);
            ctx.waitFor(mc -> ShadowWorld.entities() != null, 200);
            check(count() == 0, "nothing drawn before anything is summoned");

            // Mario's cell in the shadow, and spots a few blocks around it on his face.
            int cell = ctx.computeOnClient(mc -> s.cellAt(GalaxyCraftClient.galaxyPos().get()));
            check(cell >= 0, "Mario is on the planet");
            ShadowMap map = ShadowWorld.entities().map();
            int n = map.grid.n, f = map.grid.face(cell);
            double x0 = map.x(cell) + 0.5, y = map.y(cell) + 1.5, z0 = map.z(cell) + 0.5;
            String[] what = {"pig", "cow", "chicken", "tnt{fuse:400}", "falling_block{BlockState:{Name:\"minecraft:sand\"},Time:1}",
                    "item{Item:{id:\"minecraft:diamond\",count:1}}"};
            for (int i = 0; i < what.length; i++) {
                double a = i * Math.PI * 2 / what.length;
                double x = clampTo(x0 + 3 * Math.cos(a), f * ShadowMap.STRIDE + 1, n), z = clampTo(z0 + 3 * Math.sin(a), map.z0 + 1, n);
                String type = what[i].contains("{") ? what[i].substring(0, what[i].indexOf('{')) : what[i];
                String nbt = what[i].contains("{") ? " " + what[i].substring(what[i].indexOf('{')) : "";
                sp.getServer().runCommand(String.format(java.util.Locale.ROOT,
                        "execute in galaxycraft:shadow run summon minecraft:%s %.2f %.2f %.2f%s", type, x, y + (type.equals("falling_block") ? 3 : 0), z, nbt));
            }
            ctx.waitTicks(40);
            String shadow = ctx.computeOnClient(mc -> ShadowWorld.entities().list().stream()
                    .map(e -> e.getType().toShortString() + "@" + e.blockPosition().toShortString()).toList().toString());
            log("in the shadow: " + shadow + ", Mario's cell at " + map.x(cell) + " " + map.y(cell) + " " + map.z(cell));
            check(shadow.contains("pig") && shadow.contains("cow") && shadow.contains("chicken") && shadow.contains("tnt"),
                    "the mobs and the TNT run in the shadow");
            check(PlanetClient.dropCount() == 1, "the diamond lies on the planet");
            int drawn = count();
            // Pig, cow and chicken are several pieces each; TNT and the diamond one each.
            check(drawn >= 10, "the game draws them (" + drawn + " pieces)");
            ctx.getInput().pressKey(o -> o.keyTogglePerspective);
            ctx.waitTicks(20);
            for (int k = 0; k < 4; k++) {
                gxdev("ctl", "shot entities-" + k);
                ctx.runOnClient(mc -> mc.player.setYRot(mc.player.getYRot() + 90));
                ctx.waitTicks(10);
            }
            ctx.getInput().pressKey(o -> o.keyTogglePerspective);
            ctx.getInput().pressKey(o -> o.keyTogglePerspective);
            ctx.waitTicks(20);
            gxdev("ctl", "shot entities-galaxy-view");
            ctx.getInput().pressKey(o -> o.keyTogglePerspective);
            sp.getServer().runCommand("execute in galaxycraft:shadow run kill @e[type=!player]");
            ctx.waitTicks(20);
            ctx.runOnClient(mc -> PlanetClient.remove());
            ctx.waitTicks(10);
            log("PASS");
        }
    }

    /** v kept on the face's box from lo, n cells wide, a block in from its edges. */
    private static double clampTo(double v, int lo, int n) {
        return Math.clamp(v, lo + 1.5, lo + n - 1.5);
    }

    private static int count() {
        Matcher m = MBX.matcher(gxdev("ctl", "mbx"));
        check(m.find(), "the mailbox is found");
        long at = Long.parseLong(m.group(1), 16) + DBG_ENTITIES;
        String[] parts = gxdev("ctl", "peek 0x" + Long.toHexString(at) + " 4").strip().split("\\s+");
        int v = 0;
        for (int i = parts.length - 4; i < parts.length; i++) v = (v << 8) | Integer.parseInt(parts[i], 16);
        return v;
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            log("FAIL " + what);
            throw new AssertionError(what);
        }
        log("ok " + what);
    }

    private static void log(String msg) {
        System.out.println("[GalaxyCraft entities] " + msg);
    }

    /** Runs tools/gxdev.py (the dev Dolphin's control channel) and returns its output. */
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
