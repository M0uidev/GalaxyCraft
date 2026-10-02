# Fase 4: caminar por SMG2 desde Minecraft

Minecraft maneja al jugador. Mario es una marioneta oculta en SMG2. Lo que se ve es la cámara
del juego en primera persona, con la mano y la barra de Minecraft encima. La gravedad es la de
la galaxia: en Sky Station se puede dar la vuelta a la torre caminando.

## Jugar

```sh
dolphin/build.sh && syati/build.sh   # una vez (y tras cambiar dolphin/ o syati/)
tools/gxplay.sh
```

`gxplay.sh` abre dos programas y, al cerrar uno, cierra el otro:

- **Dolphin**, con su ventana y tu configuración normal (mandos incluidos), arrancando SMG2 con
  el módulo.
- **Minecraft**, oculto, en un mundo de prueba (aventura, pacífico).

En la ventana de Dolphin:

- **F10** alterna el enlace.
  - *Activo*: teclado y ratón van a Minecraft, Mario es la marioneta y la cámara es la de
    Minecraft.
  - *Inactivo*: el juego vuelve a tu Wiimote. Úsalo para los menús: título, partidas, mapa y
    selección de estrella.
- **Esc** sigue siendo de Dolphin.
- Al entrar en un nivel con el enlace activo, el jugador aparece donde está Mario.

Si cierras Minecraft, en 2 s Mario vuelve a ser visible y tuyo, con la cámara del juego.

## Escala: 1 bloque = 80 unidades

El spec partía de 100 u por bloque (Mario ≈ 160 u) y pedía ajustarlo con una prueba. Medido
en SB4E con el módulo (palabras de depuración, ver `docs/PHASE3.md`):

- `getPlayerCenterPos` está 60 u sobre los pies: es el centro de la esfera de colisión, no la
  mitad de la altura.
- La articulación `Head` del modelo está a 93.8 u sobre los pies (Mario quieto).

De ahí sale una altura de unos 145 ± 15 u. Como Steve mide 1.8 bloques, eso da 80 u por bloque
(un 20 % menos que 100). Se cambia con `-Dgalaxycraft.unitsPerBlock=N`, que lee
`GravityFrame.unitsPerBlock`. Los tests unitarios fijan 100.

## Test end-to-end

```sh
tools/gxroute.py new-game && tools/gxroute.py sky   # una vez por versión del módulo: sky.sav
tools/gxe2e.sh
```

`gxe2e.sh` arranca el Dolphin del arnés bajo gdb y carga `sky.sav`. Luego corre
`WalkOnGalaxyTest` en un Minecraft oculto, que comprueba:

- que se enlaza y aterriza;
- que el host maneja a Mario (`flags=1/1`) y que Mario está a menos de 50 u del jugador;
- un paseo de 20 s, girando 90° cuando una pared lo para: en el suelo ≥ 80 % del tiempo,
  > 1500 u recorridas y el "arriba" galáctico girando > 20°.

Al cerrarse Minecraft comprueba que Mario vuelve: `flags=0/0` y la palabra `driven` a 0.

Resultado del 2026-10-02, dos veces seguidas:

- 77 de 80 muestras en el suelo;
- 3813 u recorridas;
- el arriba giró hasta 82° al subir por la curva hacia la torre.

Capturas en `~/.local/share/galaxycraft-dev/ScreenShots/SB4E01/e2e-*.png`.

El test del planeta del stub (`./gradlew runClientGameTest -PgalaxycraftHidden`) sigue pasando
con la escala nueva.

## Comprobado a mano (arnés)

- **Cinemática con la marioneta puesta:** el módulo no mueve a Mario y deja la cámara de la
  cinemática (`flags=3/1`).
- **Escena nueva mientras se maneja:** con `drive` al vacío, Mario muere, `scene` sube y la
  marioneta se re-aplica al Mario nuevo.
- **Interruptor de enlace:** `ctl "link off"` pasa de `flags=3/1` a `2/0`; `link on` vuelve a
  `3/1`.

## Pendiente de probar a mano con `gxplay.sh`

- F10 en la ventana real de Dolphin; los menús con tu Wiimote o mando con el enlace inactivo.
- Morir con el mod enlazado: el mod debe re-anclar en el punto de reaparición. El host lo hace
  por tests, pero en vivo sólo se probó con `drive`.

## Limitaciones conocidas

- **Mario oculto en cinemáticas:** durante una cinemática con el enlace activo, Mario sigue
  oculto.
- **Posición durante cinemáticas:** si el juego mueve a Mario en una cinemática, Minecraft no
  se entera y al terminar Mario vuelve a la posición del jugador.
- **Cuelgue sin explicar:** una vez, al llegar a Sky Station, `dolphin-emu-nogui` murió con
  SIGSEGV. No se pudo reproducir en tres repeticiones. Por eso `gxe2e.sh` usa `gxdev.py --gdb`:
  si vuelve a pasar, la traza queda en `dolphin.log`.
- **Bordes como escalones:** como en la fase 1, las superficies son escalones de 1/8 de bloque
  (10 u con la escala nueva).
- **Savestates atados al módulo:** guardan el código del módulo, así que tras recompilar
  `syati/` hay que regenerar `sky.sav` con `gxroute.py`.
