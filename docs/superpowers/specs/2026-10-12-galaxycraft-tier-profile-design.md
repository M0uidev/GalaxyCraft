# Stage 3: what a planet holds by tier and star (TierProfile) — short spec

Date: 2026-10-12. Status: draft for the orchestrator; no code until the user playtests stage 2.
Parent: `2026-10-11-galaxycraft-planet-progression-design.md` (work order item 3). Stage 2 gives
`Universe.tier(Star)` and `Universe.starType(Star)` (commit 3474d87).

## Goal

A generated planet's ores, biomes and (declared, not yet acted on) hazards and structures depend on
the tier and star of its system. The home system and any planet made from a blueprint stay as they
are.

## What exists (checked 2026-10-12)

- Ores: one fixed list, `voxel/gen/Underground.ORES` (coal, copper, iron, lapis, gold, redstone,
  diamond), laid by `Underground.ores(..., bp.ores() percent, ...)`.
- Biomes: `BiomeLayout.at` picks a `LegacyBiome` (1.7's set, each with a `Zone`: WATER, TEMPERATE,
  WARM, COLD, SNOWY) from the planet seed; `PlanetGenerator.cells` builds palettes per biome.
- Trees and plants: `Vegetation` (whole-or-nothing). dc (feat/toad-npcs) adds
  `Vegetation.plantStructures(..., List<Structure(id, dir, Thing)>)` after `plant`, carried by
  `PlanetBlueprint.structures()` (default empty). This spec **reuses that hook**, no second one.
- Where a system planet's blueprint is made: `GalaxyStream.java:334`
  (`PlanetBlueprint.standard(...).withBiome(...).withUnderground(50, true, 100).withPlants(100)`),
  from a `GalaxyCatalog.Entry`; `SystemIndex.name/parse` maps the entry's index to its sector.

## Design

### TierProfile (pure, `voxel/gen/TierProfile`, no Minecraft types)

`TierProfile.of(int tier, StarType star)` returns an immutable value, derived, never stored:

| Field | Meaning | Used by |
|---|---|---|
| `List<OreRule> ores` | ore block, depth range, veins/1000 cells, vein size, `minTier` already applied | `Underground.ores` |
| `Map<Zone, Double> zoneWeights` | multiplier per climate zone (star bias) | `BiomeLayout` |
| `List<String> rareBiomes` + weight | extra biomes allowed on this planet | `BiomeLayout` |
| `Set<Hazard> hazards` | COLD, HEAT, NO_AIR (flags only; stage 4 reads them) | stage 4, 3D map |
| `List<String> structureKinds` | ids a later stage maps to `Structure`s (empty in stage 3) | `plantStructures`, stage 5 |
| `String mobTable` | id only (mobs are another session) | later |

Ore rules by tier (first draft = the parent spec's table; numbers tuned by playing):

- 0: coal, copper, little iron (iron rate x0.4).
- 1: + iron full, lapis, redstone, gold.
- 2: + diamond (deep, rate x0.5), emerald (windswept biomes only).
- 3: + quartz, ancient debris (deep, tiny), amethyst geodes (declared; geodes later).
- 4: + diamond x1.5, netherite scrap stays debris; 5: End stone and purpur as stone variants.
- Star bias on top: LAVA: part of the stone becomes basalt/blackstone, nether gold and quartz ores
  appear from tier 2, heat flag from tier 3; RED_DWARF: snowy/cold zone weights x2.5, warm x0.4,
  cold flag from tier 3; BLUE: ore rates of the rare ores x1.5, no hazard.
- Hazards: none below tier 3; at 3 the star's flag; at 4 NO_AIR on a planet whose seed rolls it
  (1 in 4); at 5 all of them that the star allows.

### Carrying it to the generator

- `PlanetBlueprint` gets two derived fields, `int tier` and `String star`, **default 0 / "legacy"**.
  `"legacy"` means: today's `ORES`, today's zone weights, no rare biomes. Old JSON without the
  fields loads as legacy, so a saved blueprint and every user blueprint generate exactly as before.
- `GalaxyStream` sets them from `Universe.tier/starType` of the entry's system, **only when the
  world's layout is 3 (new worlds)**. Layout 1 and 2 worlds keep `legacy` for every planet: their
  visited planets cannot change, and unvisited ones simply stay as they were (a world made before
  this feature is one coherent legacy world; the parent spec's "unvisited systems get the new
  profiles" is dropped for old worlds to avoid a seam). Decision to confirm with the user.
- Home system planets are tier 0 yellow: profile tier 0 (iron scarce, no diamond) in layout 3 worlds,
  which is the point of progression. Blueprint-mode planets are always legacy.
- `Underground.ores` takes the profile's rules instead of the static list; `Underground.blocks()`
  (the atlas / block id list) returns the union of all tiers so the runtime atlas and the shadow
  dimension never miss a block (the "planet invisible = atlas incomplete" failure mode).

### Biomes

1. Star bias only (3b): `BiomeLayout` multiplies the choice weight of each zone by `zoneWeights`.
   Pure and testable with the existing `LegacyBiome` set.
2. Rare layer (3c): new entries beside `LegacyBiome` (a `RareBiome` enum, not mixed into 1.7's list so
   `LegacyBiome.nearest` keeps working): mushroom fields (islands in ocean, mooshrooms later),
   cherry grove, bamboo jungle, then the nether-like basalt deltas for lava stars. Each needs a
   palette (cover/top/filler), tint, and tree `Thing`s through `McVegetation`; added one by one,
   each gated by `rareBiomes` of the profile. Planting uses the existing `Vegetation` path.

### Structures

Stage 3 only **declares** `structureKinds` and proves the hook is reachable from the profile
(`PlanetBlueprint.structures()` is filled from `TierProfile` plus dc's Toad huts). Villages, mines,
temples and so on are stage 5 and add kinds to the same `Structure` list.

## Tests (JUnit, `GenFixtures` style, offline)

- Same (tier, star) gives equal profiles; legacy equals today's `ORES` exactly (pins old worlds).
- No ore below its `minTier` appears in a planet of each tier (radius 32, 64, 128); each tier's ores
  appear in planets of that tier.
- `Underground.blocks()` contains every ore any profile can lay.
- Layout 2 world: every generated entry resolves to legacy; layout 3: tiers follow
  `Universe.tier`.
- Star bias: over many seeds, red dwarf planets have more cold-zone area than yellow ones.
- Old blueprint JSON without `tier`/`star` loads and generates the same cells as before.
- In game (probe, one slot): fly to a system of each tier, screenshot a planet; count ore types.

## Order and sizes

1. 3a ore tables + `TierProfile` + legacy gate (small, testable, ships alone).
2. 3b star bias on zones.
3. 3c rare biomes, one per commit.

## Open numbers

Ore rates and depths per tier, star bias factors, hazard roll for NO_AIR, which rare biomes and
their weights, whether old worlds stay legacy (decision above).
