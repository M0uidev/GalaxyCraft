# Multiplayer, part 1: connect and see each other (MP-1)

Date: 2026-10-13. Branch: `feat/multiplayer`. Status: design approved by the user (full delegation).

## The whole feature, in three parts

The user wants to play with friends in one world, flying freely between planets. Host-authoritative:
one player opens their world to LAN, guests join by direct IP and run their own Dolphin + SMG2 + mod.

| Part | What you can do when it is done |
|---|---|
| **MP-1 (this spec)** | Join a friend's world, share the universe, see each other move (Steve with skin or Mario). |
| MP-2 | Planet edits are shared (break / place), a guest receives the planet on entering. |
| MP-3 | Shared simulation: mobs, drops, fluids, redstone, `ShadowWorld` serving several players. |

Each part gets its own spec, plan and branch work. MP-1 must leave the game playable and shippable alone.

## What exists today (checked in the code, 2026-10-13)

- Planets are **client-owned**: `VoxelPlanet` / `PlanetSession` live on the client thread and are saved by
  `PlanetStore` in the user's data dir. The integrated server only runs `ShadowWorld`, a mirror dimension where
  the blocks near Mario behave like Minecraft's. There is **no server networking** in the mod (no payloads).
- `GalaxyWorlds.joined` returns early on a server that is not the client's own (`getSingleplayerServer() == null`):
  no galaxy is entered.
- The universe is deterministic from the world's seed (`UniverseClient`, `Universe`).
- `EntityClient.self(...)` already draws the local player with Minecraft's own renderer from a gravity frame
  when F6 (Minecraft movement) is on; `EntityCapture` + `EntityWire` turn renderer output into pieces for SMG2.
  Toads are drawn through the same pieces path (`ToadClient`).
- Each player has two positions: Minecraft's entity position (rebased, meaningless to others) and Mario's
  position in galaxy units.

## Goals

1. A guest with the same mod version joins a LAN-opened world by direct IP and enters **the host's galaxy**
   (same seed, same galaxy options, same planets by generation).
2. Everyone sees everyone else, smoothly, in the right place and orientation on planets (any gravity direction).
3. Joining and leaving are robust: wrong version, no mod, vanilla client, dropped connection, host quitting.
4. Cheap: no measurable frame cost with up to 8 players; network under ~1 KB/s per player.

## Non-goals (MP-1)

Shared planet edits (MP-2), shared mobs / drops / redstone (MP-3), dedicated server, relay / join codes,
anticheat or permissions, voice, custom chat, more than 8 players, guests with a different mod version.
Until MP-2, each player's own edits are local: the others may see someone standing on a block that is
missing for them. This is expected and said in the player-facing text.

## Architecture

### Roles

- **Host**: the player whose integrated server was opened to LAN. Owns the galaxy folder, as today.
- **Guest**: connects with Minecraft's Direct Connection. Needs no world of their own.
- The server side does only handshake validation and **relaying**; it holds no game state in MP-1.
  Clients are trusted (friends); the server still validates sizes and rates so a bad packet cannot hurt it.

### Channel

Fabric payloads (`PayloadTypeRegistry`) on Minecraft's existing connection; no new sockets. All payload
types are registered in `main` (both sides), handled in `client` / `main` respectively. A new package
`dev.moui.galaxycraft.net` holds the pure parts (codec, version, interpolation, rate limit) with no
Minecraft types so it is unit tested like `proto`.

Messages (protocol version `1`; first field of the handshake pair):

| Message | Direction | Content |
|---|---|---|
| `Hello` | server -> client, once on join | protocol version, mod version, worldId (UUID of the host's galaxy folder), universe seed, galaxy options blob (what `GalaxySave` / `GalaxyOptions` need to rebuild the catalog) |
| `HelloAck` | client -> server | protocol version, ok / refusal reason |
| `State` | client -> server, ~10 Hz | see below |
| `PeerState` | server -> clients | player UUID + the `State` fields, relayed to every other player |
| `Skin` | client -> server -> clients, once per change | player UUID, hash, PNG bytes (<= 24 KB; larger is not sent) for the `/skin` custom skin |
| `PeerGone` | server -> clients | player UUID (also implied by Minecraft's own player-list removal) |

`State` fields: **universe** position (3 doubles; each client has its own floating origin, so the receiver maps it with `GameOrigin.toGame`), up vector (3 floats), look direction (3 floats, a unit vector in the universe axes; a Minecraft yaw means something different in each client's gravity frame, so it is never sent),
velocity (3 floats, for extrapolation), mode (Steve / Mario), flags (sneak, sprint, swimming, gliding, on ground,
on ship), a sequence number. 67 bytes: 10 Hz x 7 peers is well under 5 KB/s.

Limits (server side, per sender): at most 20 `State` / s (extra dropped), payload size checked before decode,
unknown / malformed payloads ignored and counted; 5 consecutive malformed ones disconnect the sender.
Custom payloads from client to server are capped at 32 KB by Minecraft, which is why `Skin` is <= 24 KB.

### Join flow

1. Guest connects. Server (mod present) sends `Hello`. A server without the mod sends nothing: today's
   behavior stays (`EnteringScreen` only, no galaxy), with a short screen text saying the server has no Super
   Minecraft Galaxy.
2. Guest compares protocol version. Mismatch: `HelloAck(refused)`, the server disconnects with a message
   naming both versions ("update the mod to X"). The mod is **required** to join (the channel is registered as
   required), so a vanilla client is refused by Minecraft with its standard missing-channel text.
3. Guest builds a **guest galaxy folder** under the data dir (`guest/<worldId>/`) so planets generated from the
   seed are cached between sessions, and runs `PlanetClient.enterWorld` on it. The host's world folder is
   never touched.
4. Guest spawns where a new host spawn would: the galaxy's start planet. `/tpp <player>` (client command)
   teleports the player next to another, using the last `PeerState`.
5. On disconnect (either side) the guest's remote players are cleared and the normal leave path runs.

### Remote players

- `RemotePlayers` (client, pure core + thin Minecraft glue): keeps per player the last states in a ring of 3,
  and computes the pose to draw at render time **100 ms behind** the newest state (one interval + jitter),
  interpolating position linearly (100 ms is far too short to bend), the up vector spherically, and the angles by the short way; when
  the newest state is older than 250 ms it extrapolates with the sent velocity for up to 250 ms more, then
  holds, and after 5 s without states hides the player.
- **Steve mode**: drawn with Minecraft's player renderer using the vanilla remote player entity the client
  already has for that UUID (it supplies the skin, name, hand items and animation state), but **placed by our
  galaxy position and up vector**, never by the entity's Minecraft position, because each player rebases
  Minecraft coordinates differently. Reuses the `self()` code path split into a shared "draw an avatar at a
  frame" method. The `/skin` custom skin is applied from `Skin` messages.
- **Mario mode**: SMG2 has one real Mario, so a remote Mario is drawn as extra model pieces, like Toads.
  Plan: a Mario model built from the game's own Mario model data, with a small set of poses (stand, walk,
  run, jump, fall, crouch, swim, glide) chosen from the flags. **Risk**: if the model cannot be extracted or
  posed in an acceptable time, the **fallback is to draw the remote as Steve** (flag `mode` is still sent,
  the choice is local to the drawing code). This is decided by a time-boxed spike as the first task of
  Mario-mode work and recorded here before continuing.
- Name tags: Minecraft's name, drawn above each remote player (a nameplate quad through the same pieces path),
  hidden when farther than 64 blocks or when sneaking.
- The piece budget per frame (`Layout.ENT_*` limits) is respected: remote players are drawn first-come by
  distance; beyond the budget the farthest are skipped, never the local ones.

### Failure handling

| Case | Behavior |
|---|---|
| Server without mod | No galaxy, a clear text, game keeps working as vanilla multiplayer did |
| Protocol mismatch | Disconnected with both versions in the message |
| `Hello` never arrives (5 s after join) | Guest leaves with "the host's mod did not answer" |
| Malformed / oversized payload | Dropped and counted, 5 in a row disconnect the sender |
| Host quits | Normal Minecraft disconnect; guest returns to the title, galaxy cache kept |
| Guest leaves | `PeerGone`; their remote disappears at once |
| Skin too large / invalid | Peer drawn with their Minecraft skin; one log line |
| Peer state absurd (NaN, farther than the universe) | State ignored |

## Performance and robustness

- No allocation per frame in the interpolation core (preallocated ring and vectors).
- Network work is on Minecraft's netty / server threads only to decode and relay; drawing state is handed to
  the client thread through a single `ConcurrentHashMap<UUID, PeerSlot>` (latest-wins), as `ShadowWorld` does
  with its queues.
- Everything optional: with `multiplayer` off in the host's options nothing is registered at runtime and
  single-player is byte-for-byte what it is today (a test checks that no payload is sent in singleplayer).

## Testing

1. **Unit (no Minecraft)**: codec round-trip for every message; truncated / oversized / garbage input; version
   negotiation; rate limiter; interpolation (straight line, around a sphere, jitter, packet loss, extrapolation
   end, hide after 5 s); skin size gate.
2. **In-process integration**: a dedicated-server-style gametest where two fake connections run the handshake
   and relay (server logic is plain Java behind a small interface), asserting `PeerState` fan-out, version
   refusal and the malformed-input disconnect.
3. **Two real clients on one machine** (`tools/gxvoxel.sh mp`, new probe): host client opens LAN, a second
   Minecraft client (without Dolphin; the plan checks whether a switch for that exists and adds one if not) joins `localhost`; the probe asserts the guest enters the
   host's galaxy (same seed / catalog hash) and that each side's `RemotePlayers` has the other with a
   position within tolerance of what was sent, then that the entity frame contains the remote's pieces.
   Never run while another session's Dolphin is up (see memory `gxplay-lag`).
4. **Player playtest** (user): checklist in `docs/MULTIJUGADOR.md`: two machines or the dual boot, join by IP,
   see each other on a planet and in space, Steve and Mario modes, leave and rejoin.

## Open items (to settle in the plan, not blocking)

- Exact `GalaxyOptions` serialization for `Hello` (reuse `GalaxySave` fields).
- Where the start spawn of a guest is chosen (host's start planet vs nearest to the host).
- Whether Mario mode needs the model from the DOL data or from the arc (the spike answers).

## Decisions made while building (2026-10-13)

- **Look, not yaw.** `State` carries a unit look vector in universe axes (see Channel); the receiver derives the body
  heading (look projected onto the tangent plane of `up`) and the head pitch.
- **`/tpp` moved out of MP-1.** Teleporting to a point of the universe has no primitive today (warps go through the
  map and its progression gate); it needs its own small design. Players reach each other by flying.
- **A server without the mod** is not refused: it plays as plain Minecraft. A guest only waits for `Hello` and
  disconnects after 5 s when the server does speak our channels but never sends it.
