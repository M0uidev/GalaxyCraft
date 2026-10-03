# Planetas de bloques — hito 1: plan de implementación

> Ejecución nativa (el usuario pidió autonomía y ahorrar tokens): tareas en orden, cada una con
> sus tests y su commit. Pasos en `- [ ]`.

**Objetivo:** un planeta cube-sphere de bloques, dibujado y colisionable dentro de SMG2, que se
rompe y se construye desde Minecraft.

**Arquitectura:** el mod genera el planeta, la malla (display list GX) y el KCL de cada chunk, y los
manda por el ring M→S. Dolphin los copia a un *inbox* del módulo en la RAM del juego. El módulo
copia cada chunk a su heap, lo dibuja y le crea un `CollisionParts`.

**Spec:** `docs/superpowers/specs/2026-10-02-galaxycraft-voxel-planets-design.md` (con §13, resultados de los spikes).

## Restricciones globales

- 80 u por bloque (`GravityFrame.unitsPerBlock`); planeta: núcleo 8, superficie 16, techo 24
  bloques de radio; 24×24 celdas por cara; chunks de 8×8×8.
- Datos para la Wii en big-endian; el ring y la shm, en little-endian.
- Nada de Nintendo ni de Mojang en el repo: el atlas se genera en `syati/build/gen/`.
- Kamek: los globales del módulo sólo tienen inicializadores constantes.

## Review Focus

1. Romper la última celda de un chunk (chunk vacío): el chunk desaparece, no se queda el viejo.
2. Bordes entre caras del cubo: vecinos correctos (sin caras dobles ni huecos en la malla).
3. Cambio de escena con el planeta puesto: el módulo no dibuja ni usa punteros de la escena vieja.
4. Inbox lleno con muchos chunks (carga inicial): Dolphin reparte en varios frames sin perder ninguno.
5. Clic con la mano vacía sigue siendo girar/B; con ítem, nunca llega al Wiimote.

## Tareas

### 1. Protocolo v4
`protocol/galaxycraft_protocol.h`, `protocol/test_layout.c`, `fabric/.../proto/Layout.java`, `tools/gxproto.py`.
- [ ] `GXC_VERSION 4`, `GXC_MBX_VERSION 3`; `GXC_PLAYER_ITEM_ACTIVE = 2`; ring M→S de 1 MiB.
- [ ] Mensajes M→S: `GXC_MSG_PLANET = 102` (`GxcPlanet`), `GXC_MSG_CHUNK = 103` (`GxcChunk` + DL + KCL),
      `GXC_MSG_PLANET_TP = 104`.
- [ ] Buzón: `inbox_addr`, `inbox_size` al final. Inbox (BE): `GxcInboxHeader {state, count, bytes}`
      + registros `{u16 type, u16 0, u32 len, payload, alineado a 4}`.
- [ ] Tests de layout C/Java/Python. Commit.

### 2. Grilla y planeta (Java puro) — `fabric/src/main/java/dev/moui/galaxycraft/voxel/`
- [ ] `CubeSphere`: `corner(face,i,j,layer)`, `cellAt(Vector3d)`, `neighbor(cell, dir)` entre caras.
- [ ] `Material` (AIR, BEDROCK, STONE, DIRT, GRASS; teselas arriba/lado/abajo).
- [ ] `VoxelPlanet`: generar capas, `get/set`, versión y chunks sucios; capa 0 irrompible.
- [ ] `PlanetRaycast`: pasos de 0.05 bloques, alcance 4.5, celda + cara.
- [ ] JUnit: ida y vuelta, vecinos simétricos, esquinas compartidas, raycast. Commit.

### 3. Geometría (Java puro)
- [ ] `KclWriter` (puerto de `tools/kcl.py`, hoja con lista en `offset+2`, grosor 40) — test con `KclParser`.
- [ ] `PlanetMesher`: chunk → quads (posición f32, color RGBA8 con sombreado de Minecraft, UV u8 frac 2)
      como display list GX (`0x80|7`, relleno a 32 bytes) + triángulos para el KCL.
- [ ] JUnit: malla del planeta cerrada; romper una celda agrega las caras esperadas; chunk vacío → 0 bytes. Commit.

### 4. Atlas
- [ ] `tools/voxel_atlas.py`: texturas del jar → RGB565 64×64 en bloques 4×4 → `syati/build/gen/atlas.h`;
      pasto pre-teñido (#79C05A). Test Python del tiling. `syati/build.sh` lo llama. Commit.

### 5. Módulo Syati
- [ ] `src/core/Inbox.{h,cpp}`: parser de registros (g++ test).
- [ ] `src/VoxelPlanet.cpp` (sustituye al spike): actor por escena, inbox de 256 KiB, gravedad puntual,
      chunks copiados al heap, `CollisionParts` por chunk, dibujo con atlas, teletransporte. Commit.

### 6. Dolphin
- [ ] `HostBridge`: cola de mensajes 102–104 → inbox cuando `state == 0`; descarta al cambiar de escena.
- [ ] `MarioInput`: con `ITEM_ACTIVE`, clics no dan Shake/B; F da Shake siempre.
- [ ] Tests en `host_bridge_test` y `mario_input_test`; regenerar el parche. Commit.

### 7. Mod
- [ ] `PlanetService`: `/galaxycraft planet spawn|tp`, envío inicial y por edición, descarte al cambiar de escena.
- [ ] `BlockInteraction`: consume clics de ataque/uso con ítem en la mano, raycast desde la cámara, romper/poner.
- [ ] `ITEM_ACTIVE` en `PlayerState`; el mundo de juego da pasto, tierra, piedra y un pico. Commit.

### 8. Verificación
- [ ] Arnés: `planet spawn` + `tp` por `ctl`/comando, romper la celda bajo Mario, Mario baja ~1 bloque; capturas.
- [ ] Documentar en `docs/PHASE5.md`. Commit.
