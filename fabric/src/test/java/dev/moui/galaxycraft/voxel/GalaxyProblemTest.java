package dev.moui.galaxycraft.voxel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What a guest refuses of the galaxy a host sends it. */
class GalaxyProblemTest {
    static GalaxyCatalog.Entry planet(int i, double x, int radius, GalaxyCatalog.Kind kind) {
        return new GalaxyCatalog.Entry(i, x, 0, 0, radius, kind, "random", null, 1L);
    }

    static GalaxySave.Galaxy galaxy(GalaxyCatalog.Entry... es) {
        return new GalaxySave.Galaxy(1, GalaxyCatalog.Options.defaults(1), Arrays.asList(es));
    }

    @Test
    void anOrdinaryGalaxyIsFine() {
        assertTrue(GalaxySave.problem(galaxy(planet(0, 0, 48, GalaxyCatalog.Kind.GENERATED), planet(1, 900, 128, GalaxyCatalog.Kind.GENERATED))).isEmpty());
    }

    @Test
    void emptyOrTooManyPlanetsAreRefused() {
        assertTrue(GalaxySave.problem(null).isPresent());
        assertTrue(GalaxySave.problem(galaxy()).isPresent());
        List<GalaxyCatalog.Entry> many = new ArrayList<>();
        for (int i = 0; i <= GalaxyCatalog.MAX; i++) many.add(planet(i, i * 10.0, 48, GalaxyCatalog.Kind.GENERATED));
        assertTrue(GalaxySave.problem(new GalaxySave.Galaxy(1, GalaxyCatalog.Options.defaults(1), many)).isPresent());
        many.remove(0);
        assertTrue(GalaxySave.problem(new GalaxySave.Galaxy(1, GalaxyCatalog.Options.defaults(1), many)).isEmpty(), "exactly the maximum");
    }

    @Test
    void absurdRadiiAndPlacesAreRefused() {
        for (int r : new int[] {-5, 0, GalaxyCatalog.MIN_RADIUS - 1, GalaxyCatalog.MAX_RADIUS + 1, 2_000_000_000})
            assertTrue(GalaxySave.problem(galaxy(planet(0, 0, r, GalaxyCatalog.Kind.GENERATED))).isPresent(), "radius " + r);
        for (double x : new double[] {Double.NaN, Double.POSITIVE_INFINITY, 1e30})
            assertTrue(GalaxySave.problem(galaxy(planet(0, x, 48, GalaxyCatalog.Kind.GENERATED))).isPresent(), "x " + x);
    }

    @Test
    void nullPartsAreRefused() {
        assertTrue(GalaxySave.problem(galaxy(planet(0, 0, 48, null))).isPresent(), "no kind");
        assertTrue(GalaxySave.problem(galaxy((GalaxyCatalog.Entry) null)).isPresent(), "a null planet");
        assertTrue(GalaxySave.problem(new GalaxySave.Galaxy(1, null, List.of(planet(0, 0, 48, GalaxyCatalog.Kind.GENERATED)))).isPresent(), "no options");
    }

    @Test
    void aBlueprintPlanetIsRefusedUntilBlueprintsTravel() {
        var why = GalaxySave.problem(galaxy(planet(0, 0, 48, GalaxyCatalog.Kind.BLUEPRINT)));
        assertTrue(why.isPresent() && why.get().contains("blueprint"), why.toString());
    }
}
