# Fase 5: planetas de bloques (hito 1, persistencia y planetas grandes)

Diseño: `docs/superpowers/specs/2026-10-02-galaxycraft-voxel-planets-design.md`. Plan:
`docs/superpowers/plans/2026-10-02-galaxycraft-voxel-planets.md`.

Planetas cube-sphere de 10 a 256 bloques de radio (bedrock, piedra, tierra y pasto) que SMG2
dibuja y con los que Mario colisiona. Se rompen y se construyen desde Minecraft, y se guardan:
uno por galaxia.

## Jugar

```sh
dolphin/build.sh && syati/build.sh
tools/gxplay.sh
```

- En cuanto el enlace sigue a Mario en un nivel, aparece un planeta 80 bloques por encima de él,
  fuera del alcance de su gravedad. **P** te deja en su superficie.
- La barra: el slot 1 está vacío (los clics son de Mario: girar y B). En los slots 2–5 hay un
  pico, pasto, tierra y piedra: con ellos en la mano, **clic izquierdo rompe** y **clic derecho
  pone** el bloque de la mano. **F** gira siempre. En el demo, los slots 6–9: cobblestone, hielo,
  balde de agua y balde de lava.
- **Agua, lava y hielo** (`voxel/Fluids.java`, como `FlowingFluid` de Minecraft con "abajo" hacia
  el centro): el balde lleno pone una fuente y queda vacío; el vacío recoge una fuente. El agua
  avanza 7 bloques cada 5 ticks, la lava 3 cada 30, y van hacia el desnivel más cercano (4 y 2
  bloques), así que el **generador de cobblestone** clásico funciona. Lava tocada por agua (no
  desde abajo): fuente → **obsidiana**, corriente → cobblestone; lava que cae sobre agua → piedra.
  Dos fuentes de agua crean otra. El hielo roto deja agua. El nivel del fluido va en el nibble alto
  de la celda (los guardados viejos siguen valiendo). Los fluidos se dibujan opacos, a la altura de
  su nivel, y Mario los atraviesa: no hay nado ni daño de lava todavía.
- `/galaxycraft planet spawn [radio]` crea uno nuevo (10–256, por defecto 32) que reemplaza al
  de la galaxia; `tp` y `remove` (borra también el guardado). El chat se abre con **T** en el
  overlay: Dolphin traduce cada tecla con tu distribución de teclado (XKB: Shift, AltGr, ñ) y se la
  da a Minecraft como texto (protocolo v6, `GxcTextState`). Con el chat abierto Mario no se mueve,
  Esc lo cierra y Enter envía. Las teclas muertas (tildes) no componen todavía.
- **`/fly`** alterna el vuelo libre: vuelas como en creativo (Espacio sube, Shift baja, Ctrl
  acelera), el "arriba" pasa a ser el +Y de la galaxia (horizonte horizontal, sin gravedad de
  planetas) y la cámara del juego te sigue. Mario se queda quieto y se ve. `/fly` otra vez te
  devuelve a sus ojos.
- **Persistencia:** un archivo por galaxia en `~/.local/share/galaxycraft/planets/<Stage>.gxplanet`
  (gzip; `-Dgalaxycraft.planetDir` lo cambia, y `gxvoxel.sh` usa `fabric/build/test-planets`). No
  en el directorio del juego: los client game tests con que `gxplay.sh` lanza Minecraft lo borran
  en cada arranque. Se guarda cada 10 s si cambió y al salir de
  la galaxia; al entrar a una galaxia con planeta guardado, se carga y se manda solo. Si Mario muere
  o cambia de escena dentro de la misma galaxia, se manda de nuevo.

## Memoria: Dolphin con 256 MiB de MEM2

Con los 64 MiB normales al heap de la escena le quedan ~1,6 MiB en el prólogo: nada. Con el RAM
override de Dolphin (`MEM2Size = 268435456`, en `tools/dolphin-dev/Dolphin.ini` y en los `-C`
de `gxplay.sh`) SMG2 arranca igual y su heap MEM2 de escena crece: ~200 MiB libres. Los chunks se
piden ahí, alineados a 32, dejando 2 MiB de reserva para el juego. Los savestates de un Dolphin
con otra cantidad de RAM no sirven.

## Aspecto

- **Luz:** un sol fijo (dirección `PlanetMesher.SUN`) más luz ambiental de 0,5: el planeta tiene
  lado de día y de noche. **Oclusión ambiental** de Minecraft en cada esquina (hoyos y bordes).
- **Mipmaps** del atlas (64→8 texels, cada tesela por separado): de cerca, texels nítidos; lejos,
  sin parpadeo.

## Planetas grandes

- **Tamaño:** celdas de ~1 bloque en la superficie (`n = π·r/2` por cara), corteza de r/4 bloques
  (3–24) y aire construible de r/4 (8–32). Las celdas se estrechan hacia el centro: con esa corteza
  ninguna celda que se pueda cavar baja de 3/4 de bloque, donde Mario todavía cabe.
- **Dibujo:** cada chunk con caras visibles se manda (nunca los vacíos ni los enterrados), el más
  cercano a Mario primero. Vértices de 12 bytes (posición s16 relativa al centro del chunk, color
  RGB565, UV u16): radio 256 ≈ 15.600 chunks, 47 MB, 2,4 s del lado del mod. El módulo se salta
  los chunks detrás de la cámara o tras el horizonte (la bola de bedrock tapa). La cámara para eso
sale de la matriz de vista con que se dibuja: la del juego (`MR::getCamPos`) no es la de primera
persona, y por eso desaparecían trozos enteros del planeta.
- **Colisión:** solo los chunks a menos de 24 bloques de Mario, 160 como mucho: las zonas de
  colisión del juego aguantan 512 partes, las del nivel incluidas. El teletransporte manda primero
  la colisión de donde aterriza y un chunk recién cavado bajo Mario sale con colisión al instante.

## Mario cabe donde cabría Steve

El movimiento de Mario está hecho para los niveles de SMG2, no para bloques de 80 u: mantiene las
paredes a 80 u (`Mario::checkAllWall`), siente el suelo con tres sondas a 50 u (`checkGround`) y lo
empujan fuera del mapa bolas de 50 y 40 u (`checkBaseTransBall`, `createAtField`, `checkStep`) y su
binder de 60 u. Así un hoyo de 1×1 lo sostenía (hacía falta 3×3) y no entraba en un túnel de 1.

- Esos radios están escritos en su código (`lfs fN, d(r2)`). En la gravedad de un planeta de
  bloques el módulo cambia cada una de esas cargas por un salto a un trampolín que carga el radio
  del planeta (`GxcPlanet.mario_radius`: 0,3 bloques en la superficie como Steve,
  `-Dgalaxycraft.marioRadius`) y encoge el binder a lo mismo; fuera, deja el código del juego como
  estaba (`gRadiusPatches` en `syati/src/GalaxyCraft.cpp`; solo parchea si la palabra es la
  esperada). Tres eran fáciles de pasar por alto: el mínimo de 40 de la bola de `createAtField`
  cuando lo empujan (`tryPushToVelocity`), su ancho de 150 en otro estado (`update`) y la bola de
  80 con que `checkVerticalPress` decide si lo aplastan. Con esas sin parchear un hoyo de menos de
  ~90 u de ancho lo sostenía por delgado que fuera.
- Bajo la superficie el radio se achica con la distancia al centro, como las celdas (a 3/4 del
  radio miden 3/4). Las celdas cerca de las esquinas del cubo-esfera son además más angostas (0,72
  bloques en la superficie, 0,56 en el fondo de la corteza): 0,3 cabe en todas.
- Algo de su colisión no escala con el radio (de su tabla de constantes, no es una carga de
  `r2`): en una celda de menos de ~35 u de ancho (0,43 bloques) queda acuñado entre las paredes,
  que se juntan hacia el centro, y tirita. Las celdas cavables de hoy miden al menos 0,56 bloques;
  los planetas guardados con la corteza de antes (medio radio) se sellan con bedrock bajo la de
  hoy al cargarlos (`VoxelPlanet.sealBelowCrust`). `gxfit.sh` mide también que se quede quieto
  (en la superficie, en los hoyos y en el fondo de un pozo de un planeta de radio 16, empujando
  sus paredes); `GXC_CRUST` da cortezas más hondas para reproducirlo.
- Con paredes a menos de ~35 u por varios lados (un hoyo 1×1) Mario rebotaba de lado un cuadro de
  cada tres, algo de su propio movimiento que ningún radio parcheado alcanza (y no es el mod: pasa
  sin que Minecraft mande nada). Las paredes chocan 0,1 bloques dentro de su bloque
  (`PlanetMesher.WALL_INSET`) y los suelos y techos llegan 0,12 por debajo de cada pared que se
  alza de su borde (`FLOOR_GROW`), así no queda ranura al pie; hacia el aire quedan como se ven.
  `tools/gxshake.py` mide cada cuadro desde la memoria compartida (un tick de Minecraft ve uno de
  cada tres y no veía el rebote); empujando una pared, con el enlace apagado, se mide con el
  historial de cuadros del módulo (`Debug.history`).
- `tools/gxfit.sh` (`MarioFitTest`) lo mide en el prólogo: hoyos 1×1, cruz, 2×2 y 3×3 de dos de
  hondo (cae los dos bloques), un pozo de tres tapado (se queda en el piso de una celda 1×1×2 y el
  techo lo para al saltar) y, al quitar el planeta, el Mario del juego otra vez. Como Steve, en un
  1×1 cae solo si está a menos de ~0,2 bloques del centro. El prólogo es un libro en 2D: el stick
  solo lo mueve por un eje, así que los túneles horizontales se prueban jugando.
- `GXC_RADIUS` y `GXC_INSET` cambian los dos valores en `gxfit.sh` sin recompilar el módulo.
- **F3+B** (las hitboxes de Minecraft) dibuja la colisión de Mario sobre todo: en azul las tres
  bolas que lo sacan de los bloques (`checkBaseTransBall`), en rojo un cilindro de su radio hasta la
  de arriba y en amarillo sus tres sondas de suelo (`GXC_PLAYER_HITBOXES` → `GXC_MBX_HITBOXES`).

## Contorno del bloque

Con algo en la mano, el bloque al que apuntas (el que rompería o contra el que pondría; con el balde
vacío, la fuente que recogería) tiene el contorno de Minecraft: el mod manda sus 8 esquinas
(`GXC_MSG_OUTLINE`, protocolo v7) solo cuando cambia, antes que los chunks, y el módulo dibuja las 12
aristas en negro translúcido, con test de profundidad.

## Cómo viaja

1. El mod (`voxel/`) genera el planeta y, por chunk de 8×8×8 celdas, una display list GX y un KCL
   (`PlanetMesher`, `KclWriter`), relativos al centro del planeta.
2. Los manda por el ring M→S: `GXC_MSG_PLANET`, `GXC_MSG_CHUNK`, `GXC_MSG_PLANET_TP`.
3. Dolphin (`HostBridge::FlushInbox`) los pasa a big-endian y los copia al inbox del módulo
   (256 KiB, `GxcMailbox.inbox_addr`) cuando está vacío. Si no caben, sigue en el frame siguiente.
4. El módulo (`syati/src/VoxelPlanet.cpp`) copia cada chunk a su heap, lo dibuja con el atlas y le
   crea un `CollisionParts`. La gravedad es una `PointGravity` con alcance de superficie + 40 bloques.

## Test end-to-end

```sh
tools/gxvoxel.sh              # arranque limpio, ruta al prólogo, VoxelPlanetTest
GXC_REUSE=1 tools/gxvoxel.sh  # reutiliza el savestate del prólogo si syati/ no cambió
```

Se prueba en el prólogo (el libro) porque `tools/gxroute.py sky` ya no llega a Sky Station.
`VoxelPlanetTest` comprueba:

- Mario aterriza a 16 bloques del centro.
- Al romper el pasto bajo sus pies (3×3: Mario es más ancho que un bloque y un hoyo de 1×1 lo
  sostiene), baja exactamente uno.
- Un bloque se pone al lado, pero nunca dentro de Mario.
- Guardado y vuelto a cargar desde disco, el hoyo sigue y Mario sigue en él.
- Un planeta de radio 128: Mario aterriza en su pasto.

Las capturas quedan en `~/.local/share/galaxycraft-dev/ScreenShots/SB4E01/voxel-*.png`.
Resultado del 2026-10-02: PASS.

Contadores en el juego: la palabra 14 de depuración (`at + 0xFD0 + 0x38`) apunta a
`gVoxelStats` (`syati/src/VoxelPlanet.h`): inbox recibidos, registros, chunks con algo que dibujar,
partes creadas y vivas, chunks sin memoria, MEM2/MEM1 libres, chunks dibujados el último frame.
Medido el 2026-10-02 con radio 128: 4.056 chunks, 876 dibujados, 46 partes, ~200 MB libres.

## Aprendido en el camino

- **Las texturas GX van alineadas a 32 bytes.** Kamek no respeta `__attribute__((aligned(32)))`
  en los datos del módulo, y el GPU ignora los 5 bits bajos de la dirección. Por eso el atlas se
  copia al heap alineado.
- **La lista de una hoja del octree KCL empieza 2 bytes después del offset.**
- Hace falta `MR::invalidateClipping` en el actor; si no, deja de dibujarse.
- **`CollisionParts::init` usa la zona que se está colocando** (`MR::getCurrentPlacementZoneId`),
  que fuera de la carga del nivel queda vieja: en un nivel real el keeper no tenía esa zona y el
  juego leyó un puntero nulo (el crash de `0x8024a164`). El módulo fija la zona 0 alrededor de
  `init` y, si ni esa existe, deja el chunk sin colisión.

## Limitaciones conocidas

- Romper es instantáneo, no hay drops ni inventario survival (etapa 3).
- Las partes de colisión reemplazadas no se liberan (unos cientos de bytes por edición).
- Un planeta por galaxia; no se puede elegir dónde aparece (siempre encima del jugador).
- Lejos no hay LOD: un planeta de radio 256 visto entero dibuja cientos de miles de quads.

## Todos los bloques de Minecraft (2026-10-03)

Diseño: `docs/superpowers/specs/2026-10-03-galaxycraft-all-blocks-design.md`.

- Una celda guarda el id del estado de bloque de Minecraft (16 bits). `voxel/Blocks` es lo que el
  planeta sabe de cada id; `client/McBlocks` lo saca de los modelos horneados de Minecraft, y
  `voxel/CubeBlocks` es la versión de las pruebas.
- Los modelos se doblan sobre la celda por interpolación trilineal (`voxel/CellSpace`: x → j,
  y → afuera, z → i, sin espejo). Colisión con las cajas reales; los cubos llenos chocan como antes.
- Colocar usa `getStateForPlacement`, `updateShape` y `canSurvive` de Minecraft sobre una copia
  de los 3×3×3 vecinos escrita un instante en el nivel cliente, arriba del jugador.
- El atlas ya no se compila en el módulo: el mod lo arma con todos los sprites de bloques (RGB5A3,
  recortes con alfa) y lo manda por partes (`GXC_MSG_ATLAS`) a cada escena. Se fue
  `tools/voxel_atlas.py`.
- Los planetas se guardan como `GXP2` (paleta de estados + índices); los `GXP1` se leen igual.
- `/gamemode creative` y **E**: con un menú de Minecraft abierto Dolphin suelta el ratón y manda la
  posición del cursor (`GxcPointerState`, protocolo v9).

Limitaciones: sin translucidez real (cristal tintado, hielo y agua se ven opacos o recortados),
cofres, carteles y camas son cajas con su textura de partícula, texturas sin animar, tintes de
bioma por defecto, losas que no se juntan en dobles.

## Bloques que funcionan (2026-10-03)

- Minecraft corre los bloques cerca de Mario (`shadow/ShadowWorld`): las celdas a 48 bloques se
  copian a una dimensión del servidor integrado, `galaxycraft:shadow` (vacía, 128 de alto), cuyos
  chunks quedan cargados a la fuerza. Cada cara del cubo es una caja (x = j, y = capa, z = i;
  `shadow/ShadowMap`) con un halo de un bloque que copia las celdas del otro lado de la arista, así
  la redstone cruza las aristas. Sólo GalaxyCraft escribe en el halo y fuera del planeta.
- Ida y vuelta por colas entre hilos: los cambios del planeta (jugador, fluidos) van a la sombra con
  actualizaciones de vecinos; lo que cambia allí vuelve al planeta (`LevelChunk.setBlockState`). Un
  cambio que vuelve se descarta si el cliente cambió esa celda después.
- Clic derecho como `ServerPlayerGameMode.useItemOn`: el uso del bloque, después el del objeto
  sobre él; si nada lo usa, se coloca el bloque. Con la mano vacía, apuntar a un bloque que tiene uso
  hace los clics de Minecraft.
- En la sombra el agua y la lava no corren (las lleva `voxel/Fluids`), lo que cae se pierde y los
  sonidos se oyen donde está el jugador, más bajos según la distancia a Mario.
- Probar: `./gradlew runClientGameTest -PgalaxycraftBlocks` (`ShadowProbe`: palanca y lámpara,
  puerta, pistón; ~25 s, sin Dolphin).

Limitaciones: sólo corre lo que está cerca de Mario; los bloques en movimiento (pistones, arena que
cae) no se ven mientras se mueven; un repetidor u observador que apunta a través de una arista no
gira con ella; las entidades que crean los objetos (barcos, vagonetas, mobs de huevos) quedan en la
sombra, invisibles; 
## Objetos que caen y supervivencia (2026-10-03)

- Romper (fuera de creativo) corre en la sombra como `ServerPlayerGameMode.destroyBlock`: la
  herramienta se gasta y el botín es el de Minecraft. Los `ItemEntity` que nacen en la sombra, y los
  que tira el jugador cerca de sí mientras está en un planeta (**Q**, fuera del inventario), pasan a
  ser objetos del planeta (`voxel/PlanetDrops`): caen hacia el centro, se paran en las cajas de
  colisión reales, se juntan, desaparecen a los 5 minutos y Mario los recoge al pasar (alcance de
  Minecraft). Se dibujan como `ItemEntity` sólo del cliente (`client/DropsClient`, ids negativos)
  puestos cada tick donde los tiene el planeta. La experiencia va directa al jugador.
- Colocar fuera de creativo gasta uno del objeto en la mano.

Limitaciones: los objetos tirados no se guardan con el planeta; no flotan en el agua ni se queman.
