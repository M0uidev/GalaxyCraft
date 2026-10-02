# GalaxyCraft Fase 2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Un Dolphin con GalaxyCraft que (a) sincroniza el buzón en RAM emulada con la memoria compartida, (b) dibuja encima del juego el overlay que exporta Minecraft y (c) reenvía teclado y ratón a Minecraft; más el lado del mod que exporta el overlay e inyecta la entrada.

**Architecture:** La lógica del host vive en una librería C++17 autónoma (`dolphin/galaxycraft/`) sin dependencias de Dolphin, con su propio ejecutable de tests. Un parche pequeño sobre Dolphin (`dolphin/patches/`) la engancha en `vi_end_field_event` (hilo CPU, acceso seguro a RAM), en `OnScreenUI::Finalize` (overlay con ImGui) y en `RenderWidget::PassEventToPresenter` (entrada). El mod añade `OverlayWriter` (triple buffer) e `InputDiff` (estado → eventos) como clases puras testeadas, y la integración con Minecraft encima.

**Tech Stack:** C++17 (gcc 15), CMake + Ninja, Dolphin master (`5390a61`), Qt6; Java 25, Fabric 26.3.

**Spec:** `docs/superpowers/specs/2026-10-02-galaxycraft-design.md` (§3, §8, §9; §10 fase 2)

## Global Constraints

- Shm `/dev/shm/galaxycraft_v1` v1 (offsets de `protocol/galaxycraft_protocol.h`); el host la crea y limpia header+slots.
- Buzón en RAM emulada: **big-endian**, magic `GXCRMBX1`, versión 1, alineado a 4. El host lo busca en MEM1 (`0x80000000`, 24 MiB) y MEM2 (`0x90000000`, 64 MiB).
- Activación sólo con la variable de entorno `GALAXYCRAFT=1` (Dolphin sin ella se comporta igual que upstream).
- Overlay: RGBA8, filas de arriba a abajo, alpha no premultiplicado, máx 1920×1080.
- InputState: teclas como **scancodes SDL** (Minecraft 26.x usa SDL3; corregido desde "GLFW"), bitmap de 64 bytes; botones bit n = botón SDL n; ratón como deltas acumulados en píxeles.
- Heartbeat > 2000 ms = otro lado caído. Con el mod caído el host pone `drive = 0` en el buzón (Mario vuelve a su control).
- Disco: ~7 GB libres; la build de Dolphin vive en `dolphin/build` (git-ignored).

## Review Focus

1. **Buzón que desaparece o se mueve** (cambio de galaxia recarga el módulo): el host debe re-escanear y no escribir en la dirección vieja. → test en Task 2.
2. **Parte con KCL fuera de la RAM válida** (dirección basura): el host la ignora, sin crash ni lectura fuera de rango. → test en Task 2.
3. **Mod que arranca antes o después que Dolphin**: HELLO re-publica todo con ancla. → test en Task 2.
4. **Overlay de tamaño distinto a la ventana o sin frames** (mod no corriendo): Dolphin no dibuja nada y no crashea. → test en Task 3.
5. **Teclas sin equivalente GLFW** (multimedia, etc.): se ignoran. → test en Task 3.

---

## Estructura de archivos

```
protocol/galaxycraft_protocol.h        + GxcMailbox, GxcMbxPart (big-endian en RAM)
protocol/test_layout.c                 + asserts del buzón
dolphin/galaxycraft/                   librería del host (sin Dolphin)
  CMakeLists.txt                       lib galaxycraft_host + exe galaxycraft_host_tests
  GuestMemory.h                        interfaz de RAM emulada (+ FakeGuestMemory en tests)
  Shm.h/.cpp                           crear/mapear el archivo, seqlock, ring (C++)
  HostBridge.h/.cpp                    tick por campo de video: buzón ⇄ shm
  Overlay.h/.cpp                       lectura del último frame completo
  Input.h/.cpp                         InputState + Qt→GLFW
  tests/main.cpp                       mini runner (sin gtest)
  tests/*_test.cpp
dolphin/patches/0001-galaxycraft.patch integración en Dolphin (CMake, Core, VideoCommon, DolphinQt)
dolphin/build.sh                       aplica parche, enlaza la librería, compila
fabric/.../overlay/OverlayWriter.java  triple buffer en la shm (puro)
fabric/.../input/InputDiff.java        InputState → eventos de tecla/ratón (puro)
fabric/.../mixin/client/*              exportar frame, cielo transparente, inyectar entrada
```

---

### Task 1: Buzón en el protocolo

**Files:** Modify `protocol/galaxycraft_protocol.h`, `protocol/test_layout.c`

**Interfaces:**
- Produces: `GxcMailbox` (3944 B) y `GxcMbxPart` (60 B), offsets usados por Task 2 (host) y por la Fase 3 (Syati).

| Campo | Off | Tipo | Quién escribe |
|---|---|---|---|
| magic | 0 | char[8] `GXCRMBX1` | juego |
| version | 8 | u32 | juego |
| game_seq | 12 | u32 | juego (+1 por frame) |
| host_seq | 16 | u32 | host (+1 por escritura) |
| scene_id | 20 | u32 | juego |
| gravity | 24 | f32[3] | juego (en `player_pos`, o en Mario si no hay drive) |
| anchor_pos | 36 | f32[3] | juego (posición de Mario) |
| game_flags | 48 | u32 | juego |
| host_flags | 52 | u32 (bit0 drive) | host |
| player_pos | 56 | f32[3] | host |
| look | 68 | f32[3] | host |
| up | 80 | f32[3] | host |
| fov_y | 92 | f32 | host |
| eye_height | 96 | f32 | host |
| part_count | 100 | u32 (≤64) | juego |
| parts | 104 | GxcMbxPart[64] `{part_id u32, kcl_addr u32, kcl_size u32, mtx f32[12]}` | juego |

- [ ] **Step 1:** añadir asserts a `test_layout.c` (`sizeof(GxcMailbox)==3944`, `offsetof(...,parts)==104`, `sizeof(GxcMbxPart)==60`, etc.).
- [ ] **Step 2:** `make -C protocol test` → FAIL (tipos no existen).
- [ ] **Step 3:** añadir structs + `GXC_MBX_MAGIC "GXCRMBX1"`, `GXC_MBX_MAX_PARTS 64`, `GXC_MBX_DRIVE 1u` con comentario "big-endian en RAM emulada".
- [ ] **Step 4:** PASS. **Step 5: Commit** `feat(protocol): guest mailbox layout`.

### Task 2: Librería del host — Shm + HostBridge

**Files:** Create `dolphin/galaxycraft/{CMakeLists.txt,GuestMemory.h,Shm.h,Shm.cpp,HostBridge.h,HostBridge.cpp,tests/main.cpp,tests/host_bridge_test.cpp}`

**Interfaces:**
- `struct GuestMemory { virtual bool Read(u32 addr, void* dst, u32 n) = 0; virtual bool Write(u32 addr, const void* src, u32 n) = 0; virtual std::vector<std::pair<u32,u32>> Regions() = 0; }` (direcciones efectivas `0x80000000`/`0x90000000`; `Read` devuelve false fuera de región).
- `class Shm { static std::unique_ptr<Shm> Create(const std::string& path); u8* Data(); /* LE helpers */ }`
- `class HostBridge { HostBridge(Shm&, std::function<u64()> clock_ms); void Tick(GuestMemory&); bool HasMailbox() const; bool Driving() const; }`

Comportamiento (cada `Tick`): heartbeat host; si no hay buzón (o su magic ya no está) escanear regiones cada 60 ticks; leer buzón (BE); publicar `SCENE_CHANGE` + todas las partes si cambió `scene_id`; diff de partes (nueva/KCL distinto ⇒ `PART_UPSERT` + chunks leídos de RAM; sólo matriz distinta > 1e-4 ⇒ `PART_UPSERT`; ausente ⇒ `PART_REMOVE`); `HELLO` del mod ⇒ re-publicar y ancla; `WorldState{gravity, query_pos = anchored ? anchor_pos : player_pos, flags = WORLD_ANCHOR si anchored}`; `PlayerState` fresco ⇒ escribir pose en el buzón (BE) y `host_flags |= DRIVE`; mod caído ⇒ `host_flags &= ~DRIVE`; `host_flags` del header shm bit0 = hay buzón.

- [ ] **Step 1: Tests que fallan** (runner mínimo con `CHECK(cond)`):
  - `finds_mailbox_and_publishes_scene`: FakeGuestMemory con buzón en `0x80401000`, 1 parte con KCL de 300 bytes en `0x80500000` ⇒ ring S2M contiene `SCENE_CHANGE`, `PART_UPSERT(size 300)`, `KCL_CHUNK` con los 300 bytes exactos.
  - `anchor_until_fresh_player`: WorldState `flags&1` y `query_pos == anchor_pos`; tras escribir PlayerState (frame 1) ⇒ flags 0, buzón tiene `player_pos` (BE) y `host_flags&1`.
  - `matrix_only_change_sends_upsert_without_chunks`.
  - `removed_part_sends_remove`.
  - `hello_republishes_and_reanchors`.
  - `mod_heartbeat_stale_drops_drive`.
  - `mailbox_moved_is_rescanned`: borrar magic en la dirección vieja, poner el buzón en otra ⇒ tras ≤60 ticks se encuentra; nunca se escribe en la vieja.
  - `bad_kcl_address_ignored`: `kcl_addr = 0x12345678` ⇒ sin upsert para esa parte, sin crash.
- [ ] **Step 2:** `cmake -S dolphin/galaxycraft -B dolphin/galaxycraft/build -G Ninja && ninja -C dolphin/galaxycraft/build && dolphin/galaxycraft/build/galaxycraft_host_tests` → FAIL de compilación.
- [ ] **Step 3:** implementar. **Step 4:** PASS. **Step 5: Commit** `feat(dolphin): host bridge library`.

### Task 3: Overlay + Input en la librería

**Files:** Create `dolphin/galaxycraft/{Overlay.h,Overlay.cpp,Input.h,Input.cpp,tests/overlay_input_test.cpp}`

**Interfaces:**
- `struct OverlayFrame { u32 width, height, frame_id; const u8* rgba; }`; `std::optional<OverlayFrame> LatestOverlay(Shm&)` (nada si `latest == 0xFFFFFFFF` o tamaño > máximo).
- `class InputWriter { InputWriter(Shm&); void Key(int glfw, bool down); void MouseDelta(double dx, double dy); void Buttons(u32 mask); void Wheel(double d); }` — cada llamada publica el InputState completo con seqlock.
- `int QtKeyToGlfw(int qt_key)` (−1 si no hay equivalente). Letras/dígitos/espacio = mismo código; Esc 256, Enter 257, Tab 258, Backspace 259, flechas 262–265, Shift 340, Ctrl 341, Alt 342, F1–F12 290–301.

- [ ] **Step 1: Tests:** overlay vacío ⇒ `nullopt`; overlay con `latest=1`, 4×2 ⇒ frame con píxeles del buffer 1; tamaño 4000×4000 ⇒ `nullopt`; `QtKeyToGlfw(Qt::Key_E=0x45)==69`, `Key_Escape(0x01000000)==256`, `Key_Shift(0x01000020)==340`, `0x01000061 (Key_VolumeDown)` ⇒ −1; `InputWriter.Key(69,true)` ⇒ bit 69 del bitmap, `MouseDelta(3,-2)` dos veces ⇒ `mouse_x==6, mouse_y==-4`, seq par.
- [ ] **Step 2:** FAIL. **Step 3:** implementar. **Step 4:** PASS. **Step 5: Commit** `feat(dolphin): overlay reader and input writer`.

### Task 4: Parche de integración en Dolphin

**Files:** Create `dolphin/patches/0001-galaxycraft.patch`, `dolphin/build.sh`; (en `dolphin/src`, versionado sólo vía parche) Modify `Source/Core/Core/CMakeLists.txt`, `Source/Core/Core/Core.cpp`, `Source/Core/VideoCommon/OnScreenUI.cpp`, `Source/Core/DolphinQt/RenderWidget.cpp`; Create `Source/Core/Core/GalaxyCraft.{h,cpp}` (adaptador `GuestMemory` sobre `Memory::MemoryManager::CopyFromEmu/CopyToEmu`, singleton con `Start/Stop`).

- [ ] **Step 1:** `GalaxyCraft::Start(system)` al arrancar emulación si `GALAXYCRAFT=1`: crea Shm, `HostBridge`, registra `vi_end_field_event` → `Tick`; `Stop` al parar.
- [ ] **Step 2:** `OnScreenUI::Finalize`: antes de `ImGui::Render()`, `GalaxyCraft::DrawOverlay()` — si hay frame nuevo, (re)crea `AbstractTexture` RGBA8 del tamaño del frame y `Load`; `ImGui::GetBackgroundDrawList()->AddImage(tex, {0,0}, DisplaySize)`.
- [ ] **Step 3:** `RenderWidget::PassEventToPresenter`: si `GalaxyCraft::InputActive()`, teclas → `InputWriter::Key(QtKeyToGlfw)`, botones → `Buttons`, rueda → `Wheel`, movimiento → delta respecto al centro + `QCursor::setPos(centro)` (ignorando el evento sintético), cursor oculto. Esc sigue yendo a Dolphin.
- [ ] **Step 4:** `dolphin/build.sh`: `git -C src apply ../patches/0001-galaxycraft.patch` si no está aplicado, enlaza `galaxycraft_host` por `add_subdirectory`, `ninja -C build dolphin-emu`. Compila sin warnings nuevos.
- [ ] **Step 5: Verificación:** `GALAXYCRAFT=1 build/Binaries/dolphin-emu` con el `.rvz` de SMG2 ⇒ `/dev/shm/galaxycraft_v1` existe, heartbeat avanza (`python3 -c` lee el header), header `host_flags&1 == 0` (aún no hay módulo Syati). **Commit** `feat(dolphin): GalaxyCraft integration patch`.

### Task 5: Mod — exportar overlay e inyectar entrada

**Files:** Create `overlay/OverlayWriter.java`, `input/InputDiff.java`, tests `OverlayWriterTest`, `InputDiffTest`; Create mixins cliente `LevelRendererSkipMixin` (no dibujar mundo/cielo y limpiar con alpha 0 cuando el host está enlazado), hook de fin de frame que llama `Screenshot.takeScreenshot(mainRenderTarget, img -> OverlayWriter.write(...))`, `InputInjector` (cada frame lee InputState, `InputDiff` → `KeyboardHandler.keyPress`/`MouseHandler` + deltas de ratón al jugador).

**Interfaces:**
- `OverlayWriter(MemorySegment seg)`: `void write(int w, int h, MemorySegment rgba)` — escribe en el buffer que no es `latest`, luego publica `latest` con release; filas top-down.
- `InputDiff`: `List<KeyEvent> diff(byte[] prevKeys, byte[] keys)`, `record KeyEvent(int glfwKey, boolean down)`; `double[] mouseDelta(prev, cur)`.

- [ ] **Step 1: Tests:** `OverlayWriter` alterna buffers (0→1→2→0, nunca escribe en `latest`), header w/h/frame_id; rechaza > 1920×1080; `InputDiff` detecta pulsar/soltar por bit, deltas = diferencia de acumulados.
- [ ] **Step 2:** FAIL. **Step 3:** implementar + mixins (confirmar nombres con `javap` como en la Fase 1). **Step 4:** unit tests PASS; gametest de la Fase 1 sigue PASS.
- [ ] **Step 5: Verificación manual:** Dolphin (Task 4) con SMG2 + `./gradlew runClient` ⇒ la mano y la hotbar de Minecraft se ven encima de Galaxy 2; con la ventana de Dolphin enfocada, `E` abre el inventario de Minecraft encima del juego y el ratón lo mueve. Captura de pantalla en `docs/img/phase2-overlay.png`. **Commit** `feat(fabric): overlay export and input injection`.

---

## Siguiente plan

- **Fase 3:** módulo Syati (escribe el buzón, `MR::calcGravityVector`, Mario marioneta, `MR::setCameraViewMtx`, exportar `CollisionParts`).
