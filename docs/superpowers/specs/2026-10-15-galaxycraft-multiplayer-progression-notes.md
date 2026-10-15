# Multiplayer: shared progression and sane advancements (design notes)

Date: 2026-10-15. Research only, no code changed. Decisions are proposals for the user. Branch tags: [mp3] =
feat/multiplayer-3 (this worktree), [prog] = feat/planet-progression (c39a65e, worktree GalaxyCraft-progression,
NOT merged to master), [ev] = test/everything (ship code, `ship/*`), [master].

## 1. Progression: what exists and what is wrong for multiplayer

All on [prog] (`fabric/src/main/java/dev/moui/galaxycraft/universe/` unless noted):
- `Tier` (rings 12k/20k/40k/70k/110k blocks + seed noise, `reach(coreTier)` = 1,2,4,5), `StarType`,
  `Universe.tier/starType`: pure functions of the seed. Nothing stored, so every player with the same galaxy JSON
  already computes the same tiers and stars. Nothing to share.
- `Visited` (`Visited.java`): set of sectors in `visited.json`, next to the galaxy's planets. Client side:
  `Warp.visited()` (`fabric/src/client/.../client/Warp.java:44`), file from `PlanetClient.visitedFile()`; marked once a
  second from where Mario is (`Warp.java:132`). A guest caches the host's galaxy in its own folder (`GuestFolder`), so a
  guest today would have its OWN visited.json: wrong.
- Warp Core tier: only a client setting `GalaxyOptions.WARP_CORE` (0..3, default 3 = unlimited; `GalaxyOptions.java:67`).
  The real module belongs to the ship ([ev] `ship/ShipModules.java`: "a ship's modules, each tier 0..3"; `ShipStore`
  saves ships in a client file, `ShipRecord` per ship). Not wired to `WarpGate` yet.
- `WarpGate.allowed(u, target, visited, coreTier)`, `WarpPermission` (creative or cheats), `Warp.allowed(Minecraft)`
  (`Warp.java:92`): all evaluated on the client, from client state.

### Unit of progress (proposal)
- **Discovery (visited systems) is per WORLD**, shared by everyone: it is the world's map. A friend who finds a system
  opens it for all. Stored in the HOST's world: `<world>/galaxycraft/.../visited.json` (the folder `GalaxySave.of(
  srv.getWorldPath(LevelResource.ROOT))` already gives in `MpServer.init`, `MpServer.java:~388`). Host is authoritative.
- **Warp Core reach is per SHIP** (the module of the ship you fly), not per player: whoever pilots a ship with a
  tier-2 core can warp to tier-4 systems. With no ship module (until [ev] merges) keep the setting, but as a HOST
  world setting (in the Hello's galaxy JSON), not each client's.
- **The warp permission** (creative or cheats) stays per player, decided by the SERVER (`ServerPlayer.permissions()`),
  because a guest's client could lie. Today it is client-only: a guest in survival without cheats is correctly refused,
  but nothing stops a modified client; low risk among friends, so keep client check + add the server check in step 4.
- Friends contribute: any player's `Visited.mark` goes to the host; the host dedupes, saves, rebroadcasts. Nobody
  can un-visit (monotone set: a union, so no conflict handling). Per-player discovery was rejected: a friend
  without the right tier standing on a system their host found would be locked out of their own team's map.

### Messages and files
- `net/MpPayloads.java` [mp3] (records at lines 28-440, one `typeOf("mp_x")` each, registered in `MpServer.init`
  around `MpServer.java:415-442`, Protocol 6 in `net/Protocol.java:9`): add
  `Visit(int sector x, y, z)` guest -> host (a sector id; `SystemIndex.name/parse` in `universe/` is the text form),
  and `Progress(byte[] visitedJson-like delta list, int coreTier)` host -> guests (full list in Hello; deltas after).
  Protocol 7.
- Host side: new `net/ProgressStore` (pure: set + `load(Path)/save`, reuse `Visited` moved to main; it is already pure
  and in `main`), `MpServer.visit(ServerPlayer, Visit)` with a `RateLimiter` like `whereLimits` (`MpServer.java:270`),
  validated (a sector within `Protocol.MAX_COORD`, at most N per minute). The host's own client marks directly.
- Guest side: `MpClient` (client/.../MpClient.java:95, Hello receiver) installs a `Visited` backed by the host's list
  instead of the local file; `Warp.visited()` returns it when connected; `Warp.java:132` sends `Visit` when `mark`
  returns true.
- Hello: add the visited list and the world's core tier to `Hello(version, worldId, galaxyJson)` (`MpPayloads.java:28`)
  or send `Progress` right after it; the second is simpler (no change of the Hello codec).
- Join/rejoin: a guest that reconnects asks nothing, gets the full `Progress` again (small: a few KB for 1000 systems).
- Offline guest cache: do NOT write the host's visited into the guest's own world folder.

## 2. Advancements in the real overworld (void) with players on planets

Facts from the code [mp3]: the REAL `ServerPlayer`s (host and guests) stay in `minecraft:overworld`, a flat void
(`data/galaxycraft/worldgen/world_preset/galaxy.json`), at positions the CLIENT reports from SMG2 space
(`mixin/ServerGamePacketListenerMixin.java` trusts moves while `FIELD.hasFrame()`). The planets are mirrored into
`galaxycraft:shadow` (`shadow/ShadowWorld.java`) where a `FakePlayer` stand-in per player (`shadow/MarioProxy.java`;
`new MarioProxy(level, guestUuid)`, ShadowWorld.java:~920) lives. Mod actions in the shadow run as the REAL player
(`ShadowWorld.use(...)` line ~618 passes the real `sp`; `destroy`/breaks the same) except where the stand-in acts
(boats `ShadowWorld.java:~660`, arrows it picks up, mob damage routed through `MarioProxy.hurtServer`).
Vanilla advancements live in the host server's `PlayerAdvancements`, per UUID, saved in the host's
`<world>/advancements/<uuid>.json`: **guests already earn and keep their own** (it is the guest's real ServerPlayer).
Gap to check in a real session: toast/advancement screen sync for a guest (should be vanilla packets).

Reasoned from trigger definitions (NOT yet observed in game; each needs a one-minute check with `/advancement grant`
and a stat/`/execute` probe):
- **Fire fine (item based, `inventory_changed`, real inventory)**: story/* up to iron/diamond tools, mine_stone,
  upgrade_tools, smelt_iron, obtain_armor, lava_bucket, iron_tools, shiny_gear, husbandry water_bucket/bread/etc.,
  all `recipes/*` unlocks (KEEP, they drive the recipe book).
- **Fire when the real player is the actor**: kill_a_mob / kill_all_mobs / `player_hurt_entity` (mob hits go through
  the real `sp`; confirm the killer credit for guests via `MobHit`), breed_an_animal / tame_an_animal (feeding through
  `ShadowWorld.use` as `sp`), `adventure/trade` + hero_of_the_village (Toads are shadow Villagers), `deflect_arrow`.
- **Never fire (events skipped by the mod's own paths)**: `placed_block`/`item_used_on_block` for block items and buckets
  (the planet places them, `ShadowWorld.use` returns early for BlockItem/BucketItem, line ~644: e.g. plant_seed,
  `filled_bucket`), `slept_in_bed` (custom `sleep()` line ~750 never fires the trigger), `changed_dimension` to nether/end
  (the real player never leaves the void; shadow portals do not move the real player), `adventuring_time` (the real
  player's biome is always `the_void`; the mirror does not feed `PlayerAdvancements` biomes), structure/sculk/
  lightning_rod advancements, anything done by the stand-in alone (it never saves or syncs advancements).
- **Fire for absurd reasons**: `adventure/root` and `minecraft:tick`/`location` triggers evaluate the real void position
  and dimension. `fall_from_height` (`nether/fall_from_world_height`) can trigger from client-reported fall distance
  of SMG2 flights/warps/pulse (400 blocks/s). Distance stats (`walk_one_cm`, `fly`) count void movement at Mario speed
  and warp jumps: harmless for vanilla advancements (none read them) but do not build ours on `minecraft:distance`.
  `story/enter_the_nether`, `enter_the_end`, `nether/*`, `end/*` are unreachable and leave dead tabs in the screen.
- Beds: sleeping gives the stand-in nothing; a guest "sleeping" is not a vanilla sleep, so no sleep advancement.

### Sane set (proposal)
- Keep: story/* (to diamond), husbandry/* that need animals and items, adventure kill/trade/hero/shoot, all recipes.
- Disable (hidden, never granted): `nether/*`, `end/*`, `adventure/adventuring_time`, `adventure/sleep_in_bed`,
  `nether/fall_from_world_height`, structure/sculk ones. Vanilla files cannot be deleted by a mod's data pack, so
  override each path under `fabric/src/main/resources/data/minecraft/advancement/<tab>/<id>.json` with
  `{"criteria":{"never":{"trigger":"minecraft:impossible"}}}` and no `display` (hidden, no tab). Keep `recipes/*`
  untouched. Check that children of a hidden parent are still hidden (they are: tab root has no display).
- Replace with mod ones under `data/galaxycraft/advancement/` (only `ship/root.json` and `ship/toad_core.json` exist,
  [prog]/[ev] lines in their trees; the progression note's stage 6 is "advancements"): a `galaxycraft:progress` tab:
  `leave_home` (first warp), `tier_N` (first visit of a tier-N system), `star_red/lava/blue`, `first_station`,
  `first_launch_star`, `ship_built`, `warp_core_2`, `toad_trade`, `survive_night_on_planet`, `plant_a_tree`.
- Triggers: item-based ones use `inventory_changed` (work today). World events need a custom trigger from the mod:
  register `galaxycraft:visited_system` / `galaxycraft:flew_ship` with Fabric's `CriteriaTriggers.register` (a
  `SimpleCriterionTrigger<Instance>` with a `tier` / `star` condition) and call `trigger(serverPlayer, ...)` from the
  server handler of `Visit` (section 1), so the HOST awards it to the guest who found the system (shared discovery,
  personal advancement; or every online player, a policy to decide).
- Stand-in actions: make `MarioProxy` hand its advancements to the real player (override `getAdvancements()` to
  return `real().getAdvancements()`, if it is not final; otherwise a mixin on `CriteriaTriggers`) so boat, arrow and
  similar events count for the right person. Verify the method signature in the MC 26.x jar first.

## 3. Minimal design and steps (ordered)

1. [S] Move `Tier`, `StarType`, `Visited`, `WarpGate`, `WarpPermission` from [prog] to the branch (merge feat/planet-progression
   into the MP branch, or wait for it to land on master; it touches `universe/`, `client/Warp.java`, `GalaxyOptions`,
   `docs/GALAXIA.md`, little conflict risk with `net/*`). Blocker: [prog] is not merged and the user decides that.
2. [S] Advancement cleanup, no networking: the overrides of section 2 (about 40 small JSON files) + lang keys +
   a pure test that every override parses and has `impossible` and no display. Can ship alone, even before step 1.
3. [M] `Visit` / `Progress` payloads, `ProgressStore` (pure, unit tested like `SharedEditsPipelineTest`), host save in the
   world folder, guest uses the host's list; `Warp.visited()` and `Warp.java:132` rewired. Protocol 7.
4. [S] Server check of the warp permission: the `Visit`/warp request carries no authority; the host validates a
   `Warp` request only if `ServerPlayer.permissions()` allows it (or leave it client side, noted risk).
5. [M] Warp Core from the ship: once [ev] ship code is on the branch, `WarpGate` gets the ship's core tier
   (world setting until then, in the galaxy JSON the host sends).
6. [M] Mod advancements: tab, `visited_system` custom trigger, `MarioProxy.getAdvancements` redirect, lang, a pure
   test of trigger conditions.
7. [S] Two-game session: see below.

Effort: S+S+M+S+M+M, about 3 working sessions of code, 1 shared with the user's two-game check.

## Risks
- `Visited` was a client file; two sources of truth (host list vs. guest cache) if the guest keeps writing its own.
- Overriding vanilla advancement JSON depends on the MC version's file layout (`data/minecraft/advancement/...`,
  singular in 1.21+); a missing id is silently ignored, a typo creates a NEW stray advancement (test the id list against
  the vanilla jar in the pure test).
- Mixin/override on `MarioProxy.getAdvancements` may not exist or may break `FakePlayer`; fall back to explicit
  `CriteriaTriggers.X.trigger(real, ...)` in `MarioProxy.hurtServer`-style hooks.
- Hiding vanilla tabs is visible to players: tell them in the devlog (user rule: no devlog before they playtest).
- The host quitting ends the world for everyone (progress is saved only in the host's world, by design).

## What needs the real two-game session (`tools/gxmp.sh both`, user present, never run tests meanwhile)
- Guest finds a new system: host's map shows it visited, guest's warp to it works for both, after a rejoin too.
- A guest in survival without cheats is refused a warp; with cheats on it works.
- Guest earns `inventory_changed` advancements; toast shows; file appears in the host's `advancements/<guest uuid>.json`.
- The absurd-trigger list above (adventure root, fall_from_height, sleep, biome) by `/advancement` and by play.
- Mod trigger awarded to the finder: toast on the guest's screen, not the host's.
Pure parts (store, codec, trigger conditions, override JSON validity) are covered by `gradlew test`, no game needed.
