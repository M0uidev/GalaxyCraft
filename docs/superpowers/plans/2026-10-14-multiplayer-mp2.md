# Multiplayer MP-2 (shared planet edits) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Break/place on a catalog planet is shared between the host and guests, host-authoritative, efficient, and saved on the host.

**Architecture:** Pure, unit-tested core in `fabric/src/main/java/dev/moui/galaxycraft/net` (`EditCodec` wire format, `EditHub` server-side subscriptions and queues, `EditSync` guest reconcile). `VoxelPlanet` gets change observers. Thin Fabric glue: `MpServer` (relay), `HostPlanets` (host authority + residents), `MpEdits` (guest). Protocol 2 -> 3.

**Tech Stack:** Java 21, Fabric payloads, JUnit 5, `./gradlew test --offline -q`; MpProbe (`./gradlew runClientGameTest -PgalaxycraftMp -PgalaxycraftHidden --offline`).

**Spec:** `docs/superpowers/specs/2026-10-14-galaxycraft-multiplayer-mp2-design.md`

## Global Constraints

- `Protocol.VERSION` = 3. Hello's first fields stay frozen.
- Bounds on read: <= 2048 entries and <= 64 defs per message, <= 4096 wire ids per palette, name <= 160 chars, inflated <= 256 KB, wire <= 64 KB; 5 malformed in a row disconnect the sender.
- Edit budget 16 KB/tick per guest, drained before planet chunks (total `PlanetXfer.BUDGET_PER_TICK` = 64 KB); edit queue over 8 MB -> `Resync`.
- Guest -> host: 20 messages/s, 64 KB/s. At most 8 residents on the host.
- Guests never save planets (`MpClient.connected()` guards stay) and never run fluids/leaf decay on host planets.
- Windowed tests always `-PgalaxycraftHidden`; `pkill -f` only on script files, never on a pattern in your own command line.
- No merge, no devlog before the user has tried it (memory `merge-after-playtest`).
- Commit trailer: `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`. Work in worktree `/home/moui/Documents/Games/GalaxyCraft-multiplayer2` (branch `feat/multiplayer-2`).

## Review Focus

- Two players change the same cell in the same tick: both guests and the host end identical (host order wins), no flicker on the author.
- Edit arrives while the planet is still downloading: applied after the planet, never lost, never applied before it.
- Guest sends names of blocks the host does not know / cell out of range / planet not held: host answers a correction, nothing crashes.
- Host quits and reopens: guest work on a planet the host never visited is in the host's save.
- A guest on a slow link during a big flood or tree decay: bounded memory, `Resync`, no unbounded queue.

## File Structure

- Create `net/EditCodec.java` (wire format), `net/EditHub.java` (server subscriptions/queues), `net/EditSync.java` (guest reconcile) + tests in `src/test/.../net/`.
- Modify `net/MpPayloads.java`, `net/Protocol.java`, `net/MpServer.java`.
- Modify `voxel/VoxelPlanet.java` (observers), `voxel/PlanetSession.java` (follower mode, scope, `applyShared`).
- Create `client/HostPlanets.java`, `client/MpEdits.java`; modify `client/GalaxyStream.java`, `client/PlanetClient.java`, `client/MpPlanets.java`, `client/MpClient.java`, `client/MpPlanetHost.java`.
- Modify gametest `MpProbe`; docs `docs/MULTIJUGADOR.md`, `ROADMAP.md`.

---

### Task 1: EditCodec (wire format)

**Files:**
- Create: `fabric/src/main/java/dev/moui/galaxycraft/net/EditCodec.java`
- Test: `fabric/src/test/java/dev/moui/galaxycraft/net/EditCodecTest.java`

**Interfaces:**
- Produces: `EditCodec.Msg(int planet, int seq, int origin, List<Def> defs, int[] cells, int[] ids)`; `EditCodec.Def(int wireId, String name)`; `static byte[] encode(Msg)`; `static Msg decode(byte[]) throws EditCodec.Bad`; `Msg.withOrigin(int)`, `Msg.withDefs(List<Def>)`; constants `MAX_ENTRIES`, `MAX_DEFS`, `MAX_NAME`, `MAX_WIRE_IDS`, `MAX_INFLATED`, `MAX_WIRE`. `encode` sorts entries by cell (last one wins on a repeated cell).

- [ ] **Step 1: Write the failing tests**

```java
package dev.moui.galaxycraft.net;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class EditCodecTest {
    static EditCodec.Msg msg(int[] cells, int[] ids) {
        return new EditCodec.Msg(3, 17, 5, List.of(new EditCodec.Def(1, "minecraft:stone"), new EditCodec.Def(2, "minecraft:oak_log[axis=y]")), cells, ids);
    }

    @Test
    void roundTripsAndSortsByCell() throws Exception {
        var out = EditCodec.decode(EditCodec.encode(msg(new int[] {900, 5, 70000}, new int[] {1, 2, 1})));
        assertEquals(3, out.planet());
        assertEquals(17, out.seq());
        assertEquals(5, out.origin());
        assertArrayEquals(new int[] {5, 900, 70000}, out.cells());
        assertArrayEquals(new int[] {2, 1, 1}, out.ids());
        assertEquals("minecraft:oak_log[axis=y]", out.defs().get(1).name());
    }

    @Test
    void aRepeatedCellKeepsTheLastValue() throws Exception {
        var out = EditCodec.decode(EditCodec.encode(msg(new int[] {10, 10}, new int[] {1, 2})));
        assertArrayEquals(new int[] {10}, out.cells());
        assertArrayEquals(new int[] {2}, out.ids());
    }

    @Test
    void aSingleBreakIsSmall() {
        assertTrue(EditCodec.encode(new EditCodec.Msg(0, 1, 0, List.of(), new int[] {123456}, new int[] {0})).length < 16);
    }

    @Test
    void bigBatchesAreDeflated() throws Exception {
        int n = 2000;
        int[] cells = new int[n], ids = new int[n];
        for (int i = 0; i < n; i++) {
            cells[i] = 1000 + i;
            ids[i] = 1;
        }
        byte[] wire = EditCodec.encode(msg(cells, ids));
        assertTrue(wire.length < n, "deflated: " + wire.length);
        assertEquals(n, EditCodec.decode(wire).cells().length);
    }

    @Test
    void garbageAndTruncationAreRefused() {
        Random r = new Random(7);
        for (int i = 0; i < 300; i++) {
            byte[] junk = new byte[r.nextInt(80)];
            r.nextBytes(junk);
            try {
                EditCodec.decode(junk); // may happen to be valid; must never throw anything else
            } catch (EditCodec.Bad expected) {
            }
        }
        byte[] ok = EditCodec.encode(msg(new int[] {1, 2, 3}, new int[] {1, 1, 2}));
        for (int cut = 0; cut < ok.length; cut++) {
            byte[] part = java.util.Arrays.copyOf(ok, cut);
            assertThrows(EditCodec.Bad.class, () -> EditCodec.decode(part), "cut at " + cut);
        }
        assertThrows(EditCodec.Bad.class, () -> EditCodec.decode(new byte[EditCodec.MAX_WIRE + 1]));
    }

    @Test
    void boundsAreEnforced() {
        int[] cells = new int[EditCodec.MAX_ENTRIES + 1], ids = new int[EditCodec.MAX_ENTRIES + 1];
        for (int i = 0; i < cells.length; i++) cells[i] = i;
        assertThrows(IllegalArgumentException.class, () -> EditCodec.encode(msg(cells, ids)));
        assertThrows(IllegalArgumentException.class, () -> EditCodec.encode(new EditCodec.Msg(0, 1, 0,
                List.of(new EditCodec.Def(1, "x".repeat(EditCodec.MAX_NAME + 1))), new int[] {1}, new int[] {1})));
        assertThrows(IllegalArgumentException.class, () -> EditCodec.encode(new EditCodec.Msg(0, 1, 0,
                List.of(new EditCodec.Def(EditCodec.MAX_WIRE_IDS, "a")), new int[] {1}, new int[] {1})));
    }

    @Test
    void aBombThatInflatesHugeIsRefused() throws Exception {
        var o = new java.io.ByteArrayOutputStream();
        o.write(1);
        var d = new java.util.zip.DeflaterOutputStream(o);
        d.write(new byte[EditCodec.MAX_INFLATED + 1000]);
        d.close();
        assertThrows(EditCodec.Bad.class, () -> EditCodec.decode(o.toByteArray()));
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `cd /home/moui/Documents/Games/GalaxyCraft-multiplayer2 && ./gradlew test --offline -q --tests '*EditCodecTest'`
Expected: FAIL (compile error: EditCodec missing)

- [ ] **Step 3: Implement**

```java
package dev.moui.galaxycraft.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * The body of an Edits message: cell changes of one planet as (cell delta, wire id) pairs, the names of
 * wire ids the receiver may not know yet, deflated when it pays. Pure: no Minecraft. Every size is bounded on read.
 */
public final class EditCodec {
    public static final int MAX_ENTRIES = 2048, MAX_DEFS = 64, MAX_NAME = 160, MAX_WIRE_IDS = 4096;
    public static final int MAX_INFLATED = 256 * 1024, MAX_WIRE = 64 * 1024, DEFLATE_OVER = 128;

    private EditCodec() {}

    public record Def(int wireId, String name) {}

    /** planet: catalog index; seq: the host's sequence (0 from a guest); origin: the author's batch number (guest -> host: its own). */
    public record Msg(int planet, int seq, int origin, List<Def> defs, int[] cells, int[] ids) {
        public Msg withOrigin(int o) {
            return o == origin ? this : new Msg(planet, seq, o, defs, cells, ids);
        }

        public Msg withDefs(List<Def> d) {
            return new Msg(planet, seq, origin, d, cells, ids);
        }
    }

    public static final class Bad extends IOException {
        Bad(String why) {
            super(why);
        }
    }

    public static byte[] encode(Msg m) {
        int n = m.cells().length;
        if (n != m.ids().length || n > MAX_ENTRIES || m.defs().size() > MAX_DEFS) throw new IllegalArgumentException("too many entries or defs");
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Integer.compare(m.cells()[a], m.cells()[b])); // stable: the last of a cell stays last
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        varint(body, m.planet());
        varint(body, m.seq());
        varint(body, m.origin());
        varint(body, m.defs().size());
        for (Def d : m.defs()) {
            if (d.wireId() < 0 || d.wireId() >= MAX_WIRE_IDS) throw new IllegalArgumentException("wire id " + d.wireId());
            byte[] name = d.name().getBytes(StandardCharsets.UTF_8);
            if (name.length > MAX_NAME) throw new IllegalArgumentException("name too long");
            varint(body, d.wireId());
            varint(body, name.length);
            body.writeBytes(name);
        }
        int kept = 0;
        for (int i = 0; i < n; i++) if (i + 1 == n || m.cells()[order[i]] != m.cells()[order[i + 1]]) kept++;
        varint(body, kept);
        int last = 0;
        for (int i = 0; i < n; i++) {
            if (i + 1 < n && m.cells()[order[i]] == m.cells()[order[i + 1]]) continue;
            int cell = m.cells()[order[i]];
            if (m.ids()[order[i]] < 0 || m.ids()[order[i]] >= MAX_WIRE_IDS) throw new IllegalArgumentException("wire id");
            varint(body, cell - last);
            varint(body, m.ids()[order[i]]);
            last = cell;
        }
        byte[] raw = body.toByteArray();
        if (raw.length <= DEFLATE_OVER) return concat((byte) 0, raw);
        Deflater def = new Deflater(Deflater.BEST_SPEED);
        def.setInput(raw);
        def.finish();
        ByteArrayOutputStream z = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!def.finished()) z.write(buf, 0, def.deflate(buf));
        def.end();
        byte[] packed = z.toByteArray();
        return packed.length < raw.length ? concat((byte) 1, packed) : concat((byte) 0, raw);
    }

    public static Msg decode(byte[] wire) throws Bad {
        if (wire.length < 2 || wire.length > MAX_WIRE) throw new Bad("size " + wire.length);
        byte[] raw;
        if (wire[0] == 0) raw = Arrays.copyOfRange(wire, 1, wire.length);
        else if (wire[0] == 1) raw = inflate(wire);
        else throw new Bad("flag");
        int[] at = {0};
        int planet = varint(raw, at), seq = varint(raw, at), origin = varint(raw, at);
        int nd = varint(raw, at);
        if (nd > MAX_DEFS) throw new Bad("defs");
        List<Def> defs = new ArrayList<>(nd);
        for (int i = 0; i < nd; i++) {
            int id = varint(raw, at), len = varint(raw, at);
            if (id >= MAX_WIRE_IDS || len > MAX_NAME || at[0] + len > raw.length) throw new Bad("def");
            defs.add(new Def(id, new String(raw, at[0], len, StandardCharsets.UTF_8)));
            at[0] += len;
        }
        int n = varint(raw, at);
        if (n > MAX_ENTRIES) throw new Bad("entries");
        int[] cells = new int[n], ids = new int[n];
        long cell = 0;
        for (int i = 0; i < n; i++) {
            cell += varint(raw, at);
            int id = varint(raw, at);
            if (cell > Integer.MAX_VALUE || id >= MAX_WIRE_IDS) throw new Bad("entry");
            cells[i] = (int) cell;
            ids[i] = id;
        }
        if (at[0] != raw.length) throw new Bad("trailing bytes");
        return new Msg(planet, seq, origin, defs, cells, ids);
    }

    private static byte[] inflate(byte[] wire) throws Bad {
        Inflater inf = new Inflater();
        inf.setInput(wire, 1, wire.length - 1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        try {
            while (!inf.finished()) {
                int got = inf.inflate(buf);
                if (got == 0 && (inf.needsInput() || inf.needsDictionary())) throw new Bad("truncated");
                out.write(buf, 0, got);
                if (out.size() > MAX_INFLATED) throw new Bad("inflates too big");
            }
        } catch (DataFormatException e) {
            throw new Bad("not deflate");
        } finally {
            inf.end();
        }
        return out.toByteArray();
    }

    private static byte[] concat(byte flag, byte[] rest) {
        byte[] out = new byte[rest.length + 1];
        out[0] = flag;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }

    static void varint(ByteArrayOutputStream o, int v) {
        while ((v & ~0x7F) != 0) {
            o.write(v & 0x7F | 0x80);
            v >>>= 7;
        }
        o.write(v);
    }

    /** A non-negative varint (at most 31 bits). */
    static int varint(byte[] b, int[] at) throws Bad {
        int v = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            if (at[0] >= b.length) throw new Bad("truncated");
            int x = b[at[0]++];
            v |= (x & 0x7F) << shift;
            if ((x & 0x80) == 0) {
                if (v < 0) throw new Bad("varint");
                return v;
            }
        }
        throw new Bad("varint");
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew test --offline -q --tests '*EditCodecTest'` — Expected: PASS. Fix the implementation (not the test) if the `truncated` loop (every cut must throw `Bad`) fails: a cut inside a deflate stream gives `needsInput` -> `Bad`; a cut raw body fails on `trailing`/`truncated`.

- [ ] **Step 5: Commit** `git add -A fabric && git commit -m "mp2: Edits wire format (EditCodec)"`

---

### Task 2: Payloads and protocol 3

**Files:**
- Modify: `net/Protocol.java:8` (VERSION default 3), `net/MpPayloads.java`
- Test: `fabric/src/test/java/dev/moui/galaxycraft/net/MpPayloadsTest.java` (extend; read it first and follow its codec round-trip helper)

**Interfaces:**
- Consumes: `EditCodec.MAX_WIRE`.
- Produces: `MpPayloads.Edits(byte[] data)` (registered both directions, `readByteArray(EditCodec.MAX_WIRE)`), `MpPayloads.PlanetDrop(int index)` (serverbound), `MpPayloads.Resync(int index)` (clientbound), `PlanetHead(int index, int state, long etag, int total, int seq)` (new last field, `writeVarInt`).

- [ ] **Step 1:** In `MpPayloadsTest` add round-trip tests for `Edits`, `PlanetDrop`, `Resync`, and `PlanetHead` with `seq` (copy the existing record round-trip style), plus one asserting `Edits` with a body over `EditCodec.MAX_WIRE` fails to decode.
- [ ] **Step 2:** Run `./gradlew test --offline -q --tests '*MpPayloadsTest'` — FAIL (compile).
- [ ] **Step 3:** Implement the records following the existing ones (`typeOf("mp_edits")`, `"mp_planet_drop"`, `"mp_resync"`), register in `register()`: `Edits` both directions, `PlanetDrop` serverbound, `Resync` clientbound. Add `seq` to `PlanetHead` and update its three uses (`MpServer.deliver` x3 pass 0 for now, `MpPlanets.onHead` ignores it for now). Set `Protocol.VERSION` default to 3. Check `ProtocolTest`/`WireTest` for hard-coded `2` and fix.
- [ ] **Step 4:** `./gradlew test --offline -q` — all PASS.
- [ ] **Step 5:** Commit `mp2: Edits, PlanetDrop, Resync payloads; protocol 3`.

---

### Task 3: EditHub (server-side subscriptions and queues)

**Files:**
- Create: `net/EditHub.java`; Test: `net/EditHubTest.java`

**Interfaces:**
- Consumes: `EditCodec.Msg`, `EditCodec.Def`.
- Produces: `EditHub<G>` with:
  - `EditHub(Sink<G> sink)`; `interface Sink<G> { void resync(G guest, int planet); }`
  - `boolean subscribe(G g, int planet)` (pending; false if already subscribed -> still ok)
  - `void deliver(G g, int planet, int snapshotSeq)`: pending -> live; buffered msgs with `seq > snapshotSeq` go to the guest's edit queue
  - `void cancel(G g, int planet)`; `void forget(G g)`; `boolean subscribed(G g, int planet)`
  - `void publish(EditCodec.Msg m, G author)`: for every guest subscribed to `m.planet()`: pending -> buffer, live -> queue; the author's copy keeps `m.origin()`, everyone else's gets `withOrigin(0)`
  - `void publishTo(G g, EditCodec.Msg m)`: a correction, queued if subscribed
  - `void drain(G g, int budget, Consumer<EditCodec.Msg> send)`: sends from the front while budget lasts (first always goes); each message goes out with only the defs the guest has not been given yet (`withDefs`), and those are marked known
  - constants `MAX_QUEUE_BYTES = 8 << 20`; entry size estimate `cells*3 + defs*40 + 16`. Over the cap: that planet's subscription is cancelled, its queued/buffered messages removed, `sink.resync(g, planet)` called.

- [ ] **Step 1: Failing tests** (`EditHubTest`): helper `m(planet, seq, origin, cells...)` builds a Msg with one def `(1,"a")` and ids all 1; guests are `String`s; sink records resyncs.

```java
@Test void editsWhilePendingComeAfterDeliverAndAboveTheSnapshot() {
    hub.subscribe("g", 4);
    hub.publish(m(4, 1, 0, 10), null);   // in the planet's bytes (<= snapshot 2)
    hub.publish(m(4, 3, 0, 11), null);   // after
    hub.drain("g", 1 << 20, sent::add);
    assertTrue(sent.isEmpty());          // nothing before the planet is delivered
    hub.deliver("g", 4, 2);
    hub.drain("g", 1 << 20, sent::add);
    assertEquals(1, sent.size());
    assertEquals(3, sent.get(0).seq());
}
@Test void onlySubscribedGuestsGetEdits() { /* g1 subscribed+delivered, g2 not: only g1 drains one */ }
@Test void theAuthorKeepsTheOriginOthersGetZero() { /* publish(m(4,5,9,..), "a"): a sees origin 9, b sees 0 */ }
@Test void defsAreGivenOnce() { /* two msgs with def (1,"a"): first drained has 1 def, second 0 */ }
@Test void aCorrectionGoesOnlyToItsGuest() { /* publishTo */ }
@Test void overTheCapResyncsAndDropsTheSubscription() { /* publish ~3000 batches of 2048 cells (large est) until resync recorded; then subscribed()==false and queue empty */ }
@Test void cancelAndForgetStopEdits() {}
@Test void drainRespectsTheBudgetButAlwaysSendsOne() {}
```
Write each body in full (they are 5-10 lines each; follow the first).

- [ ] **Step 2:** Run `--tests '*EditHubTest'` — FAIL (compile).
- [ ] **Step 3: Implement.** Shape:

```java
public final class EditHub<G> {
    public static final long MAX_QUEUE_BYTES = 8L << 20;
    public interface Sink<G> { void resync(G guest, int planet); }
    private static final class Sub { boolean live; final List<EditCodec.Msg> buffer = new ArrayList<>(); }
    private static final class Peer { final Map<Integer, Sub> subs = new HashMap<>(); final Set<Integer> known = new HashSet<>();
        final ArrayDeque<EditCodec.Msg> queue = new ArrayDeque<>(); long bytes; }
    private final Map<G, Peer> peers = new HashMap<>();
    private final Sink<G> sink;
    // subscribe/deliver/cancel/forget/publish/publishTo/drain as specified.
    static long size(EditCodec.Msg m) { return m.cells().length * 3L + m.defs().size() * 40L + 16; }
}
```
`enqueue(Peer p, G g, Msg m)`: `p.queue.add(m); p.bytes += size`; if `p.bytes > MAX` -> `resyncPlanet(g, p, m.planet())`: remove sub, remove that planet's msgs from queue (recompute bytes), `sink.resync`. The buffer of a pending sub counts toward the same cap (add size to `p.bytes` when buffering, subtract when it moves or is dropped).
- [ ] **Step 4:** `./gradlew test --offline -q --tests '*EditHubTest'` — PASS.
- [ ] **Step 5:** Commit `mp2: EditHub (subscriptions, ordering, budget, resync)`.

---

### Task 4: EditSync (guest reconcile)

**Files:**
- Create: `net/EditSync.java`; Test: `net/EditSyncTest.java`

**Interfaces:**
- Produces: `EditSync(int snapshotSeq)`; `int nextCseq()` (1, 2, ...); `void local(int cell, int cseq)` (pending); `boolean accepts(int hseq)` (> snapshotSeq and > last applied hseq of ordered stream... only `> snapshotSeq`; a repeat is harmless); `void apply(EditCodec.Msg m, int[] resolvedIds, CellSetter set)` where `interface CellSetter { void set(int cell, int id); }`: first clears pending entries with `cseq <= m.origin()`, then sets each entry whose cell has no pending left; `boolean hasPending()`; `int pendingCount()`.

- [ ] **Step 1: Failing tests**, in full:

```java
@Test void anEchoClearsPendingAndApplies() {
    var s = new EditSync(0); int c = s.nextCseq(); s.local(7, c);
    List<int[]> sets = new ArrayList<>();
    s.apply(msg(1, c, new int[] {7}), new int[] {42}, (cell, id) -> sets.add(new int[] {cell, id}));
    assertEquals(1, sets.size()); assertFalse(s.hasPending());
}
@Test void otherEditsToAPendingCellAreSkipped() { /* local(7, 1); apply origin 0 for cell 7 -> no set; then apply origin 1 for cell 9 -> set 9, pending cleared */ }
@Test void aLaterLocalEditSurvivesAnEarlierEcho() { /* local(7,1), local(7,2); echo origin 1 for cell 7 -> skipped (pending cseq 2 remains); echo origin 2 -> applied */ }
@Test void snapshotFiltersOldBatches() { assertFalse(new EditSync(5).accepts(5)); assertTrue(new EditSync(5).accepts(6)); }
@Test void cseqsCount() {}
```
- [ ] **Step 2:** FAIL. **Step 3:** Implement with `HashMap<Integer,Integer> pending` (cell -> latest cseq; `local` overwrites). `apply`: `pending.values().removeIf(v -> v <= m.origin())` is wrong for a cell re-edited (latest cseq 2 > origin 1: stays, correct). **Step 4:** PASS. **Step 5:** Commit `mp2: EditSync (guest reconcile)`.

---

### Task 5: VoxelPlanet observers, PlanetSession follower mode

**Files:**
- Modify: `voxel/VoxelPlanet.java` (near `setListener`/`set`/`setQuietly`, lines ~340-420), `voxel/PlanetSession.java` (`update` ~560, `touchLeaves` 371, `applyExternal` 1026, `breakBlock` 882, `placeBlock` 908, `unload`)
- Test: `voxel/PlanetSessionTest.java` (extend) and a new `voxel/VoxelPlanetObserverTest.java` (use the same test `Blocks` helper `PlanetSessionTest` uses)

**Interfaces:**
- Produces: `VoxelPlanet.addObserver(Listener)` / `removeObserver(Listener)` (observers fire for every change **including** `setQuietly`; the single `Listener` behavior is unchanged); `VoxelPlanet.setFromRemote(int cell, int id)` (changes the cell, marks dirty, does NOT fire observers or the Listener); `PlanetSession.setFollower(boolean)` (a follower runs no fluid tick and no leaf decay, and `touchLeaves` is a no-op); `PlanetSession.applyShared(int cell, int id)` (host applies a guest's edit: `planet.set` so the Listener (shadow world) and observers fire, `touchLeaves(cell)`, `unsaved = true`); `PlanetSession.applyRemote(int cell, int id)` (guest: `setFromRemote`, no `unsaved`); `PlanetSession.setPlayerScope(...)`: see below.
- Player scope: `VoxelPlanet.setObserverMute(boolean)`. Guest observer forwards only changes made while `PlanetSession.playerEditing` is true: `breakBlock` and `placeBlock` set it around their `planet.set`/`settle` calls (try/finally) and expose `PlanetSession.addPlayerObserver(Listener)`, which fires only inside that scope. Host side uses `VoxelPlanet.addObserver` (all changes).

- [ ] **Step 1: Failing tests:** observer sees `set`, `setQuietly`, not `setFromRemote`; Listener not fired by `setQuietly`/`setFromRemote`; follower session skips `planet.fluids().tick()` and does not remove a decayed leaf (use the `LeafDecay` fixture in `PlanetSessionTest`); player observer fires for `breakBlock` (and for the door-half settle) but not for `applyRemote`/`applyExternal`.
- [ ] **Step 2:** `./gradlew test --offline -q --tests '*PlanetSessionTest' --tests '*VoxelPlanetObserverTest'` — FAIL.
- [ ] **Step 3:** Implement. In `VoxelPlanet.set`, replace both `if (listener != null) listener.changed(cell, b);` with `fire(cell, b)`:

```java
private void fire(int cell, char b) {
    if (listener != null) listener.changed(cell, b);
    for (Listener o : observers) o.changed(cell, b);   // observers: CopyOnWriteArrayList<Listener>
}
```
`setFromRemote` = the body of `set` with `fire` skipped (extract the body to a private `put(cell, b, boolean tell)` that `set` calls with true). Fluids: `fluids.touched(cell)` stays (harmless on a follower: `update` does not tick them).
- [ ] **Step 4:** PASS, plus the whole suite `./gradlew test --offline -q`.
- [ ] **Step 5:** Commit `mp2: planet observers, follower mode, player scope`.

---

### Task 6: Server wiring (MpServer)

**Files:** Modify `net/MpServer.java`, `client/MpPlanetHost.java`; test via MpProbe (Task 10).

**Interfaces:**
- Consumes: `EditHub`, `EditCodec`, `PlanetXfer.Outbox`.
- Produces: `MpServer.publish(EditCodec.Msg m, UUID authorOrNull)` (any thread -> server thread); `MpServer.correct(UUID guest, EditCodec.Msg m)`; `interface EditSink { void edits(UUID guest, EditCodec.Msg m); }` + `MpServer.setEditSink(EditSink)`; `MpServer.planetDelivered(UUID guest, int index, int snapshotSeq)` is called inside `deliver` (not public).

- [ ] **Step 1:** In `MpServer` add `private static final EditHub<UUID> hub = new EditHub<>((g, planet) -> sendTo(g, new MpPayloads.Resync(planet)))`, per-guest `RateLimiter editMsgs (20/s, burst 40)` and `editBytes` (byte budget 64 KB/s: use `RateLimiter(65536, 131072)` consuming `allow` once per KB of payload, or write a small `allow(n)` loop; keep it simple: cost = max(1, bytes/1024) calls), and a malformed counter per guest (reuse how `Relay` counts, 5 in a row -> `SINK.kick`).
- [ ] **Step 2:** `ask(...)`: call `hub.subscribe(id, ask.index())` **before** `source.ask(...)`. In `deliver`, the head gets the snapshot seq: change `PlanetSource.ask` result path so `offer(guest, index, etag, offer, seq)` carries `seq` (taken on the game thread by `HostPlanets`, Task 7); put it in every `PlanetHead`; after queuing head+chunks for DATA/SAME call `hub.deliver(guest, index, seq)`; for FAIL call `hub.cancel`.
- [ ] **Step 3:** Receiver `MpPayloads.Edits` (server): rate-limit, `EditCodec.decode` (catch `Bad`: count, kick on 5 in a row; reset the counter on a good one), require `members.contains(id)` and `hub.subscribed(id, m.planet())`, then `editSink.edits(id, m)`. Receiver `PlanetDrop`: `hub.cancel`, and tell the host game (`MpPlanetHost.dropped(guest, index)`, Task 7). Receiver for `Resync`: none (clientbound).
- [ ] **Step 4:** `pump`: per guest, first `hub.drain(id, EDIT_BUDGET /*16 KB*/, msg -> ServerPlayNetworking.send(p, new MpPayloads.Edits(EditCodec.encode(msg))))`, then the planet outbox with `BUDGET_PER_TICK - EDIT_BUDGET` (at least `CHUNK` stays reachable: the outbox always sends one). `forget(id)` also calls `hub.forget(id)`; `SERVER_STOPPED` clears the hub (make a fresh one).
- [ ] **Step 5:** `publish(m, author)`: `server.execute(() -> hub.publish(m, author))`; `correct`: `hub.publishTo`.
- [ ] **Step 6:** `./gradlew build --offline -q` compiles; `./gradlew test --offline -q` PASS. Commit `mp2: server relays planet edits`.

---

### Task 7: Host authority (HostPlanets)

**Files:**
- Create: `client/HostPlanets.java`; Modify `client/GalaxyStream.java` (`serve`, promote/demote, ~lines 345-500), `client/PlanetClient.java` (client tick, `saveNow` ~1148, session start/stop), `client/MpPlanetHost.java`, `client/MpClient.java` (host registers)

**Interfaces:**
- Consumes: `MpServer.publish/correct/setEditSink`, `VoxelPlanet.addObserver`, `PlanetSession.applyShared`, `McBlocks.name(int)`/`parse(String)`.
- Produces: `HostPlanets.register()`; `HostPlanets.tick()` (client tick, flushes batches); `HostPlanets.seq(int index)` (current hseq, game thread); `HostPlanets.hold(int index, VoxelPlanet p)` (makes `p` the resident if there is no live session); `HostPlanets.dropped(UUID guest, int index)`; `HostPlanets.flushAll()` (world close); `HostPlanets.planetOf(int index)` (live session planet, else resident, else null).

- [ ] **Step 1: Wire palette.** Inside `HostPlanets`: `Map<Integer,Integer> wireOf` (block id -> wire id, ids 0..4095) and `String[] nameOf`. `wire(int blockId)` allocates the next wire id on first use (if 4096 are used, skip the entry and log once). `defs(Set<Integer> usedWire)` builds the `Def` list for a message.
- [ ] **Step 2: Observers.** For every live catalog session (hook where `PlanetClient`/`GalaxyStream` creates and releases a catalog session: `PlanetClient.sessionOf(index)`, `release(index)`; find them with `grep -n "release(\|sessionOf(" client/*.java`) and for each resident, `addObserver(o)` where `o` appends `(cell, id)` to `Map<Integer, Batch> batches` (Batch: `IntList cells, ids; int author; int cseq`). Only when `MpServer`-side has any member (a cheap `MpServer.entered() > 0`, or just always: appending is cheap). Remove the observer on release/drop.
- [ ] **Step 3: Flush.** `HostPlanets.tick()` (called at the end of the client tick in `PlanetClient`): for each non-empty batch, split into <= `EditCodec.MAX_ENTRIES` pieces; each piece gets `seq = ++seqs[index]`, is built as a `Msg` with `origin` = the author's cseq (or 0) and `defs` for the ids used, and goes to `MpServer.publish(msg, author)`. Batches are separated by author: a change made while `apply` runs for guest G is recorded in G's batch (a field `currentAuthor/currentCseq` set by `apply`), anything else in the author-less batch.
- [ ] **Step 4: Applying a guest's edit** (`MpServer.setEditSink` -> `Minecraft.execute`): `HostPlanets.apply(UUID guest, Msg m)`: resolve `planetOf(m.planet())` (null -> correct every cell... there is nothing to correct against: just send `Resync`-less FAIL by calling `MpServer.correct` with an empty message; log); map each wire id through the guest's palette (`Map<UUID, String[]> guestNames`, filled from `m.defs()`, bounded 4096), `blocks.parse(name)`; for an unknown name or a cell outside `0..planet.cells-1`, add a correction entry (current value of the cell by this host's wire id). Valid entries: if the planet is a live session, `session.applyShared(cell, id)`, else `planet.set(cell,id)`, `touchLeaves` is skipped (resident: no leaf decay; known limit, noted in Later), mark the resident edited. Then `MpServer.correct(guest, correction)` if any.
- [ ] **Step 5: Residents.** `GalaxyStream.serve(index)`: on the game thread, before anything async, read `long seq = HostPlanets.seq(index)`; return a `Served(bytes future, seq)` so `MpPlanetHost.ask` passes `seq` to `MpServer.offer`. When the bytes were made for the occasion or read from the save and the planet is not live, decode them once into a resident (`PlanetStore.decode(bytes, blocks)` on `PlanetClient.maker`, then `HostPlanets.hold(index, planet)` on the game thread); if the planet is live or in `recent`, hold that `VoxelPlanet` itself (no copy). Max 8 residents: the ninth subscription is answered FAIL. `edited` live planets still go through the existing `saveOne` path.
- [ ] **Step 6: Hand-over.** Promote (resident exists): `PlanetClient.saveResident(index)` queued on the saver before the file read in the promote path, then `HostPlanets.release(index)` (observer detached from the resident, re-attached to the live session). Demote of a live planet with subscribers: the `recent.put(index, p)` planet becomes the resident (`hold`). A resident with no subscribers (`dropped` and disconnect) is saved if edited, then released.
- [ ] **Step 7: Saving.** Resident `edited` flag; the saver writes it 30 s after the last edit (client tick counter), on release, and in `PlanetClient.saveNow()` via `HostPlanets.flushAll()`. Use the same `store.write(key, Saved.of(p, center), blocks)` as `saveOne`.
- [ ] **Step 8:** Compile; `./gradlew test --offline -q` PASS. Commit `mp2: host holds guest planets, applies and broadcasts edits`.

---

### Task 8: Guest side (MpEdits)

**Files:**
- Create: `client/MpEdits.java`; Modify `client/MpPlanets.java` (head `seq`, release hook, `Resync`), `client/PlanetClient.java` (when a session of a fetched planet starts and is released), `client/MpClient.java` (register, reset)

**Interfaces:**
- Consumes: `EditSync`, `EditCodec`, `PlanetSession.addPlayerObserver/applyRemote/setFollower`, `MpPlanets`.
- Produces: `MpEdits.register()`, `MpEdits.attach(int index, PlanetSession s, int snapshotSeq)`, `MpEdits.detach(int index)`, `MpEdits.tick()`, `MpEdits.reset()`; `MpPlanets.seqOf(int index)` (head seq of the fetched copy).

- [ ] **Step 1: Held edits.** `MpPlanets.onHead` stores `seq` per planet (`seqs` map, kept until `reset`). Incoming `Edits` for an index with no attached session are appended to `held: Map<Integer, List<Msg>>` (bounded: more than 64 messages for one planet -> mark that planet for refetch via `MpPlanets.forget(index)`; then `Resync`-like path).
- [ ] **Step 2: attach.** Where a catalog planet's session becomes active on a guest (the `GalaxyStream.startMaking` guest branch, after the planet is loaded from the fetched file): `session.setFollower(true)`; `EditSync sync = new EditSync(MpPlanets.seqOf(index))`; register `session.addPlayerObserver((cell,id) -> out.add(cell, id))`; then apply the held messages in order through `onEdits`.
- [ ] **Step 3: Outgoing.** `tick()` (end of client tick): for each attached planet with collected changes, `cseq = sync.nextCseq()`, `sync.local(cell, cseq)` for each, wire ids via the guest palette (`Map<Integer,Integer>`, 4096 cap; first use adds a def), `ClientPlayNetworking.send(new MpPayloads.Edits(EditCodec.encode(new Msg(index, 0, cseq, defs, cells, ids))))`. Split at 2048 entries.
- [ ] **Step 4: Incoming.** Receiver `Edits` (client thread via `ctx.client().execute`): `EditCodec.decode`; bad -> log, count, ignore. Defs update the host palette (`String[]`, 4096 cap); `sync.accepts(m.seq())` else skip; resolve names with `blocks.parse` (unknown -> id AIR is wrong; skip that entry and log once); `sync.apply(m, ids, session::applyRemote)`.
- [ ] **Step 5: Lifetime.** Where a fetched planet's session is released or dropped from `recent`: `MpEdits.detach(index)`, `ClientPlayNetworking.send(new PlanetDrop(index))`, `MpPlanets.forgetFresh(index)`. `Resync(index)` receiver: `MpPlanets.forgetFresh(index)`, `MpEdits.detach`, ask again with `fetch`, and when it is in swap the planet into the open session with `PlanetSession.swap(VoxelPlanet)` (read how `GalaxyStream` loads a fetched file and reuse that load; if the planet is only held in `recent`, just drop it).
- [ ] **Step 6:** `MpClient.reset` calls `MpEdits.reset()`. Compile; `./gradlew test --offline -q`. Commit `mp2: guest predicts, sends, reconciles host edits`.

---

### Task 9: Hand check in the pure parts' seams (probe in-process)

**Files:** Modify `fabric/src/gametest/.../MpProbe*.java` (find with `grep -rl MpProbe fabric/src/gametest`).

- [ ] **Step 1:** Extend the probe: after the existing handshake and planet fetch, the guest breaks cell A and places block B on a planet using a test hook (call `PlanetSession.breakBlock` equivalent through `MpEdits` test entry `MpEdits.testEdit(index, cell, blockName)` and `HostPlanets.testEdit(...)` for the host: add these small `public static` hooks, documented as tests-only), then assert within N ticks: host cells == guest cells for both; an edit by the host appears at the guest; the same cell edited by both in one tick converges to the same value; an unknown block name from the guest produces a correction and no crash; 5 garbage `Edits` kick the guest.
- [ ] **Step 2:** Run `./gradlew runClientGameTest -PgalaxycraftMp -PgalaxycraftHidden --offline` (about 3 min). Expected: PASS. If a probe cannot make planets without Dolphin (known limit), make a small planet in memory (`VoxelPlanet.ofRadius(8, blocks)`) and register it as catalog index 0 in `HostPlanets` via a tests-only `HostPlanets.holdForTest`.
- [ ] **Step 3:** Commit `mp2: MpProbe checks shared edits`.

---

### Task 10: Real two-game check, docs, roadmap

- [ ] **Step 1: Two real games** with the recipe in memory `multiplayer-mp1-test` (host and guest commands; recreate the scratch scripts in the scratchpad; kills only in script files). Check: guest breaks and places, host sees it; host does the same, guest sees it; a planet the host is far from: guest builds, host quits, reopens, flies there, the work is there; same cell at once. Report logs (`[GalaxyCraft mp]`) and cell counts. If a real game cannot be driven by script, leave the exact checklist for the user.
- [ ] **Step 2:** Update `docs/MULTIJUGADOR.md` (shared building, what is not shared yet) in English and Spanish like the file does; update the spec's "encoded once" line to "encoded per guest only when its palette or origin differs".
- [ ] **Step 3:** `ROADMAP.md`: Multiplayer row -> MP-2 **built, awaiting playtest** (branch `feat/multiplayer-2`), bump "Last updated", one line in the answer. Do not touch Done, the public doc or Discord until the user has tried it.
- [ ] **Step 4:** `./gradlew test --offline -q` all PASS; commit `mp2: docs, roadmap`. Save a memory note (`multiplayer-mp2.md`) with the state, update `MEMORY.md` and `session-mp2.md`.

---

## Self-review

- Spec coverage: wire (T1, T2), subscriptions/ordering/budget/resync (T3, T6), guest reconcile (T4, T8), observers/follower (T5), host residents/saving/hand-over/rejections (T7), no-sim on guests (T5, T8), failure table (T3, T6, T7, T9), testing levels (T1-4 unit, T9 in-process, T10 real).
- Known open points the implementer settles by reading code, recorded here: where exactly catalog sessions start/stop on host and guest (`grep sessionOf/release`), and the resident leaf-decay gap (a guest's tree-log break on a planet the host is away from does not decay its leaves until the host visits; accepted for MP-2).
