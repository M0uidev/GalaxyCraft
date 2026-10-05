# Create World's planet options: a galaxy of up to 64 planets, drawn at every distance

2026-10-05. Status: design approved in the brainstorm ("excelente"). Spec 2 of the launcher.

## Goal

The Create World screen gets a **GalaxyCraft** tab. From it the player chooses how many planets
the world's galaxy has (1..64), how big they are, what the first one is (generated, or one of
their blueprints) and how far apart they lie. The world's seed places them semi-randomly. Every
planet is visible from anywhere in the galaxy, gaining detail as the player nears it; only the
nearest ones are complete in the game.

This is built as the first half of an infinite, No Man's Sky-like universe (stage 3, later): the
**catalog** and the **streaming** below are what an infinite universe needs; stage 3 adds catalog
entries per sector as the player explores, a floating origin, and faster travel.

## Decisions (from the brainstorm)

| Question | Choice |
|---|---|
| Order | Stage 2 = these options on a seeded catalog, finite; stage 3 = infinite universe |
| How many planets | More than 8 (up to 64): needs streaming now |
| Options on the tab | Count and sizes; first planet (generated or blueprint); spacing; seed |
| The other planets | Generated, random biome each (no blueprint mix; assumption, not contradicted) |
| How planets are decided | A catalog written once at world creation (not a pure per-sector hash) |
| Far planets | Drawn with less and less detail instead of not at all (three tiers) |

## Today

- `PlanetLayout.MAX_PLANETS = 8` (mod) and `MAX_PLANETS = 8` (`syati/src/VoxelPlanet.cpp:74`):
  every planet in the game is complete (gravity actor, collision zone, chunks or far view).
- A new world gets one default generated planet at the origin (launcher spec, §4). More only
  through `/galaxycraft planet add` or the editor's Create (`PlanetLayout.place`/`placeAlong`).
- Files: `saves/<world>/galaxycraft/planets/<stage>.gxplanet`, `<stage>.p<n>.gxplanet`
  (`PlanetStore.key`). `player.json` holds the game's planet id (1..255, recycled).
- Galaxy units: 80 per block (`GravityFrame.DEFAULT_UNITS_PER_BLOCK`). A float holds ~0.05 unit
  precision out to ~5000 blocks from the origin, so a 64-planet galaxy needs no floating origin.

## Design

### 1. The catalog (`voxel/GalaxyCatalog`, no Minecraft types, unit tested)

- `GalaxyOptions` record: `count` 1..64 (default 8), `minRadius`/`maxRadius` 16..256 (default
  48/128), `first` (mode Generated + biome or Random, or Blueprint + name; radius), `spacing`
  Near/Normal/Far = 24/96/300 extra blocks between gravities, `seed` (the world's).
- `GalaxyCatalog.make(options, blueprintRadius)` → list of `Entry(index, center, radius, kind,
  biome, planetSeed)`:
  - Entry 0 is the first planet, at the origin.
  - The others: a `Random(seed)` draws radius in [min, max] and a biome from the generator's
    table; then a center: random directions at a growing distance from the origin (shell after
    shell), the first whose gravity keeps `spacing` from every placed one (`PlanetLayout.free`).
    Up to 200 tries per planet; a planet that does not fit is dropped (see §6).
  - Same options and seed → same catalog, always.
- `index` is the planet's **catalog id**, stable for the world's life; it is the `n` of
  `<stage>.p<n>.gxplanet` (0 = `<stage>.gxplanet`). The planet file format is unchanged.
- `/galaxycraft planet add` and the editor's Create append an entry (next free index, Blueprint
  or Layers kind, center chosen as today). Replace rewrites its entry in place.

### 2. Tiers and streaming (`voxel/PlanetLayout` + `client/PlanetClient`)

| Tier | Which | In the game |
|---|---|---|
| Detail | the nearest, plus those within 64 blocks of their gravity (out at 128) | chunks, collision, gravity (today) |
| Near | the 8 nearest catalog planets, Detail included | gravity + 24×24-patch far view (today) |
| Far | every other catalog planet (≤ 56) | drawn only: far view of 12, 6 or 3 patches per face |

- `PlanetLayout.nearest(entries, mario, had)` picks the Near set: the 8 nearest by distance to
  their gravity, with hysteresis (a Near planet leaves only once another is 64 blocks nearer),
  so planets do not flicker between tiers. Checked every 10 ticks.
- `PlanetLayout.farLevel(radius, distance)`: 12 patches while the planet spans > 6° of view, 6
  above 2°, else 3. A level changes with 20% slack.
- **Entering Near:** the planet's `PlanetSession` loads from disk, or is generated off the client
  thread (as the editor's Create does today); prefetch starts when it is 9th or 10th nearest, so
  it is ready when it enters. It is added to the game as today; its Far record is removed in the
  same batch.
- **Leaving Near:** the session is saved if changed, GONE is sent, its memory is freed, and a Far
  record takes its place in the same batch.
- **A Far planet's mesh:** visited (a file exists): built from its cells by `PlanetLod` at the
  level's patch count. Never visited: sampled straight from the generator (terrain height and the
  biome's surface block at each grid point, `TerrainShaper` + `BiomeSurface`), without generating
  the planet. Meshes are cached per (planet, level) in memory.

### 3. Protocol and module (`syati/src/VoxelPlanet.cpp`)

- New record **GxcFar**: catalog-independent far id (1..255, its own pool), center, radius,
  level, flags (GONE); then its mesh as the existing far-view display list (VTXFMT5). Extra words
  big-endian, as the far view's (no Dolphin rebuild).
- Module: `MAX_FAR = 64` entries `{id, center, displayList, size}` in the module's own
  `JKRExpHeap` (see the module heap note). Drawn with the far views, culled by view; no gravity,
  no zone, no actor. Drop-all (planet id 0) drops them too.
- `VoxelStats` gains `far_count` and `far_bytes`.

### 4. Create World's tab (mixin on `CreateWorldScreen`'s tab list)

- **Planets:** slider 1..64. **Size of the others:** min and max sliders, 16..256.
- **First planet:** cycle Generated / Blueprint. Generated: biome picker (with Random). Blueprint:
  list of `BlueprintStore`'s blueprints. Its own radius slider (Generated only; a blueprint keeps
  its radius).
- **Spacing:** Near / Normal / Far.
- **Seed:** the world's (Minecraft's World tab); the tab says so in a line of text.
- Only for world type `galaxycraft:void`; other types hide the tab.
- The options travel to the server side with the world (written to
  `galaxycraft/galaxy.json` when the world is created), as the void preset does today.

### 5. Saving (`voxel/GalaxySave`)

- `galaxycraft/galaxy.json`: `{version: 1, options, entries: [...]}`, written at creation and
  whenever an entry is added or replaced.
- `player.json`'s `planet` becomes the catalog id. Old files (game id) read as catalog id 0.
- **Old worlds** (no `galaxy.json`): the catalog is built from the planet files on disk, one
  entry per file, read from each file's center and radius; options = defaults with `count` = files.
- First join: entry 0 is made (generated or from the blueprint) and the player lands on its top,
  as today; the rest stream in.

### 6. Errors

- The chosen blueprint is gone when the world is created: entry 0 is generated with the default
  biome; a chat line says so on first join.
- Not every planet fits: the catalog keeps those that did; a chat line on first join says
  "N of M planets fit".
- A planet fails to generate or load: it is marked failed for the session (Far drawn from the
  generator still, never retried in a loop); the log says why.
- Module out of far slots or heap: the farthest Far records are skipped; `VoxelStats` counts them.

## Testing

- **Unit** (`GalaxyCatalogTest`, `PlanetLayoutTest`, `GalaxySaveTest`):
  - same options + seed → same catalog; different seed → different;
  - entry 0 at the origin; every pair keeps the spacing; radii in range; count respected or
    reported short;
  - `nearest`: 8 at most, hysteresis holds against a planet 10 blocks nearer, yields to one 64
    nearer; `farLevel` thresholds and slack;
  - old world migration: N files → N entries, player.json's old id → 0;
  - generator-sampled far mesh: right patch count, heights within the planet's terrain range.
- **End to end** (`GalaxyProbe`, `tools/gxvoxel.sh galaxy`): a new world with 12 planets, Normal
  spacing; from the origin all 12 are drawn (`VoxelStats`: 8 planets + 4 far); fly the player
  (TP steps) to the far end: never more than 8 planets in the game, levels change, no planet
  missing in screenshots at three points; break a block on a far planet, fly back and forth, the
  edit is still there; `max_speed` no lower than `LodProbe`'s today at the same distances.

## Out of scope

Infinite space, per-sector catalog growth, floating origin, faster travel (stage 3); blueprints
for the other planets; structures; showing planets beyond the catalog (stars in the sky).
