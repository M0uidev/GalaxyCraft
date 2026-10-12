# Toad dialogue box Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace a Toad's chat lines with an in-HUD dialogue box (typewriter text, talk sound, choices) that never stops the player.

**Architecture:** A pure conversation state machine and script builder in `toad/`, a thin client layer (`ToadDialogueHud`, `ToadVoice`, input in `ToadClient`) that draws and drives it as a HUD element, and the existing `ToadMerchant` for Trade.

**Tech Stack:** Java 25, Fabric API HudElementRegistry (fabric-rendering-v1), JUnit 5, lang keys in `en_us.json`.

**Spec:** `docs/superpowers/specs/2026-10-12-galaxycraft-toad-dialogue-design.md`

## Global Constraints

- Not a `Screen`: the mouse stays grabbed, movement is never blocked.
- Closes at more than 8 blocks from the Toad or when any screen opens.
- Pure classes use no Minecraft types; strings are lang keys resolved on the client.
- One conversation at a time; branch `feat/toad-npcs`; no merge/push/Discord; no Dolphin until told.

## Review Focus

- A Toad that sells nothing (no merchant): the Trade choice is absent, not a dead end.
- Pressing a number key for a choice that does not exist (no Trade): ignored.
- Text keys missing in a language: the key itself shows; a test pins every key in en_us.json.
- The player leaves and returns within one frame / the Toad is removed while talking: the box closes.
- Fast clicking: advance never skips a choice screen or loops.

---

### Task 1: ToadConversation (the state machine)

**Files:**
- Create: `fabric/src/main/java/dev/moui/galaxycraft/toad/ToadConversation.java`
- Test: `fabric/src/test/java/dev/moui/galaxycraft/toad/ToadConversationTest.java`

**Interfaces:**
- Produces: `ToadConversation(ToadType type, List<Page> pages, List<Choice> choices)`; `record Page(String key, List<Object> args)`; `enum Choice { TRADE, PLACE, ADVICE, BYE }`; `enum Outcome { NONE, TRADE, CLOSED }`; methods `void tick(double seconds)`, `int shown(int pageLength)` is internal; `String visible(String fullText)`, `boolean typing(String fullText)`, `void advance(int fullLength)`, `boolean choosing()`, `int highlight()`, `void move(int delta)`, `Outcome choose(int index)`, `Outcome confirm()`, `Page page()`, `List<Choice> choices()`, `Outcome outcome()`, `void add(List<Page> more)` (pages for PLACE/ADVICE), `static final double CPS = 30`.

The conversation does not know texts (lang keys only): the client passes the resolved text length
(`fullLength`) when advancing, and asks `visibleChars(fullLength)` for the typewriter.

- [ ] **Step 1: Write the failing test**

```java
package dev.moui.galaxycraft.toad;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToadConversationTest {
    private static ToadConversation talk(boolean trade) {
        var choices = trade
                ? List.of(ToadConversation.Choice.TRADE, ToadConversation.Choice.PLACE, ToadConversation.Choice.ADVICE, ToadConversation.Choice.BYE)
                : List.of(ToadConversation.Choice.PLACE, ToadConversation.Choice.ADVICE, ToadConversation.Choice.BYE);
        return new ToadConversation(ToadType.GUIDE, List.of(
                new ToadConversation.Page("a", List.of()), new ToadConversation.Page("b", List.of())), choices);
    }

    @Test void lettersAppearOverTime() {
        ToadConversation c = talk(true);
        assertEquals(0, c.visibleChars(100));
        c.tick(1.0);
        assertEquals((int) ToadConversation.CPS, c.visibleChars(100));
        c.tick(10);
        assertEquals(100, c.visibleChars(100)); // never past the text
    }

    @Test void advanceFinishesTheTypingThenPages() {
        ToadConversation c = talk(true);
        c.advance(50);
        assertEquals(50, c.visibleChars(50));
        assertEquals("a", c.page().key());
        c.advance(50);
        assertEquals("b", c.page().key());
        assertEquals(0, c.visibleChars(50)); // the next page starts typing again
    }

    @Test void afterTheLastPageTheChoicesShow() {
        ToadConversation c = talk(true);
        c.advance(10);
        c.advance(10);
        c.advance(10);
        c.advance(10);
        assertTrue(c.choosing());
        c.advance(10); // not a way out: still choosing
        assertTrue(c.choosing());
    }

    @Test void choicesByNumberAndByHighlight() {
        ToadConversation c = talk(true);
        for (int i = 0; i < 4; i++) c.advance(10);
        assertEquals(ToadConversation.Outcome.TRADE, c.choose(0));
        ToadConversation d = talk(true);
        for (int i = 0; i < 4; i++) d.advance(10);
        d.move(3);
        assertEquals(3, d.highlight());
        d.move(1); // wraps
        assertEquals(0, d.highlight());
        d.move(-1);
        assertEquals(ToadConversation.Outcome.CLOSED, d.confirm()); // highlight 3 = Bye
    }

    @Test void aNumberWithoutAChoiceIsIgnored() {
        ToadConversation c = talk(false);
        for (int i = 0; i < 4; i++) c.advance(10);
        assertEquals(ToadConversation.Outcome.NONE, c.choose(3));
        assertEquals(ToadConversation.Outcome.NONE, c.choose(-1));
    }

    @Test void placeAndAdviceAddPagesThenComeBackToTheChoices() {
        ToadConversation c = talk(true);
        for (int i = 0; i < 4; i++) c.advance(10);
        assertEquals(ToadConversation.Outcome.NONE, c.choose(1)); // Tell me about this place
        assertEquals(ToadConversation.Choice.PLACE, c.lastAsked());
        c.add(List.of(new ToadConversation.Page("place", List.of())));
        assertFalse(c.choosing());
        assertEquals("place", c.page().key());
        c.advance(5);
        c.advance(5);
        assertTrue(c.choosing());
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `cd fabric && ./test.sh --tests '*ToadConversationTest'` — Expected: compile error, class missing.

- [ ] **Step 3: Implement** `ToadConversation` as the interface above: fields `pages` (mutable list), `index`, `elapsed` (seconds into the current page), `choosing`, `highlight`, `lastAsked`, `outcome`. `tick(s)` adds to `elapsed`; `visibleChars(n) = min(n, (int)(elapsed*CPS))`; `advance(n)`: if `visibleChars(n) < n` set `elapsed = n / CPS + 1`; else if `index + 1 < pages.size()` `index++`, `elapsed = 0`; else `choosing = true`, `highlight = 0`. `choose(i)`: ignored unless `choosing` and `0 <= i < choices.size()`; TRADE -> `outcome = TRADE`, BYE -> `CLOSED`, PLACE/ADVICE -> `lastAsked` set, `choosing = false`, outcome NONE (the caller then `add`s pages). `add(pages)` appends and sets `index` to the first new page, `elapsed = 0`, `choosing = false`. After the last of the added pages `advance` sets `choosing = true` again. `confirm()` = `choose(highlight)`. `move(d)` wraps in the choices size.

- [ ] **Step 4: Run to verify it passes**, then commit `toad: ToadConversation, the dialogue's state machine`.

---

### Task 2: ToadScript (what a Toad says and offers)

**Files:**
- Create: `fabric/src/main/java/dev/moui/galaxycraft/toad/ToadScript.java`
- Modify: `fabric/src/main/resources/assets/galaxycraft/lang/en_us.json` (keys below)
- Test: `fabric/src/test/java/dev/moui/galaxycraft/toad/ToadScriptTest.java`

**Interfaces:**
- Consumes: `ToadDialogue.lines(type, ctx)`, `ToadContext`, `ToadConversation`.
- Produces: `static ToadConversation open(ToadType type, ToadContext ctx, boolean canTrade)`; `static List<ToadConversation.Page> place(ToadType type, ToadContext ctx)`; `static List<ToadConversation.Page> advice(ToadType type, ToadContext ctx)`; `static final int PAGE_CHARS = 140` (the longest text a page may have; a test keeps the lang file to it).

New lang keys (en_us.json): `toad.choice.trade` "Trade", `toad.choice.place` "Tell me about this place", `toad.choice.advice` "Where should I go?", `toad.choice.bye` "Bye", `toad.dialogue.place.tier.0..5` (what this ring is like), `toad.dialogue.place.visited` / `toad.dialogue.place.new`, `toad.dialogue.<type>.advice.0..5` (one next step per type and tier), `toad.dialogue.bye` "Safe travels!", `toad.dialogue.advice.module` "Build the %1$s for your ship first.", and the box's `toad.hud.next` "Click to continue", `toad.hud.pick` "1-4 to choose, or Up/Down and Enter".

- [ ] **Step 1: Write the failing tests** (`ToadScriptTest`):

```java
@Test void everyTypeAndTierHasAGreetingPlaceAndAdvice() {
    for (ToadType t : ToadType.values())
        for (int tier = 0; tier <= 5; tier++) {
            ToadContext ctx = ToadContext.forSystem(tier, dev.moui.galaxycraft.universe.StarType.YELLOW);
            assertFalse(ToadScript.open(t, ctx, true).page().key().isEmpty());
            assertFalse(ToadScript.place(t, ctx).isEmpty());
            assertFalse(ToadScript.advice(t, ctx).isEmpty());
        }
}

@Test void noTradeNoTradeChoice() {
    var c = ToadScript.open(ToadType.GUIDE, ToadContext.home(), false);
    assertFalse(c.choices().contains(ToadConversation.Choice.TRADE));
    assertTrue(ToadScript.open(ToadType.GUIDE, ToadContext.home(), true).choices().contains(ToadConversation.Choice.TRADE));
}

@Test void everyKeyUsedIsInTheLangFileAndFitsAPage() throws Exception {
    var lang = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/resources/assets/galaxycraft/lang/en_us.json"))).getAsJsonObject();
    for (ToadType t : ToadType.values())
        for (int tier = 0; tier <= 5; tier++)
            for (var star : dev.moui.galaxycraft.universe.StarType.values()) {
                ToadContext ctx = ToadContext.forSystem(tier, star);
                var pages = new java.util.ArrayList<ToadConversation.Page>();
                var open = ToadScript.open(t, ctx, true);
                pages.addAll(open.pages());
                pages.addAll(ToadScript.place(t, ctx));
                pages.addAll(ToadScript.advice(t, ctx));
                for (var p : pages) {
                    assertTrue(lang.has(p.key()), "missing " + p.key());
                    assertTrue(lang.get(p.key()).getAsString().length() <= ToadScript.PAGE_CHARS, p.key());
                }
            }
    for (var c : ToadConversation.Choice.values()) assertTrue(lang.has("toad.choice." + c.name().toLowerCase()));
}
```

- [ ] **Step 2: Run to verify they fail.**
- [ ] **Step 3: Implement**: `open` = `ToadDialogue.lines(...)` mapped to pages, choices `[TRADE if canTrade, PLACE, ADVICE, BYE]`. `place` = star note (`toad.dialogue.star.<star>` if red/lava/blue) + `toad.dialogue.place.tier.<tier>` + `visited ? place.visited : place.new`. `advice` = `toad.dialogue.<type>.advice.<tier>` + `advice.module` when `missingModule != null`. Write the 5 types x 6 tiers advice lines and the 6 place lines in en_us.json (short: under 140 chars; first drafts, tuned by playing).
- [ ] **Step 4: Run to verify they pass**; commit `toad: ToadScript, greeting, place and advice per type and tier`.

---

### Task 3: ToadVoice (the talk sound's seam)

**Files:**
- Create: `fabric/src/main/java/dev/moui/galaxycraft/toad/ToadVoicePlan.java` (pure: which letters make a sound and their pitch)
- Create: `fabric/src/client/java/dev/moui/galaxycraft/client/ToadVoice.java` (plays it)
- Test: `fabric/src/test/java/dev/moui/galaxycraft/toad/ToadVoicePlanTest.java`

**Interfaces:**
- Produces: `ToadVoicePlan.blip(char c, int index)` -> `boolean`; `ToadVoicePlan.pitch(ToadType type, char c)` -> `float` in [0.8, 1.9]; client `ToadVoice.speak(ToadType type, char c, int index)` (the single seam).

- [ ] **Step 1: Failing tests**: spaces and punctuation never blip; letters blip on every second index; `pitch` is within [0.8, 1.9], differs between a Miner and an Explorer for the same letter, and is the same for the same input.
- [ ] **Step 2: Run and see fail.** - [ ] **Step 3: Implement** `blip(c, i) = Character.isLetterOrDigit(c) && i % 2 == 0`; `pitch = 0.8f + (type.ordinal() * 0.07f + (Character.toLowerCase(c) % 13) * 0.065f)` clamped. `ToadVoice.speak` plays `SoundEvents.NOTE_BLOCK_BIT`? Use a villager sound: `Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.VILLAGER_AMBIENT, pitch, 0.5f))` guarded by `blip`, with a comment that this is the place to play the disc's SvKinopio clips when exported.
- [ ] **Step 4: Run, commit** `toad: ToadVoice, a pitched sound per letter behind one method`.

---

### Task 4: The HUD box, input and the walk-away close (client)

**Files:**
- Create: `fabric/src/client/java/dev/moui/galaxycraft/client/ToadDialogueHud.java`
- Modify: `fabric/src/client/java/dev/moui/galaxycraft/client/ToadClient.java` (active conversation, per-frame tick, input, close rules)
- Modify: `fabric/src/client/java/dev/moui/galaxycraft/client/ToadTalk.java` (open the box instead of chat; keep the trade opening for the TRADE outcome)
- Modify: `fabric/src/client/java/dev/moui/galaxycraft/client/GalaxyCraftClient.java` (register the HUD element)

**Interfaces:**
- Consumes: `ToadScript.open/place/advice`, `ToadConversation`, `ToadVoice.speak`, `ToadMerchant`.
- Produces: `ToadClient.talking()` -> `ToadRecord` or null (the Toad being spoken to); `ToadClient.conversation()` -> `ToadConversation` or null; `ToadClient.endConversation()`.

- [ ] **Step 1: Drive it from the pure parts.** In `ToadClient`: fields `ToadConversation conv; ToadRecord with; int slot; int shownBlips; long lastNs`. `talked(toad)` (today's click) now calls `ToadTalk.talk(player, toad)`, which resolves a `ToadContext` (existing `context()`), builds `ToadScript.open(type, ctx, merchant != null)` and stores it (replacing an open one). It no longer sends chat lines.
- [ ] **Step 2: Per frame (`pieces()` already runs each frame)**: `conv.tick(dt)`; resolve the current page's text with `I18n.get(key, args)`; for each newly visible letter call `ToadVoice.speak(type, ch, index)`; keep the Toad `talkUntil = now + 1 s` while typing so it keeps animating and turning.
- [ ] **Step 3: Input** (`ToadClient.tick`, the same place the right click is read): while `conv != null`, right click or Enter -> `conv.advance(len)` / `conv.confirm()`; keys 1-4 via GLFW (`InputConstants.isKeyDown(window, GLFW_KEY_1 + i)`, edge-detected) -> `conv.choose(i)`; Up/Down -> `conv.move(+-1)`. Restore the hotbar slot saved at open every tick. Outcome TRADE -> end the conversation then open the merchant exactly as `ToadTalk` does today; CLOSED -> end. PLACE/ADVICE -> `conv.add(ToadScript.place/advice(...))`.
- [ ] **Step 4: Close rules** each tick: the Toad no longer exists, the player is farther than 8 blocks (`REACH` is 6, close at 8), any `Minecraft.screen != null`, or the world changed -> `endConversation()`.
- [ ] **Step 5: `ToadDialogueHud`**: `HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, id, (graphics, tickCounter) -> ...)`, draws a dark translucent box 3/4 of the width, 56 px high, 40 px above the bottom: a 40 px square in the Toad's cap colour (`ToadLook`) as the portrait slot, the Toad's name (`ToadMerchant.title`), the typed text wrapped (`font.split`), then either "Click to continue" or the numbered choices with the highlighted one marked.
- [ ] **Step 6: Compile and run all tests** (`cd fabric && JAVA_HOME=... ./gradlew compileClientJava && ./test.sh`) and commit `toad: a dialogue box on the HUD, typed with a sound, choices, the player keeps moving`.

---

### Task 5: Probe and docs

**Files:**
- Modify: `fabric/src/gametest/java/dev/moui/galaxycraft/gametest/ToadDolphinProbe.java` (the talk step now: box open, letters grow, choose 1 -> merchant screen; walk away -> closed; `ctx.takeScreenshot("toad-dialogue")`)
- Modify: `docs/TOADS.md` (how to talk now, controls)

- [ ] **Step 1:** Replace the "right click on the Toad talks" assertions: `ToadClient.conversation() != null`, after 1 s `visibleChars > 0`, keypress 1 (TRADE) -> `MerchantMenu` open; second run: choose 2 -> a place page; then move 20 blocks away -> `conversation() == null`.
- [ ] **Step 2:** Run only when the Dolphin slot is given (`tools/gxvoxel.sh toad`), read `toad-dialogue`, commit `toad: probe covers the dialogue box` and update docs.
