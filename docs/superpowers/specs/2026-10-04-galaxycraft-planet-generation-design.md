# Generated planets: terrain and biomes from Minecraft's noise — design

Date: 2026-10-04. Status: approved in conversation, awaiting review of this spec.

## Goal

A planet blueprint can be **generated** instead of layered: its surface rises and falls with
terrain and its blocks come from a Minecraft biome. Terrain is seamless across the 12 edges and 8
corners of the cube-sphere. By default a planet is one biome (Mario Galaxy style: a desert planet,
a snowy planet); a slider turns on several biomes per planet with a chosen size.

Out of this spec (later stages, in this order): water and oceans at a sea level; caves and ores;
trees and vegetation (Minecraft's `ConfiguredFeature`s, possibly placed in the shadow dimension and
copied over); structures; Nether and End biomes.

## Decisions (made by the user)

- Configurable biome layout; default **one biome per planet**.
- The biome is chosen in the editor from a searchable list, with **Random** (from the seed) first.
- **Two separate modes**: `LAYERS` (today's flat planet from the layer list) and `GENERATED`
  (the biome decides every block; the layer list is hidden).
- Approach: Minecraft's own noises and overworld biome table, sampled in 3D on the sphere
  (projecting a flat `ChunkGenerator` onto the cube was rejected: seams on the edges, distortion).

## Blueprint (`PlanetBlueprint`)

New fields, all optional in JSON so blueprints saved before read as `LAYERS`:

| field | type | meaning |
|---|---|---|
| `mode` | `LAYERS` \| `GENERATED` | missing → `LAYERS` |
| `seed` | long | the planet's noise seed |
| `biome` | string | a biome id (`minecraft:desert`) or `random` (picked from the seed among the allowed ones) |
| `biomeSize` | int | 0 = one biome per planet (default); > 0 = several, about this many blocks across each |

`problem()` also checks: `biomeSize` in 0..512; `biome` not blank. An unknown biome id is reported
by the editor (it needs Minecraft's registry), not by `problem()`.

In v1 the biomes offered are the overworld's land biomes: no ocean, river, beach or underground
biomes (no water yet).

## Editor (`PlanetEditorScreen`)

- A **Mode: Layers / Generated** button in the top row.
- In Generated, the layer rows are replaced by: a biome button (icon-less, the biome's name; it
  opens `BiomePickerScreen`, the same search-list pattern as `BlockPickerScreen`, with
  "Random" first), a seed box with a 🎲 button, and a "Biome size" slider whose 0 reads
  "One per planet".
- The crust preview column is hidden in Generated.
- Unknown biome: the button text is red and Spawn/Save say why in the status line.

## Terrain (pure code, `voxel/gen`, no Minecraft classes)

For each column `(face, i, j)`, with `d` its unit direction and `p = d × radius` (blocks):

1. **Climate**: `TerrainNoise.sample(p)` gives five values in about −1..1: continentalness,
   erosion, ridges (weirdness), temperature, humidity. Being 3D noise at a point in space, columns
   next to each other get nearly the same values on any face: that is the whole seam story.
   - One biome: each value is mapped into that biome's range in the overworld table
     (`v = lo + (n + 1) / 2 × (hi − lo)`). A biome with several ranges uses their union (built: more
     varied ground than one range picked by the seed).
   - Several biomes: temperature and humidity are sampled at `p × (vanillaBiomeScale / biomeSize)`;
     `BiomeTable.find(climate)` names the biome per column. Continentalness is kept inland
     (≥ the table's coast value) until water exists.
2. **Height** `h` (blocks above the base surface, can be negative): a simplified Minecraft shape:
   continentalness sets the base, erosion how much relief there is, ridges make peaks and valleys
   (`TerrainShaper`, a few hand-tuned piecewise-linear curves). Scaled by `min(1, radius / 64)` so
   small planets are not spiky. Clamped to `[−(depth − 2), air − 4]`: at least one block above
   the bedrock and four of air above the highest ground.
3. **Blocks**, top down, from `BiomeSurface` (a table keyed by biome id):
   `cover` (optional, one block above the top), `top`, `filler` (3 blocks), `stone` (the rest,
   down to the bedrock). Defaults grass_block / dirt / stone. Entries in v1:
   desert sand/sandstone/sandstone; badlands (all three) red_sand/terracotta;
   snowy plains, snowy taiga, snowy slopes, grove: grass_block[snowy=true] + snow cover;
   ice spikes snow_block; frozen peaks packed_ice/snow_block; jagged peaks snow_block/stone;
   stony peaks stone/calcite; mushroom fields mycelium; old growth taigas podzol;
   mangrove swamp mud/mud; swamp grass/dirt; windswept gravelly hills gravel/gravel.
   **Steep slope**: if a column is 3+ blocks higher than a neighbor, its top (and cover) become
   the biome's stone, as Minecraft does on cliffs.
4. Bedrock at `k = 0` stays; the grid keeps today's crust depth and `depth` keeps meaning the base
   surface (Mario's fit and `surface()` are unchanged).

`PlanetGenerator.build(blueprint, TerrainNoise, BiomeTable, Blocks)` returns a `VoxelPlanet` via
the existing `VoxelPlanet.of(grid, depth, cells, blocks)`; `PlanetBlueprint.build` dispatches on
`mode`, so `PlanetClient`'s spawn path keeps calling one method.

Interfaces (so the logic is tested without Minecraft):

```java
interface TerrainNoise { Climate sample(double x, double y, double z); }      // per seed
interface BiomeTable {
    String find(Climate c);                    // several biomes
    List<Climate.Range> ranges(String biome);  // one biome; empty = unknown
    List<String> land();                       // what the picker and Random offer
}
record Climate(double continentalness, double erosion, double ridges, double temperature, double humidity) {}
```

## Minecraft bridge (client)

- `McTerrainNoise`: `NormalNoise` instances from the `minecraft:continentalness`, `erosion`,
  `ridge`, `temperature`, `vegetation` noise parameters (registry `Registries.NOISE` of the
  loaded world), seeded from the planet seed with Minecraft's `RandomSource` positional factory.
  Called directly, not through `Climate.Sampler`: the overworld router ignores Y for temperature
  and humidity, which on a sphere would band the planet.
- `McBiomeTable`: the overworld `MultiNoiseBiomeSourceParameterList` preset's
  `Climate.ParameterList`; `find` is its `findValue`, `ranges` its entries for one biome, `land`
  all its biomes minus the `#minecraft:is_ocean`, `is_river`, `is_beach` tags and the cave biomes.
  Names for the picker come from the biome translation keys.

## Spawning

Generation runs on a background thread (it is about 970,000 columns × 5 noises at radius 256, a
second or two); the editor closes, the status line of `/galaxycraft planet status` (and a chat
line) says "Generating…", and the planet is spawned on the client thread when it is ready, the
same way `requestSpawn(PlanetBlueprint)` does today. Layered blueprints keep building inline.
(Built: `PlanetGenerator.cells` runs off the thread with block ids looked up beforehand, since
McBlocks adds ids as it meets states; the `VoxelPlanet` is made on the game's thread. A
generated planet of radius 96 takes about half a second.)

## Tests

Unit (`PlanetGeneratorTest`, fake noise and table):
- Seamless: for every pair of edge-adjacent columns on different faces, heights differ by at most
  what the same distance gives inside a face (smooth fake noise).
- Heights within `[−(depth − 2), air − 4]`; the bedrock is never replaced.
- Same seed, same cells; another seed, other cells.
- One-biome mode maps climate into that biome's ranges; a desert planet's top blocks are sand,
  steep columns' tops are its stone.
- Several-biome mode calls `find` and mixes surfaces.
- A blueprint JSON without the new fields reads as `LAYERS` and builds as before.

Game (`PlanetEditorProbe`, `-PgalaxycraftEditor`): generate a desert planet of radius 32 and a
several-biome planet of radius 64 with the real noises; check sand on top of the desert, more than
one biome on the other, no column at the bedrock; screenshot the editor in Generated mode and the
biome picker.

## Error handling

- Unknown biome id (from an old or hand-edited blueprint): red button, Save/Spawn refused with a
  message; Random always works.
- The world's registry lacks a noise (other mods, data packs): the bridge falls back to
  `NormalNoise` with fixed parameters equal to vanilla's, logged once.
- Generation throws: logged, the chat says why, the current planet is left as it was.
