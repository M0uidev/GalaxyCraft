package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.bridge.BridgeClient;
import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.gravity.LookMath;
import dev.moui.galaxycraft.proto.Seqlock;
import dev.moui.galaxycraft.voxel.Material;
import dev.moui.galaxycraft.voxel.PlanetSession;
import java.util.Optional;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3d;

/**
 * The voxel planet in Minecraft's hands: /galaxycraft planet, the clicks that break and place
 * blocks while something is in the main hand (Dolphin then keeps them from Mario), P to land on
 * the planet, and the messages that carry it to the game. -Dgalaxycraft.planet=true spawns one
 * as soon as the player follows Mario.
 */
public final class PlanetClient {
    /** SDL scancode of P; protocol mouse mask bits (bit n = SDL button n). */
    private static final int SC_P = 19, MOUSE_LEFT = 1 << 1, MOUSE_RIGHT = 1 << 3;
    private static final PlanetSession session = new PlanetSession(1 / GravityFrame.SCALE);
    private static boolean autoSpawn = Boolean.getBoolean("galaxycraft.planet");
    private static boolean spawnRequested;
    private static int lastButtons;
    private static boolean lastP;

    private PlanetClient() {}

    /** The planet itself (end-to-end tests edit it directly). */
    public static PlanetSession session() {
        return session;
    }

    public static boolean itemActive(LocalPlayer player) {
        return player != null && !player.getMainHandItem().isEmpty();
    }

    /** /galaxycraft planet spawn: next tick, above the player. */
    public static void requestSpawn() {
        spawnRequested = true;
    }

    public static void teleport() {
        session.teleport();
    }

    public static void remove() {
        session.remove();
    }

    public static String status() {
        if (!session.active()) return "no planet";
        Vector3d c = session.center();
        return String.format("planet at (%.0f, %.0f, %.0f), %d messages queued", c.x, c.y, c.z, session.queued());
    }

    /** Client tick, after the gravity frame is up to date. */
    public static void tick(LocalPlayer player, BridgeClient bridge, GravityFrame frame, Seqlock.WorldState world) {
        if (frame != null && world.hasGravity() && (spawnRequested || (autoSpawn && world.follow()))) {
            session.spawn(world.queryPos(), frame.upGal());
            spawnRequested = autoSpawn = false;
            GalaxyCraft.LOG.info("Voxel planet at {}", session.center());
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
        session.update(world.sceneId(), bridge.hostPid());
        for (PlanetSession.Msg m; (m = session.peek()) != null && bridge.send(m.type(), m.payload()); ) session.sent();
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
