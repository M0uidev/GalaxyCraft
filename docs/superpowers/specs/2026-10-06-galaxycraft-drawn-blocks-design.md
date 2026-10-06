# Drawn blocks: chests, beds, signs and the rest on planets

2026-10-06. Status: design (user delegated the decisions: "continue with everything").

## Goal

Every block Minecraft draws with a block entity renderer shows on a planet, looking as it does in
Minecraft: chests, trapped chests, ender chests, copper chests, shulker boxes, beds, signs and
hanging signs, banners, mob heads, decorated pots, bells, lecterns (and their book), the
enchanting table's book, conduits, shelves. Also the lit TNT drawn whole, the shulker (the mob),
and the off hand's item in Steve's hand.

It must cost nothing per frame that the planet does not already cost: a chest is drawn like a
stair is.

## What is wrong today

- In Minecraft 26.3 these blocks' models have **no faces**: `models/block/chest.json` is only a
  particle texture, and `RenderShape` has only `MODEL` and `INVISIBLE`, so `McBlocks.compute`
  bakes zero quads and the block is invisible. Its fallback (the outline box in the particle
  texture) only ran for a non-`MODEL` render shape, which no longer happens.
- Their textures are not block sprites: they live in Minecraft's sheet atlases (`chest`, `bed`,
  `shulker_boxes`, `signs`, `banner_patterns`, `decorated_pot`) or are plain entity textures
  (skulls, the bell, the enchanting book). The planet atlas has 1895 of its 2048 tiles taken.

## Decisions

| Question | Choice |
|---|---|
| Where they are drawn | Baked into the chunk mesh like every other block: no per-frame work, one draw call as now |
| Their geometry | What the block's own `BlockEntityRenderer` submits for a block entity in that state (`newBlockEntity` at the origin, `extractRenderState` + `submit` into a capturing `SubmitNodeCollector`), turned into the block's quads |
| Their textures | The sheet sprites and entity textures they use, cut into 16×16 tiles and added to the planet atlas; the atlas grows from 1024×512 to 1024×1024 (~1.4 MB more of the module's ~187 MB heap) |
| Which tiles | Only the 16×16 cells of each texture that some face samples, deduplicated; textures found once per block type at start-up (a state's rotation does not change them) |
| Faces across tiles | A face whose texture rectangle spans several tiles is split along the 16-pixel grid into one quad per tile |
| Moving parts | Still, in their resting pose: closed chests and shulker boxes, a still bell, the enchanting book closed on its table. Opening and swinging are left for "Blocks that work" |
| Per-block data | Not in this stage: sign text, banner patterns, a player head's own skin, the items on shelves and campfires. They live in block entities, which planet cells do not keep; "Blocks that work" brings them |
| Still invisible if no renderer | A block with no faces and no renderer (a moving piston, an end gateway's beam) keeps the old fallback: its outline box in its particle texture |
| Lit TNT, shulker mob | Through the existing entity path (`EntityCapture`); find why each is incomplete and fix it there |
| Off hand | `GXC_MSG_HELD` carries which hand; the module draws the off hand's item in Steve's left hand as it draws the main one in the right. Raising the shield: Minecraft already blocks with it (the real player uses it); its pose is left for later |

## How it works

**Mod (`fabric/`).**

- `voxel/TileSplit` (no Minecraft types, unit tested): a quad with texture coordinates in a
  texture's pixels becomes quads each inside one 16×16 cell of it, with the cell and the
  coordinates inside it.
- `client/BlockEntityBake`: for a block state whose model has no faces and whose block has a
  renderer, runs that renderer once into a capturing collector (models and their `ModelPart`
  cubes, as `EntityCapture` walks them; block models; custom geometry), giving faces in block
  space with their texture (a sprite of a sheet atlas, or an entity texture) and pixel rectangle.
  Unit-free, cached per state; a renderer that throws (it wanted a level) gives nothing, and the
  fallback box is used.
- `McBlocks` start-up: for each block type with a renderer, bakes its default state once to find
  the textures it uses and adds their used cells to the atlas tiles. `compute(state)` bakes the
  state and maps each face into the atlas through `TileSplit`.
- `EntityCapture` / `EntityClient`: the lit TNT and the shulker, as the investigation finds.
- `HeldClient`: sends the off hand's item too.

**Game module (`syati/`).** The atlas allowed up to 1024×1024 (already the format's limit,
`Atlas.MAX_COLUMNS`); `HeldItem` draws a second item in the left hand.

**Protocol.** `GXC_MSG_HELD` gains a hand byte (version bump).

## Testing

- `TileSplitTest`: a face inside one cell, across two and across four; texture coordinates and
  positions interpolated exactly; mirrored faces (Minecraft's cube faces flip U).
- `BlockRulesProbe` (no Dolphin): every block with a renderer bakes faces (none left invisible),
  each face's tile is inside the atlas, and a chest's faces stay inside its block.
- `tools/gxvoxel.sh blocks` (new, `DrawnBlocksProbe`): a row of chests, beds, signs, banners,
  heads, shulker boxes, pots and bells placed on a planet; screenshots; the module's atlas bytes
  and frame time before and after (no more than the same count of stairs).
- `EntityTest`: lit TNT and a shulker drawn (pieces counted).
- `HeldItemTest`: an item in the off hand drawn.
