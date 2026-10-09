# Swimming and water look (design)

2026-10-08. From the user: swimming matters; characters standing in water are drawn over it;
under water everything should look like Minecraft's underwater view.

## Goal

Water behaves and looks like Minecraft's on planets:

1. Anything in or behind water is blended with it (entities, Steve, drops).
2. Looking from under water gives Minecraft's underwater look (tint, fog, darkening).
3. In F6 (Minecraft movement) Steve swims by Minecraft's rules.

Success: screenshots from the user's three cases (pigs and Steve at the shore, a pig seen from
under water) show water over what is under it; an underwater screenshot matches Minecraft's color
and fog; a movement probe swims, sinks, surfaces and drowns as Minecraft does.

Out of scope: Mario mode swimming (its own stage, depends on whether SMG2 can wet Mario without
water volumes in his collision), currents that push, boats swimming, lava damage.

## What exists

- Fluids live on the planet only (`voxel/Fluids`, `Planet.fluid`); the shadow dimension cancels
  fluid ticks (`ShadowFluidMixin`). Minecraft never sees Steve as wet.
- `VoxelPlanet.cpp` draws a planet in three passes: far view, solid (`PASS_SOLID`), translucent
  (`PASS_CLEAR`, water: alpha blend, depth test, no depth write).
- `EntityDraw` and `VoxelPlanet` are both connected to `DrawType 0x0E`; entities draw after the
  planet, so after the water pass. Water cannot blend with anything drawn after it.

## Part 1: draw order

Draw order becomes: planet far view and solid, then entities, then the planet's translucent pass.

- `VoxelPlanet::draw` runs the entity draw between `PASS_SOLID` and `PASS_CLEAR` of the planets
  (`VoxelPlanet.cpp` already includes `EntityDraw.h`), and `EntityDraw`'s own actor stops drawing by
  itself. With several planets (up to 8), all solid passes, then entities, then all water passes.
- Particles (billboards, no depth write) keep drawing with the entities, before water.
- Test: a probe puts a pig half sunk and another on the shore, Steve half in, and the camera under
  water looking up at a pig; screenshots show blue over the sunk parts and a veil over the pig.

## Part 2: underwater view

When the camera (eye) cell is water:

- GX hardware fog (`GXSetFog`, exponential-squared or linear) on the planet, entity and sky passes,
  color and range from Minecraft's `FogRenderer` for water (biome water fog color, vision scaled
  down in the dark, longer range with the future Water Breathing / Conduit effects left out).
- A screen tint at the camera (Minecraft's underwater overlay): a quad drawn last, color the
  biome water color, darkened by the sky light.
- The water surface seen from below stays drawn (its back faces), with the same blend.
- The mod sends one new protocol word per frame (eye in water: 0/1, fog color, range) in the
  existing view message, so the game needs no knowledge of fluids. Java reads the eye cell from
  the planet (`Planet.fluid`).
- Test: a probe at depth in plains, swamp and warm ocean, captured next to Minecraft's own
  underwater values (fog color read from `BiomeColors`); the pixel tint is checked, not eyeballed.

## Part 3: swimming in F6

- A planet wetness sensor on the Java side: for Steve's box, the fraction of the body in water
  (feet, waist, eyes) and the fluid's flow state, read from the planet's cells.
- Fed into Minecraft's own movement: the proxy and the real player are told `isInWater`,
  `isUnderWater`, `eyesInWater` through mixins on `Entity.updateFluidHeightAndDoFluidPushing` (the
  planet's fluid heights replace the level's, since the level has none). Minecraft then does its
  own swimming: buoyancy and drag, sneak to sink, jump to rise, sprint-swim with the swimming
  pose, air supply and bubbles, drowning damage, getting out of the water at an edge.
- Steve's model takes the swimming pose through Minecraft's own pose (`Pose.SWIMMING`); the
  game-drawn renderer already uses the entity's pose and animation state.
- Gravity on round planets: "down" is the planet's; the sensor and the swimming pitch use the
  gravity frame (`gravity/GravityFrame`) so swimming works on the far side.
- Mario's physics is untouched in this stage; Mario mode neither swims nor sinks yet.
- Test: a movement probe (like `MovementProbe`) drops Steve in a pool and an ocean, checks float,
  sink, rise, sprint-swim speed, surfacing at an edge, and air going down then drowning.

## Order of work

1. Draw order (small, visible at once; also needs a module rebuild and a savestate remake).
2. Underwater view.
3. Swimming. The riskiest: the fluid heights come from the planet, not the level.

Each part gets its own commit and probe. The user playtests before the branch merges.
