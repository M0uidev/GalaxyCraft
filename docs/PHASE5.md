# Fase 5: planetas de bloques (hito 1)

Diseño: `docs/superpowers/specs/2026-10-02-galaxycraft-voxel-planets-design.md`. Plan:
`docs/superpowers/plans/2026-10-02-galaxycraft-voxel-planets.md`.

Un planeta cube-sphere de 16 bloques de radio (bedrock, piedra, tierra y pasto) que SMG2 dibuja
y con el que Mario colisiona. Se rompe y se construye desde Minecraft.

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
- `/galaxycraft planet spawn|tp|remove` hace lo mismo a mano (Minecraft está oculto: el chat
  se abre con T en el overlay).
- El planeta dura toda la sesión: si cambias de escena o Mario muere, se manda de nuevo.

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
- Al romper el bloque bajo sus pies, baja exactamente uno. Se rompe un hoyo de 2×2, porque el
  teletransporte lo deja en el centro de una cara del cubo, que es la esquina de cuatro celdas.
- Un bloque se pone al lado, pero nunca dentro de Mario.

Las capturas quedan en `~/.local/share/galaxycraft-dev/ScreenShots/SB4E01/voxel-*.png`.
Resultado del 2026-10-02: PASS.

Contadores en el juego: la palabra 14 de depuración (`at + 0xFB0 + 0x38`) apunta a `gVoxelStats`:
inbox recibidos, registros, chunks dibujados, partes de colisión creadas, último slot y versión.

## Aprendido en el camino

- **Las texturas GX van alineadas a 32 bytes.** Kamek no respeta `__attribute__((aligned(32)))`
  en los datos del módulo, y el GPU ignora los 5 bits bajos de la dirección. Por eso el atlas se
  copia al heap alineado.
- **La lista de una hoja del octree KCL empieza 2 bytes después del offset.**
- Hace falta `MR::invalidateClipping` en el actor; si no, deja de dibujarse.

## Limitaciones conocidas

- Romper es instantáneo, no hay drops ni inventario survival (etapa 3), ni contorno del bloque.
- Las partes de colisión reemplazadas no se liberan (unos cientos de bytes por edición).
- Como a Steve en Minecraft, a Mario parado donde se juntan cuatro celdas lo sostienen las otras
  tres si se rompe solo una.
- No hay persistencia: al cerrar Minecraft, el planeta se pierde.
