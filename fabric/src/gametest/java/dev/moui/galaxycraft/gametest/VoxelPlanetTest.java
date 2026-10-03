package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.GalaxyCraftClient;
import dev.moui.galaxycraft.client.PlanetClient;
import dev.moui.galaxycraft.voxel.Material;
import dev.moui.galaxycraft.voxel.PlanetSession;
import java.io.IOException;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import org.joml.Vector3d;

/**
 * End to end against the real game, only with -Dgalaxycraft.voxel=true (tools/gxvoxel.sh): the
 * dev Dolphin runs SMG2 in Mario mode. A voxel planet appears, Mario lands on its grass, the
 * block under him is broken and he drops a block, a block placed next to him stays.
 */
public final class VoxelPlanetTest implements FabricClientGameTest {
    private static final double UNITS = 80;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.voxel")) return;
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getServer().runCommand("gamemode adventure @a");
            sp.getServer().runCommand("difficulty peaceful");
            sp.getServer().runCommand("gamerule fall_damage false");
            sp.getServer().runCommand("tp @a 0 100 0 0 0");
            ctx.waitFor(mc -> GalaxyCraftClient.galaxyPos().isPresent(), 1200);
            ctx.waitTicks(40);
            // The chat: T opens it and what is typed in Dolphin's window (here the harness, through
            // the same text slot as the keyboard) goes into it, '/' included.
            ctx.getInput().pressKey(o -> o.keyChat);
            ctx.waitTicks(10);
            check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof ChatScreen), "T opens the chat");
            gxdev("ctl", "text /galaxycraft planet");
            ctx.waitTicks(10);
            String typed = ctx.computeOnClient(mc -> chatText(mc.gui.screen()));
            check("/galaxycraft planet".equals(typed), "the chat gets the typed text: '" + typed + "'");
            ctx.runOnClient(mc -> mc.gui.setScreen(null));
            PlanetSession s = PlanetClient.session();
            ctx.runOnClient(mc -> PlanetClient.remove()); // none saved from an earlier run
            ctx.runOnClient(mc -> PlanetClient.requestSpawn(PlanetClient.DEFAULT_RADIUS));
            ctx.waitFor(mc -> s.active() && s.queued() == 0, 400);
            ctx.waitTicks(60); // Dolphin hands it to the module a few hundred KiB a frame
            gxdev("ctl", "shot voxel-1-spawned");

            ctx.runOnClient(mc -> PlanetClient.teleport());
            ctx.runOnClient(mc -> mc.player.setXRot(50));
            ctx.waitTicks(120);
            double r1 = radius(ctx, s);
            log("on the planet: " + r1 + " blocks from its center");
            check(Math.abs(r1 - s.planet().surface()) < 0.3, "Mario stands on the grass, radius " + r1);
            gxdev("ctl", "shot voxel-2-landed");

            // Mario is wider than a block (a 1×1 hole holds him up, like a 2-wide gap would in
            // Minecraft): open the grass cells around his feet, 3×3 or 2×2, each
            // broken from right above it.
            int broke = ctx.computeOnClient(mc -> {
                Vector3d feet = GalaxyCraftClient.galaxyPos().orElseThrow();
                Vector3d up = new Vector3d(feet).sub(s.center()).normalize();
                Vector3d a = tangent(up), b = new Vector3d(up).cross(a);
                var grid = s.planet().grid;
                java.util.Set<Integer> cells = new java.util.TreeSet<>();
                for (double[] o : new double[][] {{0, 0}, {-.6, -.6}, {-.6, .6}, {.6, -.6}, {.6, .6}, {-.6, 0}, {.6, 0}, {0, -.6}, {0, .6}}) {
                    Vector3d p = new Vector3d(up).mul(-0.5).add(new Vector3d(a).mul(o[0])).add(new Vector3d(b).mul(o[1]))
                            .mul(UNITS).add(feet).sub(s.center()).div(UNITS);
                    cells.add(grid.cellAt(p));
                }
                int n = 0;
                for (int cell : cells) {
                    Vector3d top = grid.center(cell).mul(UNITS).add(s.center());
                    Vector3d cellUp = grid.center(cell).normalize();
                    if (s.breakBlock(new Vector3d(cellUp).mul(1.2 * UNITS).add(top), new Vector3d(cellUp).negate())) n++;
                }
                return n;
            });
            check(broke >= 1, "the grass under Mario breaks (" + broke + " cells)");
            ctx.waitTicks(10);
            log("queued after the break: " + ctx.computeOnClient(mc -> s.queued()));
            gxdev("ctl", "mbx");
            ctx.waitTicks(80);
            double r2 = radius(ctx, s);
            log("after digging: " + r2);
            check(r1 - r2 > 0.7 && r1 - r2 < 1.3, "Mario drops one block: " + r1 + " -> " + r2);
            gxdev("ctl", "shot voxel-3-dug");

            boolean placed = ctx.computeOnClient(mc -> {
                Vector3d feet = GalaxyCraftClient.galaxyPos().orElseThrow();
                Vector3d up = new Vector3d(feet).sub(s.center()).normalize();
                Vector3d side = tangent(up);
                // Three blocks to the side, out of the hole: on top of the grass there.
                Vector3d eye = new Vector3d(up).mul(2.5).add(new Vector3d(side).mul(3)).mul(UNITS).add(feet);
                if (!s.placeBlock(eye, new Vector3d(up).negate(), Material.STONE, feet)) return false;
                // And never inside Mario.
                Vector3d over = new Vector3d(up).mul(2.5).mul(UNITS).add(feet);
                return !s.placeBlock(over, new Vector3d(up).negate(), Material.STONE, feet);
            });
            check(placed, "a stone block goes on the grass beside the hole, none inside Mario");
            ctx.waitTicks(30);
            gxdev("ctl", "shot voxel-4-placed");

            // Minecraft's blocks, by its own placement rules, in a row on the grass the other way.
            String[] items = {"oak_stairs", "torch", "poppy", "glass", "oak_fence", "oak_fence", "oak_door", "oak_slab"};
            String[] names = ctx.computeOnClient(mc -> {
                Vector3d feet = GalaxyCraftClient.galaxyPos().orElseThrow();
                Vector3d d0 = new Vector3d(feet).sub(s.center()).normalize();
                Vector3d row = new Vector3d(d0).cross(tangent(d0)).normalize();
                var p = s.planet();
                int[] cells = new int[items.length];
                boolean[] ok = new boolean[items.length];
                for (int k = 0; k < items.length; k++) {
                    var item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .getValue(net.minecraft.resources.Identifier.withDefaultNamespace(items[k]));
                    // Along the surface, a block apart from 3 blocks out of the hole; looking down
                    // and a little ahead, as a player would.
                    double a = (k + 3) / p.surface();
                    Vector3d dir = new Vector3d(d0).mul(Math.cos(a)).add(new Vector3d(row).mul(Math.sin(a)));
                    // Aimed at the middle of that grass cell's top.
                    Vector3d up = p.grid.center(p.grid.cellAt(new Vector3d(dir).mul(p.surface() - 0.5))).normalize();
                    Vector3d ahead = new Vector3d(row).sub(new Vector3d(up).mul(row.dot(up))).normalize();
                    Vector3d at = new Vector3d(up).mul(p.surface());
                    Vector3d eye = new Vector3d(up).mul(2.5).add(at).sub(new Vector3d(ahead).mul(0.625)).mul(UNITS).add(s.center());
                    Vector3d look = new Vector3d(up).negate().add(new Vector3d(ahead).mul(0.25)).normalize();
                    ok[k] = PlanetClient.placeItem(eye, look, new net.minecraft.world.item.ItemStack(item), feet);
                    cells[k] = p.grid.cellAt(new Vector3d(up).mul(p.surface() + 0.5));
                }
                // Read back once all are down: blocks placed later change some (fences join).
                String[] out = new String[items.length + 1];
                for (int k = 0; k < items.length; k++) {
                    out[k] = (ok[k] ? "" : "NOT PLACED ") + PlanetClient.blockName(cells[k]);
                    if (items[k].equals("oak_door"))
                        out[items.length] = PlanetClient.blockName(p.grid.neighbor(cells[k], dev.moui.galaxycraft.voxel.CubeSphere.TOP));
                }
                return out;
            });
            for (String n : names) log("placed " + n);
            for (int k = 0; k < items.length; k++)
                check(names[k].startsWith("minecraft:" + items[k]), items[k] + " is on the planet: " + names[k]);
            check(names[4].contains("=true"), "the fences join: " + names[4]);
            check(names[items.length].contains("half=upper"), "the door has its upper half: " + names[items.length]);
            ctx.waitTicks(30);
            gxdev("ctl", "shot voxel-4b-minecraft-blocks");

            // Saved and loaded back from disk: the hole is still there, Mario still in it.
            ctx.runOnClient(mc -> {
                try {
                    PlanetClient.reloadFromDisk();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            check(ctx.computeOnClient(mc -> s.active()), "the planet comes back from disk");
            ctx.waitFor(mc -> s.queued() == 0, 400);
            ctx.waitTicks(60);
            double r3 = radius(ctx, s);
            check(Math.abs(r3 - r2) < 0.2, "after the reload Mario is still in the hole: " + r3);

            // A big one: radius 128, its grass 1 block from where the teleport lands Mario.
            ctx.runOnClient(mc -> PlanetClient.requestSpawn(128));
            ctx.waitFor(mc -> s.active() && s.planet().surface() == 128 && s.queued() == 0, 2400);
            ctx.waitTicks(200);
            gxdev("ctl", "shot voxel-5-big");
            ctx.runOnClient(mc -> PlanetClient.teleport());
            ctx.waitTicks(200);
            double rb = radius(ctx, s);
            log("on the big planet: " + rb);
            check(Math.abs(rb - 128) < 0.3, "Mario stands on the big planet's grass, radius " + rb);
            gxdev("ctl", "shot voxel-6-big-landed");
            // Toward the horizon, turning around: every way there is ground to it (nothing culled).
            for (int yaw = 0; yaw < 360; yaw += 90) {
                int y = yaw;
                ctx.runOnClient(mc -> {
                    mc.player.setXRot(8);
                    mc.player.setYRot(y);
                });
                ctx.waitTicks(15);
                gxdev("ctl", "shot voxel-7-horizon-" + yaw);
            }
            log("collision chunks: " + ctx.computeOnClient(mc -> s.collisionChunks()));

            // /fly: up turns to the galaxy's +Y (here the opposite of Mario's: he is under the
            // planet), the player rises on its own, Mario stays where he is.
            Vector3d marioBefore = ctx.computeOnClient(mc -> GalaxyCraftClient.galaxyPos().orElseThrow());
            ctx.runOnClient(mc -> mc.player.connection.sendCommand("fly"));
            ctx.waitTicks(40);
            check(ctx.computeOnClient(mc -> GalaxyCraftClient.galaxyUp().orElseThrow().y) > 0.999, "flying: up is the galaxy's +Y");
            ctx.getInput().holdKeyFor(o -> o.keyJump, 60);
            ctx.waitTicks(5);
            Vector3d flown = ctx.computeOnClient(mc -> GalaxyCraftClient.galaxyPos().orElseThrow());
            log("flew from " + marioBefore + " to " + flown);
            check(flown.y - marioBefore.y > 3 * UNITS, "space flies up the galaxy's +Y");
            gxdev("ctl", "mbx; shot voxel-8-flying");
            ctx.runOnClient(mc -> mc.player.connection.sendCommand("fly"));
            ctx.waitTicks(60);
            double back = ctx.computeOnClient(mc -> GalaxyCraftClient.galaxyPos().orElseThrow().distance(marioBefore)) / UNITS;
            check(back < 1, "/fly again: back at Mario's feet, " + back + " blocks off");
            ctx.runOnClient(mc -> PlanetClient.remove());

            log("PASS");
        }
    }

    private static String chatText(Object screen) {
        try {
            var f = ChatScreen.class.getDeclaredField("input");
            f.setAccessible(true);
            return ((EditBox) f.get(screen)).getValue();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static Vector3d tangent(Vector3d up) {
        return new Vector3d(up).cross(Math.abs(up.y) < 0.9 ? new Vector3d(0, 1, 0) : new Vector3d(1, 0, 0)).normalize();
    }

    private static double radius(ClientGameTestContext ctx, PlanetSession s) {
        return ctx.computeOnClient(mc -> GalaxyCraftClient.galaxyPos().orElseThrow().distance(s.center()) / UNITS);
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            log("FAIL " + what);
            throw new AssertionError(what);
        }
        log("ok " + what);
    }

    private static void log(String msg) {
        System.out.println("[GalaxyCraft voxel] " + msg);
    }

    private static void gxdev(String... args) {
        String[] cmd = new String[args.length + 2];
        cmd[0] = "python3";
        cmd[1] = Path.of(System.getProperty("galaxycraft.repoRoot"), "tools/gxdev.py").toString();
        System.arraycopy(args, 0, cmd, 2, args.length);
        try {
            new ProcessBuilder(cmd).inheritIO().start().waitFor();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
