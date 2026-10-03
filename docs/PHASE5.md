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
  pone** el bloque de la mano. **F** gira siempre.
- `/galaxycraft planet spawn [radio]` crea uno nuevo (10–256, por defecto 16) que reemplaza al
  de la galaxia; `tp` y `remove` (borra también el guardado). Minecraft está oculto: el chat se
  abre con T en el overlay.
- **Persistencia:** un archivo por galaxia en `.minecraft/galaxycraft/planets/<Stage>.gxplanet`
  (gzip; el del test vive en `fabric/build/run/...`). Se guarda cada 10 s si cambió y al salir de
  la galaxia; al entrar a una galaxia con planeta guardado, se carga y se manda solo. Si Mario muere
  o cambia de escena dentro de la misma galaxia, se manda de nuevo.

## Memoria: Dolphin con 256 MiB de MEM2

Con los 64 MiB normales al heap de la escena le quedan ~1,6 MiB en el prólogo: nada. Con el RAM
override de Dolphin (`MEM2Size = 268435456`, en `tools/dolphin-dev/Dolphin.ini` y en los `-C`
de `gxplay.sh`) SMG2 arranca igual y su heap MEM2 de escena crece: ~200 MiB libres. Los chunks se
piden ahí, alineados a 32, dejando 2 MiB de reserva para el juego. Los savestates de un Dolphin
con otra cantidad de RAM no sirven.

## Planetas grandes

- **Tamaño:** celdas de ~1 bloque en la superficie (`n = π·r/2` por cara), corteza de hasta 24
  bloques y aire construible de r/4 (8–32).
- **Dibujo:** cada chunk con caras visibles se manda (nunca los vacíos ni los enterrados), el más
  cercano a Mario primero. Vértices de 12 bytes (posición s16 relativa al centro del chunk, color
  RGB565, UV u16): radio 256 ≈ 15.600 chunks, 47 MB, 2,4 s del lado del mod. El módulo se salta
  los chunks detrás de la cámara o tras el horizonte (la bola de bedrock tapa).
- **Colisión:** solo los chunks a menos de 24 bloques de Mario, 160 como mucho: las zonas de
  colisión del juego aguantan 512 partes, las del nivel incluidas. El teletransporte manda primero
  la colisión de donde aterriza y un chunk recién cavado bajo Mario sale con colisión al instante.

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

- Romper es instantáneo, no hay drops ni inventario survival (etapa 3), ni contorno del bloque.
- Las partes de colisión reemplazadas no se liberan (unos cientos de bytes por edición).
- Como a Steve en Minecraft, a Mario parado donde se juntan cuatro celdas lo sostienen las otras
  tres si se rompe solo una.
- Un planeta por galaxia; no se puede elegir dónde aparece (siempre encima del jugador).
- Lejos no hay LOD: un planeta de radio 256 visto entero dibuja cientos de miles de quads.
