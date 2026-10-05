# GalaxyCraft

Minecraft inside *Super Mario Galaxy 2*. You play as Mario, through Minecraft's eyes, in the real
game running in Dolphin. Voxel planets that you can dig, build on and flood with water and lava
float in SMG2's galaxies, with spherical gravity, as if they had always been there.

Three programs share one world:

- **A Fabric mod for Minecraft 26.3** (`fabric/`). It owns the blocks, the inventory and the
  camera, and builds each planet's meshes and collision.
- **A patched Dolphin** (`dolphin/`). It runs the game and bridges both sides through shared
  memory, mapping keyboard and mouse to the Wii Remote and Nunchuk.
- **A Syati/Kamek module inside SMG2** (`syati/`). It draws the planets, gives them gravity and
  collision, puts the camera in Mario's eyes and adapts Mario's movement to 1-block holes.

`protocol/galaxycraft_protocol.h` is the shared-memory layout all three agree on.

> Work in progress, built and tested on Linux. Not affiliated with Nintendo or Mojang. No game
> files are included: you need your own copy of *Super Mario Galaxy 2* (USA, `SB4E01`) and of
> Minecraft.

## What works

- **Mario mode.** First-person play as Mario with keyboard and mouse. F5 switches to third
  person or to the game's own camera.
- **Voxel planets.** Cube-sphere planets with a radius of 10 to 256 blocks: bedrock, stone, dirt
  and grass. You break and place blocks as in Minecraft, with the block outline. Planets are
  saved per galaxy.
- **Every Minecraft block.** Any block can go on a planet, drawn with its own model bent onto the
  planet's cells: slabs, stairs, crossed flowers, torches, fences that join, doors, see-through
  glass and leaves. Blocks face the way Minecraft's placement rules say, relative to the planet's
  local up, and Mario collides with their real shapes. `/gamemode creative` and **E** open the
  creative inventory; while a Minecraft screen is open the mouse is a normal pointer, so you can
  click, drag and scroll in it.
- **Blocks that work.** Minecraft itself runs the blocks around Mario: doors, trapdoors and gates
  open, levers and buttons power redstone, pistons push, repeaters, comparators and observers tick,
  crops grow, chests and furnaces open their menus. Right click uses a block (or the held item on
  it, like flint and steel or bone meal) before placing anything, as in Minecraft, and aiming at
  such a block works with an empty hand too.
- **Survival items.** Outside creative, broken blocks drop what Minecraft's loot says (right tool,
  silk touch, fortune), drops fall onto the planet and go into your inventory when Mario walks over
  them, placing a block uses one from your hand, and **Q** throws the held item onto the planet.
- **Entities drawn by the game.** Dropped items, mobs, primed TNT and falling blocks are drawn
  inside SMG2 with Minecraft's own models and textures, not overlaid by Minecraft. Mobs (from
  spawn eggs) live in the shadow dimension, are animated by their own models, and walk around the
  planet across its cube faces. Lit TNT flashes, swells and blows up planet blocks.
- **Fighting.** Clicking a mob in reach hits it with what is in hand, as Minecraft would (damage,
  knockback, enchantments, wear). Mobs fall over and vanish in a puff when they die, and drop
  their loot on the planet. Hostile mobs chase and hurt Mario, arrows and explosions too.
  Particles (death puffs, explosions, broken blocks' pieces, hits) are drawn by the game. When
  something hurts the player, Mario reels in SMG2 as from an enemy's blow (or a blast, or fire),
  while SMG2's own life meter is left alone: Minecraft's health is what counts.
- **Every entity.** Arrows, minecarts, boats, armor stands, thrown items, paintings, and mobs'
  armor, held items, saddles and wool are drawn too, each by its own Minecraft renderer.
- **Using them.** Arrows, snowballs, tridents and other projectiles fly from Mario. Boats go
  where he looks, minecarts on rails. Right click a minecart or boat to ride it (W pushes or
  paddles, A/D steer a boat, Shift gets off); right click a mob to use what is in hand on it
  (shears, buckets, wheat, name tags, trading). Mario pushes what he walks into and picks up
  arrows lying around.
- **Fluids.** Water and lava buckets, ice that melts into water, and obsidian and cobblestone
  where water and lava meet. A classic cobblestone generator works.
- **Collision that feels like Minecraft.** Mario shrinks to Steve's width on planets, so he
  falls into 1×1 holes and fits in 1×2 tunnels. Press **F3+B** to see his collision.
- **`/fly`** for free flight, and `/galaxycraft planet spawn|tp|remove` to manage planets.
- **Pause menu.** Esc opens Minecraft's own pause menu, with **GalaxyCraft...** (movement, skin,
  entity distance, particles, flight, the planet editor) and **SMG2 Menu** (the game's own pause,
  the + button). Settings are kept in `config/galaxycraft.properties`.
- **Two movements.** *Mario* (SMG2 moves Mario: his jumps and spins) or *Minecraft* (Minecraft
  moves you, Mario goes along, and Steve is drawn in the game with Minecraft's player model).
  **F6** switches them.
- **`/skin <account>`** puts that Minecraft account's skin on your character (Mario's model and
  Steve's); `/skin` alone goes back to Steve.

## Requirements

- Linux, with a C++ toolchain, CMake, Ninja, Python 3 and a JDK 25.
- *Super Mario Galaxy 2* (USA) as a `.rvz`/`.iso`. By default it is looked for in
  `~/Documents/Games/Dolphin Games/`; set `GXC_GAME` to point at it instead.
- The Syati toolchain for the module, kept outside the repo in `~/.local/opt/gxc-toolchain/`
  (or `$GXC_TOOLCHAIN`):
  - Syati;
  - the CodeWarrior PPC EABI compiler, run through wine;
  - Kamek.

  See `docs/PHASE3.md`.

## Build and play

```sh
dolphin/build.sh     # clones Dolphin at a pinned commit, applies the patch, builds it
syati/build.sh       # the module, its Riivolution patch and Steve's model for SMG2
tools/gxplay.sh      # starts the patched Dolphin and Minecraft together
```

Use your Wii Remote mapping on the title screen and file select. Once you pick a save, keyboard
and mouse take over (Ctrl+G switches back and forth by hand). Once you are in a level, a planet
appears above Mario. **P** lands you on it.

| Input | Action |
|---|---|
| WASD | stick |
| Space | A |
| Shift | Z |
| Ctrl | C |
| Esc | Minecraft's pause menu (SMG2's own from there); + in SMG2's menus |
| Tab | − |
| Mouse | look and aim (the pointer) |
| Left click / right click | spin / B, or break / place with an item in hand |
| F | spin, always |
| 1–9 | hotbar: pickaxe, blocks, buckets |
| E | inventory (`/gamemode creative` for the creative one); the mouse is its pointer |
| T | chat |
| F5 | perspective |
| F6 | Mario's movement / Minecraft's |
| F3+B | Mario's collision |
| Ctrl+G | give the game back to your Wii Remote |

## Tests

```sh
make -C protocol                                  # the protocol's layout
syati/test.sh                                     # the module's pure logic
(cd fabric && ./gradlew test)                     # the mod
dolphin/galaxycraft/build/galaxycraft_host_tests  # the Dolphin bridge (built by its CMake)
tools/gxvoxel.sh                                  # end to end in the real game: planets
tools/gxvoxel.sh movement                         # end to end: pause menu, Minecraft movement, /skin
tools/gxfit.sh                                    # end to end: Mario's fit and stillness
```

The end-to-end scripts drive a separate, headless Dolphin through `tools/gxdev.py`. That Dolphin
shares `/dev/shm/galaxycraft_v1` with `gxplay.sh`, so don't run them while playing.

## Layout

| Path | What |
|---|---|
| `fabric/` | Minecraft mod: bridge, Mario mode, voxel planets, fluids, meshing to GX display lists and KCL |
| `dolphin/` | Dolphin patch (`patches/`) and the host bridge (`galaxycraft/`) |
| `syati/` | The SMG2 module: planets, gravity, collision, camera, Mario's radius patches |
| `protocol/` | Shared-memory protocol |
| `tools/` | Dev harness, routes through the game, Steve's model, test scripts |
| `docs/` | Design notes and plans per phase (in Spanish) |

## License

[MIT](LICENSE). *Super Mario Galaxy 2* is © Nintendo and Minecraft is © Mojang/Microsoft. This
project includes none of their assets.
