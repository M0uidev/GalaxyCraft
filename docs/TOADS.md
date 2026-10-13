# Toads

Toads are SMG2's own Kinopio, drawn by the game, that you can talk to. They are animated (idle,
talking), turn to you when you are near, and each type carries its own thing. The world places
them: a Guide near where you first stand on the home planet, a Technician on each station deck,
and outposts on planets of other systems.

## Try it

1. Play with the launcher (it copies `Kinopio.arc` from your own disc next to the ship's the first
   time; if you installed before, run PLAY again so it does).
2. Stand on a planet and type `/toad guide` (or `miner`, `explorer`, `technician`, `mailman`):
   a Toad appears on the ground ahead, facing you. Without `Kinopio.arc` a small orange box stands
   in.
3. Look at it and right click: a dialogue box opens at the bottom of the screen and the Toad
   talks, letter by letter, with a little voice. You keep moving the whole time. Right click or
   Enter finishes the line and goes on; after the greeting you choose with 1-4 (or Up / Down and
   Enter): **1 Trade** (the vanilla villager screen: a Guide sells bread and torches for coal,
   cobblestone or copper; each type sells different things), **2 Tell me about this place**,
   **3 Where should I go?**, **4 Bye**. Walk more than 8 blocks away, or open any menu (Esc), and
   the box closes.

## Where they are

- **Home planet:** one Guide, put down a few blocks ahead the first time you stand near the
  first planet's surface.
- **Stations:** a Technician on the deck, a few blocks from the core. Fold the station into its
  item and it goes with it; unfold it elsewhere and it is there again.
- **Outposts:** planets in systems of tier 1 and up (about 12,000 blocks from home and beyond) get
  a small mushroom hut with a Toad just inside the door: tiers 1-2 have none or one (a Miner or
  an Explorer), tier 3 and up none to two (the Mailman too). Blue and lava stars lean to
  Miners, red dwarfs to Explorers. Only planets generated from now on get them (a planet already
  saved keeps what it was). Start the game with `-Dgalaxycraft.toadHuts=false` to put a lone
  Toad on the ground instead of a hut.

## What to look for

- It stands on the ground (feet on it, not sunk, not floating) and turns to face you.
- Right click on it does not put down the block in your hand, and the box does not stop you
  walking, jumping or looking around; pressing 1-4 does not change your hotbar slot.
- The lines change with the tier and star of the system you are in (ask the same Toad at home and
  far away).
- It is still there after you quit and come back (`toads.json` next to the planets).
- The trade opens with its offers and takes the payment from your inventory.

## Not there yet

More types' wares (the first tables are a draft, refined by playing), the ship (the Warp Core and a missing
module are not asked yet; a Toad speaks and sells by the tier and star of the system you are in), Toads in villages
and mines (waits for progression's structures), and real portraits, and SMG2's own Toad voice (the box uses a pitched Minecraft sound for now).

## Toads are villagers (2026-10-13)

A Toad is a real `Villager` in the shadow dimension, tagged `galaxycraft_toad:<id>`, drawn as Kinopio
(`EntityClient.shadowToad`, `ToadClient.draw`). `toads.json` stays the permanent record; `ShadowToads`
(server) spawns the entity when its planet is mirrored, on free ground (`toad/FreeSpot`), keeps it out of
blocks (each second), publishes its position (the client writes it back to the record) and announces its
death (the record is deleted: Toads are vulnerable like villagers). Minecraft's own AI does the rest: it
claims a bed by day (the mirror registers beds and doors as points of interest), sleeps in it at night,
opens doors. Hitting, aiming and the box of the entity are the mobs' own. A right click is ours (the
dialogue box and `ToadMerchant`), the villager's trading is never opened; while talking, the Toad stands
and looks at the player. The Technician on a station stays a static record (a station is a slab with
edges). Generated huts have a 3-high room under a roof, an oak door and a red bed.

Probe (Minecraft only): `./gradlew runClientGameTest -PgalaxycraftToadEntity` (`ToadEntityProbe`): the
entity, one per Toad, persistence, position, a bed and the night, a hit, a shove into the ground, a death.
