# GalaxyCraft — Planetas de bloques (cube-sphere)

Fecha: 2026-10-02. Amplía `2026-10-02-galaxycraft-design.md` y `2026-10-02-galaxycraft-mario-mode-design.md`
(Modo Mario: manda la física de SMG2).

## 1. Intención

Planetas hechos de bloques con materiales (pasto, tierra, piedra…) que se pueden romper y poner
como en Minecraft Survival, sobre la gravedad de SMG2. La superficie es curva, no escalonada, y cada
bloque conserva su textura respecto al "arriba" local: el pasto siempre arriba, la tierra a los lados.

A futuro: planetas más grandes que los de SMG2, planetas creados por el jugador y mecánicas
survival encima. Este spec cubre los spikes y el **hito 1**; las etapas siguientes quedan
listadas (§10) sin diseño detallado.

Criterio de éxito del hito 1 (a mano con `tools/gxplay.sh`):

1. Rodeas el planeta caminando con el pasto siempre arriba.
2. Cavas un hoyo de 3 bloques y Mario cae dentro.
3. Construyes una columna de 3 bloques y subes por ella.
4. El cambio se ve en menos de 100 ms desde el clic; Dolphin no baja de 60 FPS.

Fuera de alcance del hito 1: persistencia, tiempo de minado, drops, inventario útil, partículas,
planetas grandes, streaming, formas no esféricas, varios planetas, galaxia propia.

## 2. Decisiones

| Decisión | Valor |
|---|---|
| Grilla | **Cube-sphere** (6 caras × grilla 2D × capas radiales). Formas arbitrarias (cartesiana + SDF) en la etapa 6, detrás de la misma interfaz `PlanetGrid` |
| Dueño de los bloques | **El mod de Minecraft** (fuente de verdad, futura persistencia y survival) |
| Quién genera la geometría | El mod (Java puro): display lists GX y KCL por chunk |
| Quién dibuja y colisiona | **SMG2** (actor Syati): luz, profundidad y sombra del juego; Mario colisiona con el KCL |
| Dónde vive el primer planeta | Creado por código en una zona vacía de **Sky Station**, coordenadas fijas |
| Clics | Según el ítem en la mano: bloque o herramienta → izquierdo rompe, derecho pone; mano vacía → Shake y B como hoy. **F** gira siempre |
| Descartado | Que Minecraft dibuje los bloques en el overlay: haría falta compartir profundidad, la luz no casaría, el overlay va un frame atrás y Mario igual necesitaría colisión en SMG2 |

## 3. Componentes

| Pieza | Lugar | Responsabilidad |
|---|---|---|
| `PlanetGrid` | mod, Java puro | celda ↔ posición, 8 esquinas de la celda, "arriba" local, vecinos (también entre caras del cubo) |
| `VoxelPlanet` | mod | material de cada celda, versión por chunk |
| `PlanetMesher` | mod, Java puro | chunk → vértices GX (posición, normal, UV) y KCL |
| `BlockInteraction` | mod, cliente | raycast contra la grilla desde el ojo, clics según el ítem en la mano (el contorno es de la etapa 3) |
| Protocolo v4 | `protocol/` | eventos M→S `PlanetSpawn`, `ChunkMesh`, `ChunkKcl` (troceados como `KclBlob`), `PlanetTeleport`; flag de "ítem activo" en `PlayerState` |
| `HostBridge` | Dolphin, C++ | copia los blobs al pool del juego anunciado en el buzón y marca el chunk como listo |
| `MarioInput` | Dolphin, C++ | con "ítem activo", el clic izquierdo no produce Shake ni el derecho B; F produce Shake siempre |
| `VoxelPlanetActor` | Syati | crea el actor y su gravedad esférica al entrar a Sky Station; dibuja cada chunk listo y registra o reemplaza su `CollisionParts` |
| Atlas | `tools/` | texturas de pasto (arriba, lado), tierra, piedra y bedrock del jar de Minecraft → textura GX en `syati/build/`. Nada de Nintendo ni de Mojang en el repo |

## 4. Flujo al romper o poner un bloque

1. Clic con bloque o herramienta en la mano: Dolphin no lo traduce a Shake/B; el mod lo recibe.
2. `BlockInteraction` encuentra la celda (§6), `VoxelPlanet` la cambia y sube la versión del chunk
   (y de los vecinos si la celda está en un borde de chunk).
3. `PlanetMesher` regenera malla y KCL de esos chunks; el mod los envía por el ring M→S.
4. Dolphin los escribe en el pool del juego.
5. En el frame siguiente Syati cambia dibujo y colisión del chunk juntos.
6. El KCL nuevo también llega al `CollisionField` del mod por el camino de partes actual, así que
   la cámara y su recorte contra paredes siguen funcionando.

Chunks de 8×8×8 celdas dentro de una cara del cubo.

## 5. Geometría

**Proyección equiangular:** dirección = `normalize(base_cara · (tan(u·π/4), tan(v·π/4), 1))`,
con `u, v ∈ [-1, 1]`. Los bordes entre caras calzan 1:1; en las 8 esquinas del cubo se juntan
3 celdas (sin huecos, sólo más torcidas).

**Celda** = `(cara, i, j, capa)`. Capas de **altura constante de 1 bloque**; el ancho depende del radio.

**Planeta del hito 1** (80 u por bloque):

- Radio del núcleo 8 bloques, superficie 16 (1280 u), 24×24 celdas por cara.
- Ancho de celda: ~0.52 en el núcleo, ~1.05 en la superficie, ~1.57 en el límite de construcción.
- Capas: `0` bedrock (no se rompe; tapa el centro hueco), `1–5` piedra, `6–7` tierra, `8` pasto,
  `9–16` aire construible. Nada por encima de la capa 16.

**Malla:** sólo las caras de una celda sólida que dan a aire. Arriba y abajo son quads con los
vértices en las esferas `r_k` y `r_{k+1}`, partidos en 2 triángulos; los laterales son radiales.
Resultado: curvo y facetado (~3.75° por faceta), sin escalones. Normal plana por cara.
Vértice: posición `f32`, normal `s8`, UV `s16`.

**Texturas:** cada material tiene `arriba`, `lado` y `abajo`. "Arriba" es siempre la cara radial
exterior de la celda. En los laterales la V de la textura apunta hacia afuera (el borde verde de
`grass_block_side` queda arriba). El pasto superior y el overlay lateral se pre-tiñen en el atlas
con el verde de llanura. Filtro nearest, sin mipmaps.

**Gravedad:** esfera centrada en el planeta, alcance hasta radio 16 + 40 bloques.

## 6. Raycast

La grilla es curva, así que no sirve el DDA de Minecraft. El rayo avanza en pasos de 0.05 bloques
hasta 4.5 bloques; en cada paso el punto se convierte a celda y la primera celda sólida se confirma
con un test exacto de rayo contra hexaedro. La cara golpeada da la celda donde se pone. No se puede
poner en una celda que ocupe Mario. Romper es instantáneo; la capa 0 no se rompe.

## 7. Llegar al planeta

`/galaxycraft planet tp` (y `ctl "planet tp"` en el arnés) manda `PlanetTeleport`; Syati mueve a
Mario a la superficie del planeta. Espera a que todos los chunks estén listos.

## 8. Errores y casos límite

- El módulo reserva un **pool fijo** al entrar a la escena (2 MB de entrada; el tamaño real lo fija
  el spike 0a/0b) y lo anuncia en el buzón. Si un chunk no cabe: log y se queda la versión anterior.
- Syati sólo aplica un chunk con versión mayor a la que tiene; malla y KCL cambian en el mismo frame.
- Hasta que estén todos los chunks, el planeta no se dibuja ni colisiona.
- Si se cae el enlace (heartbeat), el planeta queda congelado en su último estado; al reconectar el
  mod reenvía todo.
- Sin persistencia en el hito 1: el planeta se regenera al entrar a Sky Station.

## 9. Spikes previos (desechables)

Van antes del hito 1. Si alguno falla, se para y se revisa este diseño.

- **0a, render:** un actor propio dibuja un cubo texturizado con GX en Sky Station. Primero con
  `MR::connectToScene` en una categoría de dibujo de mapa; plan B, engancharse a un `draw` existente
  (como `MarioActor::draw`). Pasa si recibe la luz del escenario, la oclusión con Mario es correcta
  en ambos sentidos y la sombra de Mario sigue bien.
- **0b, colisión:** crear un `CollisionParts` desde bytes KCL generados (`CollisionParts::init`
  con nuestro buffer, como en Petari), y luego sacarlo de la zona y registrarlo con otro KCL. Pasa
  si Mario camina encima, cae al quitarlo y su sombra se proyecta sobre él.

## 10. Etapas siguientes (sin diseño detallado)

| # | Etapa |
|---|---|
| 2 | Regeneración incremental afinada y persistencia en el mundo de Minecraft |
| 3 | Survival básico: tiempo de minado por material, contorno, partículas, drops al inventario |
| 4 | Planetas grandes (radio 64+), streaming de chunks por distancia, presupuesto de RAM medido |
| 5 | Planetas del jugador: generador (radio, capas, minerales), galaxia propia, varios planetas |
| 6 | Formas no esféricas: grilla cartesiana + SDF detrás de `PlanetGrid` |
| 7 | Mecánicas encima: crafteo, minerales, interacción con enemigos de SMG2 |

## 11. Pruebas

- **JUnit:**
  - `PlanetGrid`: ida y vuelta posición → celda → centro; vecinos simétricos (también entre caras);
    esquinas compartidas sin huecos.
  - `PlanetMesher`: el planeta entero es una malla cerrada (cada arista en 2 triángulos); romper
    un bloque agrega exactamente las caras esperadas.
  - KCL generado → `KclParser` existente → mismos triángulos.
  - Raycast: celda y cara correctas, respeta el alcance, no rompe la capa 0.
- **g++** (`syati/test.sh`): tabla de chunks y versiones en `syati/src/core`.
- **`host_bridge_test`:** blobs troceados copiados al pool; reemplazo atómico; chunk que no cabe.
- **`mario_input_test`:** con "ítem activo" los clics no producen Shake/B; F gira siempre.
- **End-to-end** (variante de `gxe2e`): carga `sky.sav`, `planet tp`, rompe por `ctl` la celda
  bajo Mario, comprueba en el buzón que Mario baja ~1 bloque, pone el bloque de vuelta, captura.

## 12. Riesgos

| Riesgo | Mitigación |
|---|---|
| Dibujo GX propio dentro de las categorías de SMG2 requiere RE | Spike 0a con plan B (enganche a un draw existente) |
| `CollisionParts` en runtime con KCL propio | Spike 0b; Petari como referencia |
| RAM libre del juego para el pool | Medirla en el spike; el hito 1 cabe en pocos cientos de KB |
| Distorsión de celdas en un planeta chico | Aceptada en el hito 1; la etapa 4 la reduce |
| La zona "vacía" de Sky Station tiene gravedad o cámaras de zona | Elegir las coordenadas en el spike 0a con `mbx` (gravedad nula alrededor) |

## 13. Resultado de los spikes (2026-10-02) y ajustes

Probados en el prólogo (el libro), porque `tools/gxroute.py sky` ya no llega a Sky Station: se
queda en el libro, también sin el spike.

- **0a, render: pasa.** Un `LiveActor` propio registrado con `MR::connectToScene(this, 0x21, -1, -1, 0x0E)`
  (MapObj, DrawType_ElectricRail) dibuja con GX inmediato y `MR::getCameraViewMtx()`; la profundidad
  con Steve es correcta. Hace falta `MR::invalidateClipping`, si no deja de dibujarse.
- **0b, colisión: pasa.** `new CollisionParts` + `init(mtx, sensor "body", kcl, pa, 0, false)` +
  `MR::validateCollisionParts`: Mario se para encima; con otro KCL sigue encima; con
  `MR::invalidateCollisionParts` cae. Un `.pa` BCSV con 0 campos y 1 entrada basta.
- **Gravedad:** `new PointGravity` + `updateIdentityMtx` + `MR::registerGravity` en `init`; cambiar
  `mRange` después la enciende y la apaga.
- **KCL:** la lista de una hoja del octree empieza 2 bytes después del offset (comprobado en un KCL
  del disco); `tools/kcl.py` estaba corrido y se corrigió. Grosor de prisma 40, como el juego.

Ajustes al diseño:

- **Luz:** sombreado por cara de Minecraft (arriba 100 %, laterales 80 %/60 %, abajo 50 %) horneado
  en el color de vértice, en vez de las luces del escenario. Sin RE de `LightCtrl` y es más Minecraft.
- **Dónde aparece el planeta:** `/galaxycraft planet spawn` lo crea encima del jugador (fuera del
  alcance de su gravedad) en cualquier nivel, también Sky Station. Así se prueba sin la ruta rota.
- **Transporte:** el módulo publica en el buzón un *inbox* (256 KiB en su heap). Dolphin le copia los
  mensajes `PLANET`, `CHUNK` y `PLANET_TP` cuando está vacío; el módulo copia cada chunk a memoria
  propia (display list alineada a 32) y vacía el inbox. Así nadie libera datos que el otro usa.
- Los `CollisionParts` viejos no se liberan (unos cientos de bytes por edición): aceptable en el hito 1.

## 14. Etapa 2 y parte de la 4 (2026-10-02)

Hechas a pedido del usuario: persistencia (un planeta por galaxia, `PlanetStore`) y tamaño
elegible hasta radio 256 con colisión por cercanía, Dolphin con 256 MiB de MEM2 y vértices
compactos. Detalle en `docs/PHASE5.md`. Pendiente de la etapa 4: LOD a distancia.
