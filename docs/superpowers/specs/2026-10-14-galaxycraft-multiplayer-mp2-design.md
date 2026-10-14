# Multiplayer, part 2: shared planet edits (MP-2)

Date: 2026-10-14. Branch: `feat/multiplayer-2` (from master a85be04). Status: design approved by the user
(full delegation, "works like Minecraft, as long as it is efficient"). Follows
[MP-1](2026-10-13-galaxycraft-multiplayer-mp1-design.md).

## What you can do when it is done

A guest and the host (and other guests) see the same planet change: what one breaks or places, the others see,
a moment later. Everyone may break and place anything for now (no permissions, no protected zones). The host's
world keeps everything the guests built.

## Decisions

- **Host-authoritative, like a Minecraft server.** A guest's break/place is applied locally at once (prediction),
  sent to the host, applied there in arrival order, and the result is sent to everyone (the author included).
  Where the host disagrees, the host wins.
- **Only the host simulates.** Leaf decay, fluids and Minecraft-side changes (a door opened in the shadow world)
  run on the host only; each change they make is broadcast as an ordinary edit. A guest never runs its own leaf
  decay or fluid tick on a host planet, and only forwards changes that its own player made.
- **The host holds every planet a guest has** (user's pick "A", 2026-10-14). A planet sent to a guest stays in the
  host's memory, unmeshed, for as long as a guest has it, so a guest can build on a planet the host is not near, and
  the host's save always contains it. Memory is paid only for planets guests are on.
- **Saving stays on the host.** Guests never write planet edits (MP-1 guards stay).
- **Out of scope (MP-3):** mobs, drops (a guest's broken block yields what the local path already gives), block
  contents (chests, signs, banners), doors/beds' server state, Toads, stations, ships, Launch Stars, redstone.
  Station planets are not catalog planets and are not shared.

## Wire protocol (version 3)

`Protocol.VERSION` goes 2 -> 3. Changes to existing messages are kept minimal; the first fields of Hello stay frozen.

| Message | Direction | Content |
|---|---|---|
| `Edits` | guest -> server | planet index, `cseq` (guest's batch number, > 0), new block names (defs), entries (cell, wire id) |
| `Edits` | server -> guest | planet index, `hseq` (the host's sequence for that planet), `origin` cseq (0: not this guest's), defs, entries |
| `PlanetHead` | server -> guest | adds `seq`: the planet's `hseq` when its bytes were taken |
| `PlanetDrop` | guest -> server | the guest no longer holds that planet; the host stops sending it edits |
| `Resync` | server -> guest | the guest fell too far behind on that planet: drop it and ask again |

One `Edits` record type with both directions registered (as `Skin`). Body: `varint index, varint seq, varint origin,
varint ndefs, (varint wireId, utf name<=160)*, varint n, (varint cellDelta, varint wireId)*`, then the whole body is
deflated when it is over 128 bytes (flag byte first). Entries are sorted by cell, so deltas are small; a typical
single break is about 8 bytes.

**Block names, not ids.** Block ids are local to each game. Each direction of each connection has its own wire
palette: the sender gives a block's name once (a def, with a wire id) before first using it. Names are parsed on
the receiver's client thread (`McBlocks.parse`); an unknown block is dropped and counted.

**Bounds on read** (as `MpPayloads`): at most 2048 entries and 64 defs per message, 4096 wire ids per palette,
inflated size capped (256 KB), names capped; anything wrong is counted, 5 in a row disconnect the sender. Cells
are checked against the planet's cell count; the planet must be one the guest holds.

## Host

### Authority: `HostPlanets` (client side of the host)

For a catalog planet index, the authoritative `VoxelPlanet` is, in order: the live session's planet; else the
**resident** (a headless `VoxelPlanet` the host holds for guests); else it is loaded from `recent`, the save, or the
bytes just made for the guest. `GalaxyStream.serve` makes the planet resident (decoding the bytes it produced
off-thread) and records the planet's `hseq` at that moment, on the game thread, together with the bytes, so
the bytes contain every edit up to `hseq`.

Hand-over: when the host promotes a planet to a live session that has a resident, the resident is flushed to the
save first (same ordered saver as `serve`), then dropped; the live planet takes over. When a live planet with
subscribers is demoted, its `VoxelPlanet` (already kept in `recent`) becomes the resident. A resident with no
subscribers is saved (if edited) and dropped. At most 8 residents; a ninth refuses new subscriptions (`PlanetHead`
FAIL).

Saving: live sessions are saved by the existing flow (applying a guest edit sets `unsaved`). A resident is saved
about 30 s after its last edit, when dropped, and when the world closes.

### Applying and broadcasting

`VoxelPlanet` gets **observers** next to its single `Listener` (which ShadowLink owns on the host): `changed(cell,
id)` fires for every change, quiet ones included (a door opened by Minecraft is a change guests must see). A
guest's own changes are tagged by origin while `HostPlanets.apply` runs. The host's observer collects a tick's
changes per planet; at the end of the client tick each planet's batch gets the next `hseq` and goes to
`MpServer.publish`. Edits from a guest are applied with `set` (not quietly) so the shadow world sees them, then
`touchLeaves` runs on the host, which is how the derived leaf removals are born and broadcast.

Rejections: an entry the host cannot apply (unknown planet or block, cell out of range, planet not held) is
answered to the author only, as a correction carrying the host's current value for that cell.

### `MpServer` (server thread)

Per guest: a set of subscriptions (planet -> state). Ask time (`PlanetAsk`) creates the subscription as
*pending*: edits for the planet that arrive before its bytes are delivered are kept in the subscription's buffer.
At delivery, head + chunks go to the guest's outbox, then the buffered edits (those with `hseq` above the head's
`seq`). That closes the gap between "bytes taken" and "bytes delivered" without ever sending an edit twice out of
order. Edits are absolute cell values, so a repeat is harmless.

Sending: edits for delivered planets go in a second small queue drained **before** the planet chunks every tick
(16 KB/tick for edits, the rest of the 64 KB budget for chunks), so a 30 MB planet in transit does not stall
building. If a guest's edit queue passes 8 MB, its subscription to that planet is cancelled and `Resync` is sent.

Receiving a guest's `Edits`: rate limit (20 messages/s, 64 KB/s), size checks, member and subscription check, then
handed to the host's game thread (`HostEdits` sink, same shape as `PlanetSource`). Only subscribed planets accept
edits from that guest.

Disconnect, `PlanetDrop` and world close remove subscriptions; the last one off a planet lets the resident go.

## Guest

`MpEdits` (client): an observer on each shared planet the guest holds, plus a receiver for `Edits`.

- **Outgoing.** Only changes made inside the player's own `breakBlock` / `placeBlock` (and the settles they trigger)
  are forwarded, via a scope flag in `PlanetSession`; fluid ticks, leaf decay and incoming edits never are. Changes
  are collected per tick into one `Edits` per planet with the next `cseq`; the cells go to a `pending` map
  (cell -> cseq).
- **Incoming.** An `Edits` for a planet the guest is still fetching is held; once the planet is in, those with
  `hseq` above the head's `seq` are applied in order. Applying uses a no-observer `set` (so nothing echoes). A cell
  with a pending local edit is skipped until a batch whose `origin` >= the pending `cseq` arrives, which clears it
  (the host's echo of our own edit, or its correction, always settles the cell). Result: no flicker, host order wins.
- **No simulation.** On a planet that came from the host, `PlanetSession.update` skips the fluid tick and leaf
  decay (guard on `MpClient.connected()`), and `touchLeaves` does nothing.
- **Lifetime.** A planet the guest stops holding (session released, dropped from the `recent` cache) sends
  `PlanetDrop` and loses its `fresh` mark, so coming back asks again (the etag makes it a 32-byte "same" answer
  when nobody edited it meanwhile; otherwise the planet is sent whole again, a known cost, see Later).
  `Resync` does the same on a planet that is open: the planet is refetched and swapped in place.
- Remote edits mesh through the normal dirty-chunk path; the guest's physics sees them as it sees its own. Far views
  do not update from edits (they are static, as on the host).

## Efficiency

- No per-tick work when nothing changes: observers append to a per-planet list only when a cell changes.
- Host: one `hseq` bump and one encode per planet per tick, however many guests; encoded once, queued to each
  subscribed guest (the origin's copy differs only in the `origin` field).
- Wire: varint deltas + deflate; per-guest edit budget; chunk budget unchanged; planets not held by a guest cost it nothing.
- Memory: residents only for planets guests hold (max 8); pending maps are per cell, cleared on echo.

## Failure handling

| Case | Behavior |
|---|---|
| Edit for a planet the host cannot hold (budget, unknown) | Correction to the author; the guest's cell returns to the host's value |
| Guest's connection slow, edit queue over 8 MB | `Resync` for that planet |
| Malformed `Edits` | Dropped and counted, 5 in a row disconnect the sender |
| Guest leaves mid-download | Subscriptions removed, resident released |
| Host leaves | Normal disconnect; the guest's folder keeps only the host's copies (never edits) |
| Two players break/place the same cell | Host order wins; both guests converge on the echo |
| Edit inside a guest's body from another player | Applied; the guest falls/moves as with its own break |

## Testing

1. **Unit (no Minecraft):** `Edits` codec round trip (defs, deltas, deflate threshold, every bound, garbage and
   truncated input); `EditBatcher` (per planet, origin tags, one hseq per flush); `Subscription` (pending buffer
   delivered after chunks, `hseq` filtering, overflow -> resync, drop); guest `Pending` reconcile (echo clears, other
   host edits skipped while pending, correction restores); edit/chunk budget split.
2. **In-process:** MpProbe (hidden) extended: a host with a world and a guest connection exchange an edit both ways
   on a small planet made in memory, asserting identical cells, ordering with a planet in transit, rejections, and
   the 5-malformed disconnect. It cannot see planets (no Dolphin); that is checked in 3.
3. **Two real games** (recipe in memory `multiplayer-mp1-test`): host and guest break/place on the same planet, on
   a planet the host is not near, same cell at once; the host quits and reopens: the guest's work is there.
   `VoxelStats` / cell dumps compared on both sides. Always `-PgalaxycraftHidden` for windowed tests.
4. **Player playtest** (user): checklist in `docs/MULTIJUGADOR.md` (updated). No merge or devlog before it.

## Later (not in MP-2)

Delta resync (an edit log per planet so returning to a planet costs only what changed), block contents and
server-side things (MP-3), permissions / protected zones, drops for guests, far views updating from edits.
