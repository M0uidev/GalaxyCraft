# GalaxyCraft — Modo Mario: jugar SMG2 entero desde la vista de Minecraft

Fecha: 2026-10-02. Amplía `2026-10-02-galaxycraft-design.md` y **reemplaza su decisión de autoridad** (§2, "Minecraft es autoritativo"; §7 MarioPuppet).

## 1. Intención

Jugar Super Mario Galaxy 2 completo con la vista y los controles de Minecraft, como si fueras
Mario. Por ejemplo: agacharte sobre un tubo te mete en él, el clic izquierdo gira, y apuntas
con la mira.

- Mandan las mecánicas de SMG2: física de Mario, tubos, estrellas lanzadoras, giro,
  culipatín, nadar, enemigos y menús.
- Minecraft aporta:
  - la cámara en primera persona;
  - teclado y ratón;
  - la mano y la barra;
  - un mundo alineado con la galaxia, para añadir más adelante mecánicas de Minecraft
    (inventario, bloques, golpear).

Criterio de éxito:

- se puede ir del título a Sky Station y recorrerla sin tocar el Wiimote;
- funcionan desde Minecraft: entrar en un tubo, usar una estrella lanzadora, girar, hablar,
  recoger y disparar trozos de estrella, y los menús;
- Ctrl+G sigue alternando con el modo Wiimote.

Fuera de alcance: mecánicas propias de Minecraft en la galaxia (bloques, inventario útil,
combate con la mano), tercera persona, niveles 2D en modo especial.

## 2. Decisiones

| Decisión | Valor |
|---|---|
| Autoridad del movimiento | **SMG2** (Mario se mueve con su física; Minecraft lo sigue) |
| Papel de Minecraft | Vista, controles, mano/barra; mundo alineado con la galaxia (colisión mantenida) |
| Puntero de estrella | En juego: fijo en el centro, sin dibujo; la mira de Minecraft hace de puntero. En menús: libre con el ratón y visible |
| Controles | Override del Wiimote emulado en Dolphin (`ControllerEmu::SetInputOverrideFunction`) |
| Modos | Ctrl+G alterna **Minecraft** ⇄ **Wiimote**; `gxplay.sh` arranca en Minecraft |
| Modo marioneta (fase 4) | Se retira |

## 3. Controles

Entrada: teclado, botones, rueda y movimiento crudo del ratón desde el backend XInput2 de Dolphin
(`GalaxyCraft::OnXInput2`, ya existente). En el arnés headless llegan por `ctl keys …` (§8).

| Minecraft | Wiimote + Nunchuk |
|---|---|
| Ratón | Mirar (en juego) / puntero (en menús) |
| WASD | Stick del Nunchuk: W = (0,+1), S = (0,−1), A = (−1,0), D = (+1,0); diagonales normalizadas a longitud 1 |
| Espacio | A |
| Shift (izq./der.) | Z |
| Clic izquierdo | Agitar (Shake), mantenido mientras el botón esté pulsado, como mínimo 6 frames |
| Clic derecho | B |
| Ctrl (izq./der.) | C |
| Esc | + |
| Tab | − |
| Clic izquierdo, sólo en menús | A (además de agitar) |

- Minecraft sigue recibiendo todo lo que recibe hoy: ratón, E, números, rueda. WASD le llega
  pero no lo mueve (§5).
- **Esc:** en modo Minecraft, el `HotkeyScheduler` de Dolphin no ejecuta `HK_STOP` (Esc).
  Los demás atajos siguen.

**Juego o menú** (decide el host con el buzón): es *juego* si hay Mario (`game_seq` avanzó en
los últimos 30 frames), la gravedad es no nula y no hay cinemática (`game_flags` sin
`GXC_MBX_GAME_DEMO`). Si no, es *menú*.

- **En juego:** el ratón mueve la mirada de Minecraft, el puntero IR va al centro (0,0) y el
  cursor se recentra.
- **En menú:** el puntero IR sigue al cursor dentro de la ventana de render, sin recentrar; el
  clic izquierdo también es A, y Minecraft no gira la cabeza (el host deja de reenviarle el
  movimiento del ratón).

## 4. Cámara y puntero (módulo Syati)

Con `GXC_MBX_FOLLOW` en `host_flags` y sin cinemática:

- **Cámara:** tras `CameraDirector::movement`, la vista se reemplaza.
  - posición: pies de Mario + `up · eye_height`;
  - dirección: `look` del buzón;
  - arriba: `up` del buzón;
  - FOV: `fov_y` del buzón;
  - plano cercano: 10 u.

  La posición sale de Mario, no de `player_pos`, para que la cámara vaya a 60 fps.
- **Mario oculto** (`MR::hidePlayer` en el flanco de subida, `MR::showPlayer` al bajar). **No**
  se usan `offPlayerControl` ni `setPlayerPos`: Mario se controla con el Wiimote.
- **Cinemática:** cámara del juego y Mario visible. Al terminar, vuelve a ocultarse.
- **Puntero:** el módulo oculta cada frame el dibujo del puntero (`StarPointerLayout` /
  `StarPointerBlur`). Si eso resulta desactivar el puntero para el juego, se oculta de otra
  forma (moviéndolo fuera de la vista o haciéndolo transparente); lo decide la prueba de §8.
- `GXC_MBX_DRIVE` (marioneta) deja de usarse.

## 5. Minecraft sigue a Mario

- **El host:** con modo Minecraft activo y mod vivo, escribe `WorldState` cada frame con
  `query_pos` = posición de Mario, la gravedad allí y el indicador nuevo `GXC_WORLD_FOLLOW`.
  `ANCHOR` sigue marcando "re-anclar" tras un cambio de escena.
- **El mod, en FOLLOW:**
  - crea el `GravityFrame` en el primer `WorldState` con gravedad, como hoy;
  - en cada tick:
    1. re-orienta el marco con la gravedad;
    2. coloca al jugador en `frame.toMc(query_pos)`, sin gravedad (`noGravity`), con velocidad
       cero y la distancia de caída a cero;
    3. hace el re-base de altura como hoy.
  - no aplica la espera de suelo ni la colisión al jugador; el campo de colisión se sigue
    construyendo (para el futuro, §1);
  - sigue enviando `PlayerState`: posición (eco), `look`, `up`, FOV y altura de ojos. Mientras
    el host le reenvíe el ratón, la mirada se mueve con él.
- **Escena nueva:** `SceneChange` y re-anclaje, como hoy.

## 6. Modos y fallos

- **Modo Minecraft:**
  - override del Wiimote activo;
  - `FOLLOW` en buzón y `WorldState`;
  - ratón y teclas a Minecraft según juego/menú (§3);
  - Esc sin `HK_STOP`.
- **Modo Wiimote:** sin override (manda el mapeo del usuario), sin `FOLLOW`, Mario visible,
  cámara del juego, Minecraft congelado. Es lo que hace hoy el enlace desactivado.
- **Ctrl+G** alterna los dos modos y suelta todas las teclas y botones de ambos lados.
- **Mod sin latido > 2 s:** el host actúa como en modo Wiimote (sin override ni `FOLLOW`) hasta
  que vuelva.
- **Host sin escribir el buzón 60 frames:** el módulo quita la cámara y muestra a Mario
  (watchdog de `host_seq`, ya existente).

## 7. Protocolo

- **Buzón:** `GXC_MBX_FOLLOW = 2` en `host_flags`.
- **`WorldState`:** `GXC_WORLD_FOLLOW = 2` en `flags`.
- **Espejos:** `tools/gxproto.py` y `Layout.java` se actualizan. Test de layout en C, Python y
  Java.
- `GXC_VERSION` sube a 2: el mod y el host de fase 4 no deben enlazarse con los nuevos.

## 8. Pruebas

1. **Prueba de viabilidad, primero:**
   - con cámara en primera persona mirando +x y luego −z, `keys w` durante 1 s mueve a Mario
     principalmente en esa dirección (> 70 % del desplazamiento horizontal);
   - con el puntero oculto, las capturas no lo muestran, y el juego lo sigue considerando
     válido (`MR::isStarPointerValid`, publicado en una palabra de depuración).

   Si algo falla, se revisa el diseño antes de seguir.
2. **Unitarios:**
   - **C++ (host):**
     - traducción teclas/ratón → estado del Wiimote, con diagonales, agitar mínimo 6 frames,
       puntero centrado en juego y libre en menú;
     - decisión juego/menú (título sin gravedad, cinemática, sin Mario, nivel);
     - `FOLLOW` en buzón y `WorldState`;
     - caída del latido → modo Wiimote;
     - Esc bloqueado sólo en modo Minecraft;
     - parseo de `ctl keys`.
   - **Java (mod):** FOLLOW coloca al jugador en `toMc(query_pos)` sin gravedad ni caída;
     re-anclaje en escena nueva; versión de protocolo 2.
   - **Módulo (g++):** nada nuevo en `core/` salvo que surja lógica pura.
3. **E2E** (`tools/gxe2e.sh`, reemplaza a `WalkOnGalaxyTest`), en Sky Station:
   - enlace en modo Minecraft;
   - `keys w` con la mirada en dos direcciones: Mario se desplaza > 300 u en cada una, y el
     jugador de Minecraft queda a < 30 u de Mario;
   - `keys space`: Mario sube y vuelve;
   - Mario no se ve (palabra de depuración);
   - al cerrar Minecraft: `flags` sin `FOLLOW` y Mario visible.
4. **A mano con `gxplay.sh`:** tubo, estrella lanzadora, hablar con un NPC, disparar y recoger
   trozos de estrella, menús con el ratón, Esc como pausa, Ctrl+G.

## 9. Riesgos

| Riesgo | Mitigación |
|---|---|
| El stick no queda relativo a nuestra cámara (el juego usa otra base) | Prueba §8.1; si falla, rotar el stick en el host con la diferencia entre la cámara del juego y la mirada |
| Ocultar el puntero lo desactiva | Prueba §8.1; alternativas en §4 |
| "Hay Mario" mal detectado en alguna pantalla (mapa del mundo) | Verificación en vivo pantalla a pantalla; el modo menú es el seguro (ratón libre) |
| Agitar necesita más que botón (aceleración) | El override cubre el grupo Shake de Dolphin; si no basta, simular aceleración |
| Savestates atados al módulo | Regenerar `sky.sav` con `gxroute.py` tras cada cambio de `syati/` |
