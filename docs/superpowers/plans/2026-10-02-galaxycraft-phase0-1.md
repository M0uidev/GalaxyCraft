# GalaxyCraft Fases 0–1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Un mod Fabric (MC 26.3) que, conectado por memoria compartida a un stub Python que hace de Dolphin+SMG2, permite caminar con física vanilla de Minecraft alrededor de un planeta esférico con gravedad radial.

**Architecture:** Protocolo de memoria compartida (`/dev/shm/galaxycraft_v1`) con header C como fuente de verdad y espejos Python/Java. El stub publica colisión KCL y gravedad; el mod mantiene un marco girado (`GravityFrame`) para que la gravedad siempre sea −Y y voxeliza la colisión bajo demanda dentro de la consulta de colisión de `Entity`.

**Tech Stack:** C11 (gcc), Python 3.14 stdlib (`unittest`, `mmap`, `struct`), Java 25 + FFM (`MemorySegment`), Fabric Loom 1.18-SNAPSHOT, Gradle 9.7.1, MC 26.3, Fabric Loader 0.19.5, Fabric API 0.161.0+26.3, JOML (incluido en MC), JUnit 5.

**Spec:** `docs/superpowers/specs/2026-10-02-galaxycraft-design.md`

## Global Constraints

- Shm: archivo `/dev/shm/galaxycraft_v1`, little-endian, structs de tamaño fijo, magic `GXCR`, versión de protocolo `1`.
- Escala: 1 bloque = 100 unidades SMG (`SCALE = 0.01`).
- KCL en big-endian (formato Nintendo), prisma de 0x10 bytes.
- Java 25, Fabric Loader ≥ 0.19.5, MC `~26.3`. Mod id: `galaxycraft`. Paquete: `dev.moui.galaxycraft`.
- Pasos de gravedad: rotar solo si el ángulo > 0.05°; `|g| < 1e-6` mantiene el marco.
- Voxelizado a 1/8 de bloque; superficies con `normal·up < cos(55°)` se elevan 1 bloque.
- Heartbeat caído > 2000 ms ⇒ estado seguro (mod congela el marco y deja de inyectar colisión nueva).
- Toolchains sin sudo: JDK en `~/.local/opt/jdk-25*`; usar `JAVA_HOME` explícito.

## Review Focus

1. **Gravedad casi antiparalela** (saltar entre dos planetas: `u_new ≈ −u_old`): la rotación mínima no está definida; se espera un eje perpendicular estable, sin NaN. → test en Task 6.
2. **Stub muerto o no iniciado** (shm inexistente o heartbeat viejo): el mod debe arrancar y jugar sin crash, sin colisión. → test en Task 4 (`BridgeClient` con archivo ausente) y Task 8 (manual).
3. **Ring buffer lleno o mensaje que da la vuelta al final** (KCL grandes troceados): no corromper; el productor espera/descarta. → test en Task 2 y Task 4.
4. **Triángulo degenerado en KCL** (área cero, `dot(CB, EnrmC) ≈ 0`): el parser lo descarta en vez de devolver Inf. → test en Task 5.
5. **Caídas largas en MC** (y fuera de [-64, 320]): rebase del marco para no morir en el vacío. → test en Task 6.

---

## Estructura de archivos

```
protocol/galaxycraft_protocol.h        fuente de verdad del layout
protocol/test_layout.c                 static_assert de offsets
tools/gxproto.py                       espejo Python: offsets, seqlock, ring, shm
tools/kcl.py                           escribir y leer KCL (big-endian)
tools/fake_galaxy.py                   stub host: planeta + gravedad + heartbeat
tools/tests/test_gxproto.py            tests del espejo y ring
tools/tests/test_kcl.py                roundtrip KCL
tools/tests/test_fake_galaxy.py        gravedad e icosfera
fabric/                                mod (Loom)
  src/main/java/dev/moui/galaxycraft/
    proto/Layout.java                  offsets (espejo del header)
    proto/Shm.java                     mapeo FFM del archivo
    proto/Seqlock.java                 lectura/escritura seqlock
    proto/Ring.java                    SPSC ring
    bridge/BridgeClient.java           conexión, eventos, estados
    kcl/KclParser.java                 KCL → triángulos
    geom/Tri.java                      triángulo (double)
    geom/TriangleIndex.java            rejilla espacial en coords galácticas
    geom/Voxelizer.java                triángulos → cajas 1/8
    gravity/GravityFrame.java          marco girado
    collision/CollisionField.java      partes + índice + consulta
    mixin/EntityCollideMixin.java      añade formas a la consulta
    GalaxyCraft.java                   entrypoint común
  src/client/java/dev/moui/galaxycraft/client/GalaxyCraftClient.java   tick + PlayerState
  src/test/java/...                    JUnit
```

---

### Task 1: Header del protocolo y test de layout C

**Files:**
- Create: `protocol/galaxycraft_protocol.h`, `protocol/test_layout.c`, `protocol/Makefile`

**Interfaces:**
- Produces: constantes `GXC_*` (offsets/tamaños) y structs que Tasks 2 y 4 replican exactamente.

Layout (offsets en bytes desde el inicio del archivo):

| Región | Offset | Tamaño |
|---|---|---|
| Header | 0 | 64 |
| WorldState (S→M, seqlock) | 64 | 64 |
| PlayerState (M→S, seqlock) | 128 | 96 |
| InputState (S→M, seqlock) | 224 | 96 |
| Ring S→M (16 B cabecera + 4 MiB) | 4096 | 16 + 4194304 |
| Ring M→S (16 B cabecera + 64 KiB) | 4198416 | 16 + 65536 |
| Overlay (32 B cabecera + 3 × 1920×1080×4) | 4268032 | 32 + 24883200 |
| **Total** | | **29151264** |

- [ ] **Step 1: Escribir el header** con `GxcHeader` (magic u32, version u32, host_pid u32, mod_pid u32, host_heartbeat_ms u64, mod_heartbeat_ms u64, host_flags u32, mod_flags u32, reserved[24]), `GxcWorldState` (seq u32, scene_id u32, frame_id u64, gravity f32[3], query_pos f32[3], flags u32, pad hasta 64), `GxcPlayerState` (seq u32, flags u32, frame_id u64, pos f32[3], look f32[3], up f32[3], fov_y f32, eye_height f32, pad hasta 96), `GxcInputState` (seq u32, buttons u32, mouse_x f64, mouse_y f64, wheel f64, keys u8[64]), `GxcRingHeader` (head u32, tail u32, capacity u32, pad u32), `GxcMsgHeader` (type u16, reserved u16, length u32), tipos de mensaje `GXC_MSG_SCENE_CHANGE=1, PART_UPSERT=2, PART_REMOVE=3, KCL_CHUNK=4, HELLO=101`, payloads `GxcPartUpsert {part_id u32, kcl_size u32, mtx f32[12]}` (3x4 fila mayor), `GxcKclChunk {part_id u32, offset u32, total u32, data[]}`, `KCL_CHUNK_MAX=65536`, mensajes alineados a 8.
- [ ] **Step 2: test_layout.c** con `_Static_assert(sizeof(...)==N)` y `offsetof` para cada struct y región, más un `main` que imprime `OK`.
- [ ] **Step 3:** `cc -std=c11 -Wall -Werror protocol/test_layout.c -o /tmp/gxc_layout && /tmp/gxc_layout` → `OK`.
- [ ] **Step 4: Commit** `feat(protocol): shared memory layout v1`.

### Task 2: Espejo Python del protocolo

**Files:**
- Create: `tools/gxproto.py`, `tools/tests/test_gxproto.py`

**Interfaces:**
- Produces: `class Shm(path=SHM_PATH, create=False)` con `.buf` (mmap); `write_world(shm, scene_id, frame_id, gravity, query_pos)`, `read_player(shm) -> PlayerState|None`, `heartbeat_host(shm)`, `class Ring(shm, offset)` con `.push(type, payload) -> bool`, `.pop() -> (type, bytes)|None`; constantes iguales al header.

- [ ] **Step 1: Tests que fallan:**

```python
class RingTest(unittest.TestCase):
    def setUp(self):
        self.shm = gxproto.Shm(path=tmp_path(), create=True)
        self.ring = gxproto.Ring(self.shm, gxproto.RING_S2M_OFF)
    def test_roundtrip(self):
        self.assertTrue(self.ring.push(4, b"abc"))
        self.assertEqual(self.ring.pop(), (4, b"abc"))
        self.assertIsNone(self.ring.pop())
    def test_wraparound(self):
        payload = bytes(range(256)) * 200           # 51200 bytes
        for i in range(200):                         # > capacidad total: debe dar la vuelta
            self.assertTrue(self.ring.push(4, payload))
            self.assertEqual(self.ring.pop(), (4, payload))
    def test_full_returns_false(self):
        big = b"x" * gxproto.KCL_CHUNK_MAX
        n = 0
        while self.ring.push(4, big): n += 1
        self.assertEqual(n, gxproto.RING_S2M_CAP // (gxproto.KCL_CHUNK_MAX + 8))
class SeqlockTest(unittest.TestCase):
    def test_player_roundtrip(self):
        shm = gxproto.Shm(path=tmp_path(), create=True)
        gxproto.write_player(shm, frame_id=7, pos=(1,2,3), look=(0,0,-1), up=(0,1,0), fov_y=70, eye=1.62, on_ground=True)
        p = gxproto.read_player(shm)
        self.assertEqual(p.frame_id, 7); self.assertEqual(p.pos, (1.0,2.0,3.0)); self.assertTrue(p.on_ground)
```

- [ ] **Step 2:** `python3 -m unittest discover -s tools/tests -v` → FAIL (módulo no existe).
- [ ] **Step 3: Implementar.** Ring: `head`/`tail` son contadores monótonos módulo 2³² en bytes; espacio libre = `cap - (head - tail)`; un mensaje que no cabe antes del final escribe un `type=0xFFFF` (padding) y continúa en 0; si el resto es < 8 bytes, se salta implícitamente (el lector hace lo mismo). Seqlock: escritor `seq+=1` (impar), escribe, `seq+=1`; lector reintenta hasta 100 veces si `seq` impar o cambia.
- [ ] **Step 4:** tests PASS.
- [ ] **Step 5: Commit** `feat(tools): python protocol mirror`.

### Task 3: KCL writer/reader y stub `fake_galaxy.py`

**Files:**
- Create: `tools/kcl.py`, `tools/fake_galaxy.py`, `tools/tests/test_kcl.py`, `tools/tests/test_fake_galaxy.py`

**Interfaces:**
- Produces: `kcl.write(tris) -> bytes`, `kcl.read(data) -> list[tri]` (tri = 3 tuplas xyz), `fake_galaxy.icosphere(radius, subdiv) -> tris`, `fake_galaxy.gravity_at(pos, planets) -> (gx,gy,gz)` (unitario hacia el planeta más cercano), `fake_galaxy.main()`.

Formato KCL (BE): header `posOff, nrmOff, prismOff, octOff (u32), thickness f32, areaMin f32[3], masks u32[3], shifts u32[3]` = 0x38 bytes; posiciones f32[3]; normales f32[3]; prismas desde `prismOff + 0x10` hasta `octOff`: `height f32, posIdx u16, fnrm u16, en1 u16, en2 u16, en3 u16, attr u16`. Escritura por triángulo `v1,v2,v3`:
`F = unit((v2-v1)×(v3-v1))`, `EA = unit(F×(v3-v1))`, `EB = unit((v2-v1)×F)`, `EC = unit(F×(v2-v3))`, `height = (v2-v1)·EC`.
Lectura: `CA = EA×F`, `CB = EB×F`, `v2 = v1 + CB·(h/(CB·EC))`, `v3 = v1 + CA·(h/(CA·EC))`.

- [ ] **Step 1: Tests que fallan:**

```python
def test_roundtrip_triangle(self):
    tri = ((0,0,0),(100,0,0),(0,0,-100))
    out = kcl.read(kcl.write([tri]))
    self.assertEqual(len(out), 1)
    for a, b in zip(out[0], tri):
        for x, y in zip(a, b): self.assertAlmostEqual(x, y, places=2)
def test_roundtrip_icosphere(self):
    tris = fake_galaxy.icosphere(800, 2)
    out = kcl.read(kcl.write(tris))
    self.assertEqual(len(out), len(tris))
def test_degenerate_dropped(self):
    self.assertEqual(kcl.write([((0,0,0),(1,0,0),(2,0,0))]), kcl.write([]))
# test_fake_galaxy.py
def test_gravity_points_to_center(self):
    g = fake_galaxy.gravity_at((0, 1000, 0), [((0,0,0), 800)])
    self.assertAlmostEqual(g[1], -1.0)
def test_icosphere_vertices_on_radius(self):
    for tri in fake_galaxy.icosphere(800, 2):
        for v in tri: self.assertAlmostEqual(math.dist(v, (0,0,0)), 800, places=3)
```

- [ ] **Step 2:** FAIL.
- [ ] **Step 3: Implementar.** `fake_galaxy.main()`: crea la shm, escribe header (magic, version, host_pid), publica `SCENE_CHANGE(1)`, `PART_UPSERT(part 1, planeta radio 800 en origen, mtx identidad)` y `KCL_CHUNK`s; un segundo planeta de radio 600 en `(0, 2600, 0)` (part 2) para probar saltos entre planetas. Bucle a 60 Hz: heartbeat, lee `PlayerState`, calcula gravedad en `pos`, escribe `WorldState`; cada segundo imprime `pos`, altitud sobre el planeta más cercano, `on_ground`. Si el mod envía `HELLO` reenvía todas las partes (el mod puede arrancar después del stub). Spawn sugerido: `(0, 820, 0)`. Flags `--once` (publica y sale tras 1 s) para tests.
- [ ] **Step 4:** PASS.
- [ ] **Step 5: Commit** `feat(tools): KCL codec and fake galaxy stub`.

### Task 4: Proyecto Fabric + protocolo Java + BridgeClient

**Files:**
- Create: `fabric/` (plantilla oficial con mod id `galaxycraft`), `proto/Layout.java`, `proto/Shm.java`, `proto/Seqlock.java`, `proto/Ring.java`, `bridge/BridgeClient.java`, tests `ProtoTest.java`, `BridgeClientTest.java`

**Interfaces:**
- Produces:
  - `Shm.open(Path) -> Optional<Shm>`; `MemorySegment seg()`.
  - `Seqlock.readWorld(MemorySegment) -> Optional<WorldState>`; `record WorldState(int sceneId, long frameId, Vector3d gravity, Vector3d queryPos)`.
  - `Seqlock.writePlayer(MemorySegment, PlayerOut)`; `record PlayerOut(long frameId, Vector3d pos, Vector3d look, Vector3d up, float fovY, float eye, boolean onGround)`.
  - `Ring(MemorySegment seg, long offset)` con `Optional<Msg> pop()`, `boolean push(int type, byte[] p)`; `record Msg(int type, byte[] payload)`.
  - `BridgeClient(Path shmPath, LongSupplier clockMs)`: `void poll()` (reintenta abrir cada 1 s, procesa eventos), `boolean linked()` (heartbeat host < 2000 ms), `Optional<WorldState> world()`, `void sendPlayer(PlayerOut)`, listener `PartListener { onUpsert(int id, double[] mtx12, byte[] kcl); onRemove(int id); onScene(int id); }` — `onUpsert` se dispara cuando el KCL de la parte está completo (chunks reensamblados), y otra vez si sólo cambia la matriz (con el mismo `kcl`).

- [ ] **Step 1: Scaffold.** Copiar `build.gradle`, `settings.gradle`, `gradle.properties`, wrapper (`gradle wrapper` no existe: descargar `gradlew` y `gradle-wrapper.jar` del example mod), renombrar `modid`→`galaxycraft`, grupo `dev.moui`. Añadir a `build.gradle`:

```groovy
dependencies {
    testImplementation platform("org.junit:junit-bom:5.13.4")
    testImplementation "org.junit.jupiter:junit-jupiter"
    testRuntimeOnly "org.junit.platform:junit-platform-launcher"
}
test { useJUnitPlatform() }
```

- [ ] **Step 2: Tests que fallan** (`ProtoTest`): crea archivo temporal de `Layout.TOTAL_SIZE`, escribe con `Seqlock.writePlayer` y lee bytes crudos en los offsets del header C (`pos` en `128+16`); `Ring` push/pop/wraparound como en Task 2; **interop**: ejecutar `python3 tools/fake_galaxy.py --once --shm <tmp>` (via `ProcessBuilder`, se omite con `assumeTrue` si no hay python3) y comprobar que `BridgeClient` recibe `onScene(1)` y dos `onUpsert` con KCL de tamaño > 0. `BridgeClientTest`: ruta inexistente ⇒ `linked()==false`, `poll()` no lanza; heartbeat viejo (reloj falso +3000 ms) ⇒ `linked()==false`.
- [ ] **Step 3:** `JAVA_HOME=$(ls -d ~/.local/opt/jdk-25*) ./gradlew test` → FAIL de compilación.
- [ ] **Step 4: Implementar** con `FileChannel.open(path, READ, WRITE).map(READ_WRITE, 0, size, Arena.ofShared())`, `ValueLayout.JAVA_INT_UNALIGNED.withOrder(LITTLE_ENDIAN)`, acceso volátil al `seq`/`head`/`tail` vía `VarHandle` (`getAcquire`/`setRelease`).
- [ ] **Step 5:** PASS. **Commit** `feat(fabric): protocol and bridge client`.

### Task 5: KclParser Java

**Files:**
- Create: `kcl/KclParser.java`, `geom/Tri.java`, test `KclParserTest.java`, recurso `src/test/resources/icosphere.kcl` (generado por `python3 -c "import sys; sys.path.insert(0,'tools'); import kcl, fake_galaxy; open(sys.argv[1],'wb').write(kcl.write(fake_galaxy.icosphere(800,2)))" fabric/src/test/resources/icosphere.kcl`)

**Interfaces:**
- Produces: `record Tri(Vector3d a, Vector3d b, Vector3d c, Vector3d n)`; `KclParser.parse(byte[]) -> List<Tri>` (lanza `IllegalArgumentException` si el header es inválido; descarta prismas degenerados).

- [ ] **Step 1: Tests:** icosfera ⇒ 320 triángulos, todos los vértices a 800±0.05 del origen; normal apunta hacia fuera (`n·a > 0`); bytes truncados ⇒ `IllegalArgumentException`; prisma con `CB·EC = 0` (construido a mano en el test) ⇒ descartado, sin NaN.
- [ ] **Step 2:** FAIL. **Step 3:** implementar fórmula de Task 3 con `ByteBuffer.order(BIG_ENDIAN)`; descartar si `|CB·EC| < 1e-9` o algún componente no finito. **Step 4:** PASS. **Step 5: Commit** `feat(fabric): KCL parser`.

### Task 6: GravityFrame

**Files:**
- Create: `gravity/GravityFrame.java`, test `GravityFrameTest.java`

**Interfaces:**
- Produces: `GravityFrame(Vector3d galStart, Vector3d mcStart, Vector3d gStart)`;
  `Vector3d toMc(Vector3d gal)`, `Vector3d toGal(Vector3d mc)`, `Vector3d dirToMc(Vector3d)`, `Vector3d dirToGal(Vector3d)`;
  `Update update(Vector3d gravityGal, Vector3d playerMc)` → `record Update(boolean rotated, Quaterniond deltaMc)` donde `deltaMc = R'·R⁻¹` (para rotar velocidad y mirada en coords MC);
  `Optional<Vector3d> rebase(Vector3d playerMc)` → si `y ∉ [36, 164]` mueve el marco para que el jugador quede en `y=100`, devuelve el nuevo `playerMc` (solo traslación).
  Estado: `Quaterniond r` (gal→mc), `Vector3d t`, `SCALE = 0.01`.

- [ ] **Step 1: Tests:**

```java
@Test void upMatchesGravity() {
    var f = new GravityFrame(v(0,820,0), v(0,100,0), v(0,-1,0));
    var g = v(1,0,0);                                   // gravedad hacia +X galáctico
    f.update(g, v(0,100,0));
    assertVec(v(0,-1,0), f.dirToMc(g), 1e-9);           // en MC siempre cae hacia -Y
}
@Test void playerPositionContinuous() {
    var f = new GravityFrame(v(0,820,0), v(0,100,0), v(0,-1,0));
    var p = v(3,100,-2);
    var galBefore = f.toGal(p);
    f.update(v(0.3,-1,0.1).normalize(), p);
    assertVec(galBefore, f.toGal(p), 1e-9);             // el jugador no se mueve en la galaxia
}
@Test void walkAroundSphereReturnsHome() {             // 3600 pasos tangenciales sobre esfera r=800
    ... cada paso: mover 0.0698 bloques hacia +X en MC (= 2π·8/720/… ajustado), pos gal = toGal,
    proyectar a la esfera, gravedad = -pos.normalize(), update; tras una vuelta completa
    toGal(p) vuelve a (0,800,0) ± 1 unidad y dirToMc(-gal.normalize()) == -Y.
}
@Test void antiparallelNoNaN() {
    var f = new GravityFrame(v(0,820,0), v(0,100,0), v(0,-1,0));
    var u = f.update(v(0,1,0), v(0,100,0));
    assertTrue(u.rotated()); assertFinite(u.deltaMc());
    assertVec(v(0,-1,0), f.dirToMc(v(0,1,0)), 1e-9);
}
@Test void tinyAngleIgnored() { ... gravedad girada 0.01° ⇒ rotated()==false }
@Test void zeroGravityKeepsFrame() { ... v(0,0,0) ⇒ rotated()==false }
@Test void rebaseKeepsGalaxyPosition() {
    var f = new GravityFrame(v(0,820,0), v(0,100,0), v(0,-1,0));
    var p = v(5,10,5); var gal = f.toGal(p);
    var np = f.rebase(p).orElseThrow();
    assertEquals(100, np.y, 1e-9); assertVec(gal, f.toGal(np), 1e-9);
}
```

- [ ] **Step 2:** FAIL. **Step 3: Implementar:** `u_old = r⁻¹·(+Y)`, `u_new = −ĝ`; `q = rotationTo(u_old, u_new)` (JOML; si `u_old·u_new < −0.9999` usar eje `u_old × (1,0,0)` o `× (0,0,1)` y 180°); `r' = r · q⁻¹`; `deltaMc = r'·r⁻¹`; `t' = p_mc − r'·(SCALE·gal)` con `gal = toGal(p)` calculado antes de cambiar `r`. **Step 4:** PASS. **Step 5: Commit** `feat(fabric): gravity frame`.

### Task 7: TriangleIndex + Voxelizer + CollisionField

**Files:**
- Create: `geom/TriangleIndex.java`, `geom/Voxelizer.java`, `collision/CollisionField.java`, tests `VoxelizerTest.java`, `CollisionFieldTest.java`

**Interfaces:**
- Consumes: `Tri`, `KclParser`, `GravityFrame`.
- Produces:
  - `TriangleIndex(double cellGal=200)`: `void put(int partId, List<Tri> galTris)`, `void remove(int partId)`, `List<Tri> query(Vector3d minGal, Vector3d maxGal)`.
  - `Voxelizer.voxelize(List<Tri> mcTris, double[] boxMc /*minX,minY,minZ,maxX,maxY,maxZ*/) -> List<double[]>` (cajas en coords MC, celdas de 0.125 alineadas a múltiplos de 0.125, fusionadas en X; triángulos con `n·(+Y) < cos 55°` y no orientados hacia abajo (`n·Y > -0.2`) extienden su caja +1.0 en Y). Test SAT triángulo-caja (Akenine-Möller).
  - `CollisionField(BridgeClient-free)`: `void upsertPart(int id, double[] mtx12, byte[] kcl)` (parsea, aplica matriz 3x4 fila mayor), `void removePart(int id)`, `void clear()`, `void setFrame(GravityFrame)`, `List<double[]> boxesFor(double[] queryMc)` (convierte la caja MC a AABB galáctica envolvente vía las 8 esquinas, consulta el índice, transforma triángulos a MC, voxeliza; vacío si no hay marco). Límite: si la consulta abarca > 4096 celdas por eje combinado (caja > 8 bloques por lado) se recorta a 8×8×8 alrededor del centro.

- [ ] **Step 1: Tests:** suelo plano (2 triángulos en y=0) y caja `[-.5,-.2,-.5,.5,.5,.5]` ⇒ todas las cajas tienen `maxY ≤ 0.125` y cubren X/Z completos; pared vertical en x=0 ⇒ cajas con altura ≥ 1.0; caja fuera de los triángulos ⇒ lista vacía; `CollisionField` con icosfera y marco inicial en `(0,820,0)` ⇒ la consulta bajo los pies (`y∈[99.5,100.1]` en MC, jugador en y=100 a 20 u = 0.2 bloques sobre la superficie) devuelve cajas cuyo `maxY` está en `[99.75, 100]`.
- [ ] **Step 2:** FAIL. **Step 3:** implementar. **Step 4:** PASS. **Step 5: Commit** `feat(fabric): collision field and voxelizer`.

### Task 8: Integración en Minecraft (Mixin + cliente) y verificación con el stub

**Files:**
- Create: `GalaxyCraft.java`, `mixin/EntityCollideMixin.java`, `galaxycraft.mixins.json`, `client/GalaxyCraftClient.java`, `docs/PHASE1.md` (cómo correrlo)
- Modify: `fabric.mod.json`

**Interfaces:**
- Consumes: todo lo anterior.
- Produces: `GalaxyCraft.FIELD` (singleton `CollisionField`, compartido por cliente e integrated server en el mismo JVM), `GalaxyCraft.BRIDGE`.

- [ ] **Step 1: Confirmar el punto de inyección** en las fuentes de 26.3: `./gradlew genSources` y buscar en `Entity.java` la llamada `getEntityCollisions` dentro de `collide(Vec3)`. El Mixin hace `@ModifyExpressionValue`/`@Redirect` (MixinExtras viene con Fabric Loader) sobre esa llamada y, si `this instanceof Player` y `FIELD` tiene marco, devuelve la lista original + `Shapes.box(...)` por cada caja de `FIELD.boxesFor(aabbExpandida)`.
- [ ] **Step 2: Cliente.** `ClientTickEvents.START_CLIENT_TICK`: `BRIDGE.poll()`; si `linked()` y hay jugador: crear `GravityFrame` la primera vez (galStart = `(0,820,0)` desde el stub vía `WorldState.queryPos` o el spawn por defecto), `update(gravity, playerPos)`, si `rotated` rotar `player.getDeltaMovement()` y la mirada con `deltaMc` (`setYRot/setXRot` a partir del vector rotado; `yRotO/xRotO` igual para no interpolar el salto), `rebase` con `player.setPos`. `WorldRenderEvents`/fin de frame: `sendPlayer` con pos/mirada/up convertidos a galaxia. Comando `/galaxycraft status`.
- [ ] **Step 3: Mundo de prueba.** `docs/PHASE1.md`: crear mundo *Superflat → The Void*, modo aventura, pacífico, `/gamerule doDaylightCycle false`.
- [ ] **Step 4: Verificación manual con stub** (`python3 tools/fake_galaxy.py` + `./gradlew runClient`): (a) el jugador queda de pie sobre el planeta (stub muestra altitud ≈ 0 y `on_ground=1`); (b) caminar en línea recta 60 s da la vuelta al planeta (altitud estable, sin caer); (c) saltar no escapa; (d) matar el stub ⇒ el juego sigue sin crash; reiniciarlo ⇒ se reconecta. Capturar log.
- [ ] **Step 5: Commit** `feat(fabric): walk on stub planet`.

---

## Siguientes planes (fuera de este)

- **Fase 2:** fork de Dolphin (GalaxyBridge, Compositor, InputForwarder) + OverlayExporter/InputInjector/ventana oculta en el mod.
- **Fase 3:** módulo Syati (buzón, gravedad, marioneta, cámara, exportador de partes) — requiere el ISO y CodeWarrior+Kamek.
- **Fase 4:** integración en un planeta real.
