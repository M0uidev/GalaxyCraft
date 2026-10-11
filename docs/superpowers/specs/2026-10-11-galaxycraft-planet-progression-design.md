# Planet progression: tiers, stars and what each planet holds — design

Date: 2026-10-11. Status: approved in conversation, awaiting review of this spec.

## Goal

Make travelling between planets matter. Today every planet has the same ores, any system is one free
warp away and nothing asks the player to be ready. The user wants a real progression: what a planet
holds and what it asks of you depend on **where it is**, the ship's reach opens the distance, and the
planet asks for gear to survive and mine. It should feed a future **3D map** (which planet is more
dangerous) and keep advancements, structures and Mario Galaxy mobs in mind.

## Decisions (made by the user, 2026-10-11)

- **Gate C**: the ship opens distance, the planet asks for gear. Early tiers: harder mobs and
  resources only; environmental dangers (cold, heat, no air) from tier 3 on.
- **Tier by rings with noise**: distance from the home system, plus ±1 per system.
- **Each system has a star** of its own kind (lava star, etc.) that makes its planets different.
- **Planets farther apart by default** (more than today's *Far*; the user said "more than 1500
  blocks").
- Warp: the ship's **Warp Core** limits new systems; visited ones stay free ("what is most
  balanced": Claude's choice, approved).
- A number is a first draft: **refined by playing**, not decided here.
- Parallel sessions later for: the 3D map, Toad NPCs (villagers that trade and explain the world).

## Out of scope

The 3D map's interface (this leaves its data ready), Toad NPC behavior, the gear's recipes (own
sub-project, §Hazards), Nether and End planets (already in the roadmap, they build on tiers).

## What exists (checked 2026-10-11)

- `universe/`: sectors of 8,192 blocks, ~6 in 10 holding a system of 2 to 12 planets, the home
  system at the center, stars of their own color (`StarField`, `SystemIndex`). Warp (K / N) is free
  to any star; Pulse (Ctrl in the void) flies at 400 blocks/s between systems.
- `voxel/gen/Underground.ORES`: one fixed list (coal, copper, iron, lapis, gold, redstone, diamond).
- `McVegetation` forces `persistent = true` on every leaf it plants: **the cause of leaves that
  never decay** when the logs are gone.
- `docs/GALAXIA.md`: *Far* spacing is 300 blocks between gravities (the galaxy measures ~2,000).

## Model

### Tier and star (pure code, `universe/`)

- `Tier`: 0 to 5 from the distance to the home system. Initial ring edges (to tune): 0 home and
  neighbors, 1 up to ~20,000 blocks, 2 ~40,000, 3 ~70,000, 4 ~110,000, 5 beyond. A per-system noise
  from the world seed adds −1, 0 or +1 (never below 0; the home system is always 0).
- `StarType`: yellow (balanced, home), red dwarf (cold, dim), lava/ember (hot, volcanic), blue
  (bright, rare, high tiers). Drawn from the seed weighted by tier (red and lava from tier 1, blue
  from tier 3).
- `SystemIndex` returns tier and star with each system; both are derived, never stored, so the same
  seed gives the same universe.

### Tier profile (`voxel/gen/TierProfile`)

Data by tier and star: ore table, biome weights, rare-biome list, hazard flags, mob table id. The
generator takes the profile of the planet's system; blueprint planets keep their own settings.

| Tier | Ores (absent below, not rare) | Hazards | Structures | Mobs |
|---|---|---|---|---|
| 0 | coal, copper, little iron | none | none / small ruins | vanilla passive and basic |
| 1 | + plenty of iron, lapis, redstone, gold | none | villages, abandoned mines | + easy Mario mobs (Goomba) |
| 2 | + diamond (deep), emerald (mountains) | longer caves, deep lava | temples, pillagers | + Koopa, Boo |
| 3 | + quartz, ancient debris, amethyst | extreme cold or heat by star | strongholds, bastions | + Bullet Bill, Thwomp |
| 4 | + netherite, abundant diamond | + no air on some planets | ancient cities | + minor bosses |
| 5 | End stone, purpur, unique materials | all, combined | End cities | bosses |

Star bias: lava raises volcanic biomes, basalt, Nether blocks and heat; red raises snowy biomes and
cold; blue raises rare ores.

### Biomes

A **rare layer** over 1.7's: Mushroom Fields (islands in ocean regions, with Mooshrooms), cherry
grove, bamboo jungle and others that 1.7 lacks, each with its own palette and trees (planted through
`McVegetation`). Weighted by tier and star. `LegacyBiome.nearest` keeps mapping what it cannot place.

### Travel

- **Warp Core**: a new ship module with its own tier. Its tier is the highest ring a **not yet
  visited** system can be warped to. Visited systems: free warp from the ship or the map.
- Without a ship: elytra and Pulse only **inside the current system**; Pulse no longer reaches
  another one.
- The module's item, recipe and Table slot belong to the ship code (`ship/*`, owned by another
  session): this spec defines `Tier.reach(moduleTier)`, the ship side consumes it.

### Spacing

The galaxy generator's default spacing grows to at least 1,500 blocks between planets' gravities
(the exact default measured in the code first; *Near*, *Normal*, *Far* shift with it).

### Hazards and gear (sub-project)

Cold, heat and no air from tier 3, answered with craftable gear made from earlier tiers' materials.
Own spec and plan; this one only leaves the profile's hazard flags.

### Advancements

A tab of its own: first landing per tier, first Warp Core, first star of each kind, each boss. Own
sub-project after the content.

### Trees (first piece, ships alone)

- Reproduce first: a test grows each tree type on a planet face and counts logs against Minecraft's
  own result (`Vegetation`'s whole-or-nothing planting already covers some cases; the cases the user
  sees, a trunk missing a log, are found by the test before any fix).
- Plant leaves with `persistent = false` and the right `distance`.
- **Leaf decay in the voxel layer**: breaking a log (or any block holding leaves up) checks the
  leaves within 6 of it; a leaf with no log within 6 is removed with its drops, over a few ticks.
  Works on cells beyond the shadow mirror.

### Old worlds

Visited planets keep their terrain. Systems never visited come out with the new tiers, stars and
profiles.

## Work order

Each its own branch, short spec, plan and playtest by the user (nothing merges before it).

1. Trees and leaf decay.
2. Tiers, stars, spacing, Warp Core data.
3. Content by tier: ores, biomes, rare biomes.
4. Hazards and gear.
5. Structures and Toad outposts.
6. Advancements.

In parallel, own sessions and branches: 3D map, Toad NPCs, and a feasibility spike for Mario mobs
(SMG2 actors with Minecraft AI, using the game-drawn entities path).

## Tests

Offline (JUnit, `GenFixtures` style):

- Same seed, same tier and star for a system; home system always tier 0; noise stays within ±1.
- Tier never decreases with distance by more than the noise (ring monotonic).
- Profile: no ore above its tier in a planet of each tier (radius 32, 64, 128); each tier's ores
  present in planets of that tier.
- Warp: a not-visited system past the Warp Core's reach is refused; visited ones allowed.
- Leaf decay: a tree whose logs are all removed ends with no leaves; leaves within 6 of a log stay.
- Trees: each tree type's log count equals the grown tree's, on every face and edge of a planet.
- Existing generator tests keep passing.

In game: a probe flies to a system of each tier and screenshots a planet; the user playtests.

## Open numbers (tuned by playing)

Ring edges, ore rates per tier, spacing default, leaf decay speed, Warp Core tiers per ring.
