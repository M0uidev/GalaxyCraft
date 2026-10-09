package dev.moui.galaxycraft.client.music;

import dev.moui.galaxycraft.client.GalaxyCraftClient;
import dev.moui.galaxycraft.client.PlanetClient;
import dev.moui.galaxycraft.client.StationClient;
import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.music.StationMusic;
import dev.moui.galaxycraft.music.Want;
import dev.moui.galaxycraft.voxel.PlanetSession;
import dev.moui.galaxycraft.voxel.Station;
import org.joml.Vector3d;

/** Reads the game: on a station, its music; in a planet's gravity, planet music; else space music. */
final class MusicSensor {
    private MusicSensor() {}

    static Want wanted(StationMusic stations) {
        if (PlanetClient.standingOn() != null) return Want.PLANET;
        Station st = stationUnderfoot();
        return st == null ? Want.SPACE : stations.get(st.id);
    }

    /** The station whose gravity box holds the player, or null. */
    static Station stationUnderfoot() {
        Vector3d feet = GalaxyCraftClient.galaxyPos().orElse(null);
        if (feet == null) return null;
        Vector3d blocks = new Vector3d(feet).mul(GravityFrame.SCALE);
        for (PlanetSession s : StationClient.sessions())
            if (s.active() && s.body(GravityFrame.SCALE).outside(blocks) <= 0) return StationClient.of(s).orElse(null);
        return null;
    }
}
