# GalaxyCraft — menú de pausa, movimiento de Minecraft y /skin

Fecha: 2026-10-05. Amplía `2026-10-02-galaxycraft-mario-mode-design.md` y
`2026-10-02-galaxycraft-steve-model-design.md`. Protocolo v10.

## 1. Intención

- **Esc abre el menú de pausa de Minecraft**, el de siempre (Volver al juego, Logros,
  Estadísticas, Opciones..., Guardar y salir), con dos botones de GalaxyCraft en el lugar de
  "Enviar comentarios" / "Reportar errores": **GalaxyCraft...** (sus ajustes) y **SMG2 Menu** (el
  botón + de siempre, el menú de pausa del propio Galaxy 2).
- **Dos movimientos**, que se cambian con **F6** o en los ajustes:
  - *Mario*: el de hoy. SMG2 mueve a Mario (saltos, giros, salto largo) y el jugador lo sigue;
    se dibuja el modelo de Mario con la skin de Steve.
  - *Minecraft*: Minecraft mueve al jugador con su física (caminar, correr con Ctrl, agacharse con
    Shift, salto de 1,25 bloques) sobre la colisión de la galaxia, y Mario va con él, escondido. Se
    dibuja a Steve con el modelo de jugador de Minecraft, dentro del juego (no superpuesto).
- **`/skin <cuenta>`** pone la skin de esa cuenta de Minecraft al personaje (los dos modelos);
  `/skin` sola vuelve a Steve. Se recuerda entre sesiones.

Lo que de Minecraft no se copia: las Opciones de Minecraft siguen siendo las suyas (FOV,
sensibilidad, controles, sonido...), así que GalaxyCraft no las duplica. Pausar el menú de
Minecraft no pausa SMG2 (Dolphin no compondría el menú encima de un juego pausado); el botón SMG2
Menu está para eso.

## 2. Ajustes modulares (`fabric/`)

- `settings/Setting`: un ajuste con clave, etiqueta, ayuda y valor por defecto; su clase dice cómo
  se muestra y se guarda: `Toggle` (botón ON/OFF), `Choice` (botón que recorre un enum), `Range`
  (deslizador con paso), `Text` (campo de una fila con "Apply"). Los oyentes (`onChange`) oyen cada
  cambio; cargar no es un cambio.
- `settings/Settings`: el registro, en orden, guardado en `config/galaxycraft.properties` en cada
  cambio. Las claves que la versión actual no conoce se conservan. Las ejecuciones de prueba
  (`galaxycraft.planetDir` o `repoRoot`) los tienen sólo en memoria.
- `client/GalaxyOptions`: **el único sitio donde se declaran** los ajustes (movimiento, skin,
  distancia de entidades, partículas) y las acciones (volar, ir al planeta, editor de planetas).
  Añadir una opción es una línea aquí: `GalaxySettingsScreen` la dibuja según su clase y
  `Settings` la guarda.
- `client/GalaxySettingsScreen`: dos columnas de botones de 150 como las Opciones de Minecraft,
  Done abajo. También `/galaxycraft settings`.
- `client/PauseMenu`: con la API de pantallas de Fabric, al abrirse `PauseScreen` sus botones de
  comentarios se cambian por los de GalaxyCraft (si no están, van arriba a la izquierda).

## 3. Esc y el botón + (Dolphin)

- Esc es de Minecraft con una pantalla suya abierta (la cierra) y mientras Mario se puede jugar
  (`Following && InGame`: abre la pausa); en los menús de SMG2 sigue siendo el +.
- `GXC_PLAYER_PLUS` (64): mientras el mod lo manda, Dolphin aprieta el +. SMG2 Menu cierra la
  pausa de Minecraft y lo manda un cuarto de segundo (tiempo real: el juego lo ve aunque los
  ticks de Minecraft vayan rápido o lento).

## 4. Movimiento de Minecraft

- `GXC_PLAYER_WALKING` (32). El mod deja de seguir a Mario: el marco de gravedad gira con la
  gravedad sin suavizar (la física de Minecraft camina sobre él) y el jugador cae y choca con la
  colisión que ya recibe de la galaxia (planetas incluidos).
- Mario va con el jugador: cada fotograma emulado el mod manda `GXC_MSG_SEAT` con los pies del
  jugador (el mismo asiento que los minecarts), así SMG2 sigue viendo a Mario donde está el jugador
  (monedas, enemigos, gravedad, cámara). Al volver al movimiento de Mario, un asiento con 0 lo suelta.
- Dolphin no le da el teclado a Mario (como con /fly) y no pide dibujarlo (`THIRD_PERSON` nunca con
  WALKING).
- Fuera de primera persona, `EntityClient` dibuja al jugador local con su propio renderer de
  Minecraft (el mismo camino que los mobs, `EntityCapture`), situado con el marco de gravedad en vez
  de con un planeta, así que anda, mira y sostiene lo que lleva como en Minecraft.

## 5. Skins

- `view/SkinImage`: lee las respuestas de Mojang (`api.mojang.com/users/profiles/minecraft/<nombre>`
  → id; `sessionserver.mojang.com/session/minecraft/profile/<id>` → propiedad `textures` en base64 →
  URL de la skin y si es "slim") y deja la imagen como la dibuja Minecraft (64x64; las de 64x32 con
  las extremidades izquierdas espejadas; la capa interior opaca).
- `client/SkinClient`: la busca en otro hilo, la guarda en `config/galaxycraft/skins/` (sin red usa
  la última copia) y la pone:
  - en Steve como entidad: el cuerpo del jugador usa esa textura en vez de la suya;
  - en el modelo de Mario: `GXC_MSG_MARIO_SKIN` (114) a Dolphin, una vez por skin y por Dolphin.
    `MarioSkin` (Dolphin) busca en la RAM del juego las secciones TEX1 de J3D con una textura RGB5A3
    de 64x64 llamada "steve" (la de `Mario.bdl` que hace `tools/steve/build.py`) y escribe la skin
    encima. Lo repite en cada escena nueva (hasta 5 intentos, uno por segundo).
- Limitación: el modelo de Mario es siempre de brazos anchos; una skin "slim" se ve con una franja
  de un píxel en los brazos. Steve como entidad usa el modelo que Minecraft le dio al jugador.

## 6. Pruebas

- `protocol/test_layout.c`: v10, los flags y el mensaje nuevos.
- Dolphin (`galaxycraft_host_tests`): `MarioSkin` encuentra sólo la textura correcta (formato,
  tamaño, nombre, secciones rotas); con WALKING Mario no se dibuja; PLUS se lee; la skin se escribe
  y se vuelve a escribir en una escena nueva.
- Mod: `SettingsTest` (validación, oyentes, guardar y leer, valores malos), `SkinImageTest`
  (respuestas de Mojang, skins antiguas), `ProtoTest` (flags nuevos).
- Por probar en el juego: el menú de pausa en la superposición, el + con SMG2 Menu, caminar con el
  movimiento de Minecraft por un planeta y por la galaxia, Steve como entidad, y la skin sobre el
  modelo de Mario (que J3D dibuje la textura desde el archivo cargado y no de una copia).
