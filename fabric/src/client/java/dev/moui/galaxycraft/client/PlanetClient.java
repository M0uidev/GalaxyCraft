package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.universe.GameOrigin;
import dev.moui.galaxycraft.universe.SystemIndex;

import dev.moui.galaxycraft.voxel.VoxelPlanet;
import dev.moui.galaxycraft.voxel.gen.PlanetGenerator;
import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.bridge.BridgeClient;
import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.gravity.LookMath;
import dev.moui.galaxycraft.proto.Layout;
import dev.moui.galaxycraft.proto.Seqlock;
import dev.moui.galaxycraft.shadow.ShadowWorld;
import dev.moui.galaxycraft.voxel.AtlasLink;
import dev.moui.galaxycraft.voxel.CellSpace;
import dev.moui.galaxycraft.voxel.Material;
import dev.moui.galaxycraft.voxel.Mining;
import dev.moui.galaxycraft.voxel.PlanetSession;
import dev.moui.galaxycraft.voxel.BlueprintStore;
import dev.moui.galaxycraft.voxel.GalaxyCatalog;
import dev.moui.galaxycraft.voxel.GalaxySave;
import dev.moui.galaxycraft.voxel.PlanetBlueprint;
import dev.moui.galaxycraft.voxel.PlanetLayout;
import dev.moui.galaxycraft.voxel.PlanetStore;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Vector3d;

/**
 * The voxel planet in Minecraft's hands: /galaxycraft planet, the clicks that break and place
 * blocks (any of Minecraft's, by its placement rules; and pour and fill buckets of water and lava)
 * while something is in either hand and no screen is open (Dolphin then keeps them from Mario), P
 * to land on the planet, and the messages that carry it to the game, the block atlas first. Up to
 * PlanetLayout.MAX_PLANETS planets per stage (galaxy), each saved in ~/.local/share/galaxycraft/planets
 * (%APPDATA%\galaxycraft\planets on Windows)
 * (see planetDir, PlanetStore.key) and loaded again when the stage is; /galaxycraft planet add puts
 * another one up (PlanetLayout.place), spawn replaces the one in focus. The planet nearest Mario
 * is the one in focus: the clicks, the outline, P and Minecraft's running of the blocks are its;
 * the game gets the chunks of that one and of those Mario nears, the others' far view only.
 * -Dgalaxycraft.planet=true (or a radius) spawns one in a stage that has none as soon as the
 * player follows Mario.
 *
 * Minecraft runs the blocks around Mario ({@link ShadowLink}): a right click uses the block it is
 * on (doors, levers, chests) or the held item on it before placing anything, and aiming at such a
 * block makes the clicks Minecraft's even with an empty hand.
 */
public final class PlanetClient {
    /** SDL scancode of P; protocol mouse mask bits (bit n = SDL button n). */
    /** How far Mario's blows reach (Minecraft's entity interaction range), blocks. */
    static final double REACH = 3;
    /** Keys by their USB HID usage (as the host reports them). */
    private static final int SC_W = 26, SC_A = 4, SC_S = 22, SC_D = 7, SC_LSHIFT = 225, SC_RSHIFT = 229;
    private static final int SC_P = 19, MOUSE_LEFT = 1 << 1, MOUSE_RIGHT = 1 << 3;
    public static final int DEFAULT_RADIUS = 32;
    /**
     * Time a tick may spend building the planets' far views and chunks, ms (-Dgalaxycraft.meshBudgetMs):
     * a planet streams in over more ticks instead of stalling Minecraft. Mario's collision is built
     * whatever it costs (PlanetSession's urgent lane).
     */
    static final long MESH_BUDGET_NANOS = (long) (Math.max(0.5, Double.parseDouble(System.getProperty("galaxycraft.meshBudgetMs", "6"))) * 1e6);
    /**
     * No more of the planet's bulk while the host has this much of ours untaken, bytes: what is sent
     * waits in line for the game, and Mario's collision would wait behind it.
     */
    static final int BULK_BACKLOG = 256 * 1024;
    /** Bytes the host had not taken at the last tick (status). */
    private static int lastBacklog;
    /** Ticks between saves of an edited planet. */
    private static final int SAVE_TICKS = 200;
    /** The stage's first planet (index 0): the same session all along, which tests hold on to. */
    private static final PlanetSession session = new PlanetSession(1 / GravityFrame.SCALE);
    /** The stage's other planets, each with the index of its file (PlanetStore.key). */
    record Extra(PlanetSession s, int index) {}
    private static final java.util.List<Extra> extras = new java.util.ArrayList<>();
    /** The planet nearest Mario (session when there is none). */
    private static PlanetSession focus = session;
    /** Where the next planet goes up. */
    private enum Placement {
        /** Replacing the one in focus, above the player (/galaxycraft planet spawn). */
        REPLACE,
        /** Another one, in PlanetLayout's rings (/galaxycraft planet add). */
        ADD,
        /** Replacing the planet the player stands on, where it is; the player lands on the new one. */
        REPLACE_HERE,
        /** Another one where the player looked (PlanetLayout.placeAlong). */
        CREATE_AHEAD,
        /** A world's first planet, at the center of GalaxyCraftSpace (where Mario waits). */
        HOME
    }

    private static Placement placement = Placement.REPLACE;
    /** The blueprint the planet being put up is made from (its name, for the catalog), null if none. */
    private static String madeFrom;
    /** The world's galaxy streamed from its catalog (null: no world, or game tests' fixed folder). */
    private static GalaxyStream stream;

    /** How a planet of the galaxy shows now (tests): complete, far<patches>, dot or none. */
    public static String shownAs(int index) {
        return stream == null ? "none" : stream.shownAs(index);
    }

    /** The world's galaxy streaming, or null outside one. */
    static GalaxyStream stream() {
        return stream;
    }
    private static PlanetSession replaceTarget; // REPLACE_HERE: the planet stood on, and the player's
    private static Vector3d replaceDir;         // direction from its center (galaxy axes)
    private static Vector3d aheadEye, aheadLook; // CREATE_AHEAD: the player's eye and look then (galaxy)
    /** -Dgalaxycraft.planetDir (game tests): one folder for every world, as before worlds had galaxies. */
    private static final boolean FIXED_DIR = !System.getProperty("galaxycraft.planetDir", "").isEmpty();
    /** The world's planets (null: no world, so none is loaded or saved). */
    private static PlanetStore store = FIXED_DIR ? new PlanetStore(planetDir()) : null;
    /** The world's galaxy (null: none, or FIXED_DIR). */
    private static GalaxySave galaxy;
    /** This world's home planet asked for (once: a failed generation is not retried every tick). */
    private static boolean homeAsked;
    /** The stage has planet files: a home planet never goes over one, even one that could not be read. */
    private static boolean hadSaved;
    /** Entering a world in GalaxyCraftSpace: Mario goes to the saved spot (or onto the first planet) once a planet is up. */
    private static boolean landPending;
    private static GalaxySave.Spot landOn;
    /** Ticks between saves of where the player stands. */
    private static final int SPOT_TICKS = 100;
    private static int sinceSpot;
    /** Since when (nanoTime, 0: not) Mario has had no gravity in GalaxyCraftSpace after landing; past RELAND_NANOS he lands again. */
    private static long unlandedSince;
    private static final long RELAND_NANOS = 5_000_000_000L;
    /**
     * Planets are made (generated, read) one at a time on this thread, at low priority: the game,
     * Dolphin and Minecraft's server keep their cores while a galaxy streams in.
     */
    static final ExecutorService maker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "GalaxyCraft planet maker");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    /**
     * The worker threads planets and far views are made on: all cores but two (Dolphin's), at low
     * priority. A planet's own parallel work (its faces, its caves) runs here too, never on the
     * common pool, so entering a world does not starve the game.
     */
    static final java.util.concurrent.ForkJoinPool workers = new java.util.concurrent.ForkJoinPool(
            Math.max(1, Runtime.getRuntime().availableProcessors() - 2), pool -> {
                java.util.concurrent.ForkJoinWorkerThread t = java.util.concurrent.ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
                t.setName("GalaxyCraft worker " + t.getPoolIndex());
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            }, null, false);
    private static final ExecutorService saver = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "GalaxyCraft planet saver");
        t.setDaemon(true);
        return t;
    });
    private static final int autoRadius = autoRadius(System.getProperty("galaxycraft.planet"));
    private static String stage;
    private static boolean autoSpawn;
    private static int spawnRadius; // > 0: spawn next tick
    private static PlanetBlueprint spawnBlueprint; // non-null: spawn next tick
    private static java.util.concurrent.CompletableFuture<VoxelPlanet> generating; // off the game's thread
    private static VoxelPlanet generated; // non-null: generated, spawn next tick
    private static McWorldgen worldgen;
    private static MinecraftServer worldgenServer;
    static final BlueprintStore blueprints = new BlueprintStore(planetDir().resolveSibling("blueprints"));
    private static int lastButtons;
    /** Buttons held since a screen had them: not the game's until let go. */
    private static int screenButtons;
    private static boolean screenLast;
    private static boolean lastP;
    private static int sinceSave;
    private static final ShadowLink shadow = new ShadowLink(() -> focus);
    private static boolean aimUsable; // an empty hand aims at a block a click uses
    private static final DropsClient drops = new DropsClient(() -> focus);
    private static final EntityClient entities = new EntityClient(() -> focus, drops);
    private static McBlocks blocks;
    private static AtlasLink atlasLink;
    /** Breaking blocks while the button is held down, as Minecraft does (Minecraft's own speeds). */
    private static final Mining mining = new Mining();
    /**
     * The last cell broken and its block then: Minecraft breaks it on its own thread, so for a few
     * ticks the planet may still show it, and it must not break (and sound) twice.
     */
    private static int brokeCell = -1, brokeId = -1, sinceBroke;
    private static final int BROKE_TICKS = 20;
    /** This tick's frame (sounds are played where their block is, in Minecraft's coordinates). */
    private static GravityFrame frameNow;

    private PlanetClient() {}

    /** Minecraft's worldgen of the integrated server, for generated planets (null without one). */
    public static McWorldgen worldgen() {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server != worldgenServer) {
            worldgenServer = server;
            worldgen = server == null ? null : new McWorldgen(server);
        }
        return worldgen;
    }

    /**
     * Generates bp's cells on another thread, its blocks' ids looked up first on this one (McBlocks
     * adds ids as it meets new states); the planet is spawned when they are done.
     */
    private static void generate(PlanetBlueprint bp, LocalPlayer player) {
        if (worldgen() == null) {
            say(player, "Generated planets need a single player world");
            return;
        }
        say(player, "Generating " + bp.name() + "...");
        generating = generateAsync(bp);
    }

    /** How long a planet being made waits for an answer from the game's or the server's thread, s. */
    static final int ANSWER_SECONDS = 30;

    /**
     * The answer to a task handed to Minecraft's thread (or its server's) from the planet maker. Not
     * waited for forever: leaving a world drops the tasks still queued (and stops the server), and
     * the maker, one thread, would wait for an answer that never comes, every planet after it queued
     * behind (entering again, nothing loaded and Mario never landed). The planet being made is
     * given up instead.
     */
    static <T> T answer(java.util.concurrent.CompletableFuture<T> f, String what) {
        try {
            return f.get(ANSWER_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new java.util.concurrent.CompletionException(e.getCause());
        } catch (java.util.concurrent.TimeoutException | InterruptedException e) {
            f.cancel(false);
            throw new java.util.concurrent.CancellationException("no answer for " + what + " (the world was left?)");
        }
    }

    /** bp's planet made on another thread (null without Minecraft's worldgen: no single player world). */
    static java.util.concurrent.CompletableFuture<VoxelPlanet> generateAsync(PlanetBlueprint bp) {
        McWorldgen gen = worldgen();
        if (gen == null || blocks == null) return null;
        java.util.Map<String, Integer> known = new java.util.concurrent.ConcurrentHashMap<>();
        for (String b : PlanetGenerator.blocks()) known.put(b, blocks.parse(b));
        // Trees bring states of their own: those are looked up on this thread too, as they come.
        java.util.function.ToIntFunction<String> ids = name -> known.computeIfAbsent(name,
                n -> answer(Minecraft.getInstance().submit(() -> blocks.parse(n)), "block " + n));
        McBlocks b = blocks;
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            // Its parallel streams run on the workers they are started from.
            PlanetGenerator.Cells cells = workers.submit(() -> PlanetGenerator.cells(bp, gen.vegetation(), ids)).join();
            // The planet reads every cell's block info as it is put together (millions of cells for a
            // big one): that is done here, not in a tick, once the game's thread has worked out the
            // info of each block it uses (McBlocks makes it from Minecraft's models, lazily).
            java.util.BitSet used = new java.util.BitSet();
            for (char c : cells.cells()) used.set(c);
            Minecraft.getInstance().submit(() -> {
                used.stream().forEach(b::info);
                return null;
            }).join();
            VoxelPlanet planet = cells.planet(b);
            planet.sphere(0, new Vector3d(), new double[1]); // every chunk's bounding sphere, worked out here too
            return planet;
        }, maker);
    }

    private static int skySent = -1, skyAge;

    /** The sky's light last sent to the game, 0xRRGGBB (white before any). */
    static int skyLight() {
        return skySent < 0 ? 0xFFFFFF : skySent;
    }

    /**
     * The sky's light at this hour to the game (GXC_MSG_SKY): Minecraft's lightmap for full sky
     * light and no block light (the time's sky light factor and color, then lightmap.fsh's clamp
     * and its lift at the default brightness). Sent when it changes, and now and then anyway (a
     * restarted game has forgotten it).
     */
    private static void sendSky(Minecraft mc, BridgeClient bridge, float[] fog) {
        if (mc.level == null || mc.player == null) return;
        var attrs = mc.level.environmentAttributes();
        var pos = mc.player.position();
        float factor = attrs.getValue(net.minecraft.world.attribute.EnvironmentAttributes.SKY_LIGHT_FACTOR, pos);
        org.joml.Vector3fc color = attrs.getValue(net.minecraft.world.attribute.EnvironmentAttributes.SKY_LIGHT_COLOR, pos);
        double[] c = {Math.min(1, color.x() * factor), Math.min(1, color.y() * factor), Math.min(1, color.z() * factor)};
        double max = Math.max(c[0], Math.max(c[1], c[2]));
        if (max > 0) {
            double inv = 1 - max, lift = (1 - inv * inv * inv * inv) / max;
            for (int i = 0; i < 3; i++) c[i] = (c[i] + c[i] * lift) / 2;
        }
        int packed = (int) Math.round(c[0] * 255) << 16 | (int) Math.round(c[1] * 255) << 8 | (int) Math.round(c[2] * 255);
        int fogKey = java.util.Arrays.hashCode(fog);
        if (packed == skySent && fogKey == fogSent && ++skyAge < 100) return;
        // After the light: under water, Minecraft's fog (r, g, b, start and end in blocks), else zeros.
        float[] f = fog != null ? fog : new float[5];
        java.nio.ByteBuffer msg = java.nio.ByteBuffer.allocate(32).putFloat((float) c[0]).putFloat((float) c[1])
                .putFloat((float) c[2]);
        for (float v : f) msg.putFloat(v);
        if (bridge.send(Layout.MSG_SKY, msg.array())) {
            skySent = packed;
            fogSent = fogKey;
            skyAge = 0;
        }
    }

    private static int fogSent;
    /** Ticks the camera has been in water (Minecraft's water vision: the fog opens up over 30 s). */
    private static int waterVisionTime;

    /**
     * Minecraft's underwater fog for the camera, or null out of water: the biome's water fog color
     * brightened by the water vision, and its start and end (the end scaled by the vision, at least
     * a quarter), as FogRenderer and WaterFogEnvironment work them out.
     */
    private static float[] waterFog(Minecraft mc, PlanetSession session, GravityFrame frame, LocalPlayer player) {
        int cell = -1;
        if (blocks != null && session.active() && frame != null && player != null) {
            Vector3d cam = mc.options.getCameraType().isFirstPerson() ? vec(player.getEyePosition())
                    : vec(mc.gameRenderer.mainCamera().position());
            int c = session.cellAt(frame.toGal(cam));
            if (c >= 0 && session.planet().fluid(c) == dev.moui.galaxycraft.voxel.Blocks.WATER) cell = c;
        }
        waterVisionTime = cell >= 0 ? Math.min(600, waterVisionTime + 1) : Math.max(0, waterVisionTime - 10);
        if (cell < 0) return null;
        float vision = waterVisionTime >= 600 ? 1f
                : Math.min(1f, waterVisionTime / 100f) * 0.6f + (waterVisionTime < 100 ? 0f : Math.min(1f, (waterVisionTime - 100) / 500f)) * 0.39999998f;
        float[] f = blocks.waterFog(session.planet().biome(cell));
        float peak = Math.max(f[0], Math.max(f[1], f[2]));
        if (f[0] != 0 && f[1] != 0 && f[2] != 0)
            for (int i = 0; i < 3; i++) f[i] += (f[i] / peak - f[i]) * vision;
        f[4] *= Math.max(0.25f, vision);
        return f;
    }

    private static void say(LocalPlayer player, String text) {
        if (player != null) player.sendSystemMessage(Component.literal("Super Minecraft Galaxy: " + text));
    }

    /** Minecraft's blocks for the planets, once its models are loaded (null before the first tick). */
    public static McBlocks blocks() {
        return blocks;
    }

    /** Places a block item as a right click would (end-to-end tests). */
    public static boolean placeItem(Vector3d eyeGal, Vector3d lookGal, ItemStack stack, Vector3d marioFeetGal) {
        return blocks != null && focus.placeBlock(eyeGal, lookGal, blocks.placer(stack), marioFeetGal);
    }

    /** Minecraft's text for a block state id (end-to-end tests). */
    public static String stateName(int state) {
        return blocks == null ? "" : blocks.name(state);
    }

    /** Minecraft's text for the block in a cell of the planet (end-to-end tests). */
    public static String blockName(int cell) {
        return blocks == null || !focus.active() ? "" : blocks.name(focus.planet().get(cell));
    }

    /** The stage's first planet (end-to-end tests edit it directly). */
    public static PlanetSession session() {
        return session;
    }

    /** The planet in focus, nearest Mario. */
    public static PlanetSession focus() {
        return focus;
    }

    /** Every planet slot of the stage, the first first (some may be empty). */
    public static java.util.List<PlanetSession> planets() {
        java.util.List<PlanetSession> all = new java.util.ArrayList<>();
        all.add(session);
        for (Extra e : extras) all.add(e.s());
        return all;
    }

    /** Every body of the stage: the planets, then the active stations. */
    public static java.util.List<PlanetSession> bodies() {
        java.util.List<PlanetSession> all = planets();
        all.addAll(StationClient.sessions());
        return all;
    }

    /** The stage Mario is in (null before the first tick). */
    static String stage() {
        return stage;
    }

    /** A body leaving the stage: the game is told, then it is forgotten. */
    static void retire(PlanetSession s) {
        s.remove();
        leaving.add(s);
        if (focus == s) focus = session;
    }

    /** Runs on the planets' saver thread (saves in order, off Minecraft's). */
    static void save(Runnable r) {
        saver.execute(r);
    }

    /** Whose strip of the shadow dimension a body runs in: a planet's by its file, a station's by its id (it travels with it). */
    static String shadowKey(PlanetSession s) {
        if (s.station() != null) return "station-" + s.station().id;
        return stage == null ? "" : PlanetStore.key(stage, indexOf(s));
    }

    private static int indexOf(PlanetSession s) {
        for (Extra e : extras) if (e.s() == s) return e.index();
        return 0;
    }

    /** Whether a right click would scoop with an empty bucket (the outline then goes on fluids). */
    private static boolean bucketFirst(LocalPlayer player) {
        ItemStack main = player.getMainHandItem();
        return main.is(Items.BUCKET) || main.isEmpty() && player.getOffhandItem().is(Items.BUCKET);
    }

    public static boolean itemActive(LocalPlayer player) {
        return player != null && (!player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty() || aimUsable);
    }

    /** /galaxycraft planet spawn [radius]: next tick, above the player, replacing the one in focus. */
    public static void requestSpawn(int radius) {
        spawnRadius = radius;
        placement = Placement.REPLACE;
    }

    /** A planet built from that blueprint, next tick, as {@link #requestSpawn(int)} puts one. */
    public static void requestSpawn(PlanetBlueprint blueprint) {
        spawnBlueprint = blueprint;
        placement = Placement.REPLACE;
    }

    /** /galaxycraft planet add [radius]: next tick, another planet (PlanetLayout.place), the others kept. */
    public static void requestAdd(int radius) {
        spawnRadius = radius;
        placement = Placement.ADD;
    }

    /** Another planet built from that blueprint, as {@link #requestAdd(int)} puts one. */
    public static void requestAdd(PlanetBlueprint blueprint) {
        spawnBlueprint = blueprint;
        placement = Placement.ADD;
    }

    /** The planet the player stands on (in its gravity), if any. */
    public static PlanetSession standingOn() {
        Vector3d feet = GalaxyCraftClient.galaxyPos().orElse(null);
        if (feet == null) return null;
        for (PlanetSession s : planets())
            if (s.active() && s.center().distance(feet) <= s.gravityUnits()) return s;
        return null;
    }

    /** The editor's Replace: that blueprint instead of the planet stood on, where it is; the player lands on it. */
    public static boolean requestReplaceHere(PlanetBlueprint blueprint) {
        PlanetSession on = standingOn();
        Vector3d feet = GalaxyCraftClient.galaxyPos().orElse(null);
        if (on == null || feet == null) return false;
        replaceTarget = on;
        replaceDir = new Vector3d(feet).sub(on.center());
        spawnBlueprint = blueprint;
        placement = Placement.REPLACE_HERE;
        return true;
    }

    /** The editor's Create: that blueprint as another planet, where the player looks now. */
    public static boolean requestCreateAhead(PlanetBlueprint blueprint) {
        LocalPlayer player = Minecraft.getInstance().player;
        Vector3d feet = GalaxyCraftClient.galaxyPos().orElse(null);
        Vector3d up = GalaxyCraftClient.galaxyUp().orElse(null);
        if (player == null || feet == null || up == null) return false;
        aheadEye = new Vector3d(up).mul(player.getEyeHeight() / GravityFrame.SCALE).add(feet);
        aheadLook = GalaxyCraftClient.galaxyLook(player.getYRot(), player.getXRot()).orElse(up);
        spawnBlueprint = blueprint;
        placement = Placement.CREATE_AHEAD;
        return true;
    }

    /** Mario onto the planet in focus. */
    public static void teleport() {
        land(focus);
    }

    /** With Minecraft movement the player is put there too (Mario goes where it is). */
    private static void land(PlanetSession s) {
        Vector3d at = s.teleport();
        if (at != null && GalaxyCraftClient.walking()) GalaxyCraftClient.moveTo(s.galOf(at));
    }

    /** Removes the planet in focus, from the game and from disk. */
    public static void remove() {
        PlanetSession gone = focus;
        int index = indexOf(gone);
        gone.remove();
        if (gone != session) {
            extras.removeIf(e -> e.s() == gone);
            leaving.add(gone); // until the game has been told it is gone (its far view, its slots)
        }
        focus = session;
        if (stream != null) stream.removeEntry(index);
        String s = stage;
        PlanetStore store = PlanetClient.store;
        if (s != null && !s.isEmpty() && store != null) saver.execute(() -> {
            try {
                store.delete(PlanetStore.key(s, index));
            } catch (IOException e) {
                GalaxyCraft.LOG.warn("Could not delete the planet of {}: {}", s, e.toString());
            }
        });
    }

    public static String status() {
        String where = stage == null || stage.isEmpty() ? "" : " in " + stage;
        StringBuilder out = new StringBuilder();
        for (PlanetSession p : planets()) {
            if (!p.active()) continue;
            Vector3d c = p.center();
            out.append(out.isEmpty() ? "" : "; ").append(String.format("%splanet of radius %.0f%s at (%.0f, %.0f, %.0f), %d to send, %d chunks with collision, %s%s",
                    p == focus ? "* " : "", p.planet().surface(), where, c.x, c.y, c.z, p.queued(), p.collisionChunks(),
                    p.tilesStatus(), p.detail() ? "" : ", far view only"));
        }
        if (lastBacklog > 0) out.append(String.format("%s%d KB the game has not taken", out.isEmpty() ? "" : "; ", lastBacklog / 1024));
        return out.isEmpty() ? "no planet" + where : out.toString();
    }

    /**
     * Puts p up as placement says: replacing the planet in focus (or the first slot) above the
     * player, or the one stood on where it is; or as another one, in PlanetLayout's rings or where
     * the player looked, with room for its gravity among the others'. It is then in focus.
     */
    private static void spawnPlanet(VoxelPlanet p, Vector3d feet, Vector3d up, LocalPlayer player) {
        java.util.List<PlanetSession> all = planets();
        Placement how = placement;
        placement = Placement.REPLACE;
        if (how == Placement.REPLACE_HERE && (replaceTarget == null || !replaceTarget.active())) how = Placement.REPLACE;
        PlanetSession target = switch (how) {
            case REPLACE -> focus.active() ? focus : session;
            case REPLACE_HERE -> replaceTarget;
            case ADD, CREATE_AHEAD -> session.active() ? null : session;
            case HOME -> session;
        };
        if (target == null && (stream != null ? stream.entries().size() >= GalaxyCatalog.MAX
                : all.stream().filter(PlanetSession::active).count() >= PlanetLayout.MAX_PLANETS)) {
            int max = stream != null ? GalaxyCatalog.MAX : PlanetLayout.MAX_PLANETS;
            say(player, "A galaxy holds " + max + " planets at most");
            return;
        }
        java.util.List<PlanetLayout.Sphere> others = new java.util.ArrayList<>();
        for (PlanetSession s : bodies()) if (s.active() && s != target) others.add(new PlanetLayout.Sphere(s.center(), s.gravityUnits()));
        if (stream != null) // the far ones too: a new planet keeps clear of every planet of the galaxy
            for (GalaxyCatalog.Entry e : stream.entries()) {
                PlanetSession s = sessionOf(e.index());
                if ((s == null || !s.active()) && s != target)
                    others.add(new PlanetLayout.Sphere(e.center(), PlanetSession.gravityRadius(e.radius()) / GravityFrame.SCALE));
            }
        double units = 1 / GravityFrame.SCALE;
        double gravity = PlanetSession.gravityRadius(p.surface()) * units;
        Vector3d c = switch (how) {
            case REPLACE_HERE -> new Vector3d(target.center());
            case CREATE_AHEAD -> PlanetLayout.placeAlong(others, gravity, aheadEye, aheadLook, units);
            case HOME -> new Vector3d();
            default -> PlanetLayout.place(others, gravity, feet, up, units);
        };
        if (c == null) {
            say(player, how == Placement.CREATE_AHEAD ? "No room for a planet where you look" : "No room for another planet here");
            return;
        }
        String made = madeFrom;
        madeFrom = null;
        if (target == null) {
            int index = stream != null ? GalaxyCatalog.added(stream.entries(), c, 0, GalaxyCatalog.Kind.BLUEPRINT, null, null, 0).index()
                    : freeIndex();
            target = new PlanetSession(units);
            target.setBlocks(blocks);
            extras.add(new Extra(target, index));
        }
        target.spawnAt(p, c);
        focus = target;
        if (stream != null) // the catalog keeps it: a blueprint's name, or no recipe (its file is all there is)
            stream.put(new GalaxyCatalog.Entry(indexOf(target), c.x, c.y, c.z, (int) Math.round(p.surface()),
                    GalaxyCatalog.Kind.BLUEPRINT, null, made, 0));
        if (how == Placement.REPLACE_HERE) {
            // Back where the player was, on the new ground.
            Vector3d at = target.teleportToward(replaceDir);
            if (at != null && GalaxyCraftClient.walking()) GalaxyCraftClient.moveTo(target.galOf(at));
        }
    }

    /** The lowest file index no planet of the stage has. */
    private static int freeIndex() {
        for (int i = 1; ; i++) {
            int k = i;
            if (extras.stream().noneMatch(e -> e.index() == k)) return i;
        }
    }

    /** The planet whose surface is nearest Mario (session if there is none). */
    private static PlanetSession nearest(Vector3d mario) {
        PlanetSession best = session;
        double bestD = Double.MAX_VALUE;
        for (PlanetSession p : bodies()) {
            if (!p.active() || mario == null) continue;
            double d = p.center().distance(mario) - p.planet().surface() / GravityFrame.SCALE;
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return mario == null ? focus : best;
    }

    /** Client tick, after the gravity frame is up to date. */
    public static void tick(LocalPlayer player, BridgeClient bridge, GravityFrame frame, Seqlock.WorldState world) {
        if (blocks == null) {
            if (FIXED_DIR) StationClient.enterWorld(planetDir());
            blocks = McBlocks.create(Minecraft.getInstance());
            atlasLink = new AtlasLink(blocks.atlas, 1);
            session.setBlocks(blocks);
            for (Extra e : extras) e.s().setBlocks(blocks);
        }
        for (byte[] piece; (piece = atlasLink.peek(world.sceneId(), bridge.hostPid())) != null
                && bridge.send(Layout.MSG_ATLAS, piece); ) atlasLink.sent();
        if (!bridge.stage().equals(stage)) enterStage(bridge.stage());
        boolean space = galaxy != null && Layout.SPACE_STAGE.equals(stage);
        marioUniverse = world.queryPos();
        // The floating origin first: what this tick sends is from wherever it is now.
        UniverseClient.tick(bridge, world, landPending || generating != null, landPending && landOn != null && stream != null
                ? stream.entry(landOn.planet()).map(GalaxyCatalog.Entry::center).orElse(null) : null);
        for (ShadowWorld.Bed b; (b = ShadowWorld.pollBed()) != null; ) slept(b, player);
        // The player died and came back (Minecraft made it anew): Mario lands at the bed, else where he stood.
        if (space && lastPlayer != null && player != lastPlayer) {
            landOn = respawnSpot();
            landPending = true;
        }
        lastPlayer = player;
        // A world's galaxy: its catalog (made now on its first visit), its planets streamed from it.
        if (space && stream == null && !homeAsked && worldgen() != null) {
            homeAsked = true;
            startGalaxy(player);
        }
        if (spawnBlueprint != null && spawnBlueprint.mode() == PlanetBlueprint.Mode.GENERATED) {
            if (generating == null) generate(spawnBlueprint, player);
            spawnBlueprint = null;
        }
        if (generating != null && generating.isDone()) {
            try {
                generated = generating.join();
            } catch (RuntimeException e) {
                GalaxyCraft.LOG.warn("Could not generate the planet: {}", e.toString());
                say(player, "Could not generate the planet: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
            }
            generating = null;
        }
        // A planet asked for may be made out in space too (no gravity there); the automatic one waits
        // for real gravity (SMG2's menus and title screen have none).
        boolean asked = spawnRadius > 0 || spawnBlueprint != null || generated != null;
        // A world's first planet goes up in GalaxyCraftSpace before Mario has any gravity.
        boolean home = placement == Placement.HOME && asked && spawnBlueprint == null;
        if (home || frame != null && (world.hasGravity() ? asked || autoSpawn && world.follow() : asked && GalaxyCraftClient.inVoid())) {
            if (spawnBlueprint != null) madeFrom = spawnBlueprint.name();
            VoxelPlanet p = generated != null ? generated : spawnBlueprint != null ? spawnBlueprint.build(blocks)
                    : VoxelPlanet.ofRadius(spawnRadius > 0 ? spawnRadius : autoRadius, blocks);
            spawnPlanet(p, world.queryPos(), frame == null ? new Vector3d(0, 1, 0) : frame.upGal(), player);
            spawnRadius = 0;
            spawnBlueprint = null;
            generated = null;
            autoSpawn = false;
            sinceSave = SAVE_TICKS; // saved right away
            if (focus.active()) GalaxyCraft.LOG.info("Voxel planet of radius {} at {}", focus.planet().surface(), focus.center());
        }
        Optional<Seqlock.InputState> in = bridge.input();
        int buttons = in.map(Seqlock.InputState::buttons).orElse(0);
        boolean p = in.map(i -> (i.keys()[SC_P / 8] >> (SC_P % 8) & 1) != 0).orElse(false);
        boolean screen = Minecraft.getInstance().gui.screen() != null; // the clicks are the screen's
        // As Minecraft: a button pressed on a screen (Done on a sign's editor) is not the game's
        // once the screen closes, until it is let go (else it breaks what the crosshair is on).
        // The tick after it closes too: the click that closed it may land between two ticks.
        screenButtons = screen || screenLast ? buttons : screenButtons & buttons;
        screenLast = screen;
        buttons &= ~screenButtons;
        PlanetSession was = focus;
        focus = nearest(world.queryPos());
        if (focus != was) {
            was.setOutline(-1);
            was.setCrack(-1, -1, null);
            mining.stop();
        }
        frameNow = frame;
        for (PlanetSession s : bodies())
            if (s.active() && world.queryPos() != null)
                s.setDetail(PlanetLayout.detail(s.detail(), s == focus, s.center().distance(world.queryPos()), s.gravityUnits(),
                        1 / GravityFrame.SCALE));
        // Mario back at the galaxy's center with no gravity a while after landing (the stage
        // restarted: he fell, or died): onto the planet again, where the player last stood.
        if (space && frame == null && !landPending && !world.hasGravity()) {
            if (unlandedSince == 0) unlandedSince = System.nanoTime();
            else if (System.nanoTime() - unlandedSince > RELAND_NANOS) {
                landOn = respawnSpot();
                landPending = true;
                unlandedSince = 0;
            }
        } else unlandedSince = 0;
        if (stream != null) {
            Vector3d from = world.queryPos();
            if (landPending && landOn != null) from = stream.entry(landOn.planet()).map(GalaxyCatalog.Entry::center).orElse(from);
            else if (landPending) from = new Vector3d();
            stream.tick(from, world.sceneId(), bridge.hostPid());
        }
        if (space && landPending) landOnSpot(player);
        if (space && frame != null && ++sinceSpot >= SPOT_TICKS) {
            sinceSpot = 0;
            writeSpot(player);
        }
        PlanetSession session = focus; // the clicks, the outline and Minecraft's running of the blocks are its
        if (session.active() && frame != null && player != null) {
            if (p && !lastP && !screen) land(session);
            Vector3d eye = frame.toGal(vec(player.getEyePosition()));
            Vector3d look = frame.dirToGal(LookMath.direction(player.getYRot(), player.getXRot()));
            PlanetSession.Aim aim = screen ? null : session.aim(eye, look);
            Vector3d eyeLocal = session.localOf(eye);
            // A mob in reach in front of the block hit is what the crosshair is on, as in Minecraft:
            // it takes the clicks, whatever is in hand, and the block behind it is not outlined.
            double block = aim == null ? Double.MAX_VALUE
                    : CellSpace.point(session.planet().grid, aim.cell(), aim.hit().x, aim.hit().y, aim.hit().z).distance(eyeLocal);
            Entity target = screen || !shadow.available() ? null : entities.aimed(eyeLocal, look, Math.min(REACH, block));
            aimUsable = target == null && aim != null && shadow.available() && blocks.usable(session.planet().get(aim.cell()));
            boolean item = itemActive(player) && !screen;
            // Minecraft's outline on the block the clicks would act on, only with something in hand.
            session.setOutline(item && target == null ? session.target(eye, look, bucketFirst(player)) : -1);
            // Mario stands where he is in the shadow too, so mobs chase him and his blows land.
            ShadowWorld.mario(new ShadowWorld.MarioAt(session.planet(), session.localOf(world.queryPos()), new Vector3d(look),
                    player.getUUID()));
            boolean hit = false;
            if (target != null && pressed(buttons, MOUSE_LEFT)) {
                ShadowWorld.attack(target.getId(), player.getUUID());
                hit = true;
            }
            // Right click on a mob or vehicle in reach uses it (rides a minecart or boat).
            if (target != null && pressed(buttons, MOUSE_RIGHT)) {
                ShadowWorld.interact(target.getId(), player.getUUID());
                hit = true;
            }
            ShadowWorld.steer(new ShadowWorld.Steer(key(in, SC_W), key(in, SC_S), key(in, SC_A), key(in, SC_D),
                    key(in, SC_LSHIFT) || key(in, SC_RSHIFT)));
            // A station's core: its menu on a right click, and it never breaks.
            boolean core = aim != null && StationClient.isCore(session, aim.cell());
            if (core && pressed(buttons, MOUSE_RIGHT) && target == null) {
                StationScreen.open(session);
                hit = true;
            }
            if (core && pressed(buttons, MOUSE_LEFT)) player.sendOverlayMessage(Component.translatable("message.galaxycraft.station.core"));
            // Held down on a block (not on a mob): Minecraft's breaking, a tick at a time.
            if (item && target == null && !core) mine(player, session, eye, look, aim, pressed(buttons, MOUSE_LEFT), (buttons & MOUSE_LEFT) != 0);
            else {
                mining.stop();
                session.setCrack(-1, -1, null);
            }
            // Minecraft gets the same clicks and swings the arm by itself. Held, again every
            // USE_REPEAT ticks, as Minecraft's (its rightClickDelay).
            if ((buttons & MOUSE_RIGHT) == 0) useDelay = 0;
            else if (useDelay > 0) useDelay--;
            if (item && (buttons & MOUSE_RIGHT) != 0 && useDelay == 0 && !hit) {
                use(player, eye, look, world.queryPos(), aim);
                useDelay = USE_REPEAT;
            }
        }
        else {
            ShadowWorld.mario(null);
            mining.stop();
            focus.setCrack(-1, -1, null);
        }
        shadow.tick(shadowKey(focus), world.queryPos());
        drops.tick(Minecraft.getInstance(), frame, frame == null ? null : world.queryPos());
        entities.tick();
        // The player hurt by the shadow: Mario reels in the game too.
        for (ShadowWorld.Hurt h; (h = ShadowWorld.pollHurt()) != null; )
            if (session.active() && h.planet() == session.planet()) {
                Vector3d from = GameOrigin.toGame(session.galOf(h.from()));
                bridge.send(Layout.MSG_HURT, java.nio.ByteBuffer.allocate(16).putFloat((float) from.x).putFloat((float) from.y)
                        .putFloat((float) from.z).putInt(h.kind()).array());
            }
        SwimClient.tick(session, frame);
        sendSky(Minecraft.getInstance(), bridge, waterFog(Minecraft.getInstance(), session, frame, player));
        lastButtons = buttons;
        lastP = p;
        // The planet in focus first: its chunks before the others' when the ring is full. Mario's
        // collision goes whatever it costs; the rest only within this tick's budget, and while the
        // host keeps up with what it has been sent.
        StationClient.tick(stage, world.queryPos());
        java.util.List<PlanetSession> order = bodies();
        order.remove(focus);
        order.addFirst(focus);
        long end = System.nanoTime() + MESH_BUDGET_NANOS;
        lastBacklog = bridge.backlog();
        java.util.function.BooleanSupplier bulk = () -> System.nanoTime() - end < 0 && bridge.backlog() < BULK_BACKLOG;
        for (PlanetSession s : order) {
            s.update(world.sceneId(), bridge.hostPid(), world.queryPos());
            for (PlanetSession.Msg m; (m = s.peek(bulk)) != null && bridge.send(m.type(), m.payload()); ) s.sent();
        }
        for (PlanetSession s : leaving)
            for (PlanetSession.Msg m; (m = s.peek(bulk)) != null && bridge.send(m.type(), m.payload()); ) s.sent();
        leaving.removeIf(s -> s.queued() == 0);
        rescue();
        traceBodies(world.queryPos());
        if (stream != null) stream.send(bridge, bulk);
        if (++sinceSave >= SAVE_TICKS) {
            sinceSave = 0;
            saveNow();
        }
    }

    private static Vector3d marioUniverse;

    /** -Dgalaxycraft.traceBodies: each second near a body, how it shows and whether it has collision (a fall through one). */
    private static final boolean TRACE_BODIES = Boolean.getBoolean("galaxycraft.traceBodies");
    private static int traceTicks;
    private static Vector3d traceLast;

    private static void traceBodies(Vector3d mario) {
        if (!TRACE_BODIES || mario == null || ++traceTicks < 20) return;
        traceTicks = 0;
        double speed = traceLast == null ? 0 : mario.distance(traceLast) * GravityFrame.SCALE; // blocks per second
        traceLast = new Vector3d(mario);
        for (PlanetSession s : bodies()) {
            if (!s.active()) continue;
            double past = s.center().distance(mario) - PlanetSession.gravityRadius(s.planet().surface()) / GravityFrame.SCALE;
            if (past * GravityFrame.SCALE > 400) continue;
            GalaxyCraft.LOG.info("trace: {}{} {} blocks past gravity, {} blocks/s, detail {}, {} collision chunks, {} to send",
                    s == focus ? "* " : "", indexOf(s), Math.round(past * GravityFrame.SCALE), Math.round(speed), s.detail(),
                    s.collisionChunks(), s.queued());
            Vector3d l = s.localOf(mario);
            long near = s.chunksNear(mario, 8).stream().filter(s::collides).count();
            GalaxyCraft.LOG.info("trace:   mario local {} {} {} blocks, cell {}, {} of {} chunks within 8 blocks have collision",
                    Math.round(l.x * 10) / 10.0, Math.round(l.y * 10) / 10.0, Math.round(l.z * 10) / 10.0, s.cellAt(mario),
                    near, s.chunksNear(mario, 8).size());
            GalaxyCraft.LOG.info("trace:   tiles near Mario: {}", s.traceTiles(mario));
            Vector3d gc = GameOrigin.toGame(s.center()), gm = GameOrigin.toGame(mario);
            GalaxyCraft.LOG.info("trace:   game coordinates (units): body centre {} {} {}, mario {} {} {}, origin epoch {}, offset {}",
                    Math.round(gc.x), Math.round(gc.y), Math.round(gc.z), Math.round(gm.x), Math.round(gm.y), Math.round(gm.z),
                    GameOrigin.epoch(), GameOrigin.offset());
        }
        if (stream != null) stream.trace(mario, speed);
    }

    /** Endless systems around the world's galaxy (-Dgalaxycraft.endless=false: the world's galaxy alone). */
    static final boolean ENDLESS = !"false".equals(System.getProperty("galaxycraft.endless"));

    /** A saved spot with its planet as this run's index (a generated system's planet by its sector and n). */
    private static GalaxySave.Spot resolved(GalaxySave.Spot s) {
        if (s == null || s.system() == null) return s;
        return SystemIndex.parse(s.system()).map(sector -> new GalaxySave.Spot(SystemIndex.index(sector, s.planet()), s.dx(), s.dy(),
                s.dz(), s.yaw(), s.pitch())).orElse(null);
    }

    /** A spot as saved: a generated system's planet by its sector and n, which every run has the same. */
    private static GalaxySave.Spot saved(GalaxySave.Spot s) {
        return SystemIndex.ref(s.planet()).map(r -> new GalaxySave.Spot(r.n(), s.dx(), s.dy(), s.dz(), s.yaw(), s.pitch(),
                SystemIndex.name(r.sector()))).orElse(s);
    }

    /** Where the game said Mario is at the last tick, universe units (null: not known). */
    public static Vector3d marioUniverse() {
        return marioUniverse == null ? null : new Vector3d(marioUniverse);
    }

    /** The floating origin moved: planet records still queued are made again from it. */
    static void originMoved() {
        for (PlanetSession s : leaving) s.originMoved();
        if (stream != null) stream.originMoved();
    }

    /** A right click with what is in hand, where the player looks (tests). */
    public static void useHeld(LocalPlayer player, GravityFrame frame, Vector3d marioFeetGal) {
        Vector3d eye = frame.toGal(vec(player.getEyePosition()));
        Vector3d look = frame.dirToGal(LookMath.direction(player.getYRot(), player.getXRot()));
        use(player, eye, look, marioFeetGal, focus.aim(eye, look));
    }

    /** The player of the last tick: another one is the same player after dying. */
    private static LocalPlayer lastPlayer;

    /** A bed slept in: its top is where the player comes back after dying (saved with the world). */
    private static void slept(ShadowWorld.Bed b, LocalPlayer player) {
        GalaxySave g = galaxy;
        if (g == null) return;
        for (PlanetSession s : planets())
            if (s.active() && s.planet() == b.planet()) {
                Vector3d d = s.galOf(CellSpace.point(s.planet().grid, b.cell(), 0.5, 1, 0.5)).sub(s.center());
                GalaxySave.Spot spot = saved(new GalaxySave.Spot(indexOf(s), d.x, d.y, d.z, player.getYRot(), player.getXRot()));
                saver.execute(() -> {
                    try {
                        g.writeBed(spot);
                    } catch (IOException e) {
                        GalaxyCraft.LOG.warn("Could not save the bed slept in: {}", e.toString());
                    }
                });
                return;
            }
    }

    /**
     * Where Mario lands after dying: on the last bed slept in, if it is still there (as Minecraft's
     * respawn); else where the player last stood.
     */
    private static GalaxySave.Spot respawnSpot() {
        GalaxySave g = galaxy;
        if (g == null) return null;
        GalaxySave.Spot bed = resolved(g.bed().orElse(null));
        if (bed != null)
            for (PlanetSession s : planets())
                if (s.active() && indexOf(s) == bed.planet()) {
                    Vector3d top = s.localOf(new Vector3d(bed.dx(), bed.dy(), bed.dz()).add(s.center()));
                    int under = s.planet().grid.cellAt(new Vector3d(top).normalize(top.length() - 0.5));
                    String there = under < 0 ? "" : blocks.name(s.planet().get(under));
                    if (there.contains("_bed[") || there.startsWith("minecraft:respawn_anchor[") && !there.contains("charges=0")) return bed;
                    saver.execute(() -> {
                        try {
                            g.clearBed();
                        } catch (IOException e) {
                            GalaxyCraft.LOG.warn("Could not forget the bed: {}", e.toString());
                        }
                    });
                    Minecraft.getInstance().player.sendSystemMessage(Component.translatable("block.minecraft.spawn.not_valid"));
                    return resolved(g.spot().orElse(null));
                }
        return bed != null ? bed : resolved(g.spot().orElse(null));
    }

    /**
     * Ticks Mario must stay at a planet's core before he is rescued: right after a teleport he
     * still waits at the galaxy's center (the home planet's) for the game to apply it.
     */
    private static final int AT_CORE_TICKS = 40;
    private static int atCore;

    /**
     * Mario stuck at a planet's core (a teleport the game never applied, or a fall through it) is
     * landed on its ground again, as P does, once he has been there AT_CORE_TICKS.
     */
    private static void rescue() {
        PlanetSession stuck = null;
        for (PlanetSession s : planets())
            if (s.active() && s.marioAtCore() && !s.teleportQueued()) stuck = s;
        atCore = stuck == null || waitingToLand() ? 0 : atCore + 1;
        if (atCore < AT_CORE_TICKS) return;
        atCore = 0;
        GalaxyCraft.LOG.warn("Mario stuck at the core of planet {}: landed on its ground again", indexOf(stuck));
        focus = stuck;
        land(stuck);
    }

    /** Particles alive on the planet (tests). */
    public static int particleCount() {
        return entities.particleCount();
    }

    /** The mob a look from eye (planet space) would hit (tests). */
    public static Entity aimedFrom(Vector3d eye, Vector3d look) {
        return entities.aimed(eye, look, REACH);
    }

    /** Items lying on the planet (tests). */
    public static int dropCount() {
        return drops.all().size();
    }

    /**
     * Render thread, once per emulated frame: the planet's entities to the game, and Mario's seat
     * if he rides. Minecraft movement: walker is where the player's feet are (galaxy), Mario's seat
     * every frame; drawSelf, if set, the frame the player is drawn in as one of the entities;
     * marioFlies: Mario is shown in his flight pose there (the elytra, in Mario's modes).
     */
    public static void frame(BridgeClient bridge, float partialTick, Vector3d walker, GravityFrame drawSelf,
            boolean marioFlies) {
        bridge.world().ifPresent(w -> entities.frame(bridge, w.sceneId(), w.queryPos(), partialTick, drawSelf));
        ShadowWorld.Seat s = ShadowWorld.seat();
        PlanetSession f = focus;
        boolean onSeat = s != null && f.active() && s.planet() == f.planet();
        boolean on = onSeat || walker != null;
        if (on || riding) {
            Vector3d at = GameOrigin.toGame(walker != null ? walker : onSeat ? f.galOf(s.pos()) : GameOrigin.offset());
            if (bridge.send(Layout.MSG_SEAT, java.nio.ByteBuffer.allocate(16).putFloat((float) at.x).putFloat((float) at.y)
                    .putFloat((float) at.z).putInt(!on ? 0 : walker != null && marioFlies ? 2 : 1).array()))
                riding = on;
        }
    }

    /** Extra planets removed whose GONE has not reached the game yet. */
    private static final java.util.List<PlanetSession> leaving = new java.util.ArrayList<>();

    /** Mario sat on something last frame (the game is told once when he gets off). */
    private static boolean riding;

    /** Saves this stage's planet, drops it and loads it back from disk (end-to-end tests). */
    public static void reloadFromDisk() throws Exception {
        saveNow();
        saver.submit(() -> {}).get(); // the save is written
        String s = stage;
        stage = null;
        enterStage(s);
    }

    /** A new stage: the old one's planet is saved and dropped, this one's loaded if it has one. */
    private static void enterStage(String next) {
        saveNow();
        StationClient.unloadAll();
        session.unload();
        for (Extra e : extras) e.s().unload();
        extras.clear();
        leaving.clear(); // the new scene never had them
        focus = session;
        stage = next;
        autoSpawn = false;
        if (next.isEmpty() || store == null) return;
        if (galaxy != null && Layout.SPACE_STAGE.equals(next)) {
            hadSaved = true; // the galaxy's catalog says what there is (GalaxyStream)
            return;
        }
        java.util.List<Integer> saved = store.saved(next, PlanetLayout.MAX_PLANETS);
        hadSaved = !saved.isEmpty();
        for (int index : saved) {
            try {
                Optional<PlanetStore.Saved> s = store.read(PlanetStore.key(next, index), blocks);
                if (s.isEmpty()) continue;
                PlanetSession p = index == 0 ? session : new PlanetSession(1 / GravityFrame.SCALE);
                p.setBlocks(blocks);
                p.load(s.get());
                if (index != 0) extras.add(new Extra(p, index));
                GalaxyCraft.LOG.info("Voxel planet {} of {} loaded", index, next);
            } catch (IOException e) {
                GalaxyCraft.LOG.warn("Could not load planet {} of {}: {}", index, next, e.toString());
            }
        }
        // Levels only: not the title, the file select or the world map.
        if (saved.isEmpty()) autoSpawn = autoRadius > 0 && next.endsWith("Galaxy");
    }

    private static void saveNow() {
        PlanetStore store = PlanetClient.store; // the saves run later, maybe after another world's is in
        if (stage == null || stage.isEmpty() || store == null) return;
        for (PlanetSession p : planets()) {
            if (!p.unsaved()) continue;
            PlanetStore.Saved s = p.save();
            String where = PlanetStore.key(stage, indexOf(p));
            McBlocks b = blocks;
            saver.execute(() -> {
                try {
                    store.write(where, s, b);
                } catch (IOException e) {
                    GalaxyCraft.LOG.warn("Could not save the planet {}: {}", where, e.toString());
                }
            });
        }
    }

    /** A world was entered: its galaxy's planets are the ones loaded and saved from now on. */
    public static void enterWorld(GalaxySave g) {
        if (FIXED_DIR) return;
        galaxy = g;
        store = new PlanetStore(g.planets());
        StationClient.enterWorld(g.planets());
        landOn = resolved(g.spot().orElse(null));
        landPending = true;
        homeAsked = false;
        hadSaved = false;
        sinceSpot = 0;
        unlandedSince = 0;
        stage = null; // loaded at the next tick, from this world's folder
    }

    /**
     * The world is left: where the player stands and its planets are saved, and the game drops
     * them (Mario then waits in GalaxyCraftSpace for the next world).
     */
    public static void leaveWorld(BridgeClient bridge) {
        if (FIXED_DIR || galaxy == null) return;
        writeSpot(Minecraft.getInstance().player);
        saveNow();
        StationClient.enterWorld(null); // saved, dropped with the galaxy
        if (stream != null) stream.clear();
        stream = null;
        session.unload();
        for (Extra e : extras) e.s().unload();
        extras.clear();
        leaving.clear();
        focus = session;
        dropAllPending = !bridge.send(Layout.MSG_PLANET, PlanetSession.dropAll());
        UniverseClient.leave(bridge);
        galaxy = null;
        store = null;
        stage = null;
        landPending = false;
        generating = null;
        generated = null;
        spawnBlueprint = null;
        spawnRadius = 0;
        placement = Placement.REPLACE;
    }

    /**
     * Planets near Mario in the game, of those wanted (done, total): the planets made and with
     * everything sent, including the one he lands on. Total 0 while none is known yet.
     */
    public static int[] loadProgress() {
        int done = 0, total = 0;
        if (stream != null) {
            int[] n = stream.nearLoaded();
            done = n[0];
            total = n[1];
        }
        for (PlanetSession p : planets())
            if (p.active() && p.queued() > 0) total = Math.max(total, done + 1);
        return new int[] {done, total};
    }

    /** Entering a world in GalaxyCraftSpace, Mario not on its planet yet: the player waits for him. */
    public static boolean waitingToLand() {
        return galaxy != null && landPending;
    }

    /** The game told to drop every planet, once the ring has room (it was full when the world was left). */
    private static boolean dropAllPending;

    /** Every client tick, also out of a world: a drop-everything that did not fit in the ring goes now. */
    public static void flushDropAll(BridgeClient bridge) {
        if (dropAllPending && bridge.send(Layout.MSG_PLANET, PlanetSession.dropAll())) dropAllPending = false;
    }

    /** The world's galaxy, while one is entered (null otherwise, and in game tests). */
    public static GalaxySave galaxy() {
        return galaxy;
    }

    /** Mario onto the saved spot's planet, the way he stood there; or onto the first planet's top. */
    private static void landOnSpot(LocalPlayer player) {
        PlanetSession on = null;
        if (landOn != null)
            for (PlanetSession p : planets())
                if (p.active() && indexOf(p) == landOn.planet()) on = p;
        // A galaxy's planet being made (it is far from where Mario waits): waited for, not another one.
        if (on == null && landOn != null && stream != null && stream.entry(landOn.planet()).isPresent()
                && !stream.failed(landOn.planet())) return;
        Vector3d dir = on != null ? new Vector3d(landOn.dx(), landOn.dy(), landOn.dz()) : new Vector3d(0, 1, 0);
        if (on == null) on = planets().stream().filter(PlanetSession::active).findFirst().orElse(null);
        if (on == null || on.queued() > 0) return; // the planet still being made, or on its way to the game
        Vector3d at = on.teleportToward(dir);
        if (at == null) return;
        focus = on;
        Flight.end(player);
        if (GalaxyCraftClient.walking()) GalaxyCraftClient.moveTo(on.galOf(at));
        landPending = false;
        GalaxyCraft.LOG.info("Entered the world's galaxy: Mario onto planet {} toward {}", indexOf(on), dir);
    }

    /** Where the player stands, into the world's galaxy (only on a planet: in the void the last one stays). */
    private static void writeSpot(LocalPlayer player) {
        GalaxySave g = galaxy;
        PlanetSession on = standingOn();
        Vector3d feet = GalaxyCraftClient.galaxyPos().orElse(null);
        if (g == null || on == null || feet == null || player == null) return;
        Vector3d d = new Vector3d(feet).sub(on.center());
        GalaxySave.Spot spot = saved(new GalaxySave.Spot(indexOf(on), d.x, d.y, d.z, player.getYRot(), player.getXRot()));
        saver.execute(() -> {
            try {
                g.writeSpot(spot);
            } catch (IOException e) {
                GalaxyCraft.LOG.warn("Could not save where the player stands: {}", e.toString());
            }
        });
    }

    /** A catalog planet's session, if it has one (complete or not). */
    /** The galaxy's planets not in the game yet (its catalog's far ones): their gravity, in blocks. */
    static java.util.List<dev.moui.galaxycraft.gravity.GravityBody> farBodies() {
        java.util.List<dev.moui.galaxycraft.gravity.GravityBody> out = new java.util.ArrayList<>();
        if (stream == null) return out;
        for (GalaxyCatalog.Entry e : stream.entries()) {
            PlanetSession s = sessionOf(e.index());
            if (s == null || !s.active()) out.add(new dev.moui.galaxycraft.gravity.GravityBody.Sphere(
                    new Vector3d(e.center()).mul(GravityFrame.SCALE), PlanetSession.gravityRadius(e.radius())));
        }
        return out;
    }

    static PlanetSession sessionOf(int index) {
        if (index == 0) return session;
        for (Extra e : extras) if (e.index() == index) return e.s();
        return null;
    }

    /** The session a catalog planet becomes complete in: its own, or a new one. */
    static PlanetSession claim(int index) {
        PlanetSession s = sessionOf(index);
        if (s != null) return s;
        s = new PlanetSession(1 / GravityFrame.SCALE);
        s.setBlocks(blocks);
        extras.add(new Extra(s, index));
        return s;
    }

    /** A catalog planet no longer complete: the game drops it (its far view takes its place). */
    static void release(int index) {
        PlanetSession s = sessionOf(index);
        if (s == null) return;
        s.remove();
        if (s != session) {
            extras.removeIf(e -> e.s() == s);
            leaving.add(s);
        }
        if (focus == s) focus = session;
    }

    /** One planet saved now if it was edited (it is about to go). */
    static void saveOne(PlanetSession p) {
        PlanetStore store = PlanetClient.store;
        if (stage == null || stage.isEmpty() || store == null || !p.unsaved()) return;
        PlanetStore.Saved s = p.save();
        String where = PlanetStore.key(stage, indexOf(p));
        McBlocks b = blocks;
        saver.execute(() -> {
            try {
                store.write(where, s, b);
            } catch (IOException e) {
                GalaxyCraft.LOG.warn("Could not save the planet {}: {}", where, e.toString());
            }
        });
    }

    /** Runs on the saver's thread, after the saves asked for before. */
    static void saveLater(Runnable r) {
        saver.execute(r);
    }

    /** Complete planets and far ones in the game (end-to-end tests); {0, 0} without a galaxy. */
    public static int[] tiers() {
        return stream == null ? new int[2] : stream.tiers();
    }

    /** Mario onto that catalog planet (end-to-end tests): it is made complete first, as when entering the world there. */
    public static void travelTo(int index) {
        landOn = new GalaxySave.Spot(index, 0, 1, 0, 0, 0);
        landPending = true;
    }

    /** Every planet streamed (complete or far), its gravity: universe units. */
    static java.util.List<PlanetLayout.Sphere> streamedSpheres() {
        return stream == null ? java.util.List.of() : stream.spheres();
    }

    /** Where that planet's file is (end-to-end tests); null out of a world. */
    public static java.nio.file.Path planetFile(int index) {
        return store == null || stage == null ? null : store.file(PlanetStore.key(stage, index));
    }

    /** That planet's entry, the world's galaxy's or a generated system's (end-to-end tests). */
    public static java.util.Optional<GalaxyCatalog.Entry> entry(int index) {
        return stream == null ? java.util.Optional.empty() : stream.all().stream().filter(e -> e.index() == index).findFirst();
    }

    /** The galaxy's catalog (end-to-end tests); empty without one. */
    public static java.util.List<GalaxyCatalog.Entry> catalog() {
        return stream == null ? java.util.List.of() : java.util.List.copyOf(stream.entries());
    }

    /**
     * The world's galaxy, on its first tick in GalaxyCraftSpace: its galaxy.json; or, for a world
     * from before catalogs, one from its planet files; or a new one from the Create World tab's
     * options (or the defaults) and the world's seed.
     */
    private static void startGalaxy(LocalPlayer player) {
        GalaxySave g = galaxy;
        GalaxySave.Galaxy made = g.galaxy().orElse(null);
        if (made == null) {
            if (!store.saved(stage, GalaxyCatalog.MAX).isEmpty()) made = g.fromFiles(store, stage);
            else made = newGalaxy(player);
            GalaxySave.Galaxy write = made;
            saver.execute(() -> {
                try {
                    g.writeGalaxy(write);
                } catch (IOException e) {
                    GalaxyCraft.LOG.warn("Could not save the galaxy: {}", e.toString());
                }
            });
        }
        double reach = 0; // the world's galaxy's own reach: generated systems keep away from it
        for (GalaxyCatalog.Entry e : made.entries())
            reach = Math.max(reach, e.center().length() * GravityFrame.SCALE + PlanetSession.gravityRadius(e.radius()));
        dev.moui.galaxycraft.universe.Universe universe = new dev.moui.galaxycraft.universe.Universe(made.options().seed(),
                1 / GravityFrame.SCALE, made.layout()).withHome(reach);
        stream = new GalaxyStream(g, store, stage, made, ENDLESS ? universe : null);
        UniverseClient.enter(universe);
        GalaxyCraft.LOG.info("The world's galaxy: {} planets", made.entries().size());
    }

    private static GalaxySave.Galaxy newGalaxy(LocalPlayer player) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        long seed = server == null ? 0 : server.getWorldGenSettings().options().seed();
        GalaxyCatalog.Options asked = PendingGalaxy.take().orElse(GalaxyCatalog.Options.defaults(seed));
        GalaxyCatalog.Options o = new GalaxyCatalog.Options(asked.count(), asked.minRadius(), asked.maxRadius(), asked.first(),
                asked.spacing(), seed).clamp();
        int firstRadius = o.first().radius();
        if (o.first().isBlueprint()) {
            PlanetBlueprint bp = null;
            try {
                bp = blueprints.read(o.first().name()).orElse(null);
            } catch (IOException e) {
                GalaxyCraft.LOG.warn("Could not read blueprint {}: {}", o.first().name(), e.toString());
            }
            if (bp == null) {
                say(player, "The blueprint " + o.first().name() + " is gone: the first planet is generated instead");
                o = new GalaxyCatalog.Options(o.count(), o.minRadius(), o.maxRadius(), GalaxyCatalog.First.generated("random", 48),
                        o.spacing(), seed);
                firstRadius = 48;
            } else firstRadius = bp.radius();
        }
        GalaxyCatalog.Result r = GalaxyCatalog.make(o, firstRadius, dev.moui.galaxycraft.voxel.gen.LegacyBiome.land(), 1 / GravityFrame.SCALE,
                GalaxyCatalog.LAYOUT);
        if (r.placed() < r.asked()) say(player, r.placed() + " of " + r.asked() + " planets fit in the galaxy");
        return new GalaxySave.Galaxy(GalaxyCatalog.LAYOUT, o, r.entries());
    }

    /**
     * Where planets are saved: -Dgalaxycraft.planetDir, else $XDG_DATA_HOME/galaxycraft/planets
     * (~/.local/share/...), %APPDATA%\galaxycraft\planets on Windows (PlanetStore.dataDir). Not
     * the game directory: the client game tests that tools/gxplay.sh runs Minecraft through start
     * from a clean one every time.
     */
    static java.nio.file.Path planetDir() {
        String prop = System.getProperty("galaxycraft.planetDir");
        if (prop != null && !prop.isEmpty()) return java.nio.file.Path.of(prop);
        return PlanetStore.dataDir(System.getProperty("os.name", ""), System::getenv, System.getProperty("user.home"))
                .resolve("planets");
    }

    private static int autoRadius(String prop) {
        if (prop == null || prop.equals("false")) return 0;
        if (prop.equals("true")) return DEFAULT_RADIUS;
        try {
            return Integer.parseInt(prop);
        } catch (NumberFormatException e) {
            return DEFAULT_RADIUS;
        }
    }

    private static boolean key(Optional<Seqlock.InputState> in, int usage) {
        return in.map(i -> (i.keys()[usage / 8] >> (usage % 8) & 1) != 0).orElse(false);
    }

    /** Ticks between uses while the right button is held (Minecraft's rightClickDelay). */
    private static final int USE_REPEAT = 4;
    private static int useDelay;

    private static boolean pressed(int buttons, int mask) {
        return (buttons & mask) != 0 && (lastButtons & mask) == 0;
    }

    /**
     * Right click, as in Minecraft: with the main hand, then, if that does nothing at all, with
     * the off hand (torches in the off hand go down while a pickaxe is in the main one).
     */
    private static void use(LocalPlayer player, Vector3d eye, Vector3d look, Vector3d feet, PlanetSession.Aim aim) {
        use(player, InteractionHand.MAIN_HAND, eye, look, feet, aim, () -> {
            // Minecraft uses the main hand's item in the air by itself (eats, throws, draws a bow):
            // then that was the click, and the off hand stays out of it.
            if (!usesInAir(player.getMainHandItem()))
                use(player, InteractionHand.OFF_HAND, eye, look, feet, aim, () -> {});
        });
    }

    /**
     * A right click with what is in hand: a bucket pours or fills (and turns into the other one,
     * outside creative mode, as in Minecraft); otherwise the block aimed at is used, or the held
     * item on it, and if neither does anything a block item is placed. If none of it happens,
     * next runs.
     */
    private static void use(LocalPlayer player, InteractionHand hand, Vector3d eye, Vector3d look, Vector3d feet,
            PlanetSession.Aim aim, Runnable next) {
        ItemStack stack = player.getItemInHand(hand);
        boolean creative = player.getAbilities().instabuild;
        if (stack.is(Items.BUCKET)) {
            Material got = focus.scoop(eye, look);
            if (got == null) next.run();
            else if (!creative) setHand(player, hand, got == Material.WATER ? Items.WATER_BUCKET : Items.LAVA_BUCKET);
        } else if (stack.is(Items.WATER_BUCKET) || stack.is(Items.LAVA_BUCKET)) {
            if (!focus.pour(eye, look, stack.is(Items.WATER_BUCKET) ? Material.WATER : Material.LAVA)) next.run();
            else if (!creative) setHand(player, hand, Items.BUCKET);
        } else {
            ItemStack held = stack.copy();
            Runnable place = () -> {
                if (!focus.placeBlock(eye, look, blocks.placer(held, hand), feet)) {
                    next.run();
                    return;
                }
                if (!StationClient.afterPlace(focus, focus.lastPlacedCells())) return; // past a station's limits: taken back
                placed(focus);
                int cell = focus.lastPlaced();
                if (cell >= 0 && shadow.available()
                        && blocks.state(focus.planet().get(cell)).getBlock() instanceof net.minecraft.world.level.block.SignBlock)
                    ShadowWorld.placedSign(focus.planet(), cell, player.getUUID());
                if (!creative) useUp(player, hand, held);
            };
            if (aim == null || !shadow.available()) place.run();
            else ShadowWorld.use(focus.planet(), aim.cell(), aim.face(),
                    new net.minecraft.world.phys.Vec3(aim.hit().x, aim.hit().y, aim.hit().z), player.getUUID(), hand, place);
        }
    }

    /** By item class: whether it overrides Item.use (a bow, a pearl, a potion). */
    private static final ClassValue<Boolean> OWN_USE = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> c) {
            try {
                return c.getMethod("use", net.minecraft.world.level.Level.class,
                        net.minecraft.world.entity.player.Player.class, InteractionHand.class).getDeclaringClass() != Item.class;
            } catch (NoSuchMethodException e) {
                return false;
            }
        }
    };

    /**
     * Whether a right click in the air does something with stack, as Item.use and its overrides
     * do: food and potions, armor put on, a shield raised, a spear, a bow, a thrown pearl. Not
     * buckets: the planet's are poured and filled here, and with nothing to do they pass.
     */
    static boolean usesInAir(ItemStack stack) {
        if (stack.isEmpty() || stack.getItem() instanceof net.minecraft.world.item.BlockItem
                || stack.getItem() instanceof net.minecraft.world.item.BucketItem) return false;
        if (stack.has(DataComponents.CONSUMABLE) || stack.has(DataComponents.BLOCKS_ATTACKS)
                || stack.has(DataComponents.KINETIC_WEAPON)) return true;
        var equippable = stack.get(DataComponents.EQUIPPABLE);
        return equippable != null && equippable.swappable() || OWN_USE.get(stack.getItem().getClass());
    }

    /**
     * The attack button on the block aimed at (aim, null for none) this tick: Mining's breaking at
     * the speed Minecraft gives the block and what is in hand, with Minecraft's feel: the hit
     * sound every 4 ticks, a piece of the block flying off the side hit every tick, its cracks
     * growing (drawn by the game), its break sound and pieces when it goes.
     */
    private static void mine(LocalPlayer player, PlanetSession s, Vector3d eye, Vector3d look, PlanetSession.Aim aim,
            boolean pressed, boolean held) {
        if (++sinceBroke > BROKE_TICKS) brokeCell = -1;
        int cell = aim == null ? -1 : aim.cell();
        int id = cell < 0 ? -1 : s.planet().get(cell);
        if (cell >= 0 && cell == brokeCell && id == brokeId) cell = -1; // broken, on its way out
        net.minecraft.world.level.block.state.BlockState state = cell < 0 ? null : blocks.state(id);
        double perTick = state == null || !s.planet().info(cell).breakable() ? 0 : destroyProgress(player, state);
        Mining.Step step = mining.tick(pressed, held, cell, id, player.getMainHandItem().getItem(), perTick,
                player.getAbilities().instabuild);
        s.setCrack(step.crack(), step.stage(), step.stage() >= 0 ? blocks.crackUv(step.stage()) : null);
        if (state == null) return;
        net.minecraft.world.level.block.SoundType sound = state.getSoundType();
        if (step.working()) {
            player.swing(InteractionHand.MAIN_HAND, player.getMainHandItem().getAttackAnimation(), false);
            entities.particles().crack(s.planet(), cell, aim.face(), state);
        }
        if (step.hitSound()) blockSound(s, cell, sound.getHitSound(), (sound.getVolume() + 1) / 8, sound.getPitch() * 0.5f);
        if (step.broke() >= 0) {
            blockSound(s, cell, sound.getBreakSound(), (sound.getVolume() + 1) / 2, sound.getPitch() * 0.8f);
            // With Minecraft running the planet its pieces come from the shadow (level event 2001).
            if (!shadow.available()) entities.particles().burst(s.planet(), cell, state);
            brokeCell = cell;
            brokeId = id;
            sinceBroke = 0;
            breakBlock(player, eye, look, aim);
        }
    }

    /**
     * How much of the block a tick of the button breaks: Minecraft's BlockState.getDestroyProgress
     * (the tool's speed, Efficiency, Haste and Mining Fatigue against its hardness; a third as fast
     * without the right tool), save that Mario's jumps and flights do not slow it: Minecraft breaks
     * five times slower off the ground, and here Mario is in the air half the time.
     */
    private static double destroyProgress(LocalPlayer player, net.minecraft.world.level.block.state.BlockState state) {
        float hardness = state.getDestroySpeed(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO);
        if (hardness < 0) return 0;
        float speed = player.getDestroySpeed(state);
        if (!player.onGround()) speed *= 5;
        return speed / hardness / (player.hasCorrectToolForDrops(state) ? 30 : 100);
    }

    /** A block's sound where its cell is (Minecraft's coordinates of the player's frame), as Minecraft plays them. */
    private static void blockSound(PlanetSession s, int cell, net.minecraft.sounds.SoundEvent sound, float volume, float pitch) {
        net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
        if (level == null || frameNow == null) return;
        Vector3d at = frameNow.toMc(s.galOf(s.planet().grid.center(cell)));
        level.playLocalSound(at.x, at.y, at.z, sound, net.minecraft.sounds.SoundSource.BLOCKS, volume, pitch, false);
    }

    /** A block placed (by the planet, not by Minecraft): its place sound, and a few of its pieces puff off it. */
    private static void placed(PlanetSession s) {
        int cell = s.lastPlaced();
        if (cell < 0 || !s.active()) return;
        net.minecraft.world.level.block.state.BlockState state = blocks.state(s.planet().get(cell));
        if (state.isAir()) return;
        net.minecraft.world.level.block.SoundType sound = state.getSoundType();
        blockSound(s, cell, sound.getPlaceSound(), (sound.getVolume() + 1) / 2, sound.getPitch() * 0.8f);
        entities.particles().puff(s.planet(), cell, state);
    }

    /**
     * Left click: with Minecraft running the planet, its own breaking (drops outside creative, the
     * tool worn); otherwise the block just goes.
     */
    private static void breakBlock(LocalPlayer player, Vector3d eye, Vector3d look, PlanetSession.Aim aim) {
        if (aim != null && StationClient.isCore(focus, aim.cell())) return;
        if (!shadow.available()) focus.breakBlock(eye, look, player.getAbilities().instabuild);
        else if (aim != null && focus.planet().info(aim.cell()).breakable())
            ShadowWorld.destroy(focus.planet(), aim.cell(), player.getUUID());
    }

    /** A block placed outside creative: one fewer of it in hand (on the server, which syncs back). */
    private static void useUp(LocalPlayer player, InteractionHand hand, ItemStack placed) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return;
        java.util.UUID id = player.getUUID();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(id);
            if (sp != null && sp.getItemInHand(hand).is(placed.getItem())) sp.getItemInHand(hand).shrink(1);
        });
    }

    /** The inventory is the integrated server's: the item changes there and syncs back. */
    private static void setHand(LocalPlayer player, InteractionHand hand, Item item) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return;
        java.util.UUID id = player.getUUID();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(id);
            if (sp != null) sp.setItemInHand(hand, new ItemStack(item));
        });
    }

    private static Vector3d vec(net.minecraft.world.phys.Vec3 v) {
        return new Vector3d(v.x, v.y, v.z);
    }
}
