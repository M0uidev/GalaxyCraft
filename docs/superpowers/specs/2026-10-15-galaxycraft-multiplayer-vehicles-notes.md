# Vehicles for friends: ships, minecarts and boats, Launch Stars, elytra (research notes)

Date 2026-10-15, branch feat/multiplayer-3 (HEAD 2865daa, Protocol 6). Read-only research; no code changed.
Paths: `F` = fabric/src/main/java/dev/moui/galaxycraft, `C` = fabric/src/client/java/dev/moui/galaxycraft/client.

## 0. The shared picture

- The guest is a real MC client of the host's integrated server (LAN). Everything below is SMG2/Java state kept in
  **client singletons** of each game. A guest has its OWN save folder (`GuestFolder.of(dataDir, worldId)`, MpClient.onHello
  ~l.203): its `ships.json` and `stars.json` are separate files from the host's. So a guest sees none of the host's
  ships/stars, and what a guest places lives only in its folder.
- What reaches the other player today: `PeerState` (MpClient.sendState ~l.118-145; Relay through MpServer SINK ~l.350)
  with `mode` and `flags`. Drawn by `EntityClient.remotes` (~l.868) using `RemoteAvatars.visible` (l.46). Only F_SNEAK, F_SWIM,
  F_GLIDE are read there (l.78). `F_SHIP` is sent (StateSampler l.26, `ShipClient.aboard().isPresent()`) but nobody reads it.
- Host-authoritative pattern to copy (MP-2/3): guest sends request, host applies in its owner, sends result to holders of
  the planet (`HostPlanets.Entry.holders`, `MpServer.mobs/drops/useDone`), rate limit by `RateLimiter`, `hub.subscribed`.
  Codec style: `MobsCodec` (byte[] body) for snapshots, plain records for requests (MpPayloads l.28-440, registered l.414-440).

## 1. Per feature

### Elytra (cheapest)
1. State: none stored. Local flight = Minecraft's `isFallFlying` + `Flight` (C/Flight.java, `Flight.space` GalaxyCraftClient l.559);
   the player moves in its own client physics. Drawn by SMG2 as Mario's flight pose (`MSG_SEAT` kind 2, PlanetClient.frame l.1137)
   or by MC's Steve renderer (`EntityClient.self`).
2. Others: F_GLIDE already flows (StateSampler) and `remotes()` sets `h.isFallFlying = s.gliding()` (EntityClient ~l.902), so a
   gliding peer already lies in the glide pose. Missing: the WINGS. The `RemotePlayer` (RemoteAvatars l.64) has no chest item, so no
   elytra model is drawn. In Mario mode the peer is still drawn as Steve (mode only picks the model?), to verify.
3. Design: no message. In `RemoteAvatars.visible` set `a.entity.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA))` when
   F_GLIDE (clear otherwise). Optional: add `F_ELYTRA=64` later for a worn-but-folded elytra. Both can already use it; nothing to share.
4. Effort S. Risk: render state gets the equipment only if `createRenderState` reads the entity (it does). Test: unit-free, two-game glance.

### Ships (Starshroom)
1. State: `ShipClient.store` (`ShipStore`, ships.json in the galaxy folder, l.76/86) holds `ShipRecord(id, modules, pos[3], rot[4], stage)` and
   cargo per ship (`store.cargo`). `boarded` (l.80) is the local pilot's ship. Flight is the pilot's own client physics: `tick` l.273,
   `board` l.395 puts the player in the seat, `fly` l.416 integrates `ShipThrust`; the ship record is re-written from the player's
   position each frame (`placed`, l.575; `onPlayerTick` l.548). Hull collision: `syncHulls` l.703 -> `GalaxyCraft.FIELD.upsertTriangles`.
   Draw: `ShipClient.pieces` l.756 / `drawShip` l.802 build `EntityWire.Piece`s that SMG2 draws (EntityClient). Map trips: MAP_SHIPS l.669
   + `MapClient.courses()` (also host-singleton). The server knows nothing.
2. Others: a guest sees nothing of the host's ship, and the host nothing of the guest's; a boarded pilot is drawn as a plain avatar
   at the seat position with F_SHIP ignored. Cargo menu (`openCargo` l.339, ShipMenu) is local.
3. Design (host owns the store; ONE writer):
   - Ownership: the HOST's `ShipStore` is the truth for the world. Guests keep no ship file while connected (ShipClient.store for a
     guest = a read-through mirror, not saved; `enterWorld` for a guest skips ships.json).
   - New messages (Protocol 7): `ShipList(byte[] ShipsCodec)` host->guest, full list at join then deltas `ShipPut(record)` / `ShipGone(id)`;
     `ShipDo(kind, id|plan...)` guest->host with kinds PLACE(item modules, pos, rot, stage), BOARD, LEAVE, PICKUP, CARGO_OPEN/MOVE;
     `ShipFly(id, pos, rot, seq)` ~10 Hz from the pilot's client (either side) to the host, host relays to others as `ShipPose`.
   - Pilot-authoritative flight: the boarded player's client simulates (as today, no latency in the cockpit) and streams the pose; the host
     only validates (finite, MAX_SPEED) and relays. The host's store is updated from the pilot's pose (debounced save, as `SAVE_TICKS`).
     A ship is aboard-able by one pilot at a time; others see "occupied" (or a passenger seat in a second step: `ShipSeat(id, seat#)`).
   - Others draw it: the mirror feeds `pieces()` unchanged; the pilot's pose for a boarded ship replaces its record (`onTrip`-like
     override). Peers aboard: F_SHIP set, `RemoteAvatars` -> `isPassenger=true` (as `EntityClient.self` l.~793) and placed at the seat.
   - Hull collision for the guest: `syncHulls` already runs from `store.all()`; it works on the mirror for free.
   - Cargo: chest contents are items; host-authoritative: `ShipCargo` stays in the host's store, guest opens via `ShipDo(CARGO_OPEN)` and
     gets a vanilla container menu on the host's server player (like MP-3 `Use` chests), not a client-only screen. M->L on its own.
   - Items: placing a ship consumes the guest's item (use the `UsedUp(hand)` message, MpPayloads l.401).
4. Effort L. Risks: aboard player moves faster than the ~10 Hz stream (extrapolate with `PeerTrack`'s velocity); the pad/dock assist
   (`landingAssist` l.498) reads the store; map trips (`MapClient`) stay host-only for now (a guest sees the ship parked/moving only by its pose);
   a guest piloting far from the host planets (rendering other galaxy bodies is the guest's own catalog, fine).

### Launch Stars
1. State: `LaunchStarClient.store` (`StarStore`, stars.json, l.63/78), `StarRecord(id, pos, up, stage, body, code)`; links by equal `code` on
   different bodies (`StarStore.partnerOf` l.98). Flight: `launch` l.383 plans a `Route` (obstacles = local planets) and `fly` l.394 slides the
   gravity frame along it each tick on the player's client (pure client; `Flight.end` on landing). Draw: `pieces` l.456 / `dots` l.481 (SMG2 model).
2. Others: nobody sees another's stars, nor a peer being flung. A flung peer is drawn as walking (it is moving at speed with F_GLIDE false).
3. Design: a star is a world fact, like a block. Host owns the `StarStore`. `StarPut(record)` / `StarGone(id)` / `StarCode(id, code)` host->all,
   `StarDo(kind PLACE|PICKUP|CODE, ...)` guest->host (rate limited, checks the item with `UsedUp`, `crowded` and the `plan` from the guest's aim sent as
   center/up). The route is a pure function of the two records and the local planet catalog (`Route.plan`, deterministic given the same galaxy),
   so every client computes it alone; no route data on the wire. Anyone can launch from any star (flight stays local, as now).
   Visible fling: set `F_GLIDE` while flung (`StateSampler.flags` input = `glide || LaunchStarClient.flying()`) and add the heading via the normal
   velocity (PeerTrack extrapolates) -> `RemoteAvatars` lays them along `look`; set look = heading while flying (the self-draw at EntityClient l.801 does
   `LaunchStarClient.heading`). No new message for seeing it.
4. Effort M (stars, S: ~200 lines + codecs) ; fling visibility S. Risks: `Route.obstacles()` (l.367) uses planets loaded locally: a guest
   with a different loaded set could get another curve (only cosmetic for the pilot; the peers see the stream anyway, so use the pilot's position).
   Two players pick up the same star at once: host serialises, loser gets `StarGone` and no item.

### Minecarts and boats (shadow)
1. State: server-side in the host's shadow dimension, in a `ShadowWorld.Run` (ShadowWorld l.105): `Entity riding` (l.117) and
   `volatile Seat seat` (l.118); the movement keys are ONE global `steer` (l.162, set by `PlanetClient` l.871 from the host's keys). The host's
   right click calls `ShadowWorld.interact` (l.283), which only mounts when `isHostPlayer(p)` (l.290, impl. l.365: `marioAt.player()`);
   `ride()` (l.937) pushes/steers `cur.riding` from `steer` and publishes `cur.seat` (planet space) for `PlanetClient.frame` (l.1130, MSG_SEAT)
   to seat the host's Mario/Steve. Boat items also host-only (l.653). Entity itself is just a shadow entity.
2. Others: the entity is already sent to every guest of the planet by `MpMobs.hostTick` (MpMobs l.80: every shadow entity that is not Player/Toad),
   so a guest SEES the cart/boat moving and the host's rider only as an avatar (no sitting pose). A guest right click goes
   `MobHit(kind 1)` -> `HostPlanets.hitMob` (l.152) -> `ShadowWorld.interact(id, guest)`, which silently does nothing for a vehicle (not host).
3. Design (per run, per rider; Run already has `guestProxies` l.126 and `guestAt` l.552, with `moveGuestProxies` l.891):
   - Replace `Run.riding/seat` and global `steer` by `Map<UUID, Rider>` in the Run: `Rider{Entity vehicle; Seat seat; Steer steer}`. The host is key
     `marioAt.player()`. `interact` mounts for ANY player whose stand-in is in this run (`standIn(uuid)`), not just `isHostPlayer`.
   - `ride(level, p)` becomes `ride(level, player, proxy, rider)`, called for the host proxy (l.887) and for each guest proxy inside
     `moveGuestProxies` (after `px.snapTo`, l.~925). A vehicle with several riders: first rider steers (or sum pushes, capped); sneak dismounts only that rider.
   - Steer from guests: new `Steer(boolean fwd, back, left, right, sneak)` message guest->host at change + 5 Hz heartbeat (keys held), validated
     (limit via RateLimiter). Host stores it in the Rider; a heartbeat older than ~0.5 s counts as released.
   - Seat to the guest: `RideSeat(planet, x, y, z, vehicleId)` host->that guest (planet space, like `Seat`), ~10 Hz, and `RideEnd` when dismounted. The guest
     client then puts its OWN player at the seat each tick, as `ShipClient.board` does (`player.setPos`), so its `PeerState` carries the seat position
     to everyone and gravity/physics do not fight (no gravity while seated, like the ship). This also stops `moveGuestProxies` snapping the stand-in to
     a Where that lags: while a Rider exists, the stand-in uses `rider.seat` instead of `guestAt`.
   - Others draw the rider: new flag `F_RIDE = 64` in PeerState (Protocol l.10) -> `RemoteAvatars.Shown` -> `h.isPassenger = true` (EntityClient remotes ~l.902).
     The host's rider is the same (flag from `ShadowWorld.seat() != null`, MpClient.sendState l.141).
   - Boats from a guest's boat item: `use()` l.653 uses `cur.proxy` (host's); use `standIn(sp.getUUID())` instead of `isHostPlayer`.
   - Stand-in cleanup: `moveGuestProxies` l.893 removes a proxy when the guest leaves the planet: also drop the Rider and send `RideEnd`.
4. Effort M. Risks: (a) a vehicle leaving the run's mirrored columns stalls (same as today for the host, `ride` ignores it); (b) rails/minecarts need
   the guest's chunk columns forced on (`Run.wanted` l.123 already per player); (c) boat "turn left/right" assumes the sphere's up = shadow +Y per cell
   (works per `map.frame`, host proven); (d) a guest riding when `Where` stops (planet drop) -> dismount on timeout (`GUEST_LOST_TICKS` HostPlanets).

## 2. Suggested order (each step playable, one writer gets verified before the next)
1. Elytra wings on peers (RemoteAvatars) + F_GLIDE while launch-flung. [S] Files: C/RemoteAvatars.java, C/MpClient.java (flags input),
   net/StateSampler.java. Unit: StateSamplerTest. Needs a two-game glance (ride up, look at the peer).
2. Peers seated: `F_RIDE` flag + draw `isPassenger`; host aboard a ship shows seated (use the unused F_SHIP). [S] Same files + EntityClient.remotes.
3. Minecart/boat for guests (section 1 design). [M] Files: shadow/ShadowWorld.java (Rider, ride, interact, boat use, moveGuestProxies),
   net/MpPayloads.java (+Steer, RideSeat, RideEnd), net/MpServer.java (steer handler + send), C/HostPlanets.java (steer sink), C/MpUse.java or
   a new C/MpRide.java (guest: send keys, apply seat), C/PlanetClient.java l.871 (send steer on guest), Protocol VERSION 7.
   Pure unit: `RiderBook` (steer expiry, one steer per vehicle) like `UseBook`. Needs real two-game session (rail loop, boat on water, dismount).
4. Launch Stars shared. [M] New net/StarsCodec.java, MpPayloads (+StarPut/StarGone/StarDo), MpServer, a C/MpStars.java (host owner / guest mirror),
   LaunchStarClient.place/pickUp/setCode go through it when connected; guest `enterWorld` does not read stars.json. Tests: codec round trip,
   StarStore ops. Two-game: place on host, link from guest, launch both ways.
5. Ships shared, parked + pilot pose + passenger visuals. [L] ShipsCodec, ShipPut/Gone/Do/Fly/Pose, C/MpShips.java, ShipClient changes
   (mirror for guest, publish pose when aboard, F_SHIP in sendState exists). Do cargo as the last sub-step (needs a host container menu).
   Two-game session mandatory (board, fly, land, guest places a ship, pickup).
6. Vehicles with several riders / passenger seats on ships (SeatLayout has seats): later.

## 3. Open questions to settle with the user
- A guest-placed ship/star belongs to the world (persisted on the host), not to the guest: yes? Who may pick up another's? (suggest: anyone in creative; owner or host in survival).
- Map trips (MapClient) for shared ships: keep host-only until a ship has an owner.
- The gxmp.sh two-game recipe (tools/gxmp.sh) is single-machine: ship flight needs the pilot window focused (CLAUDE traps in memory multiplayer-mp3).
