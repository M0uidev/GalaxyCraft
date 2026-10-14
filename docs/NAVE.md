# La nave Toad (Starshroom)

La Starshroom, la nave roja con forma de hongo de la Brigada Toad, se puede construir y pilotar. Se
dibuja con el modelo original de SMG2 (lo copia el launcher desde tu propio disco).

## Cómo se hace

Todo con hierro y cosas de Minecraft; **no hace falta ningún logro** (los logros de `ship/` son solo
una ruta sugerida):

| Objeto | Receta |
|---|---|
| **Mesa de naves** | 8 lingotes de hierro alrededor de una mesa de crafteo |
| Casco | `I _ I / I I I` (hierro) |
| Motor | `I R I / I F I` (hierro, redstone, horno) |
| Timón | `_ I _ / I I I` (hierro) |
| Cabina | `G G G / G I G` (vidrio, hierro) |
| Núcleo Toad | `R S R / I N I / R S R` (redstone, hongo rojo, hierro, estrella del Nether) |

En la **mesa de naves** (clic derecho) se arma la nave con los módulos que lleves en el inventario:
usa el mejor de cada tipo y gasta uno de cada. Hacen falta al menos un **motor** y una **cabina**.
Sale un objeto *Nave Toad* que se coloca en el espacio o sobre un planeta, delante de ti.

## Módulos

Cada módulo tiene nivel 1 a 3. Por ahora lo único visible es la nave; los módulos cambian sus números:

- **Motor:** velocidad. **Timón:** giro. **Casco:** resistencia.
- **Cabina:** asientos (1, 1, 2 y 4) y almacenamiento.

## Pilotarla

- **Clic derecho** sobre la nave: subes y quedas sentado. La nave flota donde la llevas.
- **W / S** adelante y atrás hacia donde miras; **A / D** de lado; **Espacio** sube; **Z** baja (la tecla se cambia en Controles).
- **Ctrl** es el turbo: casi el doble de velocidad máxima.
- **Shift** te baja y te deja junto al casco.
- La nave es sólida: no se puede traspasar, ni por fuera ni caminando sobre ella.
- Al sostener una Nave Toad o una Plataforma en la mano se ve **dónde irá** (azul si se puede, rojo si no).
  Se pone sobre el suelo de un planeta o de una estación; en el espacio abierto la nave queda flotando delante.

## Plataformas de aterrizaje

**Plataforma de aterrizaje** (2): `I L I / I R I / S S S` (hierro, polvo de piedra luminosa, redstone, piedra).

- Se pone en el suelo, en un planeta o en una estación espacial. Es sólida.
- Volando, cualquier plataforma a hasta 90 bloques (y por encima de ti hasta 70) cuenta. Aparece un aviso
  con su número y distancia. **Clic derecho** a bordo elige la siguiente.
- Mantén **Z** (bajar): la nave va sola hasta la elegida (primero encima, luego baja frenando) y se acopla.
  **Espacio** despega.

## La pantalla de la nave

La nave tiene su propia pantalla, dibujada con arte propio en píxeles (los sprites salen de `tools/ship_ui_textures.py`
y están en `textures/gui/sprites/ship`). Se abre con **E** estando a bordo, o desde fuera **agachado (Shift) + clic
derecho** sobre la nave (sin Shift, el clic derecho sigue subiéndote). Tiene dos pestañas:

**Piezas.** Cuatro casillas: casco, motor, timón y cabina, cada una con sus tres puntos de nivel. Arrastra un módulo de tu
inventario a su casilla (o Shift + clic) y la nave lo usa de inmediato; el que había vuelve a tu inventario. El casco y el
timón se pueden sacar; el **motor y la cabina solo se cambian** (una nave sin ellos no vuela). La cabina no puede bajar a un
nivel que deje cofres sin bahía. Abajo se lee lo que la nave hace con esas piezas: velocidad (y con turbo), giro,
resistencia, asientos y bahías.

**Bodega.** 4 bahías en fila para cofres. El nivel de la **cabina** abre las bahías: nivel 1 una, nivel 2 dos, nivel 3 las
cuatro (las cerradas se ven rayadas).

- **Instalar un cofre:** arrastra un cofre de tu inventario a una bahía libre, o Shift + clic (uno por clic).
- **Cofre grande:** con dos cofres en las bahías 1-2 o 3-4, el botón **+** entre ellos los une en un cofre doble de 54
  espacios (ocupa las dos bahías); **-** los vuelve a separar, pero solo si está vacío.
- **Sacar un cofre:** arrástralo de vuelta; solo sale si está **vacío**, así no pierdes carga por accidente.
- **Qué cofre ves:** el botón *View* bajo cada bahía elige cuál se muestra; un cofre grande tiene dos páginas (el botón
  1/2 entre la cuadrícula y tu inventario).
- Los cofres se dibujan dentro de la nave, detrás de los asientos, a un tercio de su tamaño para que quepan en la cabina. La carga se guarda con la nave.

## El panel de vuelo

Al pilotar aparece una placa pequeña abajo a la derecha con la **velocidad** (bloques por segundo) y su medidor: la raya
dorada es la velocidad máxima, y pasada esa raya el medidor se vuelve naranja y aparece TURBO. Si estás acoplado dice
DOCKED. **Mantén I** (se puede cambiar en Controles) para ver el panel completo sobre la placa: los módulos con sus tres
puntos de nivel, asientos, resistencia y cuántos cofres lleva.

## Pendiente

- La pose sentada (de momento mantienes la pose de vuelo) y los cojines de la cabina por ajustar.
- Los módulos aún no cambian el aspecto de la nave.
- La posición y el tamaño de los cofres dentro del casco se ajustan en `CargoLayout` (no se ha mirado en el juego todavía).
- Los módulos de nivel 2 y 3 aún no se consiguen en supervivencia (solo hay recetas de nivel 1); la pantalla ya los admite.
- Equilibrar los niveles y los logros (te toca a ti).
- Habilidades: aterrizar, remolcar, saltos.
