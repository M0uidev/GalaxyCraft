# Game feel: breaking and placing blocks

2026-10-06. Status: implemented on branch `game-feel`, not yet played in the game.

## Goal

Breaking and placing blocks on a planet feels like Minecraft, with Mario Galaxy's flow: hold the
button and blocks go one after another at the speed the tool in hand gives (a shovel with
Efficiency through dirt), with Minecraft's sounds, pieces of the material flying off the block
being hit and the cracks growing on it.

Before this, a click broke the block under the crosshair at once, whatever it was and whatever
was in hand, silently; the shadow's break pieces were the only feedback.

## Decisions

| Question | Choice |
|---|---|
| Breaking speed | Minecraft's exactly: `BlockState.getDestroyProgress` (hardness, tool, right tool or not, Efficiency, Haste, Mining Fatigue) |
| Holding the button | Minecraft's `MultiPlayerGameMode`: progress every tick, 5 ticks between blocks, instant blocks one a tick, creative every 5 to 6 ticks |
| Mario Galaxy's part | Breaking is not 5× slower in the air (Minecraft's penalty): Mario jumps and spins all the time |
| Cracks | Minecraft's `destroy_stage_0..9` drawn by the game over the block's outline box, multiplied in as Minecraft's crumbling |
| Sounds | The block's own `SoundType`: hit every 4 ticks, break, place; Minecraft's volumes and pitches, played where the block is |
| Particles | Minecraft's crack particle every tick of breaking, its break burst (from the shadow, or the client without one), and a small puff of pieces on placing (GalaxyCraft's own: Minecraft makes none) |
| Empty hand | Unchanged: the clicks stay Mario's (spin) unless something is in hand or the block aimed at is usable |
| Block outline | Minecraft's: the edges of the block's real shape (stairs, fences, torches), no line across a face; shown, as before, when a click would act on the block |

## How it works

**Mod (`fabric/`).**

- `voxel/Mining` (no Minecraft types, unit tested in `MiningTest`) is `MultiPlayerGameMode`'s
  `startDestroyBlock` / `continueDestroyBlock` / `stopDestroyBlock`, a client tick at a time: given
  what is aimed at (cell and block id), the tool, the progress a tick makes and creative mode, it
  says what broke, whether the hit sound plays, whether the button worked on the block (particle
  and swing) and the crack stage. Another cell, another block in it (a door opened) or another
  tool starts over; letting go heals the block.
- `PlanetClient.mine` runs it every tick the player has something in hand and aims at a block (not
  a mob): the progress comes from Minecraft (`destroyProgress`, undoing only the airborne
  penalty), the break goes through the existing `breakBlock` (the shadow's
  `ServerPlayerGameMode`-like destroy: drops, tool wear), and a cell just broken is skipped for a
  few ticks while the shadow removes it, so it never breaks or sounds twice.
- Sounds: `ClientLevel.playLocalSound` at the cell's center mapped to Minecraft's coordinates by
  the player's `GravityFrame`, so they come from where the block is.
- Particles: `ParticleClient.crack`, `burst` and `puff` add pieces of the block (the same kind as
  the shadow's break pieces) in the cell's model space.
- `McBlocks` puts `destroy_stage_0..9` in the block atlas and gives each stage's tile
  (`crackUv`).
- `PlanetSession.setCrack` sends `GXC_MSG_CRACK` when the cell, its block or the stage changes, in
  the control queue like the outline.

- `voxel/OutlineEdges` (unit tested) is `VoxelShape.forAllEdges`: the boxes of the block's
  outline shape cut space into a grid, and a grid line is an edge where the cells around it are
  one or three inside, or two across a diagonal; touching pieces of a line join. `PlanetSession`
  sends those edges (its bounds' box past `GXC_OUTLINE_MAX_EDGES`) instead of the bounds' 8
  corners it sent before.

**Protocol.** `GxcOutline` now carries edges: planet id, count, then count × two ends (8 + 24 ×
count bytes, up to 96 edges); the host swaps all of its words, the module copies them into a
`GX_LINES` list as they come (`core/Inbox` `OutlineList`, g++ tested). `GXC_MSG_CRACK = 115`, `GxcCrack` (120 bytes): planet id (0: none), stage, the
stage's atlas tile (u0, v0, u1, v1), the eight corners of the cell's outline box grown by 0.5%
(the outline's are 2%), in `GxcOutline`'s order.

**Host (`dolphin/`).** `HostBridge::QueueInbox` passes it to the module's inbox with its fixed
fields swapped.

**Module (`syati/`).** `core/CrackMesh` (g++ tested) builds the box's six sides as quads (vertex
format 6: f32 position and texture coordinates), upright on the four sides. `VoxelPlanet` keeps two
lists in turn (as the outline) and draws the cracks after the translucent pass with the atlas:
blend `DSTCLR, SRCCLR` (2 × texture × screen: Minecraft's crumbling), texels with alpha ≤ 25 left
out, depth tested and not written.

## Not done / open

- Nothing has been played in the game yet: the mod's client code was written without building it
  (Minecraft's servers are out of reach of the cloud session), so it needs a build and a playtest.
- Bare-hand breaking (punching wood) stays impossible while empty-hand clicks are Mario's.
- Minecraft's crack shows on the block's real model; here on its outline box (for slabs and other
  non-cubes it is the box around them).
- Placing has no reach-out animation; the arm swings only while breaking.
