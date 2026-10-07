package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.GalaxyCraftClient;
import dev.moui.galaxycraft.client.PlanetClient;
import dev.moui.galaxycraft.voxel.CubeSphere;
import dev.moui.galaxycraft.voxel.Material;
import dev.moui.galaxycraft.voxel.PlanetSession;
import dev.moui.galaxycraft.voxel.VoxelPlanet;
import java.io.IOException;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import org.joml.Vector3d;

/**
 * Whether Mario fits where Steve would, only with -Dgalaxycraft.fit=true (tools/gxfit.sh): a 1×1
 * shaft two blocks deep (he drops to its bottom), a 1-wide, 2-high tunnel from there (he walks
 * through it), and out of the shaft with a jump. Measured against the real game in the dev Dolphin.
 */
public final class MarioFitTest implements FabricClientGameTest {
    private static final double UNITS = 80;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.fit")) return;
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getServer().runCommand("gamemode adventure @a");
            sp.getServer().runCommand("difficulty peaceful");
            sp.getServer().runCommand("gamerule fall_damage false");
            sp.getServer().runCommand("tp @a 0 100 0 0 0");
            ctx.waitFor(mc -> GalaxyCraftClient.galaxyPos().isPresent(), 1200);
            ctx.waitTicks(40);
            PlanetSession s = PlanetClient.session();
            log("Mario's radius on planets: " + PlanetSession.MARIO_RADIUS + " blocks");
            ctx.runOnClient(mc -> PlanetClient.remove());
            ctx.runOnClient(mc -> PlanetClient.requestSpawn(48));
            ctx.waitFor(mc -> s.active() && s.queued() == 0, 1200);
            ctx.waitTicks(100);
            ctx.runOnClient(mc -> PlanetClient.teleport());
            ctx.runOnClient(mc -> mc.player.setXRot(30));
            ctx.waitTicks(120);
            double r0Landed = radius(ctx, s);
            double r0 = r0Landed;
            log("landed at " + r0 + " (grass at " + s.planet().surface() + ")");
            still(ctx, s, "on the grass");
            gxdev("ctl", "peek " + Long.toHexString(mbx() + 0xFD0 + 0x3C) + " 12");

            // With something in hand, the block in reach gets Minecraft's outline.
            sp.getServer().runCommand("item replace entity @a weapon.mainhand with iron_pickaxe");
            ctx.runOnClient(mc -> mc.player.setXRot(55));
            ctx.waitTicks(30);
            gxdev("ctl", "shot fit-0-outline");
            sp.getServer().runCommand("item replace entity @a weapon.mainhand with air");
            // F3+B: his collision drawn, seen from behind.
            ctx.runOnClient(mc -> {
                mc.debugEntries.setStatus(DebugScreenEntries.ENTITY_HITBOXES, DebugScreenEntryStatus.ALWAYS_ON);
                mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            });
            ctx.waitTicks(30);
            if (mailbox == 0) mailbox = mbx();
            String flags = gxdevOut("ctl", "peek " + Long.toHexString(mailbox + 52) + " 4").replaceAll("[0-9a-f]{8}: ", "").trim();
            result("F3+B reaches the game (host_flags " + flags + ")", (Long.parseLong(flags.replace(" ", ""), 16) & 32) != 0 ? 1 : 0, 1, "");
            gxdev("ctl", "shot fit-0-hitbox");
            ctx.runOnClient(mc -> {
                mc.debugEntries.setStatus(DebugScreenEntries.ENTITY_HITBOXES, DebugScreenEntryStatus.NEVER);
                mc.options.setCameraType(CameraType.FIRST_PERSON);
                mc.player.setXRot(30);
            });
            ctx.waitTicks(10);

            // Holes two deep under his feet, each on fresh ground (the stick moves him along the
            // storybook's one axis): how much does he drop into each?
            String[] shapes = {"1x1", "plus", "2x2", "3x3"};
            java.util.List<Integer> dug = new java.util.ArrayList<>();
            for (String shape : shapes) {
                stick(ctx, 1, 0, 0.6);
                ctx.waitTicks(40);
                double before = radius(ctx, s);
                log("  state standing: " + state());
                double off = ctx.computeOnClient(mc -> {
                    VoxelPlanet p = s.planet();
                    Vector3d f = local(s, mario());
                    int c0 = p.grid.cellAt(new Vector3d(f).sub(new Vector3d(f).normalize().mul(0.5)));
                    Vector3d c = p.grid.center(c0), up = new Vector3d(c).normalize();
                    Vector3d d = new Vector3d(f).sub(c);
                    d.sub(new Vector3d(up).mul(d.dot(up)));
                    java.util.Set<Integer> cells = new java.util.HashSet<>();
                    cells.add(c0);
                    int[] sides = {CubeSphere.I_MINUS, CubeSphere.I_PLUS, CubeSphere.J_MINUS, CubeSphere.J_PLUS};
                    if (!shape.equals("1x1")) for (int sd : sides) cells.add(p.grid.neighbor(c0, sd));
                    if (shape.equals("2x2")) {
                        // The cell and the three toward the corner nearest his feet.
                        cells.clear();
                        int a = p.grid.neighbor(c0, d.dot(dirTo(p, c0, CubeSphere.I_PLUS)) > 0 ? CubeSphere.I_PLUS : CubeSphere.I_MINUS);
                        int jSide = d.dot(dirTo(p, c0, CubeSphere.J_PLUS)) > 0 ? CubeSphere.J_PLUS : CubeSphere.J_MINUS;
                        cells.add(c0); cells.add(a); cells.add(p.grid.neighbor(c0, jSide)); cells.add(p.grid.neighbor(a, jSide));
                    }
                    if (shape.equals("3x3"))
                        for (int sd : new int[] {CubeSphere.I_MINUS, CubeSphere.I_PLUS}) {
                            int n = p.grid.neighbor(c0, sd);
                            cells.add(p.grid.neighbor(n, CubeSphere.J_MINUS));
                            cells.add(p.grid.neighbor(n, CubeSphere.J_PLUS));
                        }
                    dug.clear();
                    for (int c1 : cells) {
                        dug.add(c1);
                        dug.add(p.grid.neighbor(c1, CubeSphere.BOTTOM));
                    }
                    for (int c1 : dug) p.set(c1, Material.AIR);
                    return d.length();
                });
                Vector3d at0 = mario();
                StringBuilder path = new StringBuilder();
                for (int t = 0; t < 20; t++) {
                    ctx.waitTicks(5);
                    Vector3d m = mario();
                    Vector3d up0 = new Vector3d(at0).sub(s.center()).normalize();
                    Vector3d d = new Vector3d(m).sub(at0);
                    double side = new Vector3d(d).sub(new Vector3d(up0).mul(d.dot(up0))).length() / UNITS;
                    path.append(String.format(java.util.Locale.ROOT, " %.2f/%.2f", before - m.distance(s.center()) / UNITS, side));
                }
                log("  " + shape + " drop/side every 5 ticks:" + path);
                log("  state after: " + state());
                still(ctx, s, "in the " + shape);
                double dropped = before - radius(ctx, s);
                result("hole " + shape + " (feet " + String.format(java.util.Locale.ROOT, "%.2f", off) + " from the cell's center)",
                        dropped, 1.7, "blocks dropped of 2");
                gxdev("ctl", "shot fit-" + shape);
                if (shape.equals("1x1")) { // seen from behind: how he hangs there
                    ctx.getInput().pressKey(o -> o.keyTogglePerspective);
                    ctx.runOnClient(mc -> mc.player.setXRot(60));
                    ctx.waitTicks(20);
                    gxdev("ctl", "shot fit-1x1-back");
                    for (int k = 0; k < 3; k++) { // front, Galaxy view, back to first person
                        ctx.getInput().pressKey(o -> o.keyTogglePerspective);
                        ctx.waitTicks(5);
                    }
                    ctx.runOnClient(mc -> mc.player.setXRot(30));
                }
                // Filled again and Mario back on top, for the next one.
                ctx.runOnClient(mc -> {
                    for (int c1 : dug) s.planet().set(c1, Material.DIRT);
                });
                ctx.waitTicks(20);
                ctx.runOnClient(mc -> PlanetClient.teleport());
                ctx.waitTicks(60);
            }
            // A closed 1x1x2 cell: a shaft three deep, its top cell filled again once he is down.
            // He stays on its floor (nothing pushes him out) and a jump bumps the ceiling.
            stick(ctx, 1, 0, 0.6);
            ctx.waitTicks(40);
            double top = radius(ctx, s);
            // Dug as a plus (so he drops wherever he stands in the middle cell), the arms only
            // two deep: then the middle is a 1x1 cell two high once its top is filled.
            int[] lid = new int[5];
            double off = ctx.computeOnClient(mc -> {
                VoxelPlanet p = s.planet();
                Vector3d f = local(s, mario());
                int c0 = p.grid.cellAt(new Vector3d(f).sub(new Vector3d(f).normalize().mul(0.5)));
                int[] sides = {CubeSphere.I_MINUS, CubeSphere.I_PLUS, CubeSphere.J_MINUS, CubeSphere.J_PLUS};
                lid[0] = c0;
                for (int k = 0; k < 4; k++) lid[k + 1] = p.grid.neighbor(c0, sides[k]);
                int c = c0;
                for (int n = 0; n < 3; n++, c = p.grid.neighbor(c, CubeSphere.BOTTOM)) p.set(c, Material.AIR);
                for (int k = 1; k < 5; k++) {
                    p.set(lid[k], Material.AIR);
                    p.set(p.grid.neighbor(lid[k], CubeSphere.BOTTOM), Material.AIR);
                }
                Vector3d d = new Vector3d(f).sub(p.grid.center(c0));
                Vector3d up = p.grid.center(c0).normalize();
                return d.sub(up.mul(d.dot(up))).length();
            });
            ctx.waitTicks(100);
            double floor = radius(ctx, s);
            result("shaft 3 deep (feet " + String.format(java.util.Locale.ROOT, "%.2f", off) + " off)", top - floor, 2.7, "blocks dropped of 3");
            // Filled back: the arms (where he could stand) and the middle's top.
            ctx.runOnClient(mc -> {
                VoxelPlanet p = s.planet();
                for (int k = 1; k < 5; k++) {
                    p.set(lid[k], Material.STONE);
                    p.set(p.grid.neighbor(lid[k], CubeSphere.BOTTOM), Material.STONE);
                }
                p.set(lid[0], Material.STONE);
            });
            ctx.waitTicks(60);
            double still = radius(ctx, s);
            result("closed 1x1x2 cell", 1 - Math.abs(still - floor), 0.9, "(1 = he stays on its floor)");
            gxdev("ctl", "link off");
            gxdev("press", "A", "0.4");
            double jump = 0;
            for (int t = 0; t < 8; t++) jump = Math.max(jump, radius(ctx, s) - floor);
            gxdev("ctl", "link on");
            ctx.waitTicks(40);
            result("jump under the ceiling", 1.0 - jump, 0.7, "(1 - rise: the ceiling stops him)");
            log("  rise " + jump + ", floor again " + (radius(ctx, s) - floor) + ", " + state());
            gxdev("ctl", "shot fit-cell");

            // A small planet, where cells narrow fast: a 1x1 shaft down to the bedrock, and he
            // stays still at its bottom, standing and pushing against its wall.
            ctx.runOnClient(mc -> PlanetClient.remove());
            ctx.runOnClient(mc -> PlanetClient.requestSpawn(Integer.getInteger("galaxycraft.fitDeep", 16)));
            ctx.waitFor(mc -> s.active() && s.queued() == 0, 1200);
            ctx.waitTicks(60);
            ctx.runOnClient(mc -> PlanetClient.teleport());
            ctx.waitTicks(120);
            double deepTop = radius(ctx, s);
            int depth = ctx.computeOnClient(mc -> {
                VoxelPlanet p = s.planet();
                Vector3d f = local(s, mario());
                int c = p.grid.cellAt(new Vector3d(f).sub(new Vector3d(f).normalize().mul(0.5)));
                int n = 0;
                for (; p.info(c).breakable(); c = p.grid.neighbor(c, CubeSphere.BOTTOM), n++) p.set(c, Material.AIR);
                return n;
            });
            ctx.waitTicks(120);
            result("deep shaft (" + depth + " of a radius " + s.planet().surface() + " planet)", deepTop - radius(ctx, s), depth - 0.3,
                    "blocks dropped");
            log("  deep: " + ctx.computeOnClient(mc -> walls(s)) + " " + state()); // units to each wall
            still(ctx, s, "at the shaft's bottom");
            for (double[] dir : new double[][] {{1, 0}, {-1, 0}}) {
                gxdev("ctl", "link off");
                ctx.waitTicks(20);
                String x = String.format(java.util.Locale.ROOT, "%.2f", dir[0]);
                Thread push = new Thread(() -> gxdev("stick", x, "0", "2.5")); // held while measured
                push.start();
                ctx.waitTicks(15);
                pushing(ctx, "pushing the shaft's wall " + (dir[0] > 0 ? "right" : "left"));
                try {
                    push.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                gxdev("ctl", "link on");
                ctx.waitTicks(30);
                still(ctx, s, "after pushing " + (dir[0] > 0 ? "right" : "left"));
            }
            gxdev("ctl", "shot fit-deep");

            ctx.runOnClient(mc -> PlanetClient.remove());
            ctx.waitTicks(30);
            // Off the planet Mario is the game's again: no patch left, his own binder.
            String after = state();
            result("game's own Mario off the planet (" + after.substring(0, 35) + ")",
                    after.startsWith("00 00 00 00") && after.substring(24, 35).equals("00 00 17 70") ? 1 : 0, 1, "");
            log("DONE");
        }
    }

    /**
     * The Wii Remote's stick for secs, in Wiimote mode (link off): the dev Dolphin's "ctl keys"
     * do not reach Mario through the override. x right, y up, relative to the game's camera.
     */
    private static void stick(ClientGameTestContext ctx, double x, double y, double secs) {
        double n = Math.max(1, Math.hypot(x, y));
        gxdev("ctl", "link off");
        ctx.waitTicks(40);
        gxdev("stick", "0", "0", "0.3");
        gxdev("stick", String.format(java.util.Locale.ROOT, "%.3f", x / n), String.format(java.util.Locale.ROOT, "%.3f", y / n), String.valueOf(secs));
        ctx.waitTicks((int) (secs * 20) + 20);
        gxdev("ctl", "link on");
        ctx.waitTicks(10);
    }

    /** The module's patch and Mario state debug words (radius_patch, mario_state). */
    private static String state() {
        if (mailbox == 0) mailbox = mbx();
        String out = gxdevOut("ctl", "peek " + Long.toHexString(mailbox + 0xFD0 + 0x3C) + " 28");
        return out.replaceAll("[0-9a-f]{8}: ", "").replace("\n", " ").trim();
    }

    private static long mailbox;

    /** Mario's feet, read from the guest's mailbox (anchor_pos, big-endian floats; galaxy units). */
    private static Vector3d mario() {
        if (mailbox == 0) mailbox = mbx();
        String out = gxdevOut("ctl", "peek " + Long.toHexString(mailbox + 36) + " 12");
        String[] b = out.substring(out.indexOf(':') + 1).trim().split("\\s+");
        double[] v = new double[3];
        for (int k = 0; k < 3; k++)
            v[k] = Float.intBitsToFloat((int) Long.parseLong(b[4 * k] + b[4 * k + 1] + b[4 * k + 2] + b[4 * k + 3], 16));
        return new Vector3d(v[0], v[1], v[2]);
    }

    /** Unit direction from a cell's center toward its neighbor on that side. */
    private static Vector3d dirTo(VoxelPlanet p, int cell, int side) {
        return p.grid.center(p.grid.neighbor(cell, side)).sub(p.grid.center(cell)).normalize();
    }

    private static Vector3d feet() {
        return GalaxyCraftClient.galaxyPos().orElseThrow();
    }

    private static Vector3d local(PlanetSession s, Vector3d gal) {
        return new Vector3d(gal).sub(s.center()).div(UNITS);
    }

    private static double radius(ClientGameTestContext ctx, PlanetSession s) {
        return mario().distance(s.center()) / UNITS;
    }

    /** Mario's height from the center and his distance to his cell's four walls, units. */
    private static String walls(PlanetSession s) {
        VoxelPlanet p = s.planet();
        Vector3d f = local(s, mario());
        int c = p.grid.cellAt(f);
        StringBuilder b = new StringBuilder(String.format(java.util.Locale.ROOT, "r=%.2f k=%d walls", f.length(), c < 0 ? -1 : p.grid.k(c)));
        if (c < 0) return b.toString();
        for (int sd : new int[] {CubeSphere.I_MINUS, CubeSphere.I_PLUS, CubeSphere.J_MINUS, CubeSphere.J_PLUS}) {
            Vector3d[] q = p.grid.side(c, sd);
            Vector3d n = new Vector3d(q[1]).sub(q[0]).cross(new Vector3d(q[2]).sub(q[0])).normalize();
            b.append(String.format(java.util.Locale.ROOT, " %.1f%s", Math.abs(new Vector3d(f).sub(q[0]).dot(n)) * UNITS,
                    p.fullCollision(p.grid.neighbor(c, sd)) ? "" : "(open)"));
        }
        return b.toString();
    }

    /**
     * Standing still (or pushing a wall) he should stay still, every frame: tools/gxshake.py reads
     * them all (a tick sees one in three, and a bounce every three frames looks still from here).
     */
    private static void still(ClientGameTestContext ctx, PlanetSession s, String where) {
        String all = tool("gxshake.py", "1.5", "--dump").trim();
        String out = all.substring(all.lastIndexOf('\n') + 1);
        double up = Double.parseDouble(out.replaceAll(".*up=([0-9.]+).*", "$1"));
        double side = Double.parseDouble(out.replaceAll(".*side=([0-9.]+).*", "$1"));
        if (up > 0.3 || side > 0.5) {
            log("  frames:\n" + all.substring(0, Math.min(all.length(), 600)));
            // The module's last six frames (Debug.history): before, after, velocity, status, flags, ground.
            log("  history:\n" + gxdevOut("ctl", "peek " + Long.toHexString(mailbox + 0xFD0 + 0x58) + " 244"));
        }
        result("still " + where + " (" + out + ")", up <= 0.3 && side <= 0.5 ? 1 : 0, 1, "");
    }

    /**
     * Walking into a wall he should stop against it, not bounce off and back (the shared memory
     * is not written while the link is off): his last six frames from the module's Debug.history,
     * the largest step back right after one forward, units, sampled a few times.
     */
    private static void pushing(ClientGameTestContext ctx, String where) {
        double bounce = 0;
        for (int n = 0; n < 4; n++) {
            String out = gxdevOut("ctl", "peek " + Long.toHexString(mailbox + 0xFD0 + 0x58) + " 316");
            byte[] b = new byte[316];
            int i = 0;
            for (String w : out.replaceAll("[0-9a-f]{8}: ", " ").trim().split("\\s+"))
                if (i < b.length) b[i++] = (byte) Integer.parseInt(w, 16);
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(b);
            int next = bb.getInt(0);
            Vector3d prev = null, lastStep = null;
            for (int k = 0; k < 6; k++) { // oldest first
                int at = 4 + 52 * ((next + k) % 6) + 12;
                Vector3d after = new Vector3d(bb.getFloat(at), bb.getFloat(at + 4), bb.getFloat(at + 8));
                if (prev != null) {
                    Vector3d step = new Vector3d(after).sub(prev);
                    if (lastStep != null && step.dot(lastStep) < 0) bounce = Math.max(bounce, Math.min(step.length(), lastStep.length()));
                    lastStep = step;
                }
                prev = after;
            }
            ctx.waitTicks(4);
        }
        result("still " + where + String.format(java.util.Locale.ROOT, " (bounces %.2f units)", bounce), bounce <= 0.5 ? 1 : 0, 1, "");
    }

    private static void result(String what, double value, double min, String unit) {
        log((value >= min ? "ok " : "FAIL ") + what + ": " + String.format("%.2f", value) + " " + unit);
    }

    private static long mbx() {
        String out = gxdevOut("ctl", "mbx");
        int at = out.indexOf("at=");
        return Long.parseLong(out.substring(at + 3, at + 11), 16);
    }

    private static String gxdevOut(String... args) {
        return tool("gxdev.py", args);
    }

    /** Runs tools/<name> with args, its output. */
    private static String tool(String name, String... args) {
        try {
            String[] cmd = new String[args.length + 2];
            cmd[0] = Python.exe();
            cmd[1] = Path.of(System.getProperty("galaxycraft.repoRoot"), "tools", name).toString();
            System.arraycopy(args, 0, cmd, 2, args.length);
            Process p = new ProcessBuilder(cmd).start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            return out;
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private static void log(String msg) {
        System.out.println("[GalaxyCraft fit] " + msg);
    }

    private static void gxdev(String... args) {
        String[] cmd = new String[args.length + 2];
        cmd[0] = Python.exe();
        cmd[1] = Path.of(System.getProperty("galaxycraft.repoRoot"), "tools/gxdev.py").toString();
        System.arraycopy(args, 0, cmd, 2, args.length);
        try {
            new ProcessBuilder(cmd).inheritIO().start().waitFor();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
