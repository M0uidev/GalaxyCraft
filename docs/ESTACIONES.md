# Estaciones espaciales: Minecraft plano en el espacio

Una estación es una plataforma **plana** que flota en el espacio. Sirve para jugar a Minecraft "de
siempre" (granjas, construcciones, redstone) sin la curvatura de un planeta. Todo lo que funciona en un
planeta funciona en ella: cultivos, cofres, animales, agua, luz. Su gravedad tira siempre hacia "abajo"
de la plataforma, como en Minecraft.

## Cómo se hace

**Núcleo de estación** (Station Core), en la mesa de crafteo:

```
Hierro  Vidrio  Hierro         (bloques de hierro)
Vidrio  Perla   Vidrio         (perla de ender en el centro)
Hierro  Vidrio  Hierro
```

## Ponerla

1. Sal al **espacio**, lejos de los planetas (al menos 16 bloques fuera de su gravedad).
2. Con el núcleo en la mano, **clic derecho**: aparece delante de ti una losa de piedra lisa de 9 × 9
   con el núcleo en el centro, orientada hacia donde miras.
3. Aterriza encima (o pulsa **P** cerca de ella) y construye.

Si no se puede, un mensaje dice por qué: demasiado cerca de un planeta u otra estación, o ya hay
demasiadas estaciones cerca (8 como mucho a la vez).

## Crece mientras construyes

La estación no tiene un tamaño fijo: cada bloque que pones en su borde la amplía. El tamaño máximo es
**256 × 256** de ancho y de **48 bloques por debajo a 79 por encima** de la losa. Un bloque fuera de
ese límite no se queda: vuelve a tu inventario con el aviso "The station can't grow further".

Fuera del borde estás en el vacío: sin gravedad, y el viento cósmico te trae de vuelta, como entre
planetas (ver `ELITROS.md`).

## El núcleo y su menú

- El núcleo **no se rompe**: sostiene la estación.
- **Clic derecho** sobre él abre su menú: el nombre (cámbialo con *Rename*), el tamaño y el número de
  bloques, y **Pack up**.
- **Pack up** recoge la estación entera en un objeto, **Packed Station**, con su nombre, tamaño y
  bloques en la descripción. Con él en la mano, **clic derecho** en el espacio la despliega de nuevo,
  orientada hacia donde miras. Vuelve con todo: cultivos, cofres con lo que tenían y animales.

Las estaciones se guardan con el mundo, y siguen ahí al volver. Solo están "vivas" (en el juego) cuando
estás a menos de 2000 bloques.

## Comandos

- `/galaxycraft station list`: todas las estaciones del mundo, con su nombre, dónde están (o
  "packed") y sus bloques.
- `/galaxycraft station restore <id>`: si perdiste el objeto de una estación recogida, te da otro. Si
  la estación está puesta, te dice dónde.

## Cómo se comprueba

- Unidades: `cd fabric && ./gradlew test` (`FlatGridTest`, `StationShapeTest`, `StationTest`,
  `FlatPlanetTest`, `StationShadowTest`) y `syati/test.sh` (la matriz de la gravedad de caja).
- Sin el juego: `./gradlew runClientGameTest -PgalaxycraftStation` (`StationProbe`): núcleo, trigo que
  crece, cofre con diamantes, crecimiento, núcleo irrompible, límite, recoger y desplegar a 300 bloques.
- En el juego real: `tools/gxvoxel.sh station` (`StationDolphinProbe`), 3000 bloques sobre el prólogo:
  la pone, la hace crecer, Mario aterriza y se queda de pie, fuera del borde es el vacío, desde arriba
  se cae sobre ella, se ve de lejos y al recogerla (con Mario encima) el juego la quita. Capturas:
  `station-*.png`.
- **No abras el juego mientras corre una prueba** (comparten `/dev/shm`).
