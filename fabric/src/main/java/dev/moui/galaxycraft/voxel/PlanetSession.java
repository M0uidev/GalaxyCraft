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
 * Render distance: only the tiles of the planet (PlanetLod: 4 × 4 chunk columns) within
 * RENDER blocks of Mario go to the game as chunks; the rest of it is drawn as those tiles' far
 * view, a few quads each. A big planet then costs the game, and the mod's meshing, about what its
 * ground around Mario does, not all of it.
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
    /** The cracks stand this fraction of a cell out of it: over the block's faces, under the outline. */
    static final double CRACK_GROW = 0.005;
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
    /** A teleport queued and not sent yet: a resend of everything (a new scene, the first update) keeps it. */
    private boolean tpQueued;
    /**
     * A planet's id in the game (GxcPlanet.planet_id): 1..255, as a chunk's slot word carries it in
     * its top byte; each live session holds one, and a freed one is not given out again right away
     * (the game may still hold chunks of it).
     */
    private static final BitSet usedIds = new BitSet();
    private static int lastId;
    /** The slot word of a part of the far view (its tile below, PlanetLod.tile). */
    static final int FAR_VIEW = 0x800000;
    /**
     * And of one whose tile the game has as chunks: it keeps the part only for a camera far from
     * the planet (then it draws the far view alone); without a display list, it keeps the one it has.
     */
    static final int FAR_COVERED = 0x400000;
    /** A chunk whose display list ends with a translucent one (water): its offset follows the header. */
    static final int TRANSLUCENT = 0x200000;
    /**
     * Tiles within this many blocks of Mario (or where he is headed) are chunks; the others, their
     * far view. One that is chunks stays so until RENDER_KEEP blocks farther. -Dgalaxycraft.renderDistance
     * changes it; 0 sends every chunk, as before render distances.
     */
    public static final double RENDER = renderDistance(System.getProperty("galaxycraft.renderDistance"));
    public static final double DEFAULT_RENDER = 64, RENDER_KEEP = 16;
    /**
     * Levels of the far view, as Minecraft's LOD mods have them: the tiles next to the chunks in
     * patches of FINEST_COLUMNS columns, so the blocks coming in are close to what was drawn, and
     * twice as wide every farStep blocks farther, up to PlanetLod's coarse patches.
     */
    public static final int FINEST_COLUMNS = 2;
    public static final double DEFAULT_FAR_STEP = 32;
    /** In pending: -2 - tile, its far view goes once the chunks queued before it are out. */
    private static final int HIDE_MARK = -2;
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
    private Vector3d tpDir = new Vector3d(0, 1, 0); // and the direction from the center it lands along
    private int id;
    /** Ids of planets this session had that the game is to drop (its new scene may not have them). */
    private final Deque<Integer> gone = new ArrayDeque<>();
    /**
     * Chunks go to the game only with detail: without it the planet is only its far view (a planet
     * far from Mario costs no memory for chunks nor time to mesh them).
     */
    private boolean detail = true;
    private final Deque<Integer> farPending = new ArrayDeque<>(); // tiles whose far view is to send (or hide)
    private final BitSet farPendingSet = new BitSet();
    private int[] farVersion = new int[0];
    private final BitSet farDirty = new BitSet(); // tiles edited since their far view was sent
    /** The player's settings, which every session follows (setRenderDistance pins one's own: tests). */
    private static volatile double settingRender = RENDER, settingFarStep = DEFAULT_FAR_STEP;
    private double render = RENDER;
    private boolean renderPinned;
    private double farStep = DEFAULT_FAR_STEP;
    private int[] farWant = new int[0]; // per tile, the patch columns its far view should have
    private int[] farCols = new int[0]; // and those the game has
    private int builtCols;
    /**
     * Chunks meshed ahead on MESHERS, a batch at a time while the tick waits (nothing edits the
     * planet meanwhile); each is taken only as build would make it now (same collision and caves,
     * its chunk unchanged since), else made again.
     */
    private static final java.util.concurrent.ForkJoinPool MESHERS = new java.util.concurrent.ForkJoinPool(
            Math.max(1, Runtime.getRuntime().availableProcessors() - 2), pool -> {
                var w = java.util.concurrent.ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
                w.setName("GalaxyCraft mesher " + w.getPoolIndex());
                w.setDaemon(true);
                w.setPriority(Thread.NORM_PRIORITY - 1);
                return w;
            }, null, false);
    static final int PREFETCH = 2 * Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
    private record Premeshed(boolean kcl, boolean cullDark, PlanetMesher.ChunkMesh mesh) {}
    private final java.util.Map<Integer, Premeshed> premeshed = new java.util.HashMap<>();
    private boolean parallel = true;
    private final java.util.LinkedHashSet<Integer> farLater = new java.util.LinkedHashSet<>(); // a level finer or coarser, once the chunks are out
    private BitSet shown = new BitSet();      // tiles the game gets as chunks (the rest: their far view)
    private BitSet farOnGuest = new BitSet(); // tiles whose far view the game may draw near (not covered)
    private BitSet farHeld = new BitSet();    // tiles whose far view the game has, covered or not
    private float[] tileSpheres;              // per tile: center (blocks), radius
    private boolean builtFarMark; // built hides a tile's far view (a mark in pending): builtTile's
    private int builtTile;
    private boolean builtFarShows; // the far view built (farPending's first) shows its tile, not covered
    private BitSet keptOnGuest = new BitSet(), keptHeld = new BitSet(), keptStale = new BitSet(); // sendAll's, across clearQueues
    private boolean guestHasIt; // the next sendAll is to a game that has this planet already
    private int sinceFar;
    private int scene = Integer.MIN_VALUE, host = Integer.MIN_VALUE;
    private int sinceResidency;
    private boolean unsaved;
    private int outline = -1; // the cell outlined in the game, -1 none
    private int outlineId = -1; // the block that was in it then: a door opening changes its outline
    private int crack = -1, crackStage = -1; // the cell cracked in the game and how far, -1 none
    private int crackId = -1;
    private int placed = -1;
    private Vector3d mario;
    /** Where Mario is headed: his position LOOKAHEAD updates on at his last step's speed. */
    private Vector3d ahead;
    /**
     * Where a teleport lands him, until he is there (the game moves him frames later): collision
     * stays there meanwhile, or he would land where it had just been taken away.
     */
    private Vector3d landing;
    private int landingUpdates;
    private int watchUpdates = -1; // updates since the last teleport, -1 once checked
    static final int WATCH_UPDATES = 300;
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
        p.setBiomes(s.biomes());
        boolean sealed = p.sealBelowCrust() > 0;
        start(p, s.center());
        unsaved = sealed;
    }

    public PlanetStore.Saved save() {
        unsaved = false;
        CubeSphere g = planet.grid;
        return new PlanetStore.Saved(g.n, g.core, g.layers, planet.depth, new Vector3d(center), planet.cells().clone(),
                planet.biomes());
    }

    /** Nothing to save: a planet made again the same from its recipe until it is edited. */
    public void clean() {
        unsaved = false;
    }

    /** Edited (or new) since the last {@link #save()}. */
    public boolean unsaved() {
        return planet != null && unsaved;
    }

    /** Forgets the planet here; the game drops it too. */
    public void remove() {
        if (planet == null) return;
        planet = null;
        tpQueued = false;
        clearQueues();
        release();
    }

    /** Forgets the planet without telling the game (it is gone with its scene). */
    public void unload() {
        planet = null;
        tpQueued = false;
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

    /** Mario onto the ground under him (once its collision is in); where he lands, planet blocks (null: no planet). */
    public Vector3d teleport() {
        if (planet == null) return null;
        if (!detail) {
            detail = true;
            guestHasIt = true;
            sendAll();
        }
        return teleportToward(mario == null || mario.lengthSquared() < 1 ? new Vector3d(0, 1, 0) : mario);
    }

    /** Lands Mario on the ground straight out from the planet's center along toward (planet space); where, planet blocks. */
    public Vector3d teleportToward(Vector3d toward) {
        if (planet == null) return null;
        if (!detail) {
            detail = true;
            guestHasIt = true;
            sendAll();
        }
        Vector3d land = new Vector3d(toward);
        tpDir = new Vector3d(toward).normalize(); // planet space has the galaxy's axes
        tpGround = (float) (ground(land) * unitsPerBlock);
        land.normalize(Math.max(planet.surface(), tpGround / unitsPerBlock));
        landing = land;
        landingUpdates = 0;
        watchUpdates = 0;
        dev.moui.galaxycraft.GalaxyCraft.LOG.info("Teleport onto planet {}: Mario {} blocks from the center, lands at {} (ground {}, surface {})",
                id, mario == null ? "?" : String.format("%.1f", mario.length()), String.format("%.1f", land.length()),
                String.format("%.1f", tpGround / unitsPerBlock), String.format("%.1f", planet.surface()));
        for (int c : residency(land, land)) queueUrgent(c);
        urgent.add(TP_MARK);
        tpQueued = true;
        return new Vector3d(land);
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
            if (sceneId != scene && scene != Integer.MIN_VALUE || hostPid != host && host != Integer.MIN_VALUE) guestHasIt = false;
            scene = sceneId;
            host = hostPid;
            sendAll();
        }
        if (planet.fluids().tick()) unsaved = true;
        for (int c : planet.takeDirty()) {
            premeshed.remove(c); // meshed before the edit
            int t = PlanetLod.tileOfChunk(planet, c);
            if (detail && near.get(c)) queueUrgent(c); // an edit under Mario: its collision now
            else if (detail && shown.get(t)) queue(c);
            farDirty.set(t); // also a tile of chunks: seen from afar, the game draws its far view
        }
        if (++sinceFar >= FAR_UPDATES && !farDirty.isEmpty()) {
            sinceFar = 0;
            farDirty.stream().forEach(this::queueFar);
            farDirty.clear();
        }
        if (mario != null) underground(PlanetMesher.covered(planet, planet.grid.cellAt(new Vector3d(mario).normalize(mario.length() + 1.5))));
        if (landing != null && (mario != null && mario.distance(landing) < NEAR || ++landingUpdates > LANDING_UPDATES)) landing = null;
        // A while after a teleport, says if Mario ended under the ground (users see him inside the planet).
        if (watchUpdates >= 0 && ++watchUpdates >= WATCH_UPDATES) {
            watchUpdates = -1;
            double under = mario == null ? 0 : ground(mario) - mario.length();
            if (under > 2)
                dev.moui.galaxycraft.GalaxyCraft.LOG.warn("Mario is {} blocks under the ground of planet {} after a teleport ({} blocks from the center, {} chunks to send)",
                        String.format("%.1f", under), id, String.format("%.1f", mario.length()), queued());
        }
        if (detail && ++sinceResidency >= RESIDENCY_UPDATES && mario != null) {
            sinceResidency = 0;
            if (!renderPinned) render = settingRender;
            farStep = settingFarStep;
            Vector3d from = landing != null ? landing : mario, to = landing != null ? landing : ahead;
            // Urgent both ways, those losing their collision first: the game's collision zone holds
            // 512 parts, and one more breaks it (SMG2 then hangs searching all of memory).
            for (int c : residency(from, to)) queueUrgent(c);
            tiles(from, to, true);
            nearestFirst(from, to);
        }
    }

    /**
     * The chunks still to send in the order Mario will reach them (their tile's distance from his
     * way), each tile's covering right after its chunks: flying fast, what he is about to reach
     * comes before what was queued earlier and is behind him now.
     */
    private void nearestFirst(Vector3d a, Vector3d b) {
        if (pending.size() < 2) return;
        java.util.Map<Integer, Double> tileKey = new java.util.HashMap<>();
        List<double[]> order = new ArrayList<>(pending.size());
        int i = 0;
        for (int c : pending) {
            int t = c <= HIDE_MARK ? HIDE_MARK - c : PlanetLod.tileOfChunk(planet, c);
            double d = tileKey.computeIfAbsent(t, k -> tileDistance(k, a, b));
            order.add(new double[] {d + (c <= HIDE_MARK ? 0.5 : 0), i++, c}); // the covering after its tile's chunks
        }
        order.sort((x, y) -> x[0] != y[0] ? Double.compare(x[0], y[0]) : Double.compare(x[1], y[1]));
        pending.clear();
        for (double[] o : order) pending.add((int) o[2]);
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
                tpQueued = false;
                built = new Msg(Layout.MSG_PLANET_TP, ByteBuffer.allocate(20).putFloat(tpGround).putInt(id) // big-endian: passed on as is
                        .putFloat((float) tpDir.x).putFloat((float) tpDir.y).putFloat((float) tpDir.z).array());
                break;
            }
            if (parallel && c >= 0 && !premeshed.containsKey(c)) premesh(urgent, urgentSet, c);
            urgentSet.clear(c);
            pendingSet.clear(c);
            build(c);
        }
        while (built == null && !farPending.isEmpty() && planet != null && bulk.getAsBoolean()) {
            int t = farPending.peek();
            // A tile of chunks gets it too, covered: the game draws it only from afar.
            builtFarShows = !shown.get(t);
            // A tile of chunks is drawn only from afar: coarse. Else as fine as its level.
            builtCols = builtFarShows && t < farWant.length && farWant[t] > 0 ? farWant[t] : PlanetLod.tilePatchColumns(planet.grid.n);
            built = farMsg(t, PlanetLod.tile(planet, t, builtCols, unitsPerBlock), !builtFarShows);
            builtFar = true;
        }
        while (built == null && !pending.isEmpty() && bulk.getAsBoolean()) {
            if (parallel && pending.peek() >= 0 && !premeshed.containsKey(pending.peek())) premesh(pending, pendingSet, -1);
            int c = pending.poll();
            if (c <= HIDE_MARK) { // a tile's chunks are out: its far view goes
                int t = HIDE_MARK - c;
                if (shown.get(t) && farOnGuest.get(t)) {
                    built = farMsg(t, null, true);
                    builtFarMark = true;
                    builtTile = t;
                }
                continue;
            }
            if (!pendingSet.get(c)) continue; // went in the urgent lane since
            pendingSet.clear(c);
            build(c);
        }
        if (built == null && pending.isEmpty() && farPending.isEmpty() && !farLater.isEmpty() && bulk.getAsBoolean()) {
            for (int t : farLater) queueFar(t);
            farLater.clear();
            return peek(bulk);
        }
        return built;
    }

    /**
     * The next PREFETCH chunks of a queue (pending, or the urgent lane from first) that build would mesh, meshed side by side, with what
     * build would decide for them as things stand (it checks again).
     */
    private void premesh(java.util.Collection<Integer> queue, BitSet queued, int first) {
        List<int[]> jobs = new ArrayList<>(); // chunk, kcl, cullDark
        java.util.Iterator<Integer> it = first >= 0 ? java.util.stream.Stream.concat(java.util.stream.Stream.of(first), queue.stream()).iterator()
                : queue.iterator();
        while (it.hasNext()) {
            int c = it.next();
            if (jobs.size() >= PREFETCH) break;
            if (c < 0 || c != first && !queued.get(c) || planet.isDirty(c) || premeshed.containsKey(c)) continue;
            boolean kcl = (near.get(c) || mario != null && near.cardinality() < MAX_PARTS && distance(c, mario, ahead) < NEAR)
                    && (withKcl.get(c) || withKcl.cardinality() < MAX_PARTS);
            int tile = PlanetLod.tileOfChunk(planet, c);
            boolean keepDrawn = !kcl && withKcl.get(c) && onGuest.get(c) && !farOnGuest.get(tile); // as build
            if (!kcl && !shown.get(tile) && !keepDrawn) continue; // dropped, not meshed
            if (!onGuest.get(c) && !planet.mayShow(c)) continue;
            jobs.add(new int[] {c, kcl ? 1 : 0, !kcl && !underground ? 1 : 0});
        }
        if (jobs.size() < 2) return;
        if (premeshed.size() > 4 * PREFETCH) premeshed.clear(); // chunks that left the queue unbuilt
        VoxelPlanet p = planet;
        double units = unitsPerBlock;
        List<PlanetMesher.ChunkMesh> meshes = MESHERS.submit(() -> jobs.parallelStream()
                .map(j -> PlanetMesher.mesh(p, j[0], units, j[1] == 1, j[2] == 1)).toList()).join();
        for (int i = 0; i < jobs.size(); i++)
            premeshed.put(jobs.get(i)[0], new Premeshed(jobs.get(i)[1] == 1, jobs.get(i)[2] == 1, meshes.get(i)));
    }

    /** Meshing side by side (true by default), or all on the caller's thread (tests compare them). */
    void setParallelMeshing(boolean on) {
        parallel = on;
        premeshed.clear();
    }

    /** Meshes a chunk into built, unless it has nothing to send. */
    private void build(int c) {
        // A chunk that just got something to show (dug into) may be under Mario already:
        // whether it is near is decided now, not at the next residency pass.
        if (!near.get(c) && mario != null && near.cardinality() < MAX_PARTS && distance(c, mario, ahead) < NEAR)
            near.set(c);
        // Never more than MAX_PARTS in the game, whatever is still to go (a full zone breaks SMG2).
        boolean kcl = near.get(c) && (withKcl.get(c) || withKcl.cardinality() < MAX_PARTS);
        int tile = PlanetLod.tileOfChunk(planet, c);
        // Losing its collision before its tile's far view is back: it only loses that now (still
        // drawn); the tile going out drops it after the far view.
        boolean keepDrawn = !kcl && withKcl.get(c) && onGuest.get(c) && !farOnGuest.get(tile);
        if (!kcl && !shown.get(tile) && !keepDrawn) {
            // Past the render distance: its tile's far view stands in for it, the game drops it.
            if (onGuest.get(c)) {
                ByteBuffer b = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
                b.putInt(id << 24 | c).putInt(planet.bump(c)).putInt(0).putInt(0);
                built = new Msg(Layout.MSG_CHUNK, b.array());
                builtChunk = c;
                onGuest.clear(c);
                withKcl.clear(c);
                darkCut.clear(c);
            }
            return;
        }
        if (!onGuest.get(c) && !planet.mayShow(c)) return;
        boolean cullDark = !kcl && !underground;
        Premeshed pre = premeshed.remove(c);
        PlanetMesher.ChunkMesh m = pre != null && pre.kcl() == kcl && pre.cullDark() == cullDark && !planet.isDirty(c) ? pre.mesh()
                : PlanetMesher.mesh(planet, c, unitsPerBlock, kcl, cullDark);
        darkCut.set(c, m.darkCut());
        if (cullDark) hasDark.set(c, m.darkCut());
        if (m.empty() && !onGuest.get(c)) return;
        boolean split = m.translucentAt() < m.displayList().length;
        ByteBuffer b = ByteBuffer.allocate((split ? 36 : 32) + m.displayList().length + m.kcl().length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(id << 24 | (split ? TRANSLUCENT : 0) | c).putInt(planet.bump(c)).putInt(m.displayList().length).putInt(m.kcl().length);
        for (float f : m.sphere()) b.putFloat(f);
        if (split) b.putInt(Integer.reverseBytes(m.translucentAt())); // after the header the host swaps: big-endian
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
            int t = farPending.poll();
            farPendingSet.clear(t);
            farOnGuest.set(t, builtFarShows); // what it carried, whatever the tile is by now
            farHeld.set(t);
            if (t < farCols.length) farCols[t] = builtCols;
            if (builtFarShows && shown.get(t)) pending.add(HIDE_MARK - t); // chunks there since: hidden after them
            return;
        }
        if (builtFarMark) {
            builtFarMark = false;
            built = null;
            farOnGuest.clear(builtTile);
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
        return gone.size() + control.size() + farPending.size() + farLater.size() + urgent.size() + pending.size() + (built != null && !builtFar ? 1 : 0);
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
        placed = cell;
        return true;
    }

    /** The cell the last block placeBlock placed went into (-1 none yet): its sound and pieces. */
    public int lastPlaced() {
        return placed;
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

    /**
     * The block grid at a point of the galaxy: {an edge along the cell's i, its first corner, its
     * up (unit, out of the planet through the cell's middle), an edge along its j}, galaxy space; null outside the planet.
     */
    public Vector3d[] gridAt(Vector3d gal) {
        int cell = cellAt(gal);
        if (cell < 0) return null;
        Vector3d corner = galOf(planet.grid.corner(cell, 0, 0, 0));
        Vector3d up = galOf(CellSpace.point(planet.grid, cell, 0.5, 1, 0.5))
                .sub(galOf(CellSpace.point(planet.grid, cell, 0.5, 0, 0.5))).normalize();
        return new Vector3d[] {galOf(planet.grid.corner(cell, 1, 0, 0)).sub(corner), corner, up,
                galOf(planet.grid.corner(cell, 0, 1, 0)).sub(corner)};
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
        if (cell >= 0) putCorners(b, cell, OUTLINE_GROW);
        return b.array();
    }

    /** The corners of cell's outline box (its shape's bounds in the cell) from the planet's center, grown by grow. */
    private void putCorners(ByteBuffer b, int cell, double grow) {
        double[] o = planet.info(cell).outline();
        Vector3d mid = CellSpace.point(planet.grid, cell, (o[0] + o[3]) / 2, (o[1] + o[4]) / 2, (o[2] + o[5]) / 2);
        for (int m = 0; m < 8; m++) {
            // Corner (di, dj, dk) is model (x, y, z) = (dj, dk, di) picks of the bounds.
            int di = m & 1, dj = m >> 1 & 1, dk = m >> 2;
            Vector3d c = CellSpace.point(planet.grid, cell, dj == 0 ? o[0] : o[3], dk == 0 ? o[1] : o[4], di == 0 ? o[2] : o[5]);
            c.sub(mid).mul(1 + grow).add(mid).mul(unitsPerBlock);
            b.putFloat((float) c.x).putFloat((float) c.y).putFloat((float) c.z);
        }
    }

    /**
     * Minecraft's cracks over this cell in the game, stage 0..Layout.CRACK_STAGES - 1 (-1, or a
     * cell of -1: none); uv is that stage's tile in the atlas (u0, v0, u1, v1). Sent if the cell,
     * its block or the stage changed.
     */
    public void setCrack(int cell, int stage, float[] uv) {
        if (planet == null) return;
        if (cell < 0 || stage < 0 || uv == null) cell = stage = -1;
        stage = Math.min(stage, Layout.CRACK_STAGES - 1);
        int block = cell < 0 ? -1 : planet.get(cell);
        if (cell == crack && stage == crackStage && block == crackId) return;
        crack = cell;
        crackStage = stage;
        crackId = block;
        control.removeIf(m -> m.type() == Layout.MSG_CRACK);
        control.add(new Msg(Layout.MSG_CRACK, crackPayload(cell, stage, uv)));
    }

    /** GxcCrack: the planet's id (0: none), the stage, its tile, the corners of the cell's outline box. */
    byte[] crackPayload(int cell, int stage, float[] uv) {
        ByteBuffer b = ByteBuffer.allocate(120).order(ByteOrder.LITTLE_ENDIAN).putInt(cell >= 0 ? id : 0)
                .putInt(Math.max(0, stage));
        for (int k = 0; k < 4; k++) b.putFloat(cell >= 0 ? uv[k] : 0);
        if (cell >= 0) putCorners(b, cell, CRACK_GROW);
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
        id = takeId();
        farVersion = new int[PlanetLod.tileCount(p)];
        farWant = new int[farVersion.length];
        farCols = new int[farVersion.length];
        guestHasIt = false;
        tileSpheres = null;
        scene = host = Integer.MIN_VALUE; // the next update sends it all
    }

    /** The game drops this session's planet (if it has one) and its id is free again. */
    private void release() {
        if (id == 0) return;
        gone.add(id);
        freeId(id);
        id = 0;
    }

    /** An id freed: given out again only after every other (the game may still hold its chunks). */
    static void freeId(int id) {
        synchronized (usedIds) {
            usedIds.clear(id);
        }
    }

    /** Whether a session or far planet holds that id (tests). */
    static boolean idUsed(int id) {
        synchronized (usedIds) {
            return usedIds.get(id);
        }
    }

    static int takeId() {
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
        guestHasIt = true; // the same planet in the same scene: the game keeps its far view
        scene = host = Integer.MIN_VALUE; // the next update sends it all again, so
    }

    private void clearQueues() {
        control.clear();
        farPending.clear();
        farPendingSet.clear();
        farLater.clear();
        farDirty.clear();
        builtFar = false;
        builtFarMark = false;
        shown = new BitSet();
        farOnGuest = new BitSet();
        outline = outlineId = -1;
        crack = crackStage = crackId = -1;
        pending.clear();
        pendingSet.clear();
        premeshed.clear();
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
        keptOnGuest = farOnGuest;
        keptHeld = farHeld;
        keptStale = (BitSet) farDirty.clone(); // edited, or still to send: theirs is old
        keptStale.or(farPendingSet);
        clearQueues();
        control.add(new Msg(Layout.MSG_PLANET, planetPayload(id, 0)));
        planet.takeDirty();
        int tileCount = PlanetLod.tileCount(planet);
        tiles(mario, ahead, false);
        if (guestHasIt) {
            // The same planet in the same scene, with or without detail before: the game keeps the
            // far view it has. Only what it lacks goes (and the tiles no longer chunks uncovered),
            // so nearing a planet its chunks are not held up behind hundreds of tiles it has.
            farOnGuest = (BitSet) keptOnGuest.clone();
            farHeld = (BitSet) keptHeld.clone();
            for (int t = 0; t < tileCount; t++)
                if (keptStale.get(t) || !farHeld.get(t) || !shown.get(t) && !farOnGuest.get(t)) queueFar(t);
                else if (!shown.get(t) && farCols[t] != farWant[t]) farLater.add(t);
        } else {
            farHeld = new BitSet();
            farCols = new int[tileCount];
            for (int t = 0; t < tileCount; t++) queueFar(t);
        }
        guestHasIt = false;
        if (tpQueued) { // after the planet, Mario's landing ground, then the teleport, as teleportToward queued them
            if (landing != null) for (int c : residency(landing, landing)) queueUrgent(c);
            urgent.add(TP_MARK);
        }
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
            else if (shown.get(PlanetLod.tileOfChunk(planet, (int) o[1]))) queue((int) o[1]);
        // Their chunks out, the shown tiles' far views are covered (if the game drew them).
        shown.stream().forEach(t -> pending.add(HIDE_MARK - t));
    }

    /**
     * Which tiles are chunks: those within the render distance of the way from a to b (none
     * without detail or Mario). With changes, a tile coming in queues its chunks, then the covering
     * of its far view; one going out, its far view, then the dropping of its chunks.
     */
    private void tiles(Vector3d a, Vector3d b, boolean changes) {
        int count = PlanetLod.tileCount(planet);
        BitSet next = new BitSet();
        List<double[]> in = new ArrayList<>();
        if (detail && a != null) {
            if (render == Double.POSITIVE_INFINITY) next.set(0, count);
            else
                for (int t = 0; t < count; t++) {
                    double d = tileDistance(t, a, b);
                    if (d < render || shown.get(t) && d < render + RENDER_KEEP) {
                        next.set(t);
                        if (!shown.get(t)) in.add(new double[] {d, t});
                    }
                }
        }
        BitSet out = (BitSet) shown.clone();
        out.andNot(next);
        shown = next;
        List<double[]> relevel = levels(a, b);
        if (!changes) return;
        // Far views a level finer or coarser, nearest first, after the chunks coming in.
        relevel.sort((x, y) -> Double.compare(x[0], y[0]));
        for (double[] o : relevel) farLater.add((int) o[1]);
        in.sort((x, y) -> Double.compare(x[0], y[0]));
        for (double[] o : in) {
            int t = (int) o[1];
            for (int c : PlanetLod.chunksOfTile(planet, t)) if (onGuest.get(c) || planet.mayShow(c)) queue(c);
            pending.add(HIDE_MARK - t);
        }
        out.stream().forEach(t -> {
            queueFar(t);
            for (int c : PlanetLod.chunksOfTile(planet, t)) if (onGuest.get(c)) queue(c);
        });
    }

    /**
     * The patch columns each tile's far view should have (farWant), from the way a to b; the tiles
     * the game has at another level, with their distance. A level only gets coarser RENDER_KEEP
     * blocks past where it would, so walking along a boundary does not resend it back and forth.
     */
    private List<double[]> levels(Vector3d a, Vector3d b) {
        int count = PlanetLod.tileCount(planet), coarse = PlanetLod.tilePatchColumns(planet.grid.n);
        List<double[]> out = new ArrayList<>();
        boolean graded = detail && a != null && render != Double.POSITIVE_INFINITY;
        for (int t = 0; t < count; t++) {
            double d = graded ? tileDistance(t, a, b) : Double.POSITIVE_INFINITY;
            int want = columnsAt(d - render, coarse);
            if (farCols[t] != 0 && want > farCols[t] && columnsAt(d - render - RENDER_KEEP, coarse) <= farCols[t]) want = farCols[t];
            farWant[t] = want;
            if (!shown.get(t) && farHeld.get(t) && farCols[t] != want && !farPendingSet.get(t)) out.add(new double[] {d, t});
        }
        return out;
    }

    /** Patch columns for a far view this many blocks past the render distance. */
    private int columnsAt(double past, int coarse) {
        if (past == Double.POSITIVE_INFINITY) return coarse;
        int s = FINEST_COLUMNS;
        for (double edge = farStep; past >= edge && s < coarse; edge += farStep) s *= 2;
        return Math.min(s, coarse);
    }

    /** Blocks between levels of the far view (the next level out has patches twice as wide). */
    public void setFarStep(double blocks) {
        farStep = Math.max(8, blocks);
    }

    /** Whether the game draws a tile's far view up close (not covered by its chunks; tests). */
    boolean farDrawnNear(int t) {
        return farOnGuest.get(t);
    }

    /** Patch columns of the far view the game has for a tile (0: none yet; tests). */
    public int farColumns(int t) {
        return farCols[t];
    }

    /** A tile's far view to send (or hide, if it is chunks by then). */
    private void queueFar(int t) {
        if (farPendingSet.get(t)) return;
        farPendingSet.set(t);
        farPending.add(t);
    }

    /** A far view message for a tile: that part, or none (keep the one it has); covered: its chunks stand there. */
    private Msg farMsg(int t, PlanetLod.Part part, boolean covered) {
        byte[] dl = part == null ? new byte[0] : part.displayList();
        ByteBuffer b = ByteBuffer.allocate(32 + dl.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(id << 24 | FAR_VIEW | (covered ? FAR_COVERED : 0) | t).putInt(++farVersion[t]).putInt(dl.length).putInt(0);
        if (part == null) b.putFloat(0).putFloat(0).putFloat(0).putFloat(0);
        else for (float x : part.sphere()) b.putFloat(x);
        b.put(dl);
        return new Msg(Layout.MSG_CHUNK, b.array());
    }

    /** How many tiles the game gets as chunks, and of how many (status). */
    public String tilesStatus() {
        return planet == null ? "" : shown.cardinality() + "/" + PlanetLod.tileCount(planet) + " tiles near";
    }

    /** Which tiles the game gets as chunks (tests). */
    public boolean tileShown(int tile) {
        return shown.get(tile);
    }

    /** Render distance in blocks; infinite: every chunk (tests, and -Dgalaxycraft.renderDistance=0). Takes effect at the next residency pass. */
    public void setRenderDistance(double blocks) {
        render = blocks;
        renderPinned = true;
    }

    /**
     * The player's level of detail, for every planet from its next residency pass: blocks around
     * Mario that are chunks, and blocks between levels of the far view past them.
     */
    public static void setLevelOfDetail(double renderBlocks, double farStepBlocks) {
        settingRender = renderBlocks;
        settingFarStep = Math.max(8, farStepBlocks);
    }

    private double tileDistance(int t, Vector3d a, Vector3d b) {
        if (tileSpheres == null) {
            int count = PlanetLod.tileCount(planet);
            float[] sp = new float[4 * count];
            Vector3d c = new Vector3d(), sum = new Vector3d();
            double[] r = new double[1];
            for (int k = 0; k < count; k++) {
                int[] chunks = PlanetLod.chunksOfTile(planet, k);
                sum.zero();
                for (int ch : chunks) {
                    planet.sphere(ch, c, r);
                    sum.add(c);
                }
                sum.div(Math.max(1, chunks.length));
                double radius = 0;
                for (int ch : chunks) {
                    planet.sphere(ch, c, r);
                    radius = Math.max(radius, c.distance(sum) + r[0]);
                }
                sp[4 * k] = (float) sum.x;
                sp[4 * k + 1] = (float) sum.y;
                sp[4 * k + 2] = (float) sum.z;
                sp[4 * k + 3] = (float) radius;
            }
            tileSpheres = sp;
        }
        scratchCenter.set(tileSpheres[4 * t], tileSpheres[4 * t + 1], tileSpheres[4 * t + 2]);
        return segment(scratchCenter, tileSpheres[4 * t + 3], a, b);
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
        dropped.stream().filter(ch -> withKcl.get(ch)).forEach(send::add);
        for (double[] o : close) {
            int ch = (int) o[1];
            if (near.get(ch) && (!onGuest.get(ch) || !withKcl.get(ch))) send.add(ch);
        }
        return send;
    }

    private double distance(int chunk, Vector3d at) {
        return distance(chunk, at, at);
    }

    /** From a chunk's bounding sphere to the segment from a to b, blocks (0 or less: it touches). */
    private double distance(int chunk, Vector3d a, Vector3d b) {
        // Every chunk of a big planet goes through here each residency pass: no allocations.
        planet.sphere(chunk, scratchCenter, scratchRadius);
        return segment(scratchCenter, scratchRadius[0], a, b);
    }

    /** From a sphere to the segment from a to b, blocks (0 or less: it touches). */
    private static double segment(Vector3d c, double radius, Vector3d a, Vector3d b) {
        double abx = b.x - a.x, aby = b.y - a.y, abz = b.z - a.z;
        double len2 = abx * abx + aby * aby + abz * abz;
        double t = len2 < 1e-9 ? 0 : Math.clamp(((c.x - a.x) * abx + (c.y - a.y) * aby + (c.z - a.z) * abz) / len2, 0, 1);
        double dx = c.x - (a.x + t * abx), dy = c.y - (a.y + t * aby), dz = c.z - (a.z + t * abz);
        return Math.sqrt(dx * dx + dy * dy + dz * dz) - radius;
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

    /** A MSG_PLANET with id 0: the game drops every planet (leaving a world, its galaxy goes). */
    public static byte[] dropAll() {
        return ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN).array();
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

    static double renderDistance(String value) {
        try {
            double r = value == null ? DEFAULT_RENDER : Double.parseDouble(value);
            return r <= 0 ? Double.POSITIVE_INFINITY : r;
        } catch (NumberFormatException e) {
            return DEFAULT_RENDER;
        }
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
