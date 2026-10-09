package dev.moui.galaxycraft.voxel;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/** The LOD inspector (PlanetSession.audit) finds nothing wrong while Mario flies and walks over a planet. */
class LodAuditTest {
    @Test void flyingOverAPlanetLeavesNoGhostsNorHoles() {
        PlanetSession s = new PlanetSession(80);
        s.setRenderDistance(64);
        s.spawn(128, new Vector3d(), new Vector3d(0, 1, 0));
        double alt = 131;
        Vector3d dir = new Vector3d(1, 0, 0), up = new Vector3d(0, -1, 0);
        List<String> seen = new java.util.ArrayList<>();
        for (double ang = 0; ang < Math.PI; ang += 4 / alt) {
            Vector3d local = new Vector3d(up).mul(Math.cos(ang)).add(new Vector3d(dir).mul(Math.sin(ang))).mul(alt);
            s.update(3, 100, new Vector3d(local).mul(80).add(s.center()));
            while (s.peek() != null) s.sent(); // the game keeps up: all of it goes out
            for (String problem : s.audit()) if (!seen.contains(problem)) seen.add(problem);
        }
        assertTrue(seen.isEmpty(), "the inspector found: " + seen.subList(0, Math.min(10, seen.size())) + " (" + seen.size() + ")");
    }

    @Test void afterAHurriedFlightAndATeleportEverythingSettles() {
        for (double render : new double[] {64, 96}) for (double speed : new double[] {3, 10}) {
            PlanetSession s = new PlanetSession(80);
            s.setRenderDistance(render);
            s.spawn(128, new Vector3d(), new Vector3d(0, 1, 0));
            double alt = 131;
            Vector3d dir = new Vector3d(1, 0, 0), up = new Vector3d(0, -1, 0);
            Vector3d local = new Vector3d();
            for (double ang = 0; ang < Math.PI; ang += speed / alt) {
                local = new Vector3d(up).mul(Math.cos(ang)).add(new Vector3d(dir).mul(Math.sin(ang))).mul(alt);
                s.update(3, 100, new Vector3d(local).mul(80).add(s.center()));
                long end = System.nanoTime() + 2_000_000;
                for (PlanetSession.Msg m; (m = s.peek(() -> System.nanoTime() - end < 0)) != null; ) s.sent();
            }
            // Mario stops (or is teleported across); the game catches up.
            Vector3d there = new Vector3d(local).negate().mul(80).add(s.center());
            for (int tick = 0; tick < 400; tick++) {
                s.update(3, 100, tick < 200 ? new Vector3d(local).mul(80).add(s.center()) : there);
                while (s.peek() != null) s.sent();
                if (tick == 199) org.junit.jupiter.api.Assertions.assertEquals(List.of(), s.audit(), "render " + render + " speed " + speed + ", after the flight");
            }
            org.junit.jupiter.api.Assertions.assertEquals(List.of(), s.audit(), "render " + render + " speed " + speed + ", after the teleport");
        }
    }
}
