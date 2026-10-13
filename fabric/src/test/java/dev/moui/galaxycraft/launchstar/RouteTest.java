package dev.moui.galaxycraft.launchstar;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.launchstar.Route.Sphere;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class RouteTest {
    static final Vector3d UP = new Vector3d(0, 1, 0);

    static double minClearance(Route r, List<Sphere> obstacles) {
        double min = Double.MAX_VALUE;
        for (int i = 0; i <= 2000; i++) {
            Vector3d p = r.at(r.length() * i / 2000.0);
            for (Sphere s : obstacles) min = Math.min(min, p.distance(s.center()) - s.radius());
        }
        return min;
    }

    @Test void startsAndEndsAtTheStars() {
        Vector3d a = new Vector3d(0, 0, 0), b = new Vector3d(5000, 0, 0);
        Route r = Route.plan(a, UP, b, UP, List.of());
        assertEquals(0, r.at(0).distance(a), 1e-6);
        assertEquals(0, r.at(r.length()).distance(b), 1e-6);
        assertTrue(r.length() >= a.distance(b));
    }

    @Test void leavesUpAndArrivesFromAbove() {
        Route r = Route.plan(new Vector3d(), UP, new Vector3d(5000, 0, 0), UP, List.of());
        assertTrue(r.tangent(0).dot(UP) > 0.9, "leaves along up");
        assertTrue(r.tangent(r.length()).dot(UP) < -0.9, "arrives coming down");
    }

    @Test void sphereOnTheChord() {
        Vector3d a = new Vector3d(0, 0, 0), b = new Vector3d(10000, 0, 0);
        List<Sphere> o = List.of(new Sphere(new Vector3d(5000, 0, 0), 1500)); // centred on the chord
        Route r = Route.plan(a, UP, b, UP, o);
        assertTrue(minClearance(r, o) >= -1, "clearance " + minClearance(r, o));
    }

    @Test void randomPlanetsInTheWay() {
        Random rnd = new Random(5);
        for (int t = 0; t < 200; t++) {
            Vector3d a = new Vector3d(0, 0, 0), b = new Vector3d(4000 + rnd.nextInt(8000), rnd.nextInt(2000) - 1000, rnd.nextInt(2000) - 1000);
            List<Sphere> o = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Vector3d c = new Vector3d(a).lerp(b, rnd.nextDouble() * 0.8 + 0.1).add(rnd.nextInt(600) - 300, rnd.nextInt(600) - 300, rnd.nextInt(600) - 300);
                Sphere s = new Sphere(c, 300 + rnd.nextInt(500));
                if (s.center().distance(a) > s.radius() + 600 && s.center().distance(b) > s.radius() + 600) o.add(s);
            }
            Route r = Route.plan(a, UP, b, UP, o);
            assertTrue(minClearance(r, o) >= -1, "trial " + t + " clearance " + minClearance(r, o));
        }
    }

    @Test void thePlanetTheStarStandsOnIsNotInTheWay() {
        Vector3d c = new Vector3d(0, -1000, 0), a = new Vector3d(0, 0, 0), b = new Vector3d(6000, 0, 0);
        List<Sphere> o = List.of(new Sphere(c, 1400)); // a's planet with its gravity around it
        Route r = Route.plan(a, UP, b, UP, o);
        assertTrue(r.length() < 3 * a.distance(b));
        assertTrue(r.at(r.length() / 2).distance(c) >= 1000 - 1);
    }

    @Test void closeAndFarStaysFinite() {
        for (double d : new double[] {3, 80, 1e6, 1e9}) {
            Route r = Route.plan(new Vector3d(), UP, new Vector3d(d, 0, 0), UP, List.of());
            assertTrue(Double.isFinite(r.length()) && r.length() > 0, "d=" + d);
            assertTrue(Double.isFinite(r.at(r.length() / 2).x), "d=" + d);
        }
    }

    @Test void positionIsContinuous() {
        Route r = Route.plan(new Vector3d(), UP, new Vector3d(5000, 300, -800), UP, List.of(new Sphere(new Vector3d(2500, 0, 0), 900)));
        Vector3d prev = r.at(0);
        for (int i = 1; i <= 4000; i++) {
            Vector3d p = r.at(r.length() * i / 4000.0);
            assertTrue(p.distance(prev) <= r.length() / 4000.0 * 1.05 + 1e-9);
            prev = p;
        }
    }
}
