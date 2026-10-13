package dev.moui.galaxycraft.shadow;

import dev.moui.galaxycraft.toad.FreeSpot;
import dev.moui.galaxycraft.voxel.CellSpace;
import dev.moui.galaxycraft.voxel.VoxelPlanet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.npc.villager.Villager;
import org.joml.Vector3d;

/**
 * The Toads of the running planet as villagers in the shadow: one tagged {@link Villager} per wanted
 * Toad, put on free ground, kept out of blocks, with its position published each second and its
 * death announced. Server thread only, except the snapshots.
 */
public final class ShadowToads {
    public static final String TAG = "galaxycraft_toad";
    private static final String PREFIX = TAG + ":";

    /** A Toad the client wants running: id, feet in planet space (blocks) and facing (degrees). */
    public record Wanted(String id, double[] local, float yaw) {}

    private static volatile List<Wanted> wanted = List.of();
    private static volatile Map<String, double[]> positions = Map.of();
    private static final ConcurrentLinkedQueue<String> deaths = new ConcurrentLinkedQueue<>();
    /** Server thread: id -> entity id of the villager that is this Toad. */
    private static final Map<String, Integer> alive = new HashMap<>();
    private static int ticks;

    private ShadowToads() {}

    static void want(List<Wanted> toads) {
        wanted = List.copyOf(toads);
    }

    /** The Toad id an entity carries, or null. */
    public static String idOf(Entity e) {
        for (String t : e.entityTags()) if (t.startsWith(PREFIX)) return t.substring(PREFIX.length());
        return null;
    }

    public static boolean isToad(Entity e) {
        return idOf(e) != null;
    }

    /** Planet-space feet (blocks) of each running Toad, as of the last second. */
    public static Map<String, double[]> positions() {
        return positions;
    }

    /** The id of a Toad that died since the last call, or null. */
    public static String pollDeath() {
        return deaths.poll();
    }

    /** The planet went away (or another came): the entities go, nobody is dead. */
    static void release(ServerLevel level) {
        for (Entity e : level.getAllEntities()) if (isToad(e)) e.discard();
        alive.clear();
        positions = Map.of();
        wanted = List.of();
    }

    /** Each server tick, with the running planet and its mirrored columns. */
    static void tick(ServerLevel level, VoxelPlanet planet, ShadowMap map, Set<Long> mirrored) {
        if (planet == null || map == null) return;
        ticks++;
        Set<String> want = new HashSet<>();
        for (Wanted w : wanted) {
            want.add(w.id());
            Integer eid = alive.get(w.id());
            if (eid != null) {
                Entity e = level.getEntity(eid);
                if (e != null && !e.isRemoved()) continue;
                alive.remove(w.id()); // it was there and is gone: killed (a /kill counts), not unloaded
                deaths.add(w.id());
                continue;
            }
            spawn(level, planet, map, mirrored, w);
        }
        // Toads nobody wants any more (the record was deleted): their entities go.
        for (var it = alive.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            if (want.contains(en.getKey())) continue;
            Entity e = level.getEntity(en.getValue());
            if (e != null) e.discard();
            it.remove();
        }
        if (ticks % 20 != 0) return;
        Map<String, double[]> out = new HashMap<>();
        for (var en : alive.entrySet()) {
            Entity e = level.getEntity(en.getValue());
            if (e == null || e.isRemoved()) continue;
            freeIfStuck(level, e);
            double[] f = map.frame(e.getX(), e.getY(), e.getZ());
            if (f != null) out.put(en.getKey(), new double[] {f[0], f[1], f[2]});
        }
        positions = out;
    }

    private static FreeSpot.Space space(ServerLevel level) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        return (x, y, z) -> {
            p.set(x, y, z);
            if (!level.isLoaded(p)) return true; // unloaded counts as solid: never spawn into the unknown
            return !level.getBlockState(p).getCollisionShape(level, p).isEmpty();
        };
    }

    private static void spawn(ServerLevel level, VoxelPlanet planet, ShadowMap map, Set<Long> mirrored, Wanted w) {
        Vector3d at = new Vector3d(w.local()[0], w.local()[1], w.local()[2]);
        int cell = planet.grid.cellAt(at);
        if (cell < 0 || !mirrored.contains(net.minecraft.world.level.ChunkPos.pack(map.x(cell) >> 4, map.z(cell) >> 4))) return;
        Vector3d m = CellSpace.local(planet.grid, cell, at);
        int[] p = FreeSpot.nearest(space(level), (int) Math.floor(map.x(cell) + m.x), (int) Math.floor(map.y(cell) + m.y),
                (int) Math.floor(map.z(cell) + m.z), 6);
        if (p == null) return; // no room yet (the columns are still being copied in): next tick
        Villager v = net.minecraft.world.entity.EntityTypes.VILLAGER.create(level, EntitySpawnReason.TRIGGERED);
        if (v == null) return;
        v.snapTo(p[0] + 0.5, p[1], p[2] + 0.5, w.yaw(), 0);
        v.setPersistenceRequired();
        v.addTag(PREFIX + w.id());
        level.addFreshEntity(v);
        alive.put(w.id(), v.getId());
    }

    /** An entity whose feet or head are in a block goes to the nearest free spot. */
    private static void freeIfStuck(ServerLevel level, Entity e) {
        // A sleeper lies in its bed (a block with a collision box): not stuck, and moving it wakes it.
        if (e instanceof net.minecraft.world.entity.LivingEntity l && l.isSleeping() || e.isPassenger()) return;
        FreeSpot.Space s = space(level);
        int x = (int) Math.floor(e.getX()), y = (int) Math.floor(e.getY()), z = (int) Math.floor(e.getZ());
        if (!FreeSpot.inside(s, x, y, z)) return;
        int[] p = FreeSpot.nearest(s, x, y, z, 6);
        if (p != null) e.snapTo(p[0] + 0.5, p[1], p[2] + 0.5, e.getYRot(), e.getXRot());
    }
}
