# GalaxyCraft — Perspectivas (F5) y Steve

Fecha: 2026-10-02. Amplía `2026-10-02-galaxycraft-mario-mode-design.md` (que dejaba la tercera
persona fuera de alcance).

## 1. Intención

En modo Mario, F5 rota la vista como en Minecraft y añade una cuarta vista con la cámara de
SMG2. Donde se ve al personaje, se ve a **Steve** (modelo y skin de Minecraft), no a Mario.

Criterio de éxito: en Sky Station, F5 recorre las 4 vistas y en todas Steve aparece donde está
Mario, con la galaxia coherente con la cámara.

Fuera de alcance: oclusión por profundidad (Steve siempre se dibuja encima de la galaxia),
animaciones de Mario en Steve, casos especiales de niveles 2D.

## 2. Decisiones

| Decisión | Valor |
|---|---|
| Quién dibuja a Steve | Minecraft, en el overlay (vanilla `LocalPlayer`) |
| Ciclo de F5 | FIRST → BACK → FRONT → GALAXY → FIRST |
| Ratón en GALAXY | Puntero de estrella libre (como en menús); Minecraft no gira |
| Cámara en FIRST/BACK/FRONT | Minecraft manda: offset de cámara relativo a los pies, sumado por Syati a los pies actuales de Mario |
| Cámara en GALAXY | SMG2 manda: el mod pone su cámara relativa a Steve como la del juego lo está a Mario |
| Cinemáticas | Mario visible con la cámara del juego; Steve no se dibuja |

## 3. Vistas (mod)

- El mod guarda `view` (FIRST, BACK, FRONT, GALAXY). Un mixin sobre el manejo de
  `keyTogglePerspective` reemplaza el ciclo vanilla: FRONT pasa a GALAXY y GALAXY a FIRST.
  En GALAXY, `CameraType` es `THIRD_PERSON_BACK` (Steve dibujado, sin mano) y la mira se oculta.
- **FIRST/BACK/FRONT:** tras `Camera.setup` el mod lee la posición y orientación reales de la
  cámara y envía en `PlayerState`:
  - `cam_offset = toGal(cámara) − toGal(pies)` en unidades de galaxia;
  - `look`, `up`: dirección y arriba de la cámara (en FRONT, la mirada invertida, como vanilla).
- **Recorte en tercera persona:** un mixin en `Camera.getMaxZoom` limita la distancia con un
  rayo contra los triángulos de `CollisionField` (sin colisión cargada: distancia completa).
- **GALAXY:** con `GameCamera` válida, tras `Camera.setup` el mod coloca la cámara en
  `pos_render_steve + dirToMc(cam_pos − mario_pos)·escala`, con orientación `cam_dir`/`cam_up`
  convertida al marco, y FOV `fov_y`. Cuerpo y cabeza de Steve miran a `mario_front`. Sin
  `GameCamera` válida dibuja como BACK.
- **Movimiento de Steve:** en FOLLOW el jugador ya no hace `setOldPosAndRot()` en cada tick: la
  posición anterior queda en `xo/yo/zo`, así hay interpolación y `walkAnimation` usa el
  desplazamiento de Mario. Tras anclar o re-basar se sigue cortando la interpolación.
- **Cinemática** (`GameCamera.flags` con DEMO): Steve no se dibuja.

## 4. Cámara y entrada (Syati y host)

- **Syati**, en FOLLOW sin cinemática:
  - sin `GXC_MBX_GALAXY_VIEW`: ojo = pies de Mario de este frame + `cam_offset`; dirección
    `look`, arriba `up`, FOV y plano cercano como hoy;
  - con `GXC_MBX_GALAXY_VIEW`: no toca la cámara ni el plano cercano; Mario sigue oculto;
  - siempre publica la cámara del juego (`MR::getCamPos`, `getCamZdir`, `getCamYdir`,
    `getFovy`) y `MR::getPlayerFrontVec`, en el mismo frame que `anchor_pos`.
- **Host:**
  - copia `cam_offset` y la vista al buzón (`GXC_MBX_GALAXY_VIEW` si `view == GALAXY`);
  - publica `GameCamera` cada frame de juego;
  - en juego con vista GALAXY: el puntero IR sigue al cursor (como en menú), cursor visible y
    sin recentrar, el movimiento del ratón no se reenvía a Minecraft, y el clic izquierdo sólo
    agita (no es A). El teclado no cambia.

## 5. Protocolo (v3)

- `GXC_VERSION` = 3.
- `GxcPlayerState`: del relleno salen `cam_offset[3]` y `view` (u32). `look`/`up` pasan a ser
  los de la cámara.
- Slot nuevo `GxcGameCamera` (S→M, seqlock) en `GXC_OFF_GAMECAM = 320`, 96 bytes:
  `seq, flags (VALID=1, DEMO=2), frame_id(u64), cam_pos[3], cam_dir[3], cam_up[3], fov_y,
  mario_pos[3], mario_front[3]`.
- Buzón (`GXC_MBX_VERSION` = 2): host `cam_offset[3]` y `GXC_MBX_GALAXY_VIEW = 4` en
  `host_flags`; juego `cam_pos[3]`, `cam_dir[3]`, `cam_up[3]`, `cam_fov`, `mario_front[3]`.
- Espejos: `tools/gxproto.py`, `Layout.java`; tests de layout en C, Python y Java.
  `sky.sav` se regenera con `gxroute.py`.

## 6. Fallos

- GALAXY sin `GameCamera` válida: el mod dibuja como BACK.
- Modo Wiimote o mod muerto: como hoy; la vista elegida se ignora.
- Escena nueva: la vista se conserva.

## 7. Pruebas

- **Java:** ciclo de F5; `cam_offset` por vista; cámara del juego → Minecraft relativa a Steve;
  rayo contra triángulos; interpolación conservada en FOLLOW y cortada al re-basar.
- **C++ host:** GALAXY → puntero libre, clic izquierdo sólo agita, ratón sin reenviar; paso de
  `cam_offset`/vista al buzón y del buzón a `GameCamera`.
- **Syati core (g++):** ojo = pies + offset.
- **Layout:** C, Python, Java.
- **E2E** (`gxe2e.sh`, Sky Station): `keys f5` ×4 recorre los offsets esperados y activa
  `GALAXY_VIEW`; en GALAXY la `GameCamera` es válida; la quinta vuelve a FIRST.
- **A mano** (`gxplay.sh`): alineación de Steve en las 4 vistas, caminando y saltando; la
  cámara en tercera no atraviesa paredes; puntero en GALAXY.
