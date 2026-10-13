# Launch Stars between planets — design

Date: 2026-10-12 · Branch: `feat/launch-stars` (from master 1f631fb) · Status: **draft for the user's review; nothing is built**

The user's idea (ROADMAP Inbox, 2026-10-12): the stars of Super Mario Galaxy that you spin around to be flung through
space. Two planets the player has already been to are linked with such stars (there and back); a simple system to fly
to the other planet; the course must bend around planets in the way so it looks good.

## 1. What I found (research)

### 1.1 The game's own Launch Stars

- The disc has two star actors with the right look: `ObjectData/SpinDriver.arc` (540 triangles, one texture) and
  `ObjectData/SuperSpinDriver.arc` (the Sling/Launch Star, 542 triangles, 4 textures, colours Yellow/Green/Pink as
  `.brk`, animations Wait/Start/Appear/NonActive/Freeze). `SpinDriverPath.arc` holds the dotted-path textures.
- In stage data (`SuperSpinDriver`: 115 rows in the base game; `SpinDriver`: 44) each star is an `ObjInfo` row with
  `CommonPath_ID` pointing at a **rail** (`Path/CommonPathInfo` + `CommonPathPointInfo.<row>`): a Bezier rail with
  in/out handles per point, `Obj_arg0` = speed-like number (300), `Obj_arg3` = 3. So a game star flies Mario along a
  *rail fixed in the stage file*.
- The module links to the game through `syati/symbols/SB4E.txt`, which only names a few star helpers
  (`registerStorageSpinDriverPathDrawRange`, `endBindAndSpinDriverJump`, `isCurrentRushSpinDriver`, the launcher
  camera). The actor classes themselves are not in the symbol map.

### 1.2 What the module can place at runtime (from the notes)

- `smg2-mobs-spike`: objects placed in `GalaxyCraftSpace`'s `ObjInfo` at build time (`tools/space_galaxy.py` empties
  the table today) come alive; a **puppet** `ModelObj` (`MR::createModelObjEnemy` + `invalidateClipping`, matrix set each
  frame) draws a real SMG2 model with real `.bck` animations beside Steve, also on a voxel planet.
- `game-drawn-entities` / toad ship: any `.bdl` can be read in Java (`BdlReader`) and sent as rigid pieces
  (`GXC_MSG_MODEL` once, `GXC_MSG_ENTITIES` per frame; 2048 models, 256 skins, 1536 pieces a frame). The Starshroom and
  the landing pads are drawn this way (`ShipClient.pieces`).
- `module-heap`: runtime allocation must go through the module's own heap; irrelevant here, no module allocation is
  added.

### 1.3 Spike result (cheap, done now, throwaway)

Ran the repo's `BdlReader` + `Rarc` on the disc's arcs with a 20-line `javac` harness (no game, no Dolphin):

| Arc | Result |
| --- | --- |
| `SpinDriver.bdl` | reads: 1 shape, 540 tris, 1 texture, ≈ 305 × 13 × 292 model units |
| `SuperSpinDriver.bdl` | reads: 2 shapes, 542 tris, 4 textures, ≈ 482 × 34 × 462 model units |
| `SuperSpinDriverShadow.bdl` | fails (`J3D model without attribute 13`): not needed |

So the star can be drawn exactly like the Starshroom with **no C++ change**.

### 1.4 What can't be done cheaply: a native game star with a runtime route

A game `SuperSpinDriver` reads its rail from the stage's path tables when it is created. Rails are not data the module
can add at runtime; a pool of pre-placed stars would need every route known when `GalaxyCraftSpace` is built, but our
routes depend on where the player puts the stars. Building a fake `RailRider`/`JMapInfoIter` in game memory means
reverse-engineering classes the symbol map doesn't name. Not worth it for v1 (see alternative A in §6).

## 2. Decision in one paragraph

**Java is the authority; the game only draws.** A Launch Star is a placed object (stored per world, drawn as the
game's `SuperSpinDriver` model through the existing entity pieces). Stars are crafted and placed **as a pair**: the two
halves know each other, so placing the second one on another planet links the two. The player holds *use* on a star
to spin it up and is flung along a smooth curve that bends around every planet in the way, as a pure function of
the game tick (the same idea as the 3D map's `Trip`/`Course`), and lands on the star at the other end.

## 3. Player experience

1. **Get a pair.** Craft "Launch Star ×2" (first-draft recipe: 1 nether star + 4 gold ingots + 4 amethyst shards; shapeless
   output of two items with the same hidden pair id). Both halves show "Launch Star — linked, A/B".
2. **Place.** Right click on the ground of a planet or station: the star stands upright on the surface (`planet.up`,
   same placement code as the landing pad). The second half is placed on another planet (carry it there by ship,
   elytra or warp). Placing the second half links them; a gold sparkle and a chime say so.
   Rules: both ends must be on **different bodies**; a star can't be placed in the void.
3. **See.** The star slowly turns and, when the player is within about 48 blocks, a line of small glowing dots shows
   the whole route to the other planet (the game's way of showing the course). An unlinked star (partner broken or
   not placed yet) is dim and gives no route.
4. **Launch.** Look at the star within 4 blocks and hold *use*: the star spins faster for 0.75 s (the Wii "spin"
   of the original; the player is pulled to the star's centre and stands on it), then flings. Releasing early
   cancels.
5. **Fly.** Third-person camera behind the player, Mario in the SpaceFlyLoop pose (the elytra seat, `riding=2`). The
   star's route accelerates over the first second, cruises, and decelerates over the last second. Player inputs are
   ignored except **Shift/jump = cancel and glide** (falls back to the normal elytra/void state at that point).
6. **Land.** The partner star pulls the player in and sets them down on top of it, facing across the planet's surface;
   gravity of the destination planet takes over like any landing from space.
7. **Break.** Mining a star (creative: instantly; survival: a few hits, drops the item) removes it; its partner turns
   dim and keeps its pair id, so crafting is not needed again: placing the replacement half relinks.

Numbers (all first drafts, user decides): reach 4 blocks; cruise speed 8 blocks/tick (160 blocks/s); trip time
clamped to 6 s – 60 s; spin-up 15 ticks.

## 4. How it works

### 4.1 Pieces (new code, all Java; no protocol or C++ change)

| Unit | Does | Depends on |
| --- | --- | --- |
| `launchstar/StarRecord` (+ `StarStore`) | id, pair id, half (A/B), stage, `UPos` position, `up` vector, body key; saved in the galaxy's JSON next to `ships.json` (same pattern as `ShipStore`; damaged file loses stars, not the world) | `UPos` |
| `launchstar/LaunchStarItem`, `LaunchStarBlocks` registration | the item (pair id in `CUSTOM_DATA`), the recipe, the break/drop; `use` decided on the client like `LandingPadItem`/`ShipHooks` | `ship/*` as pattern |
| `launchstar/Route` (pure) | `Route.plan(from, fromUp, to, toUp, obstacles) → Route` (§4.3); `position(s)`, `tangent(s)`, `length()` | `UPos`, `Vector3d`; no Minecraft types, unit tested |
| `launchstar/Flight` (pure) | tick → arc length with ease in/out, `FlightState` (WINDUP, FLYING, LANDING); `positionAt(tick)` | `Route` |
| `launchstar/Obstacles` | builds the list of spheres (centre, radius) near a segment from `Universe` + `PlanetClient.bodies()` | `Universe`, `GalaxyCatalog` |
| `client/LaunchStarClient` | draws stars + route dots (`pieces(ec, pt, out)` hooked beside `ShipClient.pieces`), handles hold-use, drives the player along the route with `GravityFrame.slide` | `BdlReader`, `EntityWire`, `ShipClient` patterns |

`BdlReader` and `Rarc` live under `ship/model`; the star models move to a neutral `model/` package in a first
refactor commit (rename only), or are used from where they are if the toad-ship session objects.

### 4.2 Drawing

- The launcher already copies `UFOKinoko.arc` from the player's disc to `<data>/ship`; it copies
  `SuperSpinDriver.arc` (and `SpinDriverPath.arc`, optional) the same way. No game file enters the repo.
- The star is two rigid pieces (its two shapes) with their skins; Java rotates the piece matrix around the star's axis
  (spin; faster during wind-up). Colour: yellow base (the `.brk` colour animation is not applied; v1 tints skins
  like the ship's red). The route dots are one small sprite model (a flat 4-point star, ≈ 8 px) repeated, up to
  200 per route, only for the nearest star and only within 48 blocks (budget: 1536 pieces/frame).
- Shown only in the stage (`GalaxyCraftSpace`) and at the right floating-origin offset through `GameOrigin`, like ships.

### 4.3 The route (bends around planets)

Input: start star (`A`, `upA`), end star (`B`, `upB`), universe layout. All in universe units (`UPos`), solved in a
frame centred on `A` so doubles stay exact at any distance.

1. **Obstacles.** Spheres `(centre, R)` with `R = gravityRadius(planetRadius) + 48 blocks` for every planet whose system
   is within `reach + 512 blocks` of the straight segment `A→B`: both systems' planets plus any system in between
   (`Universe.around()` over the sectors the segment crosses; ≤ ~20 stars for the longest planned trip), using
   `Universe.system()` / the home catalog. Stations are spheres with their gravity radius.
2. **Ends leave their own planet.** The curve starts at `A` with tangent `+upA` and ends at `B` with tangent `−upB`
   (arrives from above), both with handle length `0.25 × chord` (min 200 blocks): it climbs out and drops in like the
   game's launches, and the planets it starts and ends on are not treated as obstacles beyond their surface.
3. **Detours.** Walk the chord in 64 steps. For the first obstacle sphere the curve enters, insert a waypoint
   on the sphere's surface, pushed out from the closest point on the chord along the direction away from the
   sphere's centre (if the chord passes through the centre: the same side for the whole trip, `upA × chord`,
   so the trip never zigzags). Rebuild a **centripetal Catmull-Rom** spline through `A, w1, …, wk, B` (end tangents
   from step 2), re-test, repeat at most 8 times.
4. **Check.** Sample 256 points on the final spline; if any is inside an obstacle (rare: dense systems with the
   endpoint inside another's reach), raise the waypoint altitude by 25 % and retry; at last fall back to the
   path with a single wide detour above the system plane (documented as the failure branch, covered by a test).
5. **Arc length.** An arc-length table (512 samples) makes position a function of distance, so the speed profile is
   independent of the spline's parameter.

`Route.plan` is deterministic from the inputs. The route of a pair is computed when a flight starts and recomputed
(not stored) after loading, so it follows planets if the world's layout changes.

### 4.4 Flying the player

Same method as the 3D map's autopilot and the pulse: the Minecraft player stays at the same spot and
`GravityFrame.slide(Δ)` moves the galaxy under them (no server "moved too quickly", no void chunks at any speed or
distance). Inputs: the position is `Route.positionAt(Flight.arc(tick))`, tick being the world's game time, so a
frame-rate drop or a pause can't desync it. Mario is seated flying (`GXC_MSG_SEAT`, `riding=2`); the abyss-kill patch
already covers him in empty space (`elytra`). The player's *up* eases to the route's tangent-perpendicular over the
first second, and to `upB` over the last (the `UpTurn` that stations and planets already use).

Crossing systems: the pulse is capped at 0 when `Warp.here()` is empty (`warp` rules: the pulse stays inside a
system). A Launch Star is **not** the pulse: it moves the frame itself, so it needs no change to those rules, and
it is the intended way to cross to *visited* places without a Warp Core (see §5).

Landing in another system: at 85 % of the trip `LaunchStarClient` asks `PlanetClient.travelTo(B's planet)` with the
fade of `EnteringScreen` (the Warp path), so B's planet is streamed in before the star arrives; if the planet is in the
same system the normal far-planet streaming (`far-planets`) is enough and no fade is used.

### 4.5 Visited planets

A pair is carried by the player, so "both ends are planets I have been to" holds by construction: you can only place a
half where you stand. No new data. Planet-progression's `Visited` (per system, plan 2) is **not** required; if it is
merged first, nothing changes. The map (3D map) can show linked stars as lines in Phase 2 (§7).

## 5. Interactions with the other work

- **Planet progression / Warp Core**: the gate "a Warp Core limits new systems, visited ones are free" is kept intact:
  a Launch Star can only connect two places the player already physically reached.
- **3D map**: shows the stars as links; the route preview uses the same `Route.plan` (Phase 2).
- **Toad ship** (`ship/*`, other session): only uses the pad *pattern* (record + store + hook); no change in their code.
- **Warp rules** (warp only from the map, creative/cheats only): Launch Stars are not a warp; they stay legal in survival.
- **Flat stations**: allowed as endpoints (`up` from the station frame, same as pads).

## 6. Alternatives considered

| | What | Why not (for now) |
| --- | --- | --- |
| A | Game-native stars: rows in `GalaxyCraftSpace` plus rails, SMG2's own spin/launch/flight/camera | A route per pair must exist when the stage is built; no way to add rails at runtime without RE of `RailRider`; Mario's launch needs his own state machine tied to a stage rail. Best look, worst flexibility. Revisit if the Java flight feels wrong. |
| B | Puppet star with real animations (`createModelObjEnemy` like the Goomba, `Wait`/`Start` `.bck`) | Needs a C++ puppet kind and per-star lifetime; gives animated look. Good **polish** step after v1. |
| C | Link by hand (select star 1, then star 2) instead of crafting a pair | More UI, more states; pair crafting is the same result with none. |
| D | Pick the destination from a list of visited planets (map screen) with the second star auto-placed | The far side is not loaded or has no obvious free spot; auto-placing on an unloaded procedural planet is fragile. Good fit for Phase 2 once the 3D map and `Visited` are merged. |
| E | Straight line (no bending) | Flies through planets: the user asked for a bent course explicitly. |

## 7. Plan outline (scope for the implementation plan)

Phase 1 (this spec): pair item and store, draw (star + dots), hold-to-launch, route + flight, landing, tests.
Phase 2 (later, own spec if big): map links, a game SE message (`SE_PM_LV_MARIO_LAUNCHER_FLY` is a real name in the
module; names of the star's own SEs unverified), real animated puppet (B), colour variants for different pairs.

Tasks (each testable alone): (1) `Route` + `Obstacles` pure + unit tests; (2) `Flight` timing pure + tests;
(3) `StarRecord`/`StarStore` + tests; (4) item, recipe, pair id, placement hook; (5) model loading + pieces + dots;
(6) client: hold-use, drive the frame, cancel; (7) landing and cross-system streaming; (8) a Dolphin probe
`tools/gxvoxel.sh launchstar` (two stars on two planets of the home catalog, launch, assert the player lands within
2 blocks of B and no sample of the flight was inside an obstacle).

## 8. Testing

- Unit (no Minecraft): route never enters an obstacle for 10 000 random pairs/seeds in `Universe`; ends tangent to ±up;
  deterministic; arc length monotonic; `Flight.positionAt` continuous at the phase changes; failure branch.
- Store round trip, damaged file, broken half → partner unlinked, relink on replace.
- Probe in game (needs Dolphin; run only when no `gxplay`/Dolphin from another session is up — `pgrep -af 'gxplay|dolphin'`;
  none was running on 2026-10-12 when this was written): place both halves, hold use, screenshot mid-flight, assert the end
  position and that the player is upright on B.
- Playtest by the user before any merge (`merge-after-playtest`).

## 9. Risks and open points

- **Fast floating-origin moves** while flying across several systems: the pulse already slides the frame at 10–20
  blocks/tick; 8 blocks/tick is slower. The cross-system fade (§4.4) is the part with no precedent in a flight; first
  thing to prove in the probe.
- **Streaming along the route**: planets near the route appear as far views; if the game's far-planet LOD can't keep up,
  lower the speed or fade the route's middle.
- **Obstacle cost** for very long trips (many systems): bounded by the sectors the segment crosses; cached per pair.
- **Look of the model**: skins are tinted like the ship's red; the original colours may need the TEV/brk treatment
  (same problem the ship's cap had).
- **Item design**: recipe, durability, whether survival needs a tier of ship/Warp Core to craft — undecided, easy to
  change; I chose cheap and visible.

## 10. Questions for the user (none blocks the plan)

1. Is "craft a pair, carry the second half" the right way to link? (Alternative D picks from a list later.)
2. Should the trip be possible in survival from day one? (I assumed yes.)
3. How fast should it feel: ~6 s inside a system and up to a minute between systems?
