package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.shadow.ShadowWorld;
import dev.moui.galaxycraft.voxel.PlanetDrops;
import dev.moui.galaxycraft.voxel.PlanetSession;
import dev.moui.galaxycraft.voxel.VoxelPlanet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3d;

/**
 * The planet's dropped items: they arrive from {@link ShadowWorld} (a block's drops, an item the
 * player threw), lie on the planet ({@link PlanetDrops}), go into the inventory when Mario walks
 * over them, and are drawn as Minecraft's own item entities, client-side only, placed each tick
 * where the planet has them.
 */
public final class DropsClient {
    /** Ticks before a drop that did not fit in the inventory is tried again. */
    static final int RETRY_DELAY = 20;
    static final double MERGE_DISTANCE = 0.5;

    private final PlanetSession session;
    private final PlanetDrops<ItemStack> drops = new PlanetDrops<>();
    private final Map<PlanetDrops.Drop<ItemStack>, ItemEntity> shown = new IdentityHashMap<>();
    private VoxelPlanet planet;
    private int ticks;
    /** Client-only entities' ids: negative, never the server's. */
    private int nextId = -1_000_000;

    public DropsClient(PlanetSession session) {
        this.session = session;
    }

    public List<PlanetDrops.Drop<ItemStack>> all() {
        return drops.all();
    }

    /** Client tick: frame maps the galaxy to Minecraft (null: not linked), Mario's feet in the galaxy. */
    public void tick(Minecraft mc, GravityFrame frame, Vector3d marioFeetGal) {
        VoxelPlanet p = session.active() ? session.planet() : null;
        if (p != planet) {
            drops.clear();
            planet = p;
        }
        ShadowWorld.catchThrown(p != null && frame != null && ShadowWorld.available());
        for (ShadowWorld.Drop d; (d = ShadowWorld.pollDrop()) != null; ) {
            if (p == null) continue;
            if (d.planet() == p) drops.add(d.pos(), d.vel(), d.stack(), d.delay());
            else if (d.planet() == null && frame != null)
                drops.add(session.localOf(frame.toGal(d.pos())), frame.dirToGal(d.vel()), d.stack(), d.delay());
        }
        if (p != null) {
            drops.tick(p);
            if (++ticks % 10 == 0) merge();
            if (marioFeetGal != null && mc.player != null && mc.player.isAlive() && !mc.player.isSpectator()) {
                Vector3d feet = session.localOf(marioFeetGal);
                for (PlanetDrops.Drop<ItemStack> d : drops.pickUp(feet, new Vector3d(feet).normalize())) {
                    Vector3d at = new Vector3d(d.pos);
                    ShadowWorld.give(mc.player.getUUID(), d.item, left -> drops.add(at, new Vector3d(), left, RETRY_DELAY));
                }
            }
        }
        show(mc, frame);
    }

    /** Minecraft's merging: stacks of the same item lying close together become one. */
    private void merge() {
        List<PlanetDrops.Drop<ItemStack>> all = drops.all();
        for (int a = 0; a < all.size(); a++)
            for (int b = all.size() - 1; b > a; b--) {
                ItemStack x = all.get(a).item, y = all.get(b).item;
                if (all.get(a).pos.distance(all.get(b).pos) > MERGE_DISTANCE || !ItemStack.isSameItemSameComponents(x, y)
                        || x.getCount() + y.getCount() > x.getMaxStackSize())
                    continue;
                x.grow(y.getCount());
                all.get(a).delay = Math.max(all.get(a).delay, all.get(b).delay);
                all.remove(b);
            }
    }

    private void show(Minecraft mc, GravityFrame frame) {
        boolean visible = frame != null && mc.level != null && planet != null;
        shown.entrySet().removeIf(e -> {
            boolean keep = visible && e.getValue().level() == mc.level && drops.all().contains(e.getKey());
            if (!keep && e.getValue().level() == mc.level) mc.level.removeEntity(e.getValue().getId(), Entity.RemovalReason.DISCARDED);
            return !keep;
        });
        if (!visible) return;
        for (PlanetDrops.Drop<ItemStack> d : drops.all()) {
            Vector3d at = frame.toMc(session.galOf(d.pos));
            ItemEntity e = shown.get(d);
            if (e == null) {
                e = new ItemEntity(mc.level, at.x, at.y, at.z, d.item.copy());
                e.setId(nextId--);
                e.setNoGravity(true);
                e.noPhysics = true;
                e.setDeltaMovement(0, 0, 0);
                mc.level.addEntity(e);
                shown.put(d, e);
            } else if (e.getItem().getCount() != d.item.getCount()) e.setItem(d.item.copy());
            e.setPos(at.x, at.y, at.z);
        }
    }
}
