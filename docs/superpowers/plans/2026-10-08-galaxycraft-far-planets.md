# Planets farther apart, seen from afar — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans (native, token-friendly,
> as the user chose for the terrain work). Steps use checkbox (`- [ ]`) syntax.

**Goal:** wider planet spacing for new worlds; every planet of the current system always visible
(dot → far view → complete); far views built off the game's thread; other systems' stars open into
their planets; generation limited to `cores − 2` threads.

**Architecture:** a layout version in the galaxy save selects spacing and system reach (old worlds
keep 1). Pure helpers (`FarSight`) decide levels, crossfade and load distances. Planet dots ride
the existing star message (`StarField`, 20 bytes a point), so the module needs no change. Far views
are made on a worker pool and handed to the tick through a queue.

**Tech stack:** Java 21 (Fabric mod), JUnit 5 (`cd fabric && ./test.sh`), gxvoxel harness.

**Spec:** `docs/superpowers/specs/2026-10-08-galaxycraft-far-planets-design.md`

## Global constraints

- Layout 1 = today: Spacing 24/96/300, `SYSTEM_BLOCKS` 2560. Layout 2 = 150/600/1500, 3500.
- Saves without a version, or version 1, are layout 1. New worlds write 2.
- Dot under 0.2°, far view over 0.3°, keep level between. Star → planets crossfade from 1° to 3°
  of the system's span. Systems load at 20,000 blocks, unload past 25,000.
- No far view built on Minecraft's thread. Worker pool `max(1, cores − 2)`, threads at min priority.
- Module unchanged (STARS_MAX 4096, STAR_BYTES 20).

## Review focus

1. An old world (galaxy.json version 1) keeps its planets and other systems where they were → Task 1 test.
2. A planet is never invisible while approaching: its dot stays until its far view was sent → Task 4 test.
3. Leaving a world while far views are being made does not hang or leak (pool tasks dropped) → Task 4 test.
4. 64-planet galaxy at FAR (1500) still places all planets → Task 1 test.
5. Star message stays ≤ 4096 points with many loaded systems → Task 3 test.

---

### Task 1: layout version, spacing, system reach

**Files:** `voxel/GalaxyCatalog.java` (Spacing `blocks(int layout)`, `LAYOUT = 2`, `make(..., layout)`),
`voxel/GalaxySave.java` (version 0/1 → 1), `universe/Universe.java` (constructor takes layout;
`systemBlocks()`; jitter and star Spacing by layout), `client/PlanetClient.java`
(`newGalaxy` writes `LAYOUT`; `startGalaxy` passes `made.version()`), `client/GalaxyStream.java`
(saves keep the galaxy's version), `client/GalaxyTab.java` (labels show blocks). Tests:
`GalaxyCatalogTest`, `UniverseTest`, `GalaxySaveTest`.

- [ ] Tests: presets per layout; layout-1 universe's stars identical to today's (golden: centers
  of `new Universe(7, U, 1).around(ZERO, 3)` equal the current code's); layout 2 reach 3500; 64
  planets at FAR layout 2 all placed; JSON without version reads layout 1.
- [ ] Implement; full suite; commit.

### Task 2: `FarSight` — levels, crossfade, loading

**Files:** create `voxel/FarSight.java`, test `voxel/FarSightTest.java`.

**Produces:** `enum Level { DOT, FAR }`; `static double angle(double radius, double distance)` (deg);
`static Level level(double angleDeg, Level had)`; `static double opened(double systemSpanDeg)` (0 at
≤1°, 1 at ≥3°, linear); `static boolean load(double blocks, boolean loaded)` (load < 20,000, keep
< 25,000).

- [ ] Tests from the spec's numbers (0.5°, 1°, 2°, 3°, 4°; hysteresis both ways). Implement. Commit.

### Task 3: dots in the star message

**Files:** `universe/StarField.java` (`record Dot(Vector3d dir, float px, int rgba)`;
`message(stars, dots, from, opened, unitsPerBlock)`: star alpha × (1 − opened(star)), dots
appended after stars, total ≤ MAX, nearest first); `voxel/DotColor.java` (rgba from an entry: Auto
generated → grass green 0x6A9A3A, one-biome → the biome's color table, blueprint → its top layer's
map color via a `ToIntFunction<String>`; brightness by size, floor 0.35); `client/GalaxyStream.java`
(`List<StarField.Dot> dots(Vector3d from)`: every loaded planet whose far view is not sent);
`client/UniverseClient.java` (send stars + dots; resend when moved ≥ 1/10 of the nearest dot's or
star's distance, at most every 5 ticks; `STAR_SKIP_BLOCKS` removed). Tests: `StarFieldTest`,
`DotColorTest`.

- [ ] Tests: dots appended and counted; a star fully opened has alpha 0 and is dropped; ≤ 4096
  with 200 stars + 5000 dots; colors for the three kinds. Implement. Commit.

### Task 4: far views off the game's thread

**Files:** `client/PlanetClient.java` (`static final ForkJoinPool workers` of `max(1, cores − 2)`
min-priority threads; `generateAsync` runs `cells` inside it so its parallel streams stay there;
`Caves`/`PlanetGenerator` unchanged — their parallel streams inherit the pool), `client/GalaxyStream.java`
(`meshFar`: a planet whose `FarSight.level` is FAR and has no mesh at that patch count gets a task
on `workers` → `ready` queue; the tick takes ready meshes, makes/updates `FarPlanet`s (slots: the
80 nearest by angle, the rest `dropFar` → dot); `build` asserts it is not on Minecraft's thread;
leaving clears pending tasks by an epoch counter). Test: a pure `FarQueue` (create
`voxel/FarQueue.java`: pending set, epoch, ready list) in `FarQueueTest`.

- [ ] Tests: a planet stays in `dots()` until its mesh is ready and sent; stale results (older epoch)
  are dropped; at most 80 far at once, nearest kept. Implement. Commit.

### Task 5: systems load far, open from their star

**Files:** `client/GalaxyStream.java` (`systemsNear`: `FarSight.load` with each star's distance,
`around(at, 3)` sectors; loaded systems' planets only dots until FAR), `client/UniverseClient.java`
(opened per star = `FarSight.opened(span)` where span = `2·atan(systemBlocks / distance)`).

- [ ] Test (FarSightTest): load/unload hysteresis by distance. Implement. Full suite. Commit.

### Task 6: ApproachProbe

**Files:** create `gametest/.../ApproachProbe.java`, list it in `src/gametest/resources/fabric.mod.json`
and in WalkOnStubPlanetTest's skip list; `fabric/build.gradle` property `galaxycraftApproach`;
`tools/gxvoxel.sh approach`.

- [ ] Probe: a new world (layout 2), Mario 5000 blocks from planet 1 on a straight line, pulse toward
  it, screenshot every 250 blocks, log its level (dot/far/complete) at each step, FAIL if none; then
  toward the nearest other star, screenshots every 2000 blocks from 24,000. Run it (only when
  `pgrep -x dolphin-emu` is empty), look at the shots. Commit.
