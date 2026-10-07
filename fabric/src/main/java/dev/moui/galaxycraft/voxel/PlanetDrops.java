package dev.moui.galaxycraft.voxel;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.joml.Vector3d;

/**
 * Items lying on a planet, moving as Minecraft's ItemEntity does with "down" toward the center:
 * they fall, stop on what collides (the block's real boxes), slide with ground friction, are pushed
 * up out of a block placed on them, and vanish after five minutes. Positions in blocks from the
 * planet's center. T is what the item is (an ItemStack in the game).
 */
public final class PlanetDrops<T> {
    public static final double GRAVITY = 0.04, DRAG = 0.98, GROUND_FRICTION = 0.6;
    public static final int LIFETIME = 6000;
    /** Minecraft's pickup reach: the player's box (0.6 wide, 1.8 tall) grown by 1 sideways and 0.5 up and down. */
    public static final double REACH_SIDE = 1.3, REACH_BELOW = 0.5, REACH_ABOVE = 2.3;

    public static final class Drop<T> {
        public final Vector3d pos, vel;
        public T item;
        public int age, delay;
        public boolean onGround;

        Drop(Vector3d pos, Vector3d vel, T item, int delay) {
            this.pos = pos;
            this.vel = vel;
            this.item = item;
            this.delay = delay;
        }
    }

    private final List<Drop<T>> drops = new ArrayList<>();

    public Drop<T> add(Vector3d pos, Vector3d vel, T item, int delay) {
        Drop<T> d = new Drop<>(new Vector3d(pos), new Vector3d(vel), item, delay);
        drops.add(d);
        return d;
    }

    public List<Drop<T>> all() {
        return drops;
    }

    public void clear() {
        drops.clear();
    }

    public void tick(VoxelPlanet p) {
        for (Iterator<Drop<T>> it = drops.iterator(); it.hasNext(); ) {
            Drop<T> d = it.next();
            if (++d.age >= LIFETIME) it.remove();
            else {
                if (d.delay > 0) d.delay--;
                step(p, d);
            }
        }
    }

    private static void step(VoxelPlanet p, Drop<?> d) {
        Vector3d up = new Vector3d(d.pos).normalize();
        if (blocked(p, d.pos)) { // a block put where it lay: out on top
            d.pos.fma(0.1, up);
            d.vel.zero();
            return;
        }
        d.vel.fma(-GRAVITY, up);
        Vector3d next = new Vector3d(d.pos).add(d.vel);
        if (!blocked(p, next)) {
            d.pos.set(next);
            d.onGround = false;
        } else {
            double radial = d.vel.dot(up);
            Vector3d slide = new Vector3d(d.vel).fma(-radial, up);
            next.set(d.pos).add(slide);
            if (!blocked(p, next)) d.pos.set(next);
            else slide.zero();
            d.onGround = radial < 0;
            d.vel.set(slide);
        }
        d.vel.mul(DRAG);
        if (d.onGround) d.vel.mul(GROUND_FRICTION);
    }

    /** Whether a point is inside something that collides (or the sealed core). */
    public static boolean blocked(VoxelPlanet p, Vector3d at) {
        int c = p.grid.cellAt(at);
        if (c < 0) return p.grid.inCore(at);
        BlockInfo b = p.info(c);
        if (!b.collides()) return false;
        if (b.fullCollision()) return true;
        Vector3d m = CellSpace.local(p.grid, c, at);
        for (double[] box : b.boxes())
            if (m.x >= box[0] && m.x <= box[3] && m.y >= box[1] && m.y <= box[4] && m.z >= box[2] && m.z <= box[5])
                return true;
        return false;
    }

    /** Drops the player standing at feet (up their up) picks up now; they leave the planet. */
    public List<Drop<T>> pickUp(Vector3d feet, Vector3d up) {
        List<Drop<T>> out = new ArrayList<>();
        for (Iterator<Drop<T>> it = drops.iterator(); it.hasNext(); ) {
            Drop<T> d = it.next();
            if (d.delay > 0) continue;
            Vector3d rel = new Vector3d(d.pos).sub(feet);
            double h = rel.dot(up);
            if (h < -REACH_BELOW || h > REACH_ABOVE || rel.fma(-h, up).length() > REACH_SIDE) continue;
            out.add(d);
            it.remove();
        }
        return out;
    }
}
