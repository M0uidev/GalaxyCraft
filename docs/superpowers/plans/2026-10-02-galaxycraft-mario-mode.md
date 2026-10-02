# GalaxyCraft Modo Mario Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Jugar SMG2 entero desde la vista de Minecraft. El teclado y el ratón se traducen al Wiimote emulado y Mario se mueve con su propia física. La cámara del juego va en sus ojos, mirando adonde apunta el ratón. El jugador de Minecraft sigue a Mario.

**Architecture:**
- **Protocolo v2:** añade el indicador `FOLLOW` en el buzón y en `WorldState`.
- **Host (Dolphin):** traduce teclado y ratón (XInput2, o `ctl keys` en el arnés) a un estado de Wiimote + Nunchuk. Lo inyecta con `ControllerEmu::SetInputOverrideFunction`; agitar se cubre añadiendo override a `EmulateShake`. Decide si se está en juego o en menú.
- **Módulo:** pone la cámara en los ojos de Mario sin hacerlo marioneta, y oculta el puntero.
- **Mod:** coloca al jugador donde está Mario.
- **Se retira el modo marioneta** (`DRIVE`, `drive`).

**Tech Stack:** las mismas herramientas de las fases 3–4: Dolphin `5390a61` + parche, Syati/CodeWarrior/Kamek, Fabric (Minecraft 26.3, JDK 25), Python 3.

**Spec:** `docs/superpowers/specs/2026-10-02-galaxycraft-mario-mode-design.md`

## Global Constraints

- **Autoridad del movimiento: SMG2.** Mario se mueve con su física y Minecraft lo sigue. No se usan `MR::offPlayerControl` ni `MR::setPlayerPos`.
- **Mapeo (spec §3):**
  - WASD → stick, con diagonales normalizadas a 1;
  - Espacio → A; Shift → Z; Ctrl → C; Esc → +; Tab → −;
  - clic derecho → B;
  - clic izquierdo → agitar (mantenido, mínimo 6 frames) y, en menús, también A.
- **Juego** = `game_seq` avanzó en los últimos 30 frames, la gravedad es no nula y no hay `GXC_MBX_GAME_DEMO`. Si no, es menú.
- **Puntero:** en juego, IR (0,0), sin dibujo y con cursor recentrado. En menú, IR sin override (el mapeo del usuario), visible y sin recentrar.
- **Constantes:** `GXC_MBX_FOLLOW = 2` (host_flags), `GXC_WORLD_FOLLOW = 2` (WorldState.flags), `GXC_VERSION = 2`.
- **Cámara:** posición = pies de Mario + `up · eye_height`; plano cercano 10 u; en cinemática, cámara del juego y Mario visible.
- **Fallos:** mod sin latido > 2 s, o Ctrl+G → modo Wiimote: sin override, sin `FOLLOW`, Esc vuelve a ser `HK_STOP`.
- **Arnés:** headless, directorio propio, sin capturar la pantalla del usuario. Tras cambiar `syati/` hay que regenerar `sky.sav` (`gxroute.py`).
- Disco ~6 GB libres.

## Review Focus

1. **Cambio de modo con una tecla pulsada** (W, Espacio, clic): no queda nada pegado ni en el Wiimote ni en Minecraft. → Task 5, test `mode_switch_releases_everything`.
2. **Pantallas sin Mario o sin gravedad** (título, selección de partida, mapa del mundo): el ratón queda libre como puntero y no se recentra. → Task 5, test `menu_when_no_mario_or_no_gravity`; mapa del mundo verificado a mano en Task 9.
3. **Cinemática a mitad de un salto:** Mario se ve y la cámara es la del juego; al terminar vuelve la primera persona. → Task 3, paso en vivo.
4. **Minecraft se cierra con el override puesto:** el Wiimote vuelve al mapeo del usuario en ≤ 3 s y Esc vuelve a detener. → Task 6, test `stale_mod_drops_override`; Task 8 en el E2E.
5. **Escena nueva (tubo, muerte):** el mod re-ancla en el Mario nuevo y la cámara sigue en primera persona. → Task 8, paso en vivo.

---

## Estructura de archivos

```
protocol/galaxycraft_protocol.h + test_layout.c          FOLLOW, versión 2
tools/gxproto.py, tools/tests/                          espejo Python
fabric/.../proto/Layout.java, ProtoTest                  espejo Java
dolphin/galaxycraft/HostBridge.{h,cpp} + tests           modo Minecraft/Wiimote, FOLLOW, juego/menú
dolphin/galaxycraft/MarioInput.{h,cpp} + tests (nuevo)   teclas/ratón → estado Wiimote (puro)
dolphin/galaxycraft/DevControl.{h,cpp} + tests           follow, keys; fuera drive/undrive
dolphin/src/.../WiimoteEmu/{Dynamics,WiimoteEmu}.cpp,
  Extension/Nunchuk.cpp                                  override de agitar
dolphin/src/.../Core/GalaxyCraft.{h,cpp}                 override instalado, Esc, XInput2
dolphin/src/.../DolphinQt/HotkeyScheduler.cpp            HK_STOP ignorado en modo Minecraft
dolphin/src/.../Xlib/XInput2.cpp                         captura sólo en juego
syati/src/GalaxyCraft.cpp                                FOLLOW, puntero oculto
fabric/.../bridge/BridgeClient.java, client/GalaxyCraftClient.java   seguir a Mario
fabric/.../gametest/WalkAsMarioTest.java (nuevo)         E2E; se borra WalkOnGalaxyTest
tools/gxe2e.sh, tools/gxplay.sh, docs/PHASE5.md
```

### Task 1: Protocolo v2

**Files:**
- Modify: `protocol/galaxycraft_protocol.h`, `protocol/test_layout.c`, `tools/gxproto.py`, `fabric/src/main/java/dev/moui/galaxycraft/proto/Layout.java`.
- Tests: `tools/tests/`, `ProtoTest`.

**Interfaces — Produces:**
- `GXC_VERSION 2u`, `GXC_MBX_FOLLOW 2u`, `GXC_WORLD_FOLLOW 2u`.
- Python: `WORLD_FOLLOW = 2`, `VERSION = 2`.
- Java: `Layout.WORLD_FOLLOW = 2`, `Layout.VERSION = 2`.
- `Seqlock.WorldState.follow()` → `(flags & WORLD_FOLLOW) != 0`.

- [ ] **Tests (RED):**
  - `_Static_assert(GXC_VERSION == 2u && GXC_MBX_FOLLOW == 2u && GXC_WORLD_FOLLOW == 2u, "v2")`;
  - Python: `assert gxproto.VERSION == 2 and gxproto.WORLD_FOLLOW == 2`;
  - Java: `assertTrue(new WorldState(1, 1, g, q, Layout.WORLD_FOLLOW).follow())` and `assertEquals(2, Layout.VERSION)`.
- [ ] Implementar. Verdes: `make -C protocol test`, `python3 -m unittest discover -s tools/tests`, `fabric/test.sh`, `dolphin/galaxycraft/test.sh` (usa `GXC_VERSION` del header).
- [ ] Commit `feat(protocol): v2 with FOLLOW`.

### Task 2: Host — modos Minecraft/Wiimote y FOLLOW

**Files:** Modify `dolphin/galaxycraft/HostBridge.{h,cpp}`, `DevControl.{h,cpp}`, `tests/host_bridge_test.cpp`, `tests/devcontrol_test.cpp`; fachada `Core/GalaxyCraft.cpp` (sólo renombres).

**Interfaces — Produces:**
- `void HostBridge::SetMinecraftMode(bool)` y `bool MinecraftMode() const`. Sustituyen a `SetLinkEnabled`/`LinkEnabled`; `ctl link on|off` se mantiene como alias.
- `bool HostBridge::Following() const`: modo Minecraft y mod vivo.
- `bool HostBridge::InGame() const`: regla de juego de las Global Constraints, evaluada en el último Tick.
- `void HostBridge::SetDevFollow(std::optional<PlayerState>)`: mirada, `up` y FOV de prueba, como si los mandara el mod.
- DevControl: `follow LX LY LZ [UX UY UZ]` (`DevCommand::Follow`) y `unfollow`; se eliminan `Drive`/`Undrive` y `SetDevDrive`.
- Mientras sigue, cada Tick:
  - buzón: `host_flags = GXC_MBX_FOLLOW`, más `look`/`up`/`fov_y`/`eye_height` del mod (`player_pos` = eco);
  - `WorldState`: `query_pos` = ancla (Mario), `flags = GXC_WORLD_FOLLOW`, con `GXC_WORLD_ANCHOR` sólo hasta la primera pose fresca tras un cambio de escena.
  - Nunca se escribe `GXC_MBX_DRIVE`.

- [ ] **Tests (RED)** en `host_bridge_test.cpp`:
  - `follow_writes_flags_and_mario_position`: con mod vivo y pose fresca → buzón `host_flags == 2` y `look` copiado; `WorldState.query_pos == anchor` y `flags & GXC_WORLD_FOLLOW`.
  - `wiimote_mode_clears_follow`: `SetMinecraftMode(false)` → `host_flags == 0` y header shm `host_flags == 0`.
  - `stale_mod_stops_following`: latido del mod a +2,5 s → `!Following()` y `host_flags == 0`.
  - `in_game_rule`, con estos casos:

    | Caso | `InGame()` |
    |---|---|
    | `game_seq` sin avanzar 31 Ticks | false |
    | gravedad (0,0,0) | false |
    | `game_flags` con DEMO | false |
    | `game_seq` avanzando, gravedad (0,−1,0), sin DEMO | true |

  - `dev_follow_overrides_mod`: análogo al test actual de `drive`.
- [ ] **Tests (RED)** en `devcontrol_test.cpp`: `follow 1 0 0`, `follow 1 0 0 0 1 0`, `unfollow`, `follow 1 0` → Bad, `drive 1 2 3 0 0 1` → Bad.
- [ ] Implementar. Ajustar los tests de `drive`/link existentes a los nombres nuevos. `dolphin/galaxycraft/test.sh` verde; parche regenerado; `ninja -C dolphin/build dolphin-emu dolphin-emu-nogui` compila.
- [ ] Commit `feat(dolphin): follow mode replaces the puppet`.

### Task 3: Módulo — cámara en los ojos de Mario sin marioneta (incluye prueba de viabilidad del stick)

**Files:** Modify `syati/src/GalaxyCraft.cpp`.

**Interfaces:**
- **Consumes:** `GXC_MBX_FOLLOW` (Task 1); `ctl follow` (Task 2).
- **Produces:**
  - `Debug` gana `star_pointer_valid` (u32, `MR::isStarPointerValid(0)`), añadido al final.
  - Con `host_flags & GXC_MBX_FOLLOW` (y watchdog de `host_seq`):
    - flanco de subida → `MR::hidePlayer()`;
    - flanco de bajada → `MR::showPlayer()`;
    - en cinemática → `showPlayer`, y al terminar `hidePlayer`;
    - cámara: ojo = `anchor_pos + up · eye_height`, con `LookAtView`, FOV, y near 10 u, salvo en cinemática.
  - Se elimina `setPlayerPos`/`offPlayerControl`/`onPlayerControl` y `gDriven` pasa a `gFollowing`.
  - `game_flags`: bit 0 = siguiendo, bit 1 = DEMO (igual que antes).

- [ ] Implementar; `syati/build.sh` y `syati/test.sh` verdes.
- [ ] **En vivo** (arnés, nueva partida → intro, o `gxroute.py`):
  1. `ctl "follow 1 0 0"`; `gxdev.py stick 0 1 1` (pipe: stick arriba, 1 s). Mario se desplaza en +x: más del 70 % del desplazamiento horizontal (de `mbx anchor`) va en x.
  2. Repetir con `follow 0 0 -1`: el desplazamiento va en −z.
  3. Captura: primera persona, Mario no visible.
  4. `unfollow` → Mario visible.
  5. Si (1) o (2) fallan, PARAR y anotar en el ledger. El stick no es relativo a nuestra cámara; aplicar la mitigación de spec §9 (rotar el stick en el host) antes de seguir.
- [ ] **En vivo, Review Focus 3:** con `follow` puesto, provocar una cinemática (reaparición tras morir en la intro, o hablar con un Toad). `mbx flags=3/2`, captura con Mario visible y cámara del juego; al terminar, primera persona.
- [ ] Regenerar `sky.sav` (`gxroute.py new-game && gxroute.py sky`). Commit `feat(syati): follow camera without puppet`.

### Task 4: Módulo — ocultar el puntero de estrella (prueba de viabilidad)

**Files:** Modify `syati/src/GalaxyCraft.cpp` (+ `syati/symbols_extra.txt` si hace falta un símbolo).

**Interfaces — Produces:** con `gFollowing && !gDemo`, cada frame se oculta el dibujo del puntero; `star_pointer_valid` sigue a 1.

- [ ] **Investigar** con `peek` y desensamblado (como en la fase 3) cómo llegar a la instancia de `StarPointerLayout` y `StarPointerBlur`:
  - `hideAll__17StarPointerLayoutFv` = `0x8049DAE0`;
  - `hideAll__15StarPointerBlurFv` = `0x80499270`.

  Anotar en el ledger el camino de punteros encontrado.
- [ ] Implementar la llamada por frame.
- [ ] **En vivo** en `sky.sav` con `follow`:
  - captura sin estrella-puntero;
  - `star_pointer_valid == 1`;
  - recoger un trozo de estrella apuntando: `ctl keys` llega en Task 6. Aquí basta con que la palabra siga a 1, y la recogida se verifica en Task 9.
- [ ] Si ocultarlo desactiva el puntero (`valid == 0`), probar las alternativas de spec §4 y elegir con un ruling.
- [ ] Regenerar `sky.sav`. Commit `feat(syati): hide the star pointer in Minecraft mode`.

### Task 5: Host — traducción teclas/ratón → Wiimote (puro)

**Files:** Create `dolphin/galaxycraft/MarioInput.{h,cpp}`, `dolphin/galaxycraft/tests/mario_input_test.cpp`; Modify `CMakeLists.txt`, `DevControl.{h,cpp}` + test.

**Interfaces — Produces:**
```cpp
namespace gxc {
struct WiimoteState {            // lo que el override devuelve
  bool a, b, minus, plus, home, one, two, c, z;
  float stick_x, stick_y;        // -1..1, y arriba
  bool shake;
  bool center_ir;                // true: IR (0,0); false: sin override (puntero del usuario)
};
class MarioInput {
public:
  // keys: bitmap de scancodes SDL (64 bytes, como InputState); buttons: máscara SDL (bit1 izq,
  // bit2 medio, bit3 der); in_game: regla de juego/menú. Llamar una vez por frame de Wiimote.
  WiimoteState Update(const u8 keys[64], u32 buttons, bool in_game);
  void Reset();                  // cambio de modo: todo suelto, contadores a cero
};
}
```
- Agitar: mientras el clic izquierdo esté pulsado y como mínimo 6 llamadas desde la pulsación.
- Clic izquierdo = A sólo cuando `!in_game`.
- DevControl: `keys <lista>` (`DevCommand::Keys`, `arg` = lista separada por espacios de `w a s d space shift ctrl esc tab lmb rmb`), que mantiene ese conjunto hasta el siguiente `keys`; `keys` sin lista = soltar todo.

- [ ] **Tests (RED):**
  - `wasd_stick_and_diagonals`: W → (0,1); W+D → (0.7071, 0.7071) ±1e-4; A+D → (0,0).
  - `buttons_map`: Espacio → `a`, Shift → `z`, Ctrl → `c`, Esc → `plus`, Tab → `minus`, clic derecho → `b`.
  - `shake_minimum_six_frames`: pulsar y soltar en la 2.ª llamada → `shake` verdadero en las llamadas 1–6 y falso en la 7.
  - `left_click_is_a_only_in_menus`.
  - `center_ir_only_in_game`.
  - `mode_switch_releases_everything`: con teclas pulsadas, `Reset()` y luego `Update` con bitmap vacío → todo falso y stick (0,0).
  - `menu_when_no_mario_or_no_gravity`: queda en HostBridge (Task 2) y aquí sólo se comprueba que `in_game=false` no centra el IR.
  - DevControl: parseo de `keys w space lmb`, `keys`, `keys w jump` (Bad).
- [ ] Implementar; `dolphin/galaxycraft/test.sh` verde.
- [ ] Commit `feat(dolphin): keyboard and mouse to Wii Remote translation`.

### Task 6: Dolphin — instalar el override, agitar, Esc, captura XInput2

**Files:**
- Modify (Dolphin, vía parche): `Core/HW/WiimoteEmu/Dynamics.{h,cpp}`, `Core/HW/WiimoteEmu/WiimoteEmu.cpp`, `Core/HW/WiimoteEmu/Extension/Nunchuk.cpp`, `Core/GalaxyCraft.{h,cpp}`, `DolphinQt/HotkeyScheduler.cpp`, `InputCommon/ControllerInterface/Xlib/XInput2.cpp`.
- Regenerate `dolphin/patches/0001-galaxycraft.patch`.

**Interfaces:**
- **Consumes:** `MarioInput`, `WiimoteState` (Task 5); `HostBridge::Following()`, `InGame()`, `SetMinecraftMode()` (Task 2).
- **Produces:**
  - **`EmulateShake` gana override:** `EmulateShake(PositionalState*, ControllerEmu::Shake*, const ControllerEmu::InputOverrideFunction&, float)`. Si el override devuelve un valor para `("Shake", "X"|"Y"|"Z")`, se usa en lugar del estado del grupo. Llamadas en `WiimoteEmu.cpp` y `Nunchuk.cpp`.
  - **Override de la fachada:** con `Following()`, la fachada instala en el Wiimote 1 y en su Nunchuk (vía el grupo `Attachments`) una `InputOverrideFunction` que devuelve, del último `WiimoteState`:

    | Grupo | Controles | Valor |
    |---|---|---|
    | `Buttons` (Wiimote) | `A`, `B`, `-`, `+`, `Home`, `1`, `2` | 0/1 |
    | `Buttons` (Nunchuk) | `C`, `Z` | 0/1 |
    | `Stick` | `X`, `Y` | eje |
    | `IR` | `X`, `Y` | 0 si `center_ir`; si no, `nullopt` |
    | `Shake` | `X`, `Y`, `Z` | 1 si `shake`, si no 0 |

    Cualquier otro control → `nullopt`. Sin `Following()` se limpia con `ClearInputOverrideFunction`.
  - **`MarioInput::Update`:** se llama una vez por field del CPU, en el mismo hook que `HostBridge::Tick`. Recibe las teclas y botones de la última llamada a `OnXInput2` o de `ctl keys`.
  - **`OnXInput2`:**
    - devuelve `true` (capturar y recentrar) sólo si `Following() && InGame()`;
    - en menú no reenvía movimiento de ratón a Minecraft;
    - las teclas siguen yendo a Minecraft siempre que siga.
  - **`HotkeyScheduler`:** `if (IsHotkey(HK_STOP) && !GalaxyCraft::BlocksStopHotkey())`. `BlocksStopHotkey()` = `Following()`.
  - **`ToggleLink()`** pasa a alternar `SetMinecraftMode` y llama a `MarioInput::Reset()` e `InputWriter::ReleaseAll()`.

- [ ] Implementar. Compila (`ninja -C dolphin/build dolphin-emu dolphin-emu-nogui`); `dolphin/galaxycraft/test.sh` verde; parche regenerado.
- [ ] **En vivo (arnés, `sky.sav`):** con `ctl "follow 1 0 0"` y `ctl "keys w"` 1 s → Mario avanza (la ruta del override, no el pipe). `keys space` → `anchor` sube y vuelve. `keys lmb` → giro: captura o un salto de `game_seq` sin errores; aceptable si el giro se ve en la captura.
- [ ] **En vivo, Review Focus 4:** sin mod (sin Minecraft) y con `follow` dev retirado → override limpio. `pad "PRESS A"` del pipe vuelve a mover a Mario.
- [ ] Commit `feat(dolphin): Wii Remote override for Mario mode`.

### Task 7: Mod — seguir a Mario

**Files:** Modify `fabric/src/main/java/dev/moui/galaxycraft/bridge/BridgeClient.java` (nada si `follow()` basta), `fabric/src/client/java/dev/moui/galaxycraft/client/GalaxyCraftClient.java`; Create pure helper `fabric/src/main/java/dev/moui/galaxycraft/gravity/Follow.java` + `FollowTest.java`.

**Interfaces — Produces:** `final class Follow { static Vector3d target(GravityFrame f, Vector3d marioGal); }` → `f.toMc(marioGal)`.

En el cliente, con `world.follow()`:
- crear o actualizar el marco como hoy;
- `player.setPos(target)` y `setOldPosAndRot()`;
- `hold(player, true)`: sin gravedad, velocidad 0 y caída 0;
- sin espera de suelo;
- re-base como hoy;
- `afterTick` envía la pose como hoy.

Sin `follow()` (modo Wiimote o host viejo) → congelado, como hoy.

- [ ] **Tests (RED):**
  - `FollowTest.targetIsMarioInMinecraftSpace`: marco `GravityFrame(v(0,820,0), v(0,100,0), v(0,-1,0))`; Mario en (0, 820+160, 0) → (0, 100+160·SCALE, 0) ±1e-9.
  - `ProtoTest.followFlag` (Task 1).
- [ ] Implementar; `fabric/test.sh` verde.
- [ ] Commit `feat(fabric): the player follows Mario`.

### Task 8: E2E en Sky Station

**Files:**
- Create `fabric/src/gametest/java/dev/moui/galaxycraft/gametest/WalkAsMarioTest.java`.
- Delete `WalkOnGalaxyTest.java`.
- Modify `fabric.mod.json` (gametest), `tools/gxe2e.sh`.

**Interfaces — Consumes:** `ctl keys`, `ctl follow`/`mbx`/`peek` (Tasks 2, 5); `GalaxyCraftClient.galaxyPos()` / `galaxyUp()`.

**El test:**
- mundo como hoy (aventura, pacífico, `fall_damage false`);
- esperar `galaxyPos()`;
- `mbx` → `flags=1/2` (siguiendo / FOLLOW);
- jugador a < 30 u de `anchor`;
- girar la mirada a yaw 0, `gxdev ctl "keys w"` 1,5 s y `keys`: `anchor` se desplaza > 300 u, y el jugador sigue a < 30 u;
- girar 90° y repetir: la dirección del segundo desplazamiento forma > 45° con la del primero;
- `keys space` 0,3 s: el `anchor` sube y vuelve en 2 s;
- palabra de depuración: Mario oculto, `star_pointer_valid == 1`;
- capturas `e2e-mario-*`;
- `PASS`.

**`gxe2e.sh`** comprueba, tras cerrar Minecraft:
- `flags` sin bit FOLLOW;
- palabra `driven/following` a 0;
- que el override se limpió: `ctl status` muestra `following=false`.

- [ ] Escribir el test y adaptar `gxe2e.sh`.
- [ ] `tools/gxe2e.sh` en verde. Leer las capturas: primera persona en Sky Station, sin puntero de estrella; tras cerrar, Mario visible.
- [ ] **En vivo, Review Focus 5:** durante el test, o a mano con el arnés, `keys` hacia el borde hasta morir → `scene` sube, el mod loguea `Linked to galaxy at` y `mbx flags=1/2` de nuevo.
- [ ] El test del stub (`./gradlew runClientGameTest -PgalaxycraftHidden`) sigue verde.
- [ ] Commit `test(e2e): play as Mario from Minecraft`.

### Task 9: Jugar a mano y documentación

**Files:** Modify `tools/gxplay.sh` (arranca en modo Minecraft: sin `GALAXYCRAFT_START_UNLINKED`), `docs/PHASE4.md` (remitir a PHASE5); Create `docs/PHASE5.md`.

- [ ] `gxplay.sh`: modo Minecraft desde el inicio.
- [ ] `PHASE5.md` en español:
  - controles (la tabla del spec §3);
  - Ctrl+G;
  - Esc como pausa;
  - juego o menú;
  - savestates atados al módulo;
  - lista de comprobación a mano: tubo, estrella lanzadora, hablar, trozos de estrella (recoger y disparar), menús con ratón (título, partida, mapa del mundo, selección de estrella), Esc, Ctrl+G;
  - limitaciones conocidas.
- [ ] **En vivo (arnés):**
  - título → mapa del mundo con `ctl keys lmb` y el puntero en modo menú;
  - anotar cuáles de esas pantallas detecta `InGame()` como menú (Review Focus 2);
  - corregir la regla si alguna sale mal, con un test nuevo en Task 2 (`in_game_rule`).
- [ ] Commit `docs: phase 5 Mario mode`.
