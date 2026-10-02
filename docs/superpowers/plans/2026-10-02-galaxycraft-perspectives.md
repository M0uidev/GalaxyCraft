# Perspectivas (F5) y Steve — plan

Spec: `docs/superpowers/specs/2026-10-02-galaxycraft-perspectives-design.md`. Ejecución inline,
TDD donde hay lógica pura; un commit por tarea.

1. **Protocolo v3.** `galaxycraft_protocol.h`: `GXC_VERSION 3`; `GxcPlayerState` + `cam_offset[3]`
   (60) y `view` (72); `GxcGameCamera` en `GXC_OFF_GAMECAM 320` (96 B); buzón v2 con
   `cam_offset` tras `eye_height` y luego `cam_pos/cam_dir/cam_up/cam_fov/mario_front`;
   `GXC_MBX_GALAXY_VIEW 4`. Espejos `gxproto.py`, `Layout.java`, `Seqlock.java`; tests de layout
   (C, `ProtoTest`, Python si existe).
2. **Host (`dolphin/galaxycraft`).** `PlayerState` con `cam_offset`/`view`; `Write/ReadGameCamera`;
   `HostBridge` parsea la cámara del juego, publica `GameCamera`, escribe `cam_offset` y
   `GALAXY_VIEW`, expone `GalaxyView()`. Tests en `host_bridge_test.cpp`.
3. **Entrada en GALAXY.** Puntero libre, cursor visible, ratón sin reenviar, clic izquierdo sólo
   agita (`MarioInput::Update` recibe `free_pointer`); patch de Dolphin. Tests en
   `mario_input_test.cpp`.
4. **Syati.** Ojo = pies + `cam_offset` (función en `core/ViewMath`, test g++); GALAXY no toca la
   cámara; publica cámara del juego y frente de Mario.
5. **Mod.** `View` (ciclo), `CameraPose` (offset y cámara del juego → MC, puras con tests),
   `CameraClip` (rayo contra triángulos, test), interpolación en FOLLOW (test en `FollowTest`),
   mixins: F5, `Camera.setup`, `Camera.getMaxZoom`, mira, Steve oculto en cinemática.
   `BridgeClient.gameCamera()`, `PlayerOut` con `camOffset`/`view`.
6. **Herramientas.** `fake_galaxy.py` y `gxe2e.sh` (paso F5 ×5), regenerar `sky.sav`.
7. **Verificación.** Todos los tests, build completo, E2E, captura de las 4 vistas con `gxplay`.
