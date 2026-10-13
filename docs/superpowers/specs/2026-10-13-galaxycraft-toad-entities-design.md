# Toads as Minecraft entities — design

Date: 2026-10-13. Status: approved in conversation (option A), spec awaiting the user's review.
Replaces the walking of `2026-10-12-galaxycraft-toad-life-design.md` (ToadWander / ToadWalker). Builds on
`2026-10-11-galaxycraft-toad-npcs-design.md` and `2026-10-12-galaxycraft-toad-dialogue-design.md`.

## Asked (user, 2026-10-13)

1. Toads are **Minecraft entities**: a hitbox, knockback, damage, aimed and hit like any mob, as close as
   possible to how mobs already work here.
2. **Villager-like**: they use the bed, light and door the player builds, sleep indoors at night, go home.
   Today the home Guide lies down in the open (it knows no house, only a fixed spot).
3. **Vulnerable like villagers** (chosen): they can be hurt, killed by monsters or the player, and are
   gone for good when they die.
4. Sometimes a Toad ends up **inside blocks and stays stuck**: must not happen.

## Understanding

A Toad stops being a drawn record that slides over the ground (ToadWalker) and becomes a real
`Villager` in the **shadow dimension**, where the planet's mobs already live (ShadowWorld mirrors the
planet's columns near Mario into a Minecraft level; mobs there use vanilla AI, collision and damage;
`ShadowWorld.interact` / `attack` already take a shadow entity id). Villager AI (beds, doors, schedule,
panic, monsters) comes from Minecraft. What stays ours: the Kinopio drawing, the dialogue box with Enter,
the offers by type and tier (`ToadMerchant`), and the permanent record.

## Model

- **Record (permanent).** `ToadStore` / `toads.json` stays the source of truth: id, type, stage, last
  position and rotation, `placed` marks. It is the only thing saved. A dead Toad's record is removed.
- **Entity (while its planet is mirrored).** `ShadowToads` (server thread, in `shadow/`): each tick, for the
  running planet, every record of that stage whose spot is inside the mirrored columns and has no entity
  yet gets one: `Villager` (profession NONE, no trades), `setPersistenceRequired`, tagged
  `galaxycraft_toad:<id>` (the entity tag, so any mob lookup finds it), placed at the record's position
  mapped into the shadow (`ShadowMap`) **on free ground**: the nearest standing spot with two free blocks
  above it (search up then around), never inside a block. A Toad whose planet is not mirrored has no
  entity; its record keeps the last position.
- **Position back.** Every second, and when its planet unmirrors or the game saves, the entity's position
  and yaw are written back to the record (shadow -> galaxy through `ShadowMap.frame`, as mobs are drawn).
- **Stuck-in-blocks guard.** (a) Vanilla collision keeps a real entity out of blocks; (b) on spawn and when
  the planet re-mirrors, the spot is made free as above; (c) each second, an entity whose feet or head cell
  is a colliding block is moved to the nearest free spot (the same search). Blocks the player places on
  one are refused (`occupied`, done).
- **Death.** A removed or dead Toad entity (`Entity.isRemoved()` with a death reason, not an unload) deletes
  its record, its `placed` mark stays (the outpost is not regenerated). The client is told by a polled
  event like `pollBed`.
- **Talking.** The client aims at the Toad entity (the shadow entity list is already handed to the client).
  A right click starts the dialogue box (unchanged `ToadTalk` / `ToadConversation`); trade opens our
  `ToadMerchant` menu. The vanilla trading screen is never used. While a conversation is open the
  entity is told to stand still and look at the player (`stopTalking` when it ends or the player walks
  away), via a server op like `interact`.
- **Hitting.** The left click goes through the existing mob path (`ShadowWorld.attack`), including
  knockback and damage; the Toad turns hostile to nobody. Monsters target it by vanilla goals.
- **Drawing.** `EntityClient.shadowEntity`: if the entity carries a Toad tag, it is drawn by the Kinopio
  pipeline (`ToadClient.draw`, extracted from `pieces`) at the entity's frame, with animation chosen from
  its state: Sleep (`isSleeping`), Walk (horizontal speed > 0.05), Talk (in conversation), Wait.
  The hurt flash, death fall and knockback tilt are the entity's own. The Technician on a station and the
  Guide use the same path (stations mirror their own planet space).
- **Hut (what a villager needs).** Generated huts are rebuilt for the villager AI: the room is **3 blocks
  high** (walls three high, windows on the middle one) with the **roof block on top, 4 in all** (today the
  walls are two high, too low for a 2-high Toad with a door); a real **oak door** (both halves, closed by
  default, villagers open it) in the doorway; a **red bed** (foot and head) along the back wall of the
  3x3 room; the torch stays. Facing comes from the hut's own facing vector through the planet's cell
  orientation (`CellSpace.direction`), not from a compass guess. The door gap and the level way out stay.
  The room is cleared 4 high so a leveled hut never has terrain inside it.
- **Removed.** `ToadWander`, `ToadWalker`, `ToadWanderTest` and the walk code in `ToadClient.pieces`;
  `ToadClient.occupies` (the entity has a real box, `ShadowWorld.occupied` already covers it).

## Risks to settle first (step 1 of the plan, a spike with a probe)

1. **POIs.** Villager AI claims a bed through the level's POI manager. If the mirror copies columns without
   the normal block-change path, beds and doors are unknown: the fix is to register them from the mirror
   (`PoiManager.add`) for bed, door, and nothing else.
2. **Clock.** The shadow level's day/night must follow the world's, or the schedule never sleeps.
3. **Despawn.** Persistence plus the mob caps (`mobCounts`) must not count or remove Toads.
4. **Mirror edges.** Entities near a face's edge are re-wrapped (`wrap`); a Toad must survive that with
   its brain intact (`getNavigation().stop()` is already called).
5. **Station planet space.** The Technician must spawn on the deck, not in the void of the station's
   shadow slot.

## Not in scope

Breeding, gossip, villager professions, iron golems, raids, Toads carrying out trade with each other, health saved across unloads (a Toad comes back at full health).

## Testing

- Unit: the free-spot search (a pure function over a "colliding?" predicate: air above ground, inside a
  solid, in a one-block cave, on a slab); the record <-> entity position mapping round trip; the dead
  Toad's record removal; the tag parse.
- Probe (`tools/gxvoxel.sh`): spawn a Toad on a mirrored planet; it has a box and is hit with knockback
  and damage; placed blocks inside it are refused; a generated hut has its door, bed and 3-high room (and a Toad inside is asleep at night), a house with a bed, a door and a light is built, the
  clock set to night, and the Toad is asleep in the bed (`isSleeping`) with its Sleep animation; a Toad put
  inside a block is moved out within a second; killed, its record is gone after a save and load; the
  dialogue box still opens and trade still works on the entity.
