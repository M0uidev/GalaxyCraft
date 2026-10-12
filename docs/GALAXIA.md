# Tu galaxia: muchos planetas al crear un mundo

Al crear un mundo eliges cómo es su galaxia: cuántos planetas tiene, de qué tamaño y cuál es el
primero. La semilla del mundo los reparte por el espacio. Todos se ven desde cualquier punto de la
galaxia y van ganando detalle a medida que te acercas.

## Crear el mundo

**Singleplayer → Create New World → pestaña GalaxyCraft** (junto a Game, World y More):

| Opción | Qué hace |
|---|---|
| **Planets** | Cuántos planetas tiene la galaxia: de 1 a 64 (8 por defecto). |
| **Spacing** | La distancia entre planetas: *Near*, *Normal* o *Far* (600, 1.600 o 2.400 bloques entre sus gravedades en mundos nuevos; los mundos de antes conservan 24, 96 o 300, o 150, 600 o 1.500). |
| **Others from / to** | El radio mínimo y máximo de los demás planetas, de 16 a 256 bloques. |
| **First planet** | El planeta donde empiezas: *Generated* (con un bioma) o *Blueprint* (uno de tus planos del editor). |
| **First's radius** | El radio del primero, si es generado (un plano mantiene su propio tamaño). |
| **Biome** | El bioma del primero: uno de la lista o *Random*. |
| **Blueprint** | Cuál de tus planos, si elegiste *Blueprint*. |

- La **semilla** es la de la pestaña *World*: con la misma semilla y las mismas opciones sale la misma
  galaxia.
- Los demás planetas son generados, cada uno con un bioma al azar.
- El primero queda en el centro de la galaxia y los demás alrededor, tan cerca como permita la
  separación elegida. Con 64 planetas grandes y separación *Far*, la galaxia mide unos 2.000 bloques
  de radio.
- Si un plano ya no existe al crear el mundo, el primer planeta se genera y un mensaje en el chat lo
  avisa. Si no caben todos los planetas pedidos, el chat dice cuántos entraron.

## Cerca y lejos

En el juego hay como máximo **8 planetas completos**: los más cercanos a Mario. Tienen gravedad y se
pueden pisar, y el más cercano tiene además sus bloques (chunks) para romper y poner. Los demás se
**dibujan desde lejos**, sin gravedad y con menos detalle cuanto más pequeños se ven (12, 6 o 3 parches
por cara del cubo).

- Al acercarte a un planeta lejano, se carga o se genera antes de que llegues y pasa a ser completo
  sin desaparecer de la vista.
- Al alejarte, vuelve a ser lejano. Si lo cambiaste, se guarda antes.
- Un planeta que nunca cambiaste no se guarda: la semilla lo vuelve a generar igual.
- Desde lejos, un planeta que todavía no visitaste se ve según su receta (su bioma, o la capa de
  arriba de su plano). Uno que ya visitaste se ve como lo dejaste.

## Niveles y estrellas

Cada sistema solar tiene un **nivel** (0 a 5) y un **tipo de estrella**, ambos derivados de la
semilla (no se guarda nada): la misma semilla da el mismo universo.

- El nivel sale del anillo de distancia al hogar, con un escalón de ruido (-1, 0 o +1): hasta 12.000
  bloques nivel 0; luego 20.000, 40.000, 70.000 y 110.000; más allá, nivel 5. El hogar es siempre
  nivel 0 y amarillo.
- Estrella: amarilla; enana roja y de lava desde el nivel 1; azul desde el nivel 3.
- **Warp Core**: un sistema que todavía no visitaste solo se abre si su nivel no pasa del alcance del
  Warp Core (núcleo 0 a 3: alcanza nivel 1, 2, 4 o 5). Los visitados y el hogar se abren siempre. Hasta
  que exista el módulo de la nave, el ajuste **Warp Core Tier** hace de núcleo (3 = sin límite, como
  hasta ahora).
- El **Pulse** solo funciona dentro del alcance de un sistema; entre sistemas queda apagado.
- Los sistemas visitados se anotan en `visited.json`, junto a los planetas de la galaxia.

Por ahora solo se puede hacer warp desde el mapa (K o N) y solo en modo creativo o con trucos
(cheats) activados; si no, el chat lo dice. Para probarlo en el juego: pon *Warp Core Tier* en 0,
abre el mapa y elige una estrella lejana (el chat dice su nivel y que tu Warp Core no alcanza); ponlo
en 3 y elígela otra vez: viaja. En el vacío entre
sistemas, planear con sprint ya no acelera el Pulse. Las etiquetas de las estrellas muestran
«tier N, tipo de estrella».

## Mundos de antes

Un mundo creado antes de esta función conserva sus planetas: la primera vez que entras se arma su
galaxia con los planetas que ya tenía, donde estaban. `/galaxycraft planet add` y el **Create** del
editor siguen agregando planetas, hasta 64 por galaxia.

## Dónde se guarda

En la carpeta del mundo, `saves/<mundo>/galaxycraft/`:

- `galaxy.json`: las opciones y la lista de planetas (dónde está cada uno, su radio, su bioma o plano, su
  semilla).
- `planets/`: los planetas visitados y cambiados, uno por archivo, como siempre, y `visited.json` (los sistemas donde estuviste).
- `player.json`: el planeta donde estás y en qué punto.

## Cómo se prueba

```sh
tools/gxvoxel.sh galaxy
```

`GalaxyProbe` abre Create World y revisa que exista la pestaña. Luego crea un mundo de 64 planetas (el máximo) y
comprueba que desde el primero haya 8 completos y 56 lejanos. Después lleva a Mario al planeta del
medio y al más lejano (en cada lugar saca una captura `galaxy-*.png` y anota la velocidad de Dolphin),
rompe un bloque allí, vuelve al primero y otra vez al más lejano: el bloque sigue roto. No abras el
juego mientras corre (comparten el canal con Dolphin).
