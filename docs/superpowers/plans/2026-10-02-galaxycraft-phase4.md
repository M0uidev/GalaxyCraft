# GalaxyCraft Fase 4 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Caminar sobre un planeta de SMG2 (Sky Station) con física de Minecraft y la gravedad del juego, con Mario como marioneta oculta y la cámara del juego en primera persona; verificado por un test end-to-end automático y una prueba a mano.

**Architecture:** No hay componentes nuevos. Las piezas de las fases 1–3 se conectan de verdad: Dolphin + módulo (fase 3) publican la galaxia real, y el mod (fase 1) camina sobre ella. Para la prueba automática se añaden tres cosas:
- un savestate de desarrollo en Sky Station;
- un *client gametest* que se engancha al Dolphin del arnés y comprueba el resultado desde ambos lados (Minecraft y `gxdev.py ctl mbx`);
- `tools/gxe2e.sh`, que lo orquesta.

Para jugar a mano se añade un interruptor de enlace en Dolphin, para poder usar los menús de SMG2 con el Wiimote.

**Tech Stack:** Lo de las fases 1–3: Fabric (Minecraft 26.3, JDK 25), Dolphin `5390a61` + parche, Syati/CodeWarrior/Kamek, Python 3.

**Spec:** `docs/superpowers/specs/2026-10-02-galaxycraft-design.md` (§2, §4, §5, §7, §9; §10 fase 4)

## Global Constraints

- Escala inicial: **1 bloque = 100 unidades SMG** (Mario ≈ 160 u, Steve = 1.8 bloques; se ajusta en Fase 4 con una prueba) — spec §4.
- Minecraft es autoritativo en posición; Mario es marioneta oculta; la cámara de Minecraft en primera persona sobrescribe la de SMG2 — spec §2, §7.
- Si un heartbeat se detiene > 2 s, Dolphin devuelve el control a Mario y la cámara al juego; Minecraft se congela — spec §9.
- MVP: caminar con gravedad en un solo nivel. Fuera de alcance: bloques, combate, estrellas, tercera persona, multijugador — spec §2.
- Arnés: nunca `~/.config/dolphin-emu`, nunca capturar la pantalla del usuario; Dolphin headless con directorio propio (`tools/gxdev.py`). Minecraft de los tests con `-Dgalaxycraft.hidden=true`.
- Los savestates incluyen el código del módulo: tras recompilar `syati/` hay que regenerarlos (ruta en `tools/gxroute.py`).
- Disco: ~6 GB libres. Como mucho 2 savestates de desarrollo (~50 MB c/u) en `~/.local/share/galaxycraft-dev`.

## Review Focus

1. **Cinemática (demo) mientras se maneja a Mario:** p. ej. hablar con un NPC o una estrella. El juego mueve a Mario y la cámara de la demo debe verse; la marioneta no debe pelear con la demo. → Task 1, el módulo no fuerza posición ni cámara si `MR::isDemoActive()`; prueba a mano en Task 5.
2. **Minecraft se cierra o se cuelga en mitad del paseo:** Mario reaparece con control y vuelve la cámara del juego en ≤ 3 s. → Task 4, `gxe2e.sh` lo comprueba con `mbx` tras cerrar Minecraft.
3. **Escena nueva mientras se maneja (muerte, cambio de zona):** la marioneta se re-aplica al Mario nuevo; el mod re-ancla al recibir `SceneChange` (ya cubierto por tests de fase 1–2). → Task 5, paso en vivo con `drive` fuera del planeta.
4. **Enlace desactivado con el interruptor:** no se escribe `DRIVE`, el teclado va al Wiimote y el mod queda congelado, no cayendo. Al reactivar se re-ancla. → Task 3, tests del host.
5. **Partes que llegan tarde al caminar a una zona nueva:** el jugador no atraviesa el suelo. → Task 4, el test exige `onGround` en ≥ 80 % de los ticks del paseo y altitud sin caída libre.

---

## Estructura de archivos

```
syati/src/GalaxyCraft.cpp                  demo-safety; altura de Mario en la palabra de depuración
tools/gxroute.py                           rutas de menú: title → partida 2 → intro → Sky Station; savestate
dolphin/galaxycraft/HostBridge.{h,cpp}     SetLinkEnabled(bool)
dolphin/galaxycraft/DevControl.{h,cpp}     comando "link on|off"
dolphin/patches/0001-galaxycraft.patch     hotkey F10 = alternar enlace (Qt) y ctl link
fabric/src/main/java/.../gravity/GravityFrame.java   SCALE desde -Dgalaxycraft.unitsPerBlock
fabric/src/gametest/java/.../WalkOnGalaxyTest.java   E2E contra Dolphin real (-Dgalaxycraft.galaxy=true)
fabric/build.gradle                        -PgalaxycraftGalaxy → vmArgs del test
tools/gxe2e.sh                             orquesta Dolphin + savestate + gametest + comprobación de cierre
tools/gxplay.sh                            jugar a mano: Dolphin GUI + Minecraft oculto
docs/PHASE4.md
```

### Task 1: Llegar a Sky Station y módulo a prueba de cinemáticas

**Files:** Modify `syati/src/GalaxyCraft.cpp`; Create `tools/gxroute.py`.

**Interfaces:**
- Módulo: `Debug` gana `mario_height_x100` (u32: `200·|center−pos|`, con `MR::getPlayerCenterPos()`) y `demo` (u32: `MR::isDemoActive()`). Se mantiene el orden de las 8 palabras previas.
- Si `MR::isDemoActive()`: no `setPlayerPos` y no override de cámara. Ese frame `game_flags` lleva el bit 2 (`GXC_MBX_GAME_DEMO = 2`, se añade a `protocol/galaxycraft_protocol.h` y sus espejos Python y Java, con test de layout).
- `gxroute.py new-game` = título → partida 2 → empezar → cuento. `gxroute.py sky` = desde donde quede, avanzar hasta que la escena cambie a Sky Station. Lo detecta por `scene` y por la forma de la gravedad: no constante (−Y) al caminar. Termina con `save ~/.local/share/galaxycraft-dev/sky.sav`. Cada paso imprime `mbx` y deja una captura `route-N.png` para revisarla con Read.

- [ ] Constante `GXC_MBX_GAME_DEMO` en el header C, `tools/gxproto.py` y `Layout.java`; `make -C protocol test`, `python3 -m unittest discover -s tools/tests` y `fabric/test.sh` verdes.
- [ ] Módulo: demo-safety + palabras de depuración; `syati/build.sh` y `syati/test.sh` verdes.
- [ ] `gxroute.py` y llegada en vivo a Sky Station. Ahí:
  - dos `mbx` con Mario en dos puntos del planeta dan gravedades unitarias distintas (ángulo > 20°) que apuntan al planeta;
  - `parts>0`;
  - se lee la altura de Mario de la palabra de depuración (para Task 2).
- [ ] Commit `feat(syati): demo-safe puppet, Sky Station route`.

### Task 2: Escala con una prueba

**Files:** Modify `fabric/src/main/java/dev/moui/galaxycraft/gravity/GravityFrame.java`, `fabric/src/test/java/dev/moui/galaxycraft/gravity/GravityFrameTest.java`.

**Interfaces:**
- `GravityFrame.SCALE` pasa a `1.0 / unitsPerBlock()`. `unitsPerBlock()` lee `-Dgalaxycraft.unitsPerBlock` (por defecto el valor elegido aquí). Los usos existentes de `SCALE` no cambian.
- Regla de elección: `u = altura_Mario / 1.8`. Se cambia el valor por defecto sólo si `u` difiere de 100 en más de un 15 %; si no, queda 100 y la medida se registra en `docs/PHASE4.md`.

- [ ] Test (RED): con `unitsPerBlock = 89`, un punto a 178 u sobre el ancla queda 2.0 bloques sobre el jugador en Minecraft; con la propiedad ausente, el valor por defecto.
- [ ] Implementar; `fabric/test.sh` verde (incluye los tests de GravityFrame y CollisionField existentes).
- [ ] Commit `feat(fabric): units per block from a measured Mario`.

### Task 3: Interruptor de enlace

**Files:** Modify `dolphin/galaxycraft/HostBridge.{h,cpp}`, `dolphin/galaxycraft/DevControl.{h,cpp}`, tests; Dolphin `Core/GalaxyCraft.{h,cpp}`, `DolphinQt/RenderWidget.cpp`; regenerate patch.

**Interfaces:**
- `void HostBridge::SetLinkEnabled(bool)`; `bool LinkEnabled() const`. Desactivado:
  - `host_flags` del header shm = 0 (el mod se congela como si no hubiera host);
  - `DRIVE` = 0 en el buzón;
  - no se publican partes nuevas.
  Al reactivar: republicar escena (`SceneChange`) y re-anclar.
- `DevCommand::Link` con `arg` `"on"`/`"off"`; salida `ok link on|off`.
- Fachada: `GalaxyCraft::ToggleLink()`; `InputActive()` = enlace activo y mod vivo. En `RenderWidget`, F10 llama a `ToggleLink()` antes de reenviar teclas.

- [ ] Tests (RED):
  - `link_off_clears_drive_and_host_flag`: con mod vivo y pose fresca, tras `SetLinkEnabled(false)` + Tick, `DRIVE` = 0 y `host_flags` = 0;
  - `link_on_republishes_and_reanchors`: `SetLinkEnabled(true)` + Tick → `SceneChange` y `WorldState` con `ANCHOR`;
  - parse de `link on`, `link off` y `link maybe` (Bad).
- [ ] Implementar; `dolphin/galaxycraft/test.sh` verde; `ninja -C dolphin/build dolphin-emu dolphin-emu-nogui` compila; parche regenerado.
- [ ] Commit `feat(dolphin): link toggle`.

### Task 4: Test end-to-end contra el juego real

**Files:** Create `fabric/src/gametest/java/dev/moui/galaxycraft/gametest/WalkOnGalaxyTest.java`, `tools/gxe2e.sh`; Modify `fabric/build.gradle`, `WalkOnStubPlanetTest.java` y `DolphinOverlayDemo.java` (los tres tests se excluyen entre sí por propiedad).

**Interfaces:**
- `-PgalaxycraftGalaxy` → `-Dgalaxycraft.galaxy=true -Dgalaxycraft.hidden=true -Dgalaxycraft.repoRoot=<repo>`.
- El test llama a `python3 tools/gxdev.py ctl mbx` y parsea `flags=G/H` y `player=(…)`.
- `gxe2e.sh` hace, en orden:
  1. `syati/build.sh`;
  2. `gxdev.py start`, esperar la escena y `ctl "load sky.sav"`;
  3. `./gradlew runClientGameTest -PgalaxycraftGalaxy`;
  4. 3 s después de que Gradle termine, `ctl mbx` debe mostrar `flags=0/0` (o `flags=2/0`), y `peek` de la palabra de depuración `driven` (`at + 0xF68 + 0x1C`) debe dar 0;
  5. `ctl "shot e2e-after"`;
  6. `gxdev.py stop`.
  Sale ≠ 0 si cualquier paso falla.

- [ ] Test `WalkOnGalaxyTest`:
  - mundo nuevo, aventura, pacífico, `tp @a 0 100 0`;
  - esperar `galaxyPos()` (≤ 20 s) y `onGround` (≤ 10 s);
  - `mbx` → `flags=1/1` y `player` a < 50 u de `galaxyPos()`;
  - `ctl "shot e2e-standing"`;
  - caminar 20 s con `keyUp`, girando 30° cada 5 s;
  - durante el paseo, `onGround` en ≥ 80 % de los ticks y desplazamiento galáctico > 1500 u;
  - el "arriba" galáctico gira > 20° entre el inicio y el final;
  - `ctl "shot e2e-walked"`;
  - log `PASS`.
- [ ] `gxe2e.sh` completo en verde. Leer `e2e-standing.png` y `e2e-walked.png`: vista en primera persona de Sky Station y Mario invisible; `e2e-after.png`: Mario visible con la cámara del juego.
- [ ] `WalkOnStubPlanetTest` sigue verde sin propiedades (`./gradlew runClientGameTest`).
- [ ] Commit `test(e2e): walk on Sky Station from Minecraft`.

### Task 5: Jugar a mano y documentación

**Files:** Create `tools/gxplay.sh`, `docs/PHASE4.md`.

**Interfaces:**
- `gxplay.sh` hace tres cosas:
  1. arranca `dolphin-emu` (GUI) con `GALAXYCRAFT=1 -e syati/build/galaxycraft.json`, usando el directorio de usuario normal de Dolphin, para que valgan los mandos que ya tengas configurados;
  2. arranca `./gradlew runClient -Dgalaxycraft.hidden=true`, que entra en un mundo de prueba;
  3. al terminar uno de los dos, cierra el otro.
- `PHASE4.md` explica:
  - cómo jugar: F10 alterna enlace, menús con el Wiimote y enlace activo dentro del nivel;
  - la escala medida y la decisión;
  - la lista de comprobación a mano (Review Focus 1 y 3);
  - las limitaciones conocidas.

- [ ] Verificación a mano en el arnés (headless, con pipe y `drive` desactivado):
  - durante una demo, `mbx` muestra `flags` con el bit 2 y la captura muestra la cámara de la demo;
  - escena nueva mientras se maneja: `drive` a una pose fuera del planeta (en el vacío) hace que Mario caiga y muera; `scene` sube, y tras reaparecer, `mbx` vuelve a `flags=1/1` con Mario oculto y la cámara en la pose (la marioneta se re-aplica al Mario nuevo); `undrive` lo devuelve.
- [ ] `docs/PHASE4.md`.
- [ ] Commit `docs: phase 4 integration and play guide`.
