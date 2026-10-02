# Fase 1: caminar sobre el planeta del stub

El stub `tools/fake_galaxy.py` hace de Dolphin + Galaxy 2: publica dos planetas esféricos
(radio 800 u en el origen y 600 u en `(0, 2600, 0)`; 100 u = 1 bloque) y responde con
gravedad hacia el planeta más cercano.

## Requisitos

- Python 3 (sin paquetes extra).
- JDK 25. Si no está instalado en el sistema, `fabric/test.sh` usa `~/.local/opt/jdk-25*`;
  para Gradle a mano: `export JAVA_HOME=$(ls -d ~/.local/opt/jdk-25*)`.

## Tests

```sh
python3 -m unittest discover -s tools/tests   # protocolo, KCL y stub
make -C protocol test                         # layout del header C
fabric/test.sh                                # unit tests del mod
cd fabric && ./gradlew runClientGameTest      # end-to-end: abre Minecraft y camina sobre el stub
```

El test end-to-end deja capturas en `fabric/build/run/clientGameTest/screenshots/`.

## Jugar a mano

1. `python3 tools/fake_galaxy.py` (déjalo corriendo; imprime posición y altitud cada segundo).
2. `cd fabric && ./gradlew runClient`.
3. Crea un mundo **Superflat** con el preset **The Void**, modo **Aventura**, dificultad **Pacífico**.
4. Ya en el mundo: `/tp @s 0 100 0`. El mod se enlaza, te deja sobre el planeta y la gravedad
   apunta siempre a su centro. Camina en línea recta: das la vuelta al planeta.
5. `/galaxycraft status` muestra si está enlazado, la posición galáctica y el "arriba" actual.

Si matas el stub, el mod congela el último marco (sigues de pie); al relanzarlo se reconecta.

## Limitaciones conocidas (Fase 1)

- Las superficies son escalones de 1/8 de bloque (se nota al caminar sobre pendientes).
- Agacharse en un borde no impide caer (Minecraft lo calcula con bloques reales).
- El salto entre planetas funciona con la gravedad del más cercano, pero no hay launch stars.
