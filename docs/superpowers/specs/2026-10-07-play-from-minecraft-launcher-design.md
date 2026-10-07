# Play from the Minecraft Launcher (MVP)

Date: 2026-10-07. Status: approved by the user (MVP scope).

## Why

The launcher's own Microsoft sign-in needs Mojang to approve the "SMG Launcher" app (form sent
2026-10-07, no date promised, may be refused). Players must be able to play without it: they sign
in where they already do, in Mojang's official **Minecraft Launcher**, and the game still runs as
when our launcher's PLAY starts it (Dolphin + hidden Minecraft, closed together).

Minecraft and Dolphin only meet through shared memory (`/dev/shm/galaxycraft_v1`), so it does not
matter who starts Minecraft, as long as it runs our mod, Fabric, and `-Dgalaxycraft.hidden=true`.

## What the player does

1. Opens our launcher once: CHOOSE GAME (their Super Mario Galaxy 2), INSTALL (as today).
2. From then on: in the Minecraft Launcher, picks the **Super Minecraft Galaxy** installation and
   presses PLAY. Dolphin opens with the game; quitting either side closes the other.

## Pieces

### 1. INSTALL registers an installation in the Minecraft Launcher (launcher, `src/core` + `src/main`)

After a successful install (and again on each PLAY/UPDATE, so it stays in step with the installed
version), the launcher, as Fabric's own installer does:

- Finds `.minecraft`: Linux `~/.minecraft` (also the Flatpak `~/.var/app/com.mojang.Minecraft/.minecraft`
  if that one exists), Windows `%APPDATA%\.minecraft`. None, or no `launcher_profiles.json` in it:
  skip, and the PLAY area says to install the Minecraft Launcher (piece 4).
- Writes the Fabric version the release uses: `versions/<id>/<id>.json` from Fabric's meta
  (`/v2/versions/loader/<mc>/<loader>/profile/json`, the same versions as `game.json`), with an
  empty `<id>.jar` if Fabric's installer does that. The Minecraft Launcher downloads the libraries itself.
- Adds or updates one profile in `launcher_profiles.json`, keyed by a fixed id
  (`super-minecraft-galaxy`), leaving every other key and profile untouched: `name` "Super
  Minecraft Galaxy", `type` "custom", `lastVersionId` the Fabric version id, `gameDir` our
  installation's game folder (the same one our PLAY uses, mods already synced there by
  `installer.syncMods`), `javaArgs` the installation's memory args + `-Dgalaxycraft.hidden=true
  -Dgalaxycraft.startDolphin=true`, `icon` our icon as a `data:image/png;base64,` URI, `created`/
  `lastUsed` ISO dates (keep `created` if it exists). Written atomically (temp file + rename).
- Writes `<dataDir>/play.json` (piece 2).

Pure functions (profile entry, merged JSON, `.minecraft` candidates per system, play.json content)
live in `src/core/` with unit tests; file writing in `src/main/`.

### 2. `<dataDir>/play.json`: how to start Dolphin

`{ "format": 1, "cmd", "args", "cwd", "env" }`: exactly the Dolphin process of
`gamepack.playerPlan` (`env` only the variables PLAY adds: `GALAXYCRAFT`, `GALAXYCRAFT_BOOT`,
`QT_QPA_PLATFORM` on Linux, not the whole environment). The seeding PLAY does before starting
(`seed`: Dolphin's Config, SMG2 save) is done by the launcher at install time, so the mod only
has to start the process.

### 3. The mod starts Dolphin (fabric, client)

Only when `-Dgalaxycraft.startDolphin=true` (set by that profile alone, so `tools/gxplay.sh`, our
PLAY and the tests are unchanged). On `CLIENT_STARTED`:

- Reads `play.json` from the data dir (`GXC_DATA_DIR`, else `%APPDATA%\galaxycraft` / 
  `$XDG_DATA_HOME/galaxycraft` / `~/.local/share/galaxycraft`, as `launcher/src/core/paths.js`).
- Missing or unreadable: does not hide the window and shows a screen "Open the Super Minecraft
  Galaxy launcher and press INSTALL" (with Quit). The hidden flag is applied only once Dolphin started.
- Else starts Dolphin (`ProcessBuilder`, its output to Minecraft's log prefixed `[Dolphin]`).
- Dolphin exits: Minecraft stops (`client.scheduleStop()`).
- Minecraft stops (`CLIENT_STOPPING` and a JVM shutdown hook): Dolphin and its descendants are destroyed.

### 4. Our launcher without a signed-in account (renderer)

Where it now says SIGN IN, once the game is installed, it also says: "Not signed in? Play from the
Minecraft Launcher: choose **Super Minecraft Galaxy** and press PLAY." If no `.minecraft` was
found: "Install the Minecraft Launcher (minecraft.net), sign in, then press INSTALL again."

## Testing

- Unit tests: profile entry and merge (keeps other profiles and unknown keys, updates ours,
  keeps `created`), `.minecraft` candidates per system, play.json from a plan.
- Mod: a unit test for reading play.json / data dir if the module's tests allow it.
- Real run on Linux (local, needs the disc): a scratch `.minecraft` with a `launcher_profiles.json`,
  INSTALL into it, then start Minecraft as the Minecraft Launcher would with that profile's version,
  game dir and Java args (offline name is fine for the test): Dolphin opens with the game, closing
  Dolphin ends Minecraft and the other way round.
- The player's playtest: press PLAY in the real Minecraft Launcher.

## Later (not in the MVP)

Prism Launcher (one click through `prismlauncher --launch`), a "Start Minecraft with…" setting per
installation, our PLAY opening the other launcher, our launcher showing/stopping a Minecraft it did
not start.
