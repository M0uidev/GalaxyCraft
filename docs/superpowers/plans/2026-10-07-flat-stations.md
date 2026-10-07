# Flat Space Stations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Player-built flat platforms in space: a Station Core makes a slab that grows as you build,
pulls "down" in a box over it (Steve and Mario), runs Minecraft's blocks like a planet, and packs
into an item to unfold elsewhere.

**Architecture:** The voxel pipeline's grid becomes an abstract `CellGrid`; `CubeSphere` is one,
the new `FlatGrid` (a rotated box of cells, offset so a cell's station coordinate is stable) the
other. A station is a `VoxelPlanet` on a `FlatGrid`, streamed by an ordinary `PlanetSession` whose
PLANET record carries a FLAT flag and its gravity box; the module gives it a `ParallelGravity`.
`StationClient` owns stations (create, grow, regrow, pack, unfold, save, activate by distance).

**Tech Stack:** Java 25 / Fabric (Minecraft 26.3, JUnit 5, Fabric client game tests), C++ Syati
module for SMG2 (SB4E, g++ tests of `src/core`), the existing Dolphin host (no rebuild).

**Spec:** `docs/superpowers/specs/2026-10-07-galaxycraft-flat-stations-design.md`

## Global Constraints

- Maximum footprint 256 × 256; vertical range: station k from -48 (below the core) to 79 (above).
- Gravity box: station bounds + 24 blocks above, + 2 on the other sides; no gravity on the underside.
- Starter slab 9 × 9 × 1 of smooth stone, the core at its middle (station coordinate 0, 0, 0 is the core).
- Slack: the grid is the bounds plus 16 on each horizontal side and above (and below, within range),
  aligned to 8; a placement inside the grid's outer 8 cells regrows it.
- Recipe: 4 iron blocks + 4 glass + 1 ender pearl (shaped: iron corners, glass sides, pearl center).
- Placing a core / packed station: only in open space (no body's gravity reaches the spot, no body
  within 16 blocks); otherwise refused on the action bar, item kept.
- The core cannot be mined; it leaves only by Pack up.
- Files: `<planets dir>/stations/<id>.gxstation`; a placed station is active within 2000 blocks of
  Mario in its stage, inactive past 2500.
- Protocol: no Dolphin rebuild; GxcPlanet keeps its 36 bytes, extra words are big-endian.
- `CubeSphere` behaves exactly as before: all existing unit tests pass unchanged.
- Never edit source while a `gxvoxel` run compiles or runs; check `pgrep -af gxplay` before Dolphin tests.
- Player-facing text says "Super Minecraft Galaxy", code ids stay `galaxycraft`.

## Review Focus

1. Regrow while Mario stands on the station: he must not fall through (collision resent first; old KCL kept by graves).
2. Building at the very edge of the maximum size or vertical range: refused cleanly, nothing half-placed, no exception.
3. Pack up while standing on the station: player and Mario drift in space, no crash, no orphan gravity in the game.
4. Unfold where a planet or another station is near: refused, the item stays.
5. Floating-origin move with a station active: it moves with planets (box gravity too), no jump.

---

## File map

Java (`fabric/src/main/java/dev/moui/galaxycraft/`):
- `voxel/CellGrid.java` (new): abstract grid: sizes, indexing, neighbors, corners, `vertex`, `columnUp`, `faces`, `columns`, `surfaceRadius`, `coreRadius`.
- `voxel/CubeSphere.java`: extends `CellGrid`.
- `voxel/FlatGrid.java` (new): rotated, offset box.
- `voxel/VoxelPlanet.java`, `PlanetMesher.java`, `PlanetCollision.java`, `PlanetLod.java`, `LodSource.java`, `PlanetLight.java`, `PlanetRaycast.java`, `CellSpace.java`, `OutlineEdges.java`, `Fluids.java`, `PlanetDrops.java`, `ModelQuad.java`, `BoxModel.java`, `CubeBlocks.java`, `PlanetSession.java`: take `CellGrid`.
- `voxel/StationShape.java` (new): bounds, growth rules, regrow sizes (pure).
- `voxel/Station.java` (new): id, name, stage, center, rotation, planet; regrow.
- `voxel/StationStore.java` (new): file format, list.
- `gravity/GravityBody.java`: `Box`.
- `shadow/ShadowMap.java`: flat grids by station coordinate.
- `station/StationBlocks.java` (new): registers block, item, packed item.
- `station/StationCoreBlock.java`, `station/PackedStationItem.java` (new).

Client (`fabric/src/client/java/dev/moui/galaxycraft/client/`):
- `StationClient.java` (new), `StationScreen.java` (new); `PlanetClient.java`, `Flight.java`, `GalaxyCraftClient.java` (hooks).

Resources: `assets/galaxycraft/{blockstates,models,textures,items,lang}`, `data/galaxycraft/recipe/station_core.json`, `data/galaxycraft/loot_table` (none: drops nothing).

Module (`syati/src/`): `core/Inbox.h`, `core/Inbox.cpp`, `VoxelPlanet.cpp`; `tests/test_core.cpp`; `protocol/galaxycraft_protocol.h`.

Tests: `fabric/src/test/.../voxel/FlatGridTest.java`, `StationShapeTest.java`, `StationTest.java`, `StationStoreTest.java`, `FlatPlanetTest.java`, `gravity/CosmicWindTest.java`, `voxel/PlanetSessionTest.java`; gametests `StationProbe.java` (no Dolphin) and `StationDolphinProbe.java` (gxvoxel).

---

### Task 1: `CellGrid` (refactor, no behavior change)

**Files:**
- Create: `voxel/CellGrid.java`
- Modify: `voxel/CubeSphere.java`, and every generic user listed in the file map (type `CubeSphere` → `CellGrid` where only the grid API is used; generation code under `voxel/gen/` and `PlanetStore`/`VoxelPlanet.layered/ofRadius/generate` keep `CubeSphere`).

**Interfaces:**
- Produces:
```java
public abstract class CellGrid {
    public static final int TOP = 0, BOTTOM = 1, I_MINUS = 2, I_PLUS = 3, J_MINUS = 4, J_PLUS = 5;
    public final int n, layers;               // a face is n × n columns of `layers` cells
    protected CellGrid(int n, int layers)
    public abstract int faces();              // 6 sphere, 1 flat
    public final int cellCount()              // faces() * n * n * layers
    public final int columns()                // faces() * n * n
    public final int index(int face, int i, int j, int k)  // ((face*n+i)*n+j)*layers+k
    public final int face(int cell), i(int cell), j(int cell), k(int cell)
    /** Column vertex (i, j in [0, n]) at height h in layers (0 = bottom of layer 0), grid units. */
    public abstract Vector3d vertex(int face, int i, int j, double h);
    /** Unit "up" of column vertex (i, j): away from the center on a sphere, the station's up when flat. */
    public abstract Vector3d columnUp(int face, int i, int j);
    public Vector3d corner(int cell, int di, int dj, int dk) // vertex(face, i+di, j+dj, k+dk)
    public Vector3d center(int cell)                          // mean of the 8 corners
    public abstract int cellAt(Vector3d p);                   // -1 outside
    public abstract int cellBeyond(int face, int i, int j, int k);
    public abstract int neighbor(int cell, int side);
    public Vector3d[] side(int cell, int side)                // moved from CubeSphere as is
    /** Radius of a sphere around the origin holding every cell's top at layer k (sphere: core + k). */
    public abstract double radiusAt(int k);
    /** Whether p is in the solid core under the layers (falls there are caught): sphere only. */
    public abstract boolean inCore(Vector3d p);
    /** Whether the cells are affine boxes (CellSpace.local needs no Newton). */
    public boolean affine() { return false; }
}
```
`CubeSphere` keeps `core`, `dir(face,i,j)`, `radius(k)` (= `radiusAt(k)`), its constructor and constants (inherited statics keep `CubeSphere.TOP` compiling everywhere).

- [ ] **Step 1: Write the failing test** — `CubeSphereTest`: add

```java
    @Test void gridApiMatchesTheOldFormulas() {
        CellGrid cg = g;
        assertEquals(6, cg.faces());
        assertEquals(6 * 24 * 24, cg.columns());
        int c = g.index(3, 5, 7, 4);
        assertEquals(0, g.dir(3, 5, 7).mul(g.radius(4)).distance(cg.vertex(3, 5, 7, 4)), 1e-9);
        assertEquals(0, g.dir(3, 5, 7).distance(cg.columnUp(3, 5, 7)), 1e-9);
        assertEquals(0, g.corner(c, 1, 0, 1).distance(cg.vertex(3, 6, 7, 5)), 1e-9);
        assertTrue(cg.inCore(new Vector3d(0, 6.5, 0)));
        assertFalse(cg.inCore(new Vector3d(0, 7.5, 0)));
        assertEquals(g.radius(9), cg.radiusAt(9));
    }
```

- [ ] **Step 2: Run it to see it fail** — `cd fabric && ./gradlew test --tests '*CubeSphereTest*'` → compile error: `CellGrid` not found.
- [ ] **Step 3: Write `CellGrid`** with the members above (moving `index/face/i/j/k/side/center` bodies from `CubeSphere` unchanged, `cellCount()` and `index` using `faces()`); make `CubeSphere extends CellGrid`, `super(n, layers)`, `faces()` 6, `vertex` = `dir(face,i,j).mul(core + h)`, `columnUp` = `dir(face,i,j)`, `radiusAt(k)` = `core + k`, `inCore(p)` = `p.length() < core`. Keep `CubeSphere.corner` overriding with its current body (bit-identical results).
- [ ] **Step 4: Move generic users to `CellGrid`.** Replace, file by file (each compiles before the next):
  - `PlanetMesher.point(g, cell, di, dj, r)` → `g.vertex(g.face(cell), g.i(cell)+di, g.j(cell)+dj, hLayers)` where the callers pass `k(c) + h[di][dj]` (heights in layers) instead of `r0 + h`; `r0 = g.radius(g.k(c))` → `k0 = g.k(c)`.
  - `VoxelPlanet.computeSphere`: corners via `grid.vertex(f, i, j, k)`; the "mid" via `grid.vertex(f, (i0+i1)/2, (j0+j1)/2, k)`; `biomes` size checks `grid.columns()`; blended cache `new int[grid.columns()]`; `surface()` = `grid.radiusAt(depth)`; `occluder()` = `grid instanceof CubeSphere s ? s.core : 0`; `chunkCount()` uses `grid.faces()`.
  - `PlanetLod` / `LodSource`: `g.dir(face,i,j).mul(g.radius(h))` → `g.vertex(face,i,j,h)`; `mid` direction → `g.columnUp(face, mi, mj)`; loops over faces → `g.faces()`; `6 * n * n` → `g.columns()`; `SurfaceSampler.columnDir(g, …)` stays for generated planets only (guard: `g instanceof CubeSphere`, else `g.columnUp`).
  - `PlanetDrops`: `at.length() < p.grid.core` → `p.grid.inCore(at)`.
  - `PlanetSession.ground`, `save`, `load`: keep `CubeSphere` (cast) — flat stations get their own path in Task 6.
  - `ShadowMap`: field type `CellGrid`; `f >= 6` → `f >= grid.faces()`.
  - `CellSpace`, `PlanetCollision`, `PlanetRaycast`, `PlanetLight`, `OutlineEdges`, `Fluids`, `ModelQuad`, `BoxModel`, `CubeBlocks`: parameter types only.
  - `VoxelPlanet.grid` field: `CellGrid`; keep a `public CubeSphere sphere()` that casts (generation, saving).
- [ ] **Step 5: Run every test** — `./gradlew test` → all PASS (the refactor changes no number). Also `./gradlew compileGametestJava compileClientJava`.
- [ ] **Step 6: Commit** — `git commit -am "voxel: CellGrid, the grid the pipeline needs (CubeSphere is one)"`

### Task 2: `FlatGrid`

**Files:** Create `voxel/FlatGrid.java`; Test `voxel/FlatGridTest.java`; Modify `voxel/CellSpace.java` (affine fast path).

**Interfaces:**
- Consumes: `CellGrid` (Task 1).
- Produces:
```java
public final class FlatGrid extends CellGrid {
    /** Station coordinate (x = along j, y = k, z = along i) of cell (0, 0, 0)'s min corner. */
    public final int ox, oy, oz;
    public final Quaterniond rotation;   // station axes (x, y, z) → galaxy axes
    public FlatGrid(int n, int layers, int ox, int oy, int oz, Quaterniond rotation)
    public int faces()                   // 1
    public int stationX(int cell), stationY(int cell), stationZ(int cell)  // j+ox, k+oy, i+oz
    public int cellOf(int sx, int sy, int sz) // -1 outside
    /** vertex: rotation · (j + ox - 0.5, h + oy - 0.5, i + oz - 0.5): the core's cell (0,0,0) is centered on the origin. */
    public Vector3d up()                 // rotation · (0, 1, 0)
}
```
`neighbor` is plain index arithmetic (−1 past any side), `cellBeyond` is `index` when inside else −1, `cellAt` inverse-rotates and floors, `radiusAt(k)` = the largest distance from the origin to the top corners of layer k's box, `inCore` false, `affine()` true.

- [ ] **Step 1: Write the failing test**

```java
package dev.moui.galaxycraft.voxel;

import static org.junit.jupiter.api.Assertions.*;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class FlatGridTest {
    final Quaterniond tilt = new Quaterniond().rotateXYZ(0.3, 1.1, -0.4);
    final FlatGrid g = new FlatGrid(24, 16, -12, -4, -12, tilt);

    @Test void oneFacePlainIndexing() {
        assertEquals(1, g.faces());
        assertEquals(24 * 24 * 16, g.cellCount());
        int c = g.index(0, 3, 5, 7);
        assertEquals(3, g.i(c)); assertEquals(5, g.j(c)); assertEquals(7, g.k(c));
    }

    @Test void stationCoordinatesRoundTrip() {
        int c = g.cellOf(0, 0, 0);
        assertEquals(0, g.stationX(c)); assertEquals(0, g.stationY(c)); assertEquals(0, g.stationZ(c));
        assertEquals(-1, g.cellOf(13, 0, 0));
        assertEquals(0, g.center(c).length(), 1e-9); // the core's cell is centered on the origin
    }

    @Test void centersMapBackUnderRotation() {
        for (int c = 0; c < g.cellCount(); c += 5) assertEquals(c, g.cellAt(g.center(c)), "cell " + c);
        assertEquals(-1, g.cellAt(new Vector3d(g.up()).mul(40)));
    }

    @Test void neighborsArePlainAndStopAtTheBox() {
        int c = g.index(0, 0, 0, 0);
        assertEquals(-1, g.neighbor(c, CellGrid.I_MINUS));
        assertEquals(-1, g.neighbor(c, CellGrid.BOTTOM));
        assertEquals(g.index(0, 1, 0, 0), g.neighbor(c, CellGrid.I_PLUS));
        assertEquals(g.index(0, 0, 0, 1), g.neighbor(c, CellGrid.TOP));
    }

    @Test void topIsUpAndCellsAreUnitCubes() {
        int c = g.index(0, 4, 4, 4);
        Vector3d up = g.corner(c, 0, 0, 1).sub(g.corner(c, 0, 0, 0));
        assertEquals(0, up.distance(g.up()), 1e-9);
        assertEquals(1, g.corner(c, 1, 0, 0).distance(g.corner(c, 0, 0, 0)), 1e-9);
        Vector3d[] top = g.side(c, CellGrid.TOP);
        Vector3d nrm = new Vector3d(top[1]).sub(top[0]).cross(new Vector3d(top[2]).sub(top[0])).normalize();
        assertEquals(1, nrm.dot(g.up()), 1e-9);
    }

    @Test void localIsExactWithoutNewton() {
        int c = g.index(0, 2, 3, 4);
        Vector3d p = CellSpace.point(g, c, 0.25, 0.5, 0.75);
        Vector3d m = CellSpace.local(g, c, p);
        assertEquals(0, m.distance(new Vector3d(0.25, 0.5, 0.75)), 1e-12);
    }
}
```

- [ ] **Step 2: Run** `./gradlew test --tests '*FlatGridTest*'` → FAIL (no `FlatGrid`).
- [ ] **Step 3: Implement `FlatGrid`**:

```java
package dev.moui.galaxycraft.voxel;

import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * A station's cells: a box of n × n columns of `layers` cells, unit cubes turned by `rotation`.
 * Cell (i, j, k) is at station coordinate (j + ox, k + oy, i + oz) (CellSpace's x = j, y = k,
 * z = i); station coordinate (0, 0, 0) is the core's cell, centered on the origin. A regrow makes
 * a bigger box with other offsets, and every cell keeps its station coordinate and place.
 */
public final class FlatGrid extends CellGrid {
    public final int ox, oy, oz;
    public final Quaterniond rotation;
    private final Quaterniond inverse;

    public FlatGrid(int n, int layers, int ox, int oy, int oz, Quaterniond rotation) {
        super(n, layers);
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.rotation = new Quaterniond(rotation).normalize();
        this.inverse = new Quaterniond(this.rotation).conjugate();
    }

    @Override public int faces() { return 1; }

    public int stationX(int cell) { return j(cell) + ox; }
    public int stationY(int cell) { return k(cell) + oy; }
    public int stationZ(int cell) { return i(cell) + oz; }

    public int cellOf(int sx, int sy, int sz) {
        int i = sz - oz, j = sx - ox, k = sy - oy;
        if (i < 0 || j < 0 || k < 0 || i >= n || j >= n || k >= layers) return -1;
        return index(0, i, j, k);
    }

    @Override public Vector3d vertex(int face, int i, int j, double h) {
        return rotation.transform(new Vector3d(j + ox - 0.5, h + oy - 0.5, i + oz - 0.5));
    }

    @Override public Vector3d columnUp(int face, int i, int j) { return up(); }

    public Vector3d up() { return rotation.transform(new Vector3d(0, 1, 0)); }

    @Override public int cellAt(Vector3d p) {
        Vector3d s = inverse.transform(new Vector3d(p));
        return cellOf((int) Math.floor(s.x + 0.5), (int) Math.floor(s.y + 0.5), (int) Math.floor(s.z + 0.5));
    }

    @Override public int cellBeyond(int face, int i, int j, int k) {
        return i < 0 || j < 0 || k < 0 || i >= n || j >= n || k >= layers ? -1 : index(0, i, j, k);
    }

    @Override public int neighbor(int cell, int side) {
        int i = i(cell), j = j(cell), k = k(cell);
        return switch (side) {
            case TOP -> cellBeyond(0, i, j, k + 1);
            case BOTTOM -> cellBeyond(0, i, j, k - 1);
            case I_MINUS -> cellBeyond(0, i - 1, j, k);
            case I_PLUS -> cellBeyond(0, i + 1, j, k);
            case J_MINUS -> cellBeyond(0, i, j - 1, k);
            default -> cellBeyond(0, i, j + 1, k);
        };
    }

    @Override public double radiusAt(int k) {
        double r = 0;
        for (int m = 0; m < 4; m++) r = Math.max(r, vertex(0, (m & 1) * n, (m >> 1) * n, k).length());
        return r;
    }

    @Override public boolean inCore(Vector3d p) { return false; }

    @Override public boolean affine() { return true; }
}
```
  `corner` is inherited (`vertex(face, i+di, j+dj, k+dk)`).
- [ ] **Step 4: `CellSpace.local` fast path**: at the top, `if (g instanceof FlatGrid f) { Vector3d s = f.rotation.transformInverse(new Vector3d(p)); return s.sub(f.stationX(cell) - 0.5, f.stationY(cell) - 0.5, f.stationZ(cell) - 0.5); }`.
- [ ] **Step 5: Run** `./gradlew test --tests '*FlatGridTest*' --tests '*CellSpace*'` → PASS.
- [ ] **Step 6: Commit** — `git commit -am "voxel: FlatGrid, a turned box of cells for stations"` (add the new files).

### Task 3: The pipeline on a flat grid

**Files:** Test `voxel/FlatPlanetTest.java`; Modify `VoxelPlanet.java` (a `flat(FlatGrid, char[] cells, Blocks)` factory), and any place the test shows still assumes a sphere.

**Interfaces:**
- Consumes: `FlatGrid`, `CellGrid`.
- Produces: `public static VoxelPlanet flat(FlatGrid g, char[] cells, Blocks blocks)` (depth 0); `VoxelPlanet.of(CellGrid, int depth, char[] cells, Blocks)` accepting either grid.

- [ ] **Step 1: Write the failing test** — a 16 × 16 × 8 grid, a 9 × 9 slab at station y 0, stone (`CubeBlocks` ids):

```java
class FlatPlanetTest {
    static VoxelPlanet slab() {
        FlatGrid g = new FlatGrid(16, 8, -8, -2, -8, new org.joml.Quaterniond().rotateY(0.7));
        char[] cells = new char[g.cellCount()];
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) cells[g.cellOf(x, 0, z)] = (char) CubeBlocks.INSTANCE.id(Material.STONE);
        return VoxelPlanet.flat(g, cells, CubeBlocks.INSTANCE);
    }

    @Test void meshesOnlyTheSlabsOutside() {
        VoxelPlanet p = slab();
        int quads = 0;
        for (int ch = 0; ch < p.chunkCount(); ch++) quads += PlanetMesher.mesh(p, ch).size();
        assertEquals(81 * 2 + 9 * 4, quads); // top + bottom of 81 cells, 36 edge sides
    }

    @Test void raycastHitsTheTopFromAbove() {
        VoxelPlanet p = slab();
        FlatGrid g = (FlatGrid) p.grid;
        Vector3d eye = new Vector3d(g.up()).mul(3), look = new Vector3d(g.up()).negate();
        PlanetRaycast.Hit h = PlanetRaycast.cast(p, eye, look, 6);
        assertNotNull(h);
        assertEquals(g.cellOf(0, 0, 0), h.hit());
        assertEquals(CellGrid.TOP, h.face());
    }

    @Test void skyLightsTheTopAndCollisionExists() {
        VoxelPlanet p = slab();
        FlatGrid g = (FlatGrid) p.grid;
        assertEquals(15, p.light().sky(g.cellOf(0, 1, 0)));
        int ch = p.chunkOf(g.cellOf(0, 0, 0));
        assertFalse(PlanetCollision.triangles(p, ch).isEmpty());
    }

    @Test void farViewHasOneFace() {
        VoxelPlanet p = slab();
        assertTrue(PlanetLod.tileCount(p) > 0);
        assertFalse(PlanetLod.tile(p, 0, 1).isEmpty() && PlanetLod.tileCount(p) == 1);
    }
}
```
  Before writing, open `PlanetMesherTest`, `PlanetRaycastTest`/`PlanetSessionTest`, `PlanetLodTest` and `PlanetCollision` and use their **exact** existing entry points (method names above are the intent: mesh a chunk, cast a ray, collision triangles of a chunk, a far-view tile); adjust the calls, not the assertions.
- [ ] **Step 2: Run** → FAIL (no `VoxelPlanet.flat`).
- [ ] **Step 3: Implement `VoxelPlanet.flat`** (`return new VoxelPlanet(g, 0, cells, blocks)` through the private constructor, now typed `CellGrid`). Run the test; for each failure, fix the sphere assumption it reveals (known candidates: `PlanetLight` sky from `k == layers-1` is right for flat; `PlanetLod` tiles per face count `faces()`; `computeMayShow` `nb < 0 && s == TOP` must hold for every side on flat (`nb < 0` → visible: change to `if (nb < 0 && (s == CubeSphere.TOP || grid.faces() == 1)) return true;`); `PlanetMesher` side culling where `neighbor == -1` on a lateral side must draw the face).
- [ ] **Step 4: Run** `./gradlew test` → all PASS.
- [ ] **Step 5: Commit** — `git commit -am "voxel: planets on a flat grid mesh, collide, light and show from afar"`

### Task 4: `StationShape` — bounds, growth, regrow sizes (pure)

**Files:** Create `voxel/StationShape.java`; Test `voxel/StationShapeTest.java`.

**Interfaces:**
- Produces:
```java
public final class StationShape {
    public static final int MAX_SPAN = 256, MIN_Y = -48, MAX_Y = 79, SLACK = 16, EDGE = 8, ALIGN = 8, START = 4; // slab -4..4
    public record Bounds(int x0, int y0, int z0, int x1, int y1, int z1) { // inclusive, station coords
        public Bounds with(int x, int y, int z)
        public boolean contains(int x, int y, int z)
        public int spanX(), spanY(), spanZ()
    }
    /** Whether a block may go at (x, y, z) given the bounds: within the vertical range and max span. */
    public static boolean allowed(Bounds b, int x, int y, int z)
    /** The grid for these bounds: square footprint n, layers, offsets (ox, oy, oz), slack included, aligned. */
    public record Size(int n, int layers, int ox, int oy, int oz) {}
    public static Size sizeFor(Bounds b)
    /** Whether (x, y, z) is in the grid's outer EDGE cells (or outside it): time to regrow. */
    public static boolean nearEdge(FlatGrid g, int x, int y, int z)
    public static Bounds starter()      // (-4, 0, -4)..(4, 0, 4)
}
```
- [ ] **Step 1: Write the failing test**

```java
class StationShapeTest {
    @Test void starterGridHasSlackAndIsAligned() {
        StationShape.Size s = StationShape.sizeFor(StationShape.starter());
        assertEquals(0, s.n() % 8);
        assertTrue(s.ox() <= -4 - 16 && s.ox() + s.n() - 1 >= 4 + 16);
        assertTrue(s.oy() <= 0 && s.oy() + s.layers() - 1 >= 16);
        assertTrue(s.oy() >= StationShape.MIN_Y && s.oy() + s.layers() - 1 <= StationShape.MAX_Y);
    }

    @Test void maxSpanAndVerticalRangeAreRefused() {
        StationShape.Bounds b = new StationShape.Bounds(-100, 0, 0, 155, 0, 0); // span 256 on x
        assertTrue(StationShape.allowed(b, 155, 0, 0));
        assertFalse(StationShape.allowed(b, 156, 0, 0));
        assertFalse(StationShape.allowed(b, -101, 0, 0));
        assertFalse(StationShape.allowed(StationShape.starter(), 0, 80, 0));
        assertFalse(StationShape.allowed(StationShape.starter(), 0, -49, 0));
        assertTrue(StationShape.allowed(StationShape.starter(), 0, 79, 0));
    }

    @Test void nearEdgeTriggersRegrow() {
        StationShape.Size s = StationShape.sizeFor(StationShape.starter());
        FlatGrid g = new FlatGrid(s.n(), s.layers(), s.ox(), s.oy(), s.oz(), new org.joml.Quaterniond());
        assertFalse(StationShape.nearEdge(g, 0, 1, 0));
        assertTrue(StationShape.nearEdge(g, s.ox() + 2, 0, 0));
        assertTrue(StationShape.nearEdge(g, s.ox() - 5, 0, 0)); // outside: regrow too
    }

    @Test void sizeNeverPassesTheMaximum() {
        StationShape.Size s = StationShape.sizeFor(new StationShape.Bounds(-128, -48, -128, 127, 79, 127));
        assertTrue(s.n() <= 256 + 2 * 16 + 8);
        assertEquals(StationShape.MIN_Y, s.oy());
        assertEquals(128, s.layers());
    }
}
```
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** `sizeFor`: `x0 = floorAlign(b.x0 - SLACK)`, `x1 = b.x1 + SLACK`, same for z; square footprint `n = alignUp(max(x1 - x0 + 1, z1 - z0 + 1))` (centered so both ranges fit); `oy = max(MIN_Y, alignDown(b.y0 - SLACK))`, `top = min(MAX_Y, b.y1 + SLACK)`, `layers = top - oy + 1`. `nearEdge`: `cellOf` −1 → true; else `i, j < EDGE || ≥ n - EDGE`, `k ≥ layers - EDGE` unless the top is `MAX_Y`, `k < EDGE` unless `oy == MIN_Y`. `allowed`: `MIN_Y ≤ y ≤ MAX_Y` and `b.with(x, y, z)` spans ≤ `MAX_SPAN` horizontally.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** — `git commit -m "voxel: StationShape, a station's bounds and grid size"` (add files).

### Task 5: `Station` and `StationStore`

**Files:** Create `voxel/Station.java`, `voxel/StationStore.java`; Modify `voxel/PlanetStore.java` (extract the palette writer/reader to package-private static helpers `writeCells(DataOutputStream, char[], Blocks)` / `readCells(DataInputStream, int count, Blocks)`); Test `voxel/StationTest.java`, `voxel/StationStoreTest.java`.

**Interfaces:**
- Consumes: `FlatGrid`, `StationShape`, `VoxelPlanet.flat`.
- Produces:
```java
public final class Station {
    public final String id;              // 8 hex chars, random
    public String name;                  // "Station" by default
    public String stage;                 // where placed (null: packed)
    public Vector3d center;              // universe units (galaxy units in stages other than space)
    public Quaterniond rotation;
    public VoxelPlanet planet;
    public StationShape.Bounds bounds;
    public static Station create(String id, String name, Quaterniond rotation, Blocks blocks, char slabBlock, char coreBlock)
    public FlatGrid grid()
    /** After a block at cell changed: bounds grow; true if the grid must regrow (Station.regrow). */
    public boolean changed(int cell)
    /** A new planet on a grid for the current bounds; every cell keeps its station coordinate. */
    public void regrow()
    public int blockCount()
    public boolean allowed(int x, int y, int z)
}
public final class StationStore {
    public record Header(String id, String name, String stage, Vector3d center, Quaterniond rotation, int blocks, int spanX, int spanY, int spanZ) {}
    public StationStore(Path dir)
    public void write(Station s, Blocks blocks) throws IOException     // atomic (tmp + move), gzip, magic "GXS1"
    public Station read(String id, Blocks blocks) throws IOException
    public List<Header> list()                                         // every file's header, unreadable ones skipped with a log line
    public Path file(String id)
}
```
- [ ] **Step 1: Write the failing tests**

```java
class StationTest {
    static final char STONE = (char) CubeBlocks.INSTANCE.id(Material.STONE), CORE = (char) CubeBlocks.INSTANCE.id(Material.DIRT);

    @Test void starterSlabWithTheCoreInTheMiddle() {
        Station s = Station.create("abcd1234", "Station", new Quaterniond(), CubeBlocks.INSTANCE, STONE, CORE);
        FlatGrid g = s.grid();
        assertEquals(CORE, s.planet.get(g.cellOf(0, 0, 0)));
        assertEquals(STONE, s.planet.get(g.cellOf(4, 0, -4)));
        assertEquals(Blocks.AIR, s.planet.get(g.cellOf(5, 0, 0)));
        assertEquals(81, s.blockCount());
    }

    @Test void regrowKeepsEveryCellAtItsStationCoordinate() {
        Station s = Station.create("abcd1234", "Station", new Quaterniond().rotateY(1), CubeBlocks.INSTANCE, STONE, CORE);
        FlatGrid before = s.grid();
        int x = before.ox + 2; // inside the outer edge
        int c = before.cellOf(x, 0, 0);
        s.planet.set(c, STONE);
        assertTrue(s.changed(c));
        Vector3d where = before.center(c);
        s.regrow();
        FlatGrid after = s.grid();
        int c2 = after.cellOf(x, 0, 0);
        assertEquals(STONE, s.planet.get(c2));
        assertEquals(0, where.distance(after.center(c2)), 1e-9);
        assertEquals(CORE, s.planet.get(after.cellOf(0, 0, 0)));
        assertFalse(StationShape.nearEdge(after, x, 0, 0));
    }
}

class StationStoreTest {
    @Test void roundTripKeepsEverything(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        Station s = Station.create("cafe0001", "Farm", new Quaterniond().rotateXYZ(0.1, 0.2, 0.3), CubeBlocks.INSTANCE, StationTest.STONE, StationTest.CORE);
        s.stage = "GalaxyCraftSpace";
        s.center = new Vector3d(1e7, -5, 3.25);
        StationStore store = new StationStore(dir);
        store.write(s, CubeBlocks.INSTANCE);
        Station r = store.read("cafe0001", CubeBlocks.INSTANCE);
        assertEquals("Farm", r.name);
        assertEquals("GalaxyCraftSpace", r.stage);
        assertEquals(0, r.center.distance(s.center), 1e-9);
        assertTrue(r.rotation.equals(s.rotation, 1e-12));
        assertArrayEquals(s.planet.cells(), r.planet.cells());
        assertEquals(s.bounds, r.bounds);
        assertEquals(1, store.list().size());
        assertEquals(81, store.list().getFirst().blocks());
    }

    @Test void packedHasNoStage(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        Station s = Station.create("cafe0002", "Farm", new Quaterniond(), CubeBlocks.INSTANCE, StationTest.STONE, StationTest.CORE);
        s.stage = null;
        s.center = new Vector3d();
        new StationStore(dir).write(s, CubeBlocks.INSTANCE);
        assertNull(new StationStore(dir).list().getFirst().stage());
    }

    @Test void aBrokenFileIsSkipped(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Files.writeString(dir.resolve("bad00000.gxstation"), "nope");
        assertTrue(new StationStore(dir).list().isEmpty());
    }
}
```
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement.** `Station.create`: bounds = `starter()`, size from `sizeFor`, slab cells, core at (0,0,0). `changed(cell)`: if not air, `bounds = bounds.with(x, y, z)`; return `StationShape.nearEdge(grid, x, y, z)` for the cell's coordinate (or any neighbor within 1 — use the cell itself). `regrow()`: new `FlatGrid` from `sizeFor(bounds)` with the same rotation; copy every non-air cell by station coordinate; `planet = VoxelPlanet.flat(...)`. File format (DataOutputStream in gzip): `int MAGIC 0x47585331`, `UTF id`, `UTF name`, `boolean placed`, `UTF stage` if placed, `3 doubles center`, `4 doubles rotation (x,y,z,w)`, `6 ints bounds`, `5 ints Size` (n, layers, ox, oy, oz), `int blockCount`, then `PlanetStore.writeCells`. `list()` reads up to the block count only.
- [ ] **Step 4: Run** `./gradlew test` → all PASS (PlanetStore's tests still pass after the helper extraction).
- [ ] **Step 5: Commit** — `git commit -m "voxel: Station (grow, regrow) and StationStore"` (add files).

### Task 6: Gravity box, PLANET record, landing on a station

**Files:** Modify `gravity/GravityBody.java`, `voxel/PlanetSession.java`, `protocol/galaxycraft_protocol.h`; Test `gravity/CosmicWindTest.java`, `voxel/PlanetSessionTest.java`.

**Interfaces:**
- Produces:
```java
// GravityBody
record Box(Vector3d center, Quaterniond rotation, Vector3d min, Vector3d max) implements GravityBody {
    // min/max: corners in the box's own axes, relative to center (same units as center)
    public double outside(Vector3d p)   // 0 or less inside; else the distance to the box
}
// PlanetSession
public void spawnStation(Station s)        // start(s.planet, s.center) + flat = s
public Station station()                   // null for planets
public void swap(VoxelPlanet p)            // same id and center, new grid: everything resent, Mario's collision first
public GravityBody body(double scale)      // Sphere for planets, Box for stations (scale: units → blocks)
static final int PLANET_FLAT = 2;          // GxcPlanet flag
```
Protocol (`galaxycraft_protocol.h`, after `GXC_PLANET_GONE`): `#define GXC_PLANET_FLAT 2u /* a station: after the flags, big-endian f32 up[3], forward[3], half[3], box_center[3] (galaxy units, box_center from center) */`.

- [ ] **Step 1: Write the failing tests**

```java
// CosmicWindTest
    @Test void boxIsInsideOverTheTopAndOutsideBelow() {
        GravityBody.Box b = new GravityBody.Box(new Vector3d(), new Quaterniond(), new Vector3d(-5, -1, -5), new Vector3d(5, 25, 5));
        assertTrue(b.outside(new Vector3d(0, 10, 0)) <= 0);
        assertEquals(3, b.outside(new Vector3d(0, -4, 0)), 1e-9);
        assertEquals(5, b.outside(new Vector3d(10, 10, 0)), 1e-9);
    }

    @Test void windPullsTowardABox() {
        GravityBody.Box b = new GravityBody.Box(new Vector3d(), new Quaterniond(), new Vector3d(-5, -1, -5), new Vector3d(5, 25, 5));
        Vector3d dv = CosmicWind.push(true, new Vector3d(500, 0, 0), new Vector3d(), java.util.List.of(b));
        assertTrue(dv.x < 0);
    }

// PlanetSessionTest
    @Test void stationRecordCarriesItsBox() {
        Station st = Station.create("cafe0003", "S", new Quaterniond(), CubeBlocks.INSTANCE, StationTest.STONE, StationTest.CORE);
        st.center = new Vector3d(800, 0, 0);
        PlanetSession s = new PlanetSession(80);
        s.spawnStation(st);
        s.update(1, 1, new Vector3d(800, 160, 0));
        PlanetSession.Msg m = s.peek();
        assertEquals(Layout.MSG_PLANET, m.type());
        ByteBuffer b = ByteBuffer.wrap(m.payload()).order(ByteOrder.BIG_ENDIAN);
        assertEquals(88, m.payload().length);
        assertEquals(PlanetSession.PLANET_FLAT, b.getInt(36));
        assertEquals(1, b.getFloat(40 + 4), 1e-6);           // up = +y
        assertEquals(6.5 * 80, b.getFloat(64), 1e-3);         // half x: slab -4..4 is 9 wide, /2 + 2 = 6.5 blocks
    }

    @Test void landingOnAStationIsOnTopOfItsHighestBlock() {
        Station st = Station.create("cafe0004", "S", new Quaterniond(), CubeBlocks.INSTANCE, StationTest.STONE, StationTest.CORE);
        st.center = new Vector3d();
        PlanetSession s = new PlanetSession(80);
        s.spawnStation(st);
        Vector3d at = s.teleportToward(new Vector3d(0, 1, 0));
        assertEquals(0.5, at.y, 1e-9); // the core's top
        assertEquals(0, at.x, 1e-9);
    }
```
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement.**
  - `Box.outside`: `q = rotation⁻¹(p - center)`; `d = max(min - q, 0, q - max)` per axis; return `|d|` if positive, else `-(smallest distance to a face)`.
  - `PlanetSession`: `station` field; `spawnStation` → `start(s.planet, s.center)`, `unsaved = true`. `box()` in station axes from `station.bounds`: min = `(x0 - 0.5 - 2, y0 - 0.5, z0 - 0.5 - 2)`, max = `(x1 + 0.5 + 2, y1 + 0.5 + 24, z1 + 0.5 + 2)`. `gravityUnits()` for a station = the box's bounding radius (blocks × unitsPerBlock). `planetPayload` for a station: allocate 88, `flags | PLANET_FLAT`, then up, forward (`rotation · (0,0,1)`), half extents × unitsPerBlock, box center (rotated) × unitsPerBlock; `occluder` 0; `surface` = `planet.surface()` (radiusAt top).
  - `ground` / `teleportToward` for a station: column = `grid.cellAt` of `toward` projected onto the slab plane (station x, z of `rotation⁻¹ toward`, clamped to the bounds), highest non-air cell in it → landing = center of its top face; none → above the core. Set `tpDir` = landing normalized, `tpGround` = |landing| × units (the module's sphere drop along that ray lands exactly there).
  - `swap(p)`: `planet = p`, keep `id`/`center`, `farVersion` etc. resized as in `start`, `clearQueues()`, `scene = host = MIN_VALUE`; then `for (int c : residency(mario, mario)) queueUrgent(c);` so Mario's collision goes first.
  - `body(scale)`: `Sphere` as Flight builds today, or `Box` (center × scale, min/max in blocks).
- [ ] **Step 4: Run** `./gradlew test` → PASS.
- [ ] **Step 5: Commit** — `git commit -am "stations: gravity box, FLAT planet record, landing on a station"`

### Task 7: The module: a station's `ParallelGravity`

**Files:** Modify `syati/src/core/Inbox.h`, `syati/src/core/Inbox.cpp`, `syati/src/VoxelPlanet.cpp`; Test `syati/tests/test_core.cpp`.

**Interfaces:**
- Consumes: the FLAT record of Task 6.
- Produces: `InboxPlanet` gains `bool flat; f32 up[3], forward[3], half[3], box_center[3];` and `const u32 PLANET_FLAT = 2;`.

- [ ] **Step 1: Write the failing test** (in `test_core.cpp`, beside the PLANET record tests, using its existing record-building helper):

```cpp
static void TestFlatPlanetRecord()
{
  u8 rec[8 + 88] = {};
  PutBE32(rec, gxc::InboxRecord::PLANET << 16);
  PutBE32(rec + 4, 88);
  PutBE32(rec + 8, 7);                         // id
  PutF32(rec + 8 + 20, 900.f);                 // gravity_range
  PutBE32(rec + 8 + 36, gxc::PLANET_FLAT);
  PutF32(rec + 8 + 40 + 4, 1.f);               // up.y
  PutF32(rec + 8 + 52 + 8, 1.f);               // forward.z
  PutF32(rec + 8 + 64, 520.f);                 // half.x
  u32 off = 0;
  gxc::InboxRecord r;
  CHECK(gxc::NextInboxRecord(rec, sizeof rec, &off, 4096, &r));
  CHECK(r.planet.flat && r.planet.up[1] == 1.f && r.planet.forward[2] == 1.f && r.planet.half[0] == 520.f);
}
```
  (Use the file's own BE writer helpers; add `PutF32` beside `PutBE32` if missing.)
- [ ] **Step 2: Run** `syati/test.sh` → FAIL (no `flat`).
- [ ] **Step 3: Implement parsing**: accept `len == 88` when `flags & PLANET_FLAT`; read the 12 floats at offsets 40..84; `flat = false` otherwise.
- [ ] **Step 4: Run** `syati/test.sh` → PASS.
- [ ] **Step 5: Module gravity.** In `VoxelPlanet.cpp`:
  - `#include "Game/Gravity/ParallelGravity.h"`; `const u32 FLAT_SLOTS = 8; ParallelGravity* gFlat[FLAT_SLOTS]; bool gFlatUsed[FLAT_SLOTS];` made in `init` like the point gravities (`setRangeType(RangeType_Box)`, priority 100, `mRange = 1.f`, a zero box), `MR::registerGravity`.
  - `Planet` gains `ParallelGravity* flat;` (0 for planets).
  - `ApplyPlanet`: if `in.flat`: entry = `Find(in.id)` or the first free entry at `GRAVITY_SLOTS..MAX_PLANETS`; take a free flat slot if it has none (none free → `alloc_failed++`, return). Set it up: `setPlane(up, center + box_center)`; box matrix: columns `right * half[0]`, `up * half[1]`, `forward * half[2]` (`right = up × forward`), translation `center + box_center`; `setRangeBox(mtx)`; `mRange = -1.f` (no distance limit) and `updateIdentityMtx()`. The rest of ApplyPlanet (slots, center, surface) is shared.
  - `Drop`: a flat entry gives its slot back (`mRange = 1.f`, zero box, `updateIdentityMtx()`), `p.flat = 0`.
  - `MoveOrigin`: a flat entry's plane and box move by `d` as its center does.
  - `MarioRadius`: a flat entry inside its box (use `p.flat->isInRange(pos, &s)`) → `*radius = p.mario_radius`.
  - Check in Petari's `ParallelGravity.cpp` (search the web if it is not in the toolchain) that `isInBoxRange` reads the matrix columns as half-extent vectors; if it uses full extents, double them here.
- [ ] **Step 6: Build** `syati/build.sh` → `built build/CustomCode/CustomCode_SB4E.bin`.
- [ ] **Step 7: Commit** — `git commit -am "module: stations pull down with SMG2's box gravity"`

### Task 8: The shadow strip by station coordinate

**Files:** Modify `shadow/ShadowMap.java`, `client/ShadowLink.java` (and `ShadowWorld.attach` callers if the key changes); Test `shadow/ShadowMapTest.java` (create if missing).

**Interfaces:**
- Produces: `ShadowMap.of(VoxelPlanet p, String key)` — for a `FlatGrid`: `x = 1 + 256 + stationX`, `z = z0 + 1 + 256 + stationZ`, `y = 48 + stationY`; `cell(x, y, z)` inverts through `cellOf`; `haloSource` −1 and `halos` empty and `wrap` null (no face edges); `frame` from the flat cell. Station key: `"station-" + id`.

- [ ] **Step 1: Write the failing test**

```java
class ShadowMapTest {
    @Test void aStationCellKeepsItsShadowPlaceAcrossARegrow() {
        Station s = Station.create("cafe0005", "S", new Quaterniond(), CubeBlocks.INSTANCE, StationTest.STONE, StationTest.CORE);
        ShadowMap before = ShadowMap.of(s.planet, "station-cafe0005");
        int c = s.grid().cellOf(3, 0, -2);
        int x = before.x(c), y = before.y(c), z = before.z(c);
        s.planet.set(s.grid().cellOf(s.grid().ox + 1, 0, 0), StationTest.STONE);
        s.changed(s.grid().cellOf(s.grid().ox + 1, 0, 0));
        s.regrow();
        ShadowMap after = ShadowMap.of(s.planet, "station-cafe0005");
        int c2 = s.grid().cellOf(3, 0, -2);
        assertEquals(x, after.x(c2)); assertEquals(y, after.y(c2)); assertEquals(z, after.z(c2));
        assertEquals(c2, after.cell(x, y, z));
        assertEquals(48, after.y(s.grid().cellOf(0, 0, 0)));
    }
}
```
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** in `ShadowMap` a `flat` branch (field `FlatGrid flat`, null for planets) in `x/y/z/cell/haloSource/halos/onEdge/frame/wrap`; `of(p, key)` picks it. In `PlanetClient.tick`, `shadow.tick(...)` key: `focus.station() != null ? "station-" + focus.station().id : PlanetStore.key(stage, indexOf(focus))`; `ShadowLink`'s map likewise (follow how the key reaches `ShadowMap.of`).
- [ ] **Step 4: Run** `./gradlew test` → PASS.
- [ ] **Step 5: Commit** — `git commit -am "shadow: a station's strip goes by station coordinates"`

### Task 9: Station Core block and Packed Station item

**Files:** Create `station/StationBlocks.java`, `station/StationCoreBlock.java`, `station/PackedStationItem.java`; resources `assets/galaxycraft/blockstates/station_core.json`, `models/block/station_core.json`, `items/station_core.json`, `items/packed_station.json`, `models/item/packed_station.json`, `textures/block/station_core.png`, `textures/item/packed_station.png`, `lang/en_us.json` (+ entries), `data/galaxycraft/recipe/station_core.json`; Modify `GalaxyCraft.java` (call `StationBlocks.register()`).

**Interfaces:**
- Produces: `StationBlocks.CORE` (Block), `StationBlocks.CORE_ITEM`, `StationBlocks.PACKED` (Item); `PackedStationItem.stack(String id, String name, int sx, int sy, int sz)`; `PackedStationItem.id(ItemStack)` → `Optional<String>` (from `DataComponents.CUSTOM_DATA`, key `"station"`). Client hooks set later: `StationCoreBlock.onUse` / item `use` call a static `StationHooks` interface (`main`-side, set by the client) so `main` never imports client classes.

- [ ] **Step 1:** Look up how Fabric 0.161 / MC 26.3 registers a block and item (`Blocks.register` pattern with `BlockBehaviour.Properties.of().setId(ResourceKey)`, `Items.registerBlock`), from the decompiled sources in the Loom cache (`~/.gradle/caches/fabric-loom/26.3/...`, or `./gradlew genSources`). Write `StationBlocks.register()` with `ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("galaxycraft", "station_core"))` (use the identifier class this version has), strength `-1` with `explosionResistance(3600000)` (unbreakable like bedrock: never mined), `noLootTable()`.
- [ ] **Step 2:** `StationCoreItem.use(level, player, hand)` (right click into the air): on the client call `StationHooks.get().placeCore(hand)`; return `InteractionResult.SUCCESS` if it accepted. `PackedStationItem.use` likewise `unfold(hand, id)`. Tooltip of the packed item: name + `W×D×H, N blocks` from its custom data.
- [ ] **Step 3:** Recipe JSON (shaped): pattern `["IGI", "GPG", "IGI"]`, `I` iron_block, `G` glass, `P` ender_pearl → `galaxycraft:station_core`. Textures: 16×16 PNGs made with Python (Pillow) — the core a dark metal frame with a cyan center; the packed station a small slab icon. Lang: `"block.galaxycraft.station_core": "Station Core"`, `"item.galaxycraft.packed_station": "Packed Station"`, the menu strings of Task 10.
- [ ] **Step 4: Verify** `./gradlew build` → BUILD SUCCESSFUL; add to `ShadowProbe` (or a new tiny gametest assertion in `StationProbe`, Task 11) that the recipe resolves and the core's destroy speed is −1.
- [ ] **Step 5: Commit** — `git commit -m "stations: Station Core block, Packed Station item, recipe"` (add files).

### Task 10: `StationClient` — place, grow, regrow, pack, unfold, save, activate

**Files:** Create `client/StationClient.java`, `client/StationScreen.java`; Modify `client/PlanetClient.java` (planets() includes active stations; `placed()` hook after a placement; `breakBlock` refuses the core cell; focus; saving), `client/Flight.java` (`bodies()` uses `PlanetSession.body`), `client/GalaxyCraftClient.java` (`StationHooks.set(StationClient.HOOKS)`, `/galaxycraft station list|restore <id>` beside the planet commands).

**Interfaces:**
- Consumes: everything above.
- Produces:
```java
public final class StationClient {
    public static final double ACTIVE = 2000, INACTIVE = 2500, CLEAR = 16;  // blocks
    public static List<PlanetSession> sessions()               // active stations' sessions
    static void tick(LocalPlayer player, String stage, Vector3d marioGal, GravityFrame frame)
    static boolean placeCore(LocalPlayer player, InteractionHand hand)
    static boolean unfold(LocalPlayer player, InteractionHand hand, String id)
    static void afterPlace(PlanetSession s, int cell)          // grow; regrow → s.swap(...)
    static boolean isCore(PlanetSession s, int cell)
    static void pack(PlanetSession s)                          // from StationScreen
    static void rename(PlanetSession s, String name)
    static void enterWorld(Path planetsDir), leaveWorld(), saveNow()
}
```
- [ ] **Step 1: Placement rule as a pure function, test first.** In `voxel/Station.java` add `public static String refusal(Vector3d atBlocks, List<GravityBody> bodies, int active, int max)` → null if allowed, else the message (`"Too close to a planet or station"`, `"Stations go in open space"`, `"Too many stations here"`). Test in `StationTest`:

```java
    @Test void placementOnlyInOpenSpace() {
        var planet = new GravityBody.Sphere(new Vector3d(), 100);
        assertNotNull(Station.refusal(new Vector3d(50, 0, 0), java.util.List.of(planet), 0, 8));
        assertNotNull(Station.refusal(new Vector3d(110, 0, 0), java.util.List.of(planet), 0, 8)); // within 16 of its reach
        assertNull(Station.refusal(new Vector3d(200, 0, 0), java.util.List.of(planet), 0, 8));
        assertNotNull(Station.refusal(new Vector3d(200, 0, 0), java.util.List.of(), 8, 8));
    }
```
  Run → FAIL; implement (`outside(p) <= CLEAR` refuses); run → PASS.
- [ ] **Step 2: `StationClient`** (client side, all on the client thread):
  - `placeCore`: refuse in a non-`Layout.SPACE_STAGE` stage unless `-Dgalaxycraft.stationsAnywhere=true` (tests); `refusal(...)` with `Flight.bodies()` + active stations; on success: `rotation` from `frame.upGal()` and the look projected on the plane ⟂ up (station +z = look, +y = up); center = the player's feet galaxy position + look × 4 blocks − up × 1 block, in the units `PlanetSession.center` uses (universe units in space, via `GameOrigin` as `PlanetClient` does); `Station.create(random 8-hex id, "Station", …, slab = smooth_stone id, core = station_core id from `blocks`)`; `stage` = current; new `PlanetSession` with `setBlocks`, `spawnStation`; `useUp(hand)`; save immediately.
  - `afterPlace(s, cell)`: if `!station.allowed(x, y, z)` → revert the cell to air, give the item back (`ShadowWorld.give`), action bar "The station can't grow further". Else `if (station.changed(cell)) { station.regrow(); s.swap(station.planet); ShadowWorld.attach(station.planet, "station-" + id) if focused }`.
  - `PlanetClient.placed(s)` calls `StationClient.afterPlace(s, s.lastPlaced())` when `s.station() != null`.
  - Breaking: `PlanetClient.breakBlock` returns early (no progress, no crack) when `StationClient.isCore(focus, aim.cell())`.
  - Right click on the core cell (in `PlanetClient.use`, before item use): open `StationScreen`.
  - `pack(s)`: `stage = null`, write the file, `s.remove()`, drop the session, give `PackedStationItem.stack(...)`; if Mario's focus was it, nothing else (he is in space now).
  - `unfold`: same checks as `placeCore`; read the file, new rotation/center as for a core, `stage` = current, spawn, consume the item, save.
  - `tick`: every 20 ticks, `StationStore.list()` cache (refreshed on writes) → activate headers with `stage == current` within `ACTIVE` of Mario, deactivate past `INACTIVE` (save first if unsaved). Every 200 ticks save unsaved active stations (on `PlanetClient.saver`).
  - `PlanetClient.planets()` appends `StationClient.sessions()` (so focus, detail, update/send, origin moves and the shadow follow them); `indexOf` returns −1 for a station (never a catalog index); `spawnPlanet`'s "others" includes stations' bodies so a new planet keeps clear.
  - `Flight.bodies()` → `for (PlanetSession s : PlanetClient.planets()) out.add(s.body(GravityFrame.SCALE))`.
- [ ] **Step 3: `StationScreen`**: a Minecraft `Screen` with an `EditBox` (name, max 32 chars), a line of info (`W × D × H, N blocks`), buttons Rename / Pack up / Done. Styled like `PlanetEditorScreen` (read it first and reuse its layout helpers).
- [ ] **Step 4: Commands** in `GalaxyCraftClient`'s `/galaxycraft` tree: `station list` (id, name, stage or "packed", blocks) and `station restore <id>` (gives the Packed Station item of a packed station; a placed one says where it is).
- [ ] **Step 5: Build** `./gradlew build` → SUCCESS; `./gradlew test` → PASS.
- [ ] **Step 6: Commit** — `git commit -am "stations: place, grow, pack and unfold; the core's menu"` (add files).

### Task 11: `StationProbe` (no Dolphin)

**Files:** Create `fabric/src/gametest/java/dev/moui/galaxycraft/gametest/StationProbe.java`; Modify `src/gametest/resources/fabric.mod.json` (entry), `WalkOnStubPlanetTest` (skip list), `build.gradle` (`-PgalaxycraftStation` → `-Dgalaxycraft.station=true -Dgalaxycraft.hidden=true -Dgalaxycraft.stationsAnywhere=true -Dgalaxycraft.planetDir=${buildDir}/test-planets`).

- [ ] **Step 1: Write the probe** after `ShadowProbe`/`SpawnProbe` (read them first: how they stand up a planet without Dolphin, drive placements through `PlanetSession.placeBlock` and wait for the shadow). It must, logging `[GalaxyCraft station] ...` lines and ending with `PASS` or `FAIL <why>`:
  1. Give the core item; call `StationClient.placeCore` where no body is → a station session is active, 81 blocks, core at station (0,0,0).
  2. Place farmland + water + wheat seeds at (1,1,0)… via `placeBlock` on the station; random tick speed 1000 (`gamerule random_tick_speed 1000`); wait ≤ 600 ticks for the wheat cell to reach age 7.
  3. Place a chest at (2,1,0), put 5 diamonds in it through the shadow world's block entity.
  4. Place blocks outward along +x until a regrow happened (`StationClient` regrow count +1); the earlier cells are still there at their station coordinates.
  5. Try to mine the core → still there.
  6. `StationClient.pack` → session gone, the inventory holds a Packed Station named "Station".
  7. `unfold` it 300 blocks away → the wheat, the chest with 5 diamonds, the regrown blocks are all there.
  8. Place a block past the max span (fake bounds via a 256-long row is slow: instead call `afterPlace` on a cell whose station x is `bounds.x0 + 256`) → refused, cell air, item returned.
- [ ] **Step 2: Run** `cd fabric && ./gradlew runClientGameTest -PgalaxycraftStation 2>&1 | grep -oE '\[GalaxyCraft station\].*|BUILD.*'` → `PASS`, `BUILD SUCCESSFUL`. Fix and rerun until it passes.
- [ ] **Step 3: Commit** — `git commit -am "test: StationProbe, a station's whole life without the game"` (add the probe).

### Task 12: In the game (Dolphin), docs, roadmap

**Files:** Create `fabric/src/gametest/java/.../StationDolphinProbe.java`, `docs/ESTACIONES.md`; Modify `tools/gxvoxel.sh` (`station` → `TEST=StationDolphinProbe PROP=galaxycraftStationGame TAG=station`), `build.gradle` (that property, like `galaxycraftElytra`), `ROADMAP.md`.

- [ ] **Step 1: The probe** (pattern: `ElytraProbe`): 3000 blocks above the stage (empty space), place a core ahead of the player, build a 20-block row (forces a regrow), screenshot `station-built.png`; Mario lands on it (`land` / P) and stands 5 s with `in_game` true and gravity (`ctl status`) — no fall; walk off the edge → `Flight.inVoid()` within 3 s; fly back (wind) → lands; from 300 blocks away `station-far.png` shows its far view; open the core menu, pack, check the game dropped it (`VoxelStats` planets count); log `max_speed` from VoxelStats on the station vs. the probe's start.
- [ ] **Step 2: Run** `pgrep -af gxplay` (must be empty or closed with the user's earlier permission), then `tools/gxvoxel.sh station`; look at the screenshots. Fix what fails (most likely: the box matrix scale (Task 7 Step 5), the regrow collision gap, the gravity's priority vs. the stage's).
- [ ] **Step 3: User doc** `docs/ESTACIONES.md` (Spanish, like `ELITROS.md`): what a station is, the recipe, placing, growing, the menu, packing, limits, the commands.
- [ ] **Step 4: Roadmap**: Flat space stations → Done when merged (date + commit), Now/Next updated; public roadmap doc and Discord `#devlog` / `#roadmap` per CLAUDE.md / CLAUDE.local.md after the merge.
- [ ] **Step 5: Commit** — `git commit -am "test: stations in the game; docs: ESTACIONES.md"` (add files).

---

## Self-review notes

- Spec §1 (everything a planet does): Task 3 + shadow (Task 8) + Task 11's farm/chest checks. §3.1 Task 1. §3.2 Task 2. §3.3 Tasks 4–5. §3.4 Tasks 5, 8, 10. §3.5 Tasks 6–7. §3.6 Tasks 6–7. §3.7 Tasks 9–10. §3.8 Tasks 10–11. §4 Tasks 2–12.
- Review Focus: 1 → Task 12 Step 1 (stand during regrow); 2 → Task 4 tests + Task 11 step 8; 3 → Task 12 Step 1 (pack while standing: add "pack while Mario stands on it" to the probe's pack step); 4 → Task 10 Step 1 test; 5 → Task 7 Step 5 (MoveOrigin) + Task 12 probe crossing an origin move (fly 1100 blocks off and back with the station active).
