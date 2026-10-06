# Romper y poner bloques como en Minecraft

Rama `game-feel` (2026-10-06). Diseño: [specs/2026-10-06-galaxycraft-game-feel-design.md](superpowers/specs/2026-10-06-galaxycraft-game-feel-design.md).

## Qué cambia en el juego

- **Romper manteniendo el botón.** Con algo en la mano, mantener el clic izquierdo sobre un bloque
  lo va rompiendo a la velocidad real de Minecraft: la dureza del bloque, la herramienta, si es la
  correcta, Eficiencia, Prisa minera y Fatiga minera. Sin soltar, rompe un bloque tras otro (5 ticks
  de pausa entre bloques, como Minecraft). El pasto, las flores y todo lo que se rompe al instante
  cae uno por tick. En creativo rompe uno cada 5 o 6 ticks.
- **Lo de Mario Galaxy:** romper en el aire no es 5 veces más lento (Minecraft sí lo hace), porque
  Mario salta y gira todo el rato.
- **Grietas.** Las texturas `destroy_stage_0..9` de Minecraft aparecen sobre el bloque y crecen a
  medida que se rompe. Si mueves la mira, cambias de herramienta o sueltas, se borran.
- **Sonidos.** Cada bloque suena con sus propios sonidos de Minecraft: el golpe cada 4 ticks, la
  rotura y al colocarlo. Suenan desde donde está el bloque.
- **Partículas.** Mientras golpeas, saltan trocitos del material desde la cara golpeada (como en
  Minecraft). Al romperse salen los pedazos de siempre, y al colocar un bloque salen unos pocos
  trocitos de él (eso es propio de GalaxyCraft: Minecraft no lo hace).

## Qué falta: probarlo en el PC

En la sesión en la nube no se pudo compilar el mod: los servidores de Minecraft y de Fabric están
bloqueados ahí. Sí pasaron los tests que no necesitan Minecraft:

- `syati/test.sh`: el mensaje nuevo y la malla de las grietas (491 comprobaciones).
- `dolphin/galaxycraft/test.sh`: Dolphin pasa el mensaje al juego (99 tests).
- Las pruebas unitarias del mod: `MiningTest` (los tiempos de Minecraft) y
  `PlanetSessionTest.cracksGoOutWhenTheirStageOrBlockChanges`.

Para la sesión de Claude Code en el PC:

1. `cd fabric && ./gradlew build` y `./test.sh`. Puede fallar algo de la API de Minecraft 26.3 en
   el código del cliente, que se escribió sin compilarlo:
   - `PlanetClient`: `mine`, `destroyProgress`, `blockSound` y `placed` usan
     `BlockState.getDestroySpeed(BlockGetter, BlockPos)`, `Player.getDestroySpeed`,
     `Player.hasCorrectToolForDrops`, `Entity.onGround()`, `BlockState.getSoundType()`,
     `SoundType.getHitSound()/getBreakSound()/getPlaceSound()`, `ClientLevel.playLocalSound` y
     `LivingEntity.swing`.
   - `McBlocks`: `crackUv` y las texturas `block/destroy_stage_N`.
   - `ParticleClient`: `crack`, `burst` y `puff`.
2. Compilar el módulo (`syati/build.sh`) y Dolphin (`dolphin/build.sh`): el mensaje 115 es nuevo
   en los tres.
3. Jugar (`tools/gxplay.sh`) y revisar:
   - Piedra con pico de madera: unos 1,15 s. Con pico de diamante y Eficiencia V, casi al
     instante. Tierra con pala: rápido. Con la mano equivocada, más lento.
   - Mantener el botón cavando un túnel: un bloque tras otro sin soltar.
   - Las grietas se ven sobre el bloque (oscurecen y no lo tapan), desde todos los lados, y se
     borran al soltar.
   - Los sonidos del golpe, la rotura y al colocar (piedra, madera, tierra, vidrio).
   - Los trocitos al golpear salen de la cara que miras.
   - En creativo, mantener el botón rompe uno cada ~¼ s.
   - Que no suene dos veces ni rompa dos bloques al cortar pasto rápido.
4. Revisar `destroyProgress`. Deshace la penalización de Minecraft por romper en el aire
   multiplicando por 5 cuando el jugador no está en el suelo. Si en 26.3 esa penalización ya no
   depende de `onGround()` (por ejemplo, si ahora es un atributo), romper saldría 5 veces más
   rápido. Se nota con el pico de madera en piedra: debe tardar unos 1,15 s.

## Preguntas abiertas

- **Romper con la mano vacía** (golpear madera) sigue sin poderse, porque con la mano vacía los
  clics son de Mario (el giro). ¿Se quiere poder romper con la mano?
- Las grietas se dibujan sobre la caja del contorno del bloque, no sobre su modelo. En losas y
  escaleras se ven sobre la caja.
