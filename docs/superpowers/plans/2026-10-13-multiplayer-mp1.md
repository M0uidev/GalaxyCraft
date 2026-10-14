# Multiplayer MP-1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A guest joins a LAN-opened world by direct IP, enters the host's galaxy, and sees every other player move (Steve with skin; Mario if the spike allows).

**Architecture:** Pure, unit-tested core in `dev.moui.galaxycraft.net` (state record, byte codec, rate limiter, relay logic, interpolation) with no Minecraft types. Thin Fabric glue: payload registration and a server relay (`main`), guest entry + sender + remote drawing (`client`). The host's `GalaxySave.Galaxy` JSON travels in `Hello` and is written into a guest galaxy folder, so the existing `PlanetClient.enterWorld` path is reused unchanged.

**Tech Stack:** Java 25, Minecraft 26.3 (unobfuscated), Fabric API 0.161.0+26.3 (`fabric-networking-api-v1`), JUnit (existing `fabric/src/test`), Gson (already used by `GalaxySave`).

**Spec:** `docs/superpowers/specs/2026-10-13-galaxycraft-multiplayer-mp1-design.md`

## Global Constraints

- Protocol version `1`; the mod is required to join (payload channels registered as required).
- Shared position is in **universe units** (`PlanetClient.marioUniverse()`), mapped by the receiver with `GameOrigin.toGame(Vector3d)`; never Minecraft coordinates.
- `State` ~10 Hz, <= 20/s accepted per sender, wire size 67 bytes, 8 players max.
- Custom payloads client -> server are capped at 32 KB by Minecraft: `Skin` PNG <= 24 KB.
- Render delay 100 ms, extrapolate at most 250 ms, hide after 5 s without states.
- 5 consecutive malformed payloads disconnect the sender; `Hello` must be answered within 5 s.
- Singleplayer must be unchanged: no payload sent, no new work per tick when not on a multiplayer connection.
- Gradle/Java: `fabric/test.sh` (portable JDK 25). Minecraft types are unobfuscated: inspect with `javap -p -cp ~/.gradle/caches/fabric-loom/26.3/minecraft-common.jar <class>` (client classes in `minecraft-client.jar`).
- Never run a Dolphin probe while another session's Dolphin runs (`pgrep -af dolphin`).
- Commit messages end with `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`. No merge, no push, no Discord devlog before the user playtests (see memory `merge-after-playtest`).

## Review Focus

- A `State` with NaN / infinite / absurd coordinates or a non-unit up vector: ignored, never reaches a renderer (Task 1, Task 3).
- Two players spawning at the same universe point, or a peer exactly at the local player: no division by zero in interpolation or name-tag distance (Task 3).
- A peer whose states stop (guest alt-tabbed, packet loss) : holds, then hides; never flies off on stale velocity (Task 3).
- A peer that joins, leaves and rejoins with the same UUID: old track gone, new one starts clean (Task 5).
- The host quits while a guest is mid-load: guest returns to the title with the cache kept, no crash on the null `Galaxy` (Task 4).

---

## File Structure

| File | Responsibility |
|---|---|
| `fabric/src/main/java/dev/moui/galaxycraft/net/PeerState.java` | The shared per-player state record + validity rules |
| `.../net/Wire.java` | Fixed 67-byte codec for `PeerState`, bounds-checked |
| `.../net/Protocol.java` | Version constant, mode / flag constants, limits, `refusal(server, client)` |
| `.../net/RateLimiter.java` | Token bucket per sender |
| `.../net/Relay.java` | Server logic: join / leave / state fan-out, malformed counting, rate limit; talks to a `Sink` |
| `.../net/PeerTrack.java` | Per-peer sample ring + interpolation / extrapolation / hiding |
| `.../net/MpPayloads.java` | The payload records, their codecs, registration (both sides) |
| `.../net/MpServer.java` | Fabric glue: JOIN sends `Hello`, receivers feed `Relay`, `Sink` sends payloads, ack timeout |
| `fabric/src/main/java/dev/moui/galaxycraft/voxel/GalaxySave.java` | + `worldId()` (UUID file in the galaxy folder) |
| `fabric/src/client/java/dev/moui/galaxycraft/client/MpClient.java` | Guest entry from `Hello`, state sender, remote store, `/tpp` |
| `.../client/RemotePlayers.java` | UUID -> `PeerTrack` map, thread hand-off, per-frame poses |
| `.../client/EntityClient.java` | Split `self()` into a shared avatar draw + `remotes(...)` |
| `fabric/src/client/java/.../client/GalaxyWorlds.java` | Route non-singleplayer joins |
| `fabric/src/gametest/.../MpProbe.java`, `tools/gxvoxel.sh` | Two-client probe |
| `docs/MULTIJUGADOR.md` | Player checklist |

---

### Task 1: PeerState, Wire and Protocol

**Files:**
- Create: `fabric/src/main/java/dev/moui/galaxycraft/net/PeerState.java`, `Wire.java`, `Protocol.java`
- Test: `fabric/src/test/java/dev/moui/galaxycraft/net/WireTest.java`

**Interfaces:**
- Produces: `record PeerState(int seq, double x, double y, double z, float upX, float upY, float upZ, float yaw, float pitch, float vx, float vy, float vz, int mode, int flags)` with `boolean valid()`; `Wire.SIZE = 63`, `static byte[] encode(PeerState)`, `static PeerState decode(byte[])` (throws `Wire.Malformed`); `Protocol.VERSION = 1`, `MODE_STEVE = 0`, `MODE_MARIO = 1`, `F_SNEAK=1, F_SPRINT=2, F_SWIM=4, F_GLIDE=8, F_GROUND=16, F_SHIP=32`, `MAX_PLAYERS = 8`, `static Optional<String> refusal(int serverVersion, int clientVersion)`.

- [ ] **Step 1: Write the failing test**

```java
package dev.moui.galaxycraft.net;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class WireTest {
    static PeerState sample() {
        return new PeerState(7, 1.5e9, -2.25, 3.0e6, 0f, 1f, 0f, 90f, -10f, 1f, 0f, -2f, Protocol.MODE_STEVE,
                Protocol.F_SNEAK | Protocol.F_GROUND);
    }

    @Test
    void roundTrips() {
        byte[] b = Wire.encode(sample());
        assertEquals(Wire.SIZE, b.length);
        assertEquals(sample(), Wire.decode(b));
    }

    @Test
    void shortInputIsMalformed() {
        assertThrows(Wire.Malformed.class, () -> Wire.decode(new byte[Wire.SIZE - 1]));
    }

    @Test
    void longInputIsMalformed() {
        assertThrows(Wire.Malformed.class, () -> Wire.decode(new byte[Wire.SIZE + 1]));
    }

    @Test
    void unknownModeIsMalformed() {
        byte[] b = Wire.encode(sample());
        b[Wire.SIZE - 3] = 9; // mode byte
        assertThrows(Wire.Malformed.class, () -> Wire.decode(b));
    }

    @Test
    void validRejectsNaNInfiniteAndAbsurd() {
        assertTrue(sample().valid());
        assertFalse(new PeerState(0, Double.NaN, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0).valid());
        assertFalse(new PeerState(0, 0, Double.POSITIVE_INFINITY, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0).valid());
        assertFalse(new PeerState(0, 1e30, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0).valid());
        assertFalse(new PeerState(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0).valid(), "up vector of length 0");
        assertFalse(new PeerState(0, 0, 0, 0, 0, 5, 0, 0, 0, 0, 0, 0, 0, 0).valid(), "up vector far from unit");
        assertFalse(new PeerState(0, 0, 0, 0, 0, 1, 0, Float.NaN, 0, 0, 0, 0, 0, 0).valid());
        assertFalse(new PeerState(0, 0, 0, 0, 0, 1, 0, 0, 0, 1e9f, 0, 0, 0, 0).valid(), "absurd velocity");
    }

    @Test
    void versionRefusalNamesBoth() {
        assertEquals(Optional.empty(), Protocol.refusal(1, 1));
        String r = Protocol.refusal(2, 1).orElseThrow();
        assertTrue(r.contains("2") && r.contains("1"), r);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd fabric && ./test.sh --tests 'dev.moui.galaxycraft.net.WireTest'`
Expected: FAIL (compilation: `PeerState` / `Wire` / `Protocol` not found).

- [ ] **Step 3: Implement**

`Protocol.java`:

```java
package dev.moui.galaxycraft.net;

import java.util.Optional;

/** Multiplayer constants shared by both sides. */
public final class Protocol {
    public static final int VERSION = 1;
    public static final int MODE_STEVE = 0, MODE_MARIO = 1;
    public static final int F_SNEAK = 1, F_SPRINT = 2, F_SWIM = 4, F_GLIDE = 8, F_GROUND = 16, F_SHIP = 32;
    public static final int MAX_PLAYERS = 8;
    /** The universe's reach in units; anything beyond is not a position. */
    public static final double MAX_COORD = 1e15;
    /** Blocks per second. */
    public static final float MAX_SPEED = 1e5f;

    private Protocol() {}

    public static Optional<String> refusal(int serverVersion, int clientVersion) {
        if (serverVersion == clientVersion) return Optional.empty();
        return Optional.of("Super Minecraft Galaxy multiplayer version differs: the host has " + serverVersion
                + ", you have " + clientVersion + ". Update the mod so both match.");
    }
}
```

`PeerState.java`:

```java
package dev.moui.galaxycraft.net;

/** What every player tells the others ~10 times a second. Position in universe units. */
public record PeerState(int seq, double x, double y, double z, float upX, float upY, float upZ, float yaw, float pitch,
        float vx, float vy, float vz, int mode, int flags) {

    /** False for anything that could not be a real player's state (NaN, infinities, absurd values). */
    public boolean valid() {
        double m = Protocol.MAX_COORD;
        if (!(Math.abs(x) <= m && Math.abs(y) <= m && Math.abs(z) <= m)) return false; // false for NaN too
        double up = Math.sqrt((double) upX * upX + (double) upY * upY + (double) upZ * upZ);
        if (!(up > 0.5 && up < 1.5)) return false;
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) return false;
        float s = Protocol.MAX_SPEED;
        if (!(Math.abs(vx) <= s && Math.abs(vy) <= s && Math.abs(vz) <= s)) return false;
        return mode == Protocol.MODE_STEVE || mode == Protocol.MODE_MARIO;
    }
}
```

`Wire.java`:

```java
package dev.moui.galaxycraft.net;

import java.nio.ByteBuffer;

/** PeerState as 63 bytes, big endian: seq, x y z, up, yaw pitch, v, mode (byte), flags (short). */
public final class Wire {
    public static final int SIZE = 4 + 24 + 12 + 8 + 12 + 1 + 2;

    public static final class Malformed extends RuntimeException {
        public Malformed(String why) {
            super(why);
        }
    }

    private Wire() {}

    public static byte[] encode(PeerState s) {
        ByteBuffer b = ByteBuffer.allocate(SIZE);
        b.putInt(s.seq()).putDouble(s.x()).putDouble(s.y()).putDouble(s.z());
        b.putFloat(s.upX()).putFloat(s.upY()).putFloat(s.upZ());
        b.putFloat(s.yaw()).putFloat(s.pitch());
        b.putFloat(s.vx()).putFloat(s.vy()).putFloat(s.vz());
        b.put((byte) s.mode()).putShort((short) s.flags());
        return b.array();
    }

    public static PeerState decode(byte[] raw) {
        if (raw.length != SIZE) throw new Malformed("state is " + raw.length + " bytes, not " + SIZE);
        ByteBuffer b = ByteBuffer.wrap(raw);
        int seq = b.getInt();
        double x = b.getDouble(), y = b.getDouble(), z = b.getDouble();
        float ux = b.getFloat(), uy = b.getFloat(), uz = b.getFloat();
        float yaw = b.getFloat(), pitch = b.getFloat();
        float vx = b.getFloat(), vy = b.getFloat(), vz = b.getFloat();
        int mode = b.get();
        int flags = b.getShort() & 0xFFFF;
        if (mode != Protocol.MODE_STEVE && mode != Protocol.MODE_MARIO) throw new Malformed("unknown mode " + mode);
        return new PeerState(seq, x, y, z, ux, uy, uz, yaw, pitch, vx, vy, vz, mode, flags);
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd fabric && ./test.sh --tests 'dev.moui.galaxycraft.net.WireTest'`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add fabric/src/main/java/dev/moui/galaxycraft/net fabric/src/test/java/dev/moui/galaxycraft/net
git commit -m "mp: PeerState, Wire codec and Protocol constants (validity rules, version refusal)

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 2: RateLimiter and Relay

**Files:**
- Create: `.../net/RateLimiter.java`, `.../net/Relay.java`
- Test: `fabric/src/test/java/dev/moui/galaxycraft/net/RelayTest.java`

**Interfaces:**
- Consumes: `PeerState`, `Wire`, `Protocol` (Task 1).
- Produces: `RateLimiter(double perSecond, int burst)` with `boolean allow(long nowNanos)`; `Relay(Sink sink)` with `void join(UUID)`, `void leave(UUID)`, `void state(UUID from, byte[] raw, long nowNanos)`; `interface Relay.Sink { void peerState(UUID to, UUID from, PeerState s); void peerGone(UUID to, UUID gone); void kick(UUID who, String reason); }`.

- [ ] **Step 1: Write the failing test**

```java
package dev.moui.galaxycraft.net;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RelayTest {
    static final UUID A = new UUID(0, 1), B = new UUID(0, 2), C = new UUID(0, 3);

    static class Rec implements Relay.Sink {
        final List<String> log = new ArrayList<>();
        public void peerState(UUID to, UUID from, PeerState s) { log.add("state " + to.getLeastSignificantBits() + "<-" + from.getLeastSignificantBits() + " #" + s.seq()); }
        public void peerGone(UUID to, UUID gone) { log.add("gone " + to.getLeastSignificantBits() + " " + gone.getLeastSignificantBits()); }
        public void kick(UUID who, String reason) { log.add("kick " + who.getLeastSignificantBits()); }
    }

    static byte[] st(int seq) {
        return Wire.encode(new PeerState(seq, 1, 2, 3, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0));
    }

    @Test
    void stateGoesToEveryoneElse() {
        Rec r = new Rec();
        Relay relay = new Relay(r);
        relay.join(A); relay.join(B); relay.join(C);
        relay.state(A, st(1), 0);
        assertEquals(List.of("state 2<-1 #1", "state 3<-1 #1"), r.log);
    }

    @Test
    void leaveTellsTheOthers() {
        Rec r = new Rec();
        Relay relay = new Relay(r);
        relay.join(A); relay.join(B);
        relay.leave(A);
        assertEquals(List.of("gone 2 1"), r.log);
        r.log.clear();
        relay.state(B, st(1), 0);
        assertEquals(List.of(), r.log, "nobody left to send to");
    }

    @Test
    void statesFromStrangersAreIgnored() {
        Rec r = new Rec();
        new Relay(r).state(A, st(1), 0);
        assertEquals(List.of(), r.log);
    }

    @Test
    void rateLimitDropsTheExcess() {
        Rec r = new Rec();
        Relay relay = new Relay(r);
        relay.join(A); relay.join(B);
        long t = 0;
        for (int i = 0; i < 100; i++) relay.state(A, st(i), t); // all in the same instant
        long sent = r.log.stream().filter(s -> s.startsWith("state")).count();
        assertTrue(sent <= 6, "burst only, got " + sent);
        r.log.clear();
        relay.state(A, st(200), 1_000_000_000L); // a second later it flows again
        assertEquals(1, r.log.size());
    }

    @Test
    void fiveMalformedInARowKick() {
        Rec r = new Rec();
        Relay relay = new Relay(r);
        relay.join(A); relay.join(B);
        for (int i = 0; i < 4; i++) relay.state(A, new byte[3], i * 100_000_000L);
        assertEquals(List.of(), r.log);
        relay.state(A, new byte[3], 500_000_000L);
        assertEquals(List.of("kick 1"), r.log);
    }

    @Test
    void aGoodStateResetsTheMalformedCount() {
        Rec r = new Rec();
        Relay relay = new Relay(r);
        relay.join(A); relay.join(B);
        for (int i = 0; i < 4; i++) relay.state(A, new byte[3], i * 100_000_000L);
        relay.state(A, st(1), 400_000_000L);
        for (int i = 0; i < 4; i++) relay.state(A, new byte[3], (5 + i) * 100_000_000L);
        assertTrue(r.log.stream().noneMatch(s -> s.startsWith("kick")));
    }

    @Test
    void invalidValuesAreDroppedNotRelayed() {
        Rec r = new Rec();
        Relay relay = new Relay(r);
        relay.join(A); relay.join(B);
        relay.state(A, Wire.encode(new PeerState(1, Double.NaN, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0)), 0);
        assertEquals(List.of(), r.log);
    }

    @Test
    void ninthPlayerIsKicked() {
        Rec r = new Rec();
        Relay relay = new Relay(r);
        for (int i = 1; i <= Protocol.MAX_PLAYERS; i++) relay.join(new UUID(0, i));
        relay.join(new UUID(0, 99));
        assertEquals(List.of("kick 99"), r.log);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd fabric && ./test.sh --tests 'dev.moui.galaxycraft.net.RelayTest'`
Expected: FAIL (compilation: `Relay` not found).

- [ ] **Step 3: Implement**

`RateLimiter.java`:

```java
package dev.moui.galaxycraft.net;

/** Token bucket: perSecond sustained, burst at once. Time is passed in so it is testable. */
public final class RateLimiter {
    private final double perSecond;
    private final double burst;
    private double tokens;
    private long last;
    private boolean started;

    public RateLimiter(double perSecond, int burst) {
        this.perSecond = perSecond;
        this.burst = burst;
        this.tokens = burst;
    }

    public boolean allow(long nowNanos) {
        if (!started) {
            started = true;
            last = nowNanos;
        }
        tokens = Math.min(burst, tokens + (nowNanos - last) / 1e9 * perSecond);
        last = nowNanos;
        if (tokens < 1) return false;
        tokens -= 1;
        return true;
    }
}
```

`Relay.java`:

```java
package dev.moui.galaxycraft.net;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** The server's whole job in MP-1: take a player's state, check it, hand it to everyone else. */
public final class Relay {
    public interface Sink {
        void peerState(UUID to, UUID from, PeerState s);

        void peerGone(UUID to, UUID gone);

        void kick(UUID who, String reason);
    }

    private static final int MALFORMED_KICK = 5;

    private static final class Peer {
        final RateLimiter limit = new RateLimiter(20, 5);
        int malformed;
    }

    private final Sink sink;
    private final Map<UUID, Peer> peers = new LinkedHashMap<>();

    public Relay(Sink sink) {
        this.sink = sink;
    }

    public void join(UUID id) {
        if (peers.size() >= Protocol.MAX_PLAYERS && !peers.containsKey(id)) {
            sink.kick(id, "The galaxy is full (" + Protocol.MAX_PLAYERS + " players).");
            return;
        }
        peers.put(id, new Peer());
    }

    public void leave(UUID id) {
        if (peers.remove(id) == null) return;
        for (UUID other : peers.keySet()) sink.peerGone(other, id);
    }

    public void state(UUID from, byte[] raw, long nowNanos) {
        Peer p = peers.get(from);
        if (p == null) return;
        if (!p.limit.allow(nowNanos)) return;
        PeerState s;
        try {
            s = Wire.decode(raw);
        } catch (Wire.Malformed e) {
            if (++p.malformed >= MALFORMED_KICK) {
                peers.remove(from);
                sink.kick(from, "Too many malformed multiplayer packets.");
                for (UUID other : peers.keySet()) sink.peerGone(other, from);
            }
            return;
        }
        if (!s.valid()) return;
        p.malformed = 0;
        for (UUID other : peers.keySet())
            if (!other.equals(from)) sink.peerState(other, from, s);
    }
}
```

Note: in `fiveMalformedInARowKick` the sender is rate-limited too; the five malformed packets span 400 ms so at 20/s with burst 5 they pass. In `ninthPlayerIsKicked`, kick is sent for the ninth and the id is not added.

- [ ] **Step 4: Run to verify it passes**

Run: `cd fabric && ./test.sh --tests 'dev.moui.galaxycraft.net.RelayTest'`
Expected: PASS (8 tests). If `fiveMalformedInARowKick` fails because the limiter drops a packet before the malformed count, raise the burst in `Relay` to 8 and keep `perSecond` 20; the spec's limit is the sustained rate.

- [ ] **Step 5: Commit**

```bash
git add fabric/src/main/java/dev/moui/galaxycraft/net fabric/src/test/java/dev/moui/galaxycraft/net
git commit -m "mp: Relay (fan-out, rate limit, malformed kick, player cap) and RateLimiter

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 3: PeerTrack (interpolation)

**Files:**
- Create: `.../net/PeerTrack.java`
- Test: `fabric/src/test/java/dev/moui/galaxycraft/net/PeerTrackTest.java`

**Interfaces:**
- Consumes: `PeerState`, `Protocol`.
- Produces: `PeerTrack()`; `void push(PeerState s, long arrivalNanos)` (ignores invalid and older-or-equal `seq` using wrap-safe compare); `Pose sample(long nowNanos)` returning `null` when nothing to show; `record Pose(double x, double y, double z, double upX, double upY, double upZ, float yaw, float pitch, int mode, int flags)`. Constants `DELAY_NANOS = 100_000_000`, `EXTRAPOLATE_NANOS = 250_000_000`, `HIDE_NANOS = 5_000_000_000L`.

- [ ] **Step 1: Write the failing test**

```java
package dev.moui.galaxycraft.net;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PeerTrackTest {
    static final long MS = 1_000_000L;

    static PeerState at(int seq, double x, float vx) {
        return new PeerState(seq, x, 0, 0, 0, 1, 0, 0, 0, vx, 0, 0, Protocol.MODE_STEVE, 0);
    }

    @Test
    void nothingBeforeTheFirstState() {
        assertNull(new PeerTrack().sample(0));
    }

    @Test
    void interpolatesBetweenTwoStatesOneDelayBehind() {
        PeerTrack t = new PeerTrack();
        t.push(at(1, 0, 10), 0);
        t.push(at(2, 1, 10), 100 * MS);
        // at now=150ms we show the moment 50ms into the interval: halfway
        assertEquals(0.5, t.sample(150 * MS).x(), 1e-9);
    }

    @Test
    void extrapolatesWithVelocityThenHolds() {
        PeerTrack t = new PeerTrack();
        t.push(at(1, 0, 10), 0);
        t.push(at(2, 1, 10), 100 * MS);
        // target = now-100ms = 200ms: 100ms after the newest -> 1 + 10*0.1
        assertEquals(2.0, t.sample(300 * MS).x(), 1e-6);
        // far past the extrapolation limit: capped at 250ms of travel
        assertEquals(1 + 10 * 0.25, t.sample(2000 * MS).x(), 1e-6);
    }

    @Test
    void hidesAfterFiveSecondsOfSilence() {
        PeerTrack t = new PeerTrack();
        t.push(at(1, 0, 0), 0);
        assertNotNull(t.sample(4_900 * MS));
        assertNull(t.sample(5_100 * MS));
    }

    @Test
    void oldAndDuplicateSequencesAreIgnored() {
        PeerTrack t = new PeerTrack();
        t.push(at(5, 5, 0), 0);
        t.push(at(4, 99, 0), 10 * MS);
        t.push(at(5, 99, 0), 20 * MS);
        assertEquals(5, t.sample(300 * MS).x(), 1e-9);
    }

    @Test
    void sequenceWrapAroundStillCountsAsNewer() {
        PeerTrack t = new PeerTrack();
        t.push(at(Integer.MAX_VALUE, 1, 0), 0);
        t.push(at(Integer.MIN_VALUE, 2, 0), 100 * MS);
        assertEquals(2, t.sample(1000 * MS).x(), 1e-9);
    }

    @Test
    void invalidStatesNeverEnter() {
        PeerTrack t = new PeerTrack();
        t.push(new PeerState(1, Double.NaN, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0), 0);
        assertNull(t.sample(10 * MS));
    }

    @Test
    void upVectorTurnsTheShortWayAndStaysUnit() {
        PeerTrack t = new PeerTrack();
        t.push(new PeerState(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0), 0);
        t.push(new PeerState(2, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0), 100 * MS);
        PeerTrack.Pose p = t.sample(150 * MS);
        assertEquals(1.0, Math.hypot(Math.hypot(p.upX(), p.upY()), p.upZ()), 1e-6);
        assertEquals(p.upX(), p.upY(), 1e-6, "halfway between +Y and +X");
    }

    @Test
    void oppositeUpVectorsDoNotDivideByZero() {
        PeerTrack t = new PeerTrack();
        t.push(new PeerState(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0), 0);
        t.push(new PeerState(2, 0, 0, 0, 0, -1, 0, 0, 0, 0, 0, 0, 0, 0), 100 * MS);
        PeerTrack.Pose p = t.sample(150 * MS);
        assertTrue(Double.isFinite(p.upX()) && Double.isFinite(p.upY()) && Double.isFinite(p.upZ()));
        assertEquals(1.0, Math.hypot(Math.hypot(p.upX(), p.upY()), p.upZ()), 1e-6);
    }

    @Test
    void yawTakesTheShortWayAround() {
        PeerTrack t = new PeerTrack();
        t.push(new PeerState(1, 0, 0, 0, 0, 1, 0, 350, 0, 0, 0, 0, 0, 0), 0);
        t.push(new PeerState(2, 0, 0, 0, 0, 1, 0, 10, 0, 0, 0, 0, 0, 0), 100 * MS);
        float yaw = t.sample(150 * MS).yaw();
        float wrapped = ((yaw % 360) + 360) % 360;
        assertEquals(0f, wrapped > 180 ? wrapped - 360 : wrapped, 1e-3);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd fabric && ./test.sh --tests 'dev.moui.galaxycraft.net.PeerTrackTest'`
Expected: FAIL (compilation: `PeerTrack` not found).

- [ ] **Step 3: Implement**

```java
package dev.moui.galaxycraft.net;

/**
 * One remote player's recent states, and where to draw them: 100 ms behind the newest (so there are
 * two states to blend), then up to 250 ms along the sent velocity, then held; hidden after 5 s.
 * Plain doubles, no allocation but the returned Pose.
 */
public final class PeerTrack {
    public static final long DELAY_NANOS = 100_000_000L, EXTRAPOLATE_NANOS = 250_000_000L, HIDE_NANOS = 5_000_000_000L;

    public record Pose(double x, double y, double z, double upX, double upY, double upZ, float yaw, float pitch, int mode, int flags) {}

    private PeerState a, b; // a older, b newest
    private long ta, tb;

    public void push(PeerState s, long arrivalNanos) {
        if (!s.valid()) return;
        if (b != null && s.seq() - b.seq() <= 0) return; // wrap-safe: newer means a positive difference
        a = b;
        ta = tb;
        b = s;
        tb = arrivalNanos;
    }

    public Pose sample(long nowNanos) {
        if (b == null || nowNanos - tb > HIDE_NANOS) return null;
        long target = nowNanos - DELAY_NANOS;
        if (a == null || target <= ta) return pose(b == null ? a : (a == null ? b : a), 0, null, 0);
        if (target >= tb) {
            double dt = Math.min(target - tb, EXTRAPOLATE_NANOS) / 1e9;
            return new Pose(b.x() + b.vx() * dt, b.y() + b.vy() * dt, b.z() + b.vz() * dt, b.upX(), b.upY(), b.upZ(), b.yaw(), b.pitch(), b.mode(), b.flags());
        }
        double f = (double) (target - ta) / (tb - ta);
        return pose(a, f, b, f);
    }

    private static Pose pose(PeerState s, double unused, PeerState to, double f) {
        if (to == null) return new Pose(s.x(), s.y(), s.z(), s.upX(), s.upY(), s.upZ(), s.yaw(), s.pitch(), s.mode(), s.flags());
        double ux = s.upX(), uy = s.upY(), uz = s.upZ(), vx = to.upX(), vy = to.upY(), vz = to.upZ();
        double[] up = slerp(ux, uy, uz, vx, vy, vz, f);
        float dyaw = (float) (((to.yaw() - s.yaw()) % 360 + 540) % 360 - 180); // shortest signed turn
        return new Pose(s.x() + (to.x() - s.x()) * f, s.y() + (to.y() - s.y()) * f, s.z() + (to.z() - s.z()) * f,
                up[0], up[1], up[2], s.yaw() + dyaw * (float) f, s.pitch() + (to.pitch() - s.pitch()) * (float) f, to.mode(), to.flags());
    }

    /** Unit vectors turning the short way; near-parallel or opposite inputs fall back to a normalized blend. */
    private static double[] slerp(double ax, double ay, double az, double bx, double by, double bz, double f) {
        double la = Math.sqrt(ax * ax + ay * ay + az * az), lb = Math.sqrt(bx * bx + by * by + bz * bz);
        ax /= la; ay /= la; az /= la; bx /= lb; by /= lb; bz /= lb;
        double dot = Math.max(-1, Math.min(1, ax * bx + ay * by + az * bz));
        double w0, w1;
        double theta = Math.acos(dot), sin = Math.sin(theta);
        if (sin < 1e-6) { // same or opposite
            w0 = 1 - f;
            w1 = f;
        } else {
            w0 = Math.sin((1 - f) * theta) / sin;
            w1 = Math.sin(f * theta) / sin;
        }
        double x = ax * w0 + bx * w1, y = ay * w0 + by * w1, z = az * w0 + bz * w1;
        double len = Math.sqrt(x * x + y * y + z * z);
        if (len < 1e-9) return new double[] {ax, ay, az}; // exactly opposite: keep the older one
        return new double[] {x / len, y / len, z / len};
    }
}
```

Simplify while writing: the `pose` helper's `unused` parameter and the first-branch ternary are noise; the implementer should reduce them to `poseOf(PeerState)` and `blend(PeerState, PeerState, double)` while keeping the tests green.

- [ ] **Step 4: Run to verify it passes**

Run: `cd fabric && ./test.sh --tests 'dev.moui.galaxycraft.net.PeerTrackTest'`
Expected: PASS (10 tests).

- [ ] **Step 5: Commit**

```bash
git add fabric/src/main/java/dev/moui/galaxycraft/net fabric/src/test/java/dev/moui/galaxycraft/net
git commit -m "mp: PeerTrack (100 ms delayed interpolation, bounded extrapolation, hide after 5 s)

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Payloads, server wiring, world id

**Files:**
- Create: `.../net/MpPayloads.java`, `.../net/MpServer.java`
- Modify: `fabric/src/main/java/dev/moui/galaxycraft/GalaxyCraft.java` (register), `voxel/GalaxySave.java` (add `worldId()`)
- Test: `fabric/src/test/java/dev/moui/galaxycraft/voxel/GalaxySaveWorldIdTest.java`

**Interfaces:**
- Consumes: `Relay`, `Wire`, `PeerState`, `Protocol`.
- Produces (`MpPayloads`, all `record ... implements CustomPacketPayload`, each with `public static final Type<X> TYPE = new Type<>(Identifier.fromNamespaceAndPath("galaxycraft", "<name>"))` and `STREAM_CODEC`): `Hello(int version, String worldId, String galaxyJson)` S2C; `HelloAck(int version, boolean ok)` C2S; `State(byte[] data)` C2S; `Peer(UUID id, byte[] data)` S2C; `Skin(UUID id, byte[] png)` C2S and S2C (the server fills `id`; the client sends its own, ignored); `Gone(UUID id)` S2C. `MpPayloads.register()` registers all types on both registries (call from the common entrypoint). `GalaxySave.worldId()` returns a stable `UUID` stored in `<world>/galaxycraft/world-id`, created on first call.
- `MpServer.init()` registers `ServerPlayConnectionEvents.JOIN/DISCONNECT`, `ServerPlayNetworking.registerGlobalReceiver` for `HelloAck`, `State`, `Skin`, and a server tick check for unanswered `Hello` after 5 s.

- [ ] **Step 1: Failing test for `worldId`**

```java
package dev.moui.galaxycraft.voxel;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GalaxySaveWorldIdTest {
    @Test
    void createdOnceAndStable(@TempDir Path dir) throws Exception {
        java.util.UUID a = GalaxySave.of(dir).worldId();
        java.util.UUID b = GalaxySave.of(dir).worldId();
        assertEquals(a, b);
        assertTrue(Files.exists(dir.resolve("galaxycraft").resolve("world-id")));
    }

    @Test
    void aCorruptFileIsReplaced(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("galaxycraft"));
        Files.writeString(dir.resolve("galaxycraft").resolve("world-id"), "not a uuid");
        assertNotNull(GalaxySave.of(dir).worldId());
    }
}
```

- [ ] **Step 2: Run it, expect FAIL** — `cd fabric && ./test.sh --tests 'dev.moui.galaxycraft.voxel.GalaxySaveWorldIdTest'` (no `worldId` yet).

- [ ] **Step 3: Implement `worldId`.** Read `GalaxySave.java:51-67` first to reuse its folder helper (`of(Path worldDir)` and the `galaxycraft` subfolder `markMade` writes to), then add:

```java
    /** A stable id for this galaxy, created on first use: how guests name their cached copy of it. */
    public java.util.UUID worldId() {
        Path file = folder().resolve("world-id"); // folder(): the same galaxycraft/ directory markMade() uses
        try {
            return java.util.UUID.fromString(Files.readString(file).trim());
        } catch (IOException | IllegalArgumentException e) {
            java.util.UUID id = java.util.UUID.randomUUID();
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, id.toString());
            } catch (IOException io) {
                GalaxyCraft.LOG.warn("Could not save the galaxy id: {}", io.toString());
            }
            return id;
        }
    }
```

If `GalaxySave` has no `folder()` helper, resolve `worldDir.resolve("galaxycraft")` the way `markMade()` does and keep the same field.

- [ ] **Step 4: Payloads.** `MpPayloads.java` (use `CustomPacketPayload.codec(StreamMemberEncoder, StreamDecoder)` and `RegistryFriendlyByteBuf`; byte arrays with `writeByteArray` / `readByteArray(max)`; strings with `writeUtf(s, 262144)` / `readUtf(262144)`):

```java
package dev.moui.galaxycraft.net;

import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public final class MpPayloads {
    private MpPayloads() {}

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String name) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("galaxycraft", name));
    }

    public record Hello(int version, String worldId, String galaxyJson) implements CustomPacketPayload {
        public static final Type<Hello> TYPE = type("mp_hello");
        public static final StreamCodec<RegistryFriendlyByteBuf, Hello> CODEC = CustomPacketPayload.codec(
                (p, b) -> { b.writeVarInt(p.version); b.writeUtf(p.worldId, 64); b.writeUtf(p.galaxyJson, 262144); },
                b -> new Hello(b.readVarInt(), b.readUtf(64), b.readUtf(262144)));
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record HelloAck(int version, boolean ok) implements CustomPacketPayload {
        public static final Type<HelloAck> TYPE = type("mp_hello_ack");
        public static final StreamCodec<RegistryFriendlyByteBuf, HelloAck> CODEC = CustomPacketPayload.codec(
                (p, b) -> { b.writeVarInt(p.version); b.writeBoolean(p.ok); },
                b -> new HelloAck(b.readVarInt(), b.readBoolean()));
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record State(byte[] data) implements CustomPacketPayload {
        public static final Type<State> TYPE = type("mp_state");
        public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = CustomPacketPayload.codec(
                (p, b) -> b.writeByteArray(p.data), b -> new State(b.readByteArray(Wire.SIZE + 8)));
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Peer(UUID id, byte[] data) implements CustomPacketPayload {
        public static final Type<Peer> TYPE = type("mp_peer");
        public static final StreamCodec<RegistryFriendlyByteBuf, Peer> CODEC = CustomPacketPayload.codec(
                (p, b) -> { b.writeUUID(p.id); b.writeByteArray(p.data); },
                b -> new Peer(b.readUUID(), b.readByteArray(Wire.SIZE + 8)));
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Skin(UUID id, byte[] png) implements CustomPacketPayload {
        public static final int MAX = 24 * 1024;
        public static final Type<Skin> TYPE = type("mp_skin");
        public static final StreamCodec<RegistryFriendlyByteBuf, Skin> CODEC = CustomPacketPayload.codec(
                (p, b) -> { b.writeUUID(p.id); b.writeByteArray(p.png); },
                b -> new Skin(b.readUUID(), b.readByteArray(MAX)));
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Gone(UUID id) implements CustomPacketPayload {
        public static final Type<Gone> TYPE = type("mp_gone");
        public static final StreamCodec<RegistryFriendlyByteBuf, Gone> CODEC = CustomPacketPayload.codec(
                (p, b) -> b.writeUUID(p.id), b -> new Gone(b.readUUID()));
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Both sides: the types must be known on each end. */
    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(Hello.TYPE, Hello.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(HelloAck.TYPE, HelloAck.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(State.TYPE, State.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Peer.TYPE, Peer.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Skin.TYPE, Skin.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Skin.TYPE, Skin.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Gone.TYPE, Gone.CODEC);
    }
}
```

`readByteArray(max)` throws `DecoderException` on larger input, which Minecraft turns into a disconnect of that connection: acceptable for oversized garbage (the size is checked before allocation). `Skin` is registered in both directions with the same type id; Fabric allows one type per direction.

- [ ] **Step 5: `MpServer`.**

```java
package dev.moui.galaxycraft.net;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.voxel.GalaxySave;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/** Fabric glue: Relay's Sink is the server's players. Does nothing until someone joins. */
public final class MpServer {
    private static final long ACK_TICKS = 100; // 5 s
    private static Relay relay;
    private static MinecraftServer server;
    private static final Map<UUID, Long> waiting = new HashMap<>();

    private MpServer() {}

    private static ServerPlayer player(UUID id) {
        return server == null ? null : server.getPlayerList().getPlayer(id);
    }

    private static final Relay.Sink SINK = new Relay.Sink() {
        public void peerState(UUID to, UUID from, PeerState s) {
            ServerPlayer p = player(to);
            if (p != null) ServerPlayNetworking.send(p, new MpPayloads.Peer(from, Wire.encode(s)));
        }
        public void peerGone(UUID to, UUID gone) {
            ServerPlayer p = player(to);
            if (p != null) ServerPlayNetworking.send(p, new MpPayloads.Gone(gone));
        }
        public void kick(UUID who, String reason) {
            ServerPlayer p = player(who);
            if (p != null) p.connection.disconnect(Component.literal(reason));
        }
    };

    public static void init() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> {
            server = srv;
            if (relay == null) relay = new Relay(SINK);
            ServerPlayer p = handler.getPlayer();
            // The host's own client needs no Hello: it owns the galaxy. Only remote connections get one.
            if (srv.isSingleplayerOwner(p.nameAndId())) { relay.join(p.getUUID()); return; }
            GalaxySave galaxy = GalaxySave.of(srv.getWorldPath(LevelResource.ROOT));
            String json = galaxy.galaxyJson().orElse(null); // see Step 6
            if (json == null) { SINK.kick(p.getUUID(), "The host's galaxy is not ready yet. Try again in a moment."); return; }
            relay.join(p.getUUID());
            waiting.put(p.getUUID(), (long) srv.getTickCount());
            ServerPlayNetworking.send(p, new MpPayloads.Hello(Protocol.VERSION, galaxy.worldId().toString(), json));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, srv) -> {
            UUID id = handler.getPlayer().getUUID();
            waiting.remove(id);
            if (relay != null) relay.leave(id);
        });
        ServerPlayNetworking.registerGlobalReceiver(MpPayloads.HelloAck.TYPE, (payload, ctx) -> {
            UUID id = ctx.player().getUUID();
            waiting.remove(id);
            Protocol.refusal(Protocol.VERSION, payload.version()).ifPresent(why -> SINK.kick(id, why));
            if (!payload.ok()) SINK.kick(id, "The guest could not enter the galaxy.");
        });
        ServerPlayNetworking.registerGlobalReceiver(MpPayloads.State.TYPE, (payload, ctx) -> {
            if (relay != null) relay.state(ctx.player().getUUID(), payload.data(), System.nanoTime());
        });
        ServerPlayNetworking.registerGlobalReceiver(MpPayloads.Skin.TYPE, (payload, ctx) -> {
            UUID from = ctx.player().getUUID();
            for (ServerPlayer other : ctx.server().getPlayerList().getPlayers())
                if (!other.getUUID().equals(from)) ServerPlayNetworking.send(other, new MpPayloads.Skin(from, payload.png()));
        });
        ServerTickEvents.END_SERVER_TICK.register(srv -> {
            if (waiting.isEmpty()) return;
            waiting.entrySet().removeIf(e -> {
                if (srv.getTickCount() - e.getValue() < ACK_TICKS) return false;
                SINK.kick(e.getKey(), "The guest's Super Minecraft Galaxy did not answer. Is the mod installed and the same version?");
                return true;
            });
        });
    }
}
```

Verify before compiling: `isSingleplayerOwner`'s exact signature and `handler.getPlayer()` with `javap -p -cp ~/.gradle/caches/fabric-loom/26.3/minecraft-common.jar net.minecraft.server.MinecraftServer | grep -i owner` and `net.minecraft.server.network.ServerGamePacketListenerImpl`; adapt the host test to whatever 26.3 offers (compare `srv.getSingleplayerProfile()` with the player's profile id if needed). The host player must also be in the relay so guests see them.

- [ ] **Step 6: Add `GalaxySave.galaxyJson()`.** The host's galaxy as the JSON `writeGalaxy` would write, `Optional<String>`: read `GalaxySave.java:69-100` and return `galaxy().map(GSON::toJson)`. Add a unit test next to `GalaxySaveWorldIdTest`: write a `Galaxy` with `writeGalaxy`, assert `galaxyJson()` is present and `GSON.fromJson` gives an equal `Galaxy`.

- [ ] **Step 7: Register in `GalaxyCraft.onInitialize()`:** add `dev.moui.galaxycraft.net.MpPayloads.register(); dev.moui.galaxycraft.net.MpServer.init();`.

- [ ] **Step 8: Run all tests and compile client + server.**

Run: `cd fabric && ./test.sh` — Expected: all tests pass (baseline count + the new ones). Then `./gradlew compileJava compileClientJava --console=plain -q` — Expected: no errors.

- [ ] **Step 9: Commit**

```bash
git add -A fabric
git commit -m "mp: payloads, server relay wiring, galaxy world id and JSON for Hello

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Guest entry (Hello -> galaxy) and MpClient

**Files:**
- Create: `fabric/src/client/java/dev/moui/galaxycraft/client/MpClient.java`
- Modify: `client/GalaxyWorlds.java` (route non-singleplayer joins), `client/GalaxyCraftClient.java` (register in `onInitializeClient`; call `MpClient.tick` once per client tick; clear on DISCONNECT at `:163`)
- Test: `fabric/src/test/java/dev/moui/galaxycraft/net/GuestFolderTest.java` (pure path rule, in `net`)

**Interfaces:**
- Consumes: `MpPayloads.Hello/HelloAck`, `GalaxySave.of(Path)`, `GalaxySave.writeGalaxy`, `PlanetClient.enterWorld(GalaxySave)`, `PlanetStore.dataDir(osName, env, home)`.
- Produces: `GuestFolder.of(Path dataDir, String worldId)` (pure, in `net`): returns `dataDir/guest/<worldId>` after rejecting any `worldId` that is not a UUID string (path traversal); `MpClient.onHello(Minecraft, Hello)`; `MpClient.connected()` (true on a remote server with a valid hello), `MpClient.remotes()` (`RemotePlayers`, Task 6).

- [ ] **Step 1: Failing test**

```java
package dev.moui.galaxycraft.net;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GuestFolderTest {
    @Test
    void usesTheWorldIdUnderGuest() {
        String id = "123e4567-e89b-12d3-a456-426614174000";
        assertEquals(Path.of("/d/guest", id), GuestFolder.of(Path.of("/d"), id));
    }

    @Test
    void rejectsAnythingThatIsNotAUuid() {
        for (String bad : new String[] {"../x", "a/b", "", "..", "123e4567"})
            assertThrows(IllegalArgumentException.class, () -> GuestFolder.of(Path.of("/d"), bad), bad);
    }
}
```

- [ ] **Step 2: Run, expect FAIL** (`GuestFolder` missing). **Step 3: Implement**

```java
package dev.moui.galaxycraft.net;

import java.nio.file.Path;
import java.util.UUID;

/** Where a guest caches a host's galaxy. The id comes from the network, so it must be a UUID, nothing else. */
public final class GuestFolder {
    private GuestFolder() {}

    public static Path of(Path dataDir, String worldId) {
        UUID id = UUID.fromString(worldId); // IllegalArgumentException for anything else
        if (!id.toString().equals(worldId.toLowerCase(java.util.Locale.ROOT))) throw new IllegalArgumentException(worldId);
        return dataDir.resolve("guest").resolve(id.toString());
    }
}
```

- [ ] **Step 4: `MpClient.onHello`.** In `GalaxyCraftClient.onInitializeClient` register `ClientPlayNetworking.registerGlobalReceiver(MpPayloads.Hello.TYPE, (p, ctx) -> ctx.client().execute(() -> MpClient.onHello(ctx.client(), p)))`. Implementation:

```java
static void onHello(Minecraft client, MpPayloads.Hello hello) {
    var refusal = Protocol.refusal(Protocol.VERSION, hello.version());
    try {
        if (refusal.isPresent()) { ClientPlayNetworking.send(new MpPayloads.HelloAck(Protocol.VERSION, false)); return; }
        Path dir = GuestFolder.of(PlanetStore.dataDir(System.getProperty("os.name"), System::getenv, System.getProperty("user.home")), hello.worldId());
        GalaxySave save = GalaxySave.of(dir);
        GalaxySave.Galaxy galaxy = GalaxySave.GSON.fromJson(hello.galaxyJson(), GalaxySave.Galaxy.class); // expose GSON package-private -> public static
        if (galaxy == null) throw new IllegalArgumentException("empty galaxy");
        save.markMade();
        save.writeGalaxy(galaxy);
        PlanetClient.enterWorld(save);
        EnteringScreen.show(client);
        connected = true;
        ClientPlayNetworking.send(new MpPayloads.HelloAck(Protocol.VERSION, true));
    } catch (RuntimeException | IOException e) {
        GalaxyCraft.LOG.warn("Could not enter the host's galaxy: {}", e.toString());
        ClientPlayNetworking.send(new MpPayloads.HelloAck(Protocol.VERSION, false));
    }
}
```

Read `GalaxySave.writeGalaxy` and `fromFiles` first: if the guest already has cached planets for this world id, `writeGalaxy` must not discard them (the catalog is identical by construction; skip the write if the file exists and decodes equal). Add that equality check.

- [ ] **Step 5: Route joins.** In `GalaxyWorlds.joined` the `server == null` branch stays, but must not show `EnteringScreen` before `Hello` decides: change it to start a 5 s timer in `MpClient` (`MpClient.awaitHello(client)`): if `connected` is still false after 100 client ticks, disconnect with the text "This server has no Super Minecraft Galaxy; open it from a world of the game." (use `client.disconnect(...)` as `PauseMenu` does; read it for the exact call). `MpClient.tick` counts it down. On `DISCONNECT` (existing handler) call `MpClient.reset()`.

- [ ] **Step 6: Compile and run all unit tests**, then commit.

Run: `cd fabric && ./test.sh && ./gradlew compileClientJava --console=plain -q` — Expected: PASS, no errors.

```bash
git add -A fabric
git commit -m "mp: a guest enters the host's galaxy from Hello (guest folder cache, version refusal, no-mod timeout)

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Send my state, keep remotes (`RemotePlayers`), `/tpp`

**Files:**
- Create: `client/RemotePlayers.java`
- Modify: `client/MpClient.java`, `client/GalaxyCraftClient.java` (per-tick call, `/tpp` registration next to the other client commands such as `/fly`, `/skin`)
- Test: `fabric/src/test/java/dev/moui/galaxycraft/net/StateSamplerTest.java` (pure: send cadence and flag packing) — create `net/StateSampler.java`.

**Interfaces:**
- Produces: `net.StateSampler` with `StateSampler(int everyTicks)`, `boolean due()` called once per client tick (true every `everyTicks` ticks, first call true), and `static int flags(boolean sneak, boolean sprint, boolean swim, boolean glide, boolean ground, boolean ship)`; `RemotePlayers.push(UUID, PeerState, long nanos)` (thread-safe, any thread), `remove(UUID)`, `clear()`, `Map<UUID, PeerTrack.Pose> poses(long nowNanos)` (client thread), `Optional<Vector3d> lastUniverse(UUID)` for `/tpp`, `Optional<UUID> byName(String)` via the connection's player list.

- [ ] **Step 1: Failing test** (`StateSamplerTest`): `due()` is true on tick 0, false for the next 1 tick when `everyTicks = 2`, true on the third call; `flags(true,false,false,false,true,false) == F_SNEAK | F_GROUND`. **Step 2:** run, expect FAIL. **Step 3:** implement both (4 lines each).

- [ ] **Step 4: Sender.** Each client tick while `connected` (or hosting with at least one other player: the host also sends, so guests see them; the host knows it is hosting when `Minecraft.getSingleplayerServer() != null && server.isPublished()`): when `sampler.due()` and `PlanetClient.marioUniverse() != null`, build a `PeerState` from: `marioUniverse()` (position), `frame.upGal()` (up; read `GalaxyCraftClient` around `:366` for how to reach the frame), `LocalPlayer` yaw/pitch, velocity from the difference of `marioUniverse()` between sends divided by the elapsed seconds (so it works for both movement modes), `mode` = `GalaxyCraftClient.walking() ? MODE_STEVE : MODE_MARIO` (check what `walking()` means in F6 and use the same predicate `EntityClient.self` is gated by), flags from the player, `seq` incrementing. Send `ClientPlayNetworking.send(new MpPayloads.State(Wire.encode(s)))`. Not on a station? Stations use their own space: read `StationClient` and send nothing while its position is not in universe units (document the skip in a comment; MP-1 does not show station interiors).

- [ ] **Step 5: Receive.** Register receivers for `Peer` (decode with `Wire.decode`, catch `Wire.Malformed`, `remotes.push(...)`) and `Gone` (`remotes.remove`). `RemotePlayers` is a `ConcurrentHashMap<UUID, PeerTrack>`; because `PeerTrack` is not thread-safe, `push` is called on the client thread through `client.execute`, so no locking is needed inside `PeerTrack`.

- [ ] **Step 6: `/tpp <name>`.** Teleport next to the peer's last universe position: use the same path `MapClient`/`Warp` use to land at a universe point (read `Warp.java` and `GalaxyCraftClient.moveTo` at `:462`); offset 2 blocks along the peer's up vector. Unknown name: `say("no such player")`.

- [ ] **Step 7:** `./test.sh && ./gradlew compileClientJava -q`; commit `mp: send my state at 10 Hz, keep remote tracks, /tpp`.

---

### Task 7: Draw remote players (Steve mode)

**Files:**
- Modify: `client/EntityClient.java` (split `self()` at `:688-715`; add `remotes(...)` called wherever `self(...)` is called, `:150-160` region), `client/RemotePlayers.java`
- Test: covered by the probe (Task 10) plus a pure test of the matrix builder `net/AvatarFrame.java` in `AvatarFrameTest`.

**Interfaces:**
- Produces: `net.AvatarFrame.matrix(Vector3d position, Vector3d up, float yawDeg)` returning a `Matrix4d` whose columns are the avatar's x, y (= up), z axes and origin = position, where yaw 0 faces the same direction as Minecraft's yaw 0 on a flat plane with up = +Y, turning about `up`.

- [ ] **Step 1: Failing test** — for `up = (0,1,0)`, `yaw = 0`: matrix is the identity rotation with translation = position; for `yaw = 90` the z axis rotates about +Y by 90 degrees (compare to `new Matrix4d().rotateY(Math.toRadians(-90))` if Minecraft yaw is clockwise from above; confirm the sign against `EntityClient.self`, which for `up = +Y` produces `galaxy` from `frame.toGal`, and write the test with the sign you measured there); for `up = (1,0,0)` the y column equals up and the three axes stay orthonormal (`det = 1`).
- [ ] **Step 2:** run, expect FAIL. **Step 3:** implement with `Matrix4d.rotateTowards`-free math: pick `fwd0` = any unit vector perpendicular to `up` (use the cross with the world axis least aligned to `up`), rotate it about `up` by `yaw`, `x = up × z`.
- [ ] **Step 4: Drawing.** Extract from `self()` the part after `EntityRenderState st = r.createRenderState(player, pt)` into `drawAvatar(AbstractClientPlayer who, Matrix4d galaxyMatrix, float pt, int skin, List<EntityWire.Piece> out)`; `self()` calls it with its frame-built matrix (behavior unchanged: run the existing probes afterwards). `remotes(pt, out)` loops `RemotePlayers.poses(System.nanoTime())`, converts `GameOrigin.toGame(universe)` for position, calls `AvatarFrame.matrix`, looks up the vanilla entity with `Minecraft.getInstance().level.getPlayerByUUID(id)` (skip the peer if absent: not yet spawned in the client's level), copies animation inputs from the pose flags (`isCrouching`, `isFallFlying`, swimming) onto the render state, and sorts by distance to the local player, skipping beyond the piece budget (`Layout.ENT_*`).
- [ ] **Step 5:** `./test.sh` — all green; `./gradlew compileClientJava -q`; commit `mp: remote players drawn with Minecraft's player renderer at their universe position`.

---

### Task 8: Skins and name tags

**Files:**
- Modify: `client/MpClient.java`, `client/SkinClient.java`, `client/EntityClient.java`
- Test: `fabric/src/test/java/dev/moui/galaxycraft/net/SkinGateTest.java` + `net/SkinGate.java`

**Interfaces:**
- Produces: `SkinGate.accept(byte[] png)`: true only if `0 < length <= MpPayloads.Skin.MAX` and the bytes start with the PNG signature `89 50 4E 47 0D 0A 1A 0A`.

- [ ] **Step 1: Failing test** for `SkinGate` (empty, too big, wrong signature, good). **Step 2:** FAIL. **Step 3:** implement (8 lines).
- [ ] **Step 4:** on join and whenever `/skin` changes, if the local custom skin PNG passes `SkinGate`, send `MpPayloads.Skin(localUuid, png)`; on receive, if it passes the gate, decode with `NativeImage.read` in a try / catch, register it via the same path `EntityClient.customSkin()` uses (`addSkin`), and use it for that peer in `drawAvatar` (`skin` parameter). A bad skin falls back to the Minecraft skin with one log line.
- [ ] **Step 5:** name tag: `remotes(...)` adds a nameplate through the same capture path Minecraft uses for `EntityRenderState.nameTag` (the player renderer already submits it when `nameTag` is set on the state; set it from the profile name, unless the pose has `F_SNEAK` or the distance is > 64 blocks). Verify in the probe screenshot.
- [ ] **Step 6:** tests and compile, commit `mp: custom skins travel once, name tags`.

---

### Task 9: Mario-mode spike, then decision

**Files:** `docs/superpowers/specs/2026-10-13-galaxycraft-multiplayer-mp1-design.md` (record the outcome), `client/EntityClient.java`.

Time-box: one working session. Question: can a remote Mario be drawn as extra pieces with an acceptable model and poses?

- [ ] **Step 1:** find how `ToadClient` obtains its Kinopio model data (`toad/ToadModels.java`, `ToadType.java`) and whether the same route can load `Mario.arc` / `MarioMini` model parts from the player's own disc (never ship Nintendo data: it must be read at runtime from the disc the launcher already extracts, as Toads do).
- [ ] **Step 2:** if yes, draw a static T-pose Mario at a remote's position behind a `GXC_REMOTE_MARIO=1` switch and take a screenshot with the probe (Task 10); if the pose set (stand, walk, jump, crouch) cannot be reached in the session, stop.
- [ ] **Step 3:** record the result in the spec ("Mario mode: drawn as Mario" or "Mario mode: drawn as Steve, because ..."). If drawn as Steve: `remotes(...)` ignores `mode` (one `if`), keep sending it for later. Commit `mp: Mario remote decision (spec updated)`.

---

### Task 10: Two-client probe, docs, roadmap

**Files:**
- Create: `fabric/src/gametest/java/dev/moui/galaxycraft/gametest/MpProbe.java`, `docs/MULTIJUGADOR.md`
- Modify: `fabric/src/gametest/resources/fabric.mod.json` (register the probe, or it never runs), `tools/gxvoxel.sh` (new `mp` subcommand), `ROADMAP.md` (Now: Multiplayer MP-1)

- [ ] **Step 1:** read `LauncherProbe.java` and `tools/gxvoxel.sh` `launch` / `boot` for how a host client game test is started, and `fabric.mod.json` in gametest resources for registration.
- [ ] **Step 2:** `MpProbe`: host opens the world to LAN (`server.publishServer(...)`, port 25599), a second Minecraft process started by `gxvoxel.sh mp` joins `localhost:25599` without Dolphin (check how `DolphinStarter` is skipped; add `-Dgalaxycraft.noDolphin=true` honoring in `DolphinStarter.start` if there is no such switch). Assertions, printed as `mp PASS` / `mp FAIL <why>`: guest received `Hello` and its `UniverseClient.universe()` seed equals the host's; each side's `RemotePlayers.poses()` contains the other within 1 unit of the sent position after 3 s; after the guest leaves, the host's track is gone within 1 s; a guest with a bumped `Protocol.VERSION` (system property override in the probe only) is disconnected with the version text.
- [ ] **Step 3:** run it only when `pgrep -af dolphin` is empty (otherwise ask the user which session uses Dolphin), `tools/gxvoxel.sh mp`, expect `mp PASS`.
- [ ] **Step 4:** `docs/MULTIJUGADOR.md` in Spanish (the other `docs/*.md` for players are Spanish): how to host (Open to LAN, IP), how to join (Direct Connection), same mod version, what is shared in this version and what is not yet (edits, mobs), `/tpp`, checklist for the playtest (two machines or the dual boot; join, see each other on a planet and in space, Steve mode, leave and rejoin).
- [ ] **Step 5:** `ROADMAP.md`: add under **Now** a row "Multiplayer (MP-1: connect and see each other)" with status `now`, built on `feat/multiplayer`, awaiting playtest, link the spec; keep the Inbox entry (MP-2 and MP-3 stay there as the next parts); bump "Last updated". Do not touch the public Claude Doc until the user playtests.
- [ ] **Step 6:** full `./test.sh`, the existing probes that exercise `self()` (`tools/gxvoxel.sh walk` or whichever the baseline used) to show the `drawAvatar` extraction changed nothing, then commit `mp: probe, player docs and roadmap`.

---

## Self-review against the spec

- Join flow (Hello, version, no-mod timeout, guest folder, `/tpp`): Tasks 4, 5, 6. Required channel: registration in Task 4 uses Fabric's default (channels are required only if both sides register the type; a vanilla client is refused when a server-sent payload type is unknown at login: confirm in the probe and, if not, add the explicit check in `MpServer.JOIN` with `ServerPlayNetworking.canSend(p, Hello.TYPE)`; a client lacking it is kicked with the text from the spec).
- Messages and limits (rates, sizes, malformed kick, 8 players): Tasks 1, 2, 4.
- Remote players (interpolation numbers, hide, Steve mode, budget, skins, name tags): Tasks 3, 6, 7, 8. Mario mode and fallback: Task 9.
- Failure table: server without mod (5), mismatch (1, 4), Hello never arrives (4 server side, 5 client side), malformed (2), host quits (existing DISCONNECT handler plus `MpClient.reset`), skin (8), absurd state (1, 3).
- Testing section: unit (1-3, 5, 6, 8), in-process relay (2), two real clients (10), playtest doc (10).
- Types to keep identical across tasks: `PeerState` fields and order, `Wire.SIZE`, `PeerTrack.Pose`, `RemotePlayers.poses(long) -> Map<UUID, PeerTrack.Pose>`, `MpPayloads.*` record names.
