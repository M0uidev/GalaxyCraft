# Ship Cargo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Chests you install in the Toad ship's hold (4 bays unlocked by the cabin tier), opened with E aboard or Shift + right click outside, drawn inside the ship.

**Architecture:** A pure `ShipCargo` (bays + chest contents) lives in `ShipStore` next to the ship records and is saved in `ships.json`. A real Minecraft container menu (`ShipMenu`) is opened on the integrated server and edits the cargo; a screen draws it; `ShipClient` draws the chests inside the ship like the cushions.

**Tech Stack:** Java 25, Fabric (Minecraft 26.3), JUnit 5 (`./test.sh`), Fabric client gametests.

**Spec:** `docs/superpowers/specs/2026-10-13-galaxycraft-ship-cargo-design.md`

## Global Constraints

- Bays: 4, numbered 0..3 from the nose; cabin tier 0..3 unlocks 0, 1, 2, 4 bays.
- Small chest 27 slots, large chest (double, bays 0+1 or 2+3) 54 slots.
- A chest comes out only when empty; split only while the large chest is empty.
- `ShipRecord` is not changed; cargo is per ship id in `ShipStore`, saved as `"cargo"` in the ship's JSON object.
- Single player only; no network sync.
- Unit tests must not touch `ItemStack` (no Minecraft bootstrap in `./test.sh`): chest contents are created lazily.

## Review Focus

- A ship whose JSON has no `"cargo"` or a damaged one loads with an empty hold (tests in Task 2).
- Joining/splitting across the 1|2 boundary (bays 1+2) is refused (Task 1).
- Locked bays refuse installs (Task 1).
- Removing a non-empty chest is refused and nothing is lost (Task 1).
- A cargo for a ship id that no longer exists is dropped on save (Task 2).

---

### Task 1: `ShipCargo` (bay rules)

**Files:**
- Create: `fabric/src/main/java/dev/moui/galaxycraft/ship/ShipCargo.java`
- Test: `fabric/src/test/java/dev/moui/galaxycraft/ship/ShipCargoTest.java`

**Interfaces:**
- Produces: `ShipCargo.BAYS = 4`; `static int capacity(int cabinTier)`; `enum Bay {EMPTY, SMALL, LARGE_HEAD, LARGE_TAIL}`;
  `ShipCargo(int capacity)`; `int capacity()`; `Bay bay(int i)`; `boolean locked(int i)`;
  `boolean canInstall(int i)` / `void install(int i)`; `boolean canJoin(int i)` / `void join(int i)` (i = 0 or 2, the head);
  `boolean canSplit(int i)` / `void split(int i)`; `boolean canRemove(int i)` / `void remove(int i)`;
  `Chest chest(int i)` (the unit's contents, the head for a large one; null for empty);
  `static final class Chest { int size(); boolean isEmpty(); List<ItemStack> items(); }` (items created on first call).

- [ ] **Step 1: Write the failing test**

```java
package dev.moui.galaxycraft.ship;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ShipCargoTest {
    @Test
    void capacityGrowsWithTheCabin() {
        assertEquals(0, ShipCargo.capacity(0));
        assertEquals(1, ShipCargo.capacity(1));
        assertEquals(2, ShipCargo.capacity(2));
        assertEquals(4, ShipCargo.capacity(3));
        assertEquals(4, ShipCargo.capacity(9));
    }

    @Test
    void lockedBaysRefuseChests() {
        ShipCargo c = new ShipCargo(2);
        assertTrue(c.canInstall(1));
        assertFalse(c.canInstall(2));
        assertTrue(c.locked(3));
        assertFalse(c.canInstall(-1));
        assertFalse(c.canInstall(4));
    }

    @Test
    void fourSmallChests() {
        ShipCargo c = new ShipCargo(4);
        for (int i = 0; i < 4; i++) {
            assertTrue(c.canInstall(i));
            c.install(i);
        }
        assertFalse(c.canInstall(0)); // taken
        assertEquals(27, c.chest(3).size());
    }

    @Test
    void joinMakesOneLargeChestOfTwoBays() {
        ShipCargo c = new ShipCargo(4);
        c.install(0);
        c.install(1);
        assertTrue(c.canJoin(0));
        c.join(0);
        assertEquals(ShipCargo.Bay.LARGE_HEAD, c.bay(0));
        assertEquals(ShipCargo.Bay.LARGE_TAIL, c.bay(1));
        assertEquals(54, c.chest(0).size());
        assertSame(c.chest(0), c.chest(1));
        assertTrue(c.canSplit(0));
        c.split(0);
        assertEquals(ShipCargo.Bay.SMALL, c.bay(0));
        assertEquals(ShipCargo.Bay.SMALL, c.bay(1));
        assertEquals(27, c.chest(1).size());
    }

    @Test
    void onlyTheHeadsJoin() {
        ShipCargo c = new ShipCargo(4);
        for (int i = 0; i < 4; i++) c.install(i);
        assertFalse(c.canJoin(1)); // bays 1+2 cross the middle
        assertFalse(c.canJoin(3));
        assertTrue(c.canJoin(2));
    }

    @Test
    void twoLargeAndOneLargeWithTwoSmall() {
        ShipCargo c = new ShipCargo(4);
        for (int i = 0; i < 4; i++) c.install(i);
        c.join(0);
        c.join(2);
        assertEquals(ShipCargo.Bay.LARGE_TAIL, c.bay(3));
        c.split(2);
        assertEquals(ShipCargo.Bay.SMALL, c.bay(3));
    }

    @Test
    void joinNeedsBothBaysSmallAndUnlocked() {
        ShipCargo c = new ShipCargo(1);
        c.install(0);
        assertFalse(c.canJoin(0)); // bay 1 is locked
        ShipCargo d = new ShipCargo(4);
        d.install(0);
        assertFalse(d.canJoin(0)); // bay 1 empty
    }

    @Test
    void aChestWithItemsStaysAndAnEmptyOneComesOut() {
        ShipCargo c = new ShipCargo(4);
        c.install(0);
        assertTrue(c.canRemove(0));
        c.remove(0);
        assertEquals(ShipCargo.Bay.EMPTY, c.bay(0));
        assertNull(c.chest(0));
        c.install(0);
        c.chest(0).markUsedForTest(); // a chest that holds something
        assertFalse(c.canRemove(0));
        assertThrows(IllegalStateException.class, () -> c.remove(0));
        assertEquals(ShipCargo.Bay.SMALL, c.bay(0));
    }

    @Test
    void aLargeChestComesOutOnlyAfterSplit() {
        ShipCargo c = new ShipCargo(4);
        c.install(0);
        c.install(1);
        c.join(0);
        assertFalse(c.canRemove(0));
        assertFalse(c.canRemove(1));
    }

    @Test
    void aFullLargeChestCannotSplit() {
        ShipCargo c = new ShipCargo(4);
        c.install(0);
        c.install(1);
        c.join(0);
        c.chest(0).markUsedForTest();
        assertFalse(c.canSplit(0));
    }
}
```

Note: `Chest.markUsedForTest()` sets a flag that makes `isEmpty()` false without creating an `ItemStack` (the real
`isEmpty()` is "no items list yet, or every stack empty"). It is package-private.

- [ ] **Step 2: Run to verify it fails.** `cd fabric && ./test.sh --tests '*ShipCargoTest*'` — FAIL (class missing).
- [ ] **Step 3: Implement `ShipCargo`** (see the file in the commit; bays array of `Bay`, a `Chest[] chests` per bay with the
  large chest's object shared by head and tail; `capacity(t) = t<=0?0 : t==1?1 : t==2?2 : 4`).
- [ ] **Step 4: Run to verify it passes.**
- [ ] **Step 5: Commit** `ship cargo: ShipCargo, the hold's bays and their rules`.

### Task 2: Persist cargo in `ShipStore`

**Files:**
- Modify: `fabric/src/main/java/dev/moui/galaxycraft/ship/ShipStore.java` (a `Map<String, ShipCargo>`; `cargo(String id, int cabinTier)`; load/save `"cargo"`; `remove(id)` drops it; save skips cargo of unknown ids)
- Create: `fabric/src/main/java/dev/moui/galaxycraft/ship/CargoCodec.java` (bay states + items <-> `JsonObject`; items through a pluggable `ItemCodec` so tests use strings)
- Test: `fabric/src/test/java/dev/moui/galaxycraft/ship/ShipStoreTest.java` (add cases)

**Interfaces:**
- Consumes: `ShipCargo` (Task 1).
- Produces: `ShipStore.cargo(String shipId, int cabinTier): ShipCargo` (created empty on first call, grown if the tier rose);
  `CargoCodec.toJson(ShipCargo, ItemCodec)` / `CargoCodec.fromJson(JsonObject, int capacity, ItemCodec)`;
  `interface ItemCodec { JsonElement encode(ItemStack s); ItemStack decode(JsonElement e); }`; `ShipStore.setItemCodec(ItemCodec)` (static; the game sets the registry-aware one).

- [ ] Steps: failing round-trip test with bay states and no items (contents empty); a ship without `"cargo"` loads empty; `"cargo":"junk"` loads empty; save drops cargo of a removed ship; implement; run `./test.sh`; commit.

### Task 3: The menu (server side)

**Files:**
- Create: `fabric/src/main/java/dev/moui/galaxycraft/ship/ShipMenu.java`, `CargoView.java`
- Register: menu type in `ShipBlocks.register()`.

**Interfaces:**
- Consumes: `ShipCargo`, `ShipStore.cargo`.
- Produces: `ShipMenu` (`AbstractContainerMenu`): 4 bay slots (chest items only, stack of 1), 54 content slots over `CargoView`
  (inactive past the selected chest's size), 36 player slots; `clickMenuButton(player, id)` ids: 0..3 select bay, 4 join 0+1, 5 join 2+3,
  6 split 0+1, 7 split 2+3; a `Runnable onChanged` that marks the store dirty on the client thread.
  `static void open(ServerPlayer, String shipId)`.

- [ ] Steps: write `ShipCargoProbe` first part (opens the menu for a player, installs a chest by `clicked`, moves a stack in, closes);
  implement; run the probe (`-PgalaxycraftShipCargo`); commit.

### Task 4: Screen and opening

**Files:**
- Create: `fabric/src/client/java/dev/moui/galaxycraft/client/ShipScreen.java` (an `AbstractContainerScreen`)
- Modify: `ShipClient.java` (Shift + right click opens; E while aboard; aim hint), `GalaxyCraftClient.java` (register the screen and the start-of-tick E handler), lang file.

- [ ] Steps: implement; run the game probe; commit.

### Task 5: Chests drawn inside the ship

**Files:**
- Create: `fabric/src/main/java/dev/moui/galaxycraft/ship/CargoLayout.java` (+ `CargoLayoutTest`)
- Modify: `ShipClient.drawShip`, `EntityClient` (a `shipChest(Matrix4d, large, pt, out)` next to `shipCushion`).

- [ ] Steps: unit-test the layout (4 bays do not overlap, behind the cabin's last seat, a large chest spans two bays); implement; look at the result in the game (`tools/gxvoxel.sh ship` screenshots); commit.

### Task 6: Docs and roadmap

**Files:** `docs/NAVE.md` (Spanish guide: the hold), `ROADMAP.md` (Inbox -> Now, notes).

- [ ] Steps: update; commit.
