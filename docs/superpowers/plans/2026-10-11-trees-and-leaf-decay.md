# Trees whole and leaves that fall — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Trees on generated planets come out whole (no log missing, no trunk floating over the grass), and leaves fall once no log holds them, as in Minecraft.

**Architecture:** `Vegetation.place` becomes whole-or-nothing for the solid parts of a tree (logs), measured first by a test over real generated planets. Leaves are planted as Minecraft grew them (not forced `persistent`); the shadow dimension mirrors every leaf as persistent (so it never decays there by itself) and the decay becomes one pure rule in the voxel layer (`LeafDecay`) that `PlanetSession` runs after any change, removing leaves through the shadow so Minecraft makes the drops.

**Tech Stack:** Java 21, Fabric (Minecraft 1.21+ mappings in `client/`), JUnit 5, Gradle (`fabric/`).

**Spec:** `docs/superpowers/specs/2026-10-11-galaxycraft-planet-progression-design.md` (section "Trees (first piece, ships alone)"). Background: `docs/ARBOLES.md`.

## Global Constraints

- Same seed, same planet: planting stays deterministic and in a fixed order (spec "Tests").
- Leaf decay reach is **6** blocks of leaf-path to a log (Minecraft: distance 7 decays); leaves decay "over a few ticks", not in one frame (spec).
- A leaf with `persistent = true` (placed by the player) never decays; it still carries distance for its neighbors, as in Minecraft.
- Visited planets keep their saved blocks: old saved trees keep their `persistent` leaves (no migration).
- Pure code in `voxel/` has no Minecraft classes (unit tests run offline); Minecraft types only in `client/` and `shadow/`.
- Never run `tools/gxvoxel.sh` or `tools/gxplay.sh` while another session runs them (shared Dolphin); only JUnit here.

## Review Focus

- A tree planted beside an overhang or cave ceiling (1.7 terrain): all its logs or none, never a trunk with a gap (Task 2 test).
- A tree on a face edge or cube corner keeps its logs contiguous (Task 2 test, over generated planets of radius 32 and 64).
- A crown shared by two trees: breaking one tree's logs must not drop leaves still held by the other's logs (Task 4 test `twoLogsHoldOneCrown`).
- Leaves a player placed (persistent) next to a falling tree stay (Task 4 test `persistentLeavesStay`).
- Breaking a log far from Mario (in a column the shadow has not mirrored) still drops the leaves, once, with their drops (Task 6 manual check).
- A planet saved before this change loads with its old leaves untouched and does not crash on a leaf with no log (Task 4 test `leafWithNoLogAtLoadIsLeftAlone`: `touch` only looks around changed cells).

---

## File Structure

- Modify `fabric/src/main/java/dev/moui/galaxycraft/voxel/gen/Vegetation.java` — `place`: logs must fit.
- Create `fabric/src/test/java/dev/moui/galaxycraft/voxel/gen/TreeIntegrityTest.java` — trees on generated planets.
- Modify `fabric/src/main/java/dev/moui/galaxycraft/voxel/Blocks.java` — `log(int)`, `persistentLeaves(int)`.
- Modify `fabric/src/client/java/dev/moui/galaxycraft/client/McBlocks.java` — the two answers from Minecraft.
- Create `fabric/src/main/java/dev/moui/galaxycraft/voxel/LeafDecay.java` — the rule and its queue.
- Create `fabric/src/test/java/dev/moui/galaxycraft/voxel/LeafDecayTest.java`.
- Modify `fabric/src/client/java/dev/moui/galaxycraft/client/McVegetation.java` — stop forcing persistent.
- Modify `fabric/src/main/java/dev/moui/galaxycraft/shadow/ShadowWorld.java` — mirror leaves persistent, keep the planet's flag on the way back, `decay`.
- Modify `fabric/src/main/java/dev/moui/galaxycraft/voxel/PlanetSession.java` — owns the queue.
- Modify `fabric/src/client/java/dev/moui/galaxycraft/client/ShadowLink.java` — feeds changes and sets the remover.
- Modify `docs/ARBOLES.md` — what changed.

---

### Task 1: Measure broken trees on generated planets

**Files:**
- Create: `fabric/src/test/java/dev/moui/galaxycraft/voxel/gen/TreeIntegrityTest.java`

**Interfaces:**
- Consumes: `PlanetGenerator.cells(PlanetBlueprint, Vegetation.Library, ToIntFunction<String>)` returning `PlanetGenerator.Cells(grid, depth, cells, biomes)`; `Vegetation.Library`, `Vegetation.Patch`, `Vegetation.Thing(ax, az, dx[], dy[], dz[], blocks[])`; `PlanetBlueprint.standard(name, radius).withMode(GENERATED).withBiome(seed, biome, size).withAir(a).withUnderground(0, false, 0).withPlants(100)`.
- Produces: the test class and its helper `Tally scan(PlanetGenerator.Cells)` (used by Task 2's assertion).

- [ ] **Step 1: Write the test (it must report, then fail on today's code if trees break)**

```java
package dev.moui.galaxycraft.voxel.gen;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.voxel.Blocks;
import dev.moui.galaxycraft.voxel.CubeSphere;
import dev.moui.galaxycraft.voxel.PlanetBlueprint;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/** Oaks planted on generated 1.7 terrain: every one has all its five logs, standing on the ground. */
class TreeIntegrityTest {
    private static final Map<String, Integer> IDS = new ConcurrentHashMap<>();
    private static final int LOGS = 5;

    private static synchronized int id(String name) {
        return IDS.computeIfAbsent(name, k -> IDS.size() + 1);
    }

    /** An oak: dirt under the trunk, five logs, a 3×3 crown above and around the top log. */
    private static Vegetation.Thing oak() {
        List<int[]> at = new ArrayList<>();
        List<String> names = new ArrayList<>();
        at.add(new int[] {0, 0, 0});
        names.add("minecraft:dirt");
        for (int y = 1; y <= LOGS; y++) {
            at.add(new int[] {0, y, 0});
            names.add("minecraft:oak_log");
        }
        for (int y = 4; y <= 6; y++)
            for (int x = -1; x <= 1; x++)
                for (int z = -1; z <= 1; z++)
                    if (!(x == 0 && z == 0 && y <= LOGS)) {
                        at.add(new int[] {x, y, z});
                        names.add("minecraft:oak_leaves");
                    }
        int n = at.size();
        int[] dx = new int[n], dy = new int[n], dz = new int[n];
        for (int b = 0; b < n; b++) {
            dx[b] = at.get(b)[0];
            dy[b] = at.get(b)[1];
            dz[b] = at.get(b)[2];
        }
        return new Vegetation.Thing(0, 0, dx, dy, dz, names.toArray(String[]::new));
    }

    private static final Vegetation.Library LIBRARY = biome -> List.of(new Vegetation.Patch(List.of(oak())));

    record Tally(int trees, int gaps, int floating) {}

    /** For every column holding logs: its logs are a run of LOGS, and under the run is solid ground. */
    static Tally scan(PlanetGenerator.Cells c) {
        CubeSphere g = c.grid();
        char log = (char) id("minecraft:oak_log");
        int trees = 0, gaps = 0, floating = 0;
        for (int col = 0; col < g.cellCount() / g.layers; col++) {
            int base = col * g.layers, first = -1, count = 0;
            for (int k = 0; k < g.layers; k++)
                if (c.cells()[base + k] == log) {
                    if (first < 0) first = k;
                    count++;
                }
            if (count == 0) continue;
            trees++;
            boolean run = count == LOGS && c.cells()[base + first + LOGS - 1] == log;
            if (!run) gaps++;
            char under = c.cells()[base + first - 1];
            if (under == Blocks.AIR || under == (char) id("minecraft:oak_leaves") || under == (char) id("minecraft:water")) floating++;
        }
        return new Tally(trees, gaps, floating);
    }

    private static PlanetGenerator.Cells planet(int radius, long seed) {
        PlanetBlueprint bp = PlanetBlueprint.standard("t", radius).withMode(PlanetBlueprint.Mode.GENERATED)
                .withBiome(seed, PlanetBlueprint.RANDOM, PlanetBlueprint.AUTO).withUnderground(0, false, 0).withPlants(100);
        return PlanetGenerator.cells(bp, LIBRARY, TreeIntegrityTest::id);
    }

    @Test void everyOakIsWholeAndOnTheGround() {
        int trees = 0, gaps = 0, floating = 0;
        for (int radius : new int[] {32, 64})
            for (long seed = 1; seed <= 4; seed++) {
                Tally t = scan(planet(radius, seed));
                trees += t.trees();
                gaps += t.gaps();
                floating += t.floating();
            }
        System.out.println("oaks " + trees + ", with a log missing " + gaps + ", floating " + floating);
        assertTrue(trees > 50, "the planets grew trees: " + trees);
        assertEquals(0, gaps, "oaks with a log missing");
        assertEquals(0, floating, "oaks over air, leaves or water");
    }
}
```

- [ ] **Step 2: Run it**

Run: `cd fabric && ./gradlew test --tests 'dev.moui.galaxycraft.voxel.gen.TreeIntegrityTest' -i`
Expected: compiles. Either PASS (then the user's broken trees come from something this fixture does not model: stop and report with the printed line — do not guess a fix) or FAIL with the printed counts of gaps/floating. Write the counts into the commit message.

- [ ] **Step 3: Commit**

```bash
git add fabric/src/test/java/dev/moui/galaxycraft/voxel/gen/TreeIntegrityTest.java
git commit -m "test: oaks on generated 1.7 terrain keep all their logs and stand on the ground (measures the broken trees)"
```

---

### Task 2: A tree's logs fit, or the tree is not planted

**Files:**
- Modify: `fabric/src/main/java/dev/moui/galaxycraft/voxel/gen/Vegetation.java` (method `place`, loop at the end of the method)
- Test: `fabric/src/test/java/dev/moui/galaxycraft/voxel/gen/TreeIntegrityTest.java`

**Interfaces:**
- Consumes: `Vegetation.place(...)` as it is; `Thing.blocks()` are Minecraft block names.
- Produces: `static boolean solidPart(String block)` (package-private): true for a log, wood, stem or hyphae block name.

Cause being fixed (found by reading `place`): the final loop does `if (t.dy()[b] != 0 && !open(cells[cell], ids)) continue;` for **every** block, so a log whose cell is taken (an overhang, a cave ceiling, water on 1.7's 3D terrain) is silently skipped while the crown above is still planted: a trunk with a missing log.

- [ ] **Step 1: Make sure the Task 1 test fails** (if it passed in Task 1, stop: this task does not apply — report to the user).

- [ ] **Step 2: Add the rule**

In `Vegetation.java`, before the loop that writes cells (the loop `for (int b = 0; b < at.length; b++) { int cell = at[b]; ...`), insert:

```java
        // Logs are the tree's body: where one does not fit (an overhang, a cave roof, water) the
        // tree is not planted, instead of standing with a log missing under its crown.
        for (int b = 0; b < at.length; b++)
            if (t.dy()[b] >= 1 && solidPart(t.blocks()[b]) && !open(cells[at[b]], ids)) return false;
```

and add the helper next to `open`:

```java
    /** A log, wood, stem or hyphae block: what a tree's body is made of (leaves, vines and the rest may be cut). */
    static boolean solidPart(String block) {
        return block.endsWith("_log") || block.endsWith("_wood") || block.endsWith("_stem") || block.endsWith("_hyphae");
    }
```

- [ ] **Step 3: Run the tests**

Run: `cd fabric && ./gradlew test --tests 'dev.moui.galaxycraft.voxel.gen.*' -i`
Expected: `TreeIntegrityTest` PASS (gaps 0), `VegetationTest`, `PlanetGeneratorTest` PASS. If `floating` is still above 0, stop and report the printed counts to the user before changing `ground`/`bare`: the cause is then in how `height[col]` or `bare[col]` are computed (`PlanetGenerator`), a different fix.

- [ ] **Step 4: Commit**

```bash
git add fabric/src/main/java/dev/moui/galaxycraft/voxel/gen/Vegetation.java
git commit -m "trees: planted whole or not at all, a log that does not fit (overhang, cave roof, water) cancels the tree"
```

---

### Task 3: Say which blocks are logs and which leaves are the player's

**Files:**
- Modify: `fabric/src/main/java/dev/moui/galaxycraft/voxel/Blocks.java` (after `leaves`)
- Modify: `fabric/src/client/java/dev/moui/galaxycraft/client/McBlocks.java:572` (after `leaves`)

**Interfaces:**
- Produces: `Blocks.log(int id): boolean` (default false), `Blocks.persistentLeaves(int id): boolean` (default false). Used by `LeafDecay` (Task 4).

- [ ] **Step 1: Add the defaults** in `Blocks.java`, right after the `leaves` method:

```java
    /** Whether it is a log (or wood, stem): what holds leaves up. */
    default boolean log(int id) {
        return false;
    }

    /** Whether it is leaves a player placed (Minecraft's persistent = true): they never fall. */
    default boolean persistentLeaves(int id) {
        return false;
    }
```

- [ ] **Step 2: Implement in `McBlocks`**, right after its `leaves` override:

```java
    @Override public boolean log(int id) {
        return state(id).is(net.minecraft.tags.BlockTags.LOGS);
    }

    @Override public boolean persistentLeaves(int id) {
        BlockState s = state(id);
        return s.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock
                && s.hasProperty(BlockStateProperties.PERSISTENT) && s.getValue(BlockStateProperties.PERSISTENT);
    }
```

- [ ] **Step 3: Compile**

Run: `cd fabric && ./gradlew compileJava compileClientJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add fabric/src/main/java/dev/moui/galaxycraft/voxel/Blocks.java fabric/src/client/java/dev/moui/galaxycraft/client/McBlocks.java
git commit -m "blocks: log() and persistentLeaves() so the voxel layer can tell what holds a leaf"
```

---

### Task 4: The decay rule (pure) and its queue

**Files:**
- Create: `fabric/src/main/java/dev/moui/galaxycraft/voxel/LeafDecay.java`
- Test: `fabric/src/test/java/dev/moui/galaxycraft/voxel/LeafDecayTest.java`

**Interfaces:**
- Consumes: `Blocks.leaves(id)`, `Blocks.log(id)`, `Blocks.persistentLeaves(id)` (Task 3); `VoxelPlanet.get(cell)`, `VoxelPlanet.blocks` (public field), `VoxelPlanet.grid.neighbor(cell, side)` (side 0..5, -1 if none).
- Produces:
  - `LeafDecay.REACH = 6`
  - `static boolean held(VoxelPlanet p, int leaf)`: a log within REACH leaf-steps.
  - `static int[] orphans(VoxelPlanet p, int... changed)`: non-persistent leaves within REACH of the changed cells that no log holds.
  - `LeafDecay.Queue`: `void touch(VoxelPlanet p, int... changed)`, `int[] tick(VoxelPlanet p)` (cells due now and still orphan, at most 64 per call).

- [ ] **Step 1: Write the failing test**

```java
package dev.moui.galaxycraft.voxel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class LeafDecayTest {
    /** CubeBlocks with glass as leaves, obsidian as logs and a slab as leaves a player placed. */
    private static final Blocks B = new Blocks() {
        final Blocks c = CubeBlocks.INSTANCE;
        @Override public BlockInfo info(int id) { return c.info(id); }
        @Override public int id(Material m) { return c.id(m); }
        @Override public Material material(int id) { return c.material(id); }
        @Override public int fluidState(int fluid, int level) { return c.fluidState(fluid, level); }
        @Override public boolean faceVisible(int id, int n, int side) { return c.faceVisible(id, n, side); }
        @Override public String name(int id) { return c.name(id); }
        @Override public int parse(String name) { return c.parse(name); }
        @Override public int atlasColumns() { return c.atlasColumns(); }
        @Override public int atlasRows() { return c.atlasRows(); }
        @Override public boolean leaves(int id) { return id == CubeBlocks.GLASS || id == CubeBlocks.SLAB; }
        @Override public boolean log(int id) { return id == c.id(Material.OBSIDIAN); }
        @Override public boolean persistentLeaves(int id) { return id == CubeBlocks.SLAB; }
    };
    private static final int LEAF = CubeBlocks.GLASS, PLACED = CubeBlocks.SLAB, LOG = B.id(Material.OBSIDIAN);

    private static VoxelPlanet planet() {
        return VoxelPlanet.ofRadius(16, B);
    }

    /** A cell in the air above the middle of a column, with room above. */
    private static int base(VoxelPlanet p) {
        int layers = p.grid.layers;
        return (p.grid.cellCount() / layers / 2) * layers + layers - 20;
    }

    /** A log at base and leaves in a column above it, up to top (cells above are +1 each). */
    private static int trunkWithLeaves(VoxelPlanet p, int top) {
        int b = base(p);
        p.set(b, LOG);
        for (int k = 1; k <= top; k++) p.set(b + k, LEAF);
        return b;
    }

    @Test void leavesWithALogAreHeld() {
        VoxelPlanet p = planet();
        int b = trunkWithLeaves(p, 6);
        assertTrue(LeafDecay.held(p, b + 6));
        assertEquals(0, LeafDecay.orphans(p, b + 3).length);
    }

    @Test void aLeafSevenStepsFromTheLogFalls() {
        VoxelPlanet p = planet();
        int b = trunkWithLeaves(p, 7);
        assertTrue(LeafDecay.held(p, b + 6));
        assertFalse(LeafDecay.held(p, b + 7));
    }

    @Test void removingTheLogDropsEveryLeafItHeld() {
        VoxelPlanet p = planet();
        int b = trunkWithLeaves(p, 5);
        p.set(b, Blocks.AIR);
        int[] gone = LeafDecay.orphans(p, b);
        assertEquals(5, gone.length, Arrays.toString(gone));
    }

    @Test void twoLogsHoldOneCrown() {
        VoxelPlanet p = planet();
        int b = trunkWithLeaves(p, 5);
        p.set(b + 6, LOG); // a second tree's log on top of the crown
        p.set(b, Blocks.AIR);
        assertEquals(0, LeafDecay.orphans(p, b).length);
    }

    @Test void persistentLeavesStayButCarryTheDistance() {
        VoxelPlanet p = planet();
        int b = trunkWithLeaves(p, 3);
        p.set(b + 4, PLACED);
        p.set(b + 5, LEAF);
        p.set(b, Blocks.AIR);
        int[] gone = LeafDecay.orphans(p, b);
        assertTrue(Arrays.stream(gone).noneMatch(c -> c == b + 4), "the player's leaf stays");
        assertEquals(List.of(b + 1, b + 2, b + 3, b + 5), Arrays.stream(gone).sorted().boxed().toList());
    }

    @Test void leafWithNoLogAtLoadIsLeftAlone() {
        VoxelPlanet p = planet();
        int b = base(p);
        p.set(b + 1, LEAF); // an old saved leaf with no log, far from any change
        p.set(b + 50, LOG);
        p.set(b + 50, Blocks.AIR);
        assertEquals(0, LeafDecay.orphans(p, b + 50).length);
    }

    @Test void theQueueLetsLeavesGoOverSeveralTicksNotAtOnce() {
        VoxelPlanet p = planet();
        int b = trunkWithLeaves(p, 5);
        p.set(b, Blocks.AIR);
        LeafDecay.Queue q = new LeafDecay.Queue();
        q.touch(p, b);
        assertEquals(0, q.tick(p).length, "not on the very first tick");
        int total = 0;
        for (int t = 0; t < 60; t++) total += q.tick(p).length;
        assertEquals(5, total);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd fabric && ./gradlew test --tests 'dev.moui.galaxycraft.voxel.LeafDecayTest'`
Expected: FAIL (compilation: `LeafDecay` not defined).

- [ ] **Step 3: Implement**

```java
package dev.moui.galaxycraft.voxel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Minecraft's leaf decay on a planet's own cells: a leaf stays while a log is within {@link #REACH}
 * steps through leaves (a leaf next to a log is 1 away; 7 falls). Leaves a player placed
 * (persistent) never fall but carry the distance. Only cells near a change are looked at, so an old
 * planet's leaf with no log is left alone until something changes around it.
 */
public final class LeafDecay {
    public static final int REACH = 6;
    private static final int MAX_PER_TICK = 64, MIN_DELAY = 2, SPREAD = 38;

    private LeafDecay() {}

    /** Whether a log is at most REACH steps from the leaf, through leaves. */
    public static boolean held(VoxelPlanet p, int leaf) {
        Set<Integer> seen = new HashSet<>();
        ArrayDeque<int[]> todo = new ArrayDeque<>();
        todo.add(new int[] {leaf, 0});
        seen.add(leaf);
        while (!todo.isEmpty()) {
            int[] at = todo.poll();
            for (int s = 0; s < 6; s++) {
                int n = p.grid.neighbor(at[0], s);
                if (n < 0 || !seen.add(n)) continue;
                int id = p.get(n);
                if (p.blocks.log(id)) return true; // a log next to a leaf at depth d is d + 1 steps
                if (p.blocks.leaves(id) && at[1] + 1 < REACH) todo.add(new int[] {n, at[1] + 1});
            }
        }
        return false;
    }

    /** Falling leaves among those within REACH of the changed cells (not the persistent ones). */
    public static int[] orphans(VoxelPlanet p, int... changed) {
        Set<Integer> seen = new HashSet<>();
        ArrayDeque<int[]> todo = new ArrayDeque<>();
        for (int c : changed) {
            if (c < 0) continue;
            todo.add(new int[] {c, 0});
            seen.add(c);
        }
        List<Integer> out = new ArrayList<>();
        while (!todo.isEmpty()) {
            int[] at = todo.poll();
            for (int s = 0; s < 6; s++) {
                int n = p.grid.neighbor(at[0], s);
                if (n < 0 || !seen.add(n)) continue;
                int id = p.get(n);
                if (!p.blocks.leaves(id)) continue;
                if (!p.blocks.persistentLeaves(id) && !held(p, n)) out.add(n);
                if (at[1] + 1 < REACH) todo.add(new int[] {n, at[1] + 1});
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Leaves due to fall, each after its own few ticks, so a tree thins out instead of vanishing. */
    public static final class Queue {
        private final Map<Integer, Integer> due = new HashMap<>();
        private final Random random = new Random(0x1EAF);
        private int now;

        /** Cells of p changed: the leaves they no longer hold are scheduled. */
        public void touch(VoxelPlanet p, int... changed) {
            for (int c : orphans(p, changed)) due.putIfAbsent(c, now + MIN_DELAY + random.nextInt(SPREAD));
        }

        /** One tick: the leaves whose time has come and that are still orphans. */
        public int[] tick(VoxelPlanet p) {
            now++;
            if (due.isEmpty()) return new int[0];
            List<Integer> go = new ArrayList<>();
            for (var it = due.entrySet().iterator(); it.hasNext() && go.size() < MAX_PER_TICK; ) {
                var e = it.next();
                if (e.getValue() > now) continue;
                it.remove();
                int id = p.get(e.getKey());
                if (p.blocks.leaves(id) && !p.blocks.persistentLeaves(id) && !held(p, e.getKey())) go.add(e.getKey());
            }
            return go.stream().mapToInt(Integer::intValue).toArray();
        }

        public boolean isEmpty() {
            return due.isEmpty();
        }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd fabric && ./gradlew test --tests 'dev.moui.galaxycraft.voxel.LeafDecayTest'`
Expected: 7 tests PASS. (`held`: a log right next to the leaf is found at depth 0, standing for distance 1; a leaf at index 6 above the log is found at depth 5, a leaf at index 7 would need depth 6 → `at[1] + 1 < REACH` stops it: matches `aLeafSevenStepsFromTheLogFalls`.)

- [ ] **Step 5: Commit**

```bash
git add fabric/src/main/java/dev/moui/galaxycraft/voxel/LeafDecay.java fabric/src/test/java/dev/moui/galaxycraft/voxel/LeafDecayTest.java
git commit -m "voxel: leaf decay rule (a log within 6 leaf steps) and a queue that lets leaves go over a few ticks"
```

---

### Task 5: Plant leaves as Minecraft grew them; the shadow mirrors them persistent

**Files:**
- Modify: `fabric/src/client/java/dev/moui/galaxycraft/client/McVegetation.java:119`
- Modify: `fabric/src/main/java/dev/moui/galaxycraft/shadow/ShadowWorld.java` (`state(int)` at the end of the file, and `changed` at line ~969)

**Interfaces:**
- Consumes: `Block.stateById`, `LeavesBlock`, `BlockStateProperties.PERSISTENT`.
- Produces: planet cells hold natural leaves with `persistent=false`; the shadow always holds persistent leaves (so Minecraft never decays them there); a change coming back keeps the planet's flag.

- [ ] **Step 1: `McVegetation`**: delete the forcing line (and fix the method's comment):

```java
                    if (s.hasProperty(BlockStateProperties.PERSISTENT)) s = s.setValue(BlockStateProperties.PERSISTENT, true);
```

Replace the javadoc sentence "Leaves stay: on the planet only the blocks near Mario are mirrored, and leaves whose log is past that edge would decay." with "Leaves stay with the state Minecraft grew them in; the shadow mirrors them persistent, and the planet's own rule ({@link dev.moui.galaxycraft.voxel.LeafDecay}) lets them fall."

- [ ] **Step 2: `ShadowWorld.state`**: leaves are persistent in the shadow:

```java
    private static BlockState state(int id) {
        BlockState s = Block.stateById(id);
        if (s == null) return Blocks.AIR.defaultBlockState();
        // The planet decides when a leaf falls (voxel/LeafDecay): here none does by itself.
        if (s.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock
                && s.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.PERSISTENT))
            return s.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.PERSISTENT, true);
        return s;
    }
```

- [ ] **Step 3: `ShadowWorld.changed`**: a leaf coming back keeps the planet's flag:

Replace `changes.add(new Change(planet, cell, Block.getId(now), applied));` with:

```java
        changes.add(new Change(planet, cell, Block.getId(naturalLeaf(planet.get(cell), now)), applied));
```

and add:

```java
    /** The shadow holds every leaf persistent: one the planet holds natural (not persistent) goes back natural. */
    private static BlockState naturalLeaf(int planetId, BlockState now) {
        var persistent = net.minecraft.world.level.block.state.properties.BlockStateProperties.PERSISTENT;
        if (!(now.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) || !now.hasProperty(persistent)) return now;
        BlockState was = Block.stateById(planetId);
        if (was != null && was.getBlock() == now.getBlock() && was.hasProperty(persistent) && !was.getValue(persistent))
            return now.setValue(persistent, false);
        return now;
    }
```

- [ ] **Step 4: Compile and run the whole offline suite**

Run: `cd fabric && ./gradlew compileJava compileClientJava test`
Expected: BUILD SUCCESSFUL, all tests PASS.

- [ ] **Step 5: Commit**

```bash
git add fabric/src/client/java/dev/moui/galaxycraft/client/McVegetation.java fabric/src/main/java/dev/moui/galaxycraft/shadow/ShadowWorld.java
git commit -m "leaves: planted as grown (not persistent); the shadow mirrors them persistent and sends them back natural"
```

---

### Task 6: Run the decay in the session and take the leaves out through the shadow

**Files:**
- Modify: `fabric/src/main/java/dev/moui/galaxycraft/voxel/PlanetSession.java` (field near `unsaved`; `breakBlock` at ~850; `applyExternal` at ~944; `update` at ~542)
- Modify: `fabric/src/main/java/dev/moui/galaxycraft/shadow/ShadowWorld.java` (new `decay`, next to `destroy` at ~308)
- Modify: `fabric/src/client/java/dev/moui/galaxycraft/client/ShadowLink.java` (`tick`, lines ~39-55)
- Test: `fabric/src/test/java/dev/moui/galaxycraft/voxel/PlanetSessionTest.java`

**Interfaces:**
- Consumes: `LeafDecay.Queue.touch/tick` (Task 4); `ShadowWorld.attach`, `ops`, `mirrorNow`.
- Produces: `PlanetSession.touchLeaves(int... cells)`; `PlanetSession.setLeafRemover(java.util.function.IntConsumer)` (null = remove straight from the planet); `ShadowWorld.decay(VoxelPlanet, int cell)`.

- [ ] **Step 1: Failing test** in `PlanetSessionTest.java` (reuse the file's own helpers for building a session on a planet; mirror the setup of `breakingFromAboveSendsTheChangedChunks`, line 539, for the session and planet, then):

```java
    @Test void breakingTheLogLetsItsLeavesFallOverSomeTicks() {
        // session and planet set up exactly as in breakingFromAboveSendsTheChangedChunks
        VoxelPlanet p = session.planet();
        int log = p.blocks.id(Material.OBSIDIAN), leaf = CubeBlocks.GLASS;
        int base = /* the planet's top ground cell the test already uses */ groundCell;
        p.set(base + 1, log);
        for (int k = 2; k <= 4; k++) p.set(base + k, leaf);
        p.set(base + 1, Blocks.AIR);
        session.touchLeaves(base + 1);
        for (int t = 0; t < 80; t++) session.update(1, 1, null);
        for (int k = 2; k <= 4; k++) assertEquals(Blocks.AIR, p.get(base + k), "leaf " + k);
    }
```

NOTE for the implementer: `CubeBlocks` has no leaves, so wrap it as in `LeafDecayTest.B` (copy that anonymous `Blocks`) and build the session's planet with it; if `PlanetSessionTest` builds planets with `CubeBlocks.INSTANCE`, use `VoxelPlanet.ofRadius(16, B)` in this one test.

Run: `cd fabric && ./gradlew test --tests 'dev.moui.galaxycraft.voxel.PlanetSessionTest'`
Expected: FAIL (compilation: `touchLeaves` not defined).

- [ ] **Step 2: `PlanetSession`**

Fields (next to `unsaved`):

```java
    private final LeafDecay.Queue leaves = new LeafDecay.Queue();
    private java.util.function.IntConsumer leafRemover;
```

Methods:

```java
    /** Cells changed (a log broken, a leaf removed): the leaves they no longer hold start to fall. */
    public void touchLeaves(int... cells) {
        if (planet != null) leaves.touch(planet, cells);
    }

    /** How a falling leaf is taken out (the shadow, so Minecraft drops what it drops); null: straight from the planet. */
    public void setLeafRemover(java.util.function.IntConsumer remover) {
        leafRemover = remover;
    }
```

In `update`, right after `if (planet.fluids().tick()) unsaved = true;`:

```java
        for (int cell : leaves.tick(planet)) {
            if (leafRemover != null) leafRemover.accept(cell);
            else {
                planet.set(cell, Blocks.AIR);
                planet.settle(cell);
                unsaved = true;
                leaves.touch(planet, cell); // its neighbors may have held on through it
            }
        }
```

In `breakBlock(eye, look, creative)`, after `planet.settle(h.hit());` add `leaves.touch(planet, h.hit());`.
In `applyExternal(cell, id)`, after `planet.setQuietly(cell, id);` add `leaves.touch(planet, cell);`.

(With the shadow remover set, a removed leaf comes back through `applyExternal`, which touches its neighbors: the crown thins out layer by layer.)

- [ ] **Step 3: `ShadowWorld.decay`** (next to `destroy`):

```java
    /** A leaf the planet says no log holds: out, with the drops Minecraft gives a decayed leaf (saplings, sticks, apples). */
    public static void decay(VoxelPlanet p, int cell) {
        ops.add(level -> {
            if (p != planet || map == null) return;
            BlockPos pos = new BlockPos(map.x(cell), map.y(cell), map.z(cell));
            mirrorNow(level, pos);
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock)) return;
            Block.dropResources(state, level, pos);
            level.removeBlock(pos, false);
        });
    }
```

- [ ] **Step 4: `ShadowLink.tick`**: in the `if (p != attached || ...)` block, after `ShadowWorld.attach(p, stage);` add:

```java
            session().setLeafRemover(p == null ? null : cell -> ShadowWorld.decay(p, cell));
```

- [ ] **Step 5: Run the suite**

Run: `cd fabric && ./gradlew compileJava compileClientJava test`
Expected: BUILD SUCCESSFUL, `PlanetSessionTest` and everything else PASS.

- [ ] **Step 6: Commit**

```bash
git add fabric/src/main/java/dev/moui/galaxycraft/voxel/PlanetSession.java fabric/src/main/java/dev/moui/galaxycraft/shadow/ShadowWorld.java fabric/src/client/java/dev/moui/galaxycraft/client/ShadowLink.java fabric/src/test/java/dev/moui/galaxycraft/voxel/PlanetSessionTest.java
git commit -m "leaves fall: the session runs the decay rule after any change and removes leaves through the shadow, with Minecraft's drops"
```

---

### Task 7: Doc and the user's playtest

**Files:**
- Modify: `docs/ARBOLES.md` (append a section)

- [ ] **Step 1: Append to `docs/ARBOLES.md`**

```markdown
## Árboles enteros y hojas que caen (2026-10-11, rama `feat/planet-progression`)

- Con el terreno 1.7 (voladizos, cuevas, agua) un tronco podía no caber: `place` omitía ese bloque
  y plantaba la copa igual (tronco con un trozo faltante). Ahora el árbol se planta entero o no se
  planta (`Vegetation.solidPart`). Prueba: `TreeIntegrityTest`.
- Las hojas no caían porque `McVegetation` las forzaba a `persistent`. Ahora se plantan como
  Minecraft las hizo; la sombra las espeja persistentes (no decaen allí) y la regla es una sola,
  en `voxel/LeafDecay`: una hoja cae si ningún tronco está a 6 hojas o menos, en unos pocos ticks y
  con los drops de Minecraft (a través de `ShadowWorld.decay`). Las hojas puestas por el jugador no caen.
- Los planetas ya guardados conservan sus hojas persistentes (no caen).

### A probar en el juego (el usuario)

1. Un planeta nuevo con bosque: ningún árbol con un tronco cortado ni flotando sobre el pasto.
2. Cortar todo el tronco de un roble: las hojas caen en unos segundos y suelta palos y brotes.
3. Dos árboles con copas juntas: cortar uno deja las hojas que sostiene el otro.
4. Una hoja puesta con la mano junto al árbol no cae.
```

- [ ] **Step 2: Commit**

```bash
git add docs/ARBOLES.md
git commit -m "docs: trees whole and leaves that fall (what changed, what to try in game)"
```

- [ ] **Step 3: Stop here.** Do not merge or post a devlog: the user plays it first (memory: merge after playtest).

---

## Self-review (done)

- **Spec coverage:** "Reproduce first" → Task 1; "Plant leaves with persistent = false" → Task 5; "Leaf decay in the voxel layer… drops, over a few ticks" → Tasks 4 and 6; tests "leaf decay" and "trees" → Tasks 4, 1–2; "Old worlds" → constraint and `leafWithNoLogAtLoadIsLeftAlone`.
- **Placeholders:** Task 6's session test names `groundCell` and the session setup of an existing test: the implementer reads `breakingFromAboveSendsTheChangedChunks` (line 539) for them, as the note says.
- **Names:** `LeafDecay.held/orphans/Queue.touch/tick`, `PlanetSession.touchLeaves/setLeafRemover`, `ShadowWorld.decay`, `Blocks.log/persistentLeaves`, `Vegetation.solidPart` agree across tasks.
- **Known limit:** if Task 1 passes on the fixture, the user's broken trees are not caused by overhangs; the plan stops and reports (Task 1, Step 2).
