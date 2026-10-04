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
    private static final int DBG_ENTITIES = 4048 + 4 * 103, DBG_LIFE = 4048 + 4 * 104;
    /** GxcMailbox.anchor_pos: Mario's position, after magic, version, three words and gravity. */
    private static final int MBX_MARIO = 36;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.entities")) return;
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getServer().runCommand("gamemode survival @a");
            sp.getServer().runCommand("difficulty normal");
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
            // The pig walks a few blocks: its legs swing by Minecraft's own walk cycle.
            sp.getServer().runOnServer(server -> {
                for (var e : server.getLevel(ShadowWorld.KEY).getAllEntities())
                    if (e instanceof net.minecraft.world.entity.animal.pig.Pig pig)
                        pig.getNavigation().moveTo(pig.getX() + (pig.getX() > x0 ? -4 : 4), pig.getY(), pig.getZ(), 1.0);
            });
            ctx.waitTicks(10);
            float walk = sp.getServer().computeOnServer(server -> {
                for (var e : server.getLevel(ShadowWorld.KEY).getAllEntities())
                    if (e instanceof net.minecraft.world.entity.animal.pig.Pig pig) return pig.walkAnimation.speed();
                return -1f;
            });
            check(walk > 0.1f, "the walking pig's legs swing (walk speed " + walk + ")");
            for (int k = 0; k < 3; k++) {
                gxdev("ctl", "shot entities-walk-" + k);
                ctx.waitTicks(3);
            }
            fight(ctx, sp, map, x0, y, z0);
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

    /** Mario hits the pig to death, a TNT goes off, a zombie hits Mario. */
    private static void fight(ClientGameTestContext ctx, TestSingleplayerContext sp, ShadowMap map, double x0, double y, double z0) {
        // Aiming: from two blocks off the pig, looking at it, the pig is what a click hits.
        boolean aims = ctx.computeOnClient(mc -> {
            for (var e : ShadowWorld.entities().list())
                if (e instanceof net.minecraft.world.entity.animal.pig.Pig pig) {
                    double[] f = map.frame(pig.getX(), pig.getY() + 0.45, pig.getZ());
                    org.joml.Vector3d at = new org.joml.Vector3d(f[0], f[1], f[2]);
                    org.joml.Vector3d eye = new org.joml.Vector3d(f[3], f[4], f[5]).mul(2).add(at);
                    return PlanetClient.aimedFrom(eye, new org.joml.Vector3d(at).sub(eye)) == pig;
                }
            return false;
        });
        check(aims, "looking at the pig aims at it");
        int dropsBefore = PlanetClient.dropCount();
        sp.getServer().runCommand("item replace entity @a hotbar.0 with diamond_sword");
        ctx.getInput().pressKey(o -> o.keyHotbarSlots[0]);
        int hits = 0;
        for (; hits < 6 && pigAlive(ctx); hits++) {
            ctx.waitTicks(15); // a full swing
            ctx.runOnClient(mc -> {
                for (var e : ShadowWorld.entities().list())
                    if (e instanceof net.minecraft.world.entity.animal.pig.Pig pig && pig.isAlive())
                        ShadowWorld.attack(pig.getId(), mc.player.getUUID());
            });
            ctx.waitTicks(2);
        }
        log("hits: " + hits);
        log("pig: " + ctx.computeOnClient(mc -> ShadowWorld.entities().list().stream()
                .filter(e -> e instanceof net.minecraft.world.entity.animal.pig.Pig)
                .map(e -> ((net.minecraft.world.entity.LivingEntity) e).getHealth() + "hp").toList())
                + ", Mario's stand-in: " + ShadowWorld.proxyState());
        boolean dying = ctx.computeOnClient(mc -> ShadowWorld.entities().list().stream()
                .anyMatch(e -> e instanceof net.minecraft.world.entity.animal.pig.Pig pig && pig.deathTime > 0));
        check(dying, "the pig falls over, dying");
        gxdev("ctl", "shot entities-pig-dying");
        ctx.waitTicks(25);
        check(PlanetClient.particleCount() > 0, "its last poof of smoke (" + PlanetClient.particleCount() + " particles)");
        gxdev("ctl", "shot entities-pig-poof");
        ctx.waitTicks(20);
        boolean pork = ctx.computeOnClient(mc -> mc.player.getInventory().contains(new net.minecraft.world.item.ItemStack(
                net.minecraft.world.item.Items.PORKCHOP)));
        log("drops " + dropsBefore + " -> " + PlanetClient.dropCount() + ", pork in the inventory: " + pork);
        check(PlanetClient.dropCount() > dropsBefore || pork, "its pork drops (on the planet or picked up)");

        // TNT a few blocks away: it goes off with its flash.
        sp.getServer().runCommand(String.format(java.util.Locale.ROOT,
                "execute in galaxycraft:shadow run summon minecraft:tnt %.2f %.2f %.2f {fuse:20}", x0, y, z0 - 6));
        ctx.waitTicks(21);
        check(PlanetClient.particleCount() > 5, "the explosion's flash and smoke (" + PlanetClient.particleCount() + " particles)");
        gxdev("ctl", "shot entities-explosion");
        ctx.waitTicks(40);

        // A zombie next to Mario: it goes for him, and the player is hurt.
        sp.getServer().runCommand("effect clear @a");
        sp.getServer().runCommand("effect give @a instant_health 1 5");
        ctx.waitTicks(10);
        float before = ctx.computeOnClient(mc -> mc.player.getHealth());
        int life = word(DBG_LIFE);
        sp.getServer().runCommand(String.format(java.util.Locale.ROOT,
                "execute in galaxycraft:shadow run summon minecraft:zombie %.2f %.2f %.2f", x0 + 1.5, y, z0));
        float[] from = new float[3];
        ctx.waitFor(mc -> {
            if (mc.player.getHealth() >= before) System.arraycopy(marioPos(), 0, from, 0, 3);
            return mc.player.getHealth() < before;
        }, 200);
        log("ok the zombie hurts Mario (" + before + " -> " + ctx.computeOnClient(mc -> mc.player.getHealth()) + ")");
        gxdev("ctl", "shot entities-zombie");
        ctx.waitTicks(10);
        float[] to = marioPos();
        double moved = Math.sqrt(Math.pow(to[0] - from[0], 2) + Math.pow(to[1] - from[1], 2) + Math.pow(to[2] - from[2], 2));
        gxdev("ctl", "shot entities-mario-hurt");
        int[] hurts = words(DBG_LIFE + 4, 2);
        log("Mario " + java.util.Arrays.toString(from) + " -> " + java.util.Arrays.toString(to) + ", blows passed on " + hurts[0]
                + ", taken " + hurts[1]);
        check(moved > 30, "Mario reels from the blow (moved " + Math.round(moved) + " units)");
        ctx.waitTicks(60);
        check(word(DBG_LIFE) == life, "SMG2's life meter is left as it was (" + life + " -> " + word(DBG_LIFE) + ")");
        sp.getServer().runCommand("execute in galaxycraft:shadow run kill @e[type=zombie]");
        sp.getServer().runCommand("effect give @a instant_health 1 5");

        // Everything else, by its own renderer: an arrow, a minecart, a boat, an armored armor stand.
        int drawnBefore = count();
        String[] more = {"arrow{Motion:[0.0,0.0,0.0]}", "minecart", "oak_boat",
                "armor_stand{equipment:{head:{id:\"minecraft:diamond_helmet\",count:1},chest:{id:\"minecraft:iron_chestplate\",count:1}}}"};
        for (int i = 0; i < more.length; i++) {
            String type = more[i].contains("{") ? more[i].substring(0, more[i].indexOf('{')) : more[i];
            String nbt = more[i].contains("{") ? " " + more[i].substring(more[i].indexOf('{')) : "";
            sp.getServer().runCommand(String.format(java.util.Locale.ROOT,
                    "execute in galaxycraft:shadow run summon minecraft:%s %.2f %.2f %.2f%s", type, x0 + 2.5, y, z0 - 2 + 1.5 * i, nbt));
        }
        ctx.waitTicks(20);
        int drawn = count();
        log("in the shadow: " + ctx.computeOnClient(mc -> ShadowWorld.entities().list().stream()
                .map(e -> e.getType().toShortString()).toList().toString()) + ", pieces " + drawnBefore + " -> " + drawn);
        check(drawn >= drawnBefore + 12, "the arrow, minecart, boat and armor stand (with its armor) are drawn");
        ctx.runOnClient(mc -> mc.player.setYRot(mc.player.getYRot() - 90));
        ctx.waitTicks(5);
        gxdev("ctl", "shot entities-more");
    }

    private static int word(int offset) {
        return words(offset, 1)[0];
    }

    /** Mario's position in the galaxy, as the game publishes it. */
    private static float[] marioPos() {
        int[] w = words(MBX_MARIO, 3);
        return new float[] {Float.intBitsToFloat(w[0]), Float.intBitsToFloat(w[1]), Float.intBitsToFloat(w[2])};
    }

    /** n big-endian words at the mailbox + offset (the debug block follows it at 4048). */
    private static int[] words(int offset, int n) {
        Matcher m = MBX.matcher(gxdev("ctl", "mbx"));
        check(m.find(), "the mailbox is found");
        long at = Long.parseLong(m.group(1), 16) + offset;
        String[] parts = gxdev("ctl", "peek 0x" + Long.toHexString(at) + " " + 4 * n).strip().split("\\s+");
        int[] w = new int[n];
        for (int i = 0; i < 4 * n; i++) w[i / 4] = w[i / 4] << 8 | Integer.parseInt(parts[parts.length - 4 * n + i], 16);
        return w;
    }

    private static boolean pigAlive(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> ShadowWorld.entities().list().stream()
                .anyMatch(e -> e instanceof net.minecraft.world.entity.animal.pig.Pig pig && pig.isAlive()));
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
