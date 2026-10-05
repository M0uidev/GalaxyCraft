# Minecraft as the launcher: its menu first, worlds as galaxies, play in empty space

2026-10-05. Status: design approved in the brainstorm ("dale, confío plenamente"); spike done.

## Goal

Opening GalaxyCraft opens **Minecraft**: its title screen (lightly GalaxyCraft-branded), its world
list, its options. SMG2 boots behind it on its own, never showing its title or file select. A
Minecraft world is a galaxy: entering it puts the player in **GalaxyCraftSpace**, an empty galaxy
with only SMG2's starry sky, standing on the world's planet. Everything persists across sessions:
the world (inventory, position, blocks), its planets, GalaxyCraft's settings and Minecraft's.

This is spec 1 of 2. Spec 2 (later) is the GalaxyCraft page of "Create World": one or several
planets, the first planet's design, rules for the others, the player's own blueprints,
semi-random placement. Spec 1 creates every new world with one default generated planet.

## Decisions (from the brainstorm)

| Question | Choice |
|---|---|
| Worlds and galaxies | 1 Minecraft world = 1 galaxy; its planets are saved inside the world's folder |
| Where you play | GalaxyCraftSpace: SMG2's starry sky only, no ship, enemies or stars; your planets are the only ground |
| Window | One window, Dolphin's: Minecraft's menus are drawn over it full-screen and opaque; in a world, the usual overlay |
| Scope/order | Spec 1 = launcher + empty space + persistence; spec 2 = Create World's planet options |

## Today, and why settings and planets are forgotten

`tools/gxplay.sh` runs Minecraft as a client **game test** (`DolphinOverlayDemo`): a throwaway
world each launch, `-Dgalaxycraft.planetDir=build/gametest-planets`, which Gradle **deletes**
before every run, and `GalaxyOptions` saves nothing when that property is set (`TEST_RUN`), so the
settings screen's changes are lost too. SMG2 starts on its title; the player walks its menus with
the Wii Remote, and the link starts when a save is picked (`GALAXYCRAFT_LINK_ON_SAVE`).

## Spike results (done, 2026-10-05)

- **Booting SMG2 by itself works** (module, `syati/src/Boot.cpp`): `FileSelector::control`
  (vtable `0x806974C0 + 0x50`) is wrapped. Its nerves are `r13 - N` (r13 = `0x807D7320`):
  title `15808`, choose a file `15784`, file chosen `15764`, start pressed `15760`, start
  `15756` (calls `requestChangeStageAfterFileSelect`), new file `15752`/create `15744`.
  The autopilot kills the `TitleSequenceProduct` (`this + 204`), calls `onSelect(item)` on the
  first existing file (items: `LiveActorGroup` at `this + 152`, `isNew`), then sets nerve 15760.
- **Our galaxy loads**: `requestChangeStageAfterFileSelect` keeps its setup (it calls
  `setInGame`, resets play results) and at `0x804D62FC` calls ours instead of its choice:
  `GameSequenceFunction::requestChangeStage("GalaxyCraftSpace", 1, -1, 0)` +
  `GameSequenceInGame::setInStage`. Then it returns (`b 0x804D63DC`).
- **GalaxyCraftSpace** is built from the disc by `tools/space_galaxy.py` (into
  `syati/build/StageData`, added by Riivolution): RedBlueExGalaxy with every placement emptied
  but `GalaxySky` and Mario's start 0 (moved to the origin). One scenario, one zone. Needs no
  `GalaxyDataTable` row. Tools: `tools/bcsv.py` (JMap tables), `tools/rarc.py` paths/renames.
- From power-on to standing in GalaxyCraftSpace: under 40 s at unlimited speed, with no input.
- Seen: with no planet Mario falls (gravity 0 here still drops him) and the stage restarts.
  The harness's Wii Remote shows "communications interrupted" once on entering the stage.

## Design

### 1. Launch (`tools/gxplay.sh`)

- Starts the patched Dolphin with `GALAXYCRAFT_BOOT=space` and its **own user folder**
  (`$XDG_DATA_HOME/galaxycraft/dolphin`, seeded from `tools/dolphin-play/`). The seed has an
  emulated Wii Remote + Nunchuk with no device bound, plus dual core and 256 MiB of MEM2, so the
  player's usual Dolphin setup no longer matters. On first launch the SMG2 save (`GameData.bin`)
  is copied from the player's normal Dolphin if one is there. If there isn't, the autopilot
  creates a file (§3).
- Starts Minecraft as a **normal Fabric client** (`./gradlew runClient`, not a game test). Its
  game folder is fixed and outside `build/`: `$XDG_DATA_HOME/galaxycraft/minecraft`, holding
  saves, options, config and logs. Hidden window (`galaxycraft.hidden`), no `planetDir`
  property.
- Closing either side closes the other, as now. Leftover hidden Minecrafts are still stopped
  first (`stop_minecraft` matches the new command line too).

### 2. Dolphin: the shell (menus) and the game

A new header word from the mod, `mod_screen` (protocol version bump): `GXC_SCREEN_MENU` when
Minecraft has no world loaded (title, world list, options, loading), `GXC_SCREEN_WORLD` in a
world.

- **Menu:** the overlay is composited **opaque, full-window** (Minecraft's title panorama hides
  SMG2 booting behind it). Keyboard and mouse go to Minecraft as with a screen open (pointer,
  clicks, scroll, text), and nothing reaches the Wii Remote. SMG2's sound is muted (Dolphin's
  volume to 0) until a world is entered.
- **World:** today's Minecraft mode: the transparent overlay, keys to Mario, the link.
- `GALAXYCRAFT_BOOT=space` replaces `GALAXYCRAFT_LINK_ON_SAVE` for play. The host sets mailbox
  `host_flags |= GXC_MBX_BOOT_SPACE` from the first tick, before linking, so the module's
  autopilot runs. Ctrl+G stays as a development switch.
- Until the mod has published a frame, Dolphin draws a plain dark "GalaxyCraft" screen instead
  of SMG2's boot.

### 3. Module: boot autopilot and holding Mario (`syati/src/Boot.cpp`)

Behind `GXC_MBX_BOOT_SPACE` only (the dev harness and the old routes keep SMG2's menus):

- The spike's autopilot, made robust: it acts only on the nerve it expects, once per nerve
  entry, and records its progress in `dbg.boot`. Existing file: the first one. No file: the
  new-file path (create → icon) is driven the same way, mapped with the harness on an empty NAND.
- After file select: `GalaxyCraftSpace`, scenario 1.
- **Holding Mario:** in GalaxyCraftSpace, until the mod's first `PLANET_TP` of the scene, Mario
  is held at the origin. His position is reset and his velocity zeroed every frame, and the
  abyss kill is skipped, the same patch the elytra uses. So he never falls into the void and
  the stage never restarts while Minecraft is in its menus. When the player leaves the world,
  the mod sends a "hold" record again and Mario waits at the origin for the next world.
- Mailbox: `stage_name` already tells the host and the mod which stage it is.

### 4. Minecraft: title screen and worlds

- **Title screen** (mixin on `TitleScreen`): Multiplayer and Realms buttons removed;
  Singleplayer, Options, Mods, Quit kept. The edition line under the logo reads
  "GalaxyCraft". A status line shows SMG2's state from the mailbox: "Super Mario Galaxy 2:
  starting…" or "ready". Singleplayer can be used before SMG2 is ready: the world loads, and
  the player is put on the planet once SMG2 arrives.
- **Create World:** Minecraft's own screen. Its defaults change and the screen is not
  replaced (spec 2 adds a tab): world type `galaxycraft:void`, a void world preset so the
  shadow world stays empty around the player; commands allowed; difficulty normal. Game rules
  as the demo sets them: `fall_damage false`, `spawn_mobs`, `advance_time`, `advance_weather`
  true. The demo's starter hotbar (pickaxe, blocks, buckets) is given once, on the world's
  first join.
- **A world's galaxy** lives in `saves/<world>/galaxycraft/`:
  - `planets/` holds the planet files (`PlanetStore`, unchanged format) for stage
    `GalaxyCraftSpace`.
  - `player.json` holds where the player stands in the galaxy: planet id, the position relative
    to that planet's center, and the look. It is written on leaving and every 30 s.
  - Blueprints stay shared by all worlds: `<game dir>/galaxycraft/blueprints`.
- **First join of a new world:** a default generated planet (`PlanetClient.DEFAULT_RADIUS`,
  the "Generated" mode with the default biome) is created centered at the galaxy origin, and the
  player is teleported onto its top. **Later joins:** the saved planets load, and the player is
  teleported to `player.json`'s spot (or to the first planet's top if that planet is gone).
- **Leaving a world:** the planets are saved and removed from the game (GONE). The mod sends the
  hold record and switches to `GXC_SCREEN_MENU`. SMG2 stays in GalaxyCraftSpace, so entering
  another world is instant.
- `PlanetClient`'s store becomes per world. It is set on join and cleared on leave, replacing
  the static `planetDir()` one. Game tests keep `-Dgalaxycraft.planetDir`, which still wins.

### 5. Settings that persist

- `GalaxyOptions` saves to `<game dir>/config/galaxycraft.properties` whenever no
  `galaxycraft.planetDir` is set, which is always the case in play. Minecraft's own `options.txt`
  (keys, video, sound) persists because the client is no longer a game test.
- Nothing else to add: every GalaxyCraft setting already goes through `Settings`.

### 6. Errors

- SMG2 never reaches GalaxyCraftSpace (no disc image, NAND error, autopilot stuck for 90 s):
  the title status line says so, with the autopilot's last nerve, and worlds can still be
  entered (no link, as Minecraft alone).
- Minecraft closes or crashes: Dolphin closes with it (as now).
- A world from before this design (no `galaxycraft/` folder) gets the default planet on join.

## Testing

- **Unit:** `tools/tests` for `bcsv.py` (read/write round trip, hash) and `rarc.py`
  (`replace_paths` with renames, round trip). Java: the per-world planet folder and
  `player.json` (write/read, missing planet → first planet's top).
- **Harness:** `tools/gxvoxel.sh boot`, which starts Dolphin with the boot flag and checks that
  the stage `GalaxyCraftSpace` is reached with no input, within 90 s at unlimited speed. A
  second run uses an empty NAND (new-file path), and a third checks that Mario is still at the
  origin after 20 s with no Minecraft.
- **End to end:** a client game test `LauncherProbe` (`tools/gxvoxel.sh launch`). It starts at
  Minecraft's title, checks the title buttons, and creates a world through the real screens.
  Then it checks that the link starts, that the player stands on the default planet in
  GalaxyCraftSpace, and that a placed block survives. It leaves to the title (hold, menu mode),
  re-enters the world, and checks the block and the player's spot are still there. Screenshots
  come from `ctx.takeScreenshot`.
- **By hand (the user):** `tools/gxplay.sh`. Check that the Minecraft menu shows first, a
  world can be created and played, quitting and relaunching keeps the world, its planet and
  the changed settings.

## Out of scope

The Create World planet options (spec 2); several planets placed by rules; Windows packaging;
multiplayer; a GalaxyCraft logo image (the edition line only).
