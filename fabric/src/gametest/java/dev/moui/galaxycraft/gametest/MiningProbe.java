package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.GalaxyCraftClient;
import dev.moui.galaxycraft.gravity.LookMath;
import dev.moui.galaxycraft.client.PlanetClient;
import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.voxel.PlanetBlueprint;
import dev.moui.galaxycraft.voxel.PlanetSession;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Vector3d;

/**
 * Breaking blocks as in Minecraft, in the real game, only with -Dgalaxycraft.mining=true
 * (tools/gxvoxel.sh mining): the left button held with a pickaxe looking down digs a shaft a block
 * after another at Minecraft's speeds, the cracks grow on the block and go when the button is let
 * go, and the outline of stairs follows their shape; torches in the off hand go down when the main
 * hand does nothing with the click. Screenshots: mining-*.png.
 */
public final class MiningProbe implements FabricClientGameTest {
    private boolean failed;

    /** A block that went while the button was held: what it was and the ticks it took. */
    private record Broke(String block, int ticks, int maxStage) {}

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.mining")) return;
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getServer().runCommand("gamemode survival @a");
            sp.getServer().runCommand("gamerule fall_damage false");
            sp.getServer().runCommand("time set day");
            sp.getServer().runCommand("tp @a 0 100 0 0 0");
            ctx.waitFor(mc -> GalaxyCraftClient.galaxyPos().isPresent(), 1200);
            ctx.waitTicks(40);

            PlanetSession s = PlanetClient.session();
            ctx.runOnClient(mc -> PlanetClient.remove());
            ctx.runOnClient(mc -> PlanetClient.requestSpawn(PlanetBlueprint.standard("mining", 48)));
            ctx.waitFor(mc -> s.active() && s.queued() == 0, 2000);
            ctx.runOnClient(mc -> PlanetClient.teleport());
            ctx.waitTicks(200);

            ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
            sp.getServer().runCommand("item replace entity @a hotbar.0 with oak_stairs 4");
            ctx.waitTicks(10);
            stairs(ctx, s, () -> sp.getServer().runCommand("item replace entity @a hotbar.0 with air"));
            sp.getServer().runCommand("item replace entity @a hotbar.0 with wooden_pickaxe");
            ctx.runOnClient(mc -> mc.player.setXRot(90));
            ctx.waitTicks(20);
            String grass = shot(ctx, "mining-grass");
            sp.getServer().runCommand("item replace entity @a hotbar.0 with air");
            ctx.waitTicks(20);
            check(differ(grass, shot(ctx, "mining-grass-bare")), "the outline shows on the block aimed at");
            sp.getServer().runCommand("item replace entity @a hotbar.0 with wooden_pickaxe");
            ctx.waitTicks(10);
            dig(ctx, s);
            offHand(ctx, s, sp);
            ctx.runOnClient(mc -> PlanetClient.remove());
            ctx.waitTicks(10);
            log(failed ? "FAIL" : "PASS");
        }
    }

    /** Held down looking at the ground: blocks go one after another, each at its own speed. */
    private void dig(ClientGameTestContext ctx, PlanetSession s) {
        int particles0 = PlanetClient.particleCount();
        List<String> sounds = new java.util.concurrent.CopyOnWriteArrayList<>();
        net.minecraft.client.sounds.SoundEventListener heard = (sound, events, range) -> sounds.add(sound.getIdentifier().getPath());
        ctx.runOnClient(mc -> mc.getSoundManager().addListener(heard));
        List<Broke> broke = new ArrayList<>();
        int lastCell = -1, lastId = -1, since = 0, maxStage = -1, everStage = -1;
        boolean shot = false;
        gxdev("ctl", "keys lmb");
        try {
            for (int t = 0; t < 400 && broke.size() < 4; t++) {
                ctx.runOnClient(mc -> mc.player.setXRot(90));
                ctx.waitTicks(1);
                int[] now = ctx.computeOnClient(mc -> aimed(s, mc.player.getYRot(), mc.player.getXRot()));
                int stage = (int) field(s, "crackStage");
                maxStage = Math.max(maxStage, stage);
                everStage = Math.max(everStage, stage);
                if (stage >= 5 && !shot) {
                    gxdev("ctl", "shot mining-crack");
                    ctx.takeScreenshot("mining-crack");
                    shot = true;
                }
                since++;
                if (lastCell >= 0 && (now[0] != lastCell || now[1] != lastId)) {
                    if (s.planet().get(lastCell) != lastId) {
                        String hand = ctx.computeOnClient(mc -> mc.player.getMainHandItem().toString());
                        broke.add(new Broke(net.minecraft.world.level.block.Block.stateById(lastId) + " with " + hand, since, maxStage));
                        log("broke " + broke.getLast());
                    }
                    since = 0;
                    maxStage = -1;
                }
                lastCell = now[0];
                lastId = now[1];
            }
            int held = (int) field(s, "crackStage");
            gxdev("ctl", "keys");
            ctx.waitTicks(3);
            int after = (int) field(s, "crackStage");
            log("crack stage while held " + held + ", after letting go " + after);
            check(after == -1, "letting go clears the cracks");
        } finally {
            gxdev("ctl", "keys");
        }
        ctx.runOnClient(mc -> mc.getSoundManager().removeListener(heard));
        log("sounds: " + new java.util.TreeSet<>(sounds));
        check(sounds.stream().anyMatch(n -> n.endsWith(".hit")), "the block's hit sound plays");
        check(sounds.stream().anyMatch(n -> n.endsWith(".break")), "the block's break sound plays");
        check(broke.size() >= 3, "holding the button breaks a block after another (" + broke.size() + ")");
        check(everStage >= 5, "the cracks grow (stage " + everStage + ")");
        check(PlanetClient.particleCount() > particles0, "pieces fly (" + PlanetClient.particleCount() + " particles)");
        // Not the first: the button may have found a block half done.
        for (Broke b : broke.subList(Math.min(1, broke.size()), broke.size()))
            check(b.ticks() >= 5, "no block goes at once with a wooden pickaxe: " + b);
    }

    /**
     * Torches in the off hand: a right click places one when the main hand holds a pickaxe or
     * nothing (one fewer left), and none when the main hand's bread takes the click.
     */
    private void offHand(ClientGameTestContext ctx, PlanetSession s, TestSingleplayerContext sp) {
        sp.getServer().runCommand("item replace entity @a weapon.offhand with torch 4");
        String[][] cases = {{"wooden_pickaxe", "minecraft:torch"}, {"bread", "minecraft:air"}, {"air", "minecraft:torch"}};
        for (String[] c : cases) {
            sp.getServer().runCommand("item replace entity @a hotbar.0 with " + c[0]);
            ctx.runOnClient(mc -> mc.player.setXRot(90));
            ctx.waitTicks(10);
            int[] ground = ctx.computeOnClient(mc -> aimed(s, mc.player.getYRot(), mc.player.getXRot()));
            if (ground[0] < 0) {
                check(false, "the ground is aimed at for the off hand");
                return;
            }
            int above = s.planet().grid.neighbor(ground[0], dev.moui.galaxycraft.voxel.CubeSphere.TOP);
            int left = ctx.computeOnClient(mc -> mc.player.getOffhandItem().getCount());
            ctx.runOnClient(mc -> PlanetClient.useHeld(mc.player, GalaxyCraftClient.frame(), GalaxyCraftClient.galaxyPos().get()));
            ctx.waitTicks(10);
            String got = ctx.computeOnClient(mc -> PlanetClient.blockName(above));
            int now = ctx.computeOnClient(mc -> mc.player.getOffhandItem().getCount());
            log("off hand torch, main hand " + c[0] + ": " + got + ", torches " + left + " -> " + now);
            check(got.equals(c[1]), "with " + c[0] + " in the main hand the off hand's torch gives " + c[1] + " (" + got + ")");
            check(now == (c[1].equals("minecraft:air") ? left : left - 1), "the off hand's torches used up as placed");
            ctx.runOnClient(mc -> s.planet().set(above, dev.moui.galaxycraft.voxel.Blocks.AIR));
        }
        sp.getServer().runCommand("item replace entity @a weapon.offhand with air");
    }

    /** Stairs placed in front: their outline follows their shape (screenshot). */
    private void stairs(ClientGameTestContext ctx, PlanetSession s, Runnable sp_clear) {
        ctx.runOnClient(mc -> mc.player.setXRot(60));
        ctx.waitTicks(5);
        boolean placed = ctx.computeOnClient(mc -> {
            GravityFrame f = GalaxyCraftClient.frame();
            Vector3d eye = f.toGal(new Vector3d(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ()));
            Vector3d look = f.dirToGal(LookMath.direction(mc.player.getYRot(), mc.player.getXRot()));
            return PlanetClient.placeItem(eye, look, mc.player.getMainHandItem().copy(), f.toGal(
                    new Vector3d(mc.player.getX(), mc.player.getY(), mc.player.getZ())));
        });
        ctx.waitTicks(20);
        int[] now = ctx.computeOnClient(mc -> aimed(s, mc.player.getYRot(), mc.player.getXRot()));
        log("stairs placed " + placed + ", aimed at " + (now[0] < 0 ? "nothing" : PlanetClient.blockName(now[0])));
        int outlined = (int) field(s, "outline");
        int edges = outlined < 0 ? 0 : ctx.computeOnClient(mc -> outlineEdges(s, outlined));
        log("outlined cell " + outlined + " (aimed " + now[0] + "), " + edges + " edges");
        String with = shot(ctx, "mining-stairs");
        sp_clear.run();
        ctx.waitTicks(20);
        check(differ(with, shot(ctx, "mining-stairs-bare")), "the outline shows on the stairs");
        check(outlined == now[0] && edges > 12, "the stairs' outline has their shape's edges (" + edges + ")");
        check(placed, "stairs placed");
    }

    /** The cell the player looks at and its block (-1, -1: none). */
    private static int[] aimed(PlanetSession s, float yRot, float xRot) {
        GravityFrame f = GalaxyCraftClient.frame();
        var p = net.minecraft.client.Minecraft.getInstance().player;
        Vector3d eye = f.toGal(new Vector3d(p.getX(), p.getEyeY(), p.getZ()));
        Vector3d look = f.dirToGal(LookMath.direction(yRot, xRot));
        PlanetSession.Aim a = s.aim(eye, look);
        return a == null ? new int[] {-1, -1} : new int[] {a.cell(), s.planet().get(a.cell())};
    }

    private static int outlineEdges(PlanetSession s, int cell) {
        try {
            var m = PlanetSession.class.getDeclaredMethod("outlinePayload", int.class);
            m.setAccessible(true);
            return java.nio.ByteBuffer.wrap((byte[]) m.invoke(s, cell)).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(4);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A Dolphin screenshot of the game a moment from now (test ticks outrun the game): its file. */
    private static String shot(ClientGameTestContext ctx, String name) {
        long end = System.currentTimeMillis() + 500;
        while (System.currentTimeMillis() < end) ctx.waitTicks(1);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("screenshot: (\\S+)").matcher(gxdev("ctl", "shot " + name));
        return m.find() ? m.group(1) : "";
    }

    /** Whether two screenshots differ anywhere. */
    private static boolean differ(String a, String b) {
        try {
            var ia = javax.imageio.ImageIO.read(new java.io.File(a));
            var ib = javax.imageio.ImageIO.read(new java.io.File(b));
            for (int y = 0; y < ia.getHeight(); y++)
                for (int x = 0; x < ia.getWidth(); x++)
                    if (ia.getRGB(x, y) != ib.getRGB(x, y)) return true;
            return false;
        } catch (IOException | RuntimeException e) {
            log("no screenshots to compare: " + a + ", " + b + ": " + e);
            return false;
        }
    }

    private static Object field(Object o, String name) {
        try {
            Field f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(o);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private void check(boolean ok, String what) {
        log((ok ? "ok   " : "FAIL ") + what);
        if (!ok) failed = true;
    }

    private static void log(String msg) {
        System.out.println("[GalaxyCraft mining] " + msg);
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
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }
}
