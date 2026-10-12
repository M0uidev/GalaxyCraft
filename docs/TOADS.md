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
3. Look at it and right click: it says one to three lines in chat, then opens its trade (the
   vanilla villager screen). A Guide sells bread and torches for coal, cobblestone or copper; each
   type sells different things.

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
- Right click on it does not put down the block in your hand.
- It is still there after you quit and come back (`toads.json` next to the planets).
- The trade opens with its offers and takes the payment from your inventory.

## Not there yet

More types' wares (the first tables are a draft, refined by playing), the system's tier and star
in what a Toad says (it answers as if the system were the home one for now), Toads in villages
and mines (waits for progression's structures), and Toads that walk.
