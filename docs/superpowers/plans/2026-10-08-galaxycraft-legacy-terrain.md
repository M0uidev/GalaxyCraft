# Minecraft 1.7 terrain on planets — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans (the user chose native,
> token-friendly execution). Steps use checkbox (`- [ ]`) syntax.

**Goal:** generated planets get 1.7's terrain: 3D density, blended biome heights, oceans, rivers,
beaches, cave labyrinths, heights scaled to the planet.

**Architecture:** pure Java in `voxel/gen` (no Minecraft classes, tested offline). A planet's cell
(direction `d`, height `hb` blocks above the base surface) maps to a *virtual Minecraft column*:
1.7's noises are sampled at `q = d · f(hb)` with `f(hb) = R0·fh/hs + (hb/v)·fv`, so the tangential
frequency is 1.7's horizontal one and the radial frequency its vertical one: seamless (a point in
3D space) and anisotropic like 1.7. `y_mc = 63 + hb/v` feeds 1.7's height falloff.

**Tech stack:** Java 21, JUnit 5 (`./gradlew test` in `fabric/`), the gxvoxel harness for screenshots.

**Spec:** `docs/superpowers/specs/2026-10-08-galaxycraft-legacy-terrain-design.md`

## Global constraints

- Scale: `v = clamp(radius / 256, 0.1, 1)`; horizontal `hs = sqrt(v)` (tuned from screenshots).
- Generated planets' crust `depth = clamp(ceil(30·v) + max(6, radius/8), 3, 40)` and air
  `air = max(bp.air, clamp(ceil(60·v) + 8, 8, 48))`; layered planets unchanged. Heights past air − 4
  are soft-clamped (`lim·tanh(h/lim)`) as today.
- Cave tunnels 2–4 blocks wide (rooms ≤ 7); never carve within 1 cell of water; lava in the lowest
  15% of the crust.
- Biome ids are today's Minecraft ids; Auto = `biomeSize == -1` (default for new blueprints and
  catalog planets), `0` = one biome, `> 0` = fixed size.
- Same seed → same cells (no shared mutable Random across threads; per-face/per-cube seeds).
- Saved planets untouched (whole cells on disk).

## Review focus

1. Tiny planets (radius 10–24): no exposed bedrock, still ≥ 1 land biome, caves fit the crust → test in Task 4/6.
2. One-biome planets of a watery biome (`minecraft:ocean`) still get islands → test in Task 3.
3. Old blueprint JSON with `biomeSize` 0 or > 0 reads and builds → test in Task 7.
4. Far view of a never-built planet agrees with the built one (handoff pop) → test in Task 7.
5. Build time of a radius-256 planet stays ≤ 2× today's (timing tag) → test in Task 6.

---

### Task 1: 1.7 noise (`Perlin`)

**Files:** Create `gen/Perlin.java`; Test `gen/PerlinTest.java`.

**Produces:** `Perlin.Octaves(Random rnd, int octaves)` with
`double sample(double x, double y, double z)` (1.7's `NoiseGeneratorOctaves`: sum over octaves of
`ImprovedNoise(x·s, y·s, z·s) / s`, `s` halving each octave, permutation from `rnd`) and
`double sample2(double x, double z)`.

- [ ] Test: same seed same values; different seeds differ; continuous (|Δ| small for Δx = 1e-4);
  16-octave amplitude within ±2^16.
- [ ] Implement (port of ImprovedNoise `grad`, `fade`, permutation of 512).
- [ ] Commit.

### Task 2: biome table and layout (`LegacyBiome`, `BiomeLayout`)

**Files:** Create `gen/LegacyBiome.java`, `gen/BiomeLayout.java`; Test `gen/BiomeLayoutTest.java`;
modify `gen/BiomeSurface.java` (entries for 1.7's biomes: savanna, jungle, birch, dark forest,
swamp, windswept hills, snowy beach…).

**Produces:**
- `enum LegacyBiome` — `id` (modern), `root`, `variation`, `zone` (WARM/TEMPERATE/COLD/SNOWY/WATER),
  `hills()` (the hills variant's root/variation, or itself). 1.7 values: ocean −1/0.1, deep ocean
  −1.8/0.1, river −0.5/0, beach 0/0.025, stony shore 0.1/0.8, plains & desert & savanna & snowy
  plains 0.125/0.05, forest & birch & dark forest & jungle & badlands 0.1/0.2, taiga & snowy taiga &
  old-growth pine taiga 0.2/0.2, windswept hills 1/0.5, swamp −0.2/0.1; hills 0.45/0.3; savanna
  plateau & wooded badlands 1.5/0.025. `static List<String> land()`, `static List<String> all()`,
  `static LegacyBiome of(String id)`.
- `BiomeLayout(long seed, int radius, String fixed /* null = mixed */, int biomeSize /* -1 auto */)`
  with `LegacyBiome at(Vector3d dir)` and `double[] blend(Vector3d dir)` → `{root, variation}`
  blended over 5×5 points 1 lattice step (4·hs blocks) apart on the tangent plane, 1.7 weights
  `10/sqrt(dx²+dz²+0.2)/(root+2)`, halved where the neighbor is higher.
- Layout: `count = biomeSize < 0 ? 5 + radius/64 (max 9) : area/biomeSize²` region seeds (random
  unit vectors from the seed), directions warped by a 3-octave Perlin before the nearest-seed test;
  zone from `dot(seed, axis)` banded (snowy pole … warm pole), then any snowy region touching a warm
  one becomes cold; ~30% of regions ocean (interior → deep ocean); rivers where a river noise
  |n| < width (land only); beach / stony shore / snowy beach within 3 blocks of an ocean border;
  hills where a hills noise > 0.55 inside land regions. One-biome planets: every land column is
  `fixed`; a watery `fixed` keeps islands (noise > 0.6 → beach/plains).

- [ ] Tests: deterministic; warm never neighbors snowy (sample 20k directions, check pairs of
  2nd-nearest regions); Auto planets r 32/64/128/256 have ≥ 3 land biomes and ≥ 1 ocean; one-biome
  desert is all desert; ocean one-biome has some land; blend continuous.
- [ ] Implement. Commit.

### Task 3: density and the column scan (`Density`)

**Files:** Create `gen/Density.java`; Test `gen/DensityTest.java`.

**Produces:** `Density(PlanetBlueprint bp)` with `Scale scale()` (`v, hs, depth, air`),
`BiomeLayout layout()`, `double at(Vector3d dir, double[] blend, double depthNoise, double hb)`
(1.7's `func_147423_a` for one point: min/max/main octaves 16/16/8 at 684.412, main ÷80/÷160,
depth noise 16 octaves at 200, the `d5 = 8.5 + d13·4` base, `d6` falloff ×4 below, top fade),
`double depthNoise(Vector3d dir)`, and `int top(Vector3d dir)` = highest `hb` (blocks, relative
to base, may be negative) with density > 0 scanning down from air − 1 (used by the far view).

- [ ] Tests: plains planet's ground within ±4 of base; ocean blend sits ≥ 0.6·28·v below; windswept
  hills reach higher than plains on the same seed; density at the same point from two faces' edges
  equal (pure 3D point); `top` matches a cell scan of Task 4's output (later, in Task 7).
- [ ] Implement. Commit.

### Task 4: building cells (`PlanetGenerator` rewritten)

**Files:** Modify `gen/PlanetGenerator.java`, `voxel/VoxelPlanet.java` (`generatedDepth`,
`generatedAir`), `gen/SurfaceSampler.java`; delete `TerrainShaper`, `Climate`, `BiomeTable`,
`TerrainNoise`; `Worldgen` keeps `vegetation()` only.

**Produces:** `PlanetGenerator.cells(PlanetBlueprint bp, Vegetation.Library plants, ToIntFunction<String> ids)`
and `build(bp, plants, blocks, ids)`; `SurfaceSampler(PlanetBlueprint bp)` (wraps `Density`), its
`at(dir)` → `Column(height, biome)` from `Density.top`.

Steps inside `cells`: lattice per face at fractional positions `t·n/m` (m = ceil(n/4·hs⁻¹)… at
least 2) × every `max(1, round(8·v))` layers, density per node (faces in parallel), trilinear
interpolation → solid/air; below sea level (`k < depth`) air → water (frozen top → ice); surface
pass per column top-down (1.7 `replaceBlocksForBiome`: run of `j = 3 + surfaceNoise/3 + rnd·0.25`
filler under each top; under water top = filler or gravel when deep; sand/gravel shore; badlands
terracotta bands by k; deepslate below a third as today); then caves (Task 5), ores
(`Underground.ores`), plants (`Vegetation.plant`, `height[]` = topmost solid − (depth − 1), bare =
biome top with air/snow above).

- [ ] Tests (rewrite `PlanetGeneratorTest`, drop `GenFixtures` noise/table): no seams across edges
  (top heights ≤ worst inside + 1); ground between bedrock and sky; same seed same planet;
  oceans full to sea level; desert one-biome is sand with stone cliffs; trees stand on dirt and
  only fill air (kept); r 16 planet builds with no bedrock showing; old blueprints still layered.
- [ ] Implement. Commit.

### Task 5: cave labyrinths and ravines (`Caves`)

**Files:** Create `gen/Caves.java`; modify `gen/Underground.java` (drop noise `carve`); Test
`gen/CavesTest.java`.

**Produces:** `Caves.carve(CubeSphere grid, int depth, char[] cells, long seed, int caves, boolean entrances, int lava, int water)`.
16-block cubes of space crossing the crust; each rolls `rnd(seed, cube)`: a system with
probability `0.25·caves/50`, 2–6 tunnels from a start, each a 1.7 worm (yaw/pitch drift, pitch
against the local up ×0.7 so it stays level, radius 1–2 (+0.3 sine), length 40–120 blocks,
branching in two at a random point, 1 in 6 chance of a room radius ≤ 3.5); ravines 1 per 50 cubes,
radius 1.5 × 3 tall. Carving: cells within the ellipsoid, not bedrock, not within 1 of water, not
within `ROOF` of the top when `!entrances`; below `lava` k → lava.

- [ ] Tests: deterministic; carves only ground, never bedrock; no carved cell next to water; widest
  tunnel cross-section ≤ 4 blocks away from rooms (measure per worm in a test hook); lava only below
  the lava layer; carved cells form few large connected groups (labyrinth: largest component ≥ 50%
  of carved cells on r 64).
- [ ] Implement. Commit.

### Task 6: speed and probe

**Files:** Test `gen/PlanetGeneratorTest.java` (timing tag); gametest probe
`fabric/src/gametest/.../TerrainShots.java` (screenshots r 32/64/128/256 from space and surface,
via `GXC` harness like `LodProbe`).

- [ ] Timing test: r 256 build ≤ 2× the old generator's time measured on master (record the number).
- [ ] Probe; run `tools/gxvoxel.sh` with it (only when `pgrep -x dolphin-emu` is empty); look at
  the shots; tune `hs`, thresholds. Commit.

### Task 7: integration

**Files:** `PlanetBlueprint` (Auto = −1 accepted, default in `standard`), `PlanetEditorScreen`
(slider "Auto" / "One per planet" / sizes; biome picker from `LegacyBiome.all()`), `PickerScreen`
call, `GalaxyStream.recipe` (Auto unless the entry's biome is fixed: first planet chosen),
`GalaxyStream` far view (`new SurfaceSampler(bp)`), `PlanetClient.generateAsync`,
`GalaxyCatalog.make` callers (land list from `LegacyBiome.land()`), `McWorldgen` (drop noise/table),
`PlanetEditorProbe`, `LodSourceTest`, `GalaxyCatalogTest` if needed.

- [ ] Tests: old JSON blueprints (biomeSize 0, 64) read and build; `LodSourceTest` sampled vs
  built within 2 blocks on most patches; `./gradlew test` and `./gradlew build` green.
- [ ] Commit. Run the probe again; send screenshots to the user; user playtests before merge.
