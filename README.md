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
- **Fluids.** Water and lava buckets, ice that melts into water, and obsidian and cobblestone
  where water and lava meet. A classic cobblestone generator works.
- **Collision that feels like Minecraft.** Mario shrinks to Steve's width on planets, so he
  falls into 1×1 holes and fits in 1×2 tunnels. Press **F3+B** to see his collision.
- **`/fly`** for free flight, and `/galaxycraft planet spawn|tp|remove` to manage planets.

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

Once you are in a level, a planet appears above Mario. **P** lands you on it.

| Input | Action |
|---|---|
| WASD | stick |
| Space | A |
| Shift | Z |
| Ctrl | C |
| Esc | + |
| Tab | − |
| Mouse | look and aim (the pointer) |
| Left click / right click | spin / B, or break / place with an item in hand |
| F | spin, always |
| 1–9 | hotbar: pickaxe, blocks, buckets |
| T | chat |
| F5 | perspective |
| F3+B | Mario's collision |
| Ctrl+G | give the game back to your Wii Remote |

## Tests

```sh
make -C protocol                                  # the protocol's layout
syati/test.sh                                     # the module's pure logic
(cd fabric && ./gradlew test)                     # the mod
dolphin/galaxycraft/build/galaxycraft_host_tests  # the Dolphin bridge (built by its CMake)
tools/gxvoxel.sh                                  # end to end in the real game: planets
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
| `tools/` | Dev harness, routes through the game, Steve's model, the block atlas, test scripts |
| `docs/` | Design notes and plans per phase (in Spanish) |

## License

[MIT](LICENSE). *Super Mario Galaxy 2* is © Nintendo and Minecraft is © Mojang/Microsoft. This
project includes none of their assets.
