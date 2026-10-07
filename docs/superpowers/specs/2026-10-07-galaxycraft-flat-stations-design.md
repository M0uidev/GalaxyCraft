# Flat space stations: player-built platforms floating in space

2026-10-07. Status: design approved in conversation, not built yet.

The roadmap's **Flat space stations** (Now, `next`): player-built **flat** platforms floating in
space, to play flat Minecraft (farms, builds) without a sphere's distortion.

## 1. What the player gets

- Craft a **Station Core** and use it in open space: a 9×9×1 slab appears with the core in its
  middle. Build on it like in a flat Minecraft world.
- The station **grows as you build**: a block placed against any station block joins it, past the
  current edge too, up to 256×256 footprint and 128 tall. A block placed in open space with nothing
  next to it does nothing, as in vanilla.
- **Gravity** pulls "down" toward the slab inside a box over the station (its bounds plus 24 blocks
  above). Walk off the edge or fall below it and you are in space: elytra and pulse work as always.
  The underside has no gravity.
- Everything a planet's blocks do works on a station: crops grow, redstone, chests, furnaces, mob
  spawning by light, water, lighting, drops (the shadow-dimension mirror runs them).
- **Right click the core** for its menu: **Rename**, **Info** (size, blocks), **Pack up**.
- **Pack up** takes the whole station out of space, its blocks and the contents of its chests and
  other block entities, and gives a **Packed Station** item named after it (size in the tooltip).
  Using that item in open space unfolds the station again, in front of the player. Mobs and drops
  on it stay behind.
- From afar a station shows in the far view (LOD) like a planet.

## 2. Decisions taken

| Question | Answer |
|---|---|
| Purpose | Player-built bases (not generated content, not SMG-style hop platforms) |
| Gravity | One-sided flat "down" inside a box; no underside gravity; outside it is space |
| Creation | Station Core block + grows as you build; no editor |
| Moving a station | Core menu → Pack up → Packed Station item → unfold elsewhere in space |
| Approach | A: a flat cell grid next to the cube sphere, reusing the whole voxel pipeline |
| Maximum size | 256 × 256 footprint, 128 tall |
| Orientation | Taken when placed: up = the player's up then, facing snapped to 90° |
| Recipe | 4 iron blocks + 4 glass + 1 ender pearl (mid-game, so farms come early) |

Rejected approaches: a planet with a huge radius whose one face looks flat (six faces of waste,
size tied to radius, still curved, no edges or growth); a separate station system with its own
storage, mesher and collision (duplicates the pipeline and falls behind it).

## 3. Design

### 3.1 `CellGrid`: what the voxel pipeline needs from a grid

`CubeSphere`'s public API becomes an interface, `voxel/CellGrid`:
`cellCount, index(face,i,j,k), face/i/j/k(cell), corner(cell,di,dj,dk), center(cell),
cellAt(p), cellBeyond, neighbor(cell, side), side(cell, side), dir(...)`, plus the sizes the
pipeline reads today as fields (`n`, `layers`; `core` and `radius(k)` where only spheres need them).
`VoxelPlanet`, `PlanetMesher`, `PlanetCollision`, `PlanetRaycast`, `PlanetLight`, `PlanetLod`,
`LodSource`, `CellSpace`, `OutlineEdges`, `Placer`, `Fluids`, `PlanetBiomes` take a `CellGrid`.
Sphere-only code (surface, occluder, `gridSize`, generation) stays on `CubeSphere` or asks for it.
`CubeSphere` behaves exactly as before: every existing test keeps passing unchanged.

Side names keep their meaning: `TOP` is up (+k), `BOTTOM` down, `I_±`, `J_±` the four horizontal
sides, so block models (`CellSpace`), placement, outlines and the shadow mirror need no change.

### 3.2 `FlatGrid`

A box of `w × d × h` cells (i, j, k; one "face"), with a station rotation `q` and an offset so the
core's cell is at the origin. `corner(cell, di, dj, dk) = q · (i+di, k+dk, j+dj) - pivot`: corners come
out rotated into galaxy orientation, relative to the station's center. Display lists, KCL and far
view tiles the module gets are therefore oriented already; **the module draws and collides stations
with the planet code as is**. `cellAt` is an inverse rotation and three floors; `CellSpace.local` gets
a fast path for affine cells (no Newton). Neighbors past the box are `-1` (outside).

The mesher may merge coplanar faces along straight rows where a grid says its cells are affine
(`CellGrid.affine()`), which only a flat grid does; it is an optimisation, done only if measurements
call for it.

### 3.3 Storage and growth

A station's cells are stored densely like a planet's (`char` block + `byte` light ≈ 3 bytes a cell),
in a `FlatGrid` sized to its **bounds plus 16 blocks of slack** on each horizontal side and above,
aligned to chunks (8). A 64×64×32 base is about 0.4 MB; the 256×256×128 maximum about 25 MB.

A placement inside the outer 8 blocks of the grid **regrows** it: a new `FlatGrid` with fresh slack,
cells copied by (i, j, k), light recomputed, the station re-announced to the game with new chunk slots
(the editor's Replace path). It happens about once every 16 blocks of growth, never per block.
A placement that would pass the maximum is refused with a message on the action bar.

Bounds (the smallest box holding every non-air cell) are kept up to date on set; they size the
gravity box.

### 3.4 `Station`

`voxel/Station` holds a `VoxelPlanet` on a `FlatGrid`, its id (from the planets' 1..255 pool), name,
rotation, center (universe units) and bounds. `PlanetSession` keeps stations next to planets: the
focus (nearest body) picks between both, so clicks, outline, cracks, shadow, drops and mobs work on
stations unchanged. Detail chunks load within the same distances as planets.

Saved per system next to planets as `<stage>.s<n>.gxstation`: the planet file's cell format with a
station header (size, rotation, center, name) and a `BENT` trailer of block-entity NBT (chests,
furnaces, signs...) read from the shadow world when saving and written back when it mirrors them.

### 3.5 Gravity

- Mod: `GravityBody.Box(center, rotation, halfExtents)`; `outside(p)` is the distance past the box
  (station bounds + 24 blocks above, + 2 on the other sides). `CosmicWind`, `Flight` and the focus
  pick take it unchanged. `GravityFrame`'s "up" on a station is constant (the station's +k).
- Game: the module gets 8 `ParallelGravity` slots (`RangeType_Box`, plane up = the station's up, box
  matrix from rotation and half-extents, same priority as planets) next to its 16 `PointGravity` ones.
  Abyss-kill protection (`gAbyssKillPatch`) covers falling off a station like a planet.

### 3.6 Protocol

`GxcPlanet` keeps its layout. A station sends the flags word with a new **`GXC_PLANET_FLAT`** bit,
then, big-endian like the other extra words: `f32 up[3]`, `f32 forward[3]`, `f32 half[3]` (gravity box,
galaxy units) and `f32 box_center[3]` (relative to `center`). The host passes extra words on as is:
**no Dolphin rebuild**. `surface` = the top of its bounds (for the TP record), `occluder` = 0,
`gravity_range` = the box's bounding radius (used for "near" checks only). The floating origin moves
stations like planets.

### 3.7 Blocks, items, menu

- `galaxycraft:station_core`: a block with its own texture; recipe as in §2. Used in open space (no
  body's gravity reaches the spot, no body within 16 blocks): makes the station, the core at the
  slab's middle, the slab of smooth stone. Placed elsewhere it is refused (action bar) and stays in
  hand. On a station it cannot be mined; it leaves only by Pack up.
- Right click opens `StationScreen`: name field (Rename), Info (W×D×H, block count), Pack up.
- `galaxycraft:packed_station`: one item per packed station; its data holds the station's file id,
  name and size only. Used in open space it unfolds the station centered in front of the player,
  oriented as in §2; refused like the core elsewhere.
- A Packed Station lost (lava, despawn) never deletes its file. `/galaxycraft station list` lists
  packed and placed stations of the world; `/galaxycraft station restore <id>` gives its item back.

### 3.8 Edge cases

- Packing while the player (or Mario) stands on it: they stay where they are, now in space.
- No free planet id or gravity slot: placing refused, with a message.
- Two stations' boxes may not overlap: growth into another body's box is refused.
- Leaving the system with a station placed: it stays and saves with its system like planets.

## 4. Testing

- Unit: `FlatGridTest` (index round trip, neighbors and outside, `cellAt` and corners under rotation,
  affine `local`), `StationTest` (growth, bounds, slack and regrow keeping cells, max size refused,
  save/load round trip with block entities), `GravityBody.Box` cases in `CosmicWindTest`; all
  existing voxel tests unchanged on `CubeSphere`.
- Game test without Dolphin: `StationProbe` (`./gradlew runClientGameTest -PgalaxycraftStation`):
  place a core in space, build a farmland row with water, crops grow, chest with items, regrow past
  the slack, pack, unpack elsewhere, everything is still there. Listed in the gametest
  `fabric.mod.json` and `WalkOnStubPlanetTest`'s skip list.
- In Dolphin: `tools/gxvoxel.sh station`, 3000 blocks above the stage (empty space): Mario lands and
  walks on it, falls off the edge into flight, sees it in the far view from 300 blocks, the core menu
  and packing work; FPS (VoxelStats) compared with a planet of a similar cell count.

## 5. Out of scope

Underside or wrap-around gravity, generated sky islands (a follow-up on the same grid), moving or
rotating stations, a station as spawn point, listing stations on the galaxy map, multiplayer.
