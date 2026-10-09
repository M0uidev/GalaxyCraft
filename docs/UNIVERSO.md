# El universo infinito: sistemas solares sin fin

La galaxia de tu mundo es solo el comienzo: alrededor hay sistemas solares sin fin, cada uno con sus
propios planetas, generados a partir de la semilla del mundo a medida que exploras. Los ves como
estrellas en el cielo, puedes volar hacia ellos o saltar de uno a otro con un warp.

## Los sistemas

- El espacio está dividido en sectores de 8.192 bloques. Más o menos 6 de cada 10 tienen un sistema
  solar con 2 a 12 planetas, de tamaños y biomas al azar.
- La galaxia que elegiste al crear el mundo es el **sistema de casa**, en el centro. Los demás nunca
  se acercan a menos de 512 bloques de ella, por grande que sea.
- Con la misma semilla sale el mismo universo. Un sistema que solo visitaste no se guarda: se vuelve a
  generar igual. Un planeta que cambiaste (un bloque roto o puesto) sí se guarda, en su propio archivo.
- Al acercarte a menos de 6.000 bloques de un sistema, sus planetas aparecen desde lejos; al entrar en
  él, los más cercanos se vuelven completos (se pisan y se construye en ellos).

## Las estrellas

Cada sistema que no está cerca (hasta unos 65.000 bloques) es un punto de luz en el cielo: más grande
cuantos más planetas tiene y cuanto más cerca está, y de un color propio (de rojizo a azul). Al
moverte, las estrellas cambian de lugar como lo harían de verdad.

## Viajar

| Cómo | Dónde | Qué hacer |
|---|---|---|
| **Élitros** | Dentro de un sistema | Como siempre: saltar en el aire con élitros y cohetes. |
| **Pulso** | En el vacío | Planeando con élitros fuera de toda gravedad, **mantén sprint** (Ctrl): aceleras hasta 400 bloques por segundo hacia donde miras. Frenas solo al acercarte a la gravedad de un planeta, o al soltar sprint. |
| **Warp** | A otro sistema | Apunta a una estrella y pulsa **K** (en el vacío, su nombre aparece sobre la barra). Sin estrella en la mira, K abre el **mapa de la galaxia**; también con **N**. El warp es gratis. |

- Un warp oscurece la pantalla, te deja en el primer planeta del sistema y la cámara baja desde el
  espacio como al entrar al mundo. Si estabas planeando, aterrizas de pie.
- En el vacío entre sistemas el viento cósmico ya no te devuelve a un planeta: puedes volar libre. Si
  te quedas sin élitros allí, te lleva despacio al sistema más cercano.
- Si sales del mundo estando en otro sistema, al volver apareces en el mismo planeta.

## Por qué no tiembla

Super Mario Galaxy 2 guarda las posiciones con números de 32 bits, que pierden precisión lejos del
centro: a cien mil bloques Mario y la cámara empezarían a temblar, y a un millón el suelo dejaría de
funcionar. Por eso el juego tiene un **origen flotante**: cuando entras en un sistema (o cada unos
3.000 bloques en el vacío) todo se mueve de golpe para que tú quedes cerca del centro de las
coordenadas del juego. No se nota: el movimiento es exacto. Minecraft guarda dónde estás en el
universo con números de 64 bits, así que no hay borde ni "Far Lands".

## Para probar (desarrollo)

- `tools/gxvoxel.sh universe`: la prueba de punta a punta (el origen a un millón de bloques, el vuelo
  por el vacío, otro sistema, estrellas, pulso, warp, salir y volver). Con un `-P` nuevo, añade
  `GXC_ARGS=--no-configuration-cache`. No la corras con `tools/gxplay.sh` abierto: comparten canal.
- `/galaxycraft origin x y z` fija el origen a esa distancia (bloques) para ver qué hacen los números
  lejanos; `/galaxycraft origin auto` lo suelta.
- `-Dgalaxycraft.endless=false`: solo la galaxia del mundo. `-Dgalaxycraft.floatingOrigin=false`: sin
  origen flotante.
- Diseño: `docs/superpowers/specs/2026-10-06-galaxycraft-infinite-universe-design.md`.
