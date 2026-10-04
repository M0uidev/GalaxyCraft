# Árboles en planetas generados: enteros, como en Minecraft

Síntoma reportado: en los planetas generados los árboles se ven rotos, con troncos cortados,
copas con huecos y bloques que faltan.

## Qué se midió

`VegetationTest` planta árboles con forma de Minecraft (roble: tronco de 5, copa 5×5×2 + 3×3×2;
pino gigante 2×2 de 24; jungla gigante 2×2 de 28 con ramas) en planetas de radio 32, 64 y 128,
y cuenta bloques perdidos y pares de bloques vecinos en Minecraft que quedan separados en el
planeta. Antes del arreglo, en suelo plano:

| Árbol | Radio | Centro de cara | Junto a un borde | Junto a una esquina |
|---|---|---|---|---|
| roble (71) | 32 | – | 0 perdidos, 6 separados | 5 perdidos, 20 separados |
| pino gigante (644) | 32 | 260 perdidos | 147 perdidos | 122 perdidos, 108 separados |
| pino gigante (644) | 128 | 0 | 0 perdidos, 151 separados | 24 perdidos, 307 separados |
| jungla gigante (466) | 128 | 107 perdidos | 60 perdidos, 40 separados | 39 perdidos, 147 separados |

## Por qué se rompían

1. **Cada bloque se ubicaba por su cuenta en el espacio** (`cellAt` de un punto). Las celdas del
   planeta son angulares: más anchas arriba que en el suelo y estiradas cerca de los bordes de
   la cara. Dos bloques de una copa caían en la misma celda (uno se perdía) o se saltaban una
   (un hueco). Era la causa principal, peor en árboles altos y planetas chicos.
2. **Los árboles 2×2 eran 4 cosas sueltas.** `McVegetation` hacía una cosa por cada bloque sobre
   el suelo; un tronco 2×2 eran cuatro, cada una plantada a la altura de su propio suelo y con
   su propio sorteo: en terreno desparejo el árbol salía partido en cuartos desalineados.
3. **Los árboles salían transpuestos.** La dimensión sombra pone la celda (i, j, k) en x = j,
   y = k, z = i, pero el dx de Minecraft iba por i. Norte y oeste quedaban cambiados: lianas y
   cacao miraban al lado equivocado.

## Qué cambió (rama `fix/planet-trees`)

- `CubeSphere.cellBeyond`: la celda (i, j) de una cara, siguiendo más allá de su borde por las
  filas de la cara vecina (que calzan una a una). Más allá de dos bordes a la vez (alrededor de
  una esquina del cubo) devuelve -1: ahí se juntan tres caras y no hay lugar para una cuarta.
- `Vegetation.place` pone cada cosa en la grilla misma: x por j, z por i, y por las capas. Dentro
  de una cara y cruzando un borde, 0 bloques perdidos y 0 separados (prueba de regresión). El
  árbol se ensancha con la altura igual que todo lo construido ahí.
- Como Minecraft, que no hace crecer un árbol donde no cabe: se planta entero o no se planta.
  No se planta si llegaría más allá del cielo, si daría la vuelta a una esquina del cubo, o si
  algún tronco quedaría enterrado o colgando. Un tronco 2×2 puede tener una columna un bloque más
  abajo: su tierra rellena el escalón, como hace Minecraft. Las hojas siguen yendo solo al aire,
  así que un cerro corta la copa como en Minecraft.
- `Vegetation.things` (puro, con prueba): separa lo que creció en cosas. Troncos vecinos que
  suben más de un bloque son un solo árbol; flores y pasto quedan aparte; copas que se tocan se
  reparten por cercanía como antes.
- `McVegetation`: margen de captura 8 → 12 (los jungla gigantes y robles oscuros se podían
  cortar) y un WARN si algo creció contra las paredes o el techo de la zona de captura.

## Para seguir en el computador (checklist)

Compilación y pruebas (en esta máquina ya pasaron `./gradlew build` y `./gradlew test`):

```sh
git checkout fix/planet-trees
(cd fabric && ./gradlew build test)
tools/gxvoxel.sh                     # planeta, aterrizar, cavar y construir: sigue igual
tools/gxvoxel.sh perf                # el costo de un planeta generado no debería cambiar
```

En el juego (`tools/gxplay.sh`), con planetas generados de radio 64 y 128, plantas al 100%:

- [ ] Bosque (`minecraft:forest`): robles y abedules con copa completa, sin huecos ni bloques
      flotando. Ponerse al lado de uno y comparar con un árbol de un mundo normal de Minecraft.
- [ ] Taiga de pinos gigantes (`minecraft:old_growth_spruce_taiga`): troncos 2×2 enteros, nunca
      en cuartos desalineados; en cerros suaves algunos faltan (no cabían), ninguno cortado.
- [ ] Jungla (`minecraft:jungle`): árboles gigantes con ramas; lianas pegadas al tronco o a las
      hojas, no flotando a un costado; cacao pegado al tronco.
- [ ] Bosque oscuro (`minecraft:dark_forest`): robles oscuros y hongos gigantes enteros.
- [ ] Caminar sobre un borde de cara del cubo: los árboles que lo cruzan siguen enteros.
- [ ] Cerca de una esquina del cubo hay menos árboles grandes (los que no caben no se plantan).
- [ ] En el log: `Grew N things for <bioma>` por bioma, y ningún `grew ... against the edge of
      its room`. Si aparece, anotar el bioma: hay que subir `MARGIN` o `HEIGHT` en McVegetation.
- [ ] Radio 32: los árboles se ven más anchos arriba (las celdas crecen con la altura), pero
      enteros.

## Lo que sigue

- Un árbol que no cabe se pierde. Si cerca de las esquinas o en cerros quedan claros que se
  notan, probar con otra cosa del mismo parche en ese lugar en vez de nada.
- Lianas y otros bloques que dependen de un vecino pueden quedar sin soporte donde una copa se
  cortó contra un cerro; Minecraft tampoco los pondría ahí.
