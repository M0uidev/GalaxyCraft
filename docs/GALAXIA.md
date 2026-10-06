# Tu galaxia: muchos planetas al crear un mundo

Al crear un mundo eliges cómo es su galaxia: cuántos planetas tiene, de qué tamaño y cuál es el
primero. La semilla del mundo los reparte por el espacio. Todos se ven desde cualquier punto de la
galaxia y van ganando detalle a medida que te acercas.

## Crear el mundo

**Singleplayer → Create New World → pestaña GalaxyCraft** (junto a Game, World y More):

| Opción | Qué hace |
|---|---|
| **Planets** | Cuántos planetas tiene la galaxia: de 1 a 64 (8 por defecto). |
| **Spacing** | La distancia entre planetas: *Near*, *Normal* o *Far* (24, 96 o 300 bloques entre sus gravedades). |
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

## Mundos de antes

Un mundo creado antes de esta función conserva sus planetas: la primera vez que entras se arma su
galaxia con los planetas que ya tenía, donde estaban. `/galaxycraft planet add` y el **Create** del
editor siguen agregando planetas, hasta 64 por galaxia.

## Dónde se guarda

En la carpeta del mundo, `saves/<mundo>/galaxycraft/`:

- `galaxy.json`: las opciones y la lista de planetas (dónde está cada uno, su radio, su bioma o plano, su
  semilla).
- `planets/`: los planetas visitados y cambiados, uno por archivo, como siempre.
- `player.json`: el planeta donde estás y en qué punto.

## Cómo se prueba

```sh
tools/gxvoxel.sh galaxy
```

`GalaxyProbe` abre Create World y revisa que exista la pestaña. Luego crea un mundo de 20 planetas y
comprueba que desde el primero haya 8 completos y 12 lejanos. Después lleva a Mario al planeta del
medio y al más lejano (en cada lugar saca una captura `galaxy-*.png` y anota la velocidad de Dolphin),
rompe un bloque allí, vuelve al primero y otra vez al más lejano: el bloque sigue roto. No abras el
juego mientras corre (comparten el canal con Dolphin).
