package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.voxel.VoxelPlanet;
import dev.moui.galaxycraft.voxel.gen.PlanetGenerator;
import dev.moui.galaxycraft.voxel.gen.TerrainNoise;
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
import dev.moui.galaxycraft.voxel.PlanetSession;
import dev.moui.galaxycraft.voxel.BlueprintStore;
import dev.moui.galaxycraft.voxel.PlanetBlueprint;
import dev.moui.galaxycraft.voxel.PlanetLayout;
import dev.moui.galaxycraft.voxel.PlanetStore;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
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
 * while something is in the main hand and no screen is open (Dolphin then keeps them from Mario), P
 * to land on the planet, and the messages that carry it to the game, the block atlas first. Up to
 * PlanetLayout.MAX_PLANETS planets per stage (galaxy), each saved in ~/.local/share/galaxycraft/planets
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
    /** Ticks between saves of an edited planet. */
    private static final int SAVE_TICKS = 200;
    /** The stage's first planet (index 0): the same session all along, which tests hold on to. */
    private static final PlanetSession session = new PlanetSession(1 / GravityFrame.SCALE);
    /** The stage's other planets, each with the index of its file (PlanetStore.key). */
    private record Extra(PlanetSession s, int index) {}
    private static final java.util.List<Extra> extras = new java.util.ArrayList<>();
    /** The planet nearest Mario (session when there is none). */
    private static PlanetSession focus = session;
    private static boolean spawnAdds; // the next spawn adds a planet instead of replacing the one in focus
    private static final PlanetStore store = new PlanetStore(planetDir());
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
    private static java.util.concurrent.CompletableFuture<PlanetGenerator.Cells> generating; // off the game's thread
    private static VoxelPlanet generated; // non-null: generated, spawn next tick
    private static McWorldgen worldgen;
    private static MinecraftServer worldgenServer;
    static final BlueprintStore blueprints = new BlueprintStore(planetDir().resolveSibling("blueprints"));
    private static int lastButtons;
    private static boolean lastP;
    private static int sinceSave;
    private static final ShadowLink shadow = new ShadowLink(() -> focus);
    private static boolean aimUsable; // an empty hand aims at a block a click uses
    private static final DropsClient drops = new DropsClient(() -> focus);
    private static final EntityClient entities = new EntityClient(() -> focus, drops);
    private static McBlocks blocks;
    private static AtlasLink atlasLink;

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
        McWorldgen gen = worldgen();
        if (gen == null) {
            say(player, "Generated planets need a single player world");
            return;
        }
        java.util.Map<String, Integer> known = new java.util.concurrent.ConcurrentHashMap<>();
        for (String b : PlanetGenerator.blocks()) known.put(b, blocks.parse(b));
        // Trees bring states of their own: those are looked up on this thread too, as they come.
        java.util.function.ToIntFunction<String> ids = name -> known.computeIfAbsent(name,
                n -> Minecraft.getInstance().submit(() -> blocks.parse(n)).join());
        TerrainNoise noise = gen.noise(bp.seed());
        say(player, "Generating " + bp.name() + "...");
        generating = java.util.concurrent.CompletableFuture.supplyAsync(() -> PlanetGenerator.cells(bp, noise, gen.biomes(), gen.vegetation(), ids));
    }

    private static void say(LocalPlayer player, String text) {
        if (player != null) player.sendSystemMessage(Component.literal("GalaxyCraft: " + text));
    }

    /** Minecraft's blocks for the planets, once its models are loaded (null before the first tick). */
    static McBlocks blocks() {
        return blocks;
    }

    /** Places a block item as a right click would (end-to-end tests). */
    public static boolean placeItem(Vector3d eyeGal, Vector3d lookGal, ItemStack stack, Vector3d marioFeetGal) {
        return blocks != null && focus.placeBlock(eyeGal, lookGal, blocks.placer(stack), marioFeetGal);
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

    private static int indexOf(PlanetSession s) {
        for (Extra e : extras) if (e.s() == s) return e.index();
        return 0;
    }

    public static boolean itemActive(LocalPlayer player) {
        return player != null && (!player.getMainHandItem().isEmpty() || aimUsable);
    }

    /** /galaxycraft planet spawn [radius]: next tick, above the player, replacing the one in focus. */
    public static void requestSpawn(int radius) {
        spawnRadius = radius;
        spawnAdds = false;
    }

    /** A planet built from that blueprint, next tick, as {@link #requestSpawn(int)} puts one. */
    public static void requestSpawn(PlanetBlueprint blueprint) {
        spawnBlueprint = blueprint;
        spawnAdds = false;
    }

    /** /galaxycraft planet add [radius]: next tick, another planet (PlanetLayout.place), the others kept. */
    public static void requestAdd(int radius) {
        spawnRadius = radius;
        spawnAdds = true;
    }

    /** Another planet built from that blueprint, as {@link #requestAdd(int)} puts one. */
    public static void requestAdd(PlanetBlueprint blueprint) {
        spawnBlueprint = blueprint;
        spawnAdds = true;
    }

    /** Mario onto the planet in focus. */
    public static void teleport() {
        focus.teleport();
    }

    /** Removes the planet in focus, from the game and from disk. */
    public static void remove() {
        PlanetSession gone = focus;
        int index = indexOf(gone);
        gone.remove();
        if (gone != session) extras.removeIf(e -> e.s() == gone);
        focus = session;
        String s = stage;
        if (s != null && !s.isEmpty()) saver.execute(() -> {
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
            out.append(out.isEmpty() ? "" : "; ").append(String.format("%splanet of radius %.0f%s at (%.0f, %.0f, %.0f), %d to send, %d chunks with collision%s",
                    p == focus ? "* " : "", p.planet().surface(), where, c.x, c.y, c.z, p.queued(), p.collisionChunks(),
                    p.detail() ? "" : ", far view only"));
        }
        return out.isEmpty() ? "no planet" + where : out.toString();
    }

    /**
     * Puts p up: replacing the planet in focus (or the first slot), or with spawnAdds as another
     * one, where PlanetLayout finds room for its gravity among the others'. It is then in focus.
     */
    private static void spawnPlanet(VoxelPlanet p, Vector3d feet, Vector3d up, LocalPlayer player) {
        java.util.List<PlanetSession> all = planets();
        PlanetSession target = !spawnAdds || !session.active() ? (focus.active() ? focus : session) : null;
        if (target == null && all.stream().filter(PlanetSession::active).count() >= PlanetLayout.MAX_PLANETS) {
            say(player, "A stage holds " + PlanetLayout.MAX_PLANETS + " planets at most");
            return;
        }
        java.util.List<PlanetLayout.Sphere> others = new java.util.ArrayList<>();
        for (PlanetSession s : all) if (s.active() && s != target) others.add(new PlanetLayout.Sphere(s.center(), s.gravityUnits()));
        double units = 1 / GravityFrame.SCALE;
        Vector3d c = PlanetLayout.place(others, PlanetSession.gravityRadius(p.surface()) * units, feet, up, units);
        if (c == null) {
            say(player, "No room for another planet here");
            return;
        }
        if (target == null) {
            target = new PlanetSession(units);
            target.setBlocks(blocks);
            extras.add(new Extra(target, freeIndex()));
        }
        target.spawnAt(p, c);
        focus = target;
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
        for (PlanetSession p : planets()) {
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
            blocks = McBlocks.create(Minecraft.getInstance());
            atlasLink = new AtlasLink(blocks.atlas, 1);
            session.setBlocks(blocks);
            for (Extra e : extras) e.s().setBlocks(blocks);
        }
        for (byte[] piece; (piece = atlasLink.peek(world.sceneId(), bridge.hostPid())) != null
                && bridge.send(Layout.MSG_ATLAS, piece); ) atlasLink.sent();
        if (!bridge.stage().equals(stage)) enterStage(bridge.stage());
        if (spawnBlueprint != null && spawnBlueprint.mode() == PlanetBlueprint.Mode.GENERATED) {
            if (generating == null) generate(spawnBlueprint, player);
            spawnBlueprint = null;
        }
        if (generating != null && generating.isDone()) {
            try {
                generated = generating.join().planet(blocks);
            } catch (RuntimeException e) {
                GalaxyCraft.LOG.warn("Could not generate the planet: {}", e.toString());
                say(player, "Could not generate the planet: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
            }
            generating = null;
        }
        if (frame != null && world.hasGravity() && (spawnRadius > 0 || spawnBlueprint != null || generated != null || (autoSpawn && world.follow()))) {
            VoxelPlanet p = generated != null ? generated : spawnBlueprint != null ? spawnBlueprint.build(blocks)
                    : VoxelPlanet.ofRadius(spawnRadius > 0 ? spawnRadius : autoRadius, blocks);
            spawnPlanet(p, world.queryPos(), frame.upGal(), player);
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
        PlanetSession was = focus;
        focus = nearest(world.queryPos());
        if (focus != was) was.setOutline(-1);
        for (PlanetSession s : planets())
            if (s.active() && world.queryPos() != null)
                s.setDetail(PlanetLayout.detail(s.detail(), s == focus, s.center().distance(world.queryPos()), s.gravityUnits(),
                        1 / GravityFrame.SCALE));
        PlanetSession session = focus; // the clicks, the outline and Minecraft's running of the blocks are its
        if (session.active() && frame != null && player != null) {
            if (p && !lastP && !screen) session.teleport();
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
            session.setOutline(item && target == null ? session.target(eye, look, player.getMainHandItem().is(Items.BUCKET)) : -1);
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
            if (item) {
                // Minecraft gets the same clicks and swings the arm by itself.
                if (pressed(buttons, MOUSE_LEFT) && !hit) breakBlock(player, eye, look, aim);
                if (pressed(buttons, MOUSE_RIGHT) && !hit) use(player, eye, look, world.queryPos(), aim);
            }
        }
        else ShadowWorld.mario(null);
        shadow.tick(stage == null ? "" : PlanetStore.key(stage, indexOf(focus)), world.queryPos());
        drops.tick(Minecraft.getInstance(), frame, frame == null ? null : world.queryPos());
        entities.tick();
        // The player hurt by the shadow: Mario reels in the game too.
        for (ShadowWorld.Hurt h; (h = ShadowWorld.pollHurt()) != null; )
            if (session.active() && h.planet() == session.planet()) {
                Vector3d from = session.galOf(h.from());
                bridge.send(Layout.MSG_HURT, java.nio.ByteBuffer.allocate(16).putFloat((float) from.x).putFloat((float) from.y)
                        .putFloat((float) from.z).putInt(h.kind()).array());
            }
        lastButtons = buttons;
        lastP = p;
        // The planet in focus first: its chunks before the others' when the ring is full.
        java.util.List<PlanetSession> order = planets();
        order.remove(focus);
        order.addFirst(focus);
        for (PlanetSession s : order) {
            s.update(world.sceneId(), bridge.hostPid(), world.queryPos());
            for (PlanetSession.Msg m; (m = s.peek()) != null && bridge.send(m.type(), m.payload()); ) s.sent();
        }
        if (++sinceSave >= SAVE_TICKS) {
            sinceSave = 0;
            saveNow();
        }
    }

    /** A right click with what is in hand, where the player looks (tests). */
    public static void useHeld(LocalPlayer player, GravityFrame frame, Vector3d marioFeetGal) {
        Vector3d eye = frame.toGal(vec(player.getEyePosition()));
        Vector3d look = frame.dirToGal(LookMath.direction(player.getYRot(), player.getXRot()));
        use(player, eye, look, marioFeetGal, focus.aim(eye, look));
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

    /** Render thread, once per emulated frame: the planet's entities to the game, and Mario's seat if he rides. */
    public static void frame(BridgeClient bridge, float partialTick) {
        bridge.world().ifPresent(w -> entities.frame(bridge, w.sceneId(), w.queryPos(), partialTick));
        ShadowWorld.Seat s = ShadowWorld.seat();
        PlanetSession f = focus;
        boolean on = s != null && f.active() && s.planet() == f.planet();
        if (on || riding) {
            Vector3d at = on ? f.galOf(s.pos()) : new Vector3d();
            if (bridge.send(Layout.MSG_SEAT, java.nio.ByteBuffer.allocate(16).putFloat((float) at.x).putFloat((float) at.y)
                    .putFloat((float) at.z).putInt(on ? 1 : 0).array()))
                riding = on;
        }
    }

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
        session.unload();
        for (Extra e : extras) e.s().unload();
        extras.clear();
        focus = session;
        stage = next;
        autoSpawn = false;
        if (next.isEmpty()) return;
        java.util.List<Integer> saved = store.saved(next, PlanetLayout.MAX_PLANETS);
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
        if (stage == null || stage.isEmpty()) return;
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

    /**
     * Where planets are saved: -Dgalaxycraft.planetDir, else $XDG_DATA_HOME/galaxycraft/planets
     * (~/.local/share/...). Not the game directory: the client game tests that tools/gxplay.sh
     * runs Minecraft through start from a clean one every time.
     */
    static java.nio.file.Path planetDir() {
        String prop = System.getProperty("galaxycraft.planetDir");
        if (prop != null && !prop.isEmpty()) return java.nio.file.Path.of(prop);
        String xdg = System.getenv("XDG_DATA_HOME");
        java.nio.file.Path data = xdg != null && !xdg.isEmpty() ? java.nio.file.Path.of(xdg)
                : java.nio.file.Path.of(System.getProperty("user.home"), ".local", "share");
        return data.resolve("galaxycraft").resolve("planets");
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

    private static boolean pressed(int buttons, int mask) {
        return (buttons & mask) != 0 && (lastButtons & mask) == 0;
    }

    /**
     * Right click: a bucket pours or fills (and turns into the other one, outside creative mode, as
     * in Minecraft); otherwise, as in Minecraft, the block aimed at is used, or the held item on it,
     * and if neither does anything a block item is placed.
     */
    private static void use(LocalPlayer player, Vector3d eye, Vector3d look, Vector3d feet, PlanetSession.Aim aim) {
        ItemStack stack = player.getMainHandItem();
        boolean creative = player.getAbilities().instabuild;
        if (stack.is(Items.BUCKET)) {
            Material got = focus.scoop(eye, look);
            if (got != null && !creative) setMainHand(player, got == Material.WATER ? Items.WATER_BUCKET : Items.LAVA_BUCKET);
        } else if (stack.is(Items.WATER_BUCKET) || stack.is(Items.LAVA_BUCKET)) {
            if (focus.pour(eye, look, stack.is(Items.WATER_BUCKET) ? Material.WATER : Material.LAVA) && !creative)
                setMainHand(player, Items.BUCKET);
        } else {
            ItemStack held = stack.copy();
            Runnable place = () -> {
                if (focus.placeBlock(eye, look, blocks.placer(held), feet) && !creative) useUp(player, held);
            };
            if (aim == null || !shadow.available()) place.run();
            else ShadowWorld.use(focus.planet(), aim.cell(), aim.face(),
                    new net.minecraft.world.phys.Vec3(aim.hit().x, aim.hit().y, aim.hit().z), player.getUUID(), place);
        }
    }

    /**
     * Left click: with Minecraft running the planet, its own breaking (drops outside creative, the
     * tool worn); otherwise the block just goes.
     */
    private static void breakBlock(LocalPlayer player, Vector3d eye, Vector3d look, PlanetSession.Aim aim) {
        if (!shadow.available()) focus.breakBlock(eye, look, player.getAbilities().instabuild);
        else if (aim != null && focus.planet().info(aim.cell()).breakable())
            ShadowWorld.destroy(focus.planet(), aim.cell(), player.getUUID());
    }

    /** A block placed outside creative: one fewer of it in the main hand (on the server, which syncs back). */
    private static void useUp(LocalPlayer player, ItemStack placed) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return;
        java.util.UUID id = player.getUUID();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(id);
            if (sp != null && sp.getMainHandItem().is(placed.getItem())) sp.getMainHandItem().shrink(1);
        });
    }

    /** The inventory is the integrated server's: the item changes there and syncs back. */
    private static void setMainHand(LocalPlayer player, Item item) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return;
        java.util.UUID id = player.getUUID();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(id);
            if (sp != null) sp.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item));
        });
    }

    private static Vector3d vec(net.minecraft.world.phys.Vec3 v) {
        return new Vector3d(v.x, v.y, v.z);
    }
}
