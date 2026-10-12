# Toad Brigade ship: a craftable, modular vehicle (design)

2026-10-11. From the user: "implement the Toad ship as a craftable vehicle inside the game, scaled
so it feels perfect. I don't know how to get in or out, but maybe use advancements as player
progress, a rare piece or a ship crafting table, and make ships modular."

## Decisions taken with the user

| Question | Answer |
|---|---|
| Which ship | The Toad Brigade's ship, the **Starshroom** (the red mushroom ship; not Starship Mario). |
| Its look | SMG2's own model of it: `UFOKinoko.arc` on the disc (the mushroom UFO), one piece that is the hull; the engine, rudder and cabin modules live inside it and change stats and seats, not the look. (An earlier draft built it from Starship Mario's pieces by mistake; the disc does have the Starshroom.) |
| Real models or cubes | Real SMG2 models from the start (cubes only as a fallback, below). |
| Getting in and out | Right click to board, Shift to get off, as boats and minecarts. |
| Progression | Advancements -> Ship Table -> modules. The Ship Table is craftable **without** any advancement: advancements are a suggested linear path, balanced later by the user. |
| What modules do | Stage 1: flight stats and seats. Abilities (land on planets, tow, jump between systems) are a later stage. |

## Goal

1. A **Ship Table** block where the player makes modules and assembles a ship from them.
2. **Modules** (stage 1): hull, engine, rudder, cabin. A ship is the sum of the modules put in it.
3. A **ship entity** that persists in the world, is boarded with right click and left with Shift,
   and flies between planets using the elytra's flight (gravity-body generic).
4. Ship pieces drawn by the game as SMG2's real models, at one tunable scale.
5. **Advancements** as data (datapack JSON): a suggested chain that unlocks module families and
   the table's recipe hint, never a hard lock.

Success: a probe run in empty space crafts a ship from modules, boards it, flies it, gets off with
Shift, and the stats match the modules; a Dolphin capture shows the ship beside Steve and Mario at
the agreed scale.

## What exists (verified in the code, 2026-10-11)

- Entities (mobs, boats, minecarts) live in the shadow dimension. Minecraft's own renderer draws
  them into `EntityCapture`, which cuts them into **rigid pieces** sent through `GXC_MSG_MODEL`,
  `GXC_MSG_SKIN` and `GXC_MSG_ENTITIES` (`EntityClient`, `EntityDraw.cpp`). Pieces and textures go
  once per scene; each frame only the placements.
- Mario does not "ride" a Minecraft vehicle: `GXC_MSG_SEAT` (pos + riding) puts him on a seat each
  frame; riding 2 shows him gliding. `Flight` (elytra) is generic over gravity bodies.
- `ShadowRideMixin` keeps the player from riding shadow mobs into the shadow dimension; Mario rides
  carts and boats "his own way".
- Station Core (`StationBlocks`, `StationCoreItem`, recipe `data/galaxycraft/recipe/station_core.json`)
  is the pattern for a new block, item and recipe. Advancements are not used anywhere yet.
- `launcher/scripts/j3d.py` reads J3D `.bdl`: vertices, normals, triangles per shape and joint
  transforms (geometry only: **no** materials or textures).
- The launcher extracts files from the player's own `.rvz` with `dolphin-tool` (soundtrack does it
  already); nothing from the disc goes into the repository.

## Seats: Minecraft's cushions (added 2026-10-11, from the user)

Minecraft 26.3 has a `Cushion` entity (16 colors, three wool slabs, sat on by right click, own
renderer and model). The cabin's seats are cushions in Toad red and white (one per seat the cabin
holds: 1, 1, 2 or 4) and the player's character is drawn seated on them, not in the glide pose,
while the ship is slow. A cushion is block-attached in Minecraft, so on the ship it is only drawn
(a client-only instance through the same renderer); where the entity cannot be built a tinted cube
stands in. Placing cushions on planets and stations is a separate matter: check that a real
cushion draws and can be sat on there with the entity pipeline (a probe, not part of this feature).

## Design

### 1. Models: real SMG2 pieces without a new C++ message

Players have no Python, so the conversion is in the mod:

1. At install and PLAY the launcher copies one arc from the player's own disc (decompressed) to
   `<data>/ship/` (`UFOKinoko`, the Starshroom), as the soundtrack is copied.
2. The mod reads them at run time with a Java RARC and BDL reader (vertices, UVs, textures) and
   builds rigid pieces (quads and a skin) in the shape `EntityWire` already sends through the
   existing MODEL/SKIN/ENTITIES records. The C++ module is unchanged.

Fallback: if an arc is missing or unsupported, that module is drawn from tinted cubes in Java so
the vehicle is never invisible.

### 2. The ship: a mod-managed object, not a Minecraft entity

- Minecraft's entities live in the shadow dimension; drops and stations are objects of the mod
  that `EntityClient` draws directly. A ship follows that precedent: a record saved per galaxy
  (`ShipStore`: modules, position in galaxy units, yaw), drawn as pieces each frame.
- Right click boards: the player is seated on the ship (seat message), Shift leaves it parked.
- The elytra flight is driven with the ship's stats (speed, turn, pulse); stats come only from
  `ShipStats.of(modules)`, a pure function.
- Placing: the assembled ship is an item (like `PackedStationItem`) that becomes the record.

### 3. Ship Table and modules

- Block `ship_table` plus items `ship_hull`, `ship_engine`, `ship_rudder`, `ship_cabin` and the
  `toad_ship` item. Registered like `StationBlocks`, shown in a creative tab.
- Stage 1 stats: hull = durability, engine = speed and pulse, rudder = turn rate, cabin = seats
  and storage. A missing module means a worse (or no) ship: the table refuses to assemble without
  an engine and a cabin.
- Recipes are normal JSON recipes; the table's menu assembles.

### 4. Advancements

A short chain in `data/galaxycraft/advancement/ship/`: build a station -> glide between planets ->
craft the rare piece, the `toad_core` (default recipe: a nether star with redstone and iron; the user may change it). Each only grants recipe unlocks
(the recipe book) and is **never required** to craft. Balancing is the user's, later, and is data
only.

### 5. Scale

One constant (`ShipScale`) scales the ship's pieces and the seat offset. Calibrated by capturing
the ship beside Steve and Mario in Dolphin; the value is recorded in the spec when chosen.

## Tests

- Unit: `ShipStats.of(modules)` (pure), module list serialization, the table's assembling rules.
- `ShipProbe` (client gametest, 3000 blocks above the stage as `ElytraProbe`): craft, assemble,
  board, fly, get off with Shift, stats applied.
- A Dolphin probe (`tools/gxvoxel.sh ship`): the ship drawn, with a screenshot beside Steve.
- Converter test: a fixture `.bdl` (not from the disc) converts to pieces with the expected counts.

## Risks

- **Models:** `j3d.py` reads no textures; the Java reader adds TEX1 and maps one skin per model. J3D skeletons, multiple materials and TEV effects may not reduce to rigid textured
  pieces. First task of the plan is a spike on one arc (`UFOKinoko`); the decision rule is
  that pieces missing a texture get a flat color, and a model that needs real TEV falls back to
  cubes for that piece.
- **One model for all modules:** the Starshroom is a single model, so engine/rudder/cabin tiers
  are not visible on it yet (a later stage may add visible parts).
- **Flight and seats across planet and space:** the seat message is sent per frame (holds 30
  frames); the ship must keep sending it when no planet is in focus.

## Out of scope (stage 1)

Abilities (landing, towing, jumping between systems), a visual assembling screen beyond the
table menu, animated models, multiplayer ships, ship combat.
