# Multiplayer, part 3: shared simulation (MP-3)

Date: 2026-10-14. Branch: `feat/multiplayer-3` (from master c1465b3). Status: the user delegated the whole of
MP-3 ("trabaja todo el día... decide, anota las decisiones y sigue"); the decisions below are mine, made under
that delegation. Follows [MP-2](2026-10-14-galaxycraft-multiplayer-mp2-design.md).

## What you can do when it is done

A guest uses blocks as the host does (opens doors and chests, flips levers, presses buttons, sleeps), sees the
mobs and dropped items of the planet, hits and rides them, and picks drops up. Each step leaves the game playable
and testable on its own.

## What the code gives us (read 2026-10-14)

- Minecraft's behaviour of blocks near Mario is run by the **host's integrated server** in the dimension
  `galaxycraft:shadow` (`ShadowWorld`, one static instance: ONE running planet, the host's focus, mirrored cell by
  cell from the host's `VoxelPlanet`; what the shadow changes comes back as `Change`s to the planet).
- Every planet change on the host, wherever it came from, reaches the guests that hold the planet through the
  MP-2 observer (`HostPlanets.record` -> `Edits`). So **a door a guest opens in the shadow reaches every guest as
  an ordinary edit**, with no new block protocol.
- A guest is a real `ServerPlayer` on the host's server (it has an inventory, a game mode, menus). `ShadowWorld.use/
  destroy/give` already take a player UUID; `ShadowReachMixin` and `ShadowOpenersMixin` already treat any player.
  Chest and crafting menus therefore open on the guest's screen by vanilla's own packets.
- Parts that assume "the player is the host": Mario's stand-in (`MarioProxy`, one, follows the host), the sign
  editor (a consumer on the host's client), beds (`Bed` queue read by the host), `attack/interact` (use the
  proxy), `give`/drops (`DropsClient`, planet drops live on the host's client only).

## Decisions

1. **Guests ask, the host runs.** A guest never simulates. Its right click on a block goes to the host as a `Use`
   request; the host runs it in the shadow as that guest's `ServerPlayer`, and the answer is `UseDone(nonce,
   passed)`. `passed` = nothing consumed the click, so the guest places its block (MP-2's local prediction), exactly
   what `ShadowWorld.use(..., onPass)` already means for the host.
2. **Only the planet the shadow runs.** The shadow mirrors the host's focus planet. A `Use` for another planet is
   answered `passed` at once (the guest just places blocks, as in MP-2). Known limit, recorded in the roadmap. (A
   second shadow strip per held planet is possible, ShadowMap has a z-strip per stage, but `ShadowWorld`'s
   static state is one planet; generalizing it is a separate piece of work, not needed for friends on one planet.)
3. **Latency kept low.** The guest asks only when the click can matter: the block has a use (`McBlocks.usable`) or
   the held item is not a plain block/bucket/empty hand. A plain block placed on stone is local and immediate.
4. **Consumed blocks.** Placing a block outside creative must use one up in the guest's inventory, which lives on the
   host's server (`useUp` only worked in single player). A tiny `UsedUp(hand)` message does it on the server.
5. **Per-player state stays per player.** Beds set a respawn only for whoever slept (the `Bed` carries the UUID; the
   host's client ignores other players' beds, the guest's spawn point is not kept in step 1). The sleep itself
   (skip the night) is the world's. The sign editor and the boat (both use the host's Mario) are host-only for
   now: a guest using a sign or a boat item gets "consumed, nothing happens" (documented).
6. **Wire protocol 4**, two new messages only (`Use`, `UseDone`, plus `UsedUp`); old messages unchanged.
   Limits: `Use` rate-limited per guest; the planet must be one the guest holds; cell, face and hand validated.

## Steps (each ends playable)

- **A. Block use** (`Use`, `UseDone`, `UsedUp`): doors, chests, levers, buttons, crafting table, furnace, beds,
  flint and steel, hoes, bone meal, buckets stay the planet's. Tests: `UseBook` (pending clicks by nonce, timeout)
  and `UseCodec` unit tests, a host+guest simulation like `SharedEditsPipelineTest`, real two-game check.
- **B. Entities on the guest.** The host sends, per planet a guest holds and where the shadow runs, a compact
  snapshot of the shadow's entities near that guest (id, type, planet-space position, yaw/pitch, the synced data
  that decides how it looks). The guest builds light client entities and the existing `EntityClient` draws them.
  Spec detail written when A is done and `EntityClient`'s input is read for real.
- **C. Mobs and drops.** The guest's blows and right clicks on mobs go to the host (a stand-in per guest in the
  shadow, so mobs see guests and spawn around them); drops (`Drop`) become shared: picked up by whoever is
  nearer, delivered through `give`. Broken blocks by a guest go through the shadow's `destroy` (drops, tool wear).

Out of scope for MP-3 (kept in ROADMAP Inbox): going to a friend, names over heads, Mario's model for others,
a second running planet, stations, ships and Launch Stars for guests, Toads' trades for guests.
