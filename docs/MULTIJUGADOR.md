# Multijugador (partes 1 y 2): unirse, verse y construir juntos

Puedes jugar con amigos en la misma galaxia. **Os veis moviéndoos** y **los bloques que rompéis o ponéis
se comparten** en los planetas del catálogo. Los mobs, los objetos soltados, los fluidos y la redstone
siguen sin compartirse (parte 3, ver `ROADMAP.md`).

## Jugar con un amigo que no está en tu red (dirección directa y Hamachi)

Funciona como un servidor de Minecraft: el anfitrión abre el mundo y el amigo entra con **IP:puerto**. El juego
no usa ningún servicio nuestro; solo hace falta que el amigo *llegue* a tu ordenador. Mantiene el login de Mojang
(cada jugador entra con su cuenta de Minecraft) y no pide aceptar amigos: quien conoce la dirección, entra.

**Anfitrión**

1. Entra en tu mundo, Esc → **Abrir a LAN** (*Open to LAN*). Puedes elegir el **puerto** en esa pantalla (por
   ejemplo `25599`; también `/publish false 25599` en el chat). El mundo escucha en **todas** las interfaces de
   red, incluida la de Hamachi.
2. Al abrirlo, el juego escribe en el chat las direcciones donde tus amigos pueden entrar, por ejemplo:
   `25.4.7.9:25599 (Hamachi)` y `192.168.1.20:25599 (misma Wi-Fi o router)`. Pásales la que corresponda.
3. **Hamachi** (red virtual, la más fácil si el router no deja abrir puertos): tú y tu amigo instaláis Hamachi,
   creáis una red y os unís a ella; tu dirección es la `25.x.x.x` que muestra Hamachi (el juego la marca).
4. **Abrir puerto en el router** (alternativa): reenvía el puerto TCP elegido a tu ordenador y dile a tu amigo
   tu IP pública. No funciona detrás de CGNAT (algunos operadores): ahí usa Hamachi.
5. Si tienes firewall, permite Java/Minecraft en la red de Hamachi o el puerto elegido.

**Amigo**

1. Misma versión del mod que el anfitrión (si no, el juego te lo dice al entrar).
2. En el **launcher de Super Minecraft Galaxy**: escribe la dirección en el campo **Join a friend**
   (`25.4.7.9:25599`) y pulsa **Join**: arranca el juego y entra directo. (Con Minecraft desde el launcher oficial
   de Mojang: Multijugador → Conexión directa.) O, dentro del juego: **Multijugador → Conexión directa**.
3. La primera vez se descargan los planetas del anfitrión (1-4 MB cada uno); con una conexión lenta tarda
   más. Se guardan, así que la próxima vez es rápido.

**Si no conecta**

- *"Connection timed out" / no encuentra el servidor*: la dirección o el puerto no llegan al anfitrión. Prueba con
  Hamachi (¿ambos aparecen "en línea" en la misma red?), revisa el firewall y que el mundo siga abierto (el puerto
  cambia cada vez que lo abres, salvo que lo elijas tú).
- *"Invalid session"*: tu cuenta de Minecraft no está iniciada (abre el juego desde el launcher con tu cuenta).
- *"version differs"*: actualizad el mod a la misma versión.
- *Entra pero la galaxia tarda*: es la descarga de planetas; el progreso va en la pantalla de carga. El anfitrión
  envía primero el planeta donde estás.
- Registro con el tráfico: `GXC_MP_TRACE=1` escribe cada 10 s cuántos KB/s envía cada tipo de mensaje
  (`[mp bytes]` en `logs/latest.log`), útil para saber qué pesa en una conexión lenta.

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
3. Entras en **la galaxia del anfitrión**: los mismos planetas y el mismo universo. La semilla viaja con
   la conexión y **el anfitrión te envía cada planeta** que necesitas, igual que lo tiene él (con sus
   árboles y lo que haya construido). Tarda unos segundos al entrar y al acercarte a un planeta nuevo.
4. Tu juego guarda una copia en `galaxycraft/guest/` para entrar más rápido la próxima vez: si un planeta
   no ha cambiado, no se vuelve a descargar. No toca tus propios mundos.

## Qué se ve

- Los demás jugadores aparecen con el modelo y el skin de Minecraft donde están, en cualquier planeta
  o en el espacio, de pie sobre su propio "arriba" (la gravedad de cada planeta).
- Si algún jugador lleva un skin con `/skin`, los demás también lo ven.
- Al caminar, agacharse o planear, su cuerpo lo muestra.

## Construir juntos (parte 2)

- Lo que un jugador rompe o pone, **los demás lo ven** al momento en cualquier planeta de la galaxia,
  también si el anfitrión está lejos de ese planeta.
- **Cualquiera puede romper y poner todo** (por ahora no hay permisos ni zonas protegidas).
- Manda el anfitrión, como en un servidor de Minecraft: si dos jugadores cambian el mismo bloque a la vez,
  gana el último que llega al anfitrión y todos acaban viendo lo mismo.
- Solo el anfitrión simula: las hojas que caen al romper un tronco las quita él y los demás lo ven.
- **Lo construido se guarda en el mundo del anfitrión**, aunque lo hiciera un invitado en un planeta donde
  el anfitrión no ha estado. El invitado no guarda sus cambios: la próxima vez recibe el planeta del anfitrión.
- Un invitado que vuelve a un planeta que dejó lejos puede tener que descargarlo otra vez si alguien lo
  cambió mientras tanto.

## Estaciones espaciales (fase 1)

El anfitrión envía al invitado la lista de sus estaciones (`StationUpdate`: id, nombre, lugar, giro) y, como un
planeta, sus bytes (`PlanetAsk` con un número de **ranura** >= 1<<24, que el anfitrión reparte y no guarda). El invitado
las activa cuando se acerca (a menos de 2000 bloques), las guarda en `mp-cache/stations` y construye en ellas; el
anfitrión aplica las ediciones, corre la estación en su dimensión sombra (la misma franja que usa para la suya) y,
cuando una estación crece y estrena rejilla, corta a los invitados con un `Resync` para que la pidan de nuevo
(las ediciones llevan la clave de la rejilla, `Station.gridKey`, y las viejas se descartan). Todavía solo el
anfitrión puede colocar, empaquetar o renombrar una estación (fase 2).

## Límites de esta versión

- Todos deben tener **la misma versión** del juego. Si no, el anfitrión te echa con un mensaje que dice
  cuáles son las dos versiones.
- Hasta **8 jugadores** por galaxia.
- Los **fluidos** (agua y lava que fluyen) y las hojas que caen los simula solo el anfitrión: en un
  invitado pueden llegar con un pequeño retraso.
- El invitado no tiene las cosas que vive el servidor del anfitrión: puertas, cofres y redstone, mobs y
  objetos soltados, Toads, estaciones, naves y Launch Stars (parte 3).
- El anfitrión no limita la velocidad del invitado (se fía de los amigos).
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
10. [ ] El invitado recibe los planetas del anfitrión (en el log: `planet N from the host is in`) y camina
    en ellos, en modo Mario y en modo Minecraft, sin que el servidor lo devuelva a su sitio.
11. [ ] Al entrar otra vez, los planetas que no cambiaron no se descargan de nuevo.
12. [ ] El invitado rompe y pone bloques: el anfitrión los ve al momento. Y al revés.
13. [ ] Los dos cambian el mismo bloque a la vez: acaban viendo el mismo bloque.
14. [ ] El invitado rompe el tronco de un árbol: las hojas caen también para el anfitrión y para él.
15. [ ] El invitado construye en otro planeta lejos del anfitrión; el anfitrión cierra el mundo y lo abre
    de nuevo, vuela allí: la construcción está.
16. [ ] Dos invitados y el anfitrión construyen a la vez en el mismo planeta sin tirones ni pérdidas.

## Probarlo en un solo ordenador (desarrollo)

**Atajo:** `tools/gxmp.sh both` abre el anfitrión y el invitado en el orden correcto (lee su cabecera: explica las
trampas conocidas); `tools/gxmp.sh status` y `tools/gxmp.sh stop`. Por defecto cada juego toma el teclado y el
ratón solo mientras su ventana tiene el foco (`GXC_FOCUS_INPUT=0` para desactivarlo). Lo de abajo es lo que
hace por dentro.

Dos juegos completos a la vez (cada uno con su Dolphin). Comparten el teclado y el ratón del sistema, así
que no se pueden jugar a la vez; sirve para ver que se ven y que los planetas llegan.

- Anfitrión: `GXC_OFFLINE_LAN=1 GXC_LAN_PORT=25599 GXC_MC_ARGS="--quickPlaySingleplayer '<mundo>'" tools/gxplay.sh`
  (abre el mundo a LAN solo, en ese puerto).
- Invitado: `GXC_KEEP_OTHERS=1 GXC_SHM_DIR=/tmp/g2/shm XDG_DATA_HOME=/tmp/g2 GXC_OFFLINE_LAN=1
  GXC_MC_ARGS="--username Amigo --quickPlayMultiplayer localhost:25599" tools/gxplay.sh`.
- `GXC_OFFLINE_LAN` apaga la verificación de sesión de Mojang en el servidor LAN (sin ella, un
  cliente sin cuenta recibe "Invalid session"). Solo para desarrollo.

Si algo falla, mira `logs/latest.log` y busca las líneas `[GalaxyCraft mp]`.
