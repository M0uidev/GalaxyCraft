# Elytra: gliding, leaving a planet, flying to others

2026-10-05. Status: design, awaiting review.

## Goal

Minecraft's elytra work in the galaxy: glide off a cliff, rocket off a planet, cross the void
and land standing on another planet of the same stage. They work the same in all three movement
modes (Mario, Minecraft, Mario at Minecraft's speeds).

Out of scope: travel to another SMG2 stage/galaxy; automatic solar-system generation; new
planet placement rules (PlanetLayout's rings already keep gravity fields apart).

## Decisions (from the brainstorm)

| Question | Choice |
|---|---|
| Gravity in space | Mario Galaxy style: inside a planet's field it pulls; outside every field, none (momentum kept); rockets to escape |
| Items | Vanilla: elytra in the chest slot, Space in the air opens them, firework rocket in hand boosts; durability and rockets are spent in survival |
| Up while flying | Follows the pulling planet's gravity; frozen in the void; turns smoothly (1–2 s for a full flip) on entering another field; you land standing |
| Mario mode | Mario is a puppet that follows the player in SMG2's flight pose (Launch Star); no elytra drawn on him |
| Too far away | Never die from it: past a radius around the planets, a soft "cosmic wind" slows the player and pushes them back |
| Other planets | The existing `/galaxycraft planet add` + PlanetLayout rings |

## Approach

Vanilla elytra inside `GravityFrame`. The frame already rotates the world each tick so the
galaxy's gravity points to Minecraft's -Y and Minecraft physics runs unchanged, so vanilla
`fallFlying` (glide, rockets, durability, sound, camera) works around any planet. We only add
what vanilla cannot know: the void, the slow turn between fields, the wind, and Mario.

Rejected: our own flight integrator in galaxy space (rewrites what Minecraft does well, feels
less like Minecraft); native SMG2 flight (deep reverse engineering, fragile).

## Design

### 1. States and flow (`GalaxyCraftClient`)

- **Take-off:** `player.isFallFlying()` becomes true by vanilla rules. While it is true the
  player moves by Minecraft's physics in every mode (as `walking()` does), whatever F6 says.
  In Mario modes, Mario becomes a puppet (section 2).
- **Each flight tick**, with `g = world.gravity()` (SMG2's gravity at Mario, who is where the
  player is):
  - **In a field** (`hasGravity()`): `up = limitTurn(frame.upGal(), -g, FLIGHT_TURN_PER_TICK)`,
    `frame.update(-up, pos)`. `FLIGHT_TURN_PER_TICK` ≈ π / 30 (a flip in 1.5 s), slower than
    walking's `MAX_TURN_PER_TICK`.
  - **In the void**: `player.setNoGravity(true)`, `frame.update(-frame.upGal(), pos)` (up
    frozen). Vanilla's elytra drag still applies, so a glide slowly loses speed; rockets push
    as always.
  - Leaving the void restores `setNoGravity(false)`.
- **Landing:** vanilla closes the elytra on touching ground. The selected mode takes over
  again: Minecraft keeps walking; Mario modes let Mario go at the player's feet with the
  existing `WALK_LIFT` and `SETTLE_TICKS` handling (as `moveTo` does).
- **Planets:** `PlanetClient.focus` is already the nearest planet and its chunks stream in
  within 64 blocks of its gravity, so nothing changes there.
- **Void without elytra** (fell off with no elytra, or they broke): same rules, no gravity and
  the wind; the player drifts back toward the planets and can rocket only with elytra open.
  `/galaxycraft planet tp` remains the way out.

### 2. Mario during flight

- Mario's position follows the player through the same channel Minecraft movement uses
  today ("Mario goes where it is").
- A new mailbox flag `GXC_MBX_MC_FLY` (next free bit; check the protocol header) tells the
  module that Mario is airborne by our hand: it keeps his flight pose (the Launch Star flight
  animation) and stops SMG2 from treating the missing ground/gravity as a fall or death.
- **Risk, checked first (spike):** whether the flight animation can be forced from the module,
  and whether SMG2 kills or resets a Mario with no gravity around him. Fallback pose: SMG2's
  falling pose. Fallback for the void: keep Mario at the last point that had gravity and draw
  only the player there (Minecraft's own third-person Steve), as `/fly` does today.

### 3. Cosmic wind

- `PlanetWind` (pure, in `gravity/`): given the player's galaxy position and the planets'
  spheres (center, gravity reach), returns an acceleration.
- Zero inside `max over planets (distance to center ≤ reach + WIND_FREE)`, `WIND_FREE` = 200
  blocks past the farthest field. Beyond, a pull toward the nearest planet's center that grows
  smoothly to 0.08 blocks/tick² over 100 blocks, plus a drag of 5 % per tick on the outward
  component. Nobody dies or gets lost; rockets can still fight it briefly.
- Applied in the frame (galaxy vector → Minecraft space via the frame's rotation) to
  `deltaMovement` each tick, flying or not.

### 4. Existing `/fly`

Unchanged (creative free flight, galaxy +Y up). If the elytra open while `/fly` is on, `/fly`
wins (vanilla does the same with creative flight).

## Testing

- **Unit (JUnit, fast):** `PlanetWindTest` (zero inside, grows outward, points to nearest
  planet, never pushes outward); a `GravityFrame` test that a 180° change of gravity takes
  about 30 flight ticks and ends aligned.
- **Game test `ElytraProbe`** (`tools/gxvoxel.sh elytra`), on a stage with two planets made
  by `planet add`:
  1. Minecraft mode: give elytra + rockets, jump, open, rocket outward; check the player
     leaves the first field, drifts with no gravity in the void, enters the second field, up
     turns to it, and lands standing on it (feet at its surface ± 1 block, up within 10°).
  2. Mario mode: the same; check Mario is near the player all flight, alive (no death/reset in
     Dolphin's status), and walking under his own physics after landing.
  3. Wind: TP 400 blocks past every field; check the player comes back within 60 s.
  4. Survival: a rocket is spent and the elytra lose durability.
- Run the existing suites + `MovementProbe` (nothing regresses on the ground).

## Order of work

1. Spike: SMG2 Mario with no gravity and the forced flight pose (decides section 2 or its
   fallback).
2. Flight in the frame (section 1) in Minecraft mode + unit tests.
3. `PlanetWind`.
4. Mario modes + the mailbox flag + module pose.
5. `ElytraProbe`, docs (`docs/ELITROS.md`, in Spanish like the other user docs), memory.
