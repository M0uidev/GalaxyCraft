# Toads as Minecraft entities Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every Toad is a real `Villager` in the shadow dimension (hitbox, damage, death, bed/door/night AI), drawn with Kinopio, talked to with the existing dialogue box; generated huts get a 4-high room, a door and a bed.

**Architecture:** `toads.json` (`ToadStore`) stays the permanent record. The client tells the server which Toads to run (`ShadowWorld.wantToads`); a new server-thread helper `ShadowToads` spawns/maintains one tagged `Villager` per wanted Toad on free ground, keeps it out of blocks, publishes positions and deaths in thread-safe snapshots; the client writes positions back to the records, deletes dead Toads' records, aims at the entities, and draws tagged entities with the Kinopio pipeline. The walking code (`ToadWander`, `ToadWalker`) is deleted. `Huts` builds a 3-high room + roof, a door and a bed.

**Tech Stack:** Java 25, Minecraft 26.x (Fabric, Mojang mappings), JUnit 5, Fabric client gametest probes (`tools/gxvoxel.sh`, `-PgalaxycraftBlocks` style flags), Dolphin only for the final visual check.

**Spec:** `docs/superpowers/specs/2026-10-13-galaxycraft-toad-entities-design.md`

Work in the worktree `/home/moui/Documents/Games/GalaxyCraft-toad-npcs` (branch `feat/toad-npcs`). Build/test: `export JAVA_HOME=$(ls -d ~/.local/opt/jdk-25* | head -1)`; from `fabric/`: `./gradlew test --tests '<Class>' --console=plain -q`, `./gradlew compileClientJava compileGametestJava --console=plain -q`. Test results XML: `fabric/build/test-results/test/`. Fish shell: quote globs. Never `pkill -f` a pattern that matches your own shell (use `"[g]alaxycraft.hidden=true"`).

## Global Constraints

- Players see "Super Minecraft Galaxy"; code ids stay `galaxycraft`.
- A Toad entity is only ever on a planet's mirrored columns; the record is the single source of truth for existence.
- Server-thread state is touched only from `ShadowWorld.tick` or an `ops` lambda; the client reads only the volatile snapshots (`ShadowToads.positions()`, `pollDeath()`), like `ShadowWorld.entities()`.
- No Toad dies silently: a record is deleted only by a death event; an unload never deletes.
- Commit trailer on every commit: `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01QFQN9UUHwjKGZ1JtDk5fVa`.
- Do not touch the unrelated uncommitted `dolphin/galaxycraft/DevControl.*` changes.

## Review Focus

1. A Toad whose spawn spot is inside a hill, a house wall or on the very edge of a face: it must come out on free ground, never inside a block (Task 1 test, Task 7 probe).
2. Unloading the planet (walking away / `attach(null)`) must not delete any record or leave a second entity when it comes back (Task 2 test).
3. The same Toad asked for twice in a tick, or a record whose entity was removed by `/kill`: exactly one entity, a `/kill` counts as a death (Task 2, 6).
4. A hit that does not kill: the Toad lives, the record stays (Task 6 probe).
5. Mob caps and despawn must neither count nor remove a Toad (Task 3 probe).
6. A hut whose ground is sloped: door, bed and the way out stay reachable and nothing floats (Task 8 test).

---

### Task 1: FreeSpot, the pure "nearest free standing spot" search

**Files:**
- Create: `fabric/src/main/java/dev/moui/galaxycraft/toad/FreeSpot.java`
- Test: `fabric/src/test/java/dev/moui/galaxycraft/toad/FreeSpotTest.java`

**Interfaces:**
- Produces: `FreeSpot.Space { boolean solid(int x, int y, int z); }`, `static int[] FreeSpot.nearest(Space s, int x, int y, int z, int radius)` → `{x,y,z}` of a cell with `!solid(x,y,z) && !solid(x,y+1,z) && solid(x,y-1,z)` (feet cell, head cell free, ground below), the closest by squared distance (ties: lower y first), or `null`. `static boolean inside(Space s, int x, int y, int z)` → `solid(x,y,z) || solid(x,y+1,z)` for the feet/head cells.

- [ ] **Step 1: Write the failing test**

```java
package dev.moui.galaxycraft.toad;

import static org.junit.jupiter.api.Assertions.*;

import java.util.function.IntBinaryOperator;
import org.junit.jupiter.api.Test;

class FreeSpotTest {
    /** A world: ground below y=0 (solid), plus the extra solid cells given. */
    private static FreeSpot.Space world(int... solidXYZ) {
        java.util.Set<String> extra = new java.util.HashSet<>();
        for (int i = 0; i < solidXYZ.length; i += 3) extra.add(solidXYZ[i] + "," + solidXYZ[i + 1] + "," + solidXYZ[i + 2]);
        return (x, y, z) -> y < 0 || extra.contains(x + "," + y + "," + z);
    }

    @Test void freeGroundStaysWhereItIs() {
        assertArrayEquals(new int[] {3, 0, 4}, FreeSpot.nearest(world(), 3, 0, 4, 6));
    }

    @Test void insideTheGroundComesOutOnTop() {
        // asked deep in the ground (y -3): the nearest standing spot is on top
        assertArrayEquals(new int[] {0, 0, 0}, FreeSpot.nearest(world(), 0, -3, 0, 6));
    }

    @Test void insideAHillComesOutBesideItNotThroughIt() {
        // a solid 5x5 pillar 4 high: asked in its middle, the spot is on top of it or beside it, never inside
        int[] cells = new int[5 * 5 * 4 * 3];
        int n = 0;
        for (int x = -2; x <= 2; x++) for (int y = 0; y < 4; y++) for (int z = -2; z <= 2; z++) { cells[n++] = x; cells[n++] = y; cells[n++] = z; }
        FreeSpot.Space w = world(cells);
        int[] p = FreeSpot.nearest(w, 0, 2, 0, 8);
        assertNotNull(p);
        assertFalse(FreeSpot.inside(w, p[0], p[1], p[2]));
        assertTrue(w.solid(p[0], p[1] - 1, p[2]));
    }

    @Test void aOneBlockCaveIsNotAStandingSpot() {
        // ceiling at y=1 over (0,0,0): the head cell is solid, so (0,0,0) is refused
        int[] p = FreeSpot.nearest(world(0, 1, 0), 0, 0, 0, 3);
        assertNotNull(p);
        assertFalse(p[0] == 0 && p[2] == 0);
    }

    @Test void nothingNearGivesNull() {
        FreeSpot.Space allSolid = (x, y, z) -> true;
        assertNull(FreeSpot.nearest(allSolid, 0, 0, 0, 4));
    }

    @Test void insideReportsFeetAndHead() {
        FreeSpot.Space w = world(2, 0, 2, 5, 1, 5);
        assertTrue(FreeSpot.inside(w, 2, 0, 2));
        assertTrue(FreeSpot.inside(w, 5, 0, 5)); // head cell solid
        assertFalse(FreeSpot.inside(w, 0, 0, 0));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd fabric && ./gradlew test --tests '*FreeSpotTest' --console=plain -q`
Expected: compile FAIL (`FreeSpot` not defined).

- [ ] **Step 3: Implement**

```java
package dev.moui.galaxycraft.toad;

/**
 * Where a two-block-high walker can stand: the pure search the Toads use to come out of blocks
 * (on spawn, and each second if one finds itself inside one). Cells are integer block coordinates
 * of whatever space the caller has (the shadow dimension's); the caller says which are solid.
 */
public final class FreeSpot {
    /** The space being searched. */
    public interface Space {
        boolean solid(int x, int y, int z);
    }

    private FreeSpot() {}

    /** Whether a walker whose feet are in this cell has its feet or head inside a block. */
    public static boolean inside(Space s, int x, int y, int z) {
        return s.solid(x, y, z) || s.solid(x, y + 1, z);
    }

    /** The closest cell to (x, y, z) within radius where a walker can stand, or null. */
    public static int[] nearest(Space s, int x, int y, int z, int radius) {
        int[] best = null;
        long bestD = Long.MAX_VALUE;
        for (int dy = -radius; dy <= radius; dy++)
            for (int dx = -radius; dx <= radius; dx++)
                for (int dz = -radius; dz <= radius; dz++) {
                    int cx = x + dx, cy = y + dy, cz = z + dz;
                    if (s.solid(cx, cy, cz) || s.solid(cx, cy + 1, cz) || !s.solid(cx, cy - 1, cz)) continue;
                    long d = (long) dx * dx + (long) dy * dy + (long) dz * dz;
                    if (d < bestD || d == bestD && cy < best[1]) {
                        bestD = d;
                        best = new int[] {cx, cy, cz};
                    }
                }
        return best;
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd fabric && ./gradlew test --tests '*FreeSpotTest' --console=plain -q`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add fabric/src/main/java/dev/moui/galaxycraft/toad/FreeSpot.java fabric/src/test/java/dev/moui/galaxycraft/toad/FreeSpotTest.java
git commit -m "toad: FreeSpot, the nearest standing spot for a walker (Toads come out of blocks)"
```

---

### Task 2: ShadowToads, a tagged Villager per wanted Toad (server side) and a probe

**Files:**
- Create: `fabric/src/main/java/dev/moui/galaxycraft/shadow/ShadowToads.java`
- Modify: `fabric/src/main/java/dev/moui/galaxycraft/shadow/ShadowWorld.java` (call `ShadowToads.tick` in `tick`, release on `attach`/`release`, expose `wantToads`)
- Create: `fabric/src/gametest/java/dev/moui/galaxycraft/gametest/ToadEntityProbe.java` (+ register in `fabric/src/gametest/resources/fabric.mod.json` the way `ShadowProbe` is, and a `galaxycraftToadEntity` flag in `fabric/build.gradle` like `galaxycraftBlocks`)
- Test: the probe (Minecraft-only, no Dolphin)

**Interfaces:**
- Consumes: `FreeSpot.nearest`, `ShadowMap.x/y/z(cell)`, `ShadowMap.frame`, `ShadowWorld.entities()`, `ShadowWorld.isShadow(level)`.
- Produces:
  - `record ShadowToads.Wanted(String id, double[] local /* planet space, blocks, feet */, float yaw)`
  - `ShadowWorld.wantToads(VoxelPlanet p, List<ShadowToads.Wanted> toads)` (any thread; replaces the list)
  - `ShadowToads.TAG = "galaxycraft_toad"`, `static String idOf(Entity e)` (id or null), `static boolean isToad(Entity e)`
  - `ShadowToads.positions()` → `Map<String,double[]>` id → `{localX, localY, localZ}` in planet space (blocks), volatile snapshot
  - `ShadowToads.pollDeath()` → `String` id of a Toad that died, or null (queue)

- [ ] **Step 1: Write `ShadowToads`**

```java
package dev.moui.galaxycraft.shadow;

import dev.moui.galaxycraft.toad.FreeSpot;
import dev.moui.galaxycraft.voxel.VoxelPlanet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;

/**
 * The Toads of the running planet as villagers in the shadow: one tagged {@link Villager} per wanted
 * Toad, put on free ground, kept out of blocks, with its position published each second and its
 * death announced. Server thread only, except the snapshots.
 */
public final class ShadowToads {
    public static final String TAG = "galaxycraft_toad";
    private static final String PREFIX = TAG + ":";

    /** A Toad the client wants running: id, feet in planet space (blocks) and facing. */
    public record Wanted(String id, double[] local, float yaw) {}

    private static volatile List<Wanted> wanted = List.of();
    private static volatile Map<String, double[]> positions = Map.of();
    private static final ConcurrentLinkedQueue<String> deaths = new ConcurrentLinkedQueue<>();
    /** Server thread: id -> entity id of the villager that is this Toad. */
    private static final Map<String, Integer> alive = new HashMap<>();
    private static int ticks;

    private ShadowToads() {}

    static void want(List<Wanted> toads) {
        wanted = List.copyOf(toads);
    }

    public static String idOf(Entity e) {
        for (String t : e.entityTags()) if (t.startsWith(PREFIX)) return t.substring(PREFIX.length());
        return null;
    }

    public static boolean isToad(Entity e) {
        return idOf(e) != null;
    }

    public static Map<String, double[]> positions() {
        return positions;
    }

    public static String pollDeath() {
        return deaths.poll();
    }

    /** The planet went away (or another came): the entities go, nobody is dead. */
    static void release(ServerLevel level) {
        for (Entity e : level.getAllEntities()) if (isToad(e)) e.discard();
        alive.clear();
        positions = Map.of();
    }

    /** Each server tick, with the running planet and its mirrored columns. */
    static void tick(ServerLevel level, VoxelPlanet planet, ShadowMap map, Set<Long> mirrored) {
        if (planet == null || map == null) return;
        ticks++;
        Set<String> want = new HashSet<>();
        for (Wanted w : wanted) {
            want.add(w.id());
            Integer eid = alive.get(w.id());
            Entity e = eid == null ? null : level.getEntity(eid);
            if (e != null && !e.isRemoved()) continue;
            if (eid != null) { // it was there and is gone: killed (a /kill counts), not unloaded
                alive.remove(w.id());
                deaths.add(w.id());
                continue;
            }
            spawn(level, planet, map, mirrored, w);
        }
        // Entities of Toads nobody wants any more (the record was deleted): gone.
        for (var it = alive.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            if (want.contains(en.getKey())) continue;
            Entity e = level.getEntity(en.getValue());
            if (e != null) e.discard();
            it.remove();
        }
        if (ticks % 20 != 0) return;
        Map<String, double[]> out = new HashMap<>();
        for (var en : alive.entrySet()) {
            Entity e = level.getEntity(en.getValue());
            if (e == null || e.isRemoved()) continue;
            freeIfStuck(level, map, e);
            double[] f = map.frame(e.getX(), e.getY(), e.getZ());
            if (f != null) out.put(en.getKey(), new double[] {f[0], f[1], f[2]});
        }
        positions = out;
    }

    private static FreeSpot.Space space(ServerLevel level) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        return (x, y, z) -> {
            p.set(x, y, z);
            if (!level.isLoaded(p)) return true; // unloaded counts as solid: never spawn into the unknown
            return !level.getBlockState(p).getCollisionShape(level, p).isEmpty();
        };
    }

    private static void spawn(ServerLevel level, VoxelPlanet planet, ShadowMap map, Set<Long> mirrored, Wanted w) {
        int cell = planet.grid.cellAt(new org.joml.Vector3d(w.local()[0], w.local()[1], w.local()[2]));
        if (cell < 0 || !mirrored.contains(net.minecraft.world.level.ChunkPos.pack(map.x(cell), map.z(cell)))) return;
        org.joml.Vector3d m = dev.moui.galaxycraft.voxel.CellSpace.local(planet.grid, cell, new org.joml.Vector3d(w.local()[0], w.local()[1], w.local()[2]));
        double x = map.x(cell) + m.x, y = map.y(cell) + m.y, z = map.z(cell) + m.z;
        int[] p = FreeSpot.nearest(space(level), (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z), 6);
        if (p == null) return; // no room yet (the columns are still being copied in): try next tick
        Villager v = EntityType.VILLAGER.create(level, EntitySpawnReason.TRIGGERED);
        if (v == null) return;
        v.snapTo(p[0] + 0.5, p[1], p[2] + 0.5, w.yaw(), 0);
        v.setPersistenceRequired();
        v.addTag(PREFIX + w.id());
        v.setCustomNameVisible(false);
        level.addFreshEntity(v);
        alive.put(w.id(), v.getId());
    }

    /** An entity whose feet or head are in a block goes to the nearest free spot. */
    private static void freeIfStuck(ServerLevel level, ShadowMap map, Entity e) {
        FreeSpot.Space s = space(level);
        int x = (int) Math.floor(e.getX()), y = (int) Math.floor(e.getY()), z = (int) Math.floor(e.getZ());
        if (!FreeSpot.inside(s, x, y, z)) return;
        int[] p = FreeSpot.nearest(s, x, y, z, 6);
        if (p != null) e.snapTo(p[0] + 0.5, p[1], p[2] + 0.5, e.getYRot(), e.getXRot());
    }
}
```

(Mojang-mapped names in 26.x may differ slightly — e.g. `EntityType.VILLAGER`, `Villager` package, `entityTags()`, `snapTo`, `ChunkPos.pack`. `snapTo` and `ChunkPos.pack(BlockPos)` are already used in `ShadowWorld`; fix any other name at compile time against the jar, keeping the behavior.)

- [ ] **Step 2: Hook into `ShadowWorld`**

In `ShadowWorld.java`:
- add `public static void wantToads(VoxelPlanet p, List<ShadowToads.Wanted> toads) { ops.add(level -> { if (p == planet) ShadowToads.want(toads); }); }`
- in `tick(...)`, after `animate(level);` add `ShadowToads.tick(level, planet, map, mirrored);`
- in `attach(...)`, in the op, after `release(level);` add `ShadowToads.release(level);`
- in `collect(...)`, keep Toads: they are `Mob`s with `isPersistenceRequired()` so `mobCounts` already skips them (verify in the probe, Task 3).

- [ ] **Step 3: Write the probe `ToadEntityProbe`** (modelled on `ShadowProbe`: `-Dgalaxycraft.toadEntity`, `ctx.worldBuilder().create()`, a 32-block planet session, `ShadowLink`, `run(ctx, n)` waits ticks). Steps it performs and checks, printing `[GalaxyCraft toadentity] PASS` or `FAIL [...]`:

```java
// after the planet is up and the shadow has taken it in (run(ctx, 60)):
ShadowWorld.wantToads(s.planet(), List.of(new ShadowToads.Wanted("t1", new double[] {0, s.planet().surface() + 0.5, 0}, 0f)));
run(ctx, 40);
var es = ShadowWorld.entities();
Entity toad = es.list().stream().filter(e -> "t1".equals(ShadowToads.idOf(e))).findFirst().orElse(null);
check(toad != null, "a villager tagged t1 exists");
check(toad.getBoundingBox().getXsize() > 0.5 && toad.isPersistenceRequired() == true, "has a box and is persistent");
// asked twice: still one
ShadowWorld.wantToads(s.planet(), List.of(new ShadowToads.Wanted("t1", new double[] {0, s.planet().surface() + 0.5, 0}, 0f)));
run(ctx, 40);
check(count("t1") == 1, "exactly one entity for t1");
// a position was published
run(ctx, 30);
check(ShadowToads.positions().containsKey("t1"), "its position is published");
// unload: nobody is dead
ShadowWorld.attach(null, "");  // then attach again
check(ShadowToads.pollDeath() == null, "unloading kills nobody");
```

Add `galaxycraftToadEntity` to `build.gradle` beside `galaxycraftBlocks` (`vmArg "-Dgalaxycraft.toadEntity=true"`, `hidden=true`) and the entrypoint to the gametest `fabric.mod.json` `fabric-client-gametest` list.

- [ ] **Step 4: Run the probe**

Run: `cd fabric && ./gradlew runClientGametest -PgalaxycraftToadEntity --console=plain -q 2>&1 | grep -i "toadentity\|FAIL\|Exception"`
Expected: `[GalaxyCraft toadentity] PASS`. Fix compile/name errors against the Minecraft jar until it does.

- [ ] **Step 5: Commit**

```bash
git add fabric/src fabric/build.gradle
git commit -m "toad: a Toad is a tagged villager in the shadow (spawned on free ground, position published, death announced), with a Minecraft-only probe"
```

---

### Task 3: The spike, beds, doors, the clock and the mob caps (probe extension)

**Files:**
- Modify: `fabric/src/gametest/java/dev/moui/galaxycraft/gametest/ToadEntityProbe.java`
- Modify (only if a check fails): `fabric/src/main/java/dev/moui/galaxycraft/shadow/ShadowWorld.java`

This task answers the spec's risks 1 to 3 before anything else is built on them.

- [ ] **Step 1: Extend the probe** with a house: place on the planet (using the probe's `place(name, k)` helper as `ShadowProbe` does, or `s.planet().set(cell, id)` with `blocks.state` ids) a floor-level `red_bed` (foot and head cells in a row), an `oak_door` (both halves) in a wall gap and walls/roof around a 3x3x3 room (any solid blocks), then `want` a Toad inside it. Put the shadow level to night: through the integrated server `server.execute(() -> server.getLevel(ShadowWorld.KEY)` set the clock with `clockManager().moveToTimeMarker(...)` for night, or `/time set night` run with `server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set night")`). Then run 400 ticks and check:

```java
check(poiBedKnown(), "the shadow level knows the bed (PoiManager.existsAtPosition(PoiTypes.HOME, bedPos))");
check(toadEntity.isSleeping(), "the Toad is asleep at night");
check(mobCapDoesNotCountToads(), "ShadowWorld mob counts do not include the Toad");
check(still(toadEntity), "it was not despawned in 400 ticks");
```

- [ ] **Step 2: Run it**

Run: `cd fabric && ./gradlew runClientGametest -PgalaxycraftToadEntity --console=plain -q 2>&1 | grep -i "toadentity\|FAIL"`
Expected: PASS. If `poiBedKnown` fails (the likely failure: the mirror writes blocks without registering POIs), go to Step 3. If only `isSleeping` fails, check the dimension's clock (risk 2) and the villager's schedule (`brain.getSchedule()` must be `Schedule.VILLAGER_DEFAULT`; call `v.getBrain().setSchedule(...)` and `v.refreshBrain(level)` after the clock fix).

- [ ] **Step 3: If the bed is unknown, register POIs from the mirror**

In `ShadowWorld`, where the mirror (and `set(...)`) writes a block state into the level (`level.setBlock(pos, state, MIRROR_FLAGS)` / the section write), after it, for a state whose block is a bed (`BedBlock`) or a door (`DoorBlock`), call:

```java
level.getPoiManager().add(pos, level.registryAccess().lookupOrThrow(Registries.POINT_OF_INTEREST_TYPE)
        .getOrThrow(state.getBlock() instanceof BedBlock ? PoiTypes.HOME : PoiTypes.MEETING).value()...);
```

(Use `PoiTypes.forState(state)` to get the type instead of choosing by block: `PoiTypes.forState(state).ifPresent(type -> level.getPoiManager().add(pos, type))`; remove with `level.getPoiManager().remove(pos)` when the state becomes air.) Re-run Step 2 until PASS.

- [ ] **Step 4: Commit**

```bash
git add fabric/src
git commit -m "toad: the shadow knows beds and doors, a Toad sleeps in a bed at night (probe)"
```

---

### Task 4: The client wants Toads and writes their positions back

**Files:**
- Modify: `fabric/src/client/java/dev/moui/galaxycraft/client/ToadClient.java`
- Test: `fabric/src/test/java/dev/moui/galaxycraft/toad/ToadStoreTest.java` (remove-by-id still covered); the probe from Task 2 gets a client-side check

**Interfaces:**
- Consumes: `ShadowWorld.wantToads`, `ShadowToads.positions()`, `ShadowToads.pollDeath()`, `PlanetSession.localOf(Vector3d gal)` / `galOf(Vector3d local)`, `ToadStore.put/remove/all`.
- Produces: `ToadClient.syncShadow(PlanetSession focus)`, called once per client tick from `PlanetClient.tick` for the focused planet.

- [ ] **Step 1: Write `syncShadow`** in `ToadClient`:

```java
/** Once a tick: tells the shadow which Toads of this planet to run, takes their positions back, drops the dead. */
static void syncShadow(PlanetSession s) {
    if (store == null || !s.active()) return;
    List<ShadowToads.Wanted> want = new ArrayList<>();
    for (ToadRecord r : store.all()) {
        if (!r.stage().equals(stageName()) || s.cellAt(new Vector3d(r.pos()[0], r.pos()[1], r.pos()[2])) < 0) continue;
        Vector3d local = s.localOf(new Vector3d(r.pos()[0], r.pos()[1], r.pos()[2]));
        Quaterniond q = new Quaterniond(r.rot()[0], r.rot()[1], r.rot()[2], r.rot()[3]);
        Vector3d f = new Vector3d(0, 0, 1).rotate(q);
        want.add(new ShadowToads.Wanted(r.id(), new double[] {local.x, local.y, local.z}, (float) Math.toDegrees(Math.atan2(-f.x, f.z))));
    }
    ShadowWorld.wantToads(s.planet(), want);
    for (String dead; (dead = ShadowToads.pollDeath()) != null; ) {
        store.remove(dead);
        dirty = true;
    }
    for (var en : ShadowToads.positions().entrySet()) {
        store.all().stream().filter(r -> r.id().equals(en.getKey())).findFirst().ifPresent(r -> {
            Vector3d g = s.galOf(new Vector3d(en.getValue()[0], en.getValue()[1], en.getValue()[2]));
            r.pos()[0] = g.x; r.pos()[1] = g.y; r.pos()[2] = g.z;
            dirty = true;
        });
    }
    saveSoon();
}
```

with `saveSoon()` = the existing "at most every 5 s" save from `moved()` (rename that body).

- [ ] **Step 2: Call it** from `PlanetClient.tick` right after `focus = nearest(world.queryPos());`: `ToadClient.syncShadow(focus);`.

- [ ] **Step 3: Compile and run the Task 2 probe again**, adding a `ToadStore`-level check is not possible there (client store); instead the Dolphin probe in Task 9 covers write-back. Run: `./gradlew compileClientJava --console=plain -q` → no errors.

- [ ] **Step 4: Commit**

```bash
git add fabric/src
git commit -m "toad: the client tells the shadow which Toads to run, takes their positions back and drops the dead"
```

---

### Task 5: Draw tagged shadow entities with Kinopio; aim at the entity

**Files:**
- Modify: `fabric/src/client/java/dev/moui/galaxycraft/client/EntityClient.java` (`shadowEntity`)
- Modify: `fabric/src/client/java/dev/moui/galaxycraft/client/ToadClient.java` (extract `draw`, remove record drawing/walking, aim by entity)
- Modify: `fabric/src/client/java/dev/moui/galaxycraft/client/ToadClient.java:aimed/centreOf/occupies` (aim via the shadow entity)

**Interfaces:**
- Consumes: `ShadowToads.idOf`, `ShadowWorld.Entities`, `ShadowMap.frame`.
- Produces: `ToadClient.draw(EntityClient ec, ToadRecord r, Matrix4d base, String animName, double clockSec, List<EntityWire.Piece> out)` — everything in the current `pieces` loop from `// The pose` to the end of the goods loop, taking the base matrix and animation name instead of computing them; `ToadClient.recordOf(String id)` → `ToadRecord` or null; `ToadClient.animFor(Entity e, boolean talking)` → `"Sleep"` if `e instanceof LivingEntity l && l.isSleeping()`, else `"Talk"` if talking, else `"Walk"` if horizontal speed² > 0.0025, else `"Wait"` (mapped through `ToadLook` names for the Pickel set).

- [ ] **Step 1: Extract `draw`** from the body of the `for (ToadRecord r : store.all())` loop in `ToadClient.pieces` (the pose sampling and the parts/goods emission), as a method with the signature above. Keep the `look`, `model`, `srt`, `sample` locals inside it (allocate per call; the Toad count is small).

- [ ] **Step 2: Replace `pieces`** with a version that draws nothing for records whose planet is mirrored (their entity draws them) and keeps drawing a record nobody simulates (far planets) at its saved spot, standing (`Wait`), exactly as before but without the walker:

```java
static void pieces(EntityClient ec, List<EntityWire.Piece> out) {
    drainOutposts();
    dialogueFrame(System.nanoTime());
    if (store == null) return;
    tickClock();
    // Toads whose planet is running are drawn from their entity (EntityClient.shadowEntity -> drawEntity);
    // the others stand where they were saved.
    java.util.Set<String> running = ShadowToads.positions().keySet();
    ...
    for (ToadRecord r : store.all()) {
        if (!r.stage().equals(stageName()) || running.contains(r.id())) continue;
        Matrix4d base = new Matrix4d().translation(r.pos()[0], r.pos()[1], r.pos()[2]).rotate(ToadWalker...orientation → new Quaterniond(r.rot()[0], r.rot()[1], r.rot()[2], r.rot()[3])).scale(unitsPerPixel);
        draw(ec, r, base, "Wait", clockSec, out);
    }
}
```

`tickClock()` is the paused-aware clock added earlier (`clockSec += gdt`).

- [ ] **Step 3: Draw from the entity.** In `EntityClient.shadowEntity`, before the generic renderer, add:

```java
String toadId = ShadowToads.idOf(e);
if (toadId != null) {
    ToadRecord rec = ToadClient.recordOf(toadId);
    double[] f = shadow.map().frame(e.getX(), e.getY(), e.getZ());
    if (rec == null || f == null || new Vector3d(f[0], f[1], f[2]).distance(mario) > range()) return;
    Matrix4d at = new Matrix4d(f[3], f[4], f[5], 0, f[6], f[7], f[8], 0, f[9], f[10], f[11], 0, f[0], f[1], f[2], 1);
    seen.add(new Seen(e, at, new Vector3d(e.getX(), e.getY(), e.getZ())));
    double yaw = Math.toRadians(-((e instanceof LivingEntity l) ? l.yBodyRot : e.getYRot()));
    Matrix4d base = new Matrix4d(at).rotateY(yaw).scale(1 / 16.0);
    ToadClient.draw(this, rec, base, ToadClient.animFor(e, ToadClient.talkingTo() != null && rec.id().equals(ToadClient.talkingTo().id())), ToadClient.clock(), out);
    return;
}
```

(`at` maps one shadow block to one planet block; the Toad's model is in 1/16-block pixels, so `scale(1/16)` replaces `unitsPerPixel` here. The current draw code uses galaxy units; here the matrix is already in planet blocks because `shadowEntity` pieces are in the entity wire's planet space — check against how `capture.begin(at, out)` is used and match it, converting with the same factor the other shadow entities get.)

- [ ] **Step 4: Aim at the entity.** Replace `ToadClient.aimed` so it tests the crosshair against the Toads' shadow entities' boxes, as `EntityClient` does for mobs (`EntityClient:190`, `getBoundingBox().inflate(getPickRadius())` through its `Seen` list): expose `EntityClient.seenToad(Vector3d eyeGal, Vector3d lookGal, double reach)` returning the nearest tagged `Seen`, and let `aimed` map it to its record via `recordOf(ShadowToads.idOf(entity))`. Records with no running entity keep the old sphere test (`ShipBoarding.nearest`).

- [ ] **Step 5: Remove** `ToadWalker`, `ToadWander`, `ToadWanderTest` and their uses (`ToadClient.moved`, `st.yaw` walking, `centreOf` for running Toads, `occupies`); `PlanetClient.placeBlock(...)` keeps only `ShadowWorld.occupied(...)` (the entity box). Delete `ToadWalker.near`, `face`, `orientation`, `upOf` uses by drawing standing Toads with the saved rotation only.

- [ ] **Step 6: Compile**

Run: `cd fabric && ./gradlew compileClientJava compileGametestJava test --console=plain -q`
Expected: no errors; remaining Toad tests PASS.

- [ ] **Step 7: Commit**

```bash
git add -A fabric/src
git commit -m "toad: Toads are drawn from their shadow entity (Kinopio, with the entity's own state), aimed at by its box; the walker is gone"
```

---

### Task 6: Talking, trading, hitting and dying on the entity

**Files:**
- Modify: `fabric/src/main/java/dev/moui/galaxycraft/shadow/ShadowWorld.java` (`stand(int entityId, boolean on, UUID player)` op)
- Modify: `fabric/src/main/java/dev/moui/galaxycraft/shadow/ShadowToads.java` (`stand`)
- Modify: `fabric/src/client/java/dev/moui/galaxycraft/client/ToadClient.java` (begin/end call it), `EntityClient.java` (left click on a Toad entity)

**Interfaces:**
- Produces: `ShadowWorld.stand(String toadId, UUID player, boolean on)`: while on, the Toad's navigation is stopped, `setNoAi`-free (so it can still be hit): `v.getNavigation().stop(); v.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(player, true))` each tick of the op set `standing`; off clears it.

- [ ] **Step 1: Implement `ShadowToads.stand(level, id, player, on)`** keeping a `Set<String> standing`; in `tick`, for each standing Toad: `v.getNavigation().stop()` and `v.getLookControl().setLookAt(playerProxy)`.

- [ ] **Step 2: Call it** from `ToadClient.begin` (`on=true`) and `endConversation` (`on=false`), through `ShadowWorld.stand(with.id(), player.getUUID(), on)`.

- [ ] **Step 3: Left click** on a Toad entity: check how mobs get hit (`ShadowWorld.attack(entityId, player)` called from the click path in `PlanetClient`/`EntityClient`); make sure the same path accepts a tagged villager (it targets by entity id, so it already does if the aim code of Task 5 returns the entity id). Add the entity id to `EntityClient.seenToad`'s result.

- [ ] **Step 4: Probe checks** (add to `ToadEntityProbe`): `ShadowWorld.attack(id, player)` lowers `toad.getHealth()`; a lethal blow (`toad.hurt(damageSources.generic(), 1000)` through an op) ends in `ShadowToads.pollDeath()` returning `"t1"`; a `/kill` does the same; a non-lethal hit leaves `pollDeath()` null and the Toad in `positions()`.

- [ ] **Step 5: Run**

Run: `cd fabric && ./gradlew runClientGametest -PgalaxycraftToadEntity --console=plain -q 2>&1 | grep -i "toadentity\|FAIL"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A fabric/src
git commit -m "toad: a talked-to Toad stands and looks at the player; hits hurt it, a death deletes its record"
```

---

### Task 7: Stuck-in-blocks guard under test

**Files:**
- Modify: `fabric/src/gametest/java/dev/moui/galaxycraft/gametest/ToadEntityProbe.java`

- [ ] **Step 1: Add probe steps**: (a) want a Toad whose `local` position is inside the planet's ground (surface − 3 blocks): after 40 ticks its published position is at or above the surface and `!FreeSpot.inside(...)`; (b) after it spawned, teleport its entity inside a block via an op (`e.snapTo(x, y - 2, z, 0, 0)`): within 40 ticks (two guard passes) it is out; (c) place a block on top of a Toad's head cell via `ShadowWorld.set` (the client path): the placement is refused by `ShadowWorld.occupied` (call it directly: returns true for the Toad's cell).

- [ ] **Step 2: Run** the probe as in Task 6 Step 5. Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add fabric/src
git commit -m "toad: probe: a Toad spawned or shoved into a block comes out within a second"
```

---

### Task 8: Huts with a 3-high room, roof, door and bed

**Files:**
- Modify: `fabric/src/main/java/dev/moui/galaxycraft/voxel/gen/Huts.java`
- Test: `fabric/src/test/java/dev/moui/galaxycraft/voxel/gen/HutsTest.java` (extend the existing hut test; if absent, create it from the helpers the other generator tests use)

**Interfaces:**
- Consumes: `ids: ToIntFunction<String>` that maps block-state strings (`"minecraft:oak_door[half=lower,facing=east,...]"`) to ids; `Huts.Placed.facing`.
- Produces: unchanged `Huts.Placed`; the hut now has: walls `level+1..level+3` (windows on `level+2`), roof cap at `level+4` (the spotted layer at `level+4`, the top at `level+5`), a door at the gap (`level+1` lower, `level+2` upper), a `red_bed` foot and head on the floor along the back wall (`a = -1`, `b = 0` foot, `a = -1`... the two cells in line with the facing axis), the torch at `level+2`; `HEIGHT = 8` so clearing covers the taller hut.

- [ ] **Step 1: Write the failing test** (extend the hut test with the id function that records the names it is asked for):

```java
@Test void hutHasA3HighRoomRoofDoorAndBed() {
    List<String> asked = new ArrayList<>();
    ToIntFunction<String> ids = name -> { asked.add(name); return asked.indexOf(name) + 2; };
    ... // the existing test's grid/cells/height/bare setup, build = true
    List<Huts.Placed> out = Huts.plant(grid, depth, cells, height, bare, List.of(new Huts.Request("h", dir)), true, ids);
    assertEquals(1, out.size());
    // walls three high: the cell three above the floor in a wall column is not air, four above is the roof
    assertTrue(asked.stream().anyMatch(n -> n.startsWith("minecraft:oak_door")), "a door");
    assertTrue(asked.stream().anyMatch(n -> n.startsWith("minecraft:red_bed")), "a bed");
    // the room's inside, floor to 3 above, is air
    assertAll(insideCells.stream().map(c -> () -> assertEquals(Blocks.AIR, cells[c])));
    // the doorway is the door's two halves, nothing else
}
```

(`insideCells` = the 3x3 interior columns × `level+1..level+3` except the bed cells; compute them from the hut's column indices the way the existing test finds `ci, cj, level`.)

- [ ] **Step 2: Run to verify it fails**

Run: `cd fabric && ./gradlew test --tests '*HutsTest' --console=plain -q` — Expected: FAIL (no door/bed).

- [ ] **Step 3: Implement** in `Huts.tryAt` (build branch): raise `HEIGHT` to 8; walls loop `level+1..level+3` (glass at `level+2` for windows; the door column gets the door instead of air at the gap), roof at `level+4` (the existing `stem/cap` pattern) and `level+5`; the bed: foot at `(a=-1, b=-1)`... choose the back-left corner pair along the hut's `b` axis: foot `(a=-1,b=-1)`, head `(a=-1,b=0)`; state strings built from the facing as `CellSpace`-independent grid directions: the bed's `facing` is the direction from foot to head and the door's `facing` the direction out, each as the compass name the planet's cell orientation gives for the grid step (`+i` and `+j` of the hut's face map to compass names through `ShadowMap.STEP`/the planet's `CubeSphere` side table — use the same lookup `Placer` uses when it places a door by hand: `CellSpace.DIRECTION_OF_SIDE[face]` indexes `Direction.values()`). Unit-test the lookup by asking the existing `Placer` for a door at the same cell and comparing its state string with the hut's.

- [ ] **Step 4: Run to verify it passes** — `./gradlew test --tests '*HutsTest' --console=plain -q` → PASS; then the whole suite later in Task 9.

- [ ] **Step 5: Commit**

```bash
git add fabric/src
git commit -m "huts: a 3-high room under a 4th-and-5th-block roof, a real door and a red bed for the villager Toads"
```

---

### Task 9: Whole-suite run, Dolphin visual check, docs, merge into test/everything

**Files:**
- Modify: `docs/TOADS.md` (what a Toad is now: villager in the shadow, vulnerable, sleeps in beds; the dialogue/trade unchanged)
- Modify: `fabric/src/gametest/java/dev/moui/galaxycraft/gametest/ToadDolphinProbe.java` (stop depending on the walker: it asserted positions differ over time; assert instead that the Toad is a tagged entity and that the dialogue box opens on a right click aimed at its box)

- [ ] **Step 1:** Run the full suite: `cd fabric && ./test.sh` (about 6 minutes). Expected: all tests pass (`MarioPerspectivesTest` may fail on its own after the toad probe; re-run it alone to confirm).
- [ ] **Step 2:** Run `tools/gxvoxel.sh toad` and `tools/gxvoxel.sh outpost` (needs the Dolphin slot: ask the user to close the game first; `pkill -f "[g]alaxycraft.hidden=true"`). Expected: PASS and shots `toad-*.png`, `outpost-front.png` show a Toad in the 4-high hut with a door.
- [ ] **Step 3:** Update `docs/TOADS.md`; commit `docs: Toads are villagers`.
- [ ] **Step 4:** Merge into the integration branch: `cd ../GalaxyCraft-everything && git merge --no-edit feat/toad-npcs`, resolve conflicts keeping both sides, `cd fabric && ./gradlew compileClientJava --console=plain -q`, rebuild `syati/build.sh` only if `syati` changed (it did not), relaunch with `tools/gxplay.sh` for the user's playtest. Do not merge to `master`, do not push, do not post to Discord (the user has not played it: see the merge-after-playtest rule).
- [ ] **Step 5:** Tell the user what to try: hit a Toad (it takes damage, can die), put a Toad in a corner or a block and see it come out, build a house with a bed and door and wait for night (or `/time set night`), look for a generated hut with its door and bed.

---

## Self-review

- **Spec coverage:** record/entity split (Tasks 2, 4), position back (4), stuck guard (1, 2, 7), death (2, 4, 6), talking/standing (6), hitting (6), drawing (5), hut (8), removals (5), risks 1–5 (3, 5 for stations: the Technician is a record on a station's planet like any other; Task 9 Step 2's probe covers `toad` which includes the Technician), testing list (1, 2, 3, 6, 7, 8, 9).
- **Placeholders:** Mojang-name details in Tasks 2, 3, 5 and 8 are marked as "fix against the jar", with the behavior spelled out; the draw matrix in Task 5 must be matched to how `capture.begin(at, out)` scales other shadow entities (stated).
- **Type consistency:** `ShadowToads.Wanted(String, double[], float)`, `wantToads`, `positions()`, `pollDeath()`, `idOf`, `isToad`, `stand`, `ToadClient.syncShadow`, `recordOf`, `draw`, `animFor`, `clock`, `FreeSpot.nearest/inside` are used with the same names throughout.
