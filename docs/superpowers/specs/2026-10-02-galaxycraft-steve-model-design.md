# GalaxyCraft — Steve dentro de SMG2

Fecha: 2026-10-02. Reemplaza a Steve en el overlay (`2026-10-02-galaxycraft-perspectives-design.md` §3).

## 1. Intención

El personaje que se ve en tercera persona, en la vista Galaxy y en las cinemáticas es Steve
**dibujado por SMG2**: con la luz, las sombras y la profundidad del juego, y animado con las
animaciones de Mario. El overlay de Minecraft sólo dibuja la mano y la HUD.

Prueba de viabilidad (2026-10-02, desechable): un Steve de cajas sobre el esqueleto de Mario,
convertido con SuperBMD 2.5 y cargado como `Mario.arc`, se ve y se anima en el prólogo.

Fuera de alcance: power-ups (abeja, boo, roca, nube, muelle, fuego, invencible), Yoshi, Luigi,
modelo de baja calidad o de lejos si el juego lo usa. Siguen viéndose como Mario.

## 2. Assets (`tools/steve/`)

Nada de Nintendo ni de Mojang se guarda en el repo: todo se genera en `syati/build/` desde el
disco y el jar de Minecraft locales.

1. `tools/rarc.py`: Yaz0 y RARC (leer, reemplazar ficheros conservando el árbol).
2. `tools/steve/build.py`:
   - extrae `ObjectData/Mario.arc`, `MarioHandL.arc` y `MarioHandR.arc` con `dolphin-tool`;
   - exporta `Mario.bdl` a DAE con SuperBMD (esqueleto de 30 huesos);
   - genera `Steve.dae`: cajas con la skin (64×64, por defecto `steve.png` del jar de
     Minecraft, o `--skin`), cada caja rígida a su hueso: cabeza → `Head`, torso → `Spine2`,
     muslo/pantorrilla → `LegX1`/`LegX2`, brazo/antebrazo → `ArmX1`/`ArmX2`; mismas proporciones
     que en la prueba (pierna = cadera de Mario);
   - las manos: `MarioHandL/R.bdl` con su propio esqueleto y un único triángulo de área cero
     (invisibles);
   - convierte con SuperBMD (`-b`, material `tools/steve/material.json`, textura *nearest*);
   - reempaqueta los `.arc` en `syati/build/ObjectData/`.
3. SuperBMD 2.5.0 (RenolY2) en `~/.local/opt/gxc-toolchain/SuperBMD`, bajo wine; `build.py` lo
   descarga si falta (URL fija y SHA-256).
4. `syati/build.sh` llama a `build.py` si cambian sus entradas y añade al XML de Riivolution un
   `<file>` por cada `.arc` generado.

**Material:** el canal de color con la luz del juego como el cuerpo de Mario (`Body1_v`: luces
del escenario, ambiente por registro), pero el color base sale de la skin y no de los vértices:
TEV = textura × color rasterizado. Se ajusta comparando capturas de Steve y de un NPC junto a él.

## 3. Visibilidad (módulo Syati)

- `host_flags` gana `GXC_MBX_THIRD_PERSON = 8`: el host lo pone si la vista del mod no es FIRST.
- El modelo se dibuja salvo en primera persona: `MarioDraw` lo omite sólo con FOLLOW, sin
  cinemática y sin THIRD_PERSON. La lógica va en `core/` (`gxc::MarioVisible`) con test g++.

## 4. Mod

- Steve (el jugador local) nunca se dibuja en el overlay mientras el host está enlazado.
- Se retira la cámara de Minecraft en la vista Galaxy (posición, rotación, FOV), el giro de Steve
  hacia el frente de Mario y `hideSteve`: el modelo del juego ya hace todo eso. `GameCamera` se
  sigue publicando, sin uso en el mod.
- F5, el offset de cámara en FIRST/BACK/FRONT y el recorte de la cámara contra paredes siguen.

## 5. Pruebas

- Python: Yaz0/RARC (ida y vuelta, reemplazo conserva los demás ficheros); el generador conserva
  nombres y orden de huesos, UV dentro de la skin, cada vértice con peso 1 a un hueso válido.
- Host: THIRD_PERSON en BACK, FRONT y GALAXY; no en FIRST ni en modo Wiimote.
- Syati core: `MarioVisible`.
- Java: el overlay no dibuja al jugador enlazado (por lectura; sin test de render).
- A mano (`gxplay.sh`): Steve en las 4 vistas y en una cinemática, sin guantes, iluminado como el
  entorno, tapado por paredes; primera persona sin modelo.
