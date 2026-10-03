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
    public static final double DEFAULT_MARIO_RADIUS = 0.3;
    /** The outline stands this fraction of a cell out of it. */
    static final double OUTLINE_GROW = 0.02;
    /** Collision reaches this far around Mario, blocks, for at most MAX_PARTS chunks. */
    public static final double NEAR = 24;
    public static final int MAX_PARTS = 160;
    /** Updates between recomputing which chunks are near Mario. */
    public static final int RESIDENCY_UPDATES = 10;
    /**
     * Mario's collision radius at the planet's surface, blocks (Steve's): his own is 60 units (1.5
     * blocks wide), too wide for a 1-block hole or tunnel. Below the surface it shrinks as the
     * cells narrow toward the center (the module scales it). -Dgalaxycraft.marioRadius changes it
     * (0: his own).
     */
    public static final double MARIO_RADIUS = marioRadius(System.getProperty("galaxycraft.marioRadius"));
    /** In the chunk queue: the teleport, once the chunks before it are out. */
    private static final int TP_MARK = -1;

    private final double unitsPerBlock;
    private Blocks blocks = CubeBlocks.INSTANCE;
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
    private int outline = -1; // the cell outlined in the game, -1 none
    private Vector3d mario;

    public PlanetSession(double unitsPerBlock) {
        this.unitsPerBlock = unitsPerBlock;
    }

    public boolean active() {
        return planet != null;
    }

    /** What new and loaded planets are made of (before any is). */
    public void setBlocks(Blocks blocks) {
        this.blocks = blocks;
    }

    public Blocks blocks() {
        return blocks;
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
        VoxelPlanet p = VoxelPlanet.ofRadius(radius, blocks);
        double above = (gravityRadius(p.surface()) + 24) * unitsPerBlock;
        start(p, new Vector3d(upGal).normalize().mul(above).add(feetGal));
        unsaved = true;
    }

    /** A saved planet, as it was (but for what lies below today's crust: see sealBelowCrust). */
    public void load(PlanetStore.Saved s) {
        VoxelPlanet p = VoxelPlanet.of(new CubeSphere(s.n(), s.core(), s.layers()), s.depth(), s.cells(), blocks);
        boolean sealed = p.sealBelowCrust() > 0;
        start(p, s.center());
        unsaved = sealed;
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
        if (planet.fluids().tick()) unsaved = true;
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

    /** Breaks the block the eye looks at; ice melts into water outside creative mode, as in Minecraft. */
    public boolean breakBlock(Vector3d eyeGal, Vector3d lookGal) {
        return breakBlock(eyeGal, lookGal, false);
    }

    /** False if no block in reach or it is unbreakable. Its neighbors then settle (a door's other half goes). */
    public boolean breakBlock(Vector3d eyeGal, Vector3d lookGal, boolean creative) {
        PlanetRaycast.Hit h = cast(eyeGal, lookGal);
        if (h == null || !planet.info(h.hit()).breakable()) return false;
        boolean melts = !creative && planet.material(h.hit()) == Material.ICE;
        planet.set(h.hit(), melts ? planet.blocks.fluidState(Blocks.WATER, Fluids.SOURCE) : Blocks.AIR);
        planet.settle(h.hit());
        unsaved = true;
        return true;
    }

    /** Places one of the planet's own blocks against the one the eye looks at. */
    public boolean placeBlock(Vector3d eyeGal, Vector3d lookGal, Material m, Vector3d marioFeetGal) {
        return m != null && !m.fluid() && placeBlock(eyeGal, lookGal, Placer.of(planet.blocks.id(m)), marioFeetGal);
    }

    /**
     * Places what placer gives against the block the eye looks at (into it if it is replaceable,
     * like short grass), unless a cell it fills is taken or one that collides is where Mario stands.
     * Its neighbors then settle (fences join it).
     */
    public boolean placeBlock(Vector3d eyeGal, Vector3d lookGal, Placer placer, Vector3d marioFeetGal) {
        PlanetRaycast.Hit h = cast(eyeGal, lookGal);
        if (h == null || placer == null) return false;
        CubeSphere g = planet.grid;
        int face = faceAt(h.hit(), h.point());
        int cell = planet.info(h.hit()).replaceable() ? h.hit() : g.neighbor(h.hit(), face);
        if (cell < 0 || !planet.info(cell).replaceable()) return false;
        Vector3d hit = CellSpace.local(g, cell, h.point());
        hit.set(clamp01(hit.x), clamp01(hit.y), clamp01(hit.z));
        List<int[]> sets = placer.place(planet, cell, face, hit, CellSpace.direction(g, cell, lookGal));
        if (sets == null || sets.isEmpty()) return false;
        Vector3d feet = local(marioFeetGal);
        Vector3d up = new Vector3d(feet).normalize();
        for (int[] set : sets) {
            if (set[0] < 0 || (set[0] != cell && !planet.info(set[0]).replaceable())) return false;
            if (!planet.blocks.info(set[1]).collides()) continue;
            for (double y : new double[] {0.1, 0.9, 1.7})
                if (g.cellAt(new Vector3d(up).mul(y).add(feet)) == set[0]) return false;
        }
        int[] changed = new int[sets.size()];
        for (int i = 0; i < sets.size(); i++) {
            planet.set(sets.get(i)[0], sets.get(i)[1]);
            changed[i] = sets.get(i)[0];
        }
        planet.settle(changed);
        unsaved = true;
        return true;
    }

    /** The side of cell nearest a point on (or just inside) it: the face a ray entered through. */
    private int faceAt(int cell, Vector3d point) {
        Vector3d m = CellSpace.local(planet.grid, cell, point);
        double[] dist = {1 - m.y, m.y, m.z, 1 - m.z, m.x, 1 - m.x}; // by side: TOP, BOTTOM, I-, I+, J-, J+
        int best = 0;
        for (int s = 1; s < 6; s++) if (dist[s] < dist[best]) best = s;
        return best;
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    /**
     * The cell the eye can act on: the block it points at, or with sources (an empty bucket in
     * hand) the fluid source. -1 if none in reach.
     */
    public int target(Vector3d eyeGal, Vector3d lookGal, boolean sources) {
        if (planet == null) return -1;
        PlanetRaycast.Hit h = PlanetRaycast.cast(planet, local(eyeGal), new Vector3d(lookGal).normalize(), REACH, sources);
        return h == null ? -1 : h.hit();
    }

    /** Outlines this cell in the game (Minecraft's block outline), -1 for none. Sent if it changed. */
    public void setOutline(int cell) {
        if (planet == null || cell == outline) return;
        outline = cell;
        queueOutline();
    }

    private void queueOutline() {
        control.removeIf(m -> m.type() == Layout.MSG_OUTLINE);
        control.add(new Msg(Layout.MSG_OUTLINE, outlinePayload(outline)));
    }

    /**
     * GxcOutline: the corners of the block's outline (its shape's bounds in the cell) from the
     * planet's center, a little out of it (no z-fighting).
     */
    byte[] outlinePayload(int cell) {
        ByteBuffer b = ByteBuffer.allocate(100).order(ByteOrder.LITTLE_ENDIAN).putInt(cell >= 0 ? 1 : 0);
        if (cell < 0) return b.array();
        double[] o = planet.info(cell).outline();
        Vector3d mid = CellSpace.point(planet.grid, cell, (o[0] + o[3]) / 2, (o[1] + o[4]) / 2, (o[2] + o[5]) / 2);
        for (int m = 0; m < 8; m++) {
            // Corner (di, dj, dk) is model (x, y, z) = (dj, dk, di) picks of the bounds.
            int di = m & 1, dj = m >> 1 & 1, dk = m >> 2;
            Vector3d c = CellSpace.point(planet.grid, cell, dj == 0 ? o[0] : o[3], dk == 0 ? o[1] : o[4], di == 0 ? o[2] : o[5]);
            c.sub(mid).mul(1 + OUTLINE_GROW).add(mid).mul(unitsPerBlock);
            b.putFloat((float) c.x).putFloat((float) c.y).putFloat((float) c.z);
        }
        return b.array();
    }

    /** Empties a bucket of water or lava (a source) against the block the eye looks at. */
    public boolean pour(Vector3d eyeGal, Vector3d lookGal, Material fluid) {
        PlanetRaycast.Hit h = cast(eyeGal, lookGal);
        if (h == null || h.before() < 0 || fluid == null || !fluid.fluid() || !planet.info(h.before()).replaceable())
            return false;
        planet.set(h.before(), fluid, Fluids.SOURCE);
        unsaved = true;
        return true;
    }

    /** Fills an empty bucket from the fluid source the eye looks at: what it got, or null. */
    public Material scoop(Vector3d eyeGal, Vector3d lookGal) {
        if (planet == null) return null;
        PlanetRaycast.Hit h = PlanetRaycast.cast(planet, local(eyeGal), new Vector3d(lookGal).normalize(), REACH, true);
        if (h == null || !planet.info(h.hit()).isFluid()) return null;
        Material got = planet.fluid(h.hit()) == Blocks.WATER ? Material.WATER : Material.LAVA;
        planet.set(h.hit(), Blocks.AIR);
        unsaved = true;
        return got;
    }

    private void start(VoxelPlanet p, Vector3d c) {
        planet = p;
        center = c;
        id++;
        scene = host = Integer.MIN_VALUE; // the next update sends it all
    }

    private void clearQueues() {
        control.clear();
        outline = -1;
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
        ByteBuffer b = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN).putInt(planetId);
        Vector3d c = center == null ? new Vector3d() : center;
        b.putFloat((float) c.x).putFloat((float) c.y).putFloat((float) c.z);
        double surface = planet == null ? 0 : planet.surface();
        b.putFloat((float) (surface * unitsPerBlock)).putFloat((float) (gravityRadius(surface) * unitsPerBlock));
        b.putInt(planet == null ? 0 : planet.chunkCount());
        b.putFloat((float) (planet == null ? 0 : planet.occluder() * unitsPerBlock));
        b.putFloat((float) (MARIO_RADIUS * unitsPerBlock));
        return b.array();
    }

    static double marioRadius(String value) {
        try {
            double r = value == null ? DEFAULT_MARIO_RADIUS : Double.parseDouble(value);
            return r >= 0 && r <= 1 ? r : DEFAULT_MARIO_RADIUS;
        } catch (NumberFormatException e) {
            return DEFAULT_MARIO_RADIUS;
        }
    }
}
