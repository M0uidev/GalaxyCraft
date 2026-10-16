package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.McBlocks;
import dev.moui.galaxycraft.client.ShadowLink;
import dev.moui.galaxycraft.shadow.ShadowToads;
import dev.moui.galaxycraft.shadow.ShadowWorld;
import dev.moui.galaxycraft.voxel.PlanetSession;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.entity.Entity;
import org.joml.Vector3d;

/**
 * Toads as villagers in the shadow (with -PgalaxycraftToadEntity, Minecraft only): one tagged
 * persistent entity per wanted Toad on free ground, its position published, unloading kills nobody.
 */
public final class ToadEntityProbe implements FabricClientGameTest {
    private PlanetSession s;
    private McBlocks blocks;
    private ShadowLink link;
    private Vector3d mario;
    private final List<String> fails = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.toadEntity")) return;
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            ctx.waitTicks(20);
            String carried = ctx.computeOnClient(mc -> {
                StringBuilder sb = new StringBuilder();
                var inv = mc.player.getInventory();
                for (int i = 0; i < inv.getContainerSize(); i++) if (!inv.getItem(i).isEmpty()) sb.append(inv.getItem(i)).append(' ');
                return sb.toString().strip();
            });
            check(carried.isEmpty(), "a new world starts with nothing in the inventory (" + carried + ")");
            ctx.runOnClient(mc -> {
                blocks = McBlocks.create(mc);
                s = new PlanetSession(1);
                s.setBlocks(blocks);
                s.spawn(32, new Vector3d(), new Vector3d(0, 1, 0));
                link = new ShadowLink(() -> s);
                mario = new Vector3d(0, s.planet().surface() + 0.5, 0).add(s.center());
            });
            int[] bed = ctx.computeOnClient(mc -> house());
            run(ctx, 60); // the shadow takes the planet in
            sleepInTheHouse(ctx, bed); // first: the only Toad about, so the only bed is its own
            want(ctx, new double[] {0, 0, 0}, 0.5);
            run(ctx, 60);
            Entity toad = find(ctx, "t1");
            check(toad != null, "a villager tagged t1 exists");
            if (toad != null) {
                check(ctx.computeOnClient(mc -> toad.getBoundingBox().getXsize() > 0.3), "it has a box");
                check(ctx.computeOnClient(mc -> toad instanceof net.minecraft.world.entity.Mob m && m.isPersistenceRequired()), "it is persistent");
            }
            want(ctx, new double[] {0, 0, 0}, 0.5); // asked twice: still one
            run(ctx, 40);
            check(count(ctx, "t1") == 1, "exactly one entity for t1, found " + count(ctx, "t1"));
            run(ctx, 30);
            check(ctx.computeOnClient(mc -> ShadowToads.positions().containsKey("t1")), "its position is published");
            hitShoveAndKill(ctx);
            ctx.runOnClient(mc -> ShadowWorld.attach(null, "probe")); // unload
            run(ctx, 10);
            check(ctx.computeOnClient(mc -> ShadowToads.pollDeath() == null), "unloading kills nobody");
            System.out.println("[GalaxyCraft toadentity] " + (fails.isEmpty() ? "PASS" : "FAIL " + fails));
        }
    }

    private static final String[] COMPASS = {"up", "down", "north", "south", "west", "east"};

    /** The cell a, b, h steps from o (a along i, b along j, h up). */
    private int at(int o, int a, int b, int h) {
        var g = s.planet().grid;
        int c = o;
        for (int i = 0; i < Math.abs(a); i++) c = g.neighbor(c, a > 0 ? dev.moui.galaxycraft.voxel.CubeSphere.I_PLUS : dev.moui.galaxycraft.voxel.CubeSphere.I_MINUS);
        for (int i = 0; i < Math.abs(b); i++) c = g.neighbor(c, b > 0 ? dev.moui.galaxycraft.voxel.CubeSphere.J_PLUS : dev.moui.galaxycraft.voxel.CubeSphere.J_MINUS);
        for (int i = 0; i < h; i++) c = g.neighbor(c, dev.moui.galaxycraft.voxel.CubeSphere.TOP);
        return c;
    }

    private int house;

    /**
     * A house of stone bricks beside the Toad's spot: 3x3 room, walls 3 high, a roof, a door on the
     * +i side, a bed along i. Returns {bed foot cell, door cell, room cell for the Toad}.
     */
    private int[] house() {
        var g = s.planet().grid;
        house = g.cellAt(new Vector3d(0, s.planet().surface() + 0.5, 0).add(new Vector3d(6, 0, 0)).normalize().mul(s.planet().surface() + 0.5));
        int o = house;
        int walls = blocks.parse("minecraft:stone_bricks");
        String east = COMPASS[dev.moui.galaxycraft.voxel.CellSpace.DIRECTION_OF_SIDE[dev.moui.galaxycraft.voxel.CubeSphere.I_PLUS]];
        for (int a = -2; a <= 2; a++)
            for (int b = -2; b <= 2; b++) {
                boolean wall = Math.abs(a) == 2 || Math.abs(b) == 2;
                for (int h = 0; h <= 3; h++) if (h == 3 || wall) s.planet().set(at(o, a, b, h), walls);
                for (int h = 0; h < 3 && !wall; h++) s.planet().set(at(o, a, b, h), blocks.parse("minecraft:air"));
            }
        s.planet().set(at(o, 2, 0, 0), blocks.parse("minecraft:oak_door[facing=" + east + ",half=lower,hinge=left,open=false]"));
        s.planet().set(at(o, 2, 0, 1), blocks.parse("minecraft:oak_door[facing=" + east + ",half=upper,hinge=left,open=false]"));
        s.planet().set(at(o, -1, 0, 0), blocks.parse("minecraft:red_bed[facing=" + east + ",part=foot]"));
        s.planet().set(at(o, 0, 0, 0), blocks.parse("minecraft:red_bed[facing=" + east + ",part=head]"));
        return new int[] {at(o, -1, 0, 0), at(o, 2, 0, 0), at(o, 1, 0, 0), at(o, 0, 0, 0)};
    }

    /** At night a Toad in a house with a bed and a door sleeps; the shadow knows the bed; mob caps do not count it. */
    private void sleepInTheHouse(ClientGameTestContext ctx, int[] cells) {
        double[] spot = ctx.computeOnClient(mc -> {
            Vector3d p = dev.moui.galaxycraft.voxel.CellSpace.point(s.planet().grid, cells[2], 0.5, 0.1, 0.5);
            return new double[] {p.x, p.y, p.z};
        });
        ctx.runOnClient(mc -> ShadowWorld.wantToads(s.planet(), List.of(new ShadowToads.Wanted("t2", spot, 0f))));
        time(ctx, "day"); // by day it finds its bed (the idle activity claims a home), at dusk it goes there
        run(ctx, 300);
        diag(ctx, "day", cells);
        time(ctx, "night");
        run(ctx, 600);
        diag(ctx, "night", cells);
        Entity t2 = find(ctx, "t2");
        check(t2 != null, "t2 exists after the night begins");
        diag(ctx, "night", cells);
        java.util.concurrent.atomic.AtomicBoolean known = new java.util.concurrent.atomic.AtomicBoolean();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var pos = ShadowWorld.shadowPos(s.planet(), cells[3]); // the bed's head: the villagers' HOME point
            server.execute(() -> known.set(server.getLevel(ShadowWorld.KEY).getPoiManager()
                    .existsAtPosition(net.minecraft.world.entity.ai.village.poi.PoiTypes.HOME, pos)));
        });
        ctx.waitTicks(5);
        boolean poi = known.get();
        check(poi, "the shadow level knows the bed (HOME poi)");
        if (t2 != null) check(ctx.computeOnClient(mc -> t2 instanceof net.minecraft.world.entity.LivingEntity l && l.isSleeping()), "the Toad is asleep at night");
        check(find(ctx, "t2") != null, "it was not despawned");
    }

    /** A hit hurts it; shoved into the ground it comes out within a second; a lethal blow is announced once. */
    private void hitShoveAndKill(ClientGameTestContext ctx) {
        Entity t1 = find(ctx, "t1");
        if (t1 == null) return;
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> server.getPlayerList().getPlayers().get(0).setGameMode(net.minecraft.world.level.GameType.SURVIVAL));
        });
        run(ctx, 10);
        float h0 = ctx.computeOnClient(mc -> ((net.minecraft.world.entity.LivingEntity) t1).getHealth());
        for (int i = 0; i < 6 && ctx.computeOnClient(mc -> ((net.minecraft.world.entity.LivingEntity) t1).getHealth()) >= h0; i++) {
            ctx.runOnClient(mc -> ShadowWorld.attack(t1.getId(), mc.player.getUUID()));
            run(ctx, 15); // the attack cooldown charges between blows
        }
        float h1 = ctx.computeOnClient(mc -> ((net.minecraft.world.entity.LivingEntity) t1).getHealth());
        check(h1 < h0, "a hit hurts it (" + h0 + " -> " + h1 + ")");
        check(ctx.computeOnClient(mc -> ShadowToads.pollDeath() == null), "a hit that does not kill announces no death");
        double y0 = ctx.computeOnClient(mc -> t1.getY());
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> t1.snapTo(t1.getX(), t1.getY() - 2, t1.getZ(), t1.getYRot(), 0)); // shoved into the ground
        });
        run(ctx, 45);
        double y1 = ctx.computeOnClient(mc -> t1.getY());
        check(y1 > y0 - 0.9, "shoved into the ground it came out (" + y0 + " -> " + y1 + ")");
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> t1.kill(server.getLevel(ShadowWorld.KEY)));
        });
        run(ctx, 20);
        check("t1".equals(ctx.computeOnClient(mc -> ShadowToads.pollDeath())), "its death is announced");
        check(ctx.computeOnClient(mc -> ShadowToads.pollDeath() == null), "only once");
        ctx.runOnClient(mc -> ShadowWorld.wantToads(s.planet(), List.of())); // the record is gone: nobody is wanted
        run(ctx, 20);
        check(count(ctx, "t1") == 0, "and it does not come back");
    }

    private void diag(ClientGameTestContext ctx, String label, int[] cells) {
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var bed = ShadowWorld.shadowPos(s.planet(), cells[3]);
            var door = ShadowWorld.shadowPos(s.planet(), cells[1]);
            server.execute(() -> {
                var level = server.getLevel(ShadowWorld.KEY);
                var es = ShadowWorld.entities();
                for (Entity e : es.list()) {
                    if (!"t2".equals(ShadowToads.idOf(e))) continue;
                    var v = (net.minecraft.world.entity.npc.villager.Villager) level.getEntity(e.getId());
                    var brain = v.getBrain();
                    System.out.println("[GalaxyCraft toadentity] diag " + label + " dark=" + level.isDarkOutside() + " activities=" + brain.getActiveActivities()
                            + " home=" + brain.getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.HOME)
                            + " pos=" + v.blockPosition() + " bed=" + bed + " door=" + door + " bedState=" + level.getBlockState(bed)
                            + " doorState=" + level.getBlockState(door) + " asleep=" + v.isSleeping()
                            + " poiClosest=" + level.getPoiManager().findClosest(h -> h.is(net.minecraft.world.entity.ai.village.poi.PoiTypes.HOME), bed, 48,
                                    net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.HAS_SPACE)
                            + " path=" + (v.getNavigation().createPath(bed, 1) != null)
                            + " canSleepTag=" + level.getBlockState(bed).is(net.minecraft.tags.BlockTags.VILLAGERS_CAN_SLEEP_ON_BED)
                            + " lastWoken=" + brain.getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.LAST_WOKEN)
                            + " walkTarget=" + brain.getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET).isPresent()
                            + " DIST=" + v.position().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(bed))
                            + " HOMEPOS=" + brain.getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.HOME).map(g -> g.pos().toString()).orElse("-")
                           );
                }
            });
        });
        ctx.waitTicks(3);
    }

    private void time(ClientGameTestContext ctx, String when) {
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set " + when));
        });
        ctx.waitTicks(2);
    }

    /** t1 wanted at the planet's top, plus offset blocks along the planet's up. */
    private void want(ClientGameTestContext ctx, double[] sideways, double up) {
        ctx.runOnClient(mc -> ShadowWorld.wantToads(s.planet(), List.of(new ShadowToads.Wanted("t1",
                new double[] {sideways[0], s.planet().surface() + up, sideways[2]}, 0f))));
    }

    private Entity find(ClientGameTestContext ctx, String id) {
        return ctx.computeOnClient(mc -> {
            var es = ShadowWorld.entities();
            if (es == null) return null;
            for (Entity e : es.list()) if (id.equals(ShadowToads.idOf(e))) return e;
            return null;
        });
    }

    private int count(ClientGameTestContext ctx, String id) {
        return ctx.computeOnClient(mc -> {
            var es = ShadowWorld.entities();
            int n = 0;
            if (es != null) for (Entity e : es.list()) if (id.equals(ShadowToads.idOf(e)) && !e.isRemoved()) n++;
            return n;
        });
    }

    private void run(ClientGameTestContext ctx, int ticks) {
        for (int t = 0; t < ticks; t++) {
            ctx.runOnClient(mc -> {
                link.tick("probe", mario);
                ShadowWorld.mario(new ShadowWorld.MarioAt(s.planet(), new Vector3d(0, s.planet().surface() + 0.5, 0.8), new Vector3d(0, 0, -1),
                        mc.player.getUUID()));
            });
            ctx.waitTick();
        }
    }

    private void check(boolean ok, String what) {
        System.out.println("[GalaxyCraft toadentity] " + (ok ? "ok: " : "NOT: ") + what);
        if (!ok) fails.add(what);
    }
}
