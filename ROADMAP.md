# GalaxyCraft roadmap

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

Last updated: 2026-10-06 (smoother level of detail merged; game feel and blocks in Now, on branch `game-feel`)

---

## Now

| Feature | Status | Notes |
|---|---|---|
| Game feel: breaking and placing blocks | now | Branch `game-feel`: hold to break at Minecraft's speeds (tool, Efficiency, Haste), cracks drawn by the game, block sounds, material particles on hit and place, the block outline in the block's real shape. Written in the cloud without building the mod: needs a build and a playtest on the PC ([docs/SENSACION_BLOQUES.md](docs/SENSACION_BLOQUES.md), [spec](docs/superpowers/specs/2026-10-06-galaxycraft-game-feel-design.md)). |

## Next

| Feature | Status | Notes |
|---|---|---|
| Infinite universe | next | Stage 3 of the galaxy: No Man's Sky-like endless space, planets generated per sector as you explore (the catalog grows), a floating origin (SMG2's floats), faster travel than elytra. Builds on the galaxy options' catalog and streaming. |
| Flat space stations | next | Player-built **flat** platforms floating in space, to play flat Minecraft (farms, builds) without the sphere's distortion. Hook ready: `CosmicWind` takes any `GravityBody`, not only spheres. |

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
| Swimming | planned | Its own stage. Water is already translucent and smooth. |
| Deeper seas | planned | Generated water is at most 2 blocks deep, chosen so Mario can't drown before swimming exists. |
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
| Windows support | planned | Research done, nothing changed yet: [docs/WINDOWS.md](docs/WINDOWS.md). |
| Multiplayer | idea | Out of the first design's scope. |
| Minecraft on one PC, Dolphin on another | idea | Would need a network transport instead of shared memory. |

## Polish and tech debt

| Item | Status | Notes |
|---|---|---|
| `gxroute.py sky` route broken | planned | Can't regenerate `sky.sav`, so `MarioPerspectivesTest` / `gxe2e.sh` fail. |
| Far view at ~40 blocks still draws chunks | planned | Costs speed (~123%). |
| Rare LodProbe send stall | planned | Not reproduced. |
| Teleport lands inside a planet | planned | Not reproduced; the mod logs it now. Report the log lines if it happens. |
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

---

## Inbox

New ideas, unsorted, newest first. Format: `- YYYY-MM-DD: idea (who/where it came from)`.


- 2026-10-05: Launcher follow-ups (review): old planets in ~/.local/share/galaxycraft/planets are not moved into a world; closing the Dolphin window can lose the last ≤10 s of planet edits (no save on exit).
- 2026-10-05: FPS above 60 (user, while playing). SMG2's logic is locked to 60 frames a second,
  so Dolphin cannot draw more by itself. Frame generation (lsfg-vk) needs Lossless Scaling
  (Steam) and Dolphin on Vulkan, whose swap chain fails on the user's laptop; or the module
  draws interpolated frames between the game's.
- 2026-10-05: Power-ups (bee, boo, rock, cloud, spring, fire, star), Yoshi and Luigi as Steve
  (left out of the Steve model spec; still look like Mario). Still wanted?
