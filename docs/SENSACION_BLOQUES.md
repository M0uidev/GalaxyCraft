# Romper y poner bloques como en Minecraft

Rama `game-feel` (2026-10-06). Diseño: [specs/2026-10-06-galaxycraft-game-feel-design.md](superpowers/specs/2026-10-06-galaxycraft-game-feel-design.md).

## Qué cambia en el juego

- **Romper manteniendo el botón.** Con algo en la mano, mantener el clic izquierdo sobre un bloque
  lo va rompiendo a la velocidad real de Minecraft: la dureza del bloque, la herramienta, si es la
  correcta, Eficiencia, Prisa minera y Fatiga minera. Sin soltar, rompe un bloque tras otro (5 ticks
  de pausa entre bloques, como Minecraft). El pasto, las flores y todo lo que se rompe al instante
  cae uno por tick. En creativo rompe uno cada 5 o 6 ticks.
- **Lo de Mario Galaxy:** romper en el aire no es 5 veces más lento (Minecraft sí lo hace), porque
  Mario salta y gira todo el rato.
- **Grietas.** Las texturas `destroy_stage_0..9` de Minecraft aparecen sobre el bloque y crecen a
  medida que se rompe. Si mueves la mira, cambias de herramienta o sueltas, se borran.
- **Sonidos.** Cada bloque suena con sus propios sonidos de Minecraft: el golpe cada 4 ticks, la
  rotura y al colocarlo. Suenan desde donde está el bloque.
- **Partículas.** Mientras golpeas, saltan trocitos del material desde la cara golpeada (como en
  Minecraft). Al romperse salen los pedazos de siempre, y al colocar un bloque salen unos pocos
  trocitos de él (eso es propio de GalaxyCraft: Minecraft no lo hace).

- **El borde del bloque con su forma real.** El borde oscuro que marca el bloque al que apuntas
  ya existía, pero era una caja alrededor del bloque. Ahora sigue la forma de verdad, como en
  Minecraft: la L de una escalera, el poste y las barras de una valla, una antorcha delgada. No
  dibuja líneas a través de una cara. Sale, como antes, cuando un clic haría algo con el bloque:
  con algo en la mano, o con la mano vacía sobre puertas, palancas, cofres, etc.

## Probado en el juego (2026-10-06)

`tools/gxvoxel.sh mining` (MiningProbe) lo juega en el Dolphin de desarrollo: mantiene el clic
izquierdo con un pico de madera mirando al suelo y cava un pozo.

- Rompe un bloque tras otro sin soltar: pasto 17 ticks, tierra 22 (15 de Minecraft + 5 de pausa +
  2 hasta que la sombra lo quita), piedra 30 (23 + 5 + 2). Los tiempos de Minecraft.
- Las grietas llegan a la etapa 8 y se borran al soltar. Saltan trocitos.
- Suenan el golpe y la rotura de cada bloque (pasto, tierra con el sonido de grava, piedra).
- El borde de unas escaleras sigue su forma (18 aristas) y se ve en el juego.

Lo que hubo que arreglar al traerlo de la nube:

- `LivingEntity.swing` pide en 26.3 la animación del objeto: `swing(mano, objeto.getAttackAnimation(), false)`,
  como lo llama Minecraft.
- Las grietas aclaraban todo el bloque: los huecos de `destroy_stage_N` son blancos con alfa 1/255 y
  RGB5A3 lo redondea a 1/7, que pasaba la prueba de alfa del juego. Ahora esos texeles quedan en 0
  al cargar las grietas.
- El borde no se dibujaba nunca (tampoco en `master`): `DrawOutline` no fijaba el número de etapas
  TEV y heredaba las dos de la luz de los chunks. Solo se veía mientras había grietas, porque
  `DrawCrack` deja una.
- `ctl keys lmb` del Dolphin de desarrollo ahora también llega a Minecraft (los botones del ratón),
  para que una prueba pueda mantener el clic.

## Preguntas abiertas

- **Romper con la mano vacía** (golpear madera) sigue sin poderse, porque con la mano vacía los
  clics son de Mario (el giro). ¿Se quiere poder romper con la mano?
- Las grietas se dibujan sobre la caja del contorno del bloque, no sobre su modelo. En losas y
  escaleras se ven sobre la caja.
