# Más Minecraft en los planetas

Rama `feat/minecraft-feel` (2026-10-04). Todo se probó dentro del juego con el Dolphin de
desarrollo; las capturas quedan en `~/.local/share/galaxycraft-dev/ScreenShots/SB4E01/`.

## Qué cambió

**Colores por bioma.** Cada columna de un planeta generado guarda su bioma. El pasto, las hojas,
las enredaderas y el agua toman el color de su bioma, mezclado en 5 × 5 columnas como hace
Minecraft: un pantano se ve oliva y con agua turbia, una jungla verde intenso y un océano cálido
turquesa. Los planetas generados antes de este cambio no tienen biomas guardados y se ven como
llanura (*plains*); los nuevos ya los traen.

**Agua como en Minecraft.** Cada esquina de la superficie está a la altura que le da Minecraft
(promedio con los vecinos), así que un lago es una lámina plana y el agua que corre baja en
pendiente, con la textura de flujo orientada hacia donde corre. El agua es translúcida (se ve el
fondo) y no tiene caras entre bloques de agua.

**Mobs que aparecen solos, según la luz.** El mundo espejo usa los biomas del planeta, así que
aparecen los mobs de cada bioma. Una zona nueva trae sus animales al cargarse. De noche, o en
cuevas oscuras, salen monstruos a 24 bloques o más de Mario, con la densidad de Minecraft.
Los zombis y esqueletos se queman de día. Los slimes salen en los pantanos de noche, como en
Minecraft (y no en cualquier lado).

**Luz real.** Las cuevas están oscuras. Las antorchas, la lava y la glowstone iluminan con luz
cálida. Los techos dan sombra. El día y la noche cambian la luz del planeta (de noche se ve más
oscuro y azulado). Los mobs, los ítems tirados y las partículas también se oscurecen o se
iluminan según donde están.

**Texturas animadas.** El agua, la lava, el fuego, los portales, las linternas de mar y unas 50
texturas más se mueven como en Minecraft.

**Partículas ambientales.** Cerca de Mario, los bloques sueltan sus partículas: llama y humo de
antorchas, humo de fogatas, chispas de lava, hojas que caen, partículas de portal.

## El mundo del demo (`tools/gxplay.sh`)

El mundo de prueba de Fabric viene con los mobs y el tiempo apagados; el demo ahora los prende:
los mobs aparecen solos, pasan los días, cambia el clima y la dificultad es *normal* (con hambre
y monstruos, como en Minecraft). En el mundo propio del jugador oculto no aparecen monstruos.
Se puede cambiar:

- `/difficulty peaceful` en el juego, o `-Dgalaxycraft.difficulty=peaceful`: sin monstruos.
- `-Dgalaxycraft.dayCycle=false`: siempre mediodía (salen monstruos solo en lugares oscuros).
- `-Dgalaxycraft.animals=false`: las zonas nuevas no traen animales.

## Para revisar en el juego

1. Generar un planeta con varios biomas y agua (editor `/galaxycraft`, modo Generated, tamaño
   de bioma > 0, agua activada): los biomas se distinguen por color y el agua se ve lisa y
   translúcida, moviéndose.
2. Cavar un hoyo de 3 de profundidad y taparlo: adentro queda oscuro; una antorcha lo ilumina.
3. Esperar la noche (o `/time set night`): el planeta se oscurece y salen monstruos lejos de
   Mario; al amanecer los zombis se queman.

## Pruebas

- `tools/gxvoxel.sh biomes`: planetas con biomas y agua, orillas, la luz (caja oscura, antorcha,
  medianoche) y que el agua se anime (`biomes-*.png`).
- `./gradlew runClientGameTest -PgalaxycraftSpawn` (sin Dolphin): bioma en el mundo espejo,
  animales, ningún monstruo de día en llanura, monstruos de noche, llamas de una antorcha.
- `./gradlew test`, `syati/test.sh`.
- Rendimiento: igual que antes (PerfProbe "gen full": ~210 % antes y después).

## Otros arreglos

- El teletransporte (P) manda la dirección donde se midió el suelo: Mario aterriza ahí y no
  dentro de un cerro si se movió mientras tanto.
