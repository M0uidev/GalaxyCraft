# The desktop launcher: a Minecraft Launcher of our own, for Linux and Windows

2026-10-07. Status: built on `feat/launcher` from the user's brief ("a launcher similar to the
original Minecraft launcher, customizable, Linux first, then Windows; one update releases both").
To be tested on Linux by the user's local Claude Code before merging; Windows is covered by CI
until someone runs it on a Windows PC.

## Goal

Opening Super Minecraft Galaxy opens a **launcher**, as Minecraft does: a dark window with a
sidebar, a big piece of art, news, and a green **PLAY** button at the bottom with the installation
to play next to it. Pressing PLAY starts what `tools/gxplay.sh` starts today (the patched Dolphin
and Minecraft together), on Linux and on Windows, from one codebase. Releasing a new version is
one command: CI builds the Linux and Windows launchers and publishes them in one GitHub Release,
and launchers already installed update themselves from it.

It does not replace "Minecraft as the launcher" (spec 2026-10-05): PLAY still opens Minecraft's
title screen over Dolphin, with its worlds as galaxies. The desktop launcher is the step before.

## What the official launcher has, and what ours keeps

The Minecraft Launcher (2022+) is a Chromium-based app: a left sidebar of products, tabs on top
(Play, Installations, Skins, Patch Notes), a full-bleed artwork, and a bottom bar with the
installation picker, the PLAY button and the account. Installations are profiles: a name, an
icon, a version, a game directory, Java and JVM arguments, a resolution. Settings choose whether
the launcher stays open while playing, and whether to show the game's log.

| Official launcher | Ours |
|---|---|
| Products in the sidebar | One product, plus News, Settings, and a "What's new" footer |
| Play tab: art, PLAY, installation picker | Same, with original art (a voxel planet in space, drawn by code; no Mojang or Nintendo assets) |
| Installations: name, icon, version, game dir, Java, JVM args, resolution | Name, block icon, game directory (each installation can keep its own worlds), Java home, Dolphin options (dual core, fullscreen), extra Gradle/Dolphin arguments, and the game's settings |
| Skins | Skin tab: a Minecraft account's name goes into the installation's `galaxycraft.properties` (`skin`), the same setting `/skin` writes |
| Patch Notes | Generated from `ROADMAP.md`'s Done table at build time (player wording, no commits), plus the bundled `content/news.json` |
| News | `launcher/content/news.json`, fetched from `master` on GitHub when online, the bundled copy otherwise: posting news is editing one file |
| Launcher settings | Keep open / hide / close on PLAY, show the log, accent color, background, sound on PLAY, check for updates, the game folder |
| Log window | A console drawer with Dolphin's and Minecraft's output |
| Microsoft account | Not in this version: Minecraft still runs through Gradle's dev client (see "Later") |

## Customization

Everything a player or the developer might want to change without code:

- **Themes**: accent presets (Grass, Star Bit, Luma, Redstone, Amethyst, Gold) or any color.
- **Backgrounds**: built-in scenes drawn by code (Home Planet, Nebula, Night Sky, Sunrise) or any
  image file from disk.
- **Installations** with their own icon, worlds and options; duplicated, renamed, deleted.
- **Game settings** of each installation (movement, skin, distances, particles) edited from the
  launcher, written to the same `config/galaxycraft.properties` the game's pause menu writes.
- **News**: `launcher/content/news.json`, one entry per post (title, date, tag, body, optional
  image URL and link).

## How PLAY works (a port of `tools/gxplay.sh`)

`src/core/launchplan.js` builds a plan (pure data: files to seed, processes, environment) and
`src/main/runner.js` carries it out. Per installation:

1. **Checks** (`preflight.js`): the game folder is a GalaxyCraft checkout, the patched Dolphin is
   built, the module is built (`syati/build/galaxycraft.json`), a Java 25 is found, Gradle's
   wrapper for this OS exists. Each failure names its fix (`dolphin/build.sh`, ...). PLAY is
   disabled with the reason shown instead of failing later.
2. **Dolphin's folder** (shared by installations, `<data>/dolphin`): seeded once from
   `tools/dolphin-play/*.ini`, plus the player's own `GFX.ini` and `Hotkeys.ini` and SMG2's save
   from their usual Dolphin, as `gxplay.sh` does.
3. **Leftover hidden Minecrafts** are stopped (`galaxycraft.hidden=true` in the command line):
   `pkill -f` on Linux, a CIM query + `taskkill /T /F` on Windows.
4. **Dolphin** with `GALAXYCRAFT=1 GALAXYCRAFT_BOOT=space`, the `-C` overrides of `gxplay.sh`,
   and the installation's options.
5. **Minecraft**: `runClient -PgalaxycraftHidden -PgalaxycraftGameDir=<game dir>` through
   Gradle's wrapper jar, run by the found Java itself as `gradlew` does (`java -jar
   gradle/wrapper/gradle-wrapper.jar ...`): no shell script, so the same on both systems.
   `JAVA_HOME` set. (`fabric/gradlew.bat` is added too, for working by hand on Windows.)
6. Closing either side closes the other, each as a whole process tree (a process group on Linux,
   `taskkill /T` on Windows), then leftovers are stopped again.

## Where things live

| | Linux | Windows |
|---|---|---|
| Game data (`<data>`) | `$XDG_DATA_HOME/galaxycraft` (`~/.local/share/galaxycraft`) | `%APPDATA%\galaxycraft` |
| Default installation's Minecraft | `<data>/minecraft` (what `gxplay.sh` uses: same worlds) | same |
| Other installations | `<data>/installations/<id>` | same |
| Launcher's own state | Electron's userData: `launcher.json` (installations, settings) | same |
| Dolphin binary | `dolphin/build/Binaries/dolphin-emu` | `dolphin/build/Binaries/Dolphin.exe`, `.../x64/Dolphin.exe`, `Binary/x64/Dolphin.exe` |
| Java 25 | `JAVA_HOME`, `~/.local/opt/jdk-25*`, `/usr/lib/jvm/*25*` | `JAVA_HOME`, `Program Files\{Java,Eclipse Adoptium,Microsoft,Zulu}\jdk-25*` |
| Usual Dolphin | `~/.config/dolphin-emu`, `~/.local/share/dolphin-emu`, `~/.dolphin-emu` | `Documents\Dolphin Emulator`, `%APPDATA%\Dolphin Emulator` |

The paths module takes the platform and environment as arguments, so the Windows paths are unit
tested on Linux. `$GXC_DATA_DIR` moves the game data (tests use it so they never touch the
player's worlds).

## Releases and updates

- `launcher/` is an Electron app (plain JavaScript, no bundler), packaged by electron-builder:
  Linux AppImage and `.deb`, Windows NSIS installer.
- `.github/workflows/launcher.yml`: on every push touching `launcher/`, unit tests and a smoke
  test on **ubuntu and windows**, from source and packaged: Playwright opens the real app, goes
  through every tab, makes an installation, writes game settings and a skin, and plays a fake
  game (shell scripts on Linux; on Windows a little C# program compiled with the .NET
  Framework's `csc`) from PLAY to both sides closed. This is how Windows is checked without a
  Windows PC.
- A tag `v<version>` (made by `npm run release -- <patch|minor|major>`) builds both systems and
  publishes one GitHub Release with both installers. Plain `v` tags because electron-updater
  reads the repository's latest release: a later game release would have to share this stream.
- Installed launchers check that release feed (electron-updater, GitHub provider) and offer the
  update; the AppImage and the Windows installer update in place (the `.deb` too, asking for the
  password).
- The Windows installer is unsigned: SmartScreen warns until a code-signing certificate is added.

## Later

- **Release mode**: today PLAY needs a built checkout (the game folder). A release bundle of the
  built Dolphin, module and mod, downloaded by the launcher, would let players without the repo
  play. Needs Dolphin built on Windows first (`docs/WINDOWS.md`).
- **Microsoft account sign-in** and launching Minecraft without Gradle (version manifest,
  libraries, assets, Fabric's loader): needs an Azure app registration.
- Building from the launcher (Dolphin, module) with its log, once the build scripts are
  cross-platform.
