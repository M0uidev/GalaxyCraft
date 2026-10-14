package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.net.AvatarFrame;
import dev.moui.galaxycraft.net.PeerTrack;
import dev.moui.galaxycraft.net.Protocol;
import dev.moui.galaxycraft.gravity.GravityFrame;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.RemotePlayer;
import org.joml.Matrix4d;
import org.joml.Vector3d;

/**
 * What it takes to draw the other players: a Minecraft player entity of our own for each (never put
 * in the level: each client moves in Minecraft coordinates of its own, so the server's copy of a
 * peer can be anywhere, or not here at all), its walk cycle worked out from how it moves, and where
 * its body stands. Client thread only.
 */
final class RemoteAvatars {
    /** Units per Minecraft block in the game. */
    private static final double UNITS_PER_BLOCK = 1.0 / GravityFrame.SCALE;

    private static final class Avatar {
        RemotePlayer entity;
        Vector3d lastPos;
        long lastNanos;
        float walkPos, walkSpeed;
    }

    /** One player to draw this frame. */
    record Shown(UUID id, RemotePlayer entity, Matrix4d matrix, float pitch, float walkPos, float walkSpeed, boolean crouching,
            boolean gliding, boolean swimming, int mode) {}

    private final Map<UUID, Avatar> avatars = new HashMap<>();
    private ClientLevel level;

    /**
     * The visible players at nowNanos, nearest first, each with its entity and body frame. A player
     * the server has not told us about yet (no tab list entry, so no skin) waits for the next frame.
     */
    List<Shown> visible(Minecraft mc, Map<UUID, PeerTrack.Pose> poses, long nowNanos, Vector3d meUniverse) {
        if (mc.level == null || mc.getConnection() == null) return List.of();
        if (level != mc.level) {
            avatars.clear();
            level = mc.level;
        }
        avatars.keySet().removeIf(id -> !poses.containsKey(id));
        List<Shown> out = new ArrayList<>(poses.size());
        for (var e : poses.entrySet()) {
            UUID id = e.getKey();
            PeerTrack.Pose p = e.getValue();
            Avatar a = avatars.get(id);
            if (a == null) {
                PlayerInfo info = mc.getConnection().getPlayerInfo(id);
                if (info == null) continue;
                a = new Avatar();
                a.entity = new RemotePlayer(mc.level, info.getProfile());
                avatars.put(id, a);
            }
            Vector3d universe = new Vector3d(p.x(), p.y(), p.z());
            animate(a, universe, nowNanos);
            Vector3d feet = dev.moui.galaxycraft.universe.GameOrigin.toGame(universe);
            Vector3d up = new Vector3d(p.upX(), p.upY(), p.upZ());
            Vector3d look = new Vector3d(p.lookX(), p.lookY(), p.lookZ());
            boolean crouch = (p.flags() & Protocol.F_SNEAK) != 0, glide = (p.flags() & Protocol.F_GLIDE) != 0;
            boolean swim = (p.flags() & Protocol.F_SWIM) != 0;
            out.add(new Shown(id, a.entity, AvatarFrame.matrix(feet, up, look, UNITS_PER_BLOCK), AvatarFrame.pitchDegrees(up, look),
                    a.walkPos, a.walkSpeed, crouch, glide, swim, p.mode()));
        }
        if (meUniverse != null)
            out.sort(Comparator.comparingDouble(s -> distance(s, meUniverse)));
        return out;
    }

    private static double distance(Shown s, Vector3d me) {
        // The frame's origin is in game units; any common unit orders them: compare to our own game position.
        Vector3d mine = dev.moui.galaxycraft.universe.GameOrigin.toGame(me);
        return s.matrix().transformPosition(new Vector3d()).distanceSquared(mine);
    }

    /** Minecraft's limb animation: speed follows how fast the body moved (4 x blocks per tick, at most 1), position adds it up. */
    private void animate(Avatar a, Vector3d universe, long nowNanos) {
        if (a.lastPos != null) {
            double ticks = Math.max(1e-3, (nowNanos - a.lastNanos) / 50e6);
            double blocksPerTick = universe.distance(a.lastPos) / UNITS_PER_BLOCK / ticks;
            float target = (float) Math.min(1.0, blocksPerTick * 4.0);
            a.walkSpeed += (target - a.walkSpeed) * (float) Math.min(1.0, 0.4 * ticks);
            a.walkPos += a.walkSpeed * (float) ticks;
        }
        a.lastPos = universe;
        a.lastNanos = nowNanos;
    }

    void clear() {
        avatars.clear();
        level = null;
    }
}
