# GalaxyCraft — todos los bloques de Minecraft y el inventario creativo

Fecha: 2026-10-03. Amplía `2026-10-02-galaxycraft-voxel-planets-design.md`.

## 1. Intención

Cualquier bloque de Minecraft se puede poner en un planeta, con su forma real: losas a media
altura, flores en cruz, antorchas, vallas que se unen, cristal y hojas que dejan ver a través
(recorte). Mario choca con las cajas de colisión del bloque (atraviesa flores, se para en losas).
Los bloques se orientan con las reglas de colocación de Minecraft respecto al "arriba" local.

`/gamemode` funciona en el mundo de Minecraft (trucos activados) y, con un menú de Minecraft
abierto (inventario creativo, chat), el ratón es un puntero de verdad: el cursor del sistema
aparece sobre Dolphin y su posición mueve el de Minecraft, así que se puede hacer clic en el
inventario.

Decisiones del usuario: formas reales (no cubos para todo) y orientación como en Minecraft.

Fuera de alcance: translúcidos de verdad (cristal tintado, hielo y agua se dibujan con recorte u
opacos), modelos de block entity (cofres, carteles, camas, cabezas, estandartes: un cubo con su
textura de partícula del tamaño de su forma), animaciones de texturas (primer fotograma), tintes
por bioma (el color por defecto), redstone que funcione, bloques que caen, recargar recursos (F3+T)
sin reiniciar.

## 2. Bloques en el mod (`fabric/`)

- Una celda guarda un **id de estado de bloque** (`char`, 16 bits): el id global de Minecraft
  (`Block.BLOCK_STATE_REGISTRY`), aire = 0. El nivel del agua y la lava es su propiedad `level`
  (0 fuente, 1..7 corriendo, 8 cayendo), así que `Fluids` sigue igual con ids.
- `voxel/Blocks` es lo que el planeta sabe de cada id, sin tipos de Minecraft (se prueba con
  `CubeBlocks`, unos cubos): si choca, si tapa caras vecinas, si es fluido y con qué nivel, sus
  quads (en espacio de bloque 0..1, con UV en el atlas propio, tinte y cara de culling), sus
  cajas de colisión y su caja de contorno. `client/McBlocks` lo saca de los modelos horneados
  (`BlockStateModelSet`, `BakedQuad`), `BlockColors` (tinte por defecto), las formas de colisión y
  de contorno, y `Block.shouldRenderFace` para el culling.
- Ejes: el espacio del modelo (x, y, z) va a la celda como (j, k, i): `y` es el radio hacia fuera,
  `x` el eje j y `z` el eje i, para que no quede en espejo (i × j = k en el cube-sphere). Un punto
  del modelo se lleva a la celda por interpolación trilineal de sus 8 esquinas; Este = J_PLUS,
  Oeste = J_MINUS, Sur = I_PLUS, Norte = I_MINUS.
- `PlanetMesher`: cada quad del modelo, deformado a la celda; luz = sol × oclusión ambiental (de
  la cara de la celda donde cae el quad, interpolada) × tinte. Colisión: los cubos llenos como hoy
  (WALL_INSET, FLOOR_GROW); otras formas, las caras de sus cajas que no tapa un vecino lleno.
- Coordenadas de textura u16 con 15 bits de fracción (atlas de hasta 1024×1024).
- Colocar: `Block.getStateForPlacement` con un contexto falso en un hueco de aire del nivel
  cliente, con el jugador girado temporalmente a la mirada en ejes de la celda y la cara y el punto
  del clic en ejes de la celda; después `updateShape` con los 6 vecinos sobre un `LevelReader`
  (proxy) que lee el planeta, y `canSurvive` (una antorcha sin apoyo no se pone). Los vecinos
  también reciben `updateShape` (vallas que se unen, la mitad de arriba de una puerta que cae con
  la de abajo). Puertas, plantas dobles y camas ponen su otra mitad.
- Guardar: `GXP2` = paleta de estados (texto de `BlockStateParser`) + celdas u16 de índices de
  paleta. Los `GXP1` (Material en el nibble bajo) se convierten al leerlos.

## 3. El atlas, enviado en cada escena

- El mod arma un atlas de todos los sprites que usan los modelos de bloques (y agua y lava): RGB5A3
  (alfa para recortes), potencia de dos, 16 texeles por tile, mipmaps de 4 niveles dentro de cada
  tile. Se manda en partes con `GXC_MSG_ATLAS` (nuevo) a cada escena nueva, como el objeto en mano.
- El módulo junta las partes en MEM2 y dibuja los planetas cuando el atlas está completo. Sin el
  atlas compilado (`tools/voxel_atlas.py` desaparece).
- Recorte: comparación de alfa (≥ 0.5), z sólo donde pasa.

## 4. Objeto en mano

`GxcHeld` lleva tres sprites (arriba, lados, abajo: 16×64 RGB5A3, la cuarta franja vacía) en
lugar de tiles del atlas, y deja de depender de él: BLOCK usa los tres, CUBE uno en todas las
caras.

## 5. Puntero en los menús (Dolphin)

- Con un menú de Minecraft abierto (`GXC_PLAYER_SCREEN`) Dolphin deja de capturar el ratón: el
  cursor se ve y no se recentra.
- Nuevo slot `GxcPointerState` (offset 448): posición del cursor en la ventana de render, 0..1, y
  si está dentro. El mod la lleva a la ventana de Minecraft (el overlay ocupa toda la ventana de
  Dolphin) con `MouseHandler.onMove`.
- Los clics del planeta no rompen ni ponen nada con un menú abierto.

## 6. Protocolo v9

`GxcPointerState` en 448, `GXC_MSG_ATLAS = 107` (`GxcAtlas` + datos), `GxcHeld.sprite` de
16×64×2 bytes. Espejos: `tools/gxproto.py`, `proto/Layout.java`, `protocol/test_layout.c`.

## 7. Orden

1. Protocolo v9 y sus espejos.
2. Dolphin: puntero, regla de captura, reenvío de ATLAS y del nuevo HELD.
3. Módulo: atlas por partes, RGB5A3 con recorte, UV de 15 bits, HELD de tres sprites.
4. Mod: `Blocks`, celdas u16, mesher, raycast, sesión, `GXP2`, `McBlocks`, atlas, colocación,
   objeto en mano, puntero, menú sin clics.
5. Mundo con trucos (`/gamemode`), README y PHASE5.
