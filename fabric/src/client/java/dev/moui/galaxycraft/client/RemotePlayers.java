package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.net.PeerState;
import dev.moui.galaxycraft.net.PeerTrack;
import dev.moui.galaxycraft.net.Protocol;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** The other players as this client knows them: a PeerTrack each. Client thread only. */
final class RemotePlayers {
    private final Map<UUID, PeerTrack> tracks = new HashMap<>();

    void push(UUID id, PeerState s, long nowNanos) {
        PeerTrack t = tracks.get(id);
        if (t == null) {
            if (tracks.size() >= Protocol.MAX_PLAYERS) return; // a host cannot make us keep more than the game allows
            t = new PeerTrack();
            tracks.put(id, t);
        }
        t.push(s, nowNanos);
    }

    void remove(UUID id) {
        tracks.remove(id);
    }

    void clear() {
        tracks.clear();
    }

    int size() {
        return tracks.size();
    }

    /** Where each visible player is drawn at nowNanos (those silent for too long are left out, and forgotten). */
    Map<UUID, PeerTrack.Pose> poses(long nowNanos) {
        Map<UUID, PeerTrack.Pose> out = new HashMap<>();
        tracks.entrySet().removeIf(e -> {
            PeerTrack.Pose p = e.getValue().sample(nowNanos);
            if (p == null) return true;
            out.put(e.getKey(), p);
            return false;
        });
        return out;
    }
}
