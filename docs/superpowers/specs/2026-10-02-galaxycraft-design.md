# GalaxyCraft — Diseño

> Jugar Super Mario Galaxy 2 (Dolphin) *siendo* un jugador de Minecraft: física real de Minecraft,
> mano y hotbar de Minecraft, caminando por los planetas de Galaxy 2 con su gravedad.

Estado: v0.1 · 2026-10-02 · patrón copiado de [SkyCraft](https://github.com/chasmlol/SkyCraft) (Skyrim + Minecraft).

## 1. Principio

**Ningún juego se reescribe.** Minecraft ejecuta su lógica vanilla (movimiento, colisión, salto,
sprint, caída, render de mano/GUI). Galaxy 2 ejecuta su mundo (planetas, gravedad, enemigos,
cámara de niveles). Los mods solo **traducen**:

- Galaxy 2 le dice a Minecraft *qué forma tiene el mundo* (colisión) y *hacia dónde cae* (gravedad).
- Minecraft le dice a Galaxy 2 *dónde está el jugador* y *desde dónde mirar* (cámara), y *qué dibujar encima*.

Si se reimplementa una mecánica de un juego dentro del otro, el diseño está mal. Excepción
aceptada: el stub de pruebas `fake_galaxy.py` simula una gravedad esférica simple.

## 2. Alcance del MVP (decisiones del 2026-10-02)

| Decisión | Valor |
|---|---|
| Autoridad del movimiento | **Minecraft** (Mario es marioneta oculta) |
| Primer jugable | **Caminar con gravedad** en un solo nivel; mano + hotbar compuestas. Sin bloques ni combate |
| Cámara | **Primera persona de Minecraft** sobrescribe la cámara de SMG2 |
| Enfoque | **Buzón Syati + fork de Dolphin + colisión KCL en el host** |

Fuera de alcance del MVP: poner/romper bloques, combate con enemigos, recoger estrellas, tercera
persona, multijugador, interoperabilidad GPU (se usa ruta CPU), Windows/macOS.

## 3. Componentes

```
┌──── SMG2 (PPC emulado) ────┐   ┌──── dolphin-galaxycraft (host) ────┐   ┌──── Minecraft 26.3 + Fabric ────┐
│ galaxycraft Syati module   │   │ GalaxyBridge                        │   │ galaxycraft (Fabric mod)        │
│  Mailbox (struct en RAM) ◀─┼──▶│  busca magic, copia buzón ⇄ shm ◀───┼──▶│  BridgeClient (shm)             │
│  GravityQuery  calcGravity │   │  KCL bytes desde RAM → shm          │   │  GravityFrame (marco girado)    │
│  MarioPuppet   oculta/mueve│   │ InputForwarder  teclado/ratón → shm │   │  CollisionField (KCL→AABB)      │
│  CameraDriver  vista de MC │   │ Compositor  frame MC sobre salida   │   │  OverlayExporter (PBO → shm)    │
│  CollisionExporter partes  │   └─────────────────────────────────────┘   │  InputInjector                  │
└────────────────────────────┘          memoria POSIX /galaxycraft_v1      └─────────────────────────────────┘
```

Más `protocol/`: un único esquema (header C) con espejos Java y Python y test de layout.
Más `tools/fake_galaxy.py`: hace de Dolphin+SMG2 para desarrollar el lado Minecraft sin el juego.

El código dentro de la Wii **no puede ver memoria del host**: el módulo Syati escribe un struct
big-endian en RAM emulada (el *buzón*) y el fork de Dolphin lo sincroniza con la memoria
compartida little-endian del host una vez por frame.

## 4. Coordenadas

SMG2 y Minecraft son ambos diestros con Y arriba. Escala inicial: **1 bloque = 100 unidades SMG**
(Mario ≈ 160 u, Steve = 1.8 bloques; se ajusta en Fase 4 con una prueba).

Galaxia → Minecraft es una transformación rígida `T = (R, t)` con escala `s = 1/100`:
`p_mc = R · (s · p_gal) + t`. `T` **cambia cada tick** (§5).

## 5. GravityFrame: gravedad arbitraria con física vanilla

Minecraft solo sabe caer hacia −Y. En vez de cambiar su física, **giramos el mundo**:

1. Cada tick se pide a SMG2 la gravedad `g` en la posición galáctica del jugador.
2. En el marco galáctico, `u_old = R⁻¹·(+Y)` es el "arriba" actual y `u_new = −ĝ` el nuevo.
   `Q` es la rotación mínima que lleva `u_old` a `u_new` (transporte paralelo: sin giro alrededor
   del eje vertical). `R' = R · Q⁻¹`, que cumple `R'·u_new = +Y`.
3. `t'` se elige para que la posición del jugador en Minecraft **no cambie** (rotación alrededor
   del jugador).
4. La velocidad del jugador se conserva en el marco galáctico: `v_mc' = R'·R⁻¹·v_mc`.
5. La dirección de la mirada también: se rota por `R'·R⁻¹` y se recalculan yaw/pitch.
   Si la mirada queda casi vertical se limita pitch a ±90° como hace Minecraft.

Resultado: "abajo" siempre apunta al planeta, la física de Minecraft queda intacta y caminar
alrededor de una esfera funciona. Se aplica solo si el ángulo entre `g` anterior y nueva supera
0.05° (evita jitter). Si `|g| ≈ 0` (sin gravedad) se mantiene el marco anterior.

## 6. CollisionField: la forma de SMG2 en la física de Minecraft

- SMG2 guarda la colisión de cada objeto/planeta como **KCL** (triángulos + octree) ya cargado
  en RAM, con una matriz de transformación viva (`CollisionParts`).
- El módulo Syati publica en el buzón la lista de partes cercanas: `{partId, kclAddr, kclSize,
  matriz 3x4, flags}`. El fork de Dolphin copia los bytes del KCL a la shm **una vez por parte**
  (evento `KclBlob`) y las matrices cada frame (`PartTransform`).
- El mod Fabric parsea el KCL (Java), aplica la matriz y luego `T`, y **voxeliza bajo demanda**:
  un Mixin en la consulta de colisión de entidades (`Level/CollisionGetter` → `getBlockCollisions`
  o equivalente en 26.3) añade `VoxelShape`s de 1/8 de bloque para los triángulos que tocan el
  AABB de la consulta. Las pendientes se vuelven micro-escalones (< 0.6 de step height).
- Superficies más empinadas que ~55° respecto a "arriba" local se suben a columna completa para
  que el step-up las rechace (mismo truco que SkyCraft).
- Fallback si leer KCL de RAM falla: KCL desde el disco extraído (`dolphin-tool extract`).

## 7. Jugador y cámara

- **Minecraft es autoritativo** en posición. Cada frame envía `PlayerState {posGal, lookGal,
  upGal, onGround, fovY, frameId}` ya convertido a coordenadas galácticas (el host aplica `T⁻¹`).
- **MarioPuppet** (Syati): desactiva el control de Mario, oculta su modelo, y fija su posición a
  `posGal` cada frame para que triggers, zonas de gravedad y cámaras de zona sigan reaccionando.
- **CameraDriver** (Syati): tras la actualización de cámara del juego, sobrescribe posición,
  objetivo y up con `posGal + ojo`, `lookGal`, `upGal`, y el FOV.

## 8. Render y entrada

- Minecraft corre con **ventana oculta** (GLFW invisible), a la resolución de salida de Dolphin.
  Mundo vacío: solo renderiza la mano y la GUI con fondo transparente. Se lee con PBO y se
  publica en un triple buffer en la shm (`OverlayFrame`, RGBA8).
- **Compositor** (fork Dolphin): antes de presentar, dibuja la textura del overlay encima con
  alpha blending. Sin prueba de profundidad en el MVP (mano y GUI siempre van encima).
- **InputForwarder** (fork Dolphin): con el bridge activo, la ventana de render captura el ratón;
  las teclas y deltas de ratón van a la shm y no al Wiimote emulado, salvo Esc (pausa de Dolphin).
  **InputInjector** (Fabric) los inyecta en `KeyboardHandler`/`MouseHandler`; la ventana oculta
  se marca como enfocada.

## 9. Protocolo (shm `/galaxycraft_v1`)

Little-endian, structs de tamaño fijo, sin librería de serialización.

- **Header**: magic `GXCR`, versión, PIDs, heartbeats (ms monotónicos) de ambos lados.
- **Slots última-valor con seqlock**: `WorldState` (S→M: gravedad en el último punto pedido,
  id de escena, frameId), `PlayerState` (M→S), `InputState` (S→M).
- **Dos ring buffers SPSC** de eventos: S→M (`PartUpsert`, `PartRemove`, `KclBlob` troceado,
  `SceneChange`) y M→S (`Hello`, `MenuState`).
- **OverlayFrame**: triple buffer con índice atómico del último completo.
- Si un heartbeat se detiene > 2 s el otro lado vuelve a estado seguro: Dolphin devuelve el
  control a Mario y la cámara al juego; Minecraft se congela.

Buzón en RAM (big-endian, dentro del módulo Syati): magic `GXCRMBX1`, versión, misma semántica
reducida (gravedad, PlayerState, lista de partes). Dolphin lo localiza escaneando MEM1/MEM2 en
cada cambio de escena.

## 10. Fases (cada una termina en algo probado)

| # | Fase | Hecho cuando | Necesita ISO |
|---|---|---|---|
| 0 | **Enlace** | protocolo + shm + test de layout C/Java/Python; `fake_galaxy.py` y el mod se saludan | No |
| 1 | **Caminar sobre el stub** | el mod camina alrededor de un planeta esférico del stub con física vanilla; tests de GravityFrame, parser KCL y voxelizador pasan | No |
| 2 | **Fork de Dolphin** | compila; GalaxyBridge sincroniza un buzón; Compositor dibuja el overlay del stub; InputForwarder funciona | No (prueba con cualquier juego) |
| 3 | **Módulo Syati** | compila con CodeWarrior+Kamek; buzón, gravedad, marioneta, cámara y exportador de partes | Sí |
| 4 | **Integración** | caminar en un planeta de SMG2 con física MC y gravedad | Sí |

## 11. Pruebas

- Java (JUnit): GravityFrame (continuidad de posición/velocidad, caminar 360° sobre esfera
  vuelve al inicio), parser KCL (KCL sintéticos generados por `tools/kcl_writer.py`),
  voxelizador, layout del protocolo.
- Python (pytest): espejo del protocolo, stub.
- C (test pequeño con `offsetof`/`static_assert`) para el header.
- Fork Dolphin y Syati: verificación manual documentada (logs del bridge, capturas).

## 12. Riesgos

| Riesgo | Mitigación |
|---|---|
| Funciones de cámara/Mario en SMG2 requieren RE | Petari/Garigari + símbolos de Syati; Ghidra |
| Leer KCL/`CollisionParts` de RAM | Fallback a KCL del disco extraído |
| Disco: 8 GB libres (ISO ≈ 4.7 GB, build de Dolphin ≈ 3 GB) | Fases 0–1 caben; Fase 2+ requiere liberar espacio |
| Sin sudo | Toolchains en `~/.local` (JDK, cmake vía tarball, devkitPPC extraído, CodeWarrior por wine) |
| Dolphin flatpak aísla IPC | Usar el fork compilado nativo |
| Latencia del overlay por CPU | Aceptable en MVP; GPU interop después |

## 13. Estructura del repo

```
GalaxyCraft/
  docs/
  protocol/        galaxycraft_protocol.h, test C, generador/espejos
  fabric/          mod Fabric (Gradle, Loom, MC 26.3, Java 25)
  tools/           fake_galaxy.py, kcl_writer.py, scripts de lanzamiento
  dolphin/         parche/fork de Dolphin (submódulo o patches/)
  syati/           módulo Syati (C++), build con CodeWarrior + Kamek
```
