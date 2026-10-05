# Menú de pausa, movimiento de Minecraft y /skin

Rama `feat/pause-movement-skin` (2026-10-05). La empezó una sesión de Claude en la nube (rama
`claude/pause-menu-movement-skin`), que no podía compilar contra Minecraft ni abrir el juego; se
terminó y se probó aquí, dentro del juego, con el Dolphin de desarrollo. Las capturas quedan en
`~/.local/share/galaxycraft-dev/ScreenShots/SB4E01/move-*.png`. Diseño:
`docs/superpowers/specs/2026-10-05-galaxycraft-pause-movement-skin-design.md`.

## Qué cambió

**Esc abre el menú de pausa de Minecraft.** Es el menú de siempre, con una fila nueva encima de
Options...:

- **GalaxyCraft...**: los ajustes de GalaxyCraft (movimiento, skin, distancia de entidades,
  partículas del juego) y botones para volar, ir al planeta y abrir el editor de planetas.
  También con `/galaxycraft settings`. Se guardan en `config/galaxycraft.properties`.
- **SMG2 Menu**: cierra el menú de Minecraft y abre el menú de pausa de Galaxy 2 (el botón +).

Dentro de los menús de SMG2, Esc sigue siendo el +. El menú de Minecraft no pausa el Galaxy 2
(Dolphin no podría dibujarlo encima de un juego pausado); para eso está SMG2 Menu.

**Dos movimientos, F6 los cambia** (o GalaxyCraft... → Movement):

- *Mario*: el de siempre. SMG2 mueve a Mario (saltos, giros, salto largo) y la cámara lo sigue.
- *Minecraft*: Minecraft mueve al jugador con su física: caminar, correr con Ctrl, agacharse con
  Shift, saltar 1,25 bloques, subirse a un bloque saltando. Camina sobre los planetas y sobre la
  colisión de la galaxia. Mario va pegado al jugador (así el juego sigue viendo monedas,
  enemigos y gravedad donde está el jugador), pero no se dibuja: en tercera persona (F5) se ve a
  Steve con el modelo de jugador de Minecraft, dibujado dentro del juego.

**`/skin <cuenta>`** le pone la skin de esa cuenta de Minecraft al personaje: al modelo de Mario
(se escribe sobre la textura "steve" de su modelo en la memoria del juego) y a Steve. `/skin`
sola vuelve a Steve. Se recuerda entre sesiones y se guarda una copia en
`config/galaxycraft/skins/`, así funciona sin internet la próxima vez.

## Lo que se arregló al probarlo en el juego

- **El jugador se caía a través del planeta con el movimiento de Minecraft.** La colisión de la
  galaxia le llega a Minecraft como cajitas de 1/8 de bloque, cuyo techo puede quedar hasta
  1/8 por encima del suelo real. El jugador empezaba en los pies de Mario, o sea un poco *dentro*
  de esas cajas, y Minecraft deja pasar una caja en la que ya estás. Ahora empieza un cuarto de
  bloque más arriba y cae al suelo.
- **SMG2 Menu a veces no abría nada**: el + se mantenía 4 ticks de Minecraft, que pueden durar
  menos que un fotograma del juego. Ahora se mantiene un cuarto de segundo de verdad.
- **Los botones del menú salían aplastados**: en Minecraft 26.3 el botón de comentarios es un
  ícono de 20 píxeles y los dos botones se metían en la mitad de él. Ahora tienen su propia fila.
- La rama de la nube no compilaba (`Screens.getButtons` ya no existe en Fabric) y venía de un
  master viejo; se rebasó sobre el actual. El mensaje de la skin de Mario pasó a ser el 114
  porque el 113 ya es la luz del cielo.

## Para revisar en el juego

1. Esc en un nivel: el menú de Minecraft con la fila GalaxyCraft... / SMG2 Menu. SMG2 Menu abre
   la pausa del Galaxy 2.
2. F6, caminar por un planeta, saltar a un bloque, F5 para ver a Steve.
3. `/skin` con el nombre de tu cuenta (o `jeb_`), F5 en movimiento de Mario: Mario con esa skin.

## Límites conocidos

- El modelo de Mario es siempre de brazos anchos: una skin de brazos finos ("slim") se ve con una
  franja de un píxel en los brazos. Steve como entidad sí usa el modelo correcto.
- El menú de pausa y la tecla F6 por teclado real (Esc pasando por Dolphin) no se pueden probar
  con el Dolphin sin ventana de las pruebas; se probaron los botones y el + directamente.

## Pruebas

- `tools/gxvoxel.sh movement` (MovementProbe): el menú, SMG2 Menu (el juego se pausa), caminar
  8 bloques, subir a un bloque, el salto de 1,25, Mario pegado al jugador y la skin escrita en el
  modelo de Mario (`move-*.png`).
- `tools/gxvoxel.sh` y `tools/gxvoxel.sh entities` siguen pasando (la prueba de entidades a veces
  falla en "the cow is milked": la vaca murió en la explosión o se alejó; repetirla pasa).
- `./gradlew test` (SettingsTest, SkinImageTest), `galaxycraft_host_tests` (MarioSkin),
  `make -C protocol`, `syati/test.sh`.
