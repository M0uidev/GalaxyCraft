# Launch Stars Implementation Plan

> **For agentic workers:** executed natively in one session (user said "start implementing", 2026-10-12). Steps use checkboxes.

**Goal:** craftable Launch Stars: place two on two planets, they link, hold use on one to be flung along a curve that bends around planets, landing on the other.

**Architecture:** pure `launchstar/` classes (`Route`, `FlightProfile`, `StarStore`) unit tested; items registered like the landing pad; `client/LaunchStarClient` places, draws (the disc's `SuperSpinDriver.bdl` through the entity pieces) and drives the player with `GravityFrame.slide`.

**Spec:** `docs/superpowers/specs/2026-10-12-galaxycraft-launch-stars-design.md`. Deviations decided while implementing: stars link **by placement order** (a star placed while another unpaired star waits on a different body links to the oldest one) instead of a hidden pair id on the item (no item components needed, same result); positions are `Vector3d` universe units like `ShipRecord`, not `UPos`.

## Global Constraints

- No game file in the repo: the model is read at run time from `<data>/ship/SuperSpinDriver.arc` (launcher copies it from the player's disc, `launcher/src/core/shipassets.js`).
- Another session has priority: never run `gxplay`/`gxvoxel` while a Dolphin from another session is up (`pgrep -af 'gxplay|dolphin'`).
- No merge to master, no push, no Discord. Commit only on `feat/launch-stars`.
- Gradle: `JAVA_HOME=$(ls -d ~/.local/opt/jdk-25*|head -1)`.

## Review Focus

- Both stars on the same planet: they must not link. Test: `StarStoreTest.sameBodyDoesNotLink`.
- A planet exactly on the chord, and one centred on it: the route must still clear it. Test: `RouteTest.sphereOnTheChord`.
- A and B very close (same system, neighbouring planets) and very far (1e9 units): finite, no NaN. Tests in `RouteTest`.
- Damaged `stars.json`: loads empty. Test: `StarStoreTest.damagedFileLoadsEmpty`.
- Breaking one star leaves the other unlinked, and a new star relinks to it. Test: `StarStoreTest.removingUnlinksPartner`.

---

### Task 1: Route (pure)
**Files:** Create `fabric/src/main/java/dev/moui/galaxycraft/launchstar/Route.java`; Test `fabric/src/test/java/dev/moui/galaxycraft/launchstar/RouteTest.java`
**Interfaces:** Produces `Route.Sphere(Vector3d center, double radius)`, `static Route plan(Vector3d a, Vector3d upA, Vector3d b, Vector3d upB, List<Sphere> obstacles)`, `double length()`, `Vector3d at(double s)`, `Vector3d tangent(double s)`.
- [ ] Write RouteTest (ends, clearance for random spheres, sphere on chord, close/far endpoints).
- [ ] Run, expect compile failure. Implement Hermite spline with iterative waypoint insertion. Run, expect PASS. Commit.

### Task 2: FlightProfile (pure)
**Files:** Create `launchstar/FlightProfile.java`; Test `FlightProfileTest.java`
**Interfaces:** Produces `FlightProfile.of(double lengthBlocks)` with `int ticks()`, `double arcAt(double tick)` (blocks, 0..length, monotonic, eased in/out).
- [ ] Test, implement, commit.

### Task 3: StarRecord + StarStore (pure + JSON)
**Files:** Create `launchstar/StarRecord.java`, `launchstar/StarStore.java`; Test `StarStoreTest.java`
**Interfaces:** Produces `StarRecord(String id, double[] pos, double[] up, String stage, String body, String partner)`; `StarStore.load(Path)`, `all()`, `place(StarRecord)` (returns the stored record, linked if a partner waits), `remove(String id)`, `partnerOf(String id)`, `save()`.
- [ ] Test (round trip, same body, removing unlinks, damaged file), implement, commit.

### Task 4: Items, recipe, assets
**Files:** Create `launchstar/LaunchStarItem.java`, `launchstar/LaunchStarHooks.java`, `launchstar/LaunchStarBlocks.java`; resources `data/galaxycraft/recipe/launch_star.json` (2 stars from nether star + gold + amethyst), `assets/galaxycraft/items/launch_star.json`, `models/item/launch_star.json`, `textures/item/launch_star.png`, lang; Modify `GalaxyCraft.java` (register), `launcher/src/core/shipassets.js` (`SuperSpinDriver.arc`).
- [ ] Implement, `compileJava`, commit.

### Task 5: Client: place, draw, launch, fly, land
**Files:** Create `client/LaunchStarClient.java`; Modify `ship/model/ShipModels.java` (load `SuperSpinDriver.arc`), `GalaxyCraftClient.java` (hooks + tick), `EntityClient.java` (pieces), `PlanetClient.java` (enterWorld/leave).
- [ ] Implement, `compileClientJava`, commit.

### Task 6: Verify
- [ ] `./gradlew test --tests '*launchstar*'`, `compileJava compileClientJava`.
- [ ] If no other session's game is running: run the game and screenshot (see below); otherwise leave it to the user and tell the other session.
