# Blocks show what they hold

2026-10-06. Status: approved in chat (approach A, shield included; the user delegated the rest:
"permission to do anything until you've resolved the goal, be efficient").

## Goal

What a block keeps shows on the planet as Minecraft draws it, and looks part of the planet (lit
by its light), not pasted over it:

- a sign's and hanging sign's text (colors, glowing text and its outline, both sides);
- banner patterns, the banner waving;
- a player head's own skin;
- items on shelves and food on campfires;
- chest (also trapped, copper, ender) and shulker box lids opening and closing;
- the shield: Minecraft's own shield model (with its banner pattern) in Steve's hand, raised
  as Minecraft raises it while blocking.

## Decisions

| Question | Choice |
|---|---|
| Who draws them | The block's own `BlockEntityRenderer`, on the shadow dimension's real block entity, through the game-drawn entity path (`EntityCapture` -> `GXC_MSG_ENTITIES`), as mobs are drawn |
| Which blocks are live | Only while their look depends on their data: a sign with text, every banner (it waves), a player head with a profile, a shelf or campfire holding items, a decorated pot with sherds, a chest or shulker box whose lid is not shut. Everything else stays baked in the chunk mesh |
| Range | Live within 64 blocks of Mario (Minecraft's block entity view distance), leaving at 68 so the edge does not flicker. Beyond, the baked shape shows (a sign's board without text) |
| Baked shape while live | Hidden: the mesher skips live cells (`VoxelPlanet.hidden`), a cell going live or back marks only its chunk dirty. Collision is untouched |
| Light ("in game") | Each piece in its cell's sky and block light under the hour's sky light, as the chunks (`EntityClient.lit`), faces shaded by direction (`EntityWire.shade`); glowing text full bright, as Minecraft; cutout edges (alpha compare), no blended fringe |
| Text | `submitText` runs Minecraft's own `Font.prepareText` (and `prepare8xTextOutline` for glowing text, as `TextFeatureRenderer`); each glyph renders into a `Recorder`; quads grouped by font page. Font pages live only on the GPU: a page is read back once (`copyTextureToBuffer`) after glyphs are added to it (`FontTexture.add` counted by a mixin) and sent as a skin |
| Skins not in the resources | A downloaded player skin is a `DynamicTexture`: its pixels are read from it when the resource manager has no file |
| Lids | The shadow is a server level: Minecraft animates chest lids only on clients. `ShadowWorld` runs `ChestBlockEntity.lidAnimateTick` / `EnderChestBlockEntity.lidAnimateTick` for chests near Mario. A real player using a shadow chest counts as its opener (`ContainerOpenersCounter` mixin), or the counter's recheck shuts it after 5 ticks |
| Budget | `ENT_MAX` 768 -> 1536 pieces a frame (92 KB message, the S->M ring is 4 MB); custom models (text) 256 -> 1024 |
| Shield | Drawn by Minecraft's item renderer into pieces placed on Steve's forearm (model id flag `HAND_L`/`HAND_R`: the matrix is relative to the hand placement, the game multiplies in the joint each frame). While Minecraft's player blocks, the arm takes `HumanoidModel`'s BLOCK pose: the game rotates that arm's joints after Mario's animation |

## How it works

**Server (shadow).** Each tick `ShadowWorld` collects the block entities of mirrored chunks
within range of the proxy, keeps those `Live.wants(be)`, ticks chest lids, and publishes
`Live(planet, map, list of (cell, BlockEntity))` next to `Entities`.

**Client.** `EntityClient` each client tick: diff the live cells with last tick, update
`planet.hidden`, mark those chunks dirty. Each frame: for each live block entity,
`renderer.extractRenderState(be, state, pt, cameraPos, null)` + `submit` into `EntityCapture`
placed at the cell's frame (`ShadowMap.frame`), lit by that cell.

**Game module.** `EntityDraw`: hand flags (joint x hand placement x piece); `GalaxyCraft` /
`HeldItem`: the arm's BLOCK pose while `GXC_MSG_HELD` says blocking.

## Build order

1. Live set: `ShadowWorld` collect + `Live.wants`, `VoxelPlanet.hidden`, mesher skip, dirty
   marking; draw live block entities through `EntityCapture` (banners, shelves, campfires, pots
   work here). Test: `PlanetMesherTest` hidden cell; probe screenshots.
2. Text: `submitText`, font page readback, glow full bright.
3. Player head skins: `DynamicTexture` pixels.
4. Lids: lid ticking, openers mixin; chests and shulker boxes live while open.
5. Shield: hand-attached pieces; BLOCK arm pose.
6. Budget raise; `tools/gxvoxel.sh drawn` scene grows (sign with colored and glowing text,
   banner with patterns, player head, shelf, campfire, open chest; day, night, cave); frame time.

## Testing

- `PlanetMesherTest`: a hidden cell draws no faces, its neighbors still cull against it.
- `DrawnBlocksProbe` (`tools/gxvoxel.sh drawn`): the scene above, screenshots, pieces counted
  per block, frame time with 100 signs and 50 banners within 2 ms of without.
- `HeldItemTest`: shield in the off hand drawn as pieces; blocking raises it.
