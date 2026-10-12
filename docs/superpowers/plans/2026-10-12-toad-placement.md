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
5. **Villages later**: when progression stage 5 has a structure placer, it calls
   `ToadPlan.forStructure(structureSeed, kind)` for its Toad and passes the house position; until
   then outposts are a lone Toad (optionally a 3x3 mushroom-block hut placed through `Placer`).

## What I need from progression (b5)

- `Universe.tier(Star)` and `Universe.starType(Star)` exposed as in their plan (Task 2).
- A way from an active `PlanetSession` to its `Star` + planet index (or the catalog entry): today
  only `PlanetClient.stage()` and `GalaxyCatalog.Entry` are known to me. If `Universe.system()`
  already gives it, I use that.
- Confirmation that tier/star never change for a seed (derived, not stored), so ids stay stable.
- Later: the structure table, and the Warp Core tier name for `ToadContext.warpCoreTier`.

## Tests

`ToadPlanTest`: determinism, tier 0 has no outposts, tiers only produce their types, ids unique,
no outpost under radius 48. `ToadStoreTest`: `placed` round trip and old file. Probe: extend
`ToadDolphinProbe` to enter a planet of tier >= 1 and count the placed Toads. Probe needs the
Dolphin slot from the orchestrator.

## Open questions for the user

- A lone Toad on the surface, or a small mushroom hut with it?
- How many outposts per planet is right (first draft: at most 2)?
