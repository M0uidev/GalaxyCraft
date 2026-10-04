package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.bridge.BridgeClient;
import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.gravity.LookMath;
import dev.moui.galaxycraft.proto.Layout;
import dev.moui.galaxycraft.proto.Seqlock;
import dev.moui.galaxycraft.shadow.ShadowWorld;
import dev.moui.galaxycraft.voxel.AtlasLink;
import dev.moui.galaxycraft.voxel.Material;
import dev.moui.galaxycraft.voxel.PlanetSession;
import dev.moui.galaxycraft.voxel.PlanetStore;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Vector3d;

/**
 * The voxel planet in Minecraft's hands: /galaxycraft planet, the clicks that break and place
 * blocks (any of Minecraft's, by its placement rules; and pour and fill buckets of water and lava)
 * while something is in the main hand and no screen is open (Dolphin then keeps them from Mario), P
 * to land on the planet, and the messages that carry it to the game, the block atlas first. One planet per stage (galaxy), saved in
 * ~/.local/share/galaxycraft/planets (see planetDir) and loaded again when the stage is. -Dgalaxycraft.planet=true
 * (or a radius) spawns one in a stage that has none as soon as the player follows Mario.
 *
 * Minecraft runs the blocks around Mario ({@link ShadowLink}): a right click uses the block it is
 * on (doors, levers, chests) or the held item on it before placing anything, and aiming at such a
 * block makes the clicks Minecraft's even with an empty hand.
 */
public final class PlanetClient {
    /** SDL scancode of P; protocol mouse mask bits (bit n = SDL button n). */
    private static final int SC_P = 19, MOUSE_LEFT = 1 << 1, MOUSE_RIGHT = 1 << 3;
    public static final int DEFAULT_RADIUS = 32;
    /** Ticks between saves of an edited planet. */
    private static final int SAVE_TICKS = 200;
    private static final PlanetSession session = new PlanetSession(1 / GravityFrame.SCALE);
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
    private static int lastButtons;
    private static boolean lastP;
    private static int sinceSave;
    private static final ShadowLink shadow = new ShadowLink(session);
    private static boolean aimUsable; // an empty hand aims at a block a click uses
    private static final DropsClient drops = new DropsClient(session);
    private static final EntityClient entities = new EntityClient(session, drops);
    private static McBlocks blocks;
    private static AtlasLink atlasLink;

    private PlanetClient() {}

    /** Minecraft's blocks for the planets, once its models are loaded (null before the first tick). */
    static McBlocks blocks() {
        return blocks;
    }

    /** Places a block item as a right click would (end-to-end tests). */
    public static boolean placeItem(Vector3d eyeGal, Vector3d lookGal, ItemStack stack, Vector3d marioFeetGal) {
        return blocks != null && session.placeBlock(eyeGal, lookGal, blocks.placer(stack), marioFeetGal);
    }

    /** Minecraft's text for the block in a cell of the planet (end-to-end tests). */
    public static String blockName(int cell) {
        return blocks == null || !session.active() ? "" : blocks.name(session.planet().get(cell));
    }

    /** The planet itself (end-to-end tests edit it directly). */
    public static PlanetSession session() {
        return session;
    }

    public static boolean itemActive(LocalPlayer player) {
        return player != null && (!player.getMainHandItem().isEmpty() || aimUsable);
    }

    /** /galaxycraft planet spawn [radius]: next tick, above the player, replacing this stage's. */
    public static void requestSpawn(int radius) {
        spawnRadius = radius;
    }

    public static void teleport() {
        session.teleport();
    }

    /** Removes this stage's planet, from the game and from disk. */
    public static void remove() {
        session.remove();
        String s = stage;
        if (s != null && !s.isEmpty()) saver.execute(() -> {
            try {
                store.delete(s);
            } catch (IOException e) {
                GalaxyCraft.LOG.warn("Could not delete the planet of {}: {}", s, e.toString());
            }
        });
    }

    public static String status() {
        String where = stage == null || stage.isEmpty() ? "" : " in " + stage;
        if (!session.active()) return "no planet" + where;
        Vector3d c = session.center();
        return String.format("planet of radius %.0f%s at (%.0f, %.0f, %.0f), %d to send, %d chunks with collision",
                session.planet().surface(), where, c.x, c.y, c.z, session.queued(), session.collisionChunks());
    }

    /** Client tick, after the gravity frame is up to date. */
    public static void tick(LocalPlayer player, BridgeClient bridge, GravityFrame frame, Seqlock.WorldState world) {
        if (blocks == null) {
            blocks = McBlocks.create(Minecraft.getInstance());
            atlasLink = new AtlasLink(blocks.atlas, 1);
            session.setBlocks(blocks);
        }
        for (byte[] piece; (piece = atlasLink.peek(world.sceneId(), bridge.hostPid())) != null
                && bridge.send(Layout.MSG_ATLAS, piece); ) atlasLink.sent();
        if (!bridge.stage().equals(stage)) enterStage(bridge.stage());
        if (frame != null && world.hasGravity() && (spawnRadius > 0 || (autoSpawn && world.follow()))) {
            session.spawn(spawnRadius > 0 ? spawnRadius : autoRadius, world.queryPos(), frame.upGal());
            spawnRadius = 0;
            autoSpawn = false;
            sinceSave = SAVE_TICKS; // saved right away
            GalaxyCraft.LOG.info("Voxel planet of radius {} at {}", session.planet().surface(), session.center());
        }
        Optional<Seqlock.InputState> in = bridge.input();
        int buttons = in.map(Seqlock.InputState::buttons).orElse(0);
        boolean p = in.map(i -> (i.keys()[SC_P / 8] >> (SC_P % 8) & 1) != 0).orElse(false);
        boolean screen = Minecraft.getInstance().gui.screen() != null; // the clicks are the screen's
        if (session.active() && frame != null && player != null) {
            if (p && !lastP && !screen) session.teleport();
            Vector3d eye = frame.toGal(vec(player.getEyePosition()));
            Vector3d look = frame.dirToGal(LookMath.direction(player.getYRot(), player.getXRot()));
            PlanetSession.Aim aim = screen ? null : session.aim(eye, look);
            aimUsable = aim != null && shadow.available() && blocks.usable(session.planet().get(aim.cell()));
            boolean item = itemActive(player) && !screen;
            // Minecraft's outline on the block the clicks would act on, only with something in hand.
            session.setOutline(item ? session.target(eye, look, player.getMainHandItem().is(Items.BUCKET)) : -1);
            if (item) {
                // Minecraft gets the same clicks and swings the arm by itself.
                if (pressed(buttons, MOUSE_LEFT)) breakBlock(player, eye, look, aim);
                if (pressed(buttons, MOUSE_RIGHT)) use(player, eye, look, world.queryPos(), aim);
            }
        }
        shadow.tick(stage == null ? "" : stage, world.queryPos());
        drops.tick(Minecraft.getInstance(), frame, frame == null ? null : world.queryPos());
        lastButtons = buttons;
        lastP = p;
        session.update(world.sceneId(), bridge.hostPid(), world.queryPos());
        for (PlanetSession.Msg m; (m = session.peek()) != null && bridge.send(m.type(), m.payload()); ) session.sent();
        if (++sinceSave >= SAVE_TICKS) {
            sinceSave = 0;
            saveNow();
        }
    }

    /** Items lying on the planet (tests). */
    public static int dropCount() {
        return drops.all().size();
    }

    /** Render thread, once per emulated frame: the planet's entities to the game. */
    public static void frame(BridgeClient bridge, float partialTick) {
        bridge.world().ifPresent(w -> entities.frame(bridge, w.sceneId(), w.queryPos(), partialTick));
    }

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
        stage = next;
        autoSpawn = false;
        if (next.isEmpty()) return;
        try {
            Optional<PlanetStore.Saved> saved = store.read(next, blocks);
            if (saved.isPresent()) {
                session.load(saved.get());
                GalaxyCraft.LOG.info("Voxel planet of {} loaded", next);
            } else {
                // Levels only: not the title, the file select or the world map.
                autoSpawn = autoRadius > 0 && next.endsWith("Galaxy");
            }
        } catch (IOException e) {
            GalaxyCraft.LOG.warn("Could not load the planet of {}: {}", next, e.toString());
        }
    }

    private static void saveNow() {
        if (!session.unsaved() || stage == null || stage.isEmpty()) return;
        PlanetStore.Saved s = session.save();
        String where = stage;
        McBlocks b = blocks;
        saver.execute(() -> {
            try {
                store.write(where, s, b);
            } catch (IOException e) {
                GalaxyCraft.LOG.warn("Could not save the planet of {}: {}", where, e.toString());
            }
        });
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
            Material got = session.scoop(eye, look);
            if (got != null && !creative) setMainHand(player, got == Material.WATER ? Items.WATER_BUCKET : Items.LAVA_BUCKET);
        } else if (stack.is(Items.WATER_BUCKET) || stack.is(Items.LAVA_BUCKET)) {
            if (session.pour(eye, look, stack.is(Items.WATER_BUCKET) ? Material.WATER : Material.LAVA) && !creative)
                setMainHand(player, Items.BUCKET);
        } else {
            ItemStack held = stack.copy();
            Runnable place = () -> {
                if (session.placeBlock(eye, look, blocks.placer(held), feet) && !creative) useUp(player, held);
            };
            if (aim == null || !shadow.available()) place.run();
            else ShadowWorld.use(session.planet(), aim.cell(), aim.face(),
                    new net.minecraft.world.phys.Vec3(aim.hit().x, aim.hit().y, aim.hit().z), player.getUUID(), place);
        }
    }

    /**
     * Left click: with Minecraft running the planet, its own breaking (drops outside creative, the
     * tool worn); otherwise the block just goes.
     */
    private static void breakBlock(LocalPlayer player, Vector3d eye, Vector3d look, PlanetSession.Aim aim) {
        if (!shadow.available()) session.breakBlock(eye, look, player.getAbilities().instabuild);
        else if (aim != null && session.planet().info(aim.cell()).breakable())
            ShadowWorld.destroy(session.planet(), aim.cell(), player.getUUID());
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
