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

**Dolphin (`dolphin/galaxycraft/`)**

- `HostBridge` solo saca del ring mientras su cola para el inbox del juego tenga menos de
  **512 KB** (`INBOX_BACKLOG`). Lo demás espera en el ring, así el mod ve que el juego va atrás
  y frena el grueso del planeta. En un cambio de escena se descarta también lo que quedaba en el
  ring (era de la escena vieja: el mod reenvía todo al ver la nueva).
- **Red de seguridad:** jugando como Mario, si el heartbeat de Minecraft tiene más de **250 ms**,
  el juego espera a Minecraft como si el emulador tuviera un tirón, hasta **1,5 s** por tirón. Si
  Minecraft se cerró (heartbeat de más de 2 s) o el Wii Remote tiene el control, no espera.
  `GALAXYCRAFT_NO_WAIT=1` la apaga. `HostBridge::ModWaitMs()` acumula cuánto esperó.

No hay cambios en el módulo de SMG2 (`syati/`) ni en el protocolo.

## Probarlo

```sh
git fetch origin perf/smooth-planets && git checkout perf/smooth-planets
dolphin/build.sh                     # recompila el bridge (HostBridge.cpp)
dolphin/galaxycraft/test.sh          # tests del bridge
(cd fabric && ./gradlew test)        # tests del mod
tools/gxplay.sh
```

Qué mirar con un planeta de radio 128 (generado y plano):

- Al crearlo, el tiempo de tick (F3) debería quedarse cerca de lo normal; el planeta aparece en
  algunos segundos más que antes, alrededor de Mario primero.
- `/galaxycraft status` muestra "N to send": debe bajar de forma pareja.
- Correr y saltar por el planeta mientras termina de llegar: no debería volver a perderse el suelo.
  Si Minecraft se traba, el juego se detiene un momento en vez de dejar caer a Mario.
- Si algo se siente peor, `-Dgalaxycraft.meshBudgetMs=12` (más rápido, más carga por tick) o
  `GALAXYCRAFT_NO_WAIT=1` para comparar sin la red de seguridad.

Los cambios en `PlanetClient` y `ShadowWorld` dependen de Minecraft y no se pudieron compilar en
la sesión donde se hicieron (sin acceso a los repositorios de Fabric/Mojang). Los compila el
primer `./gradlew build`.

## Lo que sigue

1. **Distancia de render para el planeta en foco.** Hoy se envían todos sus chunks visibles,
   también los del otro lado: para radio 128 o más son decenas de MB, cerca del total de MEM2 del
   Wii (`alloc_failed` en las estadísticas del módulo lo muestra). Solo los chunks a menos de
   64–96 bloques de Mario deberían ir con detalle y el resto dibujarse con la vista lejana
   (`PlanetLod`) partida en parches por cara. Es lo que hace viables los radios 128–256.
2. **Guardado incremental.** Cada 200 ticks con cambios (el agua cambia seguido) se clona el
   planeta entero (27 MB en radio 128) para guardarlo; bastaría con los chunks cambiados.
3. **Escritura directa a secciones en la dimensión sombra** en vez de `setBlock`: entre 10 y 50
   veces más rápido, para que la copia inicial no tarde segundos.
4. **Multijugador.** Necesita un servidor autoritativo, chunks con prioridad y control de flujo
   (lo de arriba) antes que nada: con dos simulaciones sin sincronizar, cada jugador más amplifica
   estos mismos problemas.
