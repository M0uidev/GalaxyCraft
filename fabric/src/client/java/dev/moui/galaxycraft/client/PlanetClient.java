package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.bridge.BridgeClient;
import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.gravity.LookMath;
import dev.moui.galaxycraft.proto.Seqlock;
import dev.moui.galaxycraft.voxel.Material;
import dev.moui.galaxycraft.voxel.PlanetSession;
import dev.moui.galaxycraft.voxel.PlanetStore;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3d;

/**
 * The voxel planet in Minecraft's hands: /galaxycraft planet, the clicks that break and place
 * blocks while something is in the main hand (Dolphin then keeps them from Mario), P to land on
 * the planet, and the messages that carry it to the game. One planet per stage (galaxy), saved in
 * .minecraft/galaxycraft/planets and loaded again when the stage is. -Dgalaxycraft.planet=true
 * (or a radius) spawns one in a stage that has none as soon as the player follows Mario.
 */
public final class PlanetClient {
    /** SDL scancode of P; protocol mouse mask bits (bit n = SDL button n). */
    private static final int SC_P = 19, MOUSE_LEFT = 1 << 1, MOUSE_RIGHT = 1 << 3;
    public static final int DEFAULT_RADIUS = 16;
    /** Ticks between saves of an edited planet. */
    private static final int SAVE_TICKS = 200;
    private static final PlanetSession session = new PlanetSession(1 / GravityFrame.SCALE);
    private static final PlanetStore store =
            new PlanetStore(FabricLoader.getInstance().getGameDir().resolve("galaxycraft/planets"));
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

    private PlanetClient() {}

    /** The planet itself (end-to-end tests edit it directly). */
    public static PlanetSession session() {
        return session;
    }

    public static boolean itemActive(LocalPlayer player) {
        return player != null && !player.getMainHandItem().isEmpty();
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
        if (session.active() && frame != null && player != null) {
            if (p && !lastP) session.teleport();
            if (itemActive(player)) {
                Vector3d eye = frame.toGal(vec(player.getEyePosition()));
                Vector3d look = frame.dirToGal(LookMath.direction(player.getYRot(), player.getXRot()));
                // Minecraft gets the same clicks and swings the arm by itself.
                if (pressed(buttons, MOUSE_LEFT)) session.breakBlock(eye, look);
                if (pressed(buttons, MOUSE_RIGHT))
                    session.placeBlock(eye, look, material(player.getMainHandItem()), world.queryPos());
            }
        }
        lastButtons = buttons;
        lastP = p;
        session.update(world.sceneId(), bridge.hostPid(), world.queryPos());
        for (PlanetSession.Msg m; (m = session.peek()) != null && bridge.send(m.type(), m.payload()); ) session.sent();
        if (++sinceSave >= SAVE_TICKS) {
            sinceSave = 0;
            saveNow();
        }
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
            Optional<PlanetStore.Saved> saved = store.read(next);
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
        saver.execute(() -> {
            try {
                store.write(where, s);
            } catch (IOException e) {
                GalaxyCraft.LOG.warn("Could not save the planet of {}: {}", where, e.toString());
            }
        });
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

    private static Material material(ItemStack stack) {
        return Material.ofItem(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
    }

    private static Vector3d vec(net.minecraft.world.phys.Vec3 v) {
        return new Vector3d(v.x, v.y, v.z);
    }
}
