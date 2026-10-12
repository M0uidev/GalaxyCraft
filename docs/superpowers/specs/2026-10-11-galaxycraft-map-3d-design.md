# 3D map: see the universe, send the ship, leave it orbiting — design

Date: 2026-10-11. Branch `feat/map-3d`. Status: design approved in conversation, awaiting review of this spec.

## Goal

Replace the galaxy map's list of buttons (`GalaxyMapScreen`, keys M / N / K) with a 3D map: see the
systems and their planets, pick one, send the ship there by remote and leave it **orbiting**. The
pilot may be aboard while it is sent. Each system shows its **tier** and **star type**.

Success: from the map, a parked ship is sent to a planet, travels there over time (visible on the
map, and from the seat when aboard) and ends orbiting it; the map never needs a loaded planet.

## Decisions (user, 2026-10-11)

- A real simulated trip (takes time by the ship's speed), not an instant move.
- The pilot may be aboard when sending: the ship flies itself, the seat follows.
- Mod or own screen: **own Minecraft screen** (evaluated below).
- Tier and star come from `SystemIndex` when the progression rework lands; by distance meanwhile.

## Mod or own screen

- **Third-party map mod**: rejected. None understands a floating origin, `long` sectors, spherical
  planets or a client drawn through Dolphin; each would need adapting, plus a dependency to ship.
- **Own `Screen` in Minecraft** (chosen): the overlay already reaches Dolphin (`GalaxyMapScreen`
  exists), the projection is plain math under JUnit, and it reuses `Universe`, `Warp`,
  `PlanetClient`.
- **Drawn by the C++ module as an SMG2 scene**: prettier but changes the module, protocol and
  patches and cannot be tested without Dolphin. A later upgrade, not this feature.

## What exists (checked 2026-10-11)

- `Universe.Star` (sector, center `UPos`, seed, planets, radii, spacing) and `Universe.around(p, r)`.
  `SystemIndex` only maps `(sector, n)` to a planet index: **no tier or star yet** (the progression
  spec says it will).
- `GalaxyMapScreen`: the 10 nearest systems as buttons, each `Warp.to`. `Warp.map()` opens it.
- `ShipClient`: ships live in a `ShipStore` (`ShipRecord`: modules, `pos` and `rot` in galaxy units of
  a stage); `fly()` moves the boarded ship with controls and `placed()` writes the position back.

## Design

### Data (`dev.moui.galaxycraft.map`, no Minecraft types, JUnit)

- **`SystemInfo { int tier; StarType star }`**, from `SystemInfo.of(Universe.Star)`. Today: tier by
  distance to home, ring edges as in the progression spec (0 home and neighbors, 1 to ~20,000
  blocks, 2 ~40,000, 3 ~70,000, 4 ~110,000, 5 beyond), star yellow unless the system says otherwise.
  When `SystemIndex` carries real tier and star, only this implementation changes.
  Other sessions (Toad NPC) read tier and star only through it.
- **`Course { UPos from, UPos to; long startTick; double speed }`**: `position(t)` interpolates and
  ends at the capture point on the target's orbit.
- **`Orbit { planet; double radius; double phase; double rate }`**: `position(t)` a circle around the
  planet; radius = planet radius + margin; phase differs per ship on the same planet.
- **Ship state**: `Parked | EnRoute(Course) | Orbiting(Orbit)`, one method `positionAt(t)`.
  The position is a **pure function of time**: nothing is simulated, so an unattended ship, a ship
  with its pilot and a world just opened all agree. Clock: the world's `gameTime` (closed world,
  stopped clock).
- **`CourseStore`**: JSON file next to `ships.json`, keyed by ship id. A damaged file is dropped:
  ships stay parked.

### Screen (`client/MapScreen`, `map/MapView`)

- Two levels: **universe** (systems from `Universe.around`, coloured by star, size by planets, tier
  label, home marked) and **system** (planets at real relative positions, size by radius). Enter a
  system: double click or Enter; Esc or Backspace goes up.
- Orbit camera around a focus: left drag turns, wheel zooms, the focus jumps to the picked element.
- `MapView` is pure: world to screen, and ray to nearest element within ~8 px.
- Drawn with `GuiGraphicsExtractor` (rects, lines, text), depth sorted, faded with distance. No
  change to the module or the protocol.
- Ship markers: parked, en route (with the course line to the destination), orbiting (ring).
- Side panel: name, tier, star, planets, distance in blocks. Buttons: **Send ship** (a short list if
  several), **Warp** (existing, free; `Tier.reach` will limit it when the Warp Core lands), **Cancel
  course** (stops the ship where it is).
- M / N / K (with no star aimed) open it. Opens with the pilot aboard and a course running; markers
  move live.

### Remote control

1. Send: a `Course` from the ship's position now, `startTick = gameTime`, `speed =
   ShipStats.speed`. An existing course is replaced starting from where the ship is.
2. Arrival turns into `Orbit` with no event: `positionAt(t)` decides on every query.
3. Cancel leaves the ship parked at its position at that instant.
4. Aboard: the ship follows the course, the seat follows the ship. **W takes the controls**, which
   cancels the course. Getting off in orbit is allowed; the ship keeps orbiting.
5. Only non-pad ships of the current world can be sent. The destination is a `UPos` from the
   `Universe` hash, so a planet that was never generated is fine.

### Seam in `ship/*` (owned by the toad ship session: **proposed, not applied without them**)

Two call sites in `ShipClient`: where each ship is placed/drawn from `store.all()` and in `fly()`
(with a course, controls are ignored except W). Both ask `CourseStore.positionAt(id, t)` and fall
back to the record. `ShipRecord`, `ShipStore` and the JSON do not change.

## Risks

- **Units**: `ShipRecord.pos` is galaxy units of a stage, `Course` is universe units (`UPos`);
  they relate through `GameOrigin`. One conversion point, unit tested, verified before the plan.
- A ship's drawing exists only while its planet is in the loaded scene: an orbiting ship far away is
  shown on the map only.
- Tier by distance is a stand-in; the numbers are the progression spec's first draft.

## Out of scope

Landing from orbit (by hand or on a pad: roadmap), map inside the Ship Table, danger filters and
overlays (wait for real tiers), the map drawn by Dolphin, multiplayer, ship combat.

## Tests

- JUnit: `Course.position` (start, middle, arrival, hand-over to orbit), `Orbit` (constant radius,
  distinct phases), `MapView` (projection, ray pick), `SystemInfo` rings and home tier 0,
  `CourseStore` round trip and damaged file, galaxy/universe conversion.
- Game probe (`gametest`, empty space, never alongside gxplay): send a parked ship, it moves and
  ends in orbit; aboard, the seat follows and W cancels.
- A Dolphin capture of the map only if the user wants it.
