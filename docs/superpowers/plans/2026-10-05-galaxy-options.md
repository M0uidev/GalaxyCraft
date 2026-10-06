# Galaxy options (Create World's planets) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Create World tab sets up a galaxy of 1..64 seeded planets; the nearest 8 are complete in the game, the rest drawn from afar with less detail the farther they are.

**Architecture:** A pure `GalaxyCatalog` (options + seed → planet entries) saved in `galaxy.json`. `PlanetClient` streams from it: the 8 nearest entries are `PlanetSession`s (as today), every other entry a `FarPlanet` (a planet record with **no gravity and no chunks**, plus 6 coarse face parts). The module already draws far-view-only planets (`chunk_count 0`); it only needs 64 planet entries, gravity off below range 1, and a far-part array that starts small. No protocol record and no Dolphin rebuild.

**Tech Stack:** Java 25 / Fabric (Minecraft 26.3, Mojang names), JUnit 5, Syati C++ module (`syati/build.sh`), probes run by `tools/gxvoxel.sh`.

**Spec:** `docs/superpowers/specs/2026-10-05-galaxycraft-galaxy-options-design.md`

## Global Constraints

- Up to 64 catalog planets; at most 8 complete (`PlanetLayout.NEAR_PLANETS = 8`) in the game.
- Radius 16..256; spacing Near/Normal/Far = 24/96/300 blocks between gravities; entry 0 at the origin.
- Same options + seed → same catalog. A catalog id is the planet's file index (`PlanetStore.key`), stable.
- Planet file format unchanged. `player.json`'s `planet` already is the file index: unchanged.
- Units: 80 per block (`1 / GravityFrame.SCALE`).
- No protocol struct changes (the host swaps fixed headers; `VoxelStats` layout is read by Dolphin).

## Spec deviations (decided while planning, from reading the code)

- **No `GxcFar` record.** A far planet is a `GxcPlanet` with `gravity_range 0` and `chunk_count 0`; its six face parts go as far-view chunks in slots 0..5. Same result, no new protocol.
- **A far planet's mesh** comes from its recipe (generator sample, or a blueprint's layers) until it has been Near in this session; then from its cells, built when it leaves Near. A planet's edits are not visible from afar at 3–12 patches anyway, and building from disk at load would mean reading every visited planet whole.
- **`VoxelStats`** gets no new fields (Dolphin reads its layout). The probe counts tiers from the mod's side (`PlanetClient.tiers()`).

## Review Focus

1. Promotion Far→Near must not leave a gap: the far record stays until the new session's far view is sent (`queued() == 0`), and only then is it GONE.
2. Leaving a world mid-generation: futures for a world already left must be dropped (compare the world's `GalaxySave`).
3. Old worlds with 2..8 planet files and no `galaxy.json` keep every planet, and the player lands where they were.
4. A world created without visiting the tab (defaults) still gets a catalog (8 planets) and the home planet at the origin as before.
5. Ids: 64 far + 8 near + leaving ones must fit in 1..255; far ids come from the same pool as sessions'.

---

### Task 1: Surface sampler out of PlanetGenerator

**Files:** Create `voxel/gen/SurfaceSampler.java`; modify `voxel/gen/PlanetGenerator.java` (column loop uses it); test `gen/SurfaceSamplerTest.java`.

**Produces:** `new SurfaceSampler(PlanetBlueprint bp, TerrainNoise noise, BiomeTable table)`; `Column at(Vector3d dir)` → `record Column(int height, String biome)` (height = blocks above/below the base surface, as `height[col]` today); `int depth()`, `boolean water()`.

- [ ] Test: for a blueprint and the tests' stand-in noise/table, `PlanetGenerator.cells` heights equal `sampler.at(columnDir).height()` on 50 random columns; `at` is deterministic.
- [ ] Move fixed/span/lowest/climateScale/relief + the per-column math into `SurfaceSampler`; `PlanetGenerator.cells` calls it. All existing generator tests still pass (`./gradlew test`).
- [ ] Commit `refactor: SurfaceSampler: a generated planet's ground at any direction, outside of building it`.

### Task 2: PlanetLod from any source, coarse whole-face parts

**Files:** modify `voxel/PlanetLod.java`; create `voxel/LodSource.java`; test `PlanetLodTest` (add cases).

**Produces:**
- `interface LodSource { CubeSphere grid(); Blocks blocks(); Patch patch(int face, int i0, int i1, int j0, int j1); int tint(Patch p, int tint); }` with `record Patch(int height, int block, int cell)` moved to `LodSource.Patch`.
- `LodSource.of(VoxelPlanet)` (today's `patch()` and `p.tint`).
- `LodSource.sampled(SurfaceSampler, CubeSphere grid, Blocks, ToIntFunction<String> ids)`: patch = sample at the patch's middle column; block = biome's top (water block if `water && height < 0`); tint = `blocks.biomeColor(biome, kind, …)` for tinted kinds.
- `PlanetLod.coarse(LodSource s, int patches, double unitsPerBlock)` → `Part[6]` (whole faces, `patches × patches`).
- [ ] Tests: `coarse(of(p), 12, 80)` gives 6 non-empty parts with ≥ 144 top quads per face; patches 3 < 6 < 12 in display-list size; sampled source on a generated test planet gives heights within ±relief of the real one's coarse parts' sphere radius (±2 blocks).
- [ ] Implement by making `region()` take a `LodSource`; existing tile/face code passes `LodSource.of(p)`. All LOD tests pass.
- [ ] Commit `feat: far views from any source: a planet's cells or its generator, whole faces at 12, 6 or 3 patches`.

### Task 3: GalaxyCatalog

**Files:** create `voxel/GalaxyCatalog.java`; test `GalaxyCatalogTest.java`.

**Produces:**
```java
public final class GalaxyCatalog {
  public enum Spacing { NEAR(24), NORMAL(96), FAR(300); public final int blocks; }
  public record First(boolean blueprint, String name, String biome, int radius) {}   // name: blueprint's; biome: id or "random"
  public record Options(int count, int minRadius, int maxRadius, First first, Spacing spacing, long seed) {
      public static Options defaults(long seed); public Options clamp(); }
  public enum Kind { GENERATED, BLUEPRINT }
  public record Entry(int index, double x, double y, double z, int radius, Kind kind, String biome, String blueprint, long seed) {
      public Vector3d center(); }          // galaxy units
  public record Result(List<Entry> entries, int asked) { public int placed(); }
  public static Result make(Options o, int firstRadius, List<String> landBiomes, double unitsPerBlock);
  public static Entry added(List<Entry> current, Vector3d center, int radius, Kind kind, String biome, String blueprint, long seed); // next free index
}
```
Placement: `Random(seed)`; per planet: radius uniform in [min, max], biome from `landBiomes`, planet seed `rnd.nextLong()`; center: up to 200 tries at distance `d` (starts at entry 0's gravity + spacing + its gravity, grows by one gravity+spacing step every 25 failed tries) in a uniformly random direction; `PlanetLayout.free`-style check (made package-visible as `PlanetLayout.fits`) with `spacing` instead of GAP. Gravity = `PlanetSession.gravityRadius(radius) * units`.
- [ ] Tests: deterministic (same → equal lists; other seed → different); entry 0 at origin with `first` radius/kind; all pairs ≥ g1+g2+spacing; radii in range; count 64 Far → all 64 placed and all within 5000 blocks; `Options.clamp` fixes count 0/99, min > max.
- [ ] Implement, pass, commit `feat: GalaxyCatalog: a world's planets from its options and seed`.

### Task 4: galaxy.json and old worlds

**Files:** modify `voxel/GalaxySave.java`; test `GalaxySaveTest.java` (create if absent).

**Produces:** `record Galaxy(int version, GalaxyCatalog.Options options, List<GalaxyCatalog.Entry> entries)`; `Optional<Galaxy> galaxy()`; `void writeGalaxy(Galaxy)` (atomic, as `writeSpot`); `static Galaxy fromFiles(List<PlanetStore.Saved-like (index, center, radius)>)`.
- [ ] Tests: round trip; corrupt file → empty; `fromFiles` with 3 planets → 3 BLUEPRINT-kind entries (`blueprint` = null: "made before catalogs") keeping indices and centers.
- [ ] Implement; `PlanetStore` gets `Optional<Header> header(String key)` reading only center/radius (n, core, depth) without the cells (new test in `PlanetStoreTest`).
- [ ] Commit `feat: a world's galaxy.json, and the catalog of worlds made before it from their planet files`.

### Task 5: Tiers in PlanetLayout

**Files:** modify `voxel/PlanetLayout.java`; tests in `PlanetLayoutTest`.

**Produces:** `NEAR_PLANETS = 8`, `CATALOG_MAX = 64`, `MAX_PLANETS` kept = `NEAR_PLANETS` (old callers); `static List<Integer> near(List<Sphere> all, Vector3d mario, Set<Integer> had)` (indices; nearest 8 by distance past gravity; a had one leaves only for one ≥ 64 blocks nearer); `static int farPatches(double radiusUnits, double distanceUnits, int had)` → 12 / 6 / 3 (angular size 2·atan(r/d) > 6° → 12, > 2° → 6, else 3; 20% slack around had's threshold); `static int prefetch(...)` = the 9th and 10th nearest.
- [ ] Tests: 12 spheres in a line → the 8 nearest; hysteresis holds against 10 blocks, yields at 64; levels at chosen distances and slack.
- [ ] Commit `feat: which planets are complete and how detailed the others are`.

### Task 6: FarPlanet

**Files:** create `voxel/FarPlanet.java`; modify `PlanetSession.java` (id pool → package `static int newId()/freeId(int)`, payload helpers reused); test `FarPlanetTest`.

**Produces:** `new FarPlanet(int index, Vector3d center, double surfaceBlocks, double unitsPerBlock)`; `void mesh(int patches, PlanetLod.Part[] parts)`; `int patches()`; `Msg peek()/void sent()/int queued()`; `void remove()` (queues GONE, frees id); `int id()`. Messages: PLANET (`gravity_range 0`, `chunk_count 0`, occluder = (surface − 2) units), then parts as far chunks slot 0..5 (`id<<24 | FAR_VIEW | slot`, version++), a new mesh resends the six.
- [ ] Tests: first peek is a PLANET with gravity 0 and chunk count 0; then 6 CHUNK with far bit and slots 0..5; remove → GONE with BE flag; id freed.
- [ ] Commit `feat: FarPlanet: a catalog planet drawn only, no gravity, no chunks`.

### Task 7: Module: 64 planets, gravity off, small far arrays

**Files:** modify `syati/src/VoxelPlanet.cpp`.
- `MAX_PLANETS = 64`. `ApplyPlanet`: `mRange = in.gravity_range > 1.f ? in.gravity_range : 1.f`. `MarioRadius` skips planets whose `gravity->mRange <= 1.f`.
- `Planet` gains `u32 far_count`. `ReplaceFar`: first allocation `far_count = slot < 6 ? 6 : FAR_VIEW_PARTS`; a slot ≥ far_count grows to FAR_VIEW_PARTS (alloc, copy, bury old). Drop/draw loops go to `far_count`.
- `DrawPlanet`: whole-planet cull first: skip a planet whose sphere (center, surface + 32 blocks) is outside the view, before its parts.
- [ ] `syati/build.sh` builds; `syati/test.sh` passes (host tests).
- [ ] Commit `feat(module): 64 planets, the far ones with no gravity; far arrays sized to use`.

### Task 8: PlanetClient streams the catalog

**Files:** modify `client/PlanetClient.java`; create `client/GalaxyStream.java` (the streaming policy and far meshes, keeps PlanetClient from growing).

**Produces:** `GalaxyStream` holds the catalog, `Map<Integer, Extra>` near sessions by index, `Map<Integer, FarPlanet>` far ones, pending generations `Map<Integer, CompletableFuture<VoxelPlanet>>`, far mesh cache `Map<Long(index<<8|patches), Part[]>`. `tick(mario, …)` every 10 ticks: `PlanetLayout.near` → promote/demote; prefetch; far levels. Promotion: load file (off-thread read, as `reloadFromDisk`) or generate from the entry (`PlanetBlueprint.standard(...).withMode(GENERATED).withBiome(entry.seed, entry.biome, 0)` or the named blueprint); `spawnAt(planet, entry.center())`; far GONE once the session's `queued() == 0`. Demotion: build its coarse meshes from cells (client thread), save if unsaved, `session.remove()`, far planet added. `PlanetClient.tiers()` → `int[]{near, far}` (tests). Session `index` = entry index (replaces `freeIndex()` for catalog worlds; `/galaxycraft planet add`/Create append an entry via `GalaxyCatalog.added` and save `galaxy.json`). `enterStage` in a world with a catalog loads nothing eagerly: the stream does it. `FIXED_DIR` / non-space stages keep today's path (no catalog).
- [ ] Unit-test `GalaxyStream`'s pure decisions via `PlanetLayout` (Task 5); this task is checked end to end (Task 10).
- [ ] `./gradlew build` passes all tests.
- [ ] Commit `feat: a world's planets stream in from its catalog: 8 complete, the rest drawn from afar`.

### Task 9: Create World's GalaxyCraft tab

**Files:** create `client/GalaxyTab.java` (GridLayoutTab), `client/mixin/CreateWorldScreenMixin.java` (`@ModifyArg` on `MenuTabBar.Builder.addTabs` in `init` appending the tab), register in `galaxycraft.client.mixins.json`; `client/PendingGalaxy.java` (static options set by the tab, read once on a new world's first join); lang keys in `assets/galaxycraft/lang/en_us.json` + `es_es.json` if present.
- Widgets: slider Planets 1..64; sliders Min/Max radius 16..256; CycleButton First: Generated/Blueprint; CycleButton biome (Random + `BiomeTable.all()` when a server-less table is unavailable: the land list from `McWorldgen` names cached at first world, else a fixed list of overworld land biomes); CycleButton blueprint (`BlueprintStore` names; disabled if none); slider first radius; CycleButton spacing; a text line "Seed: the World tab's".
- `PlanetClient.enterWorld`: new world → options = `PendingGalaxy.take()` or defaults (seed = the server's world seed) → `GalaxyCatalog.make` → `writeGalaxy`; chat lines for a missing blueprint and "N of M planets fit". Old world without galaxy.json → `fromFiles`.
- [ ] Commit `feat: Create World's GalaxyCraft tab: how many planets, their sizes, the first one, spacing`.

### Task 10: GalaxyProbe end to end, docs, roadmap

**Files:** create `gametest/GalaxyProbe.java`; modify `tools/gxvoxel.sh` (`galaxy` case like `launch`'s, `TAG=galaxy`); doc `docs/GALAXIA.md` (Spanish, for the player); `ROADMAP.md`.
- Probe: set `PendingGalaxy` (12 planets, 32..64, Normal), create a world through the screen as LauncherProbe does; wait for landing; assert `tiers()` = {8, 4} within 60 s; screenshot; TP Mario (`/galaxycraft` tp path used by ElytraProbe) to 3 points toward the far end: at each, tiers ≤ 8 near, screenshot, `max_speed` logged; break a block on the planet stood on at the far end; TP back to origin and to the far end again; the block is still gone. PASS line `[GalaxyCraft galaxy] PASS`.
- [ ] Ask the user to close their game; run `tools/gxvoxel.sh galaxy`; look at the screenshots.
- [ ] Docs + roadmap (Done/Next), public roadmap doc. Commit.
