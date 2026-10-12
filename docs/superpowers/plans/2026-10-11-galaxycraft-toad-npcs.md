# Toad NPCs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Toads placed in the world that you can talk to (contextual guidance) and trade with, drawn with SMG2's `Kinopio.arc`.

**Architecture:** A Toad is a record kept in JSON and drawn through `EntityWire`, like a ship (not a Minecraft entity). The rules (types, offers, dialogue) are pure Java with no Minecraft types, so they are unit-tested on master. Drawing, interaction and placement are client/generator layers added on top.

**Tech Stack:** Java 25, Fabric (Minecraft 26.3), JUnit 5, Gson. Package `dev.moui.galaxycraft.toad`. Tests: `fabric/test.sh --tests 'dev.moui.galaxycraft.toad.*'`.

**Spec:** `docs/superpowers/specs/2026-10-11-galaxycraft-toad-npcs-design.md`

## Global Constraints

- Tier and star come only through the 3D map session's `SystemInfo` (`map/`), never recomputed here (spec, Coordination). Until it exists, `ToadContext` is filled by the caller.
- Do not change `SystemIndex`, `TierProfile`, `GalaxyMapScreen`, `Warp.map()`, `ShipRecord`, `ShipStore`.
- Do not edit `tools/space_galaxy.py` / UseResource without telling session galaxycraft-e3 first.
- Nothing from the disc goes into the repository (the launcher extracts it from the player's own `.rvz`).
- No AI-generated text at run time: dialogue is hand-written lang keys with arguments.
- Do not run gxplay/gxvoxel while another session uses it.

## Review Focus

- A damaged `toads.json` loses its Toads, not the world (`ToadStore.load` mirrors `ShipStore`).
- An unknown item id in an offer table fails the test, never sells at tier 0 by default.
- Tier above 5 or below 0 clamps, never throws.
- Dialogue always returns at least one line, whatever the context.
- A Toad of a type with no offers at a tier opens no empty trade screen (talks only).

## Base branch

Tasks 1 to 3 are pure and run on master (this branch). Tasks 4 to 6 need `feat/toad-ship` (`Rarc`, `BdlReader`, `ShipModels`, `ShipClient`) and Task 6 also `feat/planet-progression`. Stack the branch on them (or wait for their merge) before Task 4; the user decides which.

---

### Task 1: Types, item tiers and offers

**Files:**
- Create: `fabric/src/main/java/dev/moui/galaxycraft/toad/ToadType.java`
- Create: `fabric/src/main/java/dev/moui/galaxycraft/toad/ItemTiers.java`
- Create: `fabric/src/main/java/dev/moui/galaxycraft/toad/ToadOffers.java`
- Test: `fabric/src/test/java/dev/moui/galaxycraft/toad/ToadOffersTest.java`

**Interfaces:**
- Produces: `ToadType {GUIDE, MINER, EXPLORER, TECHNICIAN, MAILMAN}` with `String id()` and `String accessory()` (arc name, `null` if none); `ItemTiers.tier(String itemId) -> int` (`-1` unknown); `ToadOffers.Price(String item, int count)`; `ToadOffers.Offer(String sell, int sellCount, List<Price> prices, int tier)`; `ToadOffers.forType(ToadType, int tier) -> List<Offer>` (offers of that tier only; tier clamped to 0..5).

- [ ] **Step 1: Write the failing test**

```java
package dev.moui.galaxycraft.toad;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ToadOffersTest {
    @Test void nothingIsSoldAboveItsTierNorPaidAboveIt() {
        for (ToadType t : ToadType.values())
            for (int tier = 0; tier <= 5; tier++)
                for (ToadOffers.Offer o : ToadOffers.forType(t, tier)) {
                    assertEquals(tier, o.tier());
                    assertTrue(ItemTiers.tier(o.sell()) >= 0, "unknown item " + o.sell());
                    assertTrue(ItemTiers.tier(o.sell()) <= tier, o.sell() + " sold below its tier");
                    boolean near = false;
                    for (ToadOffers.Price p : o.prices()) {
                        int pt = ItemTiers.tier(p.item());
                        assertTrue(pt >= 0, "unknown item " + p.item());
                        assertTrue(pt <= tier, p.item() + " asked above tier " + tier);
                        near |= pt >= tier - 1;
                    }
                    assertTrue(near, "an offer must ask for something of its tier or the one before");
                }
    }

    @Test void everyTypeSellsSomethingAtTierZeroOrSaysNothing() {
        assertFalse(ToadOffers.forType(ToadType.GUIDE, 0).isEmpty());
        assertEquals(ToadOffers.forType(ToadType.GUIDE, 0), ToadOffers.forType(ToadType.GUIDE, -3));
        assertEquals(ToadOffers.forType(ToadType.MINER, 5), ToadOffers.forType(ToadType.MINER, 99));
    }

    @Test void anUnknownItemHasNoTier() {
        assertEquals(-1, ItemTiers.tier("minecraft:not_an_item"));
        assertEquals(0, ItemTiers.tier("minecraft:coal"));
        assertEquals(2, ItemTiers.tier("minecraft:diamond"));
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `fabric/test.sh --tests 'dev.moui.galaxycraft.toad.ToadOffersTest'`
Expected: FAIL, compilation error (`ToadType` not defined).

- [ ] **Step 3: Write the implementation**

`ToadType.java`:
```java
package dev.moui.galaxycraft.toad;

/** What a Toad is: where it lives and what it sells. Data rows, one class for all. */
public enum ToadType {
    GUIDE("guide", "KinopioGoodsGlasses"),
    MINER("miner", "KinopioGoodsPickel"),
    EXPLORER("explorer", "KinopioGoodsRucksack"),
    TECHNICIAN("technician", "KinopioGoodsSnorkel"),
    MAILMAN("mailman", "KinopioGoodsMailBag");

    private final String id;
    private final String accessory;

    ToadType(String id, String accessory) {
        this.id = id;
        this.accessory = accessory;
    }

    public String id() {
        return id;
    }

    /** The disc arc of its accessory (without .arc), or null. */
    public String accessory() {
        return accessory;
    }
}
```

`ItemTiers.java` (first draft from the progression spec's ore table, refined by playing):
```java
package dev.moui.galaxycraft.toad;

import java.util.Map;

/** The first tier an item is produced at (planet progression's ore table); -1 when unknown. */
public final class ItemTiers {
    private static final Map<String, Integer> TIERS = Map.ofEntries(
            Map.entry("minecraft:bread", 0), Map.entry("minecraft:coal", 0), Map.entry("minecraft:copper_ingot", 0),
            Map.entry("minecraft:cobblestone", 0), Map.entry("minecraft:oak_log", 0), Map.entry("minecraft:torch", 0),
            Map.entry("minecraft:iron_ingot", 1), Map.entry("minecraft:gold_ingot", 1), Map.entry("minecraft:redstone", 1),
            Map.entry("minecraft:lapis_lazuli", 1), Map.entry("minecraft:iron_pickaxe", 1), Map.entry("minecraft:compass", 1),
            Map.entry("minecraft:emerald", 2), Map.entry("minecraft:diamond", 2), Map.entry("minecraft:map", 1),
            Map.entry("minecraft:quartz", 3), Map.entry("minecraft:amethyst_shard", 3), Map.entry("minecraft:ancient_debris", 3),
            Map.entry("minecraft:netherite_scrap", 4));

    private ItemTiers() {}

    public static int tier(String itemId) {
        return TIERS.getOrDefault(itemId, -1);
    }
}
```

`ToadOffers.java`:
```java
package dev.moui.galaxycraft.toad;

import java.util.List;
import java.util.Map;

/** What each type sells at each tier and what it accepts. First-draft numbers: refined by playing. */
public final class ToadOffers {
    public record Price(String item, int count) {}

    /** One thing sold; any one of the prices pays for it. */
    public record Offer(String sell, int sellCount, List<Price> prices, int tier) {}

    private static final Map<ToadType, Map<Integer, List<Offer>>> TABLE = Map.of(
            ToadType.GUIDE, Map.of(
                    0, List.of(new Offer("minecraft:bread", 4, List.of(new Price("minecraft:coal", 4), new Price("minecraft:cobblestone", 16)), 0),
                            new Offer("minecraft:torch", 16, List.of(new Price("minecraft:coal", 2), new Price("minecraft:copper_ingot", 2)), 0))),
            ToadType.MINER, Map.of(
                    0, List.of(new Offer("minecraft:coal", 8, List.of(new Price("minecraft:cobblestone", 32), new Price("minecraft:copper_ingot", 2)), 0)),
                    1, List.of(new Offer("minecraft:iron_pickaxe", 1, List.of(new Price("minecraft:iron_ingot", 3), new Price("minecraft:gold_ingot", 2)), 1))),
            ToadType.EXPLORER, Map.of(
                    1, List.of(new Offer("minecraft:compass", 1, List.of(new Price("minecraft:iron_ingot", 2), new Price("minecraft:redstone", 8)), 1),
                            new Offer("minecraft:map", 1, List.of(new Price("minecraft:copper_ingot", 4), new Price("minecraft:iron_ingot", 1)), 1))),
            ToadType.TECHNICIAN, Map.of(
                    2, List.of(new Offer("minecraft:diamond", 1, List.of(new Price("minecraft:emerald", 4), new Price("minecraft:gold_ingot", 8)), 2))),
            ToadType.MAILMAN, Map.of(
                    3, List.of(new Offer("minecraft:amethyst_shard", 4, List.of(new Price("minecraft:diamond", 1), new Price("minecraft:emerald", 3)), 3))));

    private ToadOffers() {}

    /** The offers of a type at a tier (clamped to 0..5); empty when it sells nothing there. */
    public static List<Offer> forType(ToadType type, int tier) {
        int t = Math.max(0, Math.min(5, tier));
        return TABLE.getOrDefault(type, Map.of()).getOrDefault(t, List.of());
    }
}
```
Note: the second test pins that clamping 99 gives tier 5's offers; MINER has none at 5, so both sides are the empty list and the test passes. If a later task adds a tier-5 row it still passes.

- [ ] **Step 4: Run it to see it pass**

Run: `fabric/test.sh --tests 'dev.moui.galaxycraft.toad.ToadOffersTest'`
Expected: 3 tests pass. (If `MAILMAN` tier 3 "amethyst for diamond" breaks the "near" rule, the diamond is tier 2 = 3-1, so it holds.)

- [ ] **Step 5: Commit**

```bash
git add fabric/src/main/java/dev/moui/galaxycraft/toad fabric/src/test/java/dev/moui/galaxycraft/toad
git commit -m "toad: types, item tiers and balanced offers (pure rules)"
```

---

### Task 2: ToadRecord and ToadStore

**Files:**
- Create: `fabric/src/main/java/dev/moui/galaxycraft/toad/ToadRecord.java`
- Create: `fabric/src/main/java/dev/moui/galaxycraft/toad/ToadStore.java`
- Test: `fabric/src/test/java/dev/moui/galaxycraft/toad/ToadStoreTest.java`

**Interfaces:**
- Consumes: `ToadType` (Task 1).
- Produces: `ToadRecord(String id, ToadType type, double[] pos, double yaw, String stage)`; `ToadStore.load(Path) -> ToadStore`, `all() -> List<ToadRecord>`, `put(ToadRecord)`, `remove(String id) -> Optional<ToadRecord>`, `save() throws IOException`.

- [ ] **Step 1: Write the failing test**

```java
package dev.moui.galaxycraft.toad;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToadStoreTest {
    @TempDir Path dir;

    @Test void aToadSurvivesSaveAndLoad() throws IOException {
        Path f = dir.resolve("toads.json");
        ToadStore s = ToadStore.load(f);
        s.put(new ToadRecord("t1", ToadType.MINER, new double[] {1, 2, 3}, 0.5, "planet-a"));
        s.save();
        ToadRecord r = ToadStore.load(f).all().get(0);
        assertEquals("t1", r.id());
        assertEquals(ToadType.MINER, r.type());
        assertArrayEquals(new double[] {1, 2, 3}, r.pos());
        assertEquals(0.5, r.yaw());
        assertEquals("planet-a", r.stage());
    }

    @Test void putReplacesTheSameId() {
        ToadStore s = ToadStore.load(dir.resolve("none.json"));
        s.put(new ToadRecord("t", ToadType.GUIDE, new double[] {0, 0, 0}, 0, ""));
        s.put(new ToadRecord("t", ToadType.MINER, new double[] {0, 0, 0}, 0, ""));
        assertEquals(1, s.all().size());
        assertEquals(ToadType.MINER, s.all().get(0).type());
    }

    @Test void aDamagedFileLosesItsToadsNotTheWorld() throws IOException {
        Path f = dir.resolve("toads.json");
        Files.writeString(f, "{ not json");
        assertTrue(ToadStore.load(f).all().isEmpty());
    }

    @Test void anUnknownTypeIsSkippedNotFatal() throws IOException {
        Path f = dir.resolve("toads.json");
        Files.writeString(f, "[{\"id\":\"a\",\"type\":\"penguin\",\"pos\":[0,0,0],\"yaw\":0,\"stage\":\"\"},"
                + "{\"id\":\"b\",\"type\":\"guide\",\"pos\":[0,0,0],\"yaw\":0,\"stage\":\"\"}]");
        ToadStore s = ToadStore.load(f);
        assertEquals(1, s.all().size());
        assertEquals("b", s.all().get(0).id());
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `fabric/test.sh --tests 'dev.moui.galaxycraft.toad.ToadStoreTest'`
Expected: FAIL, compilation error (`ToadRecord` not defined).

- [ ] **Step 3: Write the implementation**

`ToadRecord.java`:
```java
package dev.moui.galaxycraft.toad;

/** A placed Toad: its type, where it is (universe units), the way it faces (radians about up) and its stage. */
public record ToadRecord(String id, ToadType type, double[] pos, double yaw, String stage) {}
```

`ToadStore.java`:
```java
package dev.moui.galaxycraft.toad;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The Toads of one galaxy, kept in a JSON file next to its planets (like ShipStore). */
public final class ToadStore {
    private final Path file;
    private final List<ToadRecord> toads = new ArrayList<>();

    private ToadStore(Path file) {
        this.file = file;
    }

    public static ToadStore load(Path file) {
        ToadStore s = new ToadStore(file);
        try {
            if (!Files.exists(file)) return s;
            JsonArray a = JsonParser.parseString(Files.readString(file)).getAsJsonArray();
            for (JsonElement e : a) {
                JsonObject o = e.getAsJsonObject();
                ToadType type = null;
                for (ToadType t : ToadType.values())
                    if (t.id().equals(o.get("type").getAsString())) type = t;
                if (type == null) continue; // a type this version does not know is skipped
                JsonArray p = o.getAsJsonArray("pos");
                s.toads.add(new ToadRecord(o.get("id").getAsString(), type,
                        new double[] {p.get(0).getAsDouble(), p.get(1).getAsDouble(), p.get(2).getAsDouble()},
                        o.get("yaw").getAsDouble(), o.has("stage") ? o.get("stage").getAsString() : ""));
            }
        } catch (IOException | RuntimeException e) {
            s.toads.clear(); // a damaged file loses its Toads, not the world
        }
        return s;
    }

    public List<ToadRecord> all() {
        return List.copyOf(toads);
    }

    public void put(ToadRecord r) {
        toads.removeIf(x -> x.id().equals(r.id()));
        toads.add(r);
    }

    public Optional<ToadRecord> remove(String id) {
        for (ToadRecord r : toads)
            if (r.id().equals(id)) {
                toads.remove(r);
                return Optional.of(r);
            }
        return Optional.empty();
    }

    public void save() throws IOException {
        JsonArray a = new JsonArray();
        for (ToadRecord r : toads) {
            JsonObject o = new JsonObject();
            o.addProperty("id", r.id());
            o.addProperty("type", r.type().id());
            JsonArray p = new JsonArray();
            for (double v : r.pos()) p.add(v);
            o.add("pos", p);
            o.addProperty("yaw", r.yaw());
            o.addProperty("stage", r.stage());
            a.add(o);
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, a.toString());
    }
}
```

- [ ] **Step 4: Run it to see it pass**

Run: `fabric/test.sh --tests 'dev.moui.galaxycraft.toad.ToadStoreTest'`
Expected: 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add fabric/src/main/java/dev/moui/galaxycraft/toad fabric/src/test/java/dev/moui/galaxycraft/toad
git commit -m "toad: ToadRecord and ToadStore (JSON, damaged file loses toads not the world)"
```

---

### Task 3: Contextual dialogue rules

**Files:**
- Create: `fabric/src/main/java/dev/moui/galaxycraft/toad/ToadContext.java`
- Create: `fabric/src/main/java/dev/moui/galaxycraft/toad/ToadDialogue.java`
- Test: `fabric/src/test/java/dev/moui/galaxycraft/toad/ToadDialogueTest.java`

**Interfaces:**
- Consumes: `ToadType` (Task 1).
- Produces: `ToadContext(int systemTier, String star, int warpCoreTier, String missingModule, boolean visited)` (`missingModule` may be null; `star` is a `StarType` name such as `yellow`, `red`, `lava`, `blue`); `ToadDialogue.Line(String key, List<Object> args)`; `ToadDialogue.lines(ToadType, ToadContext) -> List<Line>` (1 to 3 lines, never empty).

- [ ] **Step 1: Write the failing test**

```java
package dev.moui.galaxycraft.toad;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ToadDialogueTest {
    static ToadContext ctx(int tier, String star, int core, String missing, boolean visited) {
        return new ToadContext(tier, star, core, missing, visited);
    }

    @Test void neverEmptyNeverMoreThanThree() {
        for (ToadType t : ToadType.values())
            for (int tier = -1; tier <= 7; tier++)
                for (int core = -1; core <= 7; core++) {
                    var l = ToadDialogue.lines(t, ctx(tier, "yellow", core, null, false));
                    assertFalse(l.isEmpty());
                    assertTrue(l.size() <= 3);
                }
    }

    @Test void aWeakWarpCoreIsMentionedByTheGuide() {
        var l = ToadDialogue.lines(ToadType.GUIDE, ctx(3, "lava", 1, null, false));
        assertTrue(l.stream().anyMatch(x -> x.key().equals("toad.dialogue.warp_core_low")));
    }

    @Test void aMissingModuleIsNamed() {
        var l = ToadDialogue.lines(ToadType.TECHNICIAN, ctx(1, "yellow", 1, "engine", true));
        var m = l.stream().filter(x -> x.key().equals("toad.dialogue.missing_module")).findFirst();
        assertTrue(m.isPresent());
        assertEquals("engine", m.get().args().get(0));
    }

    @Test void theStarIsExplainedOnlyWhereItMatters() {
        var yellow = ToadDialogue.lines(ToadType.GUIDE, ctx(0, "yellow", 0, null, true));
        assertTrue(yellow.stream().noneMatch(x -> x.key().startsWith("toad.dialogue.star.")));
        var lava = ToadDialogue.lines(ToadType.GUIDE, ctx(2, "lava", 3, null, true));
        assertTrue(lava.stream().anyMatch(x -> x.key().equals("toad.dialogue.star.lava")));
    }

    @Test void sameContextSameWords() {
        var c = ctx(2, "red", 1, "cabin", false);
        assertEquals(ToadDialogue.lines(ToadType.MINER, c), ToadDialogue.lines(ToadType.MINER, c));
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `fabric/test.sh --tests 'dev.moui.galaxycraft.toad.ToadDialogueTest'`
Expected: FAIL, compilation error (`ToadContext` not defined).

- [ ] **Step 3: Write the implementation**

`ToadContext.java`:
```java
package dev.moui.galaxycraft.toad;

/**
 * What a Toad knows when you talk to it. The caller fills it (tier and star through the 3D map's
 * SystemInfo; the Warp Core and missing module from the ship). {@code missingModule} may be null.
 */
public record ToadContext(int systemTier, String star, int warpCoreTier, String missingModule, boolean visited) {}
```

`ToadDialogue.java`:
```java
package dev.moui.galaxycraft.toad;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** The lines a Toad says before the trade: lang keys with arguments, picked by rules, no AI. */
public final class ToadDialogue {
    /** A lang key and its arguments. */
    public record Line(String key, List<Object> args) {
        static Line of(String key, Object... args) {
            return new Line(key, List.of(args));
        }
    }

    private static final Set<String> STARS_WITH_NOTES = Set.of("red", "lava", "blue");

    private ToadDialogue() {}

    public static List<Line> lines(ToadType type, ToadContext c) {
        int tier = Math.max(0, Math.min(5, c.systemTier()));
        List<Line> out = new ArrayList<>();
        if (c.warpCoreTier() < tier && !c.visited()) out.add(Line.of("toad.dialogue.warp_core_low", tier, Math.max(0, c.warpCoreTier())));
        if (c.missingModule() != null) out.add(Line.of("toad.dialogue.missing_module", c.missingModule()));
        if (STARS_WITH_NOTES.contains(c.star())) out.add(Line.of("toad.dialogue.star." + c.star()));
        if (out.size() > 2) out = new ArrayList<>(out.subList(0, 2));
        out.add(Line.of("toad.dialogue." + type.id() + ".tier." + tier));
        return out;
    }
}
```

- [ ] **Step 4: Run it to see it pass**

Run: `fabric/test.sh --tests 'dev.moui.galaxycraft.toad.ToadDialogueTest'`
Expected: 5 tests pass.

- [ ] **Step 5: Add the lang entries and commit**

Add to the mod's `en_us.json` (find it with `find fabric/src -name en_us.json`) keys for `toad.dialogue.warp_core_low` ("This system asks for a Warp Core of tier %1$s; yours is tier %2$s."), `toad.dialogue.missing_module` ("Your ship lacks its %1$s."), `toad.dialogue.star.red` ("A red dwarf: dim and cold, bring warm gear."), `toad.dialogue.star.lava` ("An ember star: hot and volcanic."), `toad.dialogue.star.blue` ("A blue star: rare ores, and danger."), and `toad.dialogue.<type>.tier.<0..5>` for the five types (30 short lines, 1 sentence each, first draft). Copy `es_es.json` the same way if the project ships one.

```bash
git add -A fabric/src
git commit -m "toad: contextual dialogue rules and lang keys"
```

---

### Task 4: Drawing and interaction (needs feat/toad-ship)

**Files (exact names to confirm in Step 1):**
- Create: `fabric/src/main/java/dev/moui/galaxycraft/toad/model/ToadModels.java` (as `ShipModels`, for `Kinopio.arc`)
- Create: `fabric/src/client/java/dev/moui/galaxycraft/client/ToadClient.java`
- Modify: the place where `ShipClient` is initialised and its ships drawn.
- Test: `fabric/src/test/java/dev/moui/galaxycraft/toad/model/ToadModelsTest.java`

- [ ] **Step 1: Read, then record exact signatures**

Read in the stacked branch: `ship/model/ShipModels.java` (how a `Slot` is read from `Rarc` + `BdlReader` and turned into `EntityWire` quads and skins), `client/.../ShipClient.java` (`aimedShip`, how records become pieces sent to the game, `REACH`, `RADIUS`), `ship/ShipBoarding.java` (`nearest`). Write the signatures you will call at the top of `ToadClient` as a comment.

- [ ] **Step 2: Write the failing test**

Same style as `ShipModelsTest` (a fixture `.bdl` that is not from the disc): a Toad converts to pieces with the expected counts and every piece under `ShipModels.MAX_QUADS`.

- [ ] **Step 3: Implement `ToadModels`**

One slot `KINOPIO("Kinopio.arc", 16f /* pixels, about 1 block tall; tune by eye */, ...)` plus the type's accessory arc as a second piece. Static pose (the bind pose); no animation.

- [ ] **Step 4: Implement `ToadClient`**

Per tick: for each `ToadRecord` of the current stage, send its pieces like `ShipClient` sends a parked ship. Aim: `ToadBoarding`-like nearest by ray against sphere (reuse `ShipBoarding.nearest` if it is generic, else a 10-line copy). Right click on an aimed Toad calls `Trade.open(record)` (Task 5).

- [ ] **Step 5: Verify and commit**

Run unit tests, then `tools/gxvoxel.sh` probe (Task 7). Commit `toad: Kinopio drawn and aimed`.

---

### Task 5: Talking and trading

**Files:**
- Create: `fabric/src/client/java/dev/moui/galaxycraft/client/ToadTalk.java`

- [ ] **Step 1: Check what the game offers (small spike, 30 minutes max)**

In Minecraft 26.3, find out whether `MerchantMenu` can be opened for a non-entity `Merchant` (`Player.openMenu` with a `Merchant` implementation holding a `MerchantOffers`). Record the answer in a comment in `ToadTalk`. If it needs an entity, the fallback is a plain container screen of our own with the offers as buttons; say so to the user before building it.

- [ ] **Step 2: Build `MerchantOffers` from `ToadOffers.forType(type, tier)`**

For every `Offer` and every `Price` make one `MerchantOffer` (cost, result). Zero offers: show dialogue lines in chat only and open nothing.

- [ ] **Step 3: Chat the dialogue first**

Fill a `ToadContext` (tier and star from `SystemInfo`, the Warp Core and missing module from the ship's `ShipModules`), send each `ToadDialogue.Line` as a translated chat line.

- [ ] **Step 4: Commit**

`toad: talk then trade`

---

### Task 6: Placement (needs feat/planet-progression and feat/toad-ship)

**Files:** exact names found in Step 1.

- [ ] **Step 1: Find the three places**

Home planet creation, station creation (where `ShipClient` already places on stations: `PlanetSession.surfaceAlong`), and the structure table of planet progression (`TierProfile` structures column, villages from tier 1).

- [ ] **Step 2: Fixed Toads first**

On the home planet: one GUIDE next to the spawn, stored with a stable id (`toad-home`) so it is not placed twice. On each station: one TECHNICIAN (`toad-station-<stage>`).

- [ ] **Step 3: Villages**

When progression's structure placer plants a village, add a mushroom house and a MINER, EXPLORER or MAILMAN chosen by the structure's seeded random (same seed, same Toad). Ids are `toad-<stage>-<index>`.

- [ ] **Step 4: Test and commit**

Unit test: the same seed and planet give the same Toad ids and types. Commit `toad: placed on the home planet, stations and villages`.

---

### Task 7: Probe, docs, roadmap

- [ ] **Step 1:** `ToadProbe` in the style of `ShipProbe`: place a Toad, aim at it, talk, trade, assert it is drawn. Run only when no other session uses gxplay.
- [ ] **Step 2:** `docs/TOADS.md` (what you can do, one screen).
- [ ] **Step 3:** ROADMAP: move the Inbox entry to its theme; public doc and `#roadmap` when merged. No devlog before the user has playtested.
- [ ] **Step 4:** Commit `docs: Toad NPCs`.
