# Windows: cómo portear GalaxyCraft para que corra en Linux y en Windows

Investigación, sin cambios hechos todavía (2026-10-04). La meta es un solo código que funcione en
los dos sistemas. Dolphin y Minecraft siguen corriendo en la misma máquina: se hablan por memoria
compartida, así que Dolphin en un PC y Minecraft en otro sería otro proyecto (un transporte por
red).

En resumen: el mod de Fabric y el módulo de SMG2 casi no cambian. El trabajo está en el input del
parche de Dolphin, en la memoria compartida y en los scripts.

## Qué está atado a Linux

### 1. El input de Dolphin pasa por XInput2/X11 (lo más grande)

`dolphin/patches/0001-galaxycraft.patch` engancha `ControllerInterface/Xlib/XInput2.cpp`: de ahí
salen los deltas del mouse, el `XQueryKeymap`, el texto (`XkbLookupKeySym`) y la captura del
cursor (`XWarpPointer`, cursor transparente). En Windows ese backend no se compila.

- Hacer el mismo enganche en `ControllerInterface/DInput/DInputKeyboardMouse.cpp`, el backend de
  teclado y mouse de Windows, llamando a una función genérica: renombrar `GalaxyCraft::OnXInput2`
  a algo como `OnKeyboardMouse` y que reciba el mapa de teclas ya convertido a scancodes SDL.
- Teclas: los códigos DIK de DirectInput son los scancodes set 1 del PC, igual que los códigos
  evdev para las teclas normales (ESC = 1, A = 30, 1..0 = 2..11, F1..F10 = 59..68). Se puede
  reusar `EvdevToScancode` de `dolphin/galaxycraft/Input.cpp` y sumar una tabla chica para las
  extendidas, que sí difieren (`DIK_RCONTROL` = 0x9D, flechas, Insert/Delete, keypad Enter...).
- Texto para el chat: en Windows el foco de Qt funciona (los arreglos de `RenderWidget` y de
  `gxplay.sh` son por XWayland bajo Hyprland). Alcanza con `QKeyEvent::text()` en
  `RenderWidget::PassEventToGalaxyCraft`, o con `WM_CHAR`.
- Captura del mouse: `SetCursorPos` al centro de la ventana de render más `ClipCursor`. El
  `setCursor(Qt::BlankCursor)` del `RenderWidget` ya sirve tal cual.
- Botones: `X11ButtonsToSdl` tiene que tener su par para los botones de DirectInput.

### 2. La memoria compartida (`/dev/shm/galaxycraft_v1`)

No es `shm_open`: es un archivo común mapeado en memoria, y eso lo hace fácil.

- C++, `dolphin/galaxycraft/Shm.cpp`: `open`/`ftruncate`/`mmap`/`munmap` pasan, con
  `#ifdef _WIN32`, a `CreateFileW` + `SetFilePointerEx`/`SetEndOfFile` + `CreateFileMappingW` +
  `MapViewOfFile`/`UnmapViewOfFile`. Abrir con `FILE_ATTRIBUTE_TEMPORARY` para que el archivo
  quede en caché y no se escriba a disco. `HostBridge.cpp` usa `getpid()`, que pasa a
  `GetCurrentProcessId()`.
- Java, `fabric/src/main/java/dev/moui/galaxycraft/proto/Shm.java`: `FileChannel.map` ya funciona
  en Windows sin cambios.
- La ruta está fija en varios lugares: `proto/Layout.java` (`SHM_PATH`), `GalaxyCraft.cpp` del
  parche (`/dev/shm` + `GXC_SHM_NAME`, y `CTL_PATH`/`CTL_OUT_PATH`), `tools/gxproto.py`,
  `tools/gxshake.py` y `tools/gxdev.py`. Conviene un solo directorio: `/dev/shm` en Linux y
  `%TEMP%` en Windows, con una variable `GXC_SHM_DIR` para pisarlo.
- El heartbeat: Java escribe `System.nanoTime() / 1e6` y Dolphin compara con `steady_clock`, de
  procesos distintos. En Linux los dos son `CLOCK_MONOTONIC`. En Windows los dos deberían salir de
  `QueryPerformanceCounter`, pero no está confirmado: probarlo primero, y si no calzan, pasar a un
  reloj común (tiempo de pared, o un contador que avanza).

### 3. Compilar Dolphin en Windows

- `dolphin/build.sh` usa `cmake -G Ninja`. El build oficial de Dolphin en Windows es la solución
  de Visual Studio (`Source/dolphin-emu.sln`, con las fuentes listadas en `DolphinLib.props`), no
  CMake. No está verificado si CMake + MSVC compila en el commit fijado (`5390a61`).
- Si no compila, el parche necesita una segunda versión que agregue `Core/GalaxyCraft.cpp` y la
  librería `galaxycraft_host` a los `.props`/`.vcxproj`.
- `dolphin/galaxycraft/CMakeLists.txt` pone `-Wall -Wextra -Werror`, que MSVC no entiende:
  `if(MSVC)` usar `/W4 /WX`.
- `std::atomic_ref` existe en MSVC con C++20: sin problema.

### 4. El módulo de SMG2 (Syati): en Windows es más fácil

`mwcceppc.exe` y `Kamek.exe` son ejecutables de Windows que en Linux corren con wine; en Windows
corren directo. Lo que no corre es `syati/build.sh`, que es shell. Pasarlo a Python (que ya es
dependencia, por `tools/steve/build.py`) lo deja igual para los dos sistemas. Verificar que
`tools/steve/build.py` no dependa de nada de Linux al leer el disco.

### 5. Scripts y rutas

- `tools/gxplay.sh` usa bash, `wait -n`, `pkill` y `$HOME/.local/opt/jdk-25*`. Reescribirlo como
  `tools/gxplay.py`, con `subprocess` y el `.exe` según el sistema, deja un solo lanzador.
- Falta `fabric/gradlew.bat`: se genera con `gradle wrapper`.
- `PlanetClient.planetDir()` cae en `~/.local/share/galaxycraft/planets` si no hay
  `XDG_DATA_HOME`. En Windows debería ir a `%APPDATA%\galaxycraft\planets`.
- La ruta por defecto del juego (`~/Documents/Games/Dolphin Games`) sirve igual en Windows, o se
  usa `GXC_GAME`.

### 6. El harness de pruebas end to end (opcional)

`tools/gxdev.py` usa `os.mkfifo` para el control de Dolphin (los Pipes de Dolphin son solo de
Unix), `SIGKILL` y `start_new_session`. Los gametests lanzan `"python3"`, que en Windows suele ser
`python` o `py` (mejor usar el mismo intérprete que corre Gradle o una propiedad). Para jugar no
hace falta nada de esto: se puede seguir probando en Linux y portarlo al final.

## Orden propuesto

1. Una sola ruta de memoria compartida, configurable (Java, C++ y Python), y `Shm.cpp` con
   `#ifdef _WIN32`. No cambia nada en Linux.
2. Probar el heartbeat entre Java y Dolphin en Windows.
3. Backend de input DInput en el parche, con `OnXInput2` generalizado.
4. Compilar Dolphin en Windows: CMake + MSVC primero, si falla los `.props`.
5. `syati/build.sh` y `tools/gxplay.sh` en Python, y `gradlew.bat`.
6. Carpeta de planetas en `%APPDATA%`.
7. Si hace falta, el harness de pruebas.
