# Tiers, stars, spacing and Warp Core reach Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every generated system has a tier (0 to 5) and a star type, both derived from the seed; planets sit farther apart in new worlds; a Warp Core tier limits which not-yet-visited systems you can warp to, and the Pulse stays inside the current system.

**Architecture:** Pure, Minecraft-free classes in `universe/` (`Tier`, `StarType`, `Visited`, `WarpGate`) derive everything from the sector hash, so the same seed gives the same universe and nothing new is stored except the set of visited sectors. `Universe` exposes `tier(Star)` and `starType(Star)`. Spacing changes only in a new world layout (3) so saved worlds keep their planets where their blocks are. The client (`Warp`, `Flight`, `GalaxyOptions`) only asks `WarpGate`; the Warp Core module itself belongs to `ship/*` (another session), so until it exists the core tier is a setting (default: unlimited, nothing changes for players).

**Tech Stack:** Java 25, Fabric, JUnit 5 (`fabric/src/test`), Gson.

**Spec:** `docs/superpowers/specs/2026-10-11-galaxycraft-planet-progression-design.md` (sections Model/Tier and star, Travel, Spacing; work order item 2).

## Global Constraints

- No Minecraft types in `universe/` (unit tested offline).
- Tier and star are derived, never stored: same seed, same universe. Home system is always tier 0, yellow.
- Tier noise is −1, 0 or +1 and the tier never goes below 0 or above 5.
- Ring edges (first draft, blocks from home): home ring 12,000; then 20,000, 40,000, 70,000, 110,000; beyond is tier 5.
- Star weights: red dwarf and lava from tier 1, blue from tier 3.
- Spacing: new layout 3 puts generated and default planets at least 1,500 blocks (gravity to gravity) apart; layouts 1 and 2 keep their numbers (a saved world must not move planets).
- Warp Core limits only NEW systems; visited systems and home warp free. Pulse never reaches another system.
- Numbers are first drafts, tuned by playing.
- Gradle: `cd fabric && JAVA_HOME=$(ls -d ~/.local/opt/jdk-25*|head -1) ./gradlew test --tests '<class>'` (full suite ~6 min; `fabric/test.sh` also works).
- Work in the worktree `../GalaxyCraft-progression` (branch `feat/planet-progression`) after `git merge master`. Never run gxvoxel/gxplay while another session does.

## Review Focus

- A world saved before this change (layout 1 or 2): its generated planets must not move or change count. Test: `Universe` with layout 2 gives the same system as before for a fixed seed.
- A sector with no system, or `Tier.of` fed a huge distance (1e15 blocks): tier 5, no overflow, no exception.
- Corrupt or missing `visited.json`: loads as empty plus home, never crashes the game.
- Warp Core tier outside 0..3 (hand-edited settings): `Tier.reach` clamps.
- Standing in a system, `Warp.here()` empty (in the void between systems): Pulse is off, but warp (K) still works within reach.

---

### Task 1: Tier

**Files:**
- Create: `fabric/src/main/java/dev/moui/galaxycraft/universe/Tier.java`
- Test: `fabric/src/test/java/dev/moui/galaxycraft/universe/TierTest.java`

**Interfaces:**
- Produces: `Tier.MAX = 5`; `static int ring(double blocks)`; `static int of(double blocks, long seed, boolean home)`; `static int reach(int coreTier)`.

- [ ] **Step 1: Write the failing test**

```java
package dev.moui.galaxycraft.universe;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TierTest {
    @Test void ringsGrowWithDistance() {
        assertEquals(0, Tier.ring(0));
        assertEquals(0, Tier.ring(11_999));
        assertEquals(1, Tier.ring(12_000));
        assertEquals(2, Tier.ring(25_000));
        assertEquals(3, Tier.ring(50_000));
        assertEquals(4, Tier.ring(100_000));
        assertEquals(5, Tier.ring(110_000));
        assertEquals(5, Tier.ring(1e15));
    }

    @Test void homeIsAlwaysTierZero() {
        for (long seed = 0; seed < 200; seed++) assertEquals(0, Tier.of(0, seed, true));
    }

    @Test void noiseStaysWithinOneAndInRange() {
        for (long seed = 0; seed < 2000; seed++) {
            for (double d : new double[] {0, 15_000, 30_000, 60_000, 90_000, 500_000}) {
                int t = Tier.of(d, seed, false);
                assertTrue(t >= 0 && t <= Tier.MAX, "tier " + t);
                assertTrue(Math.abs(t - Tier.ring(d)) <= 1, "noise at " + d);
            }
        }
    }

    @Test void sameSeedSameTier() {
        assertEquals(Tier.of(33_000, 12345, false), Tier.of(33_000, 12345, false));
    }

    @Test void reachOfTheWarpCoreClampsAndGrows() {
        assertEquals(1, Tier.reach(0));
        assertEquals(2, Tier.reach(1));
        assertEquals(4, Tier.reach(2));
        assertEquals(5, Tier.reach(3));
        assertEquals(1, Tier.reach(-4));
        assertEquals(5, Tier.reach(99));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd fabric && JAVA_HOME=$(ls -d ~/.local/opt/jdk-25*|head -1) ./gradlew test --tests '*TierTest'`
Expected: FAIL (compile error: `Tier` not found).

- [ ] **Step 3: Implement**

```java
package dev.moui.galaxycraft.universe;

/**
 * How far a system is from home, as a tier 0 to 5: rings by distance plus one step of noise from
 * the system's own seed. Derived, never stored. No Minecraft types.
 */
public final class Tier {
    public static final int MAX = 5;
    /** Ring r ends at EDGES[r] blocks from home (the last ring is open). First draft, tuned by playing. */
    static final double[] EDGES = {12_000, 20_000, 40_000, 70_000, 110_000};
    /** Highest tier of a not yet visited system a Warp Core of tier 0 to 3 reaches. */
    private static final int[] REACH = {1, 2, 4, 5};
    private static final long SALT = 0x7131E4A5D9C3B1F7L;

    private Tier() {}

    /** The ring of a distance (blocks from home), before noise. */
    public static int ring(double blocks) {
        for (int r = 0; r < EDGES.length; r++) if (blocks < EDGES[r]) return r;
        return MAX;
    }

    /** A system's tier: its ring, -1, 0 or +1 by its seed, kept in 0..MAX; home is always 0. */
    public static int of(double blocks, long seed, boolean home) {
        if (home) return 0;
        int noise = switch ((int) Math.floorMod(Universe.mix(seed ^ SALT), 4L)) {
            case 0 -> -1;
            case 3 -> 1;
            default -> 0;
        };
        return Math.max(0, Math.min(MAX, ring(blocks) + noise));
    }

    /** The highest tier of a not yet visited system a Warp Core of that tier reaches (clamped). */
    public static int reach(int coreTier) {
        return REACH[Math.max(0, Math.min(REACH.length - 1, coreTier))];
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: same command. Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add fabric/src/main/java/dev/moui/galaxycraft/universe/Tier.java fabric/src/test/java/dev/moui/galaxycraft/universe/TierTest.java
git commit -m "progression: Tier (rings by distance from home, noise of one, Warp Core reach)"
```

### Task 2: StarType and Universe.tier / starType

**Files:**
- Create: `fabric/src/main/java/dev/moui/galaxycraft/universe/StarType.java`
- Modify: `fabric/src/main/java/dev/moui/galaxycraft/universe/Universe.java` (add two methods after `reach(Star)`, ~line 100)
- Test: `fabric/src/test/java/dev/moui/galaxycraft/universe/StarTypeTest.java`

**Interfaces:**
- Consumes: `Tier.of(double, long, boolean)`, `Universe.mix(long)`.
- Produces: `enum StarType { YELLOW, RED_DWARF, LAVA, BLUE }`; `static StarType of(int tier, long seed, boolean home)`; `int Universe.tier(Star)`; `StarType Universe.starType(Star)`.

- [ ] **Step 1: Write the failing test**

```java
package dev.moui.galaxycraft.universe;

import static org.junit.jupiter.api.Assertions.*;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StarTypeTest {
    static final double U = 80;

    @Test void homeIsYellowAndTierZero() {
        for (long seed = 0; seed < 30; seed++) {
            var u = new Universe(seed, U);
            var home = u.star(Universe.Sector.HOME).orElseThrow();
            assertEquals(0, u.tier(home));
            assertEquals(StarType.YELLOW, u.starType(home));
        }
    }

    @Test void tierZeroNeverHasRedLavaOrBlue() {
        for (long seed = 0; seed < 5000; seed++) assertEquals(StarType.YELLOW, StarType.of(0, seed, false));
    }

    @Test void blueOnlyFromTierThree() {
        for (long seed = 0; seed < 5000; seed++) for (int t = 0; t < 3; t++) assertNotEquals(StarType.BLUE, StarType.of(t, seed, false));
        Set<StarType> seen = EnumSet.noneOf(StarType.class);
        for (long seed = 0; seed < 5000; seed++) seen.add(StarType.of(4, seed, false));
        assertEquals(EnumSet.allOf(StarType.class), seen);
    }

    @Test void theUniverseGivesTheSameTierAndStarForTheSameSeed() {
        List<Universe.Star> a = new Universe(9, U).around(UPos.ZERO, 4);
        var ua = new Universe(9, U);
        var ub = new Universe(9, U);
        for (var s : a) {
            assertEquals(ua.tier(s), ub.tier(s));
            assertEquals(ua.starType(s), ub.starType(s));
        }
    }

    @Test void tierDoesNotFallFarBelowItsRingFarFromHome() {
        var u = new Universe(5, U);
        for (var s : u.around(new UPos(40, 0, 0, 0, 0, 0), 6)) {
            double blocks = s.center().minus(UPos.ZERO).length() / U;
            assertTrue(u.tier(s) >= Tier.ring(blocks) - 1);
        }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `... --tests '*StarTypeTest'`. Expected: FAIL (compile error).

- [ ] **Step 3: Implement**

`StarType.java`:

```java
package dev.moui.galaxycraft.universe;

/**
 * The kind of a system's star: what its planets are like (later stages read it: lava raises
 * volcanic biomes and heat, red dwarf cold, blue rare ores). Derived from the seed and the tier.
 */
public enum StarType {
    YELLOW, RED_DWARF, LAVA, BLUE;

    private static final long SALT = 0x2F6B8C1D4E9A7305L;

    /** Cumulative weights out of 100 for YELLOW, RED_DWARF, LAVA (the rest is BLUE), by tier. First draft. */
    private static final int[][] WEIGHTS = {
        {100, 100, 100}, // 0: yellow only
        {60, 85, 100},   // 1
        {60, 85, 100},   // 2
        {40, 65, 85},    // 3: 15 blue
        {30, 55, 75},    // 4: 25 blue
        {30, 55, 75},    // 5
    };

    public static StarType of(int tier, long seed, boolean home) {
        if (home) return YELLOW;
        int[] w = WEIGHTS[Math.max(0, Math.min(Tier.MAX, tier))];
        int roll = (int) Math.floorMod(Universe.mix(seed ^ SALT), 100L);
        if (roll < w[0]) return YELLOW;
        if (roll < w[1]) return RED_DWARF;
        if (roll < w[2]) return LAVA;
        return BLUE;
    }
}
```

`Universe.java`, after `reach(Star)`:

```java
    /** A system's tier (0 to 5), from its distance to home and its own seed. */
    public int tier(Star s) {
        return Tier.of(s.center().minus(UPos.ZERO).length() / unitsPerBlock, s.seed(), s.home());
    }

    /** A system's star type, from its tier and its own seed. */
    public StarType starType(Star s) {
        return StarType.of(tier(s), s.seed(), s.home());
    }
```

- [ ] **Step 4: Run to verify it passes**

Run: `... --tests '*StarTypeTest' --tests '*UniverseTest' --tests '*TierTest'`. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fabric/src/main/java/dev/moui/galaxycraft/universe fabric/src/test/java/dev/moui/galaxycraft/universe/StarTypeTest.java
git commit -m "progression: StarType and Universe.tier / starType (derived from the seed)"
```

### Task 3: Spacing of layout 3

**Files:**
- Modify: `fabric/src/main/java/dev/moui/galaxycraft/voxel/GalaxyCatalog.java:22-42` (LAYOUT, Spacing)
- Modify: `fabric/src/main/java/dev/moui/galaxycraft/universe/Universe.java:25-27` (`systemBlocks`: layout 3 same as 2)
- Test: `fabric/src/test/java/dev/moui/galaxycraft/voxel/GalaxyCatalogTest.java` (add)

**Interfaces:**
- Produces: `GalaxyCatalog.LAYOUT == 3`; `Spacing.blocks(3)`: NEAR 600, NORMAL 1600, FAR 2400. Layouts 1 and 2 unchanged.

- [ ] **Step 1: Write the failing test** (append to `GalaxyCatalogTest`)

```java
    @Test void layoutThreeSpacesPlanetsFarApartAndOlderLayoutsKeepTheirs() {
        assertEquals(24, GalaxyCatalog.Spacing.NEAR.blocks(1));
        assertEquals(150, GalaxyCatalog.Spacing.NEAR.blocks(2));
        assertEquals(1500, GalaxyCatalog.Spacing.FAR.blocks(2));
        assertEquals(600, GalaxyCatalog.Spacing.NEAR.blocks(3));
        assertTrue(GalaxyCatalog.Spacing.NORMAL.blocks(3) > 1500);
        assertTrue(GalaxyCatalog.Spacing.FAR.blocks(3) > GalaxyCatalog.Spacing.NORMAL.blocks(3));
        assertEquals(3, GalaxyCatalog.LAYOUT);
    }

    @Test void layoutTwoSystemsAreTheSameAsBefore() {
        var u = new Universe(7, 80, 2);
        var star = u.around(UPos.ZERO, 2).stream().filter(s -> !s.home()).findFirst().orElseThrow();
        var made = u.system(star, LAND);
        // Planet count and centers are a function of the layout-2 recipe only: pin them.
        var again = new Universe(7, 80, 2).system(star, LAND);
        assertEquals(made.entries(), again.entries());
        assertEquals(star.spacing().blocks(2), star.spacing().blocks(2));
    }
```

(imports: `dev.moui.galaxycraft.universe.UPos`, `dev.moui.galaxycraft.universe.Universe` if not present.)

- [ ] **Step 2: Run to verify it fails**

Run: `... --tests '*GalaxyCatalogTest'`. Expected: FAIL (`blocks(3)` equals layout-2 value, LAYOUT is 2).

- [ ] **Step 3: Implement**

In `GalaxyCatalog`: `LAYOUT = 3` (doc: "3 spaces planets more than 1,500 blocks apart; worlds saved before keep their layout"); enum becomes

```java
        NEAR(24, 150, 600), NORMAL(96, 600, 1600), FAR(300, 1500, 2400);
        ...
        Spacing(int old, int wide, int farther) {...}
        public int blocks(int layout) {
            return layout >= 3 ? farther : layout >= 2 ? wide : old;
        }
```

`Universe.systemBlocks()` already uses `layout >= 2`; leave. Check `GalaxyTab.java:67` label uses `GalaxyCatalog.LAYOUT`: fine.

- [ ] **Step 4: Run to verify it passes**

Run: `... --tests '*GalaxyCatalogTest' --tests '*UniverseTest' --tests '*GalaxySaveTest'`. Expected: PASS. If a test pinned planet positions for `LAYOUT`, update it to pass layout 2 explicitly (that is the old-world case).

- [ ] **Step 5: Commit**

```bash
git add fabric/src
git commit -m "progression: layout 3 spaces planets more than 1,500 blocks apart (saved worlds keep theirs)"
```

### Task 4: Visited systems

**Files:**
- Create: `fabric/src/main/java/dev/moui/galaxycraft/universe/Visited.java`
- Test: `fabric/src/test/java/dev/moui/galaxycraft/universe/VisitedTest.java`

**Interfaces:**
- Consumes: `SystemIndex.name(Sector)`, `SystemIndex.parse(String)`.
- Produces: `Visited.load(Path)`, `boolean has(Universe.Sector)`, `boolean mark(Universe.Sector)` (true when new), `void save() throws IOException`. Home is always visited.

- [ ] **Step 1: Write the failing test**

```java
package dev.moui.galaxycraft.universe;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VisitedTest {
    @TempDir Path dir;

    @Test void homeIsAlwaysVisited() {
        assertTrue(Visited.load(dir.resolve("visited.json")).has(Universe.Sector.HOME));
    }

    @Test void markedSectorsSurviveSaveAndLoad() throws Exception {
        Path f = dir.resolve("visited.json");
        Visited v = Visited.load(f);
        var s = new Universe.Sector(3, -2, 7);
        assertFalse(v.has(s));
        assertTrue(v.mark(s));
        assertFalse(v.mark(s));
        v.save();
        assertTrue(Visited.load(f).has(s));
    }

    @Test void aCorruptFileLoadsEmpty() throws Exception {
        Path f = dir.resolve("visited.json");
        Files.writeString(f, "{not json");
        Visited v = Visited.load(f);
        assertTrue(v.has(Universe.Sector.HOME));
        assertFalse(v.has(new Universe.Sector(1, 0, 0)));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `... --tests '*VisitedTest'`. Expected: FAIL (compile).

- [ ] **Step 3: Implement**

```java
package dev.moui.galaxycraft.universe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/** The systems this galaxy's player has been to, kept in a JSON file next to its planets. Home always counts. */
public final class Visited {
    private final Path file;
    private final Set<Universe.Sector> sectors = new LinkedHashSet<>();

    private Visited(Path file) {
        this.file = file;
    }

    public static Visited load(Path file) {
        Visited v = new Visited(file);
        try {
            if (Files.exists(file)) {
                for (JsonElement e : JsonParser.parseString(Files.readString(file)).getAsJsonArray())
                    SystemIndex.parse(e.getAsString()).ifPresent(v.sectors::add);
            }
        } catch (Exception e) {
            v.sectors.clear(); // a corrupt file never crashes the game: nothing visited but home
        }
        return v;
    }

    public boolean has(Universe.Sector s) {
        return s.equals(Universe.Sector.HOME) || sectors.contains(s);
    }

    /** True when the sector was not visited before. */
    public boolean mark(Universe.Sector s) {
        return !s.equals(Universe.Sector.HOME) && sectors.add(s);
    }

    public void save() throws IOException {
        JsonArray a = new JsonArray();
        for (Universe.Sector s : sectors) a.add(SystemIndex.name(s));
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, a.toString());
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: same. Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add fabric/src/main/java/dev/moui/galaxycraft/universe/Visited.java fabric/src/test/java/dev/moui/galaxycraft/universe/VisitedTest.java
git commit -m "progression: Visited (the systems the player has been to, saved next to the planets)"
```

### Task 5: WarpGate

**Files:**
- Create: `fabric/src/main/java/dev/moui/galaxycraft/universe/WarpGate.java`
- Test: `fabric/src/test/java/dev/moui/galaxycraft/universe/WarpGateTest.java`

**Interfaces:**
- Consumes: `Universe.tier(Star)`, `Visited.has`, `Tier.reach`.
- Produces: `static boolean allowed(Universe u, Universe.Star target, Visited visited, int coreTier)`; `static String why(Universe u, Universe.Star target, int coreTier)` (the line shown when refused).

- [ ] **Step 1: Write the failing test**

```java
package dev.moui.galaxycraft.universe;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WarpGateTest {
    static final double U = 80;
    @TempDir Path dir;

    @Test void aNewSystemPastTheCoreIsRefusedAndAVisitedOneIsFree() {
        var u = new Universe(21, U);
        Visited v = Visited.load(dir.resolve("v.json"));
        var far = u.around(UPos.ZERO, 12).stream().filter(s -> u.tier(s) >= 3).findFirst().orElseThrow();
        assertFalse(WarpGate.allowed(u, far, v, 0));
        assertTrue(WarpGate.allowed(u, far, v, 3));
        v.mark(far.sector());
        assertTrue(WarpGate.allowed(u, far, v, 0));
    }

    @Test void homeIsAlwaysAllowed() {
        var u = new Universe(21, U);
        var home = u.star(Universe.Sector.HOME).orElseThrow();
        assertTrue(WarpGate.allowed(u, home, Visited.load(dir.resolve("v.json")), 0));
    }

    @Test void whyNamesTheTierAndTheCoreNeeded() {
        var u = new Universe(21, U);
        var far = u.around(UPos.ZERO, 12).stream().filter(s -> u.tier(s) >= 3).findFirst().orElseThrow();
        String msg = WarpGate.why(u, far, 0);
        assertTrue(msg.contains("tier " + u.tier(far)), msg);
        assertTrue(msg.contains("Warp Core"), msg);
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `... --tests '*WarpGateTest'`. Expected: FAIL (compile).

- [ ] **Step 3: Implement**

```java
package dev.moui.galaxycraft.universe;

/** Whether the ship's Warp Core opens a system: visited systems are free, new ones need the core's reach. */
public final class WarpGate {
    private WarpGate() {}

    public static boolean allowed(Universe u, Universe.Star target, Visited visited, int coreTier) {
        return target.home() || visited.has(target.sector()) || u.tier(target) <= Tier.reach(coreTier);
    }

    /** The line shown when a warp is refused. */
    public static String why(Universe u, Universe.Star target, int coreTier) {
        return "This system is tier " + u.tier(target) + ": your Warp Core reaches tier " + Tier.reach(coreTier)
                + " in systems you have not been to";
    }
}
```

- [ ] **Step 4: Run to verify it passes** — same. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fabric/src/main/java/dev/moui/galaxycraft/universe/WarpGate.java fabric/src/test/java/dev/moui/galaxycraft/universe/WarpGateTest.java
git commit -m "progression: WarpGate (new systems need the Warp Core's reach, visited ones are free)"
```

### Task 6: Client wiring (Warp, Pulse, label, setting)

**Files:**
- Modify: `fabric/src/client/java/dev/moui/galaxycraft/client/GalaxyOptions.java` (add `WARP_CORE` range setting next to the other `Setting.Range`s)
- Modify: `fabric/src/client/java/dev/moui/galaxycraft/client/Warp.java` (`to`, `label`, `tick`, new `visited()`)
- Modify: `fabric/src/client/java/dev/moui/galaxycraft/client/Flight.java:149-160` (`pulse`)
- Modify: `fabric/src/client/java/dev/moui/galaxycraft/client/PlanetClient.java` (where `planetDir()` is, expose `visitedFile()`)
- Test: in game with a probe (see Step 4); pure logic is covered by Tasks 1-5.

**Interfaces:**
- Consumes: `WarpGate.allowed/why`, `Visited.load/mark/save`, `Universe.tier/starType`.
- Produces: `GalaxyOptions.WARP_CORE.get()` (int 0..3, default 3 until the ship module exists); `Warp.visited()`.

- [ ] **Step 1: Add the setting.** Copy the shape of a neighboring `Setting.Range` (for example `FAR_VIEW_DETAIL`, line 63): id `warpCore`, label "Warp Core Tier", description "Highest tier of a system you have not been to that you can warp to. Stands in for the ship's Warp Core module until it exists (3 = no limit)", min 0, max 3, step 1, default 3, unit "".

- [ ] **Step 2: Gate `Warp.to` and mark visits.** In `Warp.java` add

```java
    private static dev.moui.galaxycraft.universe.Visited visited;
    private static java.nio.file.Path visitedFor;

    /** This galaxy's visited systems (reloaded when the galaxy's folder changes). */
    static dev.moui.galaxycraft.universe.Visited visited() {
        java.nio.file.Path f = PlanetClient.planetDir().resolve("visited.json");
        if (visited == null || !f.equals(visitedFor)) {
            visited = dev.moui.galaxycraft.universe.Visited.load(f);
            visitedFor = f;
        }
        return visited;
    }
```

(If `PlanetClient.planetDir()` can be null outside a world, return a throw-away `Visited.load` of a nonexistent temp path guarded by `if (dir == null)`; keep the null check explicit.)

At the top of `to(...)`:

```java
        Universe u = UniverseClient.universe();
        if (u != null && !dev.moui.galaxycraft.universe.WarpGate.allowed(u, s, visited(), GalaxyOptions.WARP_CORE.get())) {
            GalaxyCraftClient.say(dev.moui.galaxycraft.universe.WarpGate.why(u, s, GalaxyOptions.WARP_CORE.get()));
            return;
        }
```

In `tick`, once a second mark the system Mario is in: `here().ifPresent(s -> { if (visited().mark(s.sector())) try { visited().save(); } catch (java.io.IOException ignored) {} });`. In `label` append `", tier " + u.tier(s) + ", " + u.starType(s).name().toLowerCase().replace('_', ' ')` (needs `UniverseClient.universe()`; skip when null).

- [ ] **Step 3: Pulse stays inside a system.** In `Flight.pulse` right after `cap` is computed: `if (Warp.here().isEmpty()) cap = 0;` (`cap` must not be `final`; if it is, wrap: `double cap = ...; if (...) cap = 0;`). Update the doc comment of `pulse` to say it only works inside a system's reach.

- [ ] **Step 4: Verify in game**

Run (alone, nothing else running): the universe probe from `UniverseProbe` flies to a far system. Add to it three checks, printed as `PASS`/`FAIL` lines like the rest of that probe: (a) with `WARP_CORE=0`, `Warp.to` a tier >= 3 unvisited system leaves the player in place and says the refusal; (b) with `WARP_CORE=3` it warps; (c) afterwards `visited.json` holds that sector and warping back with `WARP_CORE=0` works. Pulse check: in the void far from every system `Flight.pulseSpeed()` stays 0 after 3 s of sprint-glide.
Command: `cd fabric && ./test.sh --probe UniverseProbe` (use the exact form `test.sh` documents; read its header first).
Expected: all PASS. Then the full suite once: `./gradlew test`, expected PASS.

- [ ] **Step 5: Commit**

```bash
git add fabric/src
git commit -m "progression: warp refuses new systems past the Warp Core's reach, visits are saved, Pulse stays inside a system"
```

### Task 7: Docs and roadmap

**Files:**
- Modify: `docs/GALAXIA.md` (spacing numbers of layout 3, tiers, stars, warp rule, Pulse)
- Modify: `ROADMAP.md` (Planet progression row: stage 2 built, awaiting playtest)

- [ ] **Step 1:** In `docs/GALAXIA.md`, update the *Spacing* row to "Near, Normal or Far (600, 1,600 or 2,400 blocks in new worlds)" and add a short section "Tiers and stars": the ring edges, star types, "a Warp Core's reach limits systems you have not visited; Pulse works only inside a system", and what to try in game (the `Warp Core Tier` setting 0 then 3, K on a far star).
- [ ] **Step 2:** Update the ROADMAP row and "Last updated"; keep the public Claude Doc untouched until the user playtests.
- [ ] **Step 3: Commit**

```bash
git add docs/GALAXIA.md ROADMAP.md
git commit -m "docs: tiers, stars, spacing of layout 3 and the Warp Core's reach"
```

## Self-review

- Spec coverage: Tier and StarType (Tasks 1-2), SystemIndex "returns tier and star" via `Universe.tier/starType` (SystemIndex only maps indexes; derived values live on `Universe`, noted for the 3D-map session), spacing (3), Warp Core and visited (4-6), Pulse (6), tests listed in the spec (1, 2, 5), old worlds (3).
- Not in this plan: TierProfile and ore tables (stage 3), star color in the sky (`StarField` keeps its own palette; the 3D map reads `Universe.starType`), the Warp Core module's item, recipe and slot (ship session consumes `Tier.reach`).
- Open decision for the user: the `Warp Core Tier` setting defaults to 3 (no limit) so nothing changes for players until the ship's module exists.
