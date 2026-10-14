# Super Minecraft Galaxy roadmap

Where the game is going: what is being built now, what comes next, and every idea said out loud
so far. The goal behind all of it: a **Minecraft survival mode inside Super Mario Galaxy 2**, on
customizable, destructible planets.

## How this file works

- **Status** of each item: `now` (being built), `next` (agreed, comes after `now`), `planned`
  (wanted, order not set), `idea` (said once, not decided), `done`.
- New ideas go to the **Inbox** at the bottom first, with the date. When one is decided, it moves
  to its theme with a status.
- When a feature is merged into `master`, it moves to **Done** with its date and commit.
- Claude keeps this file up to date (see `CLAUDE.md`): any idea you mention in a session lands in
  the Inbox, and finished work moves to Done.
- Details live in the specs (`docs/superpowers/specs/`); this file only links to them.

Last updated: 2026-10-14 (multiplayer part 2 done; MP-3 next)

---

## Now

| Feature | Status | Notes |
|---|---|---|
| Planets with Minecraft 1.7 terrain | now | User 2026-10-08: today's terrain "not that good", old terrain fits small worlds. 3D density with overhangs, several biomes per planet scaled to its size, oceans, rivers, beaches, cave labyrinths, heights scaled to the radius. Trees checked: whole, leaves fall (see Done 2026-10-11). [Spec](docs/superpowers/specs/2026-10-08-galaxycraft-legacy-terrain-design.md) |
| Swimming and water look | now | User 2026-10-08: swim like Minecraft, characters in water drawn over it, Minecraft's underwater view. Built on `feat/swimming` (water draw order, underwater fog, Minecraft's swimming in F6), awaiting playtest. [Spec](docs/superpowers/specs/2026-10-08-galaxycraft-swimming-water-design.md) |
| Panorama screenshots, and F2 that works | now | User 2026-10-08: F2 saved a blank blue picture (Dolphin draws the game): now Dolphin takes it. Ctrl+F2 or /panorama takes six 90° shots from the eyes (panorama_0..5 + one equirectangular picture); tools/set_panorama.py makes a capture the title screen's background. Branch feat/panorama, awaiting playtest. |
| Soundtrack player and space/planet music | now | User 2026-10-09: a music player based on Soundtrack Player; music follows space or planet with a crossfade, a dwell time and a cooldown (all settings, can be turned off); Music Source: both games, SMG2 only, Minecraft only or random; a Station Core option to pick a station's music. Built on `feat/soundtrack` (engine, player screen, settings, Minecraft songs working; probe passes), awaiting your listening test. Your sorting (18 space, 15 planet songs from the 96 on the disc) ships as the default list; the launcher copies the songs from the player's own disc once into a shared folder at install and PLAY. [Spec](docs/superpowers/specs/2026-10-09-galaxycraft-soundtrack-design.md) |
| Video quality while playing, cleaner menus | now | User 2026-10-11: changing the resolution, anti-aliasing and filtering in Super Minecraft Galaxy's settings applies at once (no restart). The pause menu loses the web, friends and reporting icons, the P key, Online and Telemetry; Minecraft's Video settings keeps only what matters here. Branch `feat/live-quality`, awaiting playtest. |
| First release (Linux and Windows) | now | v0.1.1 published 2026-10-07; v0.1.2 pushed 2026-10-08 (c3932ba) with flat space stations, sneaking at edges and gravity shells; v0.1.3 pushed 2026-10-08 (fe6276a) with Export report and no empty Dolphin window, tested on Windows; v0.1.4 published 2026-10-09 (1.7 terrain, far planets, swimming, soundtrack, station polish), not yet checked on Windows (GitHub Release, launcher for Linux and Windows, the game for both). Next: a new player's INSTALL and PLAY from it on Windows, with the Minecraft Launcher. |

## Next

| Feature | Status | Notes |
|---|---|---|
| Far view bugs | next | User 2026-10-08: chunks right by the player draw their blocks and a far view copy that should be gone; caves look wrong from outside and only draw right once inside. After the terrain rework. |
| Planet progression stage 3: content by tier | next | Ores by tier, star bias, rare biomes, hazards from tier 3, structures hook (`TierProfile`). [Spec](docs/superpowers/specs/2026-10-11-galaxycraft-planet-progression-design.md) The Warp Core module itself comes with the ship; today it is a setting. |
| 3D map phase 2: remote ship and orbit | next | Send the Toad ship by remote from the map (also with you aboard), leave it orbiting, land by hand or on a pad. [Spec](docs/superpowers/specs/2026-10-11-galaxycraft-map-3d-design.md) |
| Launch Stars between systems | next | Linked stars work between planets of a system; flights between systems are being checked (stutter, streaming). |

---

## Worlds and planets

| Feature | Status | Notes |
|---|---|---|
| Other planets from blueprints | planned | A world's first planet can be a blueprint; the others are generated. Missing: a mix of blueprints (with weights) for the others. |
| Planet editor: "Add planet" button | planned | Today only the command adds a planet. |
| Placement policy | done | Semi-random by the world's seed, spacing set in Create World (galaxy options, f1ef3ea). |
| Own galaxy | done | Each Minecraft world is a galaxy (GalaxyCraftSpace) holding its planets (launcher, 926f770). |
| Non-spherical planets | planned | Arbitrary shapes (old stage 6): cubes, toruses, SMG-style odd shapes. |
| Structures on generated planets | planned | Villages, temples, etc. Agreed follow-up of planet generation. |
| Nether and End biomes / planets | planned | Agreed follow-up of planet generation. |
| Travel between SMG2 galaxies | idea | Left out of elytra on purpose. |
| Automatic solar systems | done | A world's galaxy of 1..64 generated planets (galaxy options, f1ef3ea). |

## Water and fluids

| Feature | Status | Notes |
|---|---|---|
| Swimming | now | Minecraft's own swimming in F6 (float, sink, swim up, air), built on `feat/swimming`, awaiting playtest. Mario mode swimming and Steve's swim pose: later. [Spec](docs/superpowers/specs/2026-10-08-galaxycraft-swimming-water-design.md) |
| Water look | now | Water blends over what is in it (Steve, mobs); under water Minecraft's fog (biome color, opens up over 30 s). On `feat/swimming`, awaiting playtest. |
| Deeper seas | now | Part of the 1.7 terrain: real depths scaled to the planet. |
| Lava damage | planned | Lava doesn't hurt yet. |

## Movement and player

| Feature | Status | Notes |
|---|---|---|
| Minecraft physics (F6) without collision bugs | planned | You want real Minecraft physics with Steve, skin and hand. "Bugs deep down" near the core were not reproduced in simulation; needs a playtest. |
| Mario in SMG2's flight pose while gliding | done | Part of elytra: `SpaceFlyLoop`, outside first person; SMG2's fall-too-far kill skipped while seated. |

## Survival and mechanics

| Feature | Status | Notes |
|---|---|---|
| Breaking with an empty hand | idea | Punching wood: with an empty hand the clicks are Mario's (spin) today. Came up with the game feel work. |
| Full survival loop on planets | planned | Old stage 7 "more mechanics". Mining, drops, crafting, mobs, light already work; what's missing still needs listing (hunger? sleep/beds? progression?). |

## Platforms and multiplayer

| Feature | Status | Notes |
|---|---|---|
| Windows support | done | Merged 2026-10-07 (feacda7); first played on Windows the same day. Research: [docs/WINDOWS.md](docs/WINDOWS.md). 2026-10-06: wanted seamless: one codebase, every new feature works on both without extra work. Someone asked what they need to build it. 2026-10-07: the launcher (PLAY, `gradlew.bat`) works on both and CI tests it on Windows; Dolphin's input and shared memory ported on `feat/windows`, untested by hand. |
| Multiplayer | next | User 2026-10-13: play with friends in one world, flying freely between planets. Host-authoritative: one player opens the world to LAN, guests join by direct connection and run their own game. **MP-1** (join, see each other, the host sends its planets) and **MP-2** (everyone breaks and places, the host decides and saves it) are done. Next **MP-3**, shared simulation: what lives on the host's server and the guest lacks (doors, chests, buttons, redstone, mobs, dropped items, Toads, stations, ships, Launch Stars), the guest using blocks, the others' entities. Missing since MP-1: names over heads, Mario's model for others, a way to go to a friend. [MP-1 spec](docs/superpowers/specs/2026-10-13-galaxycraft-multiplayer-mp1-design.md), [MP-2 spec](docs/superpowers/specs/2026-10-14-galaxycraft-multiplayer-mp2-design.md), [player guide](docs/MULTIJUGADOR.md). |
| Minecraft on one PC, Dolphin on another | idea | Would need a network transport instead of shared memory. |

## Polish and tech debt

| Item | Status | Notes |
|---|---|---|
| `gxroute.py sky` route broken | planned | Can't regenerate `sky.sav`, so `MarioPerspectivesTest` / `gxe2e.sh` fail. |
| Far view at ~40 blocks still draws chunks | planned | Costs speed (~123%). |
| Rare LodProbe send stall | planned | Not reproduced. |
| Non-cube blocks never checked visually in SMG2 | planned | Slabs, stairs, flowers on a planet: needs a screenshot pass. |

---

## Done

| When | Feature | Commit |
|---|---|---|
| 2026-10-02 | Mario mode: first person, keyboard and mouse, perspectives, Steve model | |
| 2026-10-02 | Voxel planets: cube-sphere, break/place, saved per galaxy, radius up to 256 | |
| 2026-10-03 | Every Minecraft block, real shapes and placement rules; creative inventory | |
| 2026-10-03 | Blocks that work (doors, redstone, pistons, chests) via the shadow dimension; survival drops | |
| 2026-10-03 | Fluids: water, lava, ice, buckets, cobble generator | |
| 2026-10-03 | Mario fits 1×1 holes and 1×2 tunnels | |
| 2026-10-04 | Mobs, drops, TNT, particles drawn by the game; fighting; riding boats and minecarts | 792deb6..91fe439 |
| 2026-10-04 | Planet editor and blueprints; generated planets (terrain, biomes, water, caves, ores, trees) | b724d3e..40e290a |
| 2026-10-04 | Performance: far view (LOD), render distance, streaming without stalls | 443305d, 2708aa1 |
| 2026-10-04 | Up to 8 planets per stage | 65ed033 |
| 2026-10-04 | Minecraft feel: biome colors, translucent water, light and day/night, mob spawning, animated textures | 14eb587 |
| 2026-10-05 | Pause menu, F6 Minecraft movement, `/skin` | 58bcebd |
| 2026-10-05 | Minecraft movement collides like Minecraft; "Mario at Minecraft's speeds" option | e7e85a9 |
| 2026-10-05 | Minecraft as the launcher: its menu first, SMG2 boots by itself into GalaxyCraftSpace, each world a galaxy with its planets and your spot, settings kept | 97925d0..926f770 |
| 2026-10-05 | Create World's planet options: GalaxyCraft tab, up to 64 seeded planets (8 complete, the rest drawn from afar at less and less detail, even past SMG2's draw distance), entering a world with a zoom from space, smooth space and planet flight | f1ef3ea |
| 2026-10-05 | Elytra: glide and fly between planets in every movement mode; cosmic wind | 7bc2385 |
| 2026-10-05 | Lag fix: `gxplay.sh` runs Dolphin dual core and stops leftover hidden Minecrafts | beddf73 |
| 2026-10-06 | Smoother level of detail: blocks first when nearing a planet, far view finer near the blocks (Distant Horizons-style), parallel meshing, nearest chunks first, block distance 96 and far view detail settings; no hangs flying fast (collision zone overflow) or standing still (Wii Remote auto-sleep, stale collision); quiet F1-F8 and invalid-access dialogs | adfd848 |
| 2026-10-06 | Game feel: hold to break at Minecraft's speeds, cracks, block sounds and particles, the block outline in its real shape (and drawn again) | 7fd037b |
| 2026-10-06 | Torches, heads, signs, banners, coral fans, levers and buttons on walls (all four sides); heads on the floor turned to the player; the off hand places and uses when the main hand does nothing | ea7a354 |
| 2026-10-06 | Entering a world is silent (no SMG2 boot, music or landing sounds) until the zoom from space | edd62e6 |
| 2026-10-06 | Chests (also trapped, ender and copper), beds, signs and hanging signs, banners, mob heads, shulker boxes, decorated pots, bells, lecterns, conduits drawn on planets as Minecraft draws them, at no cost per frame; the off hand's item in Steve's left hand | 793e34d |
| 2026-10-06 | Blocks that work on planets: beds and the straw bed (respawn point, sleeping the night through), signs (typing their text), charged respawn anchors (respawn point, no explosion), the lit TNT drawn, ender chests, shelves, rails with Mario riding the minecart; landing after a teleport waits for the ground (no more falling to a planet's core on entering a world) | 40c23e9 |
| 2026-10-06 | Signs as in Minecraft: putting one down opens its editor; clicking Done no longer breaks the sign (a button held as a screen closes is ignored until let go) | 1a73a2b |
| 2026-10-06 | Blocks show what they hold: signs' text (colors, glowing text), banner patterns, a player head's own skin, items on shelves and campfires, pot sherds, chest and shulker box lids opening, all lit by the planet; the shield as Minecraft's model (with its banner) in Steve's hand, raised to block | 687a92a |
| 2026-10-06 | The game is called **Super Minecraft Galaxy** everywhere players see it (menus, chat, Dolphin, game list, README); code keeps the galaxycraft ids so worlds carry over. Discord server renamed to match | 0e844e7 |
| 2026-10-06 | Infinite universe: endless solar systems around the world's galaxy (generated per 8192-block sector from the seed, planets streamed as you approach, edits saved per planet), a floating origin so SMG2's floats never shake (tested a million blocks out), the other systems as stars on the sky, the pulse (sprint while gliding in the void, 400 blocks/s) and free warps (K at a star, M for the galaxy map). Also: a host bug that relinked the game for nothing, and the planet maker waiting forever after leaving a world. Guide: [UNIVERSO.md](docs/UNIVERSO.md) | f0115a5 |
| 2026-10-07 | Desktop launcher for Linux and Windows, like the Minecraft Launcher: news, patch notes, installations with their own worlds and settings, skins, themes and backgrounds, and PLAY. For players: sign in with a Microsoft account, choose your own Super Mario Galaxy 2 (USA), INSTALL and PLAY without the repository; new releases come as UPDATE. Tested on Linux (real PLAY into a world). [launcher/README.md](launcher/README.md) | 7cc48a6 |
| 2026-10-07 | Windows: the same game and worlds as on Linux (Dolphin built for Windows, its keyboard, mouse and shared memory); first played on Windows | feacda7 |
| 2026-10-07 | Play from the Minecraft Launcher: INSTALL adds a "Super Minecraft Galaxy" installation to Mojang's launcher, and the mod starts Dolphin and closes both together. Tested on Linux by starting Minecraft as the Minecraft Launcher would. [Spec](docs/superpowers/specs/2026-10-07-play-from-minecraft-launcher-design.md) | 6a77d69 |
| 2026-10-07 | Credits and community links: "by @M0uiDev" on the title screen and in the launcher (it opens the YouTube channel), a "Join the Discord" button on the title screen, Discord and YouTube in the launcher's sidebar and About | a1c1501 |
| 2026-10-07 | The real Power Star from Super Mario Galaxy 2 in the launcher: its icon, the Home Planet background and the star installation icon; a bolder app icon that reads at small sizes | 95333b8 |
| 2026-10-07 | The launcher's PLAY without signing in to it: it opens the Minecraft Launcher with Super Minecraft Galaxy selected, shows the game running, and closes the Minecraft Launcher when the game ends (a setting keeps it open); INSTALL no longer downloads Minecraft then | 535e352 |
| 2026-10-07 | Flat space stations: craft a Station Core, place it in open space, and it grows as you build (up to 256×256, 128 high) with flat "down" gravity; farms, chests and animals work; the core's menu renames it or packs it into an item to unfold elsewhere. [Spec](docs/superpowers/specs/2026-10-07-galaxycraft-flat-stations-design.md), [guide](docs/ESTACIONES.md) | 44aca6b |
| 2026-10-07 | Sneaking as in Minecraft: Shift in Minecraft's movement (and Mario at Minecraft's speeds) walks slowly and stops at block edges, on planets and stations | 44aca6b |
| 2026-10-07 | Gravity shells: where each planet's and station's gravity begins, drawn as faint blue lines seen from space, fading out once inside | 44aca6b |
| 2026-10-08 | Sneaking exactly like Minecraft on round planets: you stay on top of the block at its edge and corners, never stepping down or sliding off; holding right-click keeps placing; no block goes into the player | 9089508 |
| 2026-10-08 | Gravity shells only out in space (inside a gravity, only its own, fading); no fall or flight wind sounds and no snoring while Minecraft's movement carries Mario | 9089508 |
| 2026-10-08 | Export report in the launcher: one button saves a zip with the logs, crash reports, settings and a system summary (home folders and tokens blanked, nothing uploaded) to send when something fails | c77618e |
| 2026-10-08 | The game starts with only its own window: Dolphin's empty main window no longer opens behind it (Linux and Windows) | 1e18b8d |
| 2026-10-09 | Stations: building outside the gravity box stretches its blue shell toward the new size instead of jumping (visual only) | a977f32 |
| 2026-10-09 | Leave the world standing on a station and you come back on it, on the same column of blocks | 385c46a |
| 2026-10-09 | Stations walk like planets: exact block collision, the player's box lined up with the blocks and as wide as Minecraft's (no sinking into walls, no step at corners when crouching) | d1d7aa1 |
| 2026-10-09 | Walls on planets are flat where you stand: sliding along a straight wall no longer catches on the twist between columns | de552aa |
| 2026-10-09 | The D-pad no longer opens SMG2's first-person look or plays its camera sounds; SMG2's own HUD stays hidden while Minecraft drives | 8ba1a10 |
| 2026-10-09 | New title panorama (the 07:09 capture: the planet, small planets and a station's edge under the blue nebula) | c969072 |
| 2026-10-11 | Entering a world: the planet you land on loses its far planet within seconds (was about a minute), and far planets get room of their own | 981ef82, 3ae5f0f |
| 2026-10-11 | Less heat and lag: push-out queries a planet's boxes once, Minecraft gets 6 GB by default, Picture Quality kept when playing from the Minecraft Launcher | e42a315, 194634a, e41a32f |
| 2026-10-11 | Trees whole (no log missing, giant trunks stay with their tree) and leaves that fall once no log holds them, with Minecraft's drops | ef4acc2..68a612b |
| 2026-10-13 | Toad Brigade ship, the Starshroom: a Ship Table and modules by tier, a ship built from SMG2's own model that you board with right click, pilot seated (hover flight, turbo, tilt), park on landing pads and leave with Shift. [Guide](docs/NAVE.md) | 3d54beb..5faa37c |
| 2026-10-13 | Planet progression, stage 2: tiers 0-5 and a star type per system, planets 1,500+ blocks apart in new worlds, the Warp Core's reach limits which new systems you can warp to (visited ones are free), Pulse only inside a system | 6b10acf..1fefd5d |
| 2026-10-13 | 3D map of the universe: systems as glowing stars with their tier and star, orbits, planet list and ships, pick and warp from it (warp only from the map, in creative or with cheats on) | 0e01cf4, cc6d2b0 |
| 2026-10-13 | Toads: guide, miner, explorer and technician Toads drawn from Kinopio, outposts (a mushroom hut by tier and star) and a technician on each station, a dialogue box on the HUD (typed text, choices, Enter to advance) and trading by tier. They are real Minecraft villagers: a hitbox, they can be hit and killed, sleep in the bed and use the door of their hut (or of the house you build), and never stay stuck in blocks. Huts are three blocks high with a door and a bed. [Guide](docs/TOADS.md) | 72c25ed..6ab56db |
| 2026-10-13 | Launch Stars: craft two, place them on two planets, link them with a code (right click names a star), hold Shift beside one to be flung along a curve around the planets in between with SMG2's own flight effects; Shift again cancels, left click picks one up. The flight goes on behind inventory and chat | 3f4f9a4..e30e252 |
| 2026-10-14 | Multiplayer part 1: open a world to LAN, a friend joins by IP and enters your galaxy (you send it your planets, it keeps a copy), you see each other as Steve with skin, walk in Mario or Minecraft mode | 469321d |
| 2026-10-14 | Multiplayer part 2: you and your friends see each other's changes to the planets (break and place anything, the host decides and keeps what was built, even on a planet it is not at), and each game takes the keyboard and mouse only while its window is focused when you test with two on one computer | 392276a |
| 2026-10-14 | The ship's own screen and hold: E aboard (or Shift + right click outside) opens a pixel-art screen to swap the hull, engine, rudder and cabin, and a hold of chests (4 bays unlocked by the cabin, a large chest takes two) drawn inside the ship; a small flight plate while piloting, the full panel on I | 484544b |


---

## Inbox

New ideas, unsorted, newest first. Format: `- YYYY-MM-DD: idea (who/where it came from)`.

- 2026-10-13: **Ship and landing pad customization, and ship inventory** (user): (1) a real ship customizer, where you
  change the ship's parts, e.g. the glass type, with the glass texture fitted to the round porthole so the white
  Minecraft glass border shows all around; (2) the landing pad becomes a proper station with a small screen
  where you change its outside look (texture, color, more options to find); (3) ship inventory: chests you install
  in the ship (4 units of room, a large chest takes 2; the cabin's tier unlocks the room), opened by a key (E) from
  inside or crouch + right click from outside, drawn inside the ship; everything feeling interactive and well fitted to
  the 3D model. Split in three: the ship inventory is first (spec `docs/superpowers/specs/2026-10-13-galaxycraft-ship-cargo-design.md`,
  branch `feat/ship-cargo`); the customizer and the pad's screen come after.
- 2026-10-13: **Flight UI for the Toad ship** (user): a custom UI while piloting that shows the ship's stats, speed and
  similar, in a style that fits the game (not a plain Minecraft HUD). Built on `feat/ship-cargo` with the cargo, kept small after your feedback: a plate with the speed gauge, the full
  panel only while holding I; the same pixel-art style as the ship screen (parts and hold tabs). Awaiting your playtest.

- 2026-10-12: **Names for galaxies and planets, rename, and an empire** (user): galaxies and planets get random
  names from a list, in the style "Ares 48" (a name plus a number, maybe with a slash). You can rename the planet you
  are on. Maybe a conquest system: visit enough planets of a galaxy and you may rename the galaxy (or it unlocks by
  itself; to review), to build an empire across planets. Decided 2026-10-12: rename a galaxy once all its planets are
  visited, from the map; rename a visited planet from the map or with `/rename`. Built on `feat/names` (baf2eb9,
  tests green, not yet run in the game), awaiting your playtest.
- 2026-10-12: **Toads' own voices** (user, after playtesting the integrated build): Toads should use the game's own
  voices (the villager sound is not good). Done since: warp only from the map, the 3D map, Toads that walk and act as villagers,
  Enter to advance, huts with door and bed (see Done 2026-10-13). Voice research brief: `docs/`.
- 2026-10-11: **Planet generation rework and progression** (user): a full rework of how planets are
  generated, thinking the game's progression: planets of tiers with different ores (no late ores on
  the first planets), the ship's reach opens distance and the planet asks for gear (mobs and
  resources in early tiers, environmental dangers from tier 3 on), advancements in mind, Minecraft
  biomes 1.7 lacks (Mushroom Fields...), structures, Mario Galaxy mobs inside the Minecraft world.
  (Broken trees and leaves that do not decay: done, 2026-10-11, see Done.)
  Meant to feed the **3D map** (which planet is more dangerous). Being designed in a session.
- 2026-10-11: Toad ship, next steps (user, after the playtest): a **3D map** (phase 1 done, phase 2 in Next) to see the
  world and send the ship by remote, leave it **orbiting**, and choose between landing by hand or on a pad,
  done well; more **interactive items and Ship Table** (tooltips with a module's stats and tier, a table
  screen that shows the ship being built). Done since: solid hull, open portholes with Minecraft's glass,
  hover flight with turbo (Ctrl) and tilt, seated pilot, landing pads (docking assist: hold C over a pad).

- 2026-10-09: Soundtrack follow-ups (user/Claude): biome music (Slipsand Galaxy is tagged desert), more moods later (night, underwater, danger), layered `*_multi` songs mixing their layers, mp3/flac custom songs.
- 2026-10-08: Swimming matters (user): Minecraft's swimming, or Mario's body swimming, undecided. Water look: a
  character standing with legs in water is drawn over the water as if above it (screenshot coming; check
  mobs too); under water everything should be tinted like Minecraft's underwater view, faithful to it.
  Asked Claude to propose a design (links to Swimming in Water and fluids).
- 2026-10-08: Joining a galaxy should wait until the planet you land on and the other planets are
  loaded, to avoid lag spikes right after entering (user, after playtesting the far view fix).
- 2026-10-08: Planets farther apart, and seen from much farther (user): too close "doesn't feel the
  same"; far planets should be drawn from a long way off and grow as you approach, not appear out of
  nowhere in front of you. Rethink how far planets are drawn. Designed:
  [spec](docs/superpowers/specs/2026-10-08-galaxycraft-far-planets-design.md).
- 2026-10-07: Follow-ups of flat stations (Claude, review): a station's bounds grow only from what
  the player places, not from what Minecraft adds by itself (flowing water, growing trees, pistons);
  Mario's own movement keeps SMG2's crouch (user: "not made for that").
- 2026-10-07: After the Minecraft Launcher MVP (user): Prism Launcher too (one-click PLAY through
  `prismlauncher --launch`), a "Start Minecraft with…" setting per installation, our PLAY opening
  the other launcher, and our launcher showing/stopping a Minecraft it did not start.
- 2026-10-07: Launcher follow-ups (Claude, from the launcher work): Mojang's approval of the sign-in
  app "SMG Launcher" (registered in Azure and sent to Mojang 2026-10-07); the first
  release (`release/module` is made, 2026-10-07); trying a player's INSTALL from that release; Super Mario Galaxy 2 from other regions; building Dolphin and
  the module from the launcher; a code signing certificate so Windows does not warn.
- 2026-10-07: New players use their own Super Mario Galaxy 2 and their Minecraft account, and
  launchers get new game releases by themselves (user). Taken into the launcher (Now).
- 2026-10-06: Follow-ups of the infinite universe (Claude): a generated system's planet replaced or
  added in the editor lasts only the visit (its blocks are saved, its catalog entry is not: the
  spec's `sectors/<s>.json`); stars are drawn without a depth test, so in third person a few may
  show over Steve; warp could cost something later (fuel, a Launch Star).
- 2026-10-06: Make SMG2 use 64-bit numbers instead of 32 so far-away positions stay precise (user, about the infinite universe). Looked into: SMG2's engine, CPU SIMD and GPU are 32-bit throughout, so not the engine itself; our own code goes 64-bit (free on the Wii's CPU) and the floating origin covers the rest. Spec §2b.
- 2026-10-06: Follow-ups of "blocks show what they hold" (Claude): the enchanting table's book
  turning to the player and the bell swinging (still drawn at rest); a crowd of 100 signs and 50
  banners costs ~2.7 ms a frame, could be made cheaper (pieces that do not move sent once).
- 2026-10-06: Watch the in-game tests (user): `GXC_GUI=1 tools/gxvoxel.sh <test>` runs them in a
  Dolphin window.

- 2026-10-06: Seen in test screenshots (Claude): a green dome with white, pink and blue pillars upside
  down in the sky over the planet (HeldItemTest, EntityTest). Probably another planet's far view
  seen from below; to check.
- 2026-10-05: Launcher follow-ups (review): old planets in ~/.local/share/galaxycraft/planets are not moved into a world; closing the Dolphin window can lose the last ≤10 s of planet edits (no save on exit).
- 2026-10-05: FPS above 60 (user, while playing). SMG2's logic is locked to 60 frames a second,
  so Dolphin cannot draw more by itself. Frame generation (lsfg-vk) needs Lossless Scaling
  (Steam) and Dolphin on Vulkan, whose swap chain fails on the user's laptop; or the module
  draws interpolated frames between the game's.
- 2026-10-05: Power-ups (bee, boo, rock, cloud, spring, fire, star), Yoshi and Luigi as Steve
  (left out of the Steve model spec; still look like Mario). Still wanted?
