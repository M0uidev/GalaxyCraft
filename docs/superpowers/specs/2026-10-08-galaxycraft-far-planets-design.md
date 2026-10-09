# Planets farther apart, seen from much farther — design

Date: 2026-10-08. Status: approved in conversation, awaiting review of this spec.

## Goal

Planets sit much farther apart, and you see them from a long way off: they grow as you approach
instead of appearing out of nowhere in front of you. That holds inside your world's galaxy and when
flying toward another star (the two places the user sees pop-in today).

## Decisions (made by the user)

- Default gap between gravity bubbles about **600 blocks** (today 96).
- Every planet of the **solar system you are in** is always visible; other systems are stars until
  you fly toward them, then their planets grow out of the star.
- Approach A: fix today's pipeline (presets, far views off the main thread, dots, star crossfade,
  layout version), not impostor discs, not just raised limits.

## Today (found in the code)

- `GalaxyCatalog.Spacing`: NEAR 24, NORMAL 96, FAR 300 blocks.
- `GalaxyStream.meshFar` builds far views `MESHES_PER_TICK` at a time **on the game's thread**
  (`build(e, patches)`); with the 1.7 terrain a sampled far view costs more, so in a big galaxy far
  ones arrive late, one by one, and their building stutters.
- The module has 16 gravity slots and 80 drawn-only slots (`MAX_PLANETS` 96).
- Another system is only a star until 6000 blocks (`LOAD_BLOCKS`), then its planets switch on at
  once. A system keeps planets within `Universe.SYSTEM_BLOCKS` 2560 of its star; a sector is 8192.
- Planet generation runs parallel streams on the common pool: all cores, competing with Dolphin
  (the stutter measured 2026-10-08 on entering a world).

## 1. Spacing and layout

- Presets: NEAR 150, NORMAL 600 (default), FAR 1500 blocks. Create World shows the new values.
- A world's galaxy grows to fit (64 planets at 600: about 6000–8000 blocks across); placement keeps
  its rule (semi-random by the seed, no overlaps).
- Other systems keep their 8192-block sectors (stars do not move); a system may spread to 3500
  blocks from its star (`SYSTEM_BLOCKS`), so 5–10 planets with the new gaps.
- **Layout version** in the world's galaxy save (`GalaxySave`): 1 = today's spacing and system
  reach, 2 = this design. Worlds saved without it read as 1 and keep their layout (home catalog and
  generated systems), so visited planets stay where their saved blocks are. New worlds are 2.
- The pulse (400 blocks/s) and warps are unchanged.

## 2. Three levels by size on screen

The apparent size is the angle the planet spans (`2·atan(radius/distance)`).

1. **Dot** (under ~0.2°, about 2 px): a colored point of light in the star list the module already
   draws (direction + RGBA, no depth test, at the far plane). No slot, no per-frame cost, never cut
   by distance. Every planet of the current system is at least a dot.
2. **Far view** (over ~0.3°): today's `FarPlanet` coarse mesh, 1..12 patches a face by
   `PlanetLayout.farPatches`. The dot stays until the far view has been sent, so a planet is never
   invisible.
3. **Complete** (the 8 nearest): unchanged.

Between 0.2° and 0.3° a planet keeps the level it had (hysteresis).

**Building far views**: on a background pool, never on the game's thread; the tick only sends
meshes that are ready. Order: biggest on screen first, coarsest level first. On entering a system
its planets' coarsest far views are queued at once.

**Threads**: planet generation, far views and caves share one pool of `max(1, cores − 2)` threads at
low priority (instead of the common pool), so Dolphin keeps its cores.

**Slots**: far views take drawn-only slots (80); past that, the nearest keep theirs, the rest stay
dots.

**Dot color**: from the planet's recipe, without generating it: the main biome's map color (a
generated planet: its seed's first-planet biome, or for Auto the color of grass), a blueprint's
top layer, brightness by its size on screen with a floor so it stays visible.

## 3. Other stars

- A system loads (its catalog from the seed, cheap) within 20,000 blocks of its star and unloads
  past 25,000 (hysteresis). Loaded, its planets are dots at the star's position: nothing changes on
  screen yet.
- As the system's span on screen grows from 1° to 3°, the star's alpha goes 1 → 0 and its planets'
  dots 0 → 1 (one weight `w`, linear), so the star opens into its planets. Reversed when leaving.
- Its planets then follow §2 like any other.
- The star map and warps keep working; warping into a system queues its far views at once (§2).

## Tests

Unit (JUnit):

- Spacing presets' values; a galaxy save without a layout version reads as 1 and keeps the old
  spacing and system reach; new worlds write 2.
- The level for an angle, with its hysteresis.
- The star/dot crossfade weight at 0.5°, 1°, 2°, 3°, 4°.
- Systems load at 20,000, unload at 25,000, not between.
- Far views are never built on the game's thread (the build call asserts it is not); the pool's
  size is `max(1, cores − 2)`.
- Dot colors for a generated, an Auto and a blueprint planet.

In game: **ApproachProbe** (`tools/gxvoxel.sh approach`): flies straight at a planet from 5000
blocks, a screenshot every 250 blocks, asserting at each step that the planet is a dot, a far view
or complete (never nothing) and logging when each level arrived; then flies toward another star and
screenshots it opening into its planets. The user playtests a new world before merge.

## Out of this spec

The far view bugs near the player (a far view left over loaded chunks, caves seen from outside)
stay their own item, next on the roadmap.
