# Windows: cómo portear GalaxyCraft para que corra en Linux y en Windows

La meta es un solo código que funcione en los dos sistemas. La investigación (2026-10-04) está más
abajo; lo hecho en la rama `feat/windows` (2026-10-07), lo que falta probar y cómo probarlo en
Windows están en [Estado del port](#estado-del-port-2026-10-07) y
[Probar en Windows](#probar-en-windows). Dolphin y Minecraft siguen corriendo en la misma máquina: se hablan por memoria
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
- `fabric/gradlew.bat` ya está (2026-10-07, con el lanzador).
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
5. `syati/build.sh` en Python. `tools/gxplay.sh` ya tiene su par multiplataforma: el lanzador
   (`launcher/`, 2026-10-07) hace lo mismo en Linux y en Windows, y CI lo prueba en los dos.
6. Carpeta de planetas en `%APPDATA%`.
7. Si hace falta, el harness de pruebas.

## Estado del port (2026-10-07)

Hecho en la rama `feat/windows`, en el orden de arriba. En Linux nada cambia: la memoria
compartida sigue en `/dev/shm/galaxycraft_v1`, el input sigue saliendo de XInput2 con los mismos
números, y CI sigue compilando y probando lo mismo que antes.

1. **Una sola carpeta de memoria compartida.** `GXC_SHM_DIR` la elige; sin ella, `/dev/shm` en
   Linux y la carpeta temporal en Windows (`GetTempPathW` en C++, `java.io.tmpdir` en Java, que en
   Windows es lo mismo; `TMP`/`TEMP` en Python). La usan `proto/Layout.java` (`SHM_DIR`,
   `SHM_PATH`), `gxc::ShmDir`/`ShmFile` en `dolphin/galaxycraft/Shm.cpp`, el parche
   (`galaxycraft_v1`, `galaxycraft_ctl`, `galaxycraft_ctl.out`) y `tools/gxproto.py` (y con él
   `gxshake.py` y `gxdev.py`). `Shm.cpp` mapea con `CreateFileW` (compartido lectura/escritura,
   `FILE_ATTRIBUTE_TEMPORARY`) + `CreateFileMappingW` + `MapViewOfFile`; el mapeo agranda el
   archivo y nunca lo achica (Windows no deja cambiar el tamaño de un archivo que Minecraft tiene
   mapeado). `HostBridge` escribe `GetCurrentProcessId()`. La librería del host ya no usa
   builtins de GCC y compila con MSVC (`/W4 /WX`, las mismas advertencias que Dolphin) y con
   MinGW; sus tests pasan en CI con MSVC y, a mano, con MinGW bajo Wine.
2. **El reloj del heartbeat es el mismo de los dos lados, comprobado.** En Windows,
   `System.nanoTime()` de HotSpot y `std::chrono::steady_clock` de MSVC son los dos
   `QueryPerformanceCounter` escalado a nanosegundos, sin desplazamiento. No hizo falta un reloj
   común: `tools/ci/ClockCheck.java` lanza `clock_probe` (C++, `steady_clock` como `NowMs` del
   parche) y comprueba que su hora cae entre la de Java antes y después; pasa en Linux y en
   Windows en cada CI. Además, `galaxycraft.log` anota la edad del primer heartbeat de cada
   Minecraft ("clocks agree" o "the two clocks disagree") y `ctl status` da
   `mod_heartbeat_age_ms`. `gxproto.now_ms` usa `perf_counter_ns` en Windows (el `monotonic` de
   Python no es `QueryPerformanceCounter` antes de 3.13).
3. **Input por DirectInput.** `GalaxyCraft::OnKeyboardMouse` es el enganche neutro (scancodes SDL,
   botones SDL, rueda en muescas, Escape aparte, puntero 0..1). `OnXInput2` adapta el estado de
   X11 como antes; `OnDInput` adapta el de DirectInput: códigos DIK con `gxc::DikToScancode` (hasta
   F12 son los mismos que evdev; las extendidas como Ctrl/Alt derechos, flechas, Insert/Delete,
   Inicio/Fin, RePág/AvPág por tabla: las mismas teclas que en Linux), botones con
   `DInputButtonsToSdl` (también atrás/adelante), rueda `lZ / 120`. `DInputKeyboardMouse` lo llama
   en cada actualización **solo con la ventana de Dolphin al frente** (con la entrada en segundo
   plano activada, si no, las teclas llegarían al juego mientras se escribe en otro programa) y,
   mientras el mouse está capturado, deja el cursor dentro de la ventana (`ClipCursor`) y lo centra
   (`SetCursorPos`); el `Qt::BlankCursor` del `RenderWidget` lo esconde. El texto del chat sale de
   `QKeyEvent::text()` en `RenderWidget` (solo en Windows; distribución, teclas muertas y AltGr
   aplicadas); los caracteres de control no son texto.
4. **Dolphin en Windows con CMake + MSVC**, en el commit fijado, sin tocar los `.props`:
   `dolphin/package-windows.sh` (Git Bash con el entorno de MSVC) compila con Ninja y el Qt que
   Dolphin trae en `Externals/Qt`, y arma `dist/dolphin-win32-x64/` (Dolphin.exe, DolphinTool.exe,
   Qt, `QtPlugins`, `Sys` copiado de verdad, el runtime de MSVC al lado, y comprueba con
   `dumpbin` que no falte ninguna DLL) y `dist/dolphin-win32-x64.tar.gz`. El commit fijado exige
   Visual Studio 2026 (MSVC 19.51, `Source/PCH/pch.h`), así que el job usa la imagen
   `windows-2025-vs2026`. `dolphin.yml` tiene ahora:
   - *Host library (Linux, Windows)*: tests de la librería y `ClockCheck`.
   - *Dolphin (Windows)*: compila y empaqueta; después **arranca el Dolphin empaquetado** con
     `GALAXYCRAFT=1`, video Null y un programa de GameCube mínimo (`tools/ci/make_dol.py`: un bucle
     infinito), y `tools/ci/ShmCheck.java` hace de mod: encuentra la memoria compartida donde la
     busca el mod (`Layout.SHM_PATH`, en `%TEMP%`), comprueba la firma, el pid de Dolphin y la edad
     del heartbeat de Dolphin con el reloj de Java, late como el mod y le pregunta a Dolphin por
     `ctl status` qué edad ve. También comprueba que `galaxycraft.log` se escribió.
   - Artefactos: `dolphin-win32-x64` (la carpeta: descomprimir y abrir Dolphin.exe),
     `dolphin-win32-x64-tar` (para releases), `windows-test-build` (ver abajo) y
     `dolphin-windows-boot-logs`.
   - `launcher.yml`: un release también lleva `dolphin-win32-x64-<v>.tar.gz` (el `game.json` ya lo
     anunciaba para `win32-x64`), y los tests del mod (`./gradlew test`) corren en Linux y Windows.
5. **`syati/build.py`** hace lo mismo que hacía `build.sh` (mismas fuentes en el mismo orden,
   mismas opciones y archivos); `build.sh` ahora solo lo llama. CodeWarrior y Kamek corren como
   los tiene el toolchain: en Linux el script `mwcceppc` (wine) y el Kamek de .NET, en Windows los
   `.exe`. `tools/hostexe.py` reúne lo que depende del sistema: SuperBMD con wine solo en Linux,
   dónde está DolphinTool, la imagen del juego por defecto; lo usan `tools/steve/build.py`,
   `tools/space_galaxy.py` y `pack-game`. Ojo: `release/module` se hizo con `build.sh`, así que el
   hash de fuentes cambió y el próximo `npm run release` necesita el disco (`--game=...`) para
   rehacer el módulo (lo hace solo).
6. **Planetas en `%APPDATA%\galaxycraft\planets`** en Windows (`PlanetStore.dataDir`, la misma
   carpeta de datos que usa el lanzador); en Linux igual que antes.
7. **Harness**: los gametests lanzan `python` en Windows (o lo que diga `-Dgalaxycraft.python`).
   `gxdev.py` sigue siendo solo para Linux (los Pipes de Dolphin son de Unix) y ahora lo dice en
   vez de fallar con `mkfifo`.

Para depurar, el lanzador ahora guarda su log (Lanzador, Dolphin y Minecraft juntos) en
`<datos>/logs/launcher.log` (el anterior queda en `launcher.old.log`; botón *Logs folder* en el
log), y Dolphin escribe `<usuario de Dolphin>/Logs/galaxycraft.log` en cada partida.

### Lo que no está probado

Nadie jugó en Windows todavía: no hay disco de SMG2 ni Windows donde se hizo esto. CI prueba que
compila, que arranca, que comparte la memoria con el lado de Java y que los relojes coinciden.
No prueba:

- El input de verdad: teclas, mouse, captura del cursor, texto del chat (CI no tiene teclado).
- SMG2 con el módulo y Minecraft juntos en Windows (PLAY completo), el overlay, el sonido.
- Video: CI arranca con el backend Null; en un PC real Dolphin usa D3D11/D3D12/Vulkan/OpenGL.
- `syati/build.py` en Windows (CI no tiene el toolchain ni el disco), ni que rehaga el módulo
  idéntico en Linux (debería: mismos comandos).
- El instalador del lanzador en un Windows sin el runtime de Visual C++ (el Dolphin empaquetado lo
  lleva al lado, así que no debería hacer falta).

## Probar en Windows

Desde lo que arma CI, sin Visual Studio. En la página de Actions de la rama `feat/windows`:

1. Del último run de **Launcher**, el artefacto `launcher-Windows`: instalar
   `SuperMinecraftGalaxy-Launcher-<v>-win-x64.exe` (SmartScreen: *Más información* > *Ejecutar de
   todas formas*).
2. Del último run de **Dolphin**, el artefacto `windows-test-build`: descomprimirlo en cualquier
   carpeta. Trae `game.json`, este Dolphin, el módulo y el mod, `PLAY-TEST.cmd` y `LEEME.txt`.
3. Cerrar el lanzador y abrir `PLAY-TEST.cmd`: abre el lanzador instalado con
   `GXL_GAME_MANIFEST` apuntando a ese `game.json` (en vez del último release de GitHub).
4. Iniciar sesión, elegir el Super Mario Galaxy 2 (USA), **INSTALL** (versión 0.0.1) y **PLAY**.

Lista de comprobación (anotar qué pasa en cada punto):

- [ ] **Instalar**: el lanzador instala 0.0.1 sin errores (rehace los archivos del disco).
- [ ] **PLAY**: abre Dolphin; el menú de Minecraft aparece en la ventana de Dolphin y SMG2 arranca
      detrás; entrar a un mundo hace el zoom desde el espacio.
- [ ] **Mirar con el mouse**: en el mundo, el mouse gira la vista, el cursor no se ve y no se sale
      de la ventana; al abrir el inventario (E) o el chat aparece el puntero y sirve para hacer
      clic; Alt+Tab a otro programa suelta el cursor y las teclas.
- [ ] **Teclas**: WASD, Espacio, Shift, Ctrl, Tab, 1–9, E, F5, F6, flechas; Esc abre el menú de
      pausa de Minecraft; clic izquierdo/derecho rompen y ponen bloques; la rueda cambia de casilla.
- [ ] **Chat**: T, escribir con tildes y ñ, `@` (AltGr), Retroceso, Enter.
- [ ] **Planetas guardados**: romper/poner bloques, salir del mundo y cerrar; al volver, siguen.
      (Se guardan con el mundo; `%APPDATA%\galaxycraft\planets` es la carpeta de planetas sueltos
      y planos.)

Logs para traer de vuelta a Linux (todos en `%APPDATA%\galaxycraft`, que se abre con
Win+R → `%APPDATA%\galaxycraft`):

| Log | Qué tiene |
|---|---|
| `logs\launcher.log` (y `logs\launcher.old.log`) | El lanzador, y la salida de Dolphin y Minecraft |
| `dolphin\Logs\galaxycraft.log` | El puente de Dolphin: la memoria compartida, el link, la edad del heartbeat ("clocks agree") |
| `minecraft\logs\latest.log` | Minecraft (si la instalación tiene su propia carpeta: `installations\<id>\logs\latest.log`) |

Si Dolphin solo no arranca: el artefacto `dolphin-win32-x64` es el mismo Dolphin suelto; abrir
`Dolphin.exe` y, si falla, mirar si Windows avisa de una DLL que falte.
