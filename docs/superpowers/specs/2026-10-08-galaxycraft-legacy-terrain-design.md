# Generated planets: Minecraft 1.7 terrain — design

Date: 2026-10-08. Status: approved in conversation, awaiting review of this spec.

## Goal

Generated planets get the terrain of Minecraft Release 1.7–1.17: 3D density with overhangs and
cliffs, each biome with its own height and roughness blended into its neighbors, oceans, rivers,
beaches, and cave labyrinths. The user finds today's terrain (a simplified 1.18+ climate heightmap,
`2026-10-04-galaxycraft-planet-generation-design.md`) "not that good" and feels old terrain fits
small worlds better.

Out of this spec, in this order after it: the far view bugs (a far view piece left over chunks next
to the player; caves seen from outside drawn wrong until you go in), then swimming. Whether trees
still come out broken (one block above the grass, a trunk missing a log) is checked on the new
terrain before deciding on a fix.

## Decisions (made by the user)

- Release 1.7–1.17 terrain (not Beta 1.7.3, not 1.2–1.6).
- **Several biomes per planet by default**, like a small Minecraft world; one-biome planets stay
  possible.
- Biome size **scaled to the planet**: about 4–8 regions whatever the radius.
- **Real water depths** (oceans, deep lakes); swimming is the next stage after the far view bugs.
- Heights **scaled to the planet** (`radius / 256`), the crust and air made deeper for it.
- Approach A: port 1.7's generator onto the sphere as pure code (not modern Minecraft's generator
  with a 1.7-like preset; not a retuned heightmap).
- Caves are a **labyrinth**: many narrow tunnels that branch and cross, no giant rooms.

## Terrain shape (`voxel/gen/legacy`)

The ground is where 1.7's density (`ChunkProviderGenerate`) is positive:

- Noises: 16-octave low and high, 8-octave selector, 16-octave depth, as 1.7 makes them; our own
  port of 1.7's improved Perlin octaves, seeded by the planet's seed (no Minecraft classes, so the
  tests run it offline).
- Each biome's `rootHeight` and `heightVariation` (1.7's table), blended over the 5×5 neighborhood
  with 1.7's weights. The neighbors are points offset along the surface in 3D (tangent plane), so
  the blend has no seams.
- The density falls off above the blended base as in 1.7.
- Sampled at points in space: a cell's direction × its distance from the center, so neighboring
  cells on any face sample neighboring points. On a coarse lattice (as 1.7's 4×8×4) interpolated
  in between; lattice points on face edges are shared, so faces agree.

**Scale.** 1.7's heights, taken from sea level (y 63 → the planet's base surface), are multiplied by
`v = radius / 256`: radius 256 gets about Minecraft's own (ocean floors ~28 down, hills 40+ up),
radius 64 oceans ~8 deep and hills ~12–16 high. The horizontal scale shrinks with the planet less
steeply (a curve tuned from screenshots, starting at `sqrt(v)`), so small planets keep real hills.
Generated planets get a crust and air of up to `radius / 4` each (64 at radius 256) instead of
today's caps of 24 and 32; layered planets keep today's.

## Biomes

A planet is split into regions by warped cells on the sphere (a 3D Voronoi of seeded points,
borders warped by noise): about 4–8 at Auto whatever the radius.

- Each region gets a climate zone (snowy, cold, temperate, warm) from a slow temperature noise;
  as in 1.7, warm never touches snowy.
- About 30% of regions are ocean or deep ocean.
- Land regions pick from 1.7's zone lists: warm desert, savanna, plains, mesa; temperate forest,
  roofed forest, birch forest, plains, extreme hills, swamp, jungle; cold taiga, mega taiga,
  extreme hills; snowy ice plains, cold taiga.
- **Rivers** along borders between land regions; **beaches** where land meets ocean (stone shore
  at extreme hills); **hills** patches inside a region use 1.7's hills height and roughness.
- 1.7 biomes map to today's ids (extreme hills → `windswept_hills`, mesa → `badlands`, roofed
  forest → `dark_forest`, mega taiga → `old_growth_pine_taiga`, ice plains → `snowy_plains`, cold
  taiga → `snowy_taiga`, ...): blocks' colors and trees come from today's Minecraft, as now.

## Underground

**Cave labyrinths** (1.7's worm carver, made denser and narrower):

- Space around the planet is cut into 16-block cubes; each crossing the crust rolls from the seed
  and its position whether it starts a cave system (more often than 1.7's 1 in 7) and how many
  tunnels.
- Tunnels are **2–4 blocks wide**, branch more than 1.7's, and run mostly level, level meaning
  along the sphere (pitch taken against the local up), so they curve with the planet and cross
  into each other: a labyrinth through the crust.
- Rooms are few and small (5–7 blocks across at most).
- Ravines are rare and narrow.
- Tunnels reaching the surface leave openings in hillsides; the blueprint's `entrances` still
  turns them off.
- The deepest ~15% of the crust (1.7's y < 10) fills cave floors with lava.
- Caves never open into a sea or river (today's keep-roof rule).

**Ores**: today's code, over the deeper crust.

## Surface

1.7's `replaceBlocksForBiome`: the biome's top and filler (1–5 deep by a noise), stone below,
deepslate deep as today; sand or gravel at shores and on the sea floor; mesa's terracotta bands;
snow on top and ice on still water in snowy biomes. Cliffs come out of the density as bare stone.
Water fills every open cell below sea level the sky reaches (oceans, rivers, low basins); closed
caves under sea level stay dry. Small surface water lakes as 1.7's; lava lakes only deep in caves.

## Trees and plants

Per-biome counts from 1.7 (forest 10 trees a chunk, roofed forest 50, plains rare, desert none;
cactus, dead bushes, flowers, grass at 1.7's rates), grown as today's Minecraft features through
today's planting code (`Vegetation`). A column's ground is its highest solid cell with sky above;
plants only on bare biome tops, never under an overhang.

## Integration

- `PlanetGenerator.cells` stays the entry point; generated planets go through `legacy`.
- Removed: the climate path (`TerrainShaper`, `Climate`, the modern `BiomeTable`, `TerrainNoise`
  from `McWorldgen`). Saved planets are whole cells, so nothing needs it.
- Visited planets keep their terrain. Catalog planets never visited, in old worlds too, come out
  with the new terrain (and far view).
- `PlanetBlueprint.biomeSize`: `-1` = Auto, the new default; `0` (one biome) and sizes > 0 keep
  their meaning. `water` on by default. The editor's slider reads "Auto" at its first stop and
  "One per planet" next.
- Create World: a specific biome for the first planet makes a one-biome planet; Random makes an
  Auto one. Catalog planets are Auto.
- The biome picker lists the 1.7 biomes by today's names.
- **Far view of a planet never built**: `SurfaceSampler.at(dir)` scans the density down that
  direction on the same lattice: the first solid ground under the sky, its biome, water when under
  sea level. So the far view matches the blocks at handoff.
- **Speed**: coarse lattice, six faces in parallel; a radius-256 planet builds in at most twice
  today's time.

## Tests

Offline (JUnit, `GenFixtures` style):

- Seams: columns next to each other across edges and corners differ by ≤ 2 blocks.
- Same seed, same planet.
- Warm never next to snowy.
- Auto planets of radius 32, 64, 128, 256 have ≥ 3 land biomes and some ocean.
- Ground within crust and air; bedrock never exposed but on lava cave floors.
- Oceans full to sea level, closed caves dry, no cave drains a sea.
- Caves: no tunnel wider than 4 (rooms aside); a cave system's cells connect.
- Far view sample within 2 blocks of the built planet's top.
- Build time bound above.
- Today's `VegetationTest` passes.

In game: a gxvoxel probe screenshots generated planets of radius 32, 64, 128 and 256 from space and
from the surface, to tune the scale curves with the user. The user playtests before merge.
