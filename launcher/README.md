# Super Minecraft Galaxy Launcher

The game's own launcher, in the spirit of the Minecraft Launcher: news, patch notes,
installations, skins, settings, and the big green **PLAY** button. One codebase for **Linux and
Windows** (Electron, plain JavaScript, no bundler). Design: [the spec](../docs/superpowers/specs/2026-10-07-galaxycraft-desktop-launcher-design.md).

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

## Release a new version (Linux and Windows at once)

```sh
cd launcher
npm run release -- patch     # or minor, major, 1.2.3
```

That bumps the version, commits, tags `v<version>` and pushes. GitHub Actions
(`.github/workflows/launcher.yml`) then tests and packages on Linux and Windows and publishes one
GitHub Release with:

- `SuperMinecraftGalaxy-Launcher-<version>-linux-x86_64.AppImage` (updates itself) and `...-linux-amd64.deb`;
- `SuperMinecraftGalaxy-Launcher-<version>-win-x64.exe` (installer; updates itself).

Installed launchers check that release feed, download the new version in the background and
install it when restarted. Every push touching `launcher/` runs the same tests and packaging on
both systems without releasing, so a Windows break shows up in CI even without a Windows PC.
The installers to try are in the run's artifacts, with screenshots of every screen.

Build installers locally: `npm run dist:linux` (on Linux) or `npm run dist:win` (on Windows).

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
| `src/core/` | Pure logic, unit tested: paths per system, Java discovery, installations, game settings (`galaxycraft.properties`), PLAY's plan, checks, patch notes |
| `src/main/` | Electron's main process: window, IPC, running the game (process trees on both systems), updates |
| `src/renderer/` | The page: HTML, CSS, the art, the app |
| `content/` | `news.json` (and `patch-notes.json`, generated at packaging) |
| `scripts/` | Smoke test, patch notes, release, icon |
| `test/` | `node --test` unit tests |

Environment variables, for development: `GXC_ROOT` (the game folder), `GXC_DATA_DIR` (the game
data folder), `GXL_USER_DATA` (the launcher's own state), `GXL_OFFLINE=1` (no network).

## Not yet

- **Playing without the repository.** PLAY needs a checkout with Dolphin and the module built.
  A downloadable game bundle comes once Dolphin builds on Windows ([docs/WINDOWS.md](../docs/WINDOWS.md)).
- **Microsoft account sign-in**: Minecraft still starts through Gradle's dev client.
- **Code signing** for Windows: SmartScreen warns about the installer until there is a certificate.
