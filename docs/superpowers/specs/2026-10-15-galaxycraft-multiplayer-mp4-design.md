# Multiplayer part 4: friends anywhere work as if the host were there

Date: 2026-10-15. Decisions are mine (the user delegated them and asked for this: "si un amigo se va a otro planeta
debe funcionar como si el anfitrión estuviera ahí, lo mismo con las space stations y todo").

## Goal

A guest standing on any planet (and later any station) gets the whole game there: doors and chests, redstone, mobs
that spawn and chase them, dropped items, mining with drops and tool wear, throwing, hitting mobs. Today all of that
exists only on the one planet the host's game is on, because the host's shadow dimension (`ShadowWorld`) runs one
planet at a time. On any other planet a guest can only place and break blocks.

## What is already true

- Every planet already has its own strip of z in the shadow (`ShadowMap.z0`, from the stage name), so several
  planets fit in the one dimension at once. A station is a `FlatGrid` with its own offsets (`ShadowMap.of`).
- A guest already holds planets from the host, and the host keeps a `VoxelPlanet` for each (the host's own, or a
  headless resident, `HostPlanets`), applying guests' edits in order and sending them on.
- The messages for step A to C (`Use`, `Break`, `MobHit`, `DropTake`, `Throw`, `Mobs`, `Drops`) carry the planet index.

## What is global in `ShadowWorld` and must become per planet

`planet`, `map`, `forced`, `mirrored`, `toMirror`, `mirroring`, `mirrorX`, `mario` (BlockPos), `marioAt`, `proxy`,
`riding`, `seat`, `steer`, `entities`, `live`, `liveCells`, the Toads (`ShadowToads`), and the client side
`ShadowLink` (one attached planet, `pending` edit numbers), `DropsClient` (the focus planet's drops), `EntityClient`.

## Design

1. **`ShadowWorld.Run`**: one object per planet with at least one player on it. It holds what is listed above for
   that planet. `ShadowWorld` keeps `Map<VoxelPlanet, Run> runs`. The host's own planet is a run like any other;
   with no guests the game behaves exactly as today (the first rule: nothing changes for a single player).
2. **Stand-ins per player.** A run has one `MarioProxy` per player on it (the host's Mario, or a guest's). Today there
   is one proxy that follows the host. Mobs spawn around and chase each, the chunk source counts each. Each proxy
   follows its player's `MarioAt` (planet, feet, look, UUID). A guest's `MarioAt` arrives in a new message.
3. **Where a guest is**: new `Where(planetIndex, feet, look)` guest -> host at ~10 Hz (planet-local coordinates, the
   same as `Use`). The host keeps `MarioAt` per guest and puts the guest in the run of that planet, creating the run
   (and holding the planet) when the first player arrives, dropping it a few seconds after the last leaves.
4. **Chunks to run**: columns near each player, per run, as `ShadowLink.follow` does for the host (planet cells
   near a point, plus the halo). This needs the chunks-near logic without a `PlanetSession`; it is moved to
   `VoxelPlanet` (a pure function of the grid and the point).
5. **Edits between a run's planet and its shadow**: planet -> shadow through a listener on the `VoxelPlanet`
   (numbered, as `ShadowLink.changed`); shadow -> planet through `Change`, applied by the owner of the planet: the
   host's session for its own planet, `HostPlanets` for a resident (it already broadcasts the edit to the guests).
6. **Drops** live in the run (a `PlanetDrops` per run, simulated on the host), not in the host's `DropsClient`.
   `Drops` snapshots go to every player on that planet; `DropTake` and `Throw` are looked up by run.
7. **Snapshots to guests** (`Mobs`, `Drops`) are made per run and sent to the players on it. The host's own game
   draws its run's from the same snapshot, so host and guest see the same.
8. **Messages**: `Use`, `Break`, `MobHit` find their run by the planet index; `ShadowWorld.runs(p)` becomes "a run
   exists for p". `Protocol` goes to 6 (Where).
9. **Stations** (second stage): a station is a `VoxelPlanet` on a `FlatGrid`; the host would send it to guests like a
   planet (today it does not) and its run is the same code with `ShadowMap.of`'s station case.

## Order of work (each step leaves the game playable and is tested)

A. Refactor `ShadowWorld` into `Run`s with one run, one proxy: no behaviour change. Guard: `ShadowProbe`,
   `gradlew test`, and the two-game check that mobs, blocks, drops still work as before.
B. Several proxies per run: a guest on the host's planet gets a stand-in (mobs chase it) via `Where`.
C. Several runs: a guest on another planet gets its own run (headless: the host holds the planet as a resident).
D. Everything keyed by run: drops, snapshots, use, mining, hits, throws on a guest's planet.
E. Stations for guests.

## Limits and risks

- Cost: every run mirrors and ticks its chunks on the host's server. A cap of runs (MAX_PLAYERS is 8, one run each
  at most) and the mirror budget (`MIRROR_NANOS`) shared between runs keep the server tick bounded.
- Two players on the same planet share one run: one set of chunks (the union of their columns).
- A run with only a guest on it has no client-side session on the host: its planet is a resident and its edits
  are applied there. The host's `LiveBlocks` and Toads are the host's own planet only until step D covers them.
- The shadow is on the integrated server: if the host quits, everyone's runs end (as everything does).
- Test: `SharedEditsPipelineTest` style pure tests for the new pure parts (chunks near a point, the player/run
  bookkeeping, `Where` codec); everything with Minecraft is checked by the user in a two-game session.
