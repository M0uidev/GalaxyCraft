# Infinite universe: endless systems, a floating origin, faster travel

2026-10-06. Status: research and design, for review. The pure core (§3, §4.1, §4.2) is built and
unit tested on branch `feat/infinite-universe`. The game side (§4.3 on) needs SMG2, Dolphin and
the Syati toolchain, so it is left for a session on the PC.

Stage 3 of the galaxy (galaxy options spec, "Out of scope"). The roadmap's **Infinite universe**:
No Man's Sky-like endless space, planets generated as you explore, a floating origin for SMG2's
floats, faster travel than elytra.

## 1. The problem, in numbers

SMG2 keeps every position as a 32-bit float in galaxy units (80 per block): Mario, the camera, gravity
actors, collision parts' matrices, the view matrix Dolphin's GX pipeline multiplies by. A float
has 24 bits of mantissa, so the smallest step it can take grows with the distance from (0, 0, 0):

| Distance from the origin | Smallest step | In blocks | What it looks like |
|---|---|---|---|
| 1,000 blocks | 0.008 units | 0.0001 | perfect |
| 5,000 blocks (today's biggest galaxy) | 0.03 units | 0.0004 | perfect |
| 13,107 blocks (2^20 units) | 0.06–0.125 units | 0.0016 | fine |
| 100,000 blocks | 0.5 units | 0.006 | Mario and the camera start to tremble |
| 1,000,000 blocks | 8 units | 0.1 | collision misses, blocks wobble |
| 12,550,000 blocks (Minecraft's Far Lands) | 64 units | 0.8 | broken |
| 30,000,000 blocks (Minecraft's border) | 256 units | 3.2 | unplayable |

(`OriginTest.floatsShakeFarAwayAndNotNearTheOrigin`.) The error is not only where things are
drawn: the game subtracts positions every frame (gravity's direction, KCL checks, the view matrix),
and each subtraction carries the step of the larger number.

Where the numbers live today:

| Program | Type | Far away |
|---|---|---|
| SMG2 module, Dolphin (GX), the protocol | `float` galaxy units | **breaks** (table above) |
| Planet meshes and KCL (`GxcChunk`, far views) | relative to the planet's center | fine at any distance: only the center is big |
| The mod (`GravityFrame`, catalog, sessions) | `double` galaxy units | fine to ~10^11 blocks, then the same problem |
| Minecraft's player (void overworld) | `double`, re-based in y only | x/z grow with travel: chunks of void are generated and saved, and the border is at 30M |
| Planet terrain noise | planet-local coordinates, per-planet seed | fine: no Far Lands (they came from noise fed huge coordinates) |

So the fix is mostly for SMG2's floats, and a little bookkeeping in the mod so that "infinite"
really is infinite (longs, not doubles, for where things are in the universe).

## 2. What other games do (and what we take)

| Game | How | We take |
|---|---|---|
| **No Man's Sky** | A hierarchical address (galaxy, region X/Y/Z, system, planet: the 12 portal glyphs); everything is generated from a seed hashed from the address, nothing stored unless the player changed it. Each star system is its own local scene; the hyperdrive jump between systems hides the switch; the pulse engine is fast travel inside a system, and drops out near planets. | Address = integer sector coordinates; systems from a hash; a system is the unit the game loads; warp between systems; a pulse that drops out near gravity. |
| **Kerbal Space Program** | Floating origin: when the ship is far from (0, 0, 0) the whole scene shifts so it is back at the origin ("Krakensbane" also removes high velocities from the frame). | The floating origin, moved rarely and in one frame. |
| **Outer Wilds** | The player is always the origin: the solar system moves around them. | Not every frame (too costly in SMG2: collision parts, actors), but the idea that the origin follows the player. |
| **Elite Dangerous** | A 1:1 Milky Way from a procedural generator (Stellar Forge), systems named by their sector; supercruise inside systems, hyperspace jumps between them. | Same split: flight inside a system, a jump between systems. |
| **Star Citizen / Unreal Engine 5** | 64-bit positions throughout ("large world coordinates"). | Doubles and longs in the mod; SMG2 cannot change its floats, so it gets the floating origin. |
| **Minecraft** | Renders relative to the camera; its Far Lands were noise fed huge coordinates. | Universe-scale decisions use integer hashes only, never float noise of absolute positions. |

## 2b. Why not make SMG2 use 64 bits?

The idea (2026-10-06): if 32-bit floats are the problem, move SMG2 to 64-bit doubles. Checked
against Syati's SMG2 headers:

- **The game is built on 32 bits all the way down.** `TVec3f` appears ~2000 times across 226
  headers: Mario (dozens of cached `TVec3f`/`Mtx` fields in `MarioActor`), every actor's
  `mTranslation`, gravity, the camera, collision (`CollisionParts` keeps four `TMtx34f`). Its
  vector and matrix math (`PSMTX*`, `PSVEC*`) runs on the Gekko's *paired singles*: two 32-bit
  floats per register, the CPU's only SIMD. The GPU (GX's transform unit) takes 32-bit matrices.
  The level files store 32-bit floats too. Changing that means rewriting most of the game's code.
- **It would be slower**, not faster: doubles lose the paired-single SIMD the whole engine uses.
- **But the Gekko's normal FPU does doubles natively**, at the same speed as floats. So the code
  we write ourselves can use 64 bits for free.

So it is a hybrid, and the design below already uses it:

| Who owns the code | Precision | How far it holds |
|---|---|---|
| The mod (Java) | `double` + `long` cells (`UPos`) | endless |
| Our module's own math (planet and view matrices, drops, teleports) | `double` where a big number meets a small one: e.g. the planet's matrix computed camera-relative (`center - eye` in double, then to float) | endless, and steady between origin moves |
| SMG2's engine (Mario, collision, gravity, camera, GX) | 32-bit float, unchanged | kept small by the floating origin (§4.3): never past 13,107 blocks, 1/8 unit or better |

What Syati's headers also settle for §4.4:

- `CollisionParts::resetAllMtx(const TPos3f&)` sets a part's base matrix *and* its previous one,
  so a moved part is a teleported floor, not a fast-moving one dragging Mario (`setMtx` alone would
  be the latter). Step 2 uses it: no need to drop the chunks.
- `MR::setPlayerPos`, `MR::setPlayerPosAndWait` and `MR::setPlayerBaseMtx` move Mario;
  `MR::resetCameraMan` resets SMG2's own camera. The spike only checks Mario's speed survives.

## 3. Decisions

| Question | Choice |
|---|---|
| Unit of the universe | Sectors of 8192 blocks; at most one solar system per sector (60% have one), jittered inside it so two systems' gravities never meet (≥ 512 blocks of void between their reaches) |
| What is a system | A catalog made by the existing `GalaxyCatalog` (2..12 planets, sizes, spacing, seed: all from the sector's hash), within 2560 blocks of its center |
| The world's own galaxy | The system of sector (0, 0, 0), centered on (0, 0, 0): `galaxy.json` as today. Worlds of before carry over unchanged |
| Where things are | `UPos`: a long cell per axis (2^16 units, 819.2 blocks) plus a double offset. No edge, no far lands, exact between near points at any distance |
| Floating origin | A cell corner. Inside a system: the system's center (set once, on the way in). In the void between systems: follows Mario every 4 cells (3277 blocks) |
| When it moves | Only "in the clear" (no planet collision near Mario, no landing): a cheap move. Forced past 16 cells (13,107 blocks) |
| Moves are exact | By whole cells of 2^16 units: a float moved toward 0 by them keeps every bit, so nothing jumps |
| Travel | Elytra inside a system (today); pulse in the void (fast, drops near gravity); warp to another system (the "entering a world" zoom) |
| Far systems | One star each, drawn as a point on the sky (one display list for all), by direction only: independent of the origin |

## 4. Design

### 4.1 Addresses: `universe/UPos` (built)

- `UPos(cx, cy, cz, x, y, z)`: cell longs + offsets in [0, 65536) units. `of`, `plus`, `minus`
  (cells subtract as longs first, so `minus` of two near points is exact at 10^17 blocks).
- Catalog entries keep their `double` centers, **relative to their system's center**; a planet's
  universe position is `star.center().plus(entry.center())`.
- A planet's identity: `(sector, index)`. Home's planets keep their files
  (`<stage>.p<n>.gxplanet`); a generated system's planet file is
  `<stage>.s<sx>_<sy>_<sz>.p<n>.gxplanet` (signed decimals), written only once it is changed.

### 4.2 The universe: `universe/Universe` (built)

- `star(sector)`: from `hash(seed, sx, sy, sz)` (SplitMix64, integers only): whether it holds a
  system, its center (sector center ± 1280 blocks per axis), planet count 2..12, min/max radius,
  spacing, the planets' seed. A few microseconds; no allocation besides the record.
- `around(p, r)`: the stars of the (2r + 1)^3 sectors around p, nearest first. r = 8 (4913
  sectors, ~2950 stars, ±70,000 blocks) takes **3 ms**; it runs only when the player changes sector.
- `systemAt(p, margin)`: the system p is in. Only p's sector can hold it (systems stay inside
  their sectors), so it is one hash.
- `system(star, land)`: its planets, by `GalaxyCatalog.make` with the star's options; planets
  reaching past 2560 blocks are left out (7 planets on average). **0.012 ms** each; the last 32
  are kept.
- Guarantees (unit tested): same seed, same universe; home always at (0, 0, 0); ~60% density; two
  systems' reaches ≥ 512 blocks apart; every system inside its sector; a trillion sectors out
  works as well as here.

### 4.3 The floating origin: `universe/Origin`, `universe/OriginPolicy` (built)

- `Origin`: the origin's cell, an `epoch` that counts moves, and the last 16 moves.
  `local(UPos)` → game units; `universe(local)` → `UPos`; `moveTo(at)` → a `Shift(epoch, dx, dy,
  dz)` in cells (Mario ends within half a cell, ±410 blocks, of the origin);
  `local(position, epoch)` converts a position the game reported in an older epoch.
- `OriginPolicy.target(origin, mario, system, clear)`:
  - in a system (within its reach + 256 blocks): its center, if the origin is not there yet;
  - in the void: Mario, once he is 4 cells from the origin;
  - only when `clear` (Mario past `DETAIL_OUT` of every planet's gravity, no teleport or landing
    under way), unless he is 16 cells out.
- Inside a system the origin never moves, and nothing in it is farther than ~3000 blocks from it:
  every float keeps 1/64 unit or better, as in today's galaxies.
- Why exact: a float `x` is a multiple of its last bit, which divides 2^16 for |x| < 2^40; the
  result of moving it toward 0 is smaller and needs no finer bit. Tested on 100,000 random
  positions.

### 4.4 A move in the game (module, protocol, Dolphin): to build on the PC

One new message, applied at the start of the module's inbox batch, before any record of it:

```c
GXC_MSG_ORIGIN = 116, /* M -> S: u32 epoch, i32 shift[3] (cells of 65536 units), big-endian:
                         everything in the game moves by -shift * 65536 */
```

- **Protocol**: `GxcMailbox.origin_epoch` (the epoch of `anchor_pos`, `cam_pos` and the gravity
  query), forwarded to `WorldState` by the host. Version bump.
- **Dolphin**: the host forwards `GXC_MSG_ORIGIN` to the inbox (its whitelist,
  `HostBridge.cpp:346`) and copies `origin_epoch`. Needs a Dolphin rebuild.
- **Module** (`VoxelPlanet.cpp`), in one frame, `d = -shift * 65536`:
  1. every `Planet.center += d`; its gravity's `mLocalPos` (+ `updateIdentityMtx`);
     `mTranslation`. Meshes, far views, outlines and cracks are relative to the center: untouched.
  2. every chunk's collision part: `resetAllMtx` with its base matrix's translation `+= d` (it
     resets the previous matrix too, so the part is not taken as a floor moving 400 blocks in a
     frame; §2b). Fallback if that misbehaves: the policy's `clear` also releases the nearest
     planet's chunks in the void (today it keeps them always), so there are no parts to move.
  3. Mario: `MR::setPlayerPos(pos + d)` (as `Teleport` does) keeping his velocity; seated (elytra,
     the usual case out there) he follows the next `SEAT`. **Spike**: velocity and SMG2's
     "previous position" fields (a fall-too-far check must not see a 400-block jump).
  4. Camera: in GalaxyCraft's first and third person the view is built from Mario's feet and the
     mod's look/offset: nothing to do. SMG2's own camera (F5): `MR::resetCameraMan` (a snap, in
     empty space where nothing shows it).
  7. The module's own matrices (planets, far views, outline, crack): computed camera-relative in
     double (`center - eye`, then float), so they stay steady at any distance from the origin.
  5. `pending teleport` and `gLanding.to` += d; drawn entities need nothing (re-sent each frame
     in the new epoch); held items are Mario-relative.
  6. Echo the new epoch in the mailbox from this frame on.
- Cost: ≤ 96 planet records and their parts, once every 3277 blocks of void flight (or once per
  system entered). Nothing per frame; no mesh is re-sent.

### 4.5 A move in the mod

- `PlanetClient` holds `Origin` and the current `Universe`. Every galaxy-unit position it sends or
  keeps (sessions' centers, far records, the wind's bodies, flight state, entities) is
  `origin.local(universe position)`; on a move each is converted once (subtract `shift.units()`).
- `GravityFrame`: `t += r · (SCALE · shift.units())`. The player's Minecraft position does not
  change, so nothing in Minecraft notices.
- What the game reports (`anchor_pos`, `query_pos`, camera) goes through
  `origin.local(pos, origin_epoch)` before use; a `null` (too old) is a frame without a reading,
  as an unanchored frame is today.
- Also: re-base Minecraft's x/z as `GravityFrame.rebase` already does y (keep the player within
  ±4096 of 0), so a long trip does not fill the void overworld's region files or reach the border.
  To check first: drops and anything else that lives near the player in the overworld.

### 4.6 What is drawn: tiers

| Tier | Which | Cost |
|---|---|---|
| Detail | as today (nearest + within 64 blocks of gravity) | chunks, KCL |
| Near | the 8 nearest planets **of the current and neighboring systems** | gravity + far view |
| Far | the other planets of the current system (≤ 64) and of a neighbor's within 6000 blocks | far view, cached per (planet, level) |
| **Star** (new) | every other system within 8 sectors (~3000) | **one** display list: a point (`GX_POINTS`) or tiny quad per star, colored by its first planet's biome, sized by its planet count |
| Sky | SMG2's skybox | free |

- `PlanetLayout.ranked` and the Near/Far choice run over the current system's entries plus its
  neighbors' (27 sectors at most, almost always 1–2 systems), never the whole universe.
- Stars are sent by **direction and angular size** from Mario (a new `GXC_MSG_STARS`: u32 count,
  then per star f32 dir[3], f32 size, u32 rgba), drawn at a fixed distance around the camera like
  the sky, so they never meet the far plane and do not care about the origin. Re-sent when Mario
  changes sector or has moved 1/10 of the nearest star's distance (parallax).
- When a system comes within ~6000 blocks, its planets enter Far at the coarsest level (sampled
  from the generator, as unvisited planets are today) and its star is dropped in the same batch.

### 4.7 Travel

| Where | How | Speed |
|---|---|---|
| Inside a system | Elytra and rockets (today) | ~30 blocks/s |
| Void between planets/systems | **Pulse**: with elytra open in the void, holding sprint (or a key) ramps up to ~400 blocks/s; it drops out near any gravity (2× its reach) as NMS's pulse does. The cosmic wind is off while pulsing | 8192-block sector in ~20 s |
| Between systems | **Warp**: aim at a star (or pick it on a map screen) and confirm: the screen fades into the "entering a world" zoom (IntroCamera), planets are dropped, the origin moves to the target system's center, its planets stream in, Mario arrives in space just outside the first planet's gravity | a few seconds, any distance |

- The pulse's top speed is bounded by streaming: far views of a system must be ready before its
  planets come into gravity. At 400 blocks/s that is ~15 s of warning from 6000 blocks: plenty
  for coarse far views (they are generator samples), and Near prefetch starts at 9th/10th nearest
  as today.
- The **cosmic wind** pulls to the nearest planet past 200 blocks out. With endless systems it
  pulls only when the player is stranded (no elytra) and within a system's reach; beyond it, a
  stranded player is pulled toward the nearest system's center (the first `around` finds),
  never left floating.
- Warp's cost: the same as entering a world, which already works (drop all, stream, land).

### 4.8 Saving

- `galaxy.json`: unchanged (home's catalog) plus `"universe": {"version": 1}` once a world has
  been out of its home system (the generator is versioned: changing its numbers would move
  everyone's systems, so a new generator is a new version, old worlds keep theirs).
- `player.json`: `{"sector": [x, y, z], "planet": n, "pos": [cx, cy, cz, x, y, z]}`. Old files:
  sector 0.
- `galaxycraft/sectors/<sx>_<sy>_<sz>.json`: only for systems the player changed (a planet added
  or replaced in the editor): entries that override or extend the generated ones. Nothing for a
  system only visited, and a visited planet is saved only once changed, as today.
- **Shadow dimension**: `ShadowMap` gives each planet a strip of z by a hash of its key in 4096
  slots. With endless planets two will share a slot; only one planet is attached at a time and
  its columns are mirrored on attach, but to check: that mirroring overwrites every cell (air
  included), or allocate slots from an LRU table saved with the world instead of the hash.

## 5. Efficiency

- Nothing per frame: the origin moves at most once per 3277 blocks of void flight, in one frame,
  with no mesh re-sent (meshes and KCL are already relative to their planet's center).
- Universe queries are integer hashes: ~0.6 µs per sector, 3 ms for 4913 sectors, only on a
  sector change. A system's planets: 0.012 ms, cached.
- In the game at once: as today (≤ 8 complete planets, ≤ 64 far views), plus one star display
  list (~3000 points, ~60 KB) and one draw call for it.
- Disk: nothing for sectors nobody changed.
- Memory in the mod: 32 cached systems (a few KB); far meshes keep their per-(planet, level)
  cache, which needs an LRU cap now that the planets are endless.

## 6. Order of work

1. **Core** (done on this branch): `UPos`, `Origin`, `OriginPolicy`, `Universe`, 20 unit tests.
2. **Spike on the PC** (decides §4.4's fallbacks): with `/galaxycraft` debug commands, shift
   everything by one cell in the module while standing on a planet, flying, and with SMG2's
   camera: does Mario keep his speed, do collision parts follow, does the camera snap?
3. **Floating origin end to end** in the home galaxy: `GXC_MSG_ORIGIN`, `origin_epoch`, the
   module's move, the mod's conversion; a debug command `/galaxycraft origin <x> <y> <z>` that
   moves the whole home galaxy a million blocks out. Test: `OriginProbe` (§7). This alone fixes
   precision, whatever comes after.
4. **Endless systems**: `PlanetClient` streams the current and neighboring systems from
   `Universe`; saving (§4.8).
5. **Stars**: `GXC_MSG_STARS` and its display list.
6. **Travel**: pulse, then warp (with its map screen).
7. Docs (`docs/UNIVERSO.md`, in Spanish), roadmap, the public roadmap.

## 7. Testing

- **Unit** (built): `OriginTest`, `OriginPolicyTest`, `UniverseTest`. Still to write with the
  game side: the mod's conversion of every kept position on a move; `GravityFrame` after a move
  maps the player to the same Minecraft position; stars' directions.
- **End to end** (`OriginProbe`, `tools/gxvoxel.sh origin`):
  1. Move the home galaxy 1,000,000 blocks out *without* the floating origin: screenshot shows the
     shake (`max |Δ| of Mario's position while standing still` > 0.5 units). With it: < 0.01.
  2. Fly with elytra across a move: Mario's position, as the mod sees it in the universe, has no
     step larger than one tick's movement; `VoxelStats` counts the move.
  3. Stand on a planet, force a move (past `MUST_AT`): Mario stays standing, collision works,
     a block can be broken and placed.
  4. Warp to a system 50 sectors out, land, break a block, warp home and back: the block is
     still broken; the file is `<stage>.s50_0_0.p<n>.gxplanet`.

## 8. Open questions for you

1. **Density and distances**: 8192-block sectors, 60% with a system, 2..12 planets each. More
   crowded (short pulses) or emptier (warp is the only way)?
2. **Warp**: free, or does it cost something (a crafted item, fuel like NMS's warp cells, or an
   SMG2 Launch Star you have to find)?
3. **The home galaxy**: should Create World's options also set the other systems' sizes and
   counts, or are those always random?
4. **Stars on the sky**: points of light only, or a galaxy map screen (Minecraft screen) to pick
   warp targets too?
