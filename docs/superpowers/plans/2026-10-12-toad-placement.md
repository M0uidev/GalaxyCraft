# Toad NPCs, task 6: natural placement (plan, not yet coded)

Date: 2026-10-12. Supersedes "Task 6" of `2026-10-11-galaxycraft-toad-npcs.md`. Waits for progression
plan 2 (`Universe.tier(Star)`, `Universe.starType(Star)`).

## What exists

- `ToadStore` (toads.json per galaxy, `ToadClient.enterWorld`), `ToadRecord(id, type, pos, rot, stage)`,
  `ToadClient.put(type, feet, quat)` (stage = `PlanetClient.stage()`), `PlanetSession.surfaceAlong`
  to find ground. `Universe.Star` has sector, seed, planets; `GalaxyCatalog.Entry` has index, center,
  radius, seed per planet.
- There is no structure placer yet (progression stage 5), so villages cannot carry Toads yet.

## Design

1. **Pure planner** `toad/ToadPlan` (no Minecraft types, unit tested): given `(galaxySeed, sector,
   planetIndex, planetSeed, radius, systemTier, StarType, home)` returns a list of `Spot(id, type,
   direction on the sphere, band)`. Deterministic: same inputs, same ids and types. Id =
   `toad-<sector>-<planetIndex>-<n>`, so a record is never made twice.
2. **Rules by tier** (first draft, tuned by playing):
   - Home system, home planet: 1 GUIDE near the spawn (fixed id `toad-home`).
   - Every flat station: 1 TECHNICIAN (`toad-station-<id>`), placed when the station is created.
   - Tier 0: only the home Guide. Tier 1-2: 0-1 outpost per planet over radius 48 (MINER or EXPLORER
     by hash). Tier 3+: also MAILMAN, 0-2 per planet; blue/lava systems favor MINER (ores) and
     red dwarf favors EXPLORER.
   - Never in water or on an unwalkable column; direction chosen by hash, then walked to the
     nearest dry column (`SurfaceSampler.at`, the same height the mesher uses).
3. **When**: the first time a planet's session becomes active on the client (where `PlanetClient`
   spawns/loads a body), call `ToadPlacer.ensure(planetKey)`: if no record with the prefix
   `toad-<sector>-<index>-` exists and the planet has not been marked `toadsDone` in toads.json, plan
   and `put` them, then mark done. The mark (not the records) is what stops a Toad the player
   removed from coming back; one extra top-level field in toads.json (`placed: [planetKey...]`),
   old files load as empty.
4. **Where exactly**: feet on `surfaceAlong(centre + dir * (radius + margin), -dir, reach)`; the
   rotation is the one `ToadClient.place` already builds (up = surface normal). Near spawn for the
   Guide: 8 blocks from `PlanetSession.spawn` point.
5. **The hut (user decision 2026-10-12: a small mushroom hut at each outpost, not a lone Toad)**.
   A hut is a `Vegetation.Thing` built in code (`toad/ToadHut`: about 5x5x4 blocks of mushroom
   stem walls, a red mushroom block roof with white spots, a door gap, a floor patch at dy 0, a
   torch). `Vegetation.place(grid, depth, cells, height, f, i, j, col, thing, ids)` already lays a
   Thing on the grid whole-or-not-at-all, handles face edges and cover, so no new placing code.
   - **Hook (generation time)**: after `Vegetation.plant` in `Vegetation.cells`, a new
     `Vegetation.plantStructures(..., List<Structure>)` places each `(direction, Thing)` on the
     nearest column where the hut fits, flattening nothing (if it does not fit, try the next
     candidate direction from the same hash, up to 8). `PlanetBlueprint` carries the list
     (`structures()`, default empty so every existing caller is unchanged). The same hook is what
     progression's stage 5 structures (villages, mines) can use: I define it as generic
     (`Structure(String id, double[] dir, Thing thing)`), they add kinds.
   - The hut's block stays deterministic from the seed, edits persist as normal edits, and there is
     no `placed` bookkeeping for blocks. The Toad record is still put by the runtime step above,
     standing at the hut's door (the hut reports its door column), and uses the `placed` mark.
   - **Existing saved planets**: if a planet was already generated and saved with its cells, the hut
     cannot appear there. To check before coding: whether `PlanetStore.Saved` keeps cells or only
     edits over the generator. If cells, huts only appear on planets generated after this lands
     (fine for new worlds), and the runtime step skips the Toad when the planet has no hut so it
     never floats beside nothing.
   - **Fallback flag** `galaxycraft.toadHuts=false` (or no structure fits): the lone Toad
     placement described in 4, same ids.
   - Home Guide and station Technician stay lone Toads (a station is a flat metal deck; the home
     planet gets no hut in the first version).

## What I need from progression (b5)

- `Universe.tier(Star)` and `Universe.starType(Star)` exposed as in their plan (Task 2).
- A way from an active `PlanetSession` to its `Star` + planet index (or the catalog entry): today
  only `PlanetClient.stage()` and `GalaxyCatalog.Entry` are known to me. If `Universe.system()`
  already gives it, I use that.
- Confirmation that tier/star never change for a seed (derived, not stored), so ids stay stable.
- Nothing for the hut itself: `Vegetation.place` and the new `plantStructures` hook are in
  voxel/gen, which I understand is not being edited by b5 in Task 1-6 (stage 3-5 will be). If their
  stage 5 designs a different hook, tell me and I conform instead of keeping mine.
- Later: the structure table, and the Warp Core tier name for `ToadContext.warpCoreTier`.

## Tests

`ToadPlanTest`: determinism, tier 0 has no outposts, tiers only produce their types, ids unique,
no outpost under radius 48. `ToadStoreTest`: `placed` round trip and old file. Probe: extend
`ToadDolphinProbe` to enter a planet of tier >= 1 and count the placed Toads. Probe needs the
Dolphin slot from the orchestrator.

## Decided (user, 2026-10-12)

- A small mushroom hut at each outpost. Outposts per planet as drafted: 0-1 in tiers 1-2, 0-2
  (Mailman too) in tier 3+.
