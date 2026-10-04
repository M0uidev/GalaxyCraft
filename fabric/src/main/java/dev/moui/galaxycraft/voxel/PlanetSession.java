package dev.moui.galaxycraft.voxel;

import dev.moui.galaxycraft.proto.Layout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.joml.Vector3d;

/**
 * The voxel planet as the mod plays it: where it is in the galaxy, what to send the host
 * (GXC_MSG_PLANET / CHUNK / PLANET_TP, in order) and the player's edits. Galaxy units outside,
 * blocks inside {@link VoxelPlanet}. No Minecraft types, so it is unit tested.
 *
 * Every chunk with something to show is drawn; only those near Mario carry collision (the game's
 * collision zones hold 512 parts, its own stage's included). Chunks are meshed as they are sent,
 * nearest to Mario first, so a big planet arrives over a few seconds without stalling a tick.
 *
 * Two lanes: what Mario stands on goes first (the chunks whose collision changes, edits near him,
 * a teleport's landing), always; the rest of the planet only while {@link #peek(BooleanSupplier)}
 * is allowed to build more (a time budget per tick, room in the ring). A planet streaming in never
 * holds back the ground under Mario.
 */
public final class PlanetSession {
    public record Msg(int type, byte[] payload) {}

    public static final double REACH = 4.5;
    public static final double DEFAULT_MARIO_RADIUS = 0.3;
    /** The outline stands this fraction of a cell out of it. */
    static final double OUTLINE_GROW = 0.02;
    /**
     * Collision reaches this far around Mario and around where he will be in LOOKAHEAD updates at
     * his speed, blocks, for at most MAX_PARTS chunks; a chunk that has it keeps it KEEP blocks
     * farther. Each collision part costs the game every frame, so they stay few: a fall or a run
     * still finds the ground ahead loaded.
     */
    public static final double NEAR = 12, KEEP = 4;
    public static final int LOOKAHEAD = 15;
    public static final int MAX_PARTS = 160;
    /** Updates between recomputing which chunks are near Mario. */
    public static final int RESIDENCY_UPDATES = 2;
    /**
     * Mario's collision radius at the planet's surface, blocks (Steve's): his own is 60 units (1.5
     * blocks wide), too wide for a 1-block hole or tunnel. Below the surface it shrinks as the
     * cells narrow toward the center (the module scales it). -Dgalaxycraft.marioRadius changes it
     * (0: his own).
     */
    public static final double MARIO_RADIUS = marioRadius(System.getProperty("galaxycraft.marioRadius"));
    /** In the chunk queue: the teleport, once the chunks before it are out. */
    private static final int TP_MARK = -1;
    /**
     * A planet's id in the game (GxcPlanet.planet_id): 1..255, as a chunk's slot word carries it in
     * its top byte; each live session holds one, and a freed one is not given out again right away
     * (the game may still hold chunks of it).
     */
    private static final BitSet usedIds = new BitSet();
    private static int lastId;
    /** The slot word of a part of the far view (its face below). */
    static final int FAR_VIEW = 0x800000;
    static final int PLANET_GONE = 1;
    /** Updates between sending again the faces of the far view that edits changed. */
    static final int FAR_UPDATES = 40;

    private final double unitsPerBlock;
    private Blocks blocks = CubeBlocks.INSTANCE;
    private final Deque<Msg> control = new ArrayDeque<>();
    private final Deque<Integer> pending = new ArrayDeque<>();
    /** Chunks waiting in pending or urgent (an entry in pending without it was sent since: skipped). */
    private final BitSet pendingSet = new BitSet();
    /** Chunks sent before any in pending, whatever the budget: Mario's collision (and TP_MARK). */
    private final Deque<Integer> urgent = new ArrayDeque<>();
    private final BitSet urgentSet = new BitSet();
    private BitSet onGuest = new BitSet(); // chunks the game has something of
    private BitSet near = new BitSet();    // chunks with collision (wanted)
    private BitSet withKcl = new BitSet(); // chunks sent with collision
    private Msg built;
    private boolean builtFar; // built is a part of the far view (farPending's first)
    private int builtChunk = -1;
    private boolean builtStale; // edited again while its message waited for room
    private boolean builtUrgent; // and wanted in the urgent lane
    private VoxelPlanet planet;
    private Vector3d center;
    private float tpGround; // galaxy units from the center: where the next teleport lands
    private int id;
    /** Ids of planets this session had that the game is to drop (its new scene may not have them). */
    private final Deque<Integer> gone = new ArrayDeque<>();
    /**
     * Chunks go to the game only with detail: without it the planet is only its far view (a planet
     * far from Mario costs no memory for chunks nor time to mesh them).
     */
    private boolean detail = true;
    private final Deque<Integer> farPending = new ArrayDeque<>();
    private final int[] farVersion = new int[6];
    private final BitSet farDirty = new BitSet();
    private int sinceFar;
    private int scene = Integer.MIN_VALUE, host = Integer.MIN_VALUE;
    private int sinceResidency;
    private boolean unsaved;
    private int outline = -1; // the cell outlined in the game, -1 none
    private int outlineId = -1; // the block that was in it then: a door opening changes its outline
    private Vector3d mario;
    /** Where Mario is headed: his position LOOKAHEAD updates on at his last step's speed. */
    private Vector3d ahead;
    /**
     * Where a teleport lands him, until he is there (the game moves him frames later): collision
     * stays there meanwhile, or he would land where it had just been taken away.
     */
    private Vector3d landing;
    private int landingUpdates;
    static final int LANDING_UPDATES = 200;
    /**
     * Far chunks leave out the faces only a dark cave shows (PlanetMesher.Dark), hidden from a
     * viewer outside by the ground over them. With Mario under cover (something solid over his
     * head) they would show as holes in the cave around him: the chunks that left some out are
     * sent whole again, nearest first, and stay whole until he has been out OUTSIDE_UPDATES.
     */
    private boolean underground;
    private int outsideUpdates;
    static final int OUTSIDE_UPDATES = 100;
    private BitSet darkCut = new BitSet(); // chunks the game has without their dark faces
    private BitSet hasDark = new BitSet(); // chunks that had dark faces when last meshed without them

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
        spawn(VoxelPlanet.ofRadius(radius, blocks), feetGal, upGal);
    }

    /** That planet above the player, just out of its gravity's reach. */
    public void spawn(VoxelPlanet p, Vector3d feetGal, Vector3d upGal) {
        double above = (gravityRadius(p.surface()) + 24) * unitsPerBlock;
        start(p, new Vector3d(upGal).normalize().mul(above).add(feetGal));
        unsaved = true;
    }

    /** That planet with its center there (galaxy units): PlanetLayout says where. */
    public void spawnAt(VoxelPlanet p, Vector3d centerGal) {
        start(p, new Vector3d(centerGal));
        unsaved = true;
    }

    /** How far its gravity reaches from its center, galaxy units. */
    public double gravityUnits() {
        return planet == null ? 0 : gravityRadius(planet.surface()) * unitsPerBlock;
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
        release();
    }

    /** Forgets the planet without telling the game (it is gone with its scene). */
    public void unload() {
        planet = null;
        clearQueues();
        release();
        gone.clear(); // the new scene's game never had it
    }

    /**
     * Mario onto the surface, where the game puts him: on the line from the center through him.
     * The collision there goes first, then the teleport, so he never lands where nothing is solid.
     */
    /** Radius of the top of the highest block in the column through p (blocks); the surface if it is all air. */
    double ground(Vector3d p) {
        CubeSphere g = planet.grid;
        int c0 = g.cellAt(new Vector3d(p).normalize(g.core + 0.5));
        if (c0 >= 0)
            for (int k = g.layers - 1; k >= 0; k--)
                if (planet.get(c0 + k) != Blocks.AIR) return g.radius(k + 1);
        return planet.surface();
    }

    public void teleport() {
        if (planet == null) return;
        if (!detail) {
            detail = true;
            sendAll();
        }
        Vector3d land = mario == null || mario.lengthSquared() < 1 ? new Vector3d(0, 1, 0) : new Vector3d(mario);
        tpGround = (float) (ground(land) * unitsPerBlock);
        land.normalize(Math.max(planet.surface(), tpGround / unitsPerBlock));
        landing = land;
        landingUpdates = 0;
        for (int c : residency(land, land)) queueUrgent(c);
        urgent.add(TP_MARK);
    }

    /**
     * Once per tick, with Mario's position (galaxy units, null if unknown): a new scene or host lost
     * everything, so it is all sent again; then the edits and the chunks whose collision changed.
     */
    public void update(int sceneId, int hostPid, Vector3d marioGal) {
        if (planet == null) return;
        Vector3d was = mario;
        mario = marioGal == null ? null : local(marioGal);
        // A jump of more than a few blocks in one update is a teleport, not a speed.
        ahead = mario == null || was == null || was.distance(mario) > 8 ? mario
                : new Vector3d(mario).sub(was).mul(LOOKAHEAD).add(mario);
        if (sceneId != scene || hostPid != host) {
            scene = sceneId;
            host = hostPid;
            sendAll();
        }
        if (planet.fluids().tick()) unsaved = true;
        for (int c : planet.takeDirty()) {
            if (detail && near.get(c)) queueUrgent(c); // an edit under Mario: its collision now
            else if (detail) queue(c);
            farDirty.set(planet.faceOfChunk(c));
        }
        if (++sinceFar >= FAR_UPDATES && !farDirty.isEmpty()) {
            sinceFar = 0;
            farDirty.stream().filter(f -> !farPending.contains(f)).forEach(farPending::add);
            farDirty.clear();
        }
        if (mario != null) underground(PlanetMesher.covered(planet, planet.grid.cellAt(new Vector3d(mario).normalize(mario.length() + 1.5))));
        if (landing != null && (mario != null && mario.distance(landing) < NEAR || ++landingUpdates > LANDING_UPDATES)) landing = null;
        if (detail && ++sinceResidency >= RESIDENCY_UPDATES && mario != null) {
            sinceResidency = 0;
            for (int c : landing != null ? residency(landing, landing) : residency(mario, ahead))
                if (near.get(c)) queueUrgent(c);
                else queue(c); // only loses its collision: no hurry
        }
    }

    /** Next message to send, or null; {@link #sent()} once the ring took it. No budget: tests. */
    public Msg peek() {
        return peek(() -> true);
    }

    /**
     * Next message to send, or null; {@link #sent()} once the ring took it. Control messages and
     * the urgent lane (Mario's collision, a teleport) are built whatever happens; the far view and
     * the rest of the chunks only while bulk says there is time and room for more.
     */
    public Msg peek(BooleanSupplier bulk) {
        if (!gone.isEmpty()) return new Msg(Layout.MSG_PLANET, planetPayload(gone.peek(), PLANET_GONE));
        if (!control.isEmpty()) return control.peek();
        while (built == null && !urgent.isEmpty()) {
            int c = urgent.poll();
            if (c == TP_MARK) {
                built = new Msg(Layout.MSG_PLANET_TP, ByteBuffer.allocate(8).putFloat(tpGround).putInt(id).array()); // big-endian: passed on as is
                break;
            }
            urgentSet.clear(c);
            pendingSet.clear(c);
            build(c);
        }
        if (built == null && !farPending.isEmpty() && planet != null && bulk.getAsBoolean()) {
            int f = farPending.peek();
            PlanetLod.Part part = PlanetLod.face(planet, f, unitsPerBlock);
            ByteBuffer b = ByteBuffer.allocate(32 + part.displayList().length).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(id << 24 | FAR_VIEW | f).putInt(++farVersion[f]).putInt(part.displayList().length).putInt(0);
            for (float x : part.sphere()) b.putFloat(x);
            b.put(part.displayList());
            built = new Msg(Layout.MSG_CHUNK, b.array());
            builtFar = true;
        }
        while (built == null && !pending.isEmpty() && bulk.getAsBoolean()) {
            int c = pending.poll();
            if (!pendingSet.get(c)) continue; // went in the urgent lane since
            pendingSet.clear(c);
            build(c);
        }
        return built;
    }

    /** Meshes a chunk into built, unless it has nothing to send. */
    private void build(int c) {
        // A chunk that just got something to show (dug into) may be under Mario already:
        // whether it is near is decided now, not at the next residency pass.
        if (!near.get(c) && mario != null && near.cardinality() < MAX_PARTS && distance(c, mario, ahead) < NEAR)
            near.set(c);
        boolean kcl = near.get(c);
        if (!onGuest.get(c) && !planet.mayShow(c)) return;
        boolean cullDark = !kcl && !underground;
        PlanetMesher.ChunkMesh m = PlanetMesher.mesh(planet, c, unitsPerBlock, kcl, cullDark);
        darkCut.set(c, m.darkCut());
        if (cullDark) hasDark.set(c, m.darkCut());
        if (m.empty() && !onGuest.get(c)) return;
        ByteBuffer b = ByteBuffer.allocate(32 + m.displayList().length + m.kcl().length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(id << 24 | c).putInt(planet.bump(c)).putInt(m.displayList().length).putInt(m.kcl().length);
        for (float f : m.sphere()) b.putFloat(f);
        b.put(m.displayList()).put(m.kcl());
        built = new Msg(Layout.MSG_CHUNK, b.array());
        builtChunk = c;
        onGuest.set(c, !m.empty());
        withKcl.set(c, kcl && !m.empty());
    }

    public void sent() {
        if (!gone.isEmpty()) {
            gone.poll();
            return;
        }
        // In peek's order: control messages go ahead of what was built.
        if (!control.isEmpty()) {
            control.poll();
            return;
        }
        if (builtFar) {
            builtFar = false;
            built = null;
            farPending.poll();
            return;
        }
        int c = builtChunk;
        built = null;
        builtChunk = -1;
        if (builtStale) {
            builtStale = false;
            if (builtUrgent) queueUrgent(c);
            else queue(c);
        }
        builtUrgent = false;
    }

    /** Messages and chunks still to send (chunks may turn out to have nothing to send). */
    public int queued() {
        return gone.size() + control.size() + farPending.size() + urgent.size() + pending.size() + (built != null && !builtFar ? 1 : 0);
    }

    /** Whether Mario is under cover, and far chunks are sent with their dark cave faces. */
    public boolean underground() {
        return underground;
    }

    /** Whether the game has a chunk without its dark cave faces. */
    public boolean darkCut(int chunk) {
        return darkCut.get(chunk);
    }

    private void underground(boolean covered) {
        if (covered) {
            outsideUpdates = 0;
            if (underground) return;
            underground = true;
            queueFirst(darkCut);
        } else if (underground && ++outsideUpdates > OUTSIDE_UPDATES) {
            underground = false;
            BitSet whole = (BitSet) hasDark.clone();
            whole.andNot(darkCut);
            whole.andNot(near);
            whole.and(onGuest);
            whole.stream().forEach(this::queue);
        }
    }

    /** These chunks before any other waiting, nearest Mario first. */
    private void queueFirst(BitSet chunks) {
        List<double[]> order = new ArrayList<>();
        chunks.stream().forEach(ch -> order.add(new double[] {mario == null ? 0 : distance(ch, mario), ch}));
        order.sort((a, b) -> Double.compare(b[0], a[0]));
        for (double[] o : order) {
            int ch = (int) o[1];
            if (ch == builtChunk) {
                builtStale = true;
                continue;
            }
            if (pendingSet.get(ch)) pending.removeFirstOccurrence(ch);
            pendingSet.set(ch);
            pending.addFirst(ch);
        }
    }

    /** Whether a chunk carries collision now. */
    public boolean collides(int chunk) {
        return withKcl.get(chunk);
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
        int face = h.face();
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

    /** What the eye points at: the cell, the side of it facing the eye and the point, in its model space. */
    public record Aim(int cell, int face, Vector3d hit) {}

    /** Null if no block in reach. */
    public Aim aim(Vector3d eyeGal, Vector3d lookGal) {
        PlanetRaycast.Hit h = cast(eyeGal, lookGal);
        if (h == null) return null;
        Vector3d hit = CellSpace.local(planet.grid, h.hit(), h.point());
        hit.set(clamp01(hit.x), clamp01(hit.y), clamp01(hit.z));
        return new Aim(h.hit(), h.face(), hit);
    }

    /** A change Minecraft made to the planet (redstone, a door opened): kept, and saved. */
    public void applyExternal(int cell, int id) {
        if (planet == null) return;
        planet.setQuietly(cell, id);
        unsaved = true;
    }

    /** A point of the galaxy in the planet's space (blocks from its center). */
    public Vector3d localOf(Vector3d gal) {
        return local(gal);
    }

    /** A point of the planet's space in the galaxy. */
    public Vector3d galOf(Vector3d local) {
        return new Vector3d(local).mul(unitsPerBlock).add(center);
    }

    /** The cell a point of the galaxy is in; -1 outside the planet. */
    public int cellAt(Vector3d gal) {
        return planet == null ? -1 : planet.grid.cellAt(local(gal));
    }

    /** Chunks of the planet within range blocks of a point in the galaxy. */
    public List<Integer> chunksNear(Vector3d gal, double range) {
        List<Integer> out = new ArrayList<>();
        if (planet == null) return out;
        Vector3d at = local(gal);
        for (int ch = 0; ch < planet.chunkCount(); ch++)
            if (distance(ch, at) < range) out.add(ch);
        return out;
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

    /** Outlines this cell in the game (Minecraft's block outline), -1 for none. Sent if it or its block changed. */
    public void setOutline(int cell) {
        if (planet == null) return;
        int id = cell < 0 ? -1 : planet.get(cell);
        if (cell == outline && id == outlineId) return;
        outline = cell;
        outlineId = id;
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
        ByteBuffer b = ByteBuffer.allocate(100).order(ByteOrder.LITTLE_ENDIAN).putInt(cell >= 0 ? id : 0);
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
        release();
        planet = p;
        center = c;
        id = newId();
        java.util.Arrays.fill(farVersion, 0);
        scene = host = Integer.MIN_VALUE; // the next update sends it all
    }

    /** The game drops this session's planet (if it has one) and its id is free again. */
    private void release() {
        if (id == 0) return;
        gone.add(id);
        synchronized (usedIds) {
            usedIds.clear(id);
        }
        id = 0;
    }

    private static int newId() {
        synchronized (usedIds) {
            for (int k = 1; k <= 255; k++) {
                int i = (lastId + k - 1) % 255 + 1;
                if (!usedIds.get(i)) {
                    usedIds.set(i);
                    lastId = i;
                    return i;
                }
            }
        }
        throw new IllegalStateException("no planet ids left");
    }

    /** This planet's id in the game (tests). */
    public int id() {
        return id;
    }

    /** Whether the game gets its chunks (else only its far view). */
    public boolean detail() {
        return detail;
    }

    /** Chunks to the game, or only the far view (the chunks it had are dropped). */
    public void setDetail(boolean on) {
        if (on == detail) return;
        detail = on;
        scene = host = Integer.MIN_VALUE; // the next update sends it all again, so
    }

    private void clearQueues() {
        control.clear();
        farPending.clear();
        farDirty.clear();
        builtFar = false;
        outline = outlineId = -1;
        pending.clear();
        pendingSet.clear();
        urgent.clear();
        urgentSet.clear();
        built = null;
        builtChunk = -1;
        builtStale = false;
        builtUrgent = false;
        onGuest = new BitSet();
        withKcl = new BitSet();
        near = new BitSet();
        darkCut = new BitSet();
        hasDark = new BitSet();
    }

    /** The game has nothing: the planet, then every chunk that may show, nearest Mario first. */
    private void sendAll() {
        clearQueues();
        control.add(new Msg(Layout.MSG_PLANET, planetPayload(id, 0)));
        for (int f = 0; f < 6; f++) farPending.add(f);
        planet.takeDirty();
        if (!detail) return;
        if (mario != null) residency(mario, ahead);
        List<double[]> order = new ArrayList<>();
        Vector3d c = new Vector3d();
        double[] r = new double[1];
        for (int ch = 0; ch < planet.chunkCount(); ch++) {
            planet.sphere(ch, c, r);
            order.add(new double[] {mario == null ? 0 : c.distance(mario) - r[0], ch});
        }
        order.sort((a, b) -> Double.compare(a[0], b[0]));
        for (double[] o : order)
            if (near.get((int) o[1])) queueUrgent((int) o[1]); // what Mario stands on, before the rest
            else queue((int) o[1]);
    }

    /**
     * Collision goes to the nearest chunks within NEAR blocks of the way from at to to (planet
     * blocks; KEEP more for those that have it). Returns the chunks to send for it, nearest first:
     * those the game has whose collision changes, and those near that it does not have yet.
     */
    private List<Integer> residency(Vector3d at, Vector3d to) {
        List<double[]> close = new ArrayList<>();
        for (int ch = 0; ch < planet.chunkCount(); ch++) {
            double d = distance(ch, at, to);
            if (d < NEAR || near.get(ch) && d < NEAR + KEEP) close.add(new double[] {d, ch});
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
        return distance(chunk, at, at);
    }

    /** From a chunk's bounding sphere to the segment from a to b, blocks (0 or less: it touches). */
    private double distance(int chunk, Vector3d a, Vector3d b) {
        // Every chunk of a big planet goes through here each residency pass: no allocations.
        Vector3d c = scratchCenter;
        planet.sphere(chunk, c, scratchRadius);
        double abx = b.x - a.x, aby = b.y - a.y, abz = b.z - a.z;
        double len2 = abx * abx + aby * aby + abz * abz;
        double t = len2 < 1e-9 ? 0 : Math.clamp(((c.x - a.x) * abx + (c.y - a.y) * aby + (c.z - a.z) * abz) / len2, 0, 1);
        double dx = c.x - (a.x + t * abx), dy = c.y - (a.y + t * aby), dz = c.z - (a.z + t * abz);
        return Math.sqrt(dx * dx + dy * dy + dz * dz) - scratchRadius[0];
    }

    private final Vector3d scratchCenter = new Vector3d();
    private final double[] scratchRadius = new double[1];

    /** A chunk in the urgent lane: sent before anything else waiting, whatever the budget. */
    private void queueUrgent(int chunk) {
        if (chunk == builtChunk) {
            builtStale = true;
            builtUrgent = true;
            return;
        }
        if (urgentSet.get(chunk)) return;
        urgentSet.set(chunk);
        pendingSet.set(chunk);
        urgent.add(chunk);
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

    /** GxcPlanet (36 bytes the host swaps), then flags big-endian (passed on as is). */
    private byte[] planetPayload(int planetId, int flags) {
        ByteBuffer b = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN).putInt(planetId);
        Vector3d c = center == null ? new Vector3d() : center;
        b.putFloat((float) c.x).putFloat((float) c.y).putFloat((float) c.z);
        double surface = planet == null ? 0 : planet.surface();
        b.putFloat((float) (surface * unitsPerBlock)).putFloat((float) (gravityRadius(surface) * unitsPerBlock));
        b.putInt(planet == null || !detail || flags != 0 ? 0 : planet.chunkCount());
        b.putFloat((float) (planet == null ? 0 : planet.occluder() * unitsPerBlock));
        b.putFloat((float) (MARIO_RADIUS * unitsPerBlock));
        b.order(ByteOrder.BIG_ENDIAN).putInt(flags);
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
