**Title (copy as is):**
Super Minecraft Galaxy | Super Mario Galaxy 2 | Minecraft survival on destructible voxel planets

**Tags:** Minecraft, Super Mario Galaxy 2, Dolphin, Mod, Fabric, Wii, Linux, Windows, Work in progress

---

# Super Minecraft Galaxy 🌍⭐

I've had one dumb question stuck in my head for a long time: *what if Mario Galaxy's tiny round
planets were made of Minecraft blocks?*

So I built it. Super Minecraft Galaxy runs **Minecraft inside the real *Super Mario Galaxy 2***, in
Dolphin. You play as Mario (or as Steve, your pick), and the planets floating around you are voxel
worlds you can dig through, build on, flood with water and blow up with TNT, with Galaxy's round
gravity pulling you toward the core. Walk all the way around a planet and you end up where you
started. Dig down and you hit bedrock at the middle of the world, not at the bottom of it.

Today I'm putting out **v0.1.4**, and this is the first time I'm showing it properly. 🎉

## What you can actually do right now

- **Make a world, get a galaxy.** Every Minecraft world is its own galaxy, with a home planet and a
  starter kit. Ask for up to 64 planets, each with its own biomes, oceans, caves and trees.
  Planets are 10 to 256 blocks in radius, and you see them as far-off specks long before you
  reach them.
- **Break it, build it.** Pretty much every Minecraft block works, with its own model bent around
  the curve of the planet. Doors open, pistons push, redstone ticks, crops grow, chests open,
  furnaces smelt.
- **Survival, mostly.** Real loot tables, tools, drops that roll around on the planet, mobs that
  chase you across the cube faces, arrows, TNT, lava, water, a cobblestone generator that works.
- **Fly.** Elytra from planet to planet, flat space stations to land on, and a lot of empty
  space in between.
- **Two ways to move.** *Mario mode* (Galaxy's own jumps and spins, shrunk to Steve's width so he
  fits in a 1×1 hole) or *Minecraft mode* (real Minecraft movement with Steve's body and your
  skin). **F6** switches.
- **A launcher** for Linux and Windows, with a big PLAY button, so you don't have to start three
  programs by hand.

## How it works (the nerdy bit)

Three programs share one world through shared memory:

1. A **Fabric mod** for Minecraft 26.3 owns the blocks, inventory and camera, and builds each
   planet's meshes and collision.
2. A **patched Dolphin** runs the game and carries input and data between both sides.
3. A **Syati/Kamek module** injected into SMG2 draws the planets, adds gravity and collision, puts
   the camera in Mario's eyes, and even draws mobs and items with Minecraft's own models inside
   the game.

Some of the fun parts: a floating origin so planets millions of blocks away don't jitter, far-view
LODs meshed on worker threads, and a good number of "why is Mario inside the planet" bugs I had
to chase through PowerPC disassembly.

## v0.1.4

Far planets that show up properly from a distance, a better water look, a pass over the in-game
overlay, a **Mario Model** setting (Steve's body, or the game's original Mario), and a launcher that
handles it all.

## Where it stands

It's early and it's a one-person project, so expect rough edges. It's tested on Linux, and Windows
has been played and is now on CI too. Next up: Minecraft 1.7-style terrain with real oceans and
caves, and swimming.

## Bring your own games

Nothing from Nintendo or Mojang is included. You need your own copy of *Super Mario Galaxy 2*
(USA, `SB4E01`) and of Minecraft. Not affiliated with Nintendo or Mojang.

## Links

- **GitHub:** https://github.com/M0uidev/GalaxyCraft
- **Discord:** https://discord.gg/NhKmT6cVM7

If you try it and it breaks, I want to hear how. And if you've ever hacked on SMG2 or Dolphin, I
would love to compare notes. ⭐
