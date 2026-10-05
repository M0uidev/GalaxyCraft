# Élitros: volar entre planetas

Los élitros de Minecraft funcionan en la galaxia: planear, salir de un planeta con cohetes, cruzar el
vacío y aterrizar de pie en otro planeta del mismo stage. Funciona igual en los tres modos de
movimiento (Mario, Minecraft y Mario a velocidades de Minecraft).

## Cómo se usa

1. Ponte unos **élitros** en el pecho y lleva **cohetes de fuegos artificiales** en la mano.
2. Salta y, en el aire, pulsa **Espacio** otra vez: se abren los élitros (en modo Mario, el primer
   salto es el de Mario y el segundo Espacio los abre).
3. **Clic derecho** con el cohete en la mano te impulsa hacia donde miras. Para salir de un planeta,
   sube primero (mirando hacia arriba) y, ya en el espacio, gira hacia el planeta al que vas.
4. Al entrar en la gravedad de otro planeta, la cámara se endereza sola en ~1,5 s y aterrizas de pie.

En supervivencia se gastan los cohetes y la durabilidad de los élitros, como en Minecraft.

## El espacio

- **Gravedad estilo Mario Galaxy:** cada planeta atrae dentro de su campo. Fuera de todos los campos
  (y sin suelo del stage 64 bloques por debajo) estás en el **vacío**: sin gravedad, conservas tu
  impulso y tu "arriba" no cambia.
- **Viento cósmico:** si te alejas más de 200 bloques del campo más cercano, un viento suave te frena
  y te devuelve (a 20 bloques/s como mucho). Nunca mueres por alejarte.
- **Sin élitros en el vacío:** una deriva suave te lleva siempre de vuelta al planeta más cercano
  (unos 6 bloques/s). Llegar desde el espacio no hace daño por caída.
- `/galaxycraft planet add` puede crear planetas también en el espacio.

## Cámara en tercera persona

En **Opciones → GalaxyCraft...** hay tres distancias: **Camera Distance** (caminando, 4 bloques),
**Camera Distance Gliding** (con los élitros abiertos cerca de un planeta, 6) y **Camera Distance in Space**
(en el espacio, 10). La cámara pasa de una a otra con una transición suave de medio segundo, y sigue
deteniéndose antes de las paredes.

## Mario durante el vuelo

Con los élitros abiertos manda la física de Minecraft, en cualquier modo. Mario va "sentado" en tu
posición. En modo Mario, fuera de la primera persona, se le ve con la pose de vuelo del Launch Star
(`SpaceFlyLoop`). Al aterrizar vuelve el modo que tengas elegido.

SMG2 mata a Mario si "cae" demasiado lejos de su último punto seguro (`Mario::doExtraServices`). Mientras
el mod lo lleva sentado (élitros, vagonetas, barcas) y 1,5 s después, el módulo desactiva esa comprobación
para que cruzar el vacío no cuente como caer al abismo.

## Cómo se comprueba

- Unidades: `cd fabric && ./gradlew test` (`CosmicWindTest`, giro del "arriba" en `GravityFrameTest`).
- En el juego real: `tools/gxvoxel.sh elytra` (`ElytraProbe`). Lleva al jugador **3000 bloques sobre el
  prólogo** (espacio vacío, solo planetas), crea dos planetas y vuela de uno a otro en modo Minecraft y en
  modo Mario. Comprueba el vacío, el "arriba" congelado, el aterrizaje de pie, que Mario sigue al jugador
  y sigue vivo, el gasto de cohetes y durabilidad, y que el viento te devuelve. Capturas: `elytra-*.png`.
- **No abras el juego mientras corre una prueba:** comparten el enlace en memoria (`/dev/shm`) y la
  prueba acaba hablando con tu juego.

## Límites conocidos

- En modo Minecraft, Steve se dibuja sin pose de planeo (Mario sí tiene la suya).
- En el aire sobre el suelo original de un stage que tenga gravedad propia, esa gravedad sigue mandando.

## Para las estaciones espaciales planas

El vacío y el viento trabajan con "cuerpos con gravedad" genéricos (`gravity/GravityBody`), no solo con
esferas: una plataforma plana solo tendrá que decir cuánto se aleja un punto de su campo.
