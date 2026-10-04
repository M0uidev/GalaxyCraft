# Optimización: planetas grandes sin lag ni caídas a través del suelo

Síntomas reportados con un planeta de radio 128: el tiempo de tick de Minecraft se dispara al
crear el planeta y, caminando sobre él, Mario a veces pierde la colisión, atraviesa el suelo y
queda dentro del planeta.

## Cuánto pesa un planeta

| Radio | Celdas | `char[]` del planeta | Chunks de 8³ |
|---|---|---|---|
| 64 | ~2,0 M | ~4 MB | 4.056 |
| 128 | ~13,6 M | ~27 MB | 28.392 |
| 256 | ~54 M | ~108 MB | ~113.000 |

Uno de radio 128 cuesta como siete de radio 64. Un planeta plano de radio 256 son 15.612 chunks
con algo que mostrar, 47 MB de display lists y ~6 s de mallado en un solo hilo
(`PlanetSessionTest.biggestPlanetFitsTheGameAndSendsInSeconds`).

## Por qué Mario atravesaba el planeta

Minecraft y Dolphin corren cada uno a su ritmo. La colisión existe solo en los chunks cercanos a
Mario (`PlanetSession.NEAR` más hacia donde va), y Minecraft decide tick a tick cuáles son. Si eso
llega tarde al juego, Mario sale caminando del suelo con colisión y cae. Llegaba tarde por:

1. **La colisión hacía fila detrás del resto.** Los chunks cuya colisión cambiaba se encolaban al
   final de `pending`, detrás de los miles de chunks visuales de un planeta que estaba llegando.
2. **Dolphin no frenaba al mod.** `HostBridge::Tick` vaciaba el ring completo en cada field a una
   cola sin límite; el juego consume 256 KB por frame. El ring nunca se llenaba, el mod mallaba sin
   parar, y un chunk con colisión nuevo esperaba detrás de segundos de chunks ya encolados.
3. **Un tick de Minecraft trabado no frenaba al juego.** Corriendo, Mario avanza ~20 bloques/s:
   medio segundo de Minecraft congelado basta para salir de la zona con colisión.

## Por qué subían los ticks al crear el planeta

- El mod mallaba hasta llenar 1 MB del ring por tick, sin límite de tiempo (decenas o cientos de
  chunks en un tick).
- `ShadowWorld` copiaba 4 columnas de 16×16×capas por tick a la dimensión sombra, con luz de
  cielo: decenas de miles de `setBlock` por tick durante segundos.
- El `VoxelPlanet` generado se armaba en el hilo del juego (recorre todas las celdas dos veces).

## Qué cambió (rama `perf/smooth-planets`)

**Mod (`fabric/`)**

- `PlanetSession` tiene dos carriles. El **urgente** lleva lo que Mario pisa: chunks cuya
  colisión cambia (`residency`), ediciones en chunks con colisión, el suelo donde aterriza un
  teletransporte y, después, el teletransporte mismo. Siempre sale primero, cueste lo que cueste.
  El **resto** (vista lejana, chunks sin colisión) solo se arma mientras `peek(bulk)` lo permite.
- `PlanetClient` le da a ese resto **6 ms por tick** (`-Dgalaxycraft.meshBudgetMs=…`) y lo frena
  mientras Dolphin tenga más de 256 KB nuestros sin tomar del ring (`BridgeClient.backlog()`).
- El planeta generado se construye en el hilo del generador. Antes, el hilo del juego calcula la
  info de cada bloque que usa (`McBlocks.info` es perezoso y lee los modelos de Minecraft).
- `ShadowWorld` copia a la dimensión sombra **fila por fila, 4 ms por tick del servidor** como
  máximo, en vez de 4 columnas enteras. Lo que un clic necesita ya (`mirrorNow`) se copia entero
  al momento, como antes.
- `PlanetSession.distance` ya no crea objetos (se llama para todos los chunks cada dos ticks).
- **Distancia de render.** La vista lejana (`PlanetLod.tile`) ahora va en baldosas de 4×4 columnas
  de chunks (hasta 16×16 por cara). Las baldosas a menos de **64 bloques** de Mario, o de donde va,
  se mandan como chunks; el resto, como su baldosa de vista lejana. Una baldosa que pasa a chunks
  manda primero sus chunks y después oculta su vista lejana. Una que vuelve a ser lejana muestra
  primero su vista lejana y después suelta sus chunks. Hay histéresis de 16 bloques
  (`RENDER_KEEP`). `-Dgalaxycraft.renderDistance=N` la cambia; `0` manda todos los chunks, como
  antes. `/galaxycraft status` muestra "N/M tiles near".

  | Planeta, Mario parado encima | Antes | Ahora |
  |---|---|---|
  | Radio 128 | todos sus chunks | 39 de 294 baldosas como chunks, 2,1 MB |
  | Radio 256 (plano) | 15.612 chunks, 47 MB, 6,2 s de mallado | 496 chunks + 983 baldosas, 2,1 MB, 0,4 s |

- El planeta generado también precalcula las esferas de sus chunks en el hilo del generador.

**Dolphin (`dolphin/galaxycraft/`)**

- `HostBridge` solo saca del ring mientras su cola para el inbox del juego tenga menos de
  **512 KB** (`INBOX_BACKLOG`). Lo demás espera en el ring, así el mod ve que el juego va atrás
  y frena el grueso del planeta. En un cambio de escena se descarta también lo que quedaba en el
  ring (era de la escena vieja: el mod reenvía todo al ver la nueva).
- **Red de seguridad:** jugando como Mario, si el heartbeat de Minecraft tiene más de **250 ms**,
  el juego espera a Minecraft como si el emulador tuviera un tirón, hasta **1,5 s** por tirón. Si
  Minecraft se cerró (heartbeat de más de 2 s) o el Wii Remote tiene el control, no espera.
  `GALAXYCRAFT_NO_WAIT=1` la apaga. `HostBridge::ModWaitMs()` acumula cuánto esperó.

**Módulo de SMG2 (`syati/`)**

- Hasta 6×16×16 partes de vista lejana por planeta (`FAR_VIEW_PARTS`, también
  `GXC_FAR_VIEW_PARTS` en el protocolo). El arreglo se reserva en el heap de la escena con la
  primera parte que llega. Se dibujan **las baldosas y los chunks a la vez**: ya no es "vista lejana
  o chunks según la distancia de la cámara", porque el mod decide qué baldosa es qué.
- **Memoria liberada a tiempo.** Los chunks reemplazados liberaban su memoria (display list y KCL)
  8 frames después para que el binder de Mario no lea un triángulo ya liberado. Pero solo había 64
  lugares: con 20 a 60 chunks reemplazados por frame mientras llega un planeta, se liberaba antes
  de tiempo KCL que Mario podía estar leyendo. Ahora es una cola FIFO de 2048 (`core/Graves`, con
  tests). `Graves::Early()` cuenta las veces que aun así se liberó antes de tiempo.

**Importante:** el mod y el módulo de SMG2 van juntos. Con el mod nuevo y el módulo viejo, el
juego rechaza las baldosas de índice ≥ 6 y dibuja vista lejana o chunks según la cámara: el
planeta se verá con huecos. Recompilar siempre con `syati/build.sh`.

## Probarlo

```sh
git fetch origin perf/smooth-planets && git checkout perf/smooth-planets
make -C protocol                     # layout del protocolo
syati/test.sh                        # núcleo del módulo (Graves, inbox)
dolphin/galaxycraft/test.sh          # bridge de Dolphin
(cd fabric && ./gradlew build test)  # el mod: compila PlanetClient y ShadowWorld por primera vez
syati/build.sh                       # el módulo para SMG2 (VoxelPlanet.cpp cambió)
dolphin/build.sh                     # Dolphin con el bridge nuevo
tools/gxplay.sh
```

Pruebas en el juego real (Dolphin de desarrollo, sin ventana):

```sh
tools/gxvoxel.sh                     # planeta, aterrizar, cavar y construir
tools/gxvoxel.sh lod                 # un planeta visto de lejos: lod-*.png
GXC_PERF_ARGS="-PperfRadius=128" tools/gxvoxel.sh perf   # cuánto cuesta al emulador
tools/gxvoxel.sh walk                # Mario camina
tools/gxfit.sh                       # Mario cabe en huecos de 1 bloque
```

Qué mirar con un planeta de radio 128 (generado y plano):

- Al crearlo, el tiempo de tick (F3) debería quedarse cerca de lo normal; el planeta aparece en
  algunos segundos más que antes, alrededor de Mario primero.
- `/galaxycraft status`: "N to send" debe bajar de forma pareja; "N/M tiles near" debe ser una
  fracción pequeña.
- Correr y saltar por el planeta mientras termina de llegar: no debería volver a perderse el suelo.
  Si Minecraft se traba, el juego se detiene un momento en vez de dejar caer a Mario.
- Caminar lejos: delante de Mario la vista lejana se reemplaza por chunks y detrás vuelve a ser
  vista lejana, sin huecos.
- Si algo se siente peor, `-Dgalaxycraft.meshBudgetMs=12` (más rápido, más carga por tick),
  `-Dgalaxycraft.renderDistance=96` (más detalle) o `0` (todo, como antes), o
  `GALAXYCRAFT_NO_WAIT=1` para comparar sin la red de seguridad.

## Para seguir en el computador (checklist para Claude Code)

Lo que no se pudo verificar en la sesión en la nube donde se hizo, porque la red no llegaba a
Fabric ni a Mojang y no había CodeWarrior ni juego:

1. **Compilar el mod** (`./gradlew build`). Los archivos que dependen de Minecraft y no se
   compilaron: `PlanetClient.java` (presupuesto por tick, `generate()` fuera del hilo, status) y
   `ShadowWorld.java` (`mirrorSome`/`mirrorRow`). Las clases puras sí pasan sus 187 tests
   (`fabric/src/test`).
2. **Compilar el módulo** (`syati/build.sh`). `VoxelPlanet.cpp` no se compiló: usa `gxc::Graves`
   (`core/Graves.*`, que sí compila y pasa sus tests con g++), `Planet::far` como puntero, y
   `DrawPlanet` sin el "o lo uno o lo otro".
3. **Correr los tests de juego** de arriba. Ojo con supuestos viejos:
   - `LodProbe` dibuja desde lejos con `/fly`, pero Mario se queda en el planeta, así que alrededor
     de él siguen los chunks. Lo esperable es `far_parts` > 0 y algunos chunks dibujados.
   - `PerfProbe` va a medir mucho menos para los planetas grandes; eso es lo buscado.
4. **Ajustar a ojo**: `RENDER` (64) y `RENDER_KEEP` (16) en `PlanetSession`, `MESH_BUDGET_NANOS`
   (6 ms) y `BULK_BACKLOG` (256 KB) en `PlanetClient`, `INBOX_BACKLOG` (512 KB), `MOD_STALL_MS`
   (250) y `MOD_WAIT_MAX_MS` (1500) en `HostBridge.h`, y `MIRROR_NANOS` (4 ms) en `ShadowWorld`.

Limitaciones conocidas:

- Mientras llegan los chunks de una baldosa, su vista lejana sigue dibujada debajo (se oculta
  cuando terminan): en un planeta plano puede verse un parpadeo (z-fighting) por unos ticks.
- Un chunk con colisión fuera de las baldosas cercanas (un salto o una caída muy rápidos) se dibuja
  sobre la vista lejana de su baldosa hasta que Mario llega o se va.
- La vista lejana de un planeta lejano ahora son decenas o cientos de display lists (una por
  baldosa) en vez de 6. Si eso le cuesta al emulador, se puede volver a usar caras enteras para
  los planetas sin detalle (`setDetail(false)`).

## Lo que sigue

1. **Guardado incremental.** Cada 200 ticks con cambios (el agua cambia seguido) se clona el
   planeta entero (27 MB en radio 128) para guardarlo; bastaría con los chunks cambiados.
2. **Escritura directa a secciones en la dimensión sombra** en vez de `setBlock`: entre 10 y 50
   veces más rápido, para que la copia inicial no tarde segundos.
3. **Slots de chunks del juego.** `NewSlots` reserva 44 bytes por chunk del planeta entero (unos
   5 MB en radio 256), aunque con la distancia de render se usan pocos: podría ser una tabla
   dispersa.
4. **Cargar planetas guardados fuera del hilo.** `enterStage` lee y descomprime el archivo (27 MB
   en radio 128) en el hilo del juego.
5. **Multijugador.** Necesita un servidor autoritativo, chunks con prioridad y control de flujo
   (lo de arriba) antes que nada: con dos simulaciones sin sincronizar, cada jugador más amplifica
   estos mismos problemas.
