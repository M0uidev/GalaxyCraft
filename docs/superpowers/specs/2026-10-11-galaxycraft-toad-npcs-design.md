# Toad NPCs: villagers that trade and explain the world — design

Date: 2026-10-11. Status: design approved in conversation, awaiting review of this spec.

## Goal

Toads (and later other SMG2 characters) live in the world as villagers: they **guide the player's
progression**, **trade**, and explain where you are. They are drawn with SMG2's own model
(`Kinopio.arc`), not a Minecraft villager skin.

## Decisions (user, 2026-10-11)

- Main role: **progression guide** (what to do next, what each tier asks); trade and lore serve it.
- **Several types** of Toad; what each sells depends on **where it spawns**.
- Dialogue is **contextual**: depends on where you are and what you carry (hand-written templates, no AI).
- Visual: **model from the disc from the start** (option B), via the Starshroom's draw path.
- Payment is **flexible**: emeralds, items or blocks, balanced against progression.

## Out of scope

Animation (walk, idle: a later phase, static pose first), voices and sounds, quests, other NPCs
(Penguin, Rosetta, HoneyBee, Tico shop: they fit as new `ToadType`-like rows later), the 3D map.

## Model

- A Toad is **not a Minecraft entity**: ships and stations here are records kept in a JSON file
  (`ShipStore`), drawn by the game through `EntityWire`, with interaction in client hooks
  (`ShipClient.aimedShip`). `ToadRecord` + `ToadStore` follow that pattern: id, type, position
  (universe units), facing, stage. Always present where placed, never despawns, nothing attacks it.
  Drawn as rigid pieces of `Kinopio.arc` through the Starshroom's path (`Rarc`, `BdlReader`,
  `ShipModels`); `KinopioGoods*` arcs are its accessories.
- `ToadType` (data rows, no class per type):

| Type | Where | Accessory | Sells |
|---|---|---|---|
| Guide | home planet | glasses | the basics, the game guide |
| Miner | villages, mines | pickel, light | tools, ores |
| Explorer | villages, far planets | rucksack | maps, compasses |
| Technician | stations | snorkel | ship modules and parts |
| Mailman | villages | mail bag | items from other planets |

- **Trade**: reuses Minecraft's `MerchantMenu` (no new screen). Offers per type and tier. A Toad
  accepts several payments (emeralds, items, blocks). What it sells is what that tier can already
  produce; what it asks is of that tier or the previous one, so nothing skips progression nor forces
  extra travel. Numbers are a first draft, **refined by playing**.
- **Contextual dialogue**: on right click, 1 to 3 lines before the trade, picked by rules over
  templates (`{tier}`, `{star}`, `{missing_module}`): tier and star of the system, the Warp Core's
  tier, the ship module you lack, whether the system was visited. Deterministic, no AI, in the
  game's languages.
- **Placement**: a mushroom house with its Toad, placed by the planet generator using the structure
  table of planet progression (villages from tier 1); a fixed Technician on every station and a
  Guide on the home planet from the start. Deterministic by seed, saved with the world.

## Coordination with other sessions (2026-10-11)

- **Planet progression** (`feat/planet-progression`): this reads tier and star and places through
  its structure table. It does not change `SystemIndex` or `TierProfile`; if their shape changes
  that session must tell this one.
- **3D map** (`feat/map-3d`, session galaxycraft-66): tier and star are asked through its
  `SystemInfo` interface (`map/`), never recomputed here. It does not touch Toad NPC code, nor this
  `ShipModels`/`BdlReader`.
- **SMG2 mobs spike** (`spike/smg2-mobs`, session galaxycraft-e3): research only, no Java.
  Recommendation: Java authority (`ToadRecord`) plus, if wanted later, a game-side `ModelObj` puppet for
  real animations (option C); static pieces first is option B. If the puppet path is taken,
  `Kinopio.arc` must be listed in GalaxyCraftSpace's UseResource (`scenario_1.bcsv`, emptied today by
  `tools/space_galaxy.py:use_resource_arc`): coordinate with that session before editing it.
- **Toad ship** (`feat/toad-ship`, not in master): `Rarc`, `BdlReader`, the launcher's disc
  extraction. Implementation stacks on that branch and merges after it; this branch (from master)
  holds only the spec until then.

## Testing

Unit tests for offer tables (tier balance: nothing sold above its tier) and dialogue rules.
`ToadProbe` in the style of `ShipProbe`: place a Toad, talk, trade, check it is drawn. Never run
gxplay while another session uses it.

## Open questions

- Exact prices and payment mix per tier (tuned by playing).
- Can a Toad die, or only be hurt and recover?
- Static pose or the game's animation (option B or C) once the disc model is on screen.
