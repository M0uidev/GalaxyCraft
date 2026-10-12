# Toad life: walking, a home with a door and a bed, villager-like habits — design

Date: 2026-10-12. Status: user feedback after playtesting (via orchestrator). Builds on
`2026-10-11-galaxycraft-toad-npcs-design.md` and `2026-10-12-galaxycraft-toad-dialogue-design.md`.

## Asked (user, 2026-10-12)

1. Enter is the default key to go on in the dialogue, and reaches the HUD in the real game (keys come
   through Dolphin); right click stays. **Done** in code (host keys read from the bridge, HID usages
   1-4, Enter, Up, Down, with Minecraft's own); to check in the real game.
2. Toads **walk**: idle wander with Kinopio's walk animation, turning, collision with the voxel
   terrain, the planet's gravity.
3. The hut gets **a real door and a bed**.
4. **Villager-like**: stays at or near its home (the bed), sleeps at night maybe, adapts to what the
   player builds (does not walk through placed walls, goes over or around them, does not get stuck);
   if the home is destroyed it stays near where it was.

## Facts

- Kinopio.arc has: Wait, Walk, Run, Turn, Sleep, Talk, Joy, Surprised, Call, Notice, Pointing,
  Reaction ... and the Pickel set (WaitPickel, WalkPickel, TurnPickel, TalkPickel). `BckReader`
  already plays any of them (ToadClient picks `look.idle()/talk()` names).
- A Toad is a `ToadRecord(id, type, pos[3], rot[4], stage)` in `toads.json`; drawn each frame
  (`ToadClient.pieces`); position is galaxy units, up is the planet's. No physics today.
- The voxel planet exposes ground queries (`PlanetSession.surfaceAlong(from, dir, reach)`) and cells
  (`planet.get(cell)`, block info: solid or not).

## Model

- `ToadWander` (pure, deterministic with an explicit seed + clock): a small state machine IDLE ->
  TURN -> WALK -> IDLE (-> SLEEP at night at home), with a **home** point and a **leash** (6 blocks;
  12 if the home is gone). It produces a wanted step on the ground plane (direction, speed 1.1
  blocks/s, 2.2 Run when scared by a hit/near player jump? no: Walk only) and the animation name
  (Wait / Walk / Turn / Sleep). It gets a `Terrain` interface answering `groundAt(x, z)` (height or NaN)
  and `blocked(x, z, height)`; if blocked it tries 2 other headings, then waits (stuck counter);
  after 3 stuck times it picks a new target. Unit tested with a fake terrain (a wall, a step, a
  cliff, a gap).
- Client `ToadWalker`: implements `Terrain` over the active planets' sessions (ground ray from above
  down along -up at the next point; blocked = a solid cell at feet+0.5 or head height in the step
  direction, so a placed wall stops it, a one-block step is climbed (up to 1), a drop of more than 2
  is refused). Moves each frame by the wander's wish, keeps feet on the ground (gravity = snap to the
  ground along the planet's up, falling at 10 blocks/s if the ground vanishes), keeps `pos` and `rot`
  in the record, saves a changed position every 5 s (not each frame).
- Near the player the Toad still turns to them; a Toad being talked to stops (IDLE, no walking).
- Far from the player (planet not active) it is not simulated: it stays where it was.
- **Home** = the bed: `ToadRecord.home` (new, optional: x, y, z galaxy units, defaults to the placed
  position, so old files keep working). Outposts' home is the hut's bed spot (`Huts.Placed` reports it).
- **Hut**: door = two `minecraft:oak_door` blocks (lower/upper halves, facing out) in the wall gap,
  a bed (`minecraft:red_bed`, foot + head) along the back wall of the 3x3 interior, the torch stays.
  The Toad stands beside the bed (not in the doorway); the level way out stays.
- **Night**: from the host's clock (dusk to dawn: `mc.level.getDayTime() % 24000` in 13000..23000):
  walk to the bed spot and play Sleep; wake at dawn or when the player is within 3 blocks.
- **Home destroyed**: if the bed block is gone (checked when the planet is active, every 5 s), `home`
  is re-set to the Toad's current position and the leash stays (it does not teleport or vanish).

## Not in scope

Pathfinding beyond local steering (no A*), trading hours, Toads fleeing, Toads opening doors (a closed
door is a wall; the Toad stays out or in, since it lives indoors only at night), picking up items.

## Testing

Unit: `ToadWander` (stays within the leash, stops at a wall and goes round, climbs a 1-block step,
refuses a 3-block drop, sleeps only at night and at home, unstuck after 3 failures, deterministic).
`Huts` (door and bed blocks placed, the bed spot reported inside). Probe (Dolphin slot): a Toad walks
(positions differ over time, stays on the ground), a wall placed in its way is not crossed, the hut
shot shows door and bed, Enter advances the box (real keys via the bridge).
