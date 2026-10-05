# Abrir GalaxyCraft: el menú de Minecraft, tus mundos como galaxias

Al abrir el juego se abre **Minecraft**: su pantalla de título, su lista de mundos y sus opciones.
Mientras tanto, Super Mario Galaxy 2 arranca solo por detrás: no ves su título ni la elección de
archivo. Cada mundo de Minecraft es una **galaxia**: al entrar apareces en el espacio vacío, sobre el
planeta de ese mundo, donde lo dejaste.

## Cómo se abre

```sh
dolphin/build.sh     # una vez (o tras cambiar Dolphin)
syati/build.sh       # una vez (o tras cambiar el módulo)
tools/gxplay.sh      # el juego
```

Se abre una sola ventana, la de Dolphin, con el menú de Minecraft encima. Arriba a la izquierda dice
cómo va SMG2: *starting...* mientras arranca (unos segundos) y *ready* cuando ya está en el espacio.
Puedes entrar a un mundo antes: el jugador espera y aparece sobre su planeta cuando SMG2 llega.

- **Singleplayer → Create New World:** el tipo de mundo ya viene como **GalaxyCraft** (vacío; el
  mundo son los planetas). Los comandos vienen activados (`/gamemode creative`, `/galaxycraft`).
- La primera vez que entras a un mundo se genera su **planeta de inicio** en el centro de la galaxia
  y recibes el kit (pico, bloques, cubetas). Sin daño por caída, como Mario.
- **Esc → Save and Quit to Title** vuelve al menú. SMG2 sigue esperando en el espacio, así que entrar
  a otro mundo es inmediato.

## El lugar donde se juega

**GalaxyCraftSpace** es una galaxia propia: solo el cielo de estrellas de SMG2, sin la nave, sin
enemigos y sin estrellas. Lo único sólido son tus planetas. Se arma desde tu disco cada vez que
compilas el módulo (`tools/space_galaxy.py`), así que el repo no lleva archivos del juego.

## Qué se guarda y dónde

Todo vive en `~/.local/share/galaxycraft/`:

| Carpeta | Qué guarda |
|---|---|
| `minecraft/saves/<mundo>/` | El mundo de Minecraft (inventario, etc.) y, en `galaxycraft/`, sus planetas y dónde estabas parado |
| `minecraft/options.txt` | Las opciones de Minecraft (teclas, video, sonido) |
| `minecraft/config/galaxycraft.properties` | Los ajustes de GalaxyCraft (movimiento, skin, cámara...) |
| `blueprints/` | Los planos de planetas, compartidos por todos los mundos |
| `dolphin/` | La configuración de Dolphin para GalaxyCraft y el archivo de guardado de SMG2 |

La primera vez, `dolphin/` copia la configuración de tu Dolphin normal (`~/.config/dolphin-emu`) y
tu archivo de SMG2 si existe. Si no hay ninguno, el juego crea uno solo.

Los ajustes se guardan en cuanto los cambias y siguen ahí la próxima vez.

## Controles

En los menús de Minecraft, el teclado y el mouse son de Minecraft (el puntero, los clics, escribir).
Dentro de un mundo son los de siempre (ver el README). El sonido de SMG2 se silencia mientras estás
en los menús.

## Cómo se prueba

- `tools/gxvoxel.sh boot`: SMG2 llega solo a GalaxyCraftSpace y Mario espera en el origen.
  `--fresh-nand` lo prueba sin archivo de guardado.
- `tools/gxvoxel.sh launch`: título sin Multiplayer, crear un mundo, el planeta de inicio, salir,
  volver a entrar y encontrar el planeta y el lugar como estaban.

No las corras mientras juegas: comparten la memoria del juego con `gxplay.sh`.

## Pendiente (siguiente etapa)

La pestaña de GalaxyCraft en "Create World": uno o varios planetas, diseñar el primero, reglas para
los demás, usar tus propios planos y ubicación semi-aleatoria.
