# Fase 3: el módulo Syati dentro de SMG2

`syati/` es un módulo de código que corre dentro de Super Mario Galaxy 2 (SB4E). Hace tres cosas:

- Publica el buzón `GXCRMBX1` (`protocol/galaxycraft_protocol.h`): la gravedad, la posición de
  Mario y las 64 partes de colisión más cercanas (a menos de 3000 u).
- Mientras el host pone `DRIVE`, Mario pasa a ser una marioneta oculta que sigue la pose del mod.
- La cámara del juego pasa a mirar desde los ojos de esa pose.

El fork de Dolphin lee el buzón (`dolphin/galaxycraft/HostBridge`) y lo pasa al mod de Minecraft.

## Toolchain

Vive fuera del repo, en `~/.local/opt/gxc-toolchain/` (o en `$GXC_TOOLCHAIN`):

- `Syati/` (rama `main`) con `deps/CodeWarrior/` (CodeWarrior PPC EABI 4.3.0.172, `Wii/1.3` de
  decomp.dev; `mwcceppc` es un script que lo ejecuta con wine) y `deps/Kamek/Kamek.exe`
  (enlace a `Kamek/Kamek`, Kamek 2.0 compilado con .NET 10).
- wine 11 y python3.

`syati/build.sh` falla con un mensaje claro si falta alguna pieza.

## Compilar y testear

```sh
syati/test.sh                 # g++: lógica pura de src/core (partes, matriz de vista, KCL)
dolphin/galaxycraft/test.sh   # host: puente, normalización de KCL, canal de control
syati/build.sh                # módulo + loader -> syati/build/{CustomCode/,galaxycraft.xml,.json}
dolphin/build.sh              # Dolphin parcheado (ninja -C dolphin/build dolphin-emu-nogui también)
```

El juego llega sin tocar el disco: `syati/build/galaxycraft.json` es un *game mod descriptor* de
Dolphin. Apunta al `.rvz` (`$GXC_GAME`, o el de `~/Documents/Games/Dolphin Games`) y aplica
`galaxycraft.xml` por Riivolution, que contiene el loader de Syati y la carpeta `CustomCode`.
Para jugarlo a mano: `GALAXYCRAFT=1 dolphin/build/Binaries/dolphin-emu -e syati/build/galaxycraft.json`.

## Arnés de desarrollo

`tools/gxdev.py` arranca Dolphin en modo headless con su propio directorio de usuario,
`~/.local/share/galaxycraft-dev`. Ese directorio se crea con la configuración de
`tools/dolphin-dev/` cada vez que arranca. El arnés nunca toca `~/.config/dolphin-emu` ni la
pantalla.

```sh
tools/gxdev.py start [--speed 0]      # 0 = sin límite de velocidad (menús y cinemáticas)
tools/gxdev.py ctl "mbx; shot foo"    # imprime el buzón; captura en ScreenShots/SB4E01/foo.png
tools/gxdev.py press A                # pulsa y suelta un botón del Wiimote
tools/gxdev.py stick 1 0 2            # stick del Nunchuk a la derecha durante 2 s
tools/gxdev.py point 0.28 -0.6        # puntero IR (-1..1, y hacia arriba)
tools/gxdev.py stop
```

Mapa de controles (FIFO `Pipes/gxpad`):

| Botón del pipe | Control del juego |
|---|---|
| `A`, `B` | A, B |
| `START` | + |
| `L` | − |
| `R` | Home |
| `Y` | agitar (giro) |
| `X` | C |
| `Z` | Z |
| stick `MAIN` | Nunchuk |
| stick `C` | puntero IR |

Los comandos de `ctl` los ejecuta Dolphin (`dolphin/galaxycraft/DevControl.h`):

| Comando | Qué hace |
|---|---|
| `peek ADDR LEN` | Volcado hexadecimal de memoria. |
| `mbx` | Resumen del buzón: `at=` es su dirección y `flags=` es `game_flags/host_flags`. |
| `shot NAME` | Captura de pantalla. |
| `save PATH` / `load PATH` | Guarda o carga un savestate. |
| `drive X Y Z LX LY LZ [UX UY UZ]` | Hace de mod falso: activa la marioneta en esa pose. Sin `up`, usa la opuesta a la gravedad. |
| `undrive` | Vuelve a lo que diga el mod. |

Justo después del buzón (`at + 0xFD0`, `sizeof(GxcMailbox)`) el módulo deja palabras de depuración (la 14 apunta a los contadores del planeta de bloques, ver `docs/PHASE5.md`):

| Palabra | Contenido |
|---|---|
| 0 | `CollisionDirector` |
| 1 | keeper de Map |
| 2 | número de zonas |
| 3 | candidatas |
| 4 | KCL rechazados |
| 5 | primera parte |
| 6 | su KCL |
| 7 | si está siendo manejado |

Los savestates guardan toda la RAM, también el código del módulo. Después de recompilar,
un savestate viejo trae el módulo viejo. Para el código nuevo hay que arrancar desde cero:

1. Título: `pad "PRESS A; PRESS B"`.
2. Partida 2: `point 0 0` y A.
3. Start: `point 0.52 -0.78` y A.
4. Unas cuantas A para pasar el cuento.

## Verificación en vivo (2026-10-02)

En el escenario del cuento inicial, que ya es jugable:

- `mbx`: `grav=(0,-1,0)`, `anchor` sigue a Mario y `parts=4..16`. En la zona del castillo hay 59
  partes en 4 zonas y ningún KCL rechazado.
- La cabecera del KCL en RAM son punteros absolutos (MEM2) y el host los vuelve offsets.
- `drive … 0 0 -1` y luego `… 1 0 0`: la cámara mira desde la pose en cada dirección y Mario no se ve.
- `undrive`: Mario reaparece y responde al stick.

Todavía no se comprobó la gravedad hacia un planeta esférico (Sky Station).

## Datos de SB4E que no están en Syati

- `NameObj` mide 0x14 bytes en SMG2, así que los campos de `CollisionCategorizedKeeper` están 8
  bytes más allá que en Petari/Syati:
  - zonas en +0x20 y su número en +0xA0;
  - `CollisionDirector`+0x14 apunta al array de keepers.
  - `CollisionZone` y `CollisionParts` sí coinciden.
- `_savegpr_14`/`_restgpr_14` faltan en `symbols/SB4E.txt`; están en `syati/symbols_extra.txt`.
- Kamek no ejecuta constructores estáticos: los globales del módulo sólo pueden tener
  inicializadores constantes (nada de referencias a otros globales).
