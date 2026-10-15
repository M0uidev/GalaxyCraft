# Stations for guests: research notes (read-only survey, 2026-10-15, branch feat/multiplayer-3)

Paths are under `fabric/src/{main,client}/java/dev/moui/galaxycraft/`. Line numbers are of HEAD 37e0a30.

## 1. How a station lives today (host / single player)

- **Model**: `voxel/Station` (id = 8 hex digits from `Station.newId`, name, `stage`, `center` in universe units,
  `rotation`, `planet` = a `VoxelPlanet` on a `FlatGrid`, `bounds`). `FlatGrid(n, layers, ox, oy, oz, rotation)`: cell
  (i,j,k) <-> station coordinate (j+ox, k+oy, i+oz). **`Station.regrow()` (Station.java:73) makes a NEW `VoxelPlanet` with
  another grid, so cell indices change; station coordinates never do.** `PlanetSession.swap(p)` (PlanetSession.java:307)
  installs it.
- **Storage**: not `PlanetStore`. `voxel/StationStore`: one file per id, `<world planets dir>/stations/<id>.gxstation`
  (gzip, magic GXS1: id, name, stage?, center, rotation, bounds, grid size, count, `PlanetStore.writeCells` palette of
  block names). `list()` reads headers only. Written by `StationClient.write` (StationClient.java:240) on the saver thread
  every 10 s (SAVE_TICKS) when `session.unsaved()`, and on deactivate / pack.
- **Identity**: the string id, never a catalog index. `PlanetClient.indexOf` (PlanetClient.java:480) returns 0 for any
  session not in `extras`, so a station used to look like planet 0 (fixed to -1 in `catalogIndex`, :475). Shadow key
  `"station-"+id` (`shadowKey`, :469).
- **Created**: `StationClient.place` (:292, via `StationHooks.placeCore` from the Station Core item) after
  `refusal()` (:276: open space only, 16 blocks clear of every body, <= `Station.MAX_ACTIVE`=8). Packed/unfolded:
  `pack` (:357), `unfold` (:331); a packed station has `stage == null`.
- **Placed in the universe**: `Station.center` (universe units, stage `GalaxyCraftSpace`) + rotation; `spot()` (:249)
  puts it 6 blocks ahead of the player. Not in `PlanetClient.planets()` (catalog) but in `bodies()` (:444, via
  `StationClient.sessions()`).
- **Load/show**: `StationClient.tick` (:119, called from `PlanetClient.tick` :920): every 20 ticks, headers of this stage
  within `ACTIVE`=2000 blocks of Mario become a `PlanetSession` (`activate`, :201: `spawnStation`), past 2500 they are
  saved and retired (`deactivate`, :210). The session gives the game its box gravity (module, `PlanetSession.box()`),
  collision parts and chunks like a planet. `afterPlace` (:396) does growth/limits (`Station.allowed/changed/regrow`).
- **Shadow**: `ShadowMap.of` (ShadowMap.java:70) station case: offsets by station coordinate (`STATION_Y`=48, middle 256),
  strip of z from `stage.hashCode()` where stage = the key. A regrow moves nothing in the shadow.
- **Toads**: `ToadClient.putTechnician` per station (host only; shadow Villagers).

## 2. What a guest sees today, and what breaks

- `Hello` carries the galaxy JSON only (MpServer.java:396). `HostPlanets`/`MpPlanets` serve and fetch by **catalog index**
  only (`PlanetAsk(index, etag)`, `MpPlanetHost.ask` -> `PlanetClient.serve(index)` -> `GalaxyStream.serve`).
  The guest's `StationClient.enterWorld(g.planets())` (PlanetClient.java:1217) points at the guest's OWN world folder,
  so the guest sees only stations from its own single-player history (wrong world, possibly a clash of ids) and never the
  host's. A station the host built is simply absent for the friend (not even a dot); the friend flies through it.
- A guest that lands on its own station gets no mp wiring: `MpUse.whereNone()` (PlanetClient.java:850, the interim fix),
  `catalogIndex == -1` gates Use/Break/Throw (:208, :1699, :1813), `EntityClient.java:262` / `DropsClient.java:68,108`
  send nothing. So blocks pass locally, are never sent, mobs/drops/doors/mining do nothing. Edits would be lost.
- Host side: `guestWhere` (HostPlanets.java:177) needs `shared.get(w.planet())` = an `Entry` by index; there is none for
  a station, so no run, no stand-in, no Mobs/Drops to the guest.
- Other open issue: `StationClient.tick` on a guest in a joined world loads the guest's own stations dir. Must be off.

## 3. Design (smallest that reuses everything keyed by `planet` int)

**Idea: give each station a session-local wire slot and run it through the existing planet pipeline.** Every message
already carries a `planet` int (`Use`, `Break`, `MobHit`, `Drops`, `DropTake`, `Throw`, `Where`, `PlanetAsk/Head/Chunk`,
`Edits`, `PlanetDrop`, `Resync`). Make `index >= STATION_BASE (1<<24)` mean a station; the host owns the map
`id <-> slot` (`StationClient.slotOf(id)`, assigned lazily, never reused in a session; slots are not saved).

1. **Directory**: new `StationList(List<Head>)` payload host -> guest (MpPayloads, ~line 440), Head = slot, id, name, stage
   or null, center (3 doubles), rotation (4 doubles), bounds (6 ints), `gen` (regrow counter). Sent after `HelloAck`
   (MpServer join, :389) and as a delta (`StationUpdate`/same payload, one entry, `stage==null` = packed/removed) when a
   station is placed, packed, renamed, or regrown. Tiny, so no rate problem.
2. **Bytes**: reuse `PlanetAsk(slot, etag)` -> `MpPlanetHost.ask` (:23): `index >= STATION_BASE` goes to
   `StationClient.serve(slot)` which returns the `.gxstation` bytes (flush the live session first, as for planets via
   `HostPlanets.admit`: `Entry` keyed by slot, `planet` = the live station session's planet or a resident read with
   `StationStore.read(id, blocks, parse)`; `residents()` cap 8 still applies). Chunking, etag and `PlanetXfer` unchanged.
   Guest: `MpPlanets.fetch(slot, file)` caches under `<guest planets>/mp-cache/stations/<id>.gxstation` (never the guest's
   own `stations/`).
3. **Guest activation**: `StationClient` gets `remote` mode when `MpClient.connected()`: `enterWorld(null)` for the
   store; `tick` reads the `StationList` instead of `store.list()`, with the same ACTIVE/INACTIVE scan, then
   `MpPlanets.fetch` -> `StationStore.read` -> new `activateRemote(Station, slot)` (like `activate` :201, plus
   `MpEdits.attach(slot, session)`). Nothing is written to disk by the guest (skip `write`/`saveAll`).
   `PlanetClient.catalogIndex` (:475) becomes `bodyIndex`: `station() != null ? StationClient.slotOf(...) : indexOf`,
   so every `>= 0` gate keeps working for stations; `whereNone` call (:850) disappears.
4. **Edits**: `EditCodec`/`HostPlanets.apply` (:520) work unchanged for a slot, with two station-only rules:
   (a) **grid epoch**: cell indices die at a regrow, so `Edits` for a station carry `gen`; the host drops edits with a stale
   `gen` and answers `Resync(slot)` (guest refetches; its pending cells settle via `EditSync`). (b) after
   `setFromRemote`, the host runs `Station.allowed` (refuse and echo back air) and `Station.changed/regrow` + `session.swap`
   (the `afterPlace` logic, today in the client-only click path), then bumps `gen`, sends `StationUpdate` + `Resync(slot)`
   (guests refetch the new grid, ~tens of KB gz). Core block (`StationClient.isCore`) cannot be broken by anyone.
5. **Shadow run for whoever stands on it**: `guestWhere` (:177) already does `ShadowWorld.runFor(p, "mp"+index)`; change
   the key to `index >= STATION_BASE ? "station-"+id : "mp"+index` so the strip equals the host's own (`shadowKey`, :469;
   `ShadowMap.of` station case then matches `ShadowLink`). Everything else (columns `ShadowColumns.near` over a
   `FlatGrid`, `ShadowWorld.guest/follow`, Mobs/Drops per planet, `shadowChange`) is keyed by `VoxelPlanet`, so it works.
   **Risk: `ShadowWorld.runs` is keyed by the `VoxelPlanet` object, and a regrow replaces it** (the host's own focus
   re-attaches in `ShadowLink` :54, but a guest-only run and `HostPlanets.Entry.planet`, `shadowFeeds`, `Presence.on`
   keep the old one). Add `ShadowWorld.rekey(old, new)` and `HostPlanets.regrown(slot, newPlanet)` (move Entry, observer,
   feed, presence; mobs stay since shadow cells did not move).
6. **Gravity, walking, mining, drops, mobs on the guest**: local `PlanetSession` of the station (module box gravity,
   KCL parts from `bodies()` already used by the walk fixes in station-polish) + steps 4/5. Guest Mario walks on the
   station from its own SMG2; nothing new beyond `activateRemote`.
7. **Placing / packing by a guest** (phase 2): `StationPlace(spot)` guest -> host (host runs `refusal` with its bodies, makes
   the station, `StationList` to all, `UsedUp(hand)` to the guest as in `UseDone`); `StationPack(slot)` -> host packs and
   `ShadowWorld.give(guestUuid, item)`. Until then a guest sees "only the host can place or pack a station" on the core.
   Rename is the same shape (`StationRename`).
8. **Toads/technician**: host-only for now (they are shadow Villagers of the host's run; guests see them as mobs).
9. **Protocol** 6 -> 7 (`Protocol.java:8`); older peers are refused at `HelloAck`.

## 4. Steps (each leaves the game playable)

1. Pure: `StationSlots` (id <-> slot), `StationList` codec in `net/` + tests; `gen` rule in `EditSync`/`EditHub` test.
2. `StationClient`: `slotOf`, `serve(slot)`, `activateRemote`, remote tick, no disk in guest; `PlanetClient.bodyIndex`.
3. `MpPayloads` StationList (+Update), `MpServer` join send, `MpPlanetHost.ask` / `HostPlanets.admit` / `supply` /
   `liveOf` for slots (`liveOf` must find `StationClient.sessionOf(id)`), `MpPlanets.fetch` cache path.
4. `HostPlanets.apply` growth + `gen` + `regrown` rekey; `ShadowWorld.rekey`; `guestWhere` key.
5. Guest: `MpEdits.attach` for stations, drop `whereNone` special case, verify Use/Break/Throw/MobHit/DropTake gates.
6. Phase 2: StationPlace / StationPack / StationRename payloads.
7. Docs: `docs/MULTIJUGADOR.md`, ROADMAP (Done only after a playtest).

Files: `net/MpPayloads.java`, `net/MpServer.java`, `net/Protocol.java`, `net/EditCodec.java` (gen), new `net/StationSlots.java`,
`client/{StationClient,HostPlanets,MpPlanets,MpEdits,MpPlanetHost,PlanetClient,GalaxyStream?}.java`, `shadow/ShadowWorld.java`,
`voxel/Station.java` (regrow without a client: move `afterPlace` core into `Station`).

## 5. Risks

- Regrow identity (above) is the main one; also a guest edit racing a regrow (handled by `gen`).
- `Station.MAX_ACTIVE`=8 module box gravities counts guests' view too: host's own + remote must stay <= 8 per game.
- Host quits: stations survive only if the host saved (10 s timer); `flushAll` (HostPlanets.java:443) must include stations.
- Two worlds' ids never clash (guest store is not read), but a slot is session-local: never write a slot to disk.
- Far dots / the 3D map (`MapClient`) read `store.list()`; the guest needs them from `StationList` too (phase 2).
- Test hazards: never run gradle while `gxmp.sh` runs; one gradle at a time.

## 6. Verification

Headless (gradle test / probes): slot map, `StationList` codec, `gen` stale-edit rule via `SharedEditsPipelineTest`
(extend with a regrow), station bytes round trip (`StationStore` -> transfer -> read equal), `ShadowMap.of` station key =
`shadowKey`, `ShadowColumns.near` on a `FlatGrid`. `ShadowProbe` stays the guard for the host's own station.
Real two-game (`tools/gxmp.sh both`, user present): guest sees the host's station from afar and lands on it; both build,
station grows past its first regrow while the other stands on it; doors/chests/levers; mobs spawn and chase the guest
(farm on a station); mining drops and pickup; Q throw; guest walks on the edge (sneak) and gravity box; guest's own
station (phase 2) visible to the host; host quits and reloads, station keeps the guest's blocks.
