# Super Minecraft Galaxy Launcher

The game's own launcher, in the spirit of the Minecraft Launcher: news, patch notes,
installations, skins, settings, and the big green **PLAY** button. One codebase for **Linux and
Windows** (Electron, plain JavaScript, no bundler). Design: [the spec](../docs/superpowers/specs/2026-10-07-galaxycraft-desktop-launcher-design.md).

## Two ways to play

| | For | PLAY uses |
|---|---|---|
| **The released game** (default in an installed launcher) | Players | The game the launcher installed from the latest GitHub Release, the player's own Super Mario Galaxy 2, and their Minecraft account |
| **My game folder** (default from source, or when a folder or `GXC_ROOT` is set) | You, developing | Your checkout, built with `dolphin/build.sh` and `syati/build.sh`, as `tools/gxplay.sh` does |

Settings > Game > Play switches between them.

A new player: opens the launcher, **SIGN IN** (Microsoft account that owns Minecraft: Java
Edition), **CHOOSE GAME** (their own Super Mario Galaxy 2, USA: `.iso`, `.rvz`, `.wbfs`...),
**INSTALL**, **PLAY**. When a new release is out, the button says **UPDATE**. The launcher
installs:

- the game's files from the release (`game.json` lists them, all checked with SHA-256): the SMG2
  module, the patched Dolphin for their system, the mod;
- Steve's archives and GalaxyCraftSpace, **made on their computer from their own disc** through
  patches (`GXD1`) that hold none of the disc's data;
- Minecraft, Fabric and Java from Mojang's and Fabric's servers, as the official launcher does.

A version installs next to the one in use; the one before stays. Nothing of the player's disc is
ever uploaded.

## Run it from the repository

```sh
cd launcher
npm install
npm start            # the launcher; PLAY uses this checkout (dolphin/build.sh and syati/build.sh first)
npm test             # unit tests (paths on both systems, PLAY's plan, settings, processes)
xvfb-run -a npm run smoke   # the real app, clicked through, with a fake game played (plain `npm run smoke` with a display)
```

PLAY does what `tools/gxplay.sh` does (Dolphin and Minecraft together, closing one closes the
other) and uses the same folder, `~/.local/share/galaxycraft` (`%APPDATA%\galaxycraft` on
Windows), so your worlds are the same ones.

## Release a new version (launcher and game, Linux and Windows at once)

```sh
cd launcher
npm run release -- patch     # or minor, major, 1.2.3
```

That bumps the version, commits, tags `v<version>` and pushes. If the SMG2 module's sources
changed since `release/module` was made, it stops and asks you to make it again first, on your
machine (it needs the CodeWarrior toolchain and your disc, which CI does not have):

```sh
npm run pack-game -- --game "/path/to/Super Mario Galaxy 2.rvz"    # then: git add release/module && git commit
# or in one go:
npm run release -- patch --game="/path/to/Super Mario Galaxy 2.rvz"
```

GitHub Actions (`.github/workflows/launcher.yml`, `dolphin.yml`) then builds everything else and
publishes one GitHub Release:

| File | What |
|---|---|
| `SuperMinecraftGalaxy-Launcher-<v>-linux-x86_64.AppImage`, `...-linux-amd64.deb` | The launcher for Linux (updates itself) |
| `SuperMinecraftGalaxy-Launcher-<v>-win-x64.exe` | The launcher for Windows (updates itself) |
| `game.json` | What the game release is made of; launchers read it from the latest release |
| `module-<v>.tar.gz` | `release/module`: the SMG2 module and the disc patches |
| `dolphin-linux-x64-<v>.tar.gz` | The patched Dolphin with Qt and its libraries inside |
| `galaxycraft-<v>.jar` | The mod (Fabric API comes from Fabric's Maven) |

Installed launchers update themselves, then offer UPDATE for the game. Every push touching
`launcher/` or `fabric/` runs the tests on Linux and Windows, including real Minecraft started
with the mod from the launcher's own downloads; `dolphin/` changes build the Dolphin bundle.

**Windows players** get the launcher now, and the game as soon as a release carries a Windows
Dolphin (`dolphin-win32-x64-<v>.tar.gz`): until then the button says COMING SOON. The Dolphin
patch still needs its Windows port ([docs/WINDOWS.md](../docs/WINDOWS.md)).

## Microsoft sign-in (one-time setup)

Players sign in with their Microsoft account, as in the official launcher. Mojang only lets
approved apps do that, so the launcher needs an app registration of its own:

1. In the [Azure portal](https://portal.azure.com) > *App registrations* > *New registration*:
   name it *SMG Launcher* (Mojang refuses app names with "Minecraft" in them; ours is registered so, client id in `content/config.json`), *Personal Microsoft accounts only*, platform
   *Public client/native (mobile & desktop)* with the redirect URI
   `https://login.microsoftonline.com/common/oauth2/nativeclient`. Under *Authentication*, allow
   public client flows.
2. Copy its *Application (client) ID* into `content/config.json` (`msaClientId`).
3. Ask Mojang to allow it for Minecraft's services: <https://aka.ms/mce-reviewappid> (the form
   asks for the client id). Until they approve it, sign-in stops with "app registration is not
   approved by Mojang yet".

`GXL_MSA_CLIENT_ID` overrides the file for testing. Tokens are kept encrypted with the system's
keychain (Electron safeStorage) and never written to the log.

## Make it yours

| What | Where |
|---|---|
| News | `content/news.json`: one entry per post (`title`, `date`, `tag`, `body` in a little markdown, optional `image`/`link`, `pinned`). Launchers read it from GitHub's `master`, so pushing it publishes the post, no new launcher needed. |
| Patch notes | Generated from `ROADMAP.md`'s **Done** table (and **Now**/**Next** for "Coming next"); also read from `master` live. |
| Accent color, background (built-in scenes or your own picture), animations, sound | Settings, in the launcher |
| Installations: name, block icon, worlds folder, Java, Dolphin options, game settings, skin | Installations and Skins tabs |
| The art (scenes, block icons) | `src/renderer/art.js`, all drawn by code |
| Colors, fonts, layout | `src/renderer/styles.css` (`:root` variables) |
| The app icon | `scripts/make-icon.py` writes `build/icon.png` |

## Layout

| Path | What |
|---|---|
| `src/core/` | Pure logic, unit tested: paths per system, Java discovery, installations, game settings (`galaxycraft.properties`), PLAY's plans, checks, patch notes, the disc (game id, Yaz0), GXD1 patches, Minecraft's files and command line, the sign-in chain, game.json, the button's state |
| `src/main/` | Electron's main process: window, IPC, running the game (process trees on both systems), the game installer, Minecraft's download, accounts, launcher updates |
| `src/renderer/` | The page: HTML, CSS, the art, the app |
| `content/` | `news.json` (and `patch-notes.json`, generated at packaging) |
| `scripts/` | Smoke test, real-Minecraft test (`mc-integration.js`), patch notes, release, `pack-game.js`, `game-manifest.js`, `module-hash.js`, icon |
| `test/` | `node --test` unit tests |

Environment variables, for development: `GXC_ROOT` (the game folder), `GXC_DATA_DIR` (the game
data folder), `GXL_USER_DATA` (the launcher's own state), `GXL_OFFLINE=1` (no network),
`GXL_GAME_MANIFEST` (another game.json), `GXL_MSA_CLIENT_ID`. From source only (never in an
installed launcher), the smoke test's `GXL_TEST_ACCOUNT` and `GXL_TEST_MINECRAFT`.

## Not yet

- **The game on Windows**: the launcher runs there and is tested there, but the patched Dolphin
  does not build for Windows yet ([docs/WINDOWS.md](../docs/WINDOWS.md)).
- **Mojang's approval** of the sign-in app (above): one form, then wait.
- **Code signing** for Windows: SmartScreen warns about the installer until there is a certificate.
- The Super Mario Galaxy 2 for Europe, Japan or Korea: the module is built for the USA version.
