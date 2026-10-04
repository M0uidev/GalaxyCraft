package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.shadow.ShadowWorld;
import dev.moui.galaxycraft.voxel.PlanetDrops;
import dev.moui.galaxycraft.voxel.PlanetSession;
import dev.moui.galaxycraft.voxel.VoxelPlanet;
import java.util.List;
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
        for (ShadowWorld.Drop d; (d = ShadowWorld.pollDrop()) != null; ) {
            if (p == null) continue;
            if (d.planet() == p) drops.add(d.pos(), d.vel(), d.stack(), d.delay());
            else if (d.planet() == null && frame != null)
                drops.add(session().localOf(frame.toGal(d.pos())), frame.dirToGal(d.vel()), d.stack(), d.delay());
        }
        if (p != null) {
            drops.tick(p);
            if (++ticks % 10 == 0) merge();
            if (marioFeetGal != null && mc.player != null && mc.player.isAlive() && !mc.player.isSpectator()) {
                Vector3d feet = session().localOf(marioFeetGal);
                for (PlanetDrops.Drop<ItemStack> d : drops.pickUp(feet, new Vector3d(feet).normalize())) {
                    Vector3d at = new Vector3d(d.pos);
                    ShadowWorld.give(mc.player.getUUID(), d.item, left -> drops.add(at, new Vector3d(), left, RETRY_DELAY));
                }
            }
        }
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
