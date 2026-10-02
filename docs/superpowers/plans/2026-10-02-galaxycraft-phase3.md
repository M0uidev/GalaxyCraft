# GalaxyCraft Fase 3 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Un módulo Syati que corre dentro de SMG2 y (a) publica el buzón `GXCRMBX1` con gravedad, posición de Mario y las partes de colisión cercanas, (b) convierte a Mario en marioneta oculta cuando el host pone `DRIVE`, y (c) sobrescribe la cámara con la vista de Minecraft; más las herramientas para verificarlo en vivo sin tocar el escritorio del usuario.

**Architecture:** El módulo se compila con CodeWarrior 4.3 build 172 (wine) y se enlaza con Kamek como binario dinámico `CustomCode_SB4E.bin`, que carga el loader de Syati. Ambos llegan al juego por Riivolution usando un *game mod descriptor* `.json` de Dolphin (`dolphin-emu -e galaxycraft.json`), sin tocar el disco. La lógica pura del módulo (selección de partes, matriz de vista, tamaño del KCL) vive en `syati/src/core/`, C++ portable que también compila con g++ para tests en el host; el pegamento con el juego (`syati/src/GalaxyCraft.cpp`) se verifica en vivo. Para eso el fork de Dolphin gana un canal de control de desarrollo (`/dev/shm/galaxycraft_ctl`: `peek`, `mbx`, `shot`, `save`, `load`) y un arnés (`tools/gxdev.py`) que arranca `dolphin-emu-nogui -p headless` con un directorio de usuario propio y el Wiimote mapeado a un FIFO de Pipes.

**Tech Stack:** CodeWarrior PPC EABI 4.3.0.172 (`Wii/1.3` de decomp.dev) bajo wine 11, Kamek 2.0 (.NET 10), Syati (`main`, 2026-10-02), Dolphin `5390a61` + parche, Python 3, g++ 15.

**Spec:** `docs/superpowers/specs/2026-10-02-galaxycraft-design.md` (§3, §6, §7, §9; §10 fase 3)

## Global Constraints

- Región **SB4E**. Símbolos de `Syati/symbols/SB4E.txt`; direcciones fijas sólo de ese archivo.
- Toolchain fuera del repo en `~/.local/opt/gxc-toolchain/` (Syati con `deps/CodeWarrior` y `deps/Kamek`); `syati/build.sh` falla con mensaje claro si falta.
- Flags de compilación = los del loader de Syati: `-c -Cpp_exceptions off -nodefaults -proc gekko -fp hard -lang=c++ -O4,s -inline on -rtti off -sdata 0 -sdata2 0 -align powerpc -func_align 4 -str pool -enum int -DGEKKO`.
- Buzón: layout exacto de `protocol/galaxycraft_protocol.h` (big-endian nativo en PPC). `game_seq` se escribe **al final** de cada actualización.
- KCL en RAM: `KCollisionServer::setData` convierte los 4 offsets del header en punteros absolutos; el host los normaliza (`v >= 0x80000000 → v - kcl_addr`). `kcl_size` = offset del octree (el parser no lee más allá).
- Matriz de parte = `CollisionParts::mBaseMatrix` (+0x34, incluye escala). Radio = `+0xD8`. Keeper de categoría 0 (Map). Zonas: punteros en keeper+0x18, cuenta en +0x98. Partes: punteros en zona+0x4, cuenta en +0x804 (Petari, coincide con Syati).
- Selección: partes cuya distancia a la superficie de su esfera (`|p − t| − r`) sea < 3000 u, las 64 más cercanas.
- El arnés nunca usa `~/.config/dolphin-emu` ni captura la pantalla del usuario: directorio propio `~/.local/share/galaxycraft-dev`, plataforma `headless`, capturas por `Core::SaveScreenShot`.
- Disco: ~6 GB libres. Savestates de desarrollo dentro del directorio dev; borrar los que sobren.

## Review Focus

1. **Comando de control mal formado o dirección inválida** (`peek 0x0 99999999`): se responde con error, sin leer fuera de rango ni crashear. → test en Task 1.
2. **KCL cuyo header ya son offsets** (no inicializado) o con punteros fuera de rango: la normalización no lo rompe; si no cuadra, la parte se ignora. → test en Task 3.
3. **Más de 64 partes o ninguna a distancia**: se eligen las 64 más cercanas; con ninguna, `part_count = 0`. → test en Task 3.
4. **`look` paralelo a `up` o vectores nulos** en la matriz de vista: no produce NaN (se usa un eje alternativo). → test en Task 3.
5. **El host deja de mandar `DRIVE`** (Minecraft se cierra): Mario recupera control y se vuelve visible. → verificación en vivo en Task 4.

---

## Estructura de archivos

```
dolphin/galaxycraft/DevControl.{h,cpp}   parser + ejecución de comandos de desarrollo (peek, mbx)
dolphin/galaxycraft/HostBridge.{h,cpp}   + MailboxAddress(); normalización de punteros KCL
dolphin/galaxycraft/tests/               + tests de DevControl y de normalización
dolphin/patches/0001-galaxycraft.patch   + ctl en la fachada (shot/save/load vía QueueHostJob)
syati/build.sh                           compila módulo + loader, genera riivolution XML y .json
syati/riivolution/galaxycraft.xml.in     plantilla Riivolution (loader + carpeta CustomCode)
syati/src/core/gxc_types.h               tipos de ancho fijo para CW y g++
syati/src/core/Parts.{h,cpp}             selección de partes cercanas
syati/src/core/ViewMath.{h,cpp}          matriz de vista look-at (convención GX)
syati/src/core/Kcl.{h,cpp}               tamaño del KCL desde el header en RAM
syati/src/GalaxyCraft.cpp                pegamento con SMG2 (hooks, buzón, marioneta, cámara)
syati/tests/test_core.cpp + test.sh      tests g++ de core/
tools/gxdev.py                           arnés: user dir, FIFO, arrancar/parar, ctl, pipe
tools/dolphin-dev/                       Dolphin.ini, GFX.ini, WiimoteNew.ini del arnés
docs/PHASE3.md                           cómo compilar, arrancar y verificar
```

### Task 1: Canal de control de desarrollo

**Files:** Create `dolphin/galaxycraft/DevControl.{h,cpp}`, `dolphin/galaxycraft/tests/test_devcontrol.cpp`; Modify `dolphin/galaxycraft/HostBridge.h` (+`std::optional<u32> MailboxAddress() const`), `CMakeLists.txt`, test runner; Dolphin `Core/GalaxyCraft.cpp`; regenerate patch.

**Interfaces:**
- Produces: `struct DevCommand { enum Kind { Peek, Mbx, Shot, Save, Load, Bad } kind; u32 addr, len; std::string arg; };`
  `std::vector<DevCommand> ParseDevCommands(std::string_view text);`
  `std::string RunMemoryCommand(const DevCommand&, GuestMemory&, std::optional<u32> mailbox);` (Peek/Mbx → texto; Bad → `"error: ..."`).
- Facade: cada field, si existe `/dev/shm/galaxycraft_ctl`, lo lee y lo borra; Peek/Mbx se ejecutan ya y su salida se agrega a `/dev/shm/galaxycraft_ctl.out`; Shot/Save/Load se encolan con `Core::QueueHostJob` (`Core::SaveScreenShot(arg)`, `State::SaveAs/LoadAs(system, arg)`) y escriben `ok shot <arg>` etc.

- [ ] Tests (RED): parse `"peek 0x80001800 16\nmbx\nshot a\nsave /x\nload /x\nfoo\n"` → 6 comandos con kinds y valores; `peek` sin len → Bad; len > 4096 → Bad; `RunMemoryCommand(peek)` sobre FakeGuestMemory devuelve `"80001800: 00 01 ..."`; dirección fuera de rango → `"error: unreadable 0x..."`; `mbx` sin buzón → `"error: no mailbox"`; con buzón escrito por el test → línea con `game_seq=`, `scene=`, `grav=`, `anchor=`, `flags=`, `parts=`.
- [ ] Implementar; `dolphin/galaxycraft/test.sh` verde.
- [ ] Fachada + parche; `ninja -C dolphin/build dolphin-emu dolphin-emu-nogui` compila.
- [ ] Commit `feat(dolphin): dev control channel`.

### Task 2: Arnés headless + toolchain + módulo esqueleto

**Files:** Create `tools/gxdev.py`, `tools/dolphin-dev/{Dolphin.ini,GFX.ini,WiimoteNew.ini}`, `syati/build.sh`, `syati/riivolution/galaxycraft.xml.in`, `syati/src/core/gxc_types.h`, `syati/src/GalaxyCraft.cpp` (esqueleto); `.gitignore` (+`syati/build/`).

**Interfaces:**
- `syati/build.sh` → `syati/build/CustomCode/CustomCode_SB4E.bin`, `syati/build/galaxycraft.xml`, `syati/build/galaxycraft.json` (base-file = `$GXC_GAME` o el `.rvz` de `~/Documents/Games/Dolphin Games`).
- `gxdev.py start [--gui]`, `stop`, `ctl "<cmds>" [--wait S]`, `pad "<pipe cmds>"`, `press BTN [secs]`; el user dir se crea copiando `tools/dolphin-dev/` y haciendo `mkfifo Pipes/gxpad`.
- Esqueleto: `GxcMailbox gMailbox` con magic/version; hook de `MarioActor::movement` por vtable (`0x806C7448 + 0x14`) que llama al original e incrementa `game_seq`.

- [ ] `syati/build.sh` compila y enlaza; el `.bin` empieza por `Kamek\0`.
- [ ] Live: `gxdev.py start`, esperar a que el juego cargue, `ctl mbx` → aparece el buzón; dos `mbx` separados muestran `game_seq` creciendo cuando Mario existe (si no crece en el menú es esperado; se comprueba en Task 4). `ctl "shot boot"` crea un PNG en el user dir; leerlo con Read para ver el estado.
- [ ] Commit `feat(syati): toolchain, dev harness and module skeleton`.

### Task 3: Lógica pura del módulo + normalización KCL en el host

**Files:** Create `syati/src/core/{Parts,ViewMath,Kcl}.{h,cpp}`, `syati/tests/test_core.cpp`, `syati/test.sh`; Modify `dolphin/galaxycraft/HostBridge.cpp` + test.

**Interfaces:**
- `struct PartCandidate { u32 id, kcl, size; float mtx[12]; float radius; };`
  `int SelectParts(const PartCandidate* in, int n, const float pos[3], float max_dist, int* out_idx, int max_out);` (orden por distancia a superficie ascendente).
- `void LookAtView(const float eye[3], const float look[3], const float up[3], float out[12]);` (filas: derecha, arriba', `z = −look`; traslación `−R·eye`; como `C_MTXLookAt(eye, up, eye+look)`).
- `u32 KclSizeFromHeader(const u32 header[4], u32 base);` → `octree − base` si son punteros (`>= base`), `octree` si son offsets; 0 si incoherente.
- Host: en `SendPart`, tras leer, por cada una de las 4 palabras BE del header: si `v >= kcl_addr` → `v -= kcl_addr`; si el resultado > `kcl_size` → descartar la parte.

- [ ] Tests (RED) g++: 70 candidatos → 64 más cercanos ordenados; radio grande gana a centro cercano; ninguno dentro de `max_dist` → 0; vista con `eye=(0,0,0) look=(0,0,-1) up=(0,1,0)` = identidad; punto `eye+look` → `(0,0,-1)` en vista; `look ∥ up` sin NaN; KCL con punteros y con offsets. Host: KCL con header en punteros llega al ring con offsets; puntero fuera de rango → parte ignorada.
- [ ] Implementar; `syati/test.sh` y `dolphin/galaxycraft/test.sh` verdes; `syati/build.sh` compila `core/` con CW.
- [ ] Commit `feat(syati): core math and host KCL pointer normalization`.

### Task 4: Pegamento con el juego — buzón completo, gravedad, partes, marioneta, cámara

**Files:** Modify `syati/src/GalaxyCraft.cpp`; Create `docs/PHASE3.md`.

**Interfaces (SB4E):** `MR::calcGravityVector(const NameObj*, const TVec3f&, TVec3f*, GravityInfo*, u32)`, `MR::getPlayerPos()`, `MR::setPlayerPos(const TVec3f&)`, `MR::offPlayerControl()`, `MR::onPlayerControl(bool)`, `MR::hidePlayer()`, `MR::showPlayer()`, `MR::getCollisionDirector()`, `MR::setCameraViewMtx(const TPos3f&, bool, bool, const TVec3f&)`, `MR::setFovy(f32)`; vtables `__vt__10MarioActor=0x806C7448` (init +0xC, movement +0x14) y `__vt__14CameraDirector=0x8067C4D0` (movement +0x14).

- [ ] `MarioActor::init` → `scene_id++`. `MarioActor::movement` → original; si `DRIVE`: flanco de subida `offPlayerControl+hidePlayer`, cada frame `setPlayerPos(player_pos)`; flanco de bajada `onPlayerControl(true)+showPlayer`. Gravedad en `player_pos` (driven) o en Mario; `anchor_pos` = Mario; partes con `SelectParts`; `game_seq++` al final.
- [ ] `CameraDirector::movement` → original; si `DRIVE`: `eye = player_pos + up·eye_height`, `LookAtView`, `setCameraViewMtx`, `setFovy(fov_y)`.
- [ ] Live (headless + Pipes): llegar a la primera galaxia jugable, `save` un savestate de desarrollo. `mbx` muestra gravedad unitaria apuntando al planeta, `parts>0`, `anchor` = posición de Mario. Prueba de marioneta con `fake drive`: escribir `DRIVE` + pose a mano con un comando de desarrollo `drive x y z` (añadido aquí a DevControl con test) y comprobar por captura que la cámara mira desde esa pose y Mario no se ve; al quitar `DRIVE` Mario reaparece.
- [ ] `docs/PHASE3.md`; commit `feat(syati): mailbox, gravity, parts, puppet and camera`.
