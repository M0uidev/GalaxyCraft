# Toad Brigade ship: a craftable, modular vehicle (design)

2026-10-11. From the user: "implement the Toad ship as a craftable vehicle inside the game, scaled
so it feels perfect. I don't know how to get in or out, but maybe use advancements as player
progress, a rare piece or a ship crafting table, and make ships modular."

## Decisions taken with the user

| Question | Answer |
|---|---|
| Which ship | The Toad Brigade's ship (not Starship Mario). |
| Its look | Built from SMG2's own pieces. The disc has **no** Toad ship model (checked in `ObjectData`: no `KinopioShip`/`ToadShip`), so it is assembled from `MiniPlayerRocket`, `MarioFaceShipJet`, `MarioFaceShipRudder`, `MarioFaceShipChimney` and `SpaceShipStep`, tinted Toad red and white. |
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

## Design

### 1. Models: real SMG2 pieces without a new C++ message

Conversion happens at install time (launcher), like the soundtrack:

1. Extract the five arcs from the player's disc.
2. Read each `.bdl` with an extended `j3d.py` (add MAT3/TEX1: material colors, texture images) and
   write a compact **ship asset** (`ship/<piece>.gxship`): rigid pieces (vertices, UVs, a texture)
   in the same shape `EntityCapture` already produces.
3. The mod loads those assets and sends them through the existing MODEL/SKIN records. The module
   (C++) is unchanged.

Fallback: if the assets are missing (no disc, unsupported model), the ship is drawn from tinted
cubes in Java so the vehicle is never invisible.

### 2. The ship entity

- A custom entity, saved with the world, holding its module list (`hull`, `engine`, `rudder`,
  `cabin`, each a tier) and an owner.
- Right click boards: the player's seat is the ship, Mario is sent `SEAT` with the cabin's seat
  position, flying pose (riding 2) while it moves. Shift gets off and leaves the ship parked.
- The player's controls drive the elytra flight with the ship's stats (speed, turn, pulse); stats
  come only from `ShipStats.of(modules)`, a pure function.
- Placing: the assembled ship is an item (like `PackedStationItem`) that becomes the entity.

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

- **Models:** J3D skeletons, multiple materials and TEV effects may not reduce to rigid textured
  pieces. First task of the plan is a spike on one arc (`MiniPlayerRocket`); the decision rule is
  that pieces missing a texture get a flat color, and a model that needs real TEV falls back to
  cubes for that piece.
- **No Toad ship on the disc:** the look is assembled; the user may want to refine which pieces
  after seeing it.
- **Flight and seats across planet and space:** the seat message is sent per frame (holds 30
  frames); the ship must keep sending it when no planet is in focus.

## Out of scope (stage 1)

Abilities (landing, towing, jumping between systems), a visual assembling screen beyond the
table menu, animated models, multiplayer ships, ship combat.
