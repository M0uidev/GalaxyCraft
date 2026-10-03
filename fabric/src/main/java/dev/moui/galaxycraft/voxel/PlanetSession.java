package dev.moui.galaxycraft.voxel;

import dev.moui.galaxycraft.proto.Layout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.List;
import org.joml.Vector3d;

/**
 * The voxel planet as the mod plays it: where it is in the galaxy, what to send the host
 * (GXC_MSG_PLANET / CHUNK / PLANET_TP, in order) and the player's edits. Galaxy units outside,
 * blocks inside {@link VoxelPlanet}. No Minecraft types, so it is unit tested.
 *
 * Every chunk with something to show is drawn; only those near Mario carry collision (the game's
 * collision zones hold 512 parts, its own stage's included). Chunks are meshed as they are sent,
 * nearest to Mario first, so a big planet arrives over a few seconds without stalling a tick.
 */
public final class PlanetSession {
    public record Msg(int type, byte[] payload) {}

    public static final double REACH = 4.5;
    /** Collision reaches this far around Mario, blocks, for at most MAX_PARTS chunks. */
    public static final double NEAR = 24;
    public static final int MAX_PARTS = 160;
    /** Updates between recomputing which chunks are near Mario. */
    public static final int RESIDENCY_UPDATES = 10;
    /** In the chunk queue: the teleport, once the chunks before it are out. */
    private static final int TP_MARK = -1;

    private final double unitsPerBlock;
    private final Deque<Msg> control = new ArrayDeque<>();
    private final Deque<Integer> pending = new ArrayDeque<>();
    private final BitSet pendingSet = new BitSet();
    private BitSet onGuest = new BitSet(); // chunks the game has something of
    private BitSet near = new BitSet();    // chunks with collision (wanted)
    private BitSet withKcl = new BitSet(); // chunks sent with collision
    private Msg built;
    private int builtChunk = -1;
    private boolean builtStale; // edited again while its message waited for room
    private VoxelPlanet planet;
    private Vector3d center;
    private int id;
    private int scene = Integer.MIN_VALUE, host = Integer.MIN_VALUE;
    private int sinceResidency;
    private boolean unsaved;
    private Vector3d mario;

    public PlanetSession(double unitsPerBlock) {
        this.unitsPerBlock = unitsPerBlock;
    }

    public boolean active() {
        return planet != null;
    }

    public VoxelPlanet planet() {
        return planet;
    }

    public Vector3d center() {
        return center;
    }

    /** Gravity reaches this far from the center, blocks: the surface plus half the radius (40 at least). */
    public static double gravityRadius(double surface) {
        return surface + Math.max(40, surface / 2);
    }

    /** A new planet of this radius above the player, just out of its gravity's reach. */
    public void spawn(int radius, Vector3d feetGal, Vector3d upGal) {
        VoxelPlanet p = VoxelPlanet.ofRadius(radius);
        double above = (gravityRadius(p.surface()) + 24) * unitsPerBlock;
        start(p, new Vector3d(upGal).normalize().mul(above).add(feetGal));
        unsaved = true;
    }

    /** A saved planet, as it was. */
    public void load(PlanetStore.Saved s) {
        start(VoxelPlanet.of(new CubeSphere(s.n(), s.core(), s.layers()), s.depth(), s.cells()), s.center());
        unsaved = false;
    }

    public PlanetStore.Saved save() {
        unsaved = false;
        CubeSphere g = planet.grid;
        return new PlanetStore.Saved(g.n, g.core, g.layers, planet.depth, new Vector3d(center), planet.cells().clone());
    }

    /** Edited (or new) since the last {@link #save()}. */
    public boolean unsaved() {
        return planet != null && unsaved;
    }

    /** Forgets the planet here; the game drops it too. */
    public void remove() {
        if (planet == null) return;
        planet = null;
        clearQueues();
        control.add(new Msg(Layout.MSG_PLANET, planetPayload(0)));
    }

    /** Forgets the planet without telling the game (it is gone with its scene). */
    public void unload() {
        planet = null;
        clearQueues();
    }

    /**
     * Mario onto the surface, where the game puts him: on the line from the center through him.
     * The collision there goes first, then the teleport, so he never lands where nothing is solid.
     */
    public void teleport() {
        if (planet == null) return;
        Vector3d land = mario == null || mario.lengthSquared() < 1 ? new Vector3d(0, 1, 0) : new Vector3d(mario);
        land.normalize(planet.surface());
        List<Integer> first = residency(land);
        pending.removeIf(first::contains);
        pending.addFirst(TP_MARK);
        for (int i = first.size() - 1; i >= 0; i--) {
            pending.addFirst(first.get(i));
            pendingSet.set(first.get(i));
        }
    }

    /**
     * Once per tick, with Mario's position (galaxy units, null if unknown): a new scene or host lost
     * everything, so it is all sent again; then the edits and the chunks whose collision changed.
     */
    public void update(int sceneId, int hostPid, Vector3d marioGal) {
        if (planet == null) return;
        mario = marioGal == null ? null : local(marioGal);
        if (sceneId != scene || hostPid != host) {
            scene = sceneId;
            host = hostPid;
            sendAll();
        }
        for (int c : planet.takeDirty()) queue(c);
        if (++sinceResidency >= RESIDENCY_UPDATES && mario != null) {
            sinceResidency = 0;
            for (int c : residency(mario)) queue(c);
        }
    }

    /** Next message to send, or null; {@link #sent()} once the ring took it. */
    public Msg peek() {
        if (!control.isEmpty()) return control.peek();
        while (built == null && !pending.isEmpty()) {
            int c = pending.poll();
            if (c == TP_MARK) {
                built = new Msg(Layout.MSG_PLANET_TP, new byte[0]);
                break;
            }
            pendingSet.clear(c);
            // A chunk that just got something to show (dug into) may be under Mario already:
            // whether it is near is decided now, not at the next residency pass.
            if (!near.get(c) && mario != null && near.cardinality() < MAX_PARTS && distance(c, mario) < NEAR)
                near.set(c);
            boolean kcl = near.get(c);
            if (!onGuest.get(c) && !planet.mayShow(c)) continue;
            PlanetMesher.ChunkMesh m = PlanetMesher.mesh(planet, c, unitsPerBlock, kcl);
            if (m.empty() && !onGuest.get(c)) continue;
            ByteBuffer b = ByteBuffer.allocate(32 + m.displayList().length + m.kcl().length).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(c).putInt(planet.bump(c)).putInt(m.displayList().length).putInt(m.kcl().length);
            for (float f : m.sphere()) b.putFloat(f);
            b.put(m.displayList()).put(m.kcl());
            built = new Msg(Layout.MSG_CHUNK, b.array());
            builtChunk = c;
            onGuest.set(c, !m.empty());
            withKcl.set(c, kcl && !m.empty());
        }
        return built;
    }

    public void sent() {
        if (!control.isEmpty()) {
            control.poll();
            return;
        }
        int c = builtChunk;
        built = null;
        builtChunk = -1;
        if (builtStale) {
            builtStale = false;
            queue(c);
        }
    }

    /** Messages and chunks still to send (chunks may turn out to have nothing to send). */
    public int queued() {
        return control.size() + pending.size() + (built != null ? 1 : 0);
    }

    /** Chunks that carry collision now. */
    public int collisionChunks() {
        return withKcl.cardinality();
    }

    /** Breaks the block the eye looks at. False if none in reach or it is unbreakable. */
    public boolean breakBlock(Vector3d eyeGal, Vector3d lookGal) {
        PlanetRaycast.Hit h = cast(eyeGal, lookGal);
        if (h == null || !planet.get(h.hit()).breakable()) return false;
        planet.set(h.hit(), Material.AIR);
        unsaved = true;
        return true;
    }

    /** Places m against the block the eye looks at, unless Mario stands in that cell. */
    public boolean placeBlock(Vector3d eyeGal, Vector3d lookGal, Material m, Vector3d marioFeetGal) {
        PlanetRaycast.Hit h = cast(eyeGal, lookGal);
        if (h == null || h.before() < 0 || m == null || !m.solid()) return false;
        Vector3d feet = local(marioFeetGal);
        Vector3d up = new Vector3d(feet).normalize();
        for (double y : new double[] {0.1, 0.9, 1.7})
            if (planet.grid.cellAt(new Vector3d(up).mul(y).add(feet)) == h.before()) return false;
        planet.set(h.before(), m);
        unsaved = true;
        return true;
    }

    private void start(VoxelPlanet p, Vector3d c) {
        planet = p;
        center = c;
        id++;
        scene = host = Integer.MIN_VALUE; // the next update sends it all
    }

    private void clearQueues() {
        control.clear();
        pending.clear();
        pendingSet.clear();
        built = null;
        builtChunk = -1;
        builtStale = false;
        onGuest = new BitSet();
        withKcl = new BitSet();
        near = new BitSet();
    }

    /** The game has nothing: the planet, then every chunk that may show, nearest Mario first. */
    private void sendAll() {
        clearQueues();
        control.add(new Msg(Layout.MSG_PLANET, planetPayload(id)));
        planet.takeDirty();
        if (mario != null) residency(mario);
        List<double[]> order = new ArrayList<>();
        Vector3d c = new Vector3d();
        double[] r = new double[1];
        for (int ch = 0; ch < planet.chunkCount(); ch++) {
            planet.sphere(ch, c, r);
            order.add(new double[] {mario == null ? 0 : c.distance(mario) - r[0], ch});
        }
        order.sort((a, b) -> Double.compare(a[0], b[0]));
        for (double[] o : order) queue((int) o[1]);
    }

    /**
     * Collision goes to the nearest chunks within NEAR blocks of at (planet blocks). Returns the
     * chunks to send for it, nearest first: those the game has whose collision changes, and those
     * near that it does not have yet.
     */
    private List<Integer> residency(Vector3d at) {
        List<double[]> close = new ArrayList<>();
        Vector3d c = new Vector3d();
        double[] r = new double[1];
        for (int ch = 0; ch < planet.chunkCount(); ch++) {
            planet.sphere(ch, c, r);
            double d = c.distance(at) - r[0];
            if (d < NEAR) close.add(new double[] {d, ch});
        }
        close.sort((a, b) -> Double.compare(a[0], b[0]));
        BitSet next = new BitSet();
        for (double[] o : close) {
            if (next.cardinality() >= MAX_PARTS) break;
            int ch = (int) o[1];
            if (onGuest.get(ch) || planet.mayShow(ch)) next.set(ch);
        }
        BitSet dropped = (BitSet) near.clone();
        dropped.andNot(next);
        near = next;
        List<Integer> send = new ArrayList<>();
        for (double[] o : close) {
            int ch = (int) o[1];
            if (near.get(ch) && (!onGuest.get(ch) || !withKcl.get(ch))) send.add(ch);
        }
        dropped.stream().filter(ch -> withKcl.get(ch)).forEach(send::add);
        return send;
    }

    private double distance(int chunk, Vector3d at) {
        Vector3d c = new Vector3d();
        double[] r = new double[1];
        planet.sphere(chunk, c, r);
        return c.distance(at) - r[0];
    }

    private void queue(int chunk) {
        if (chunk == builtChunk) {
            builtStale = true;
            return;
        }
        if (pendingSet.get(chunk)) return;
        pendingSet.set(chunk);
        pending.add(chunk);
    }

    private PlanetRaycast.Hit cast(Vector3d eyeGal, Vector3d lookGal) {
        if (planet == null) return null;
        return PlanetRaycast.cast(planet, local(eyeGal), new Vector3d(lookGal).normalize(), REACH);
    }

    private Vector3d local(Vector3d gal) {
        return new Vector3d(gal).sub(center).div(unitsPerBlock);
    }

    private byte[] planetPayload(int planetId) {
        ByteBuffer b = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).putInt(planetId);
        Vector3d c = center == null ? new Vector3d() : center;
        b.putFloat((float) c.x).putFloat((float) c.y).putFloat((float) c.z);
        double surface = planet == null ? 0 : planet.surface();
        b.putFloat((float) (surface * unitsPerBlock)).putFloat((float) (gravityRadius(surface) * unitsPerBlock));
        b.putInt(planet == null ? 0 : planet.chunkCount());
        b.putFloat((float) (planet == null ? 0 : planet.occluder() * unitsPerBlock));
        return b.array();
    }
}
