package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.net.MpPayloads;
import dev.moui.galaxycraft.voxel.PlanetSession;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/**
 * A guest on the host's minecart or boat. The host's shadow runs the vehicle and says where it seats us
 * (RideSeat, about ten times a second); we put our own player there each tick, without gravity, so our state
 * (and the F_RIDE flag) shows the others a rider. Our movement keys go back (Steer). Client thread only.
 */
final class MpRide {
    /** Client ticks without a RideSeat after which the ride is over (the host stopped, or the packet was lost). */
    private static final int LOST_TICKS = 30, HEARTBEAT_TICKS = 4;

    private static int planet = -1;
    private static Vector3d seat;
    private static int tick, heard;
    private static int lastKeys = -1, sinceKeys;
    private static boolean pinned;

    private MpRide() {}

    static void register() {
        ClientPlayNetworking.registerGlobalReceiver(MpPayloads.RideSeat.TYPE, (m, ctx) -> ctx.client().execute(() -> {
            planet = m.planet();
            seat = new Vector3d(m.x(), m.y(), m.z());
            heard = tick;
        }));
        ClientPlayNetworking.registerGlobalReceiver(MpPayloads.RideEnd.TYPE, (m, ctx) -> ctx.client().execute(MpRide::end));
    }

    /** Whether this guest sits on a vehicle of the host's. */
    static boolean active() {
        return seat != null && tick - heard <= LOST_TICKS;
    }

    private static void end() {
        seat = null;
        planet = -1;
        lastKeys = -1;
    }

    /** Client tick, on a planet: sends the keys, and sits the player on the vehicle. */
    static void tick(LocalPlayer player, PlanetSession session, GravityFrame frame, int keys) {
        tick++;
        if (seat != null && tick - heard > LOST_TICKS) end();
        if (seat == null) {
            if (pinned && player != null) player.setNoGravity(false);
            pinned = false;
            return;
        }
        // Keys: when they change, and five times a second while any is held (the host counts silence as released).
        sinceKeys++;
        if (keys != lastKeys || keys != 0 && sinceKeys >= HEARTBEAT_TICKS) {
            ClientPlayNetworking.send(new MpPayloads.Steer(keys));
            lastKeys = keys;
            sinceKeys = 0;
        }
        if (player == null || frame == null || planet != PlanetClient.catalogIndex(session)) return;
        Vector3d mc = frame.toMc(session.galOf(seat));
        player.setPos(mc.x, mc.y, mc.z);
        player.setOldPosAndRot();
        player.setDeltaMovement(Vec3.ZERO);
        player.setNoGravity(true);
        player.resetFallDistance();
        pinned = true;
    }

    static void reset() {
        end();
        pinned = false;
    }
}
