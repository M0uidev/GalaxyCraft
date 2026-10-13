# Ship cargo: chests you install in the Toad ship

Date: 2026-10-13. Piece A of three (A cargo, B ship customizer, C landing pad station); B and C get their own specs.

## What the user asked

A ship inventory that feels part of the 3D ship: chests you install, drawn inside it, opened with **E**
from aboard or **Shift + right click** on the parked ship from outside. Four units of room; a large chest
takes two, so two large chests equal four small ones.

Decided with the user (2026-10-13):

- You install the chests yourself, from the cargo screen, and each installed chest is drawn in the model.
- Opening shows one screen with every chest (same screen inside and outside), the player's inventory below.
- The cabin's tier unlocks room: tier 1 = 1 unit, tier 2 = 2, tier 3 = 4.
- Chests are drawn inside the ship, behind the portholes, like the cushions.

## Rules

- **Bays.** The hold has 4 bays in a row, numbered 0..3 from the nose. The cabin tier unlocks the first
  `capacity` bays (0, 1, 2, 4 for tier 0..3); the others show as locked.
- **Small chest.** A vanilla chest item put into a free unlocked bay: 27 slots.
- **Large chest.** Minecraft has no large chest item, so it is a double chest: two chests in the adjacent bays
  (0+1 or 2+3) **joined** with the screen's Join button (Split undoes it, and only while the large chest is empty). A large chest has 54 slots and takes both bays.
- **Taking a chest out** is allowed only when it is empty (otherwise nothing happens and the screen says so),
  so cargo is never lost by accident. The chest item goes back to the player's inventory (or drops if full).
- A chest item is consumed on install: it is not a ship module and does not change the ship's stats.
- A ship that is already placed keeps its cargo; a ship whose cabin tier is lower than its installed chests
  cannot happen (the cabin is fixed when the ship is assembled; ships cannot be picked up today).

## Data

`ShipCargo` (pure, `ship/`): the bays and their contents.

- `Bay` states per index: `EMPTY`, `SMALL`, `LARGE_HEAD`, `LARGE_TAIL`.
- `capacity(cabinTier)`; `canInstall(i)`, `install(i)`, `canJoin(i)`, `join(i)`, `split(i)`, `canRemove(i)`, `remove(i)`.
- Each chest unit (a small chest or a large head) owns `NonNullList<ItemStack>` of 27 or 54.
- Kept per ship id by `ShipStore` and saved in `ships.json` as a `"cargo"` object of the ship's entry
  (bay states + items). `ShipRecord` is not changed (many callers; it stays immutable and small).
  A ship with no `"cargo"` loads with an empty hold.
- Items are saved with Minecraft's `ItemStack` codec through a registry-aware `JsonOps`.

## Opening and the menu

- **Aboard + E.** A client tick handler consumes `keyInventory` while aboard and opens the cargo.
- **Outside.** `ShipClient.tick` already sees a right click on an aimed ship (boards it). With Shift held the
  click opens the cargo instead of boarding. `swallowsUse` already keeps an item from being put down.
  A hint ("Shift + right click: cargo") shows while aiming at a ship with Shift held.
- The menu is a real Minecraft container menu (`ShipCargoMenu`), opened on the integrated server for the local
  player, so moving items between the player's inventory and the hold is Minecraft's own and stays in step.
  The game is single player (multiplayer is out of scope), so the client store and the server share the process;
  server-side changes to a chest are applied to the store on the client thread.
- Layout: top, the 4 bay slots (accept only chest items, stack of 1), Join / Split buttons between bays 0-1 and 2-3,
  a selector for which chest is shown; below, the selected chest's 3 or 6 rows, then the player's inventory.
  The content slots are 54 fixed slots over a `CargoView` container that delegates to the selected chest and
  is inactive past its size.

## What you see

- Each installed chest is drawn in its bay behind the cabin, with the vanilla chest model and texture; a large
  chest is drawn as a double chest across its two bays. Bays are placed by a `CargoLayout` like `SeatLayout`.
- The opened chest's lid opens while its screen is shown (last step, optional).

## Out of scope

Customizing the chests' look (piece B), picking up a placed ship with its cargo, hoppers or automation,
sharing a hold between ships, multiplayer sync.

## Tests

- Unit: `ShipCargoTest` (capacity per tier, install/join/split/remove rules, 4 small / 2+1 / 2 large combinations),
  `ShipStoreTest` (cargo round trip, ship without `cargo`, damaged `cargo` is an empty hold).
- Game: a probe (`ShipCargoProbe`) that boards a ship, installs a chest, moves an item in, closes, reloads the store
  and finds the item.
