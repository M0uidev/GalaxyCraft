# Multijugador (parte 1): unirse y verse

Puedes jugar con amigos en la misma galaxia. En esta primera parte **os veis moviéndoos**; todavía no se
comparten los bloques que rompéis o ponéis, ni los mobs, los objetos soltados o la redstone (vienen en las
partes 2 y 3, ver `ROADMAP.md`).

## Cómo se juega

**Quien abre el mundo (anfitrión)**

1. Entra en tu mundo con Super Minecraft Galaxy.
2. Pausa (Esc) → **Abrir a LAN** (*Open to LAN*) y confirma. Minecraft muestra el puerto.
3. Dile a tus amigos tu IP y ese puerto (por ejemplo `192.168.1.20:54321`).
   - En la misma red, la IP local basta.
   - Por internet hace falta abrir ese puerto en tu router, o usar una VPN (Tailscale, ZeroTier...).

**Quien se une (invitado)**

1. Abre Super Minecraft Galaxy (la misma versión del mod que el anfitrión).
2. Menú principal → **Multijugador** → **Conexión directa**, y escribe la IP y el puerto.
3. Entras en **la galaxia del anfitrión**: los mismos planetas y el mismo universo (la semilla viaja con
   la conexión). Tu partida de invitado guarda una copia en `galaxycraft/guest/` para entrar más rápido
   la próxima vez; no toca tus propios mundos.

## Qué se ve

- Los demás jugadores aparecen con el modelo y el skin de Minecraft donde están, en cualquier planeta
  o en el espacio, de pie sobre su propio "arriba" (la gravedad de cada planeta).
- Si algún jugador lleva un skin con `/skin`, los demás también lo ven.
- Al caminar, agacharse o planear, su cuerpo lo muestra.

## Límites de esta versión

- Todos deben tener **la misma versión** del juego. Si no, el anfitrión te echa con un mensaje que dice
  cuáles son las dos versiones.
- Hasta **8 jugadores** por galaxia.
- Cada uno ve **sus propios cambios en los planetas**; los de los demás todavía no (parte 2).
- Un jugador en modo Mario se ve como Steve para los demás (el modelo de Mario remoto queda para más
  adelante). No hay nombres sobre las cabezas todavía.
- No hay `/tpp` para ir hasta otro jugador: se llega volando (élitros, nave, Launch Stars).
- Un servidor sin el mod funciona como un servidor normal de Minecraft, sin galaxia.
- Un servidor dedicado (sin jugador) no está soportado: la galaxia la tiene el jugador anfitrión.

## Lista para probarlo (dos personas, o dos ordenadores)

1. [ ] El anfitrión abre a LAN; el invitado se une por IP y entra a la pantalla "Entering the galaxy".
2. [ ] Los dos aterrizan en el planeta de inicio y **se ven** (el otro Steve, de pie, mirando hacia donde
   mira la persona).
3. [ ] Uno camina alrededor del planeta: el otro lo ve moverse suave, dando la vuelta (de cabeza abajo
   en el otro lado) sin saltos.
4. [ ] Uno se agacha, corre y planea con los élitros: el otro lo ve.
5. [ ] Uno viaja a otro planeta o al espacio: el otro lo sigue viendo mientras esté cerca, y deja de verlo
   (sin errores) cuando se pierde de vista.
6. [ ] Uno se va y vuelve a entrar: no queda ninguna "sombra" de la vez anterior.
7. [ ] Un invitado con otra versión del mod es rechazado con el mensaje de versiones.
8. [ ] El anfitrión cierra el mundo: el invitado vuelve al menú principal sin cuelgue.
9. [ ] Un jugador con `/skin` otraCuenta: el otro ve ese skin (también si entra después).

Si algo falla, mira `logs/latest.log` y busca las líneas `[GalaxyCraft mp]`.
