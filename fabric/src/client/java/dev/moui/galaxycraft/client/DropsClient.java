package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.shadow.ShadowWorld;
import dev.moui.galaxycraft.voxel.PlanetDrops;
import dev.moui.galaxycraft.voxel.PlanetSession;
import dev.moui.galaxycraft.voxel.VoxelPlanet;
import dev.moui.galaxycraft.net.MpPayloads;
import dev.moui.galaxycraft.net.MpServer;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3d;

/**
 * The planet's dropped items: they arrive from {@link ShadowWorld} (a block's drops, an item the
 * player threw), lie on the planet ({@link PlanetDrops}), go into the inventory when Mario walks
 * over them, and are drawn by the game (EntityClient).
 */
public final class DropsClient {
    /** Ticks before a drop that did not fit in the inventory is tried again. */
    static final int RETRY_DELAY = 20;
    static final double MERGE_DISTANCE = 0.5;

    /** The planet in focus (the one nearest Mario): it can change from one tick to the next. */
    private final java.util.function.Supplier<PlanetSession> focus;
    private final PlanetDrops<ItemStack> drops = new PlanetDrops<>();
    private VoxelPlanet planet;
    private int ticks;

    public DropsClient(java.util.function.Supplier<PlanetSession> focus) {
        this.focus = focus;
    }

    public List<PlanetDrops.Drop<ItemStack>> all() {
        return drops.all();
    }

    /** Client tick: frame maps the galaxy to Minecraft (null: not linked), Mario's feet in the galaxy. */
    public void tick(Minecraft mc, GravityFrame frame, Vector3d marioFeetGal) {
        VoxelPlanet p = session().active() ? session().planet() : null;
        if (p != planet) {
            drops.clear();
            planet = p;
        }
        ShadowWorld.catchThrown(p != null && frame != null && ShadowWorld.available());
        boolean guest = MpUse.guest();
        for (ShadowWorld.Drop d; (d = ShadowWorld.pollDrop()) != null; ) {
            if (p == null) continue;
            if (d.planet() == p) drops.add(d.pos(), d.vel(), d.stack(), d.delay());
            else if (d.planet() == null && frame != null)
                drops.add(session().localOf(frame.toGal(d.pos())), frame.dirToGal(d.vel()), d.stack(), d.delay());
        }
        if (p != null) {
            drops.tick(p);
            if (++ticks % 10 == 0 && !guest) merge(); // the host's drops are the real ones
            if (marioFeetGal != null && mc.player != null && mc.player.isAlive() && !mc.player.isSpectator()) {
                Vector3d feet = session().localOf(marioFeetGal);
                for (PlanetDrops.Drop<ItemStack> d : drops.pickUp(feet, p.up(feet))) {
                    Vector3d at = new Vector3d(d.pos);
                    if (guest) { // the host's inventory side gives it to this player; it comes back in the next list if refused
                        ClientPlayNetworking.send(new MpPayloads.DropTake(PlanetClient.catalogIndex(session()), d.id));
                        continue;
                    }
                    ShadowWorld.give(mc.player.getUUID(), d.item, left -> drops.add(at, new Vector3d(), left, RETRY_DELAY));
                }
            }
        }
    }

    // ---- sharing

    /** Host: tells the guests that hold this planet what lies on it. */
    void hostSend(int planetIndex, java.util.Collection<java.util.UUID> holders) {
        if (holders.isEmpty()) return;
        List<MpPayloads.DropItem> items = new ArrayList<>();
        for (PlanetDrops.Drop<ItemStack> d : drops.all()) {
            if (items.size() >= MpPayloads.MAX_DROPS) break;
            items.add(new MpPayloads.DropItem(d.id, (float) d.pos.x, (float) d.pos.y, (float) d.pos.z, (float) d.vel.x,
                    (float) d.vel.y, (float) d.vel.z, d.delay, d.item));
        }
        MpPayloads.Drops body = new MpPayloads.Drops(planetIndex, items);
        for (java.util.UUID g : holders) MpServer.drops(g, body);
    }

    /** Host: a guest stepped on drop id: it leaves the planet and goes into that player's inventory (back on the ground if it does not fit). */
    void take(java.util.UUID guest, int id) {
        for (var it = drops.all().iterator(); it.hasNext(); ) {
            PlanetDrops.Drop<ItemStack> d = it.next();
            if (d.id != id) continue;
            if (d.delay > 0) return;
            it.remove();
            Vector3d at = new Vector3d(d.pos);
            ShadowWorld.give(guest, d.item, left -> drops.add(at, new Vector3d(), left, RETRY_DELAY));
            return;
        }
    }

    /** Guest: the host's list for a planet; the ones here follow it (their physics runs between lists). */
    void receive(MpPayloads.Drops m) {
        VoxelPlanet p = session().active() ? session().planet() : null;
        if (p == null || m.planet() != PlanetClient.catalogIndex(session())) return;
        java.util.Map<Integer, PlanetDrops.Drop<ItemStack>> mine = new java.util.HashMap<>();
        for (PlanetDrops.Drop<ItemStack> d : drops.all()) mine.put(d.id, d);
        List<PlanetDrops.Drop<ItemStack>> kept = new ArrayList<>();
        for (MpPayloads.DropItem i : m.items()) {
            PlanetDrops.Drop<ItemStack> d = mine.get(i.id());
            if (d == null) {
                d = drops.add(new Vector3d(i.x(), i.y(), i.z()), new Vector3d(i.vx(), i.vy(), i.vz()), i.stack(), i.delay());
                d.id = i.id();
            } else {
                d.pos.set(i.x(), i.y(), i.z());
                d.vel.set(i.vx(), i.vy(), i.vz());
                d.item = i.stack();
                d.delay = i.delay();
            }
            kept.add(d);
        }
        drops.all().retainAll(kept);
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

    private PlanetSession session() {
        return focus.get();
    }
}
