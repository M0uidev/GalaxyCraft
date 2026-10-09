# Soundtrack player and dynamic space/planet music (design)

2026-10-09. From the user: use Soundtrack Player (wd40, MIT, Fabric 1.21.11) as the baseline for an
in-game music player; put the Super Mario Galaxy 2 songs in its playlist; the music follows where
you are (space or planet) with smooth transitions and a cooldown. "It should feel epic."

## Goal

1. A soundtrack player in Minecraft: playlist, play/pause, previous/next, shuffle, loop, volume,
   a Now Playing toast, a mini player on the pause menu, keybinds.
2. The SMG2 songs in that playlist, taken from the player's own disc (never shipped).
3. Automatic music with two moods, **space** and **planet**, switching with a dwell time, a
   cooldown and a crossfade. All of those are settings; each can be turned off or tuned.
4. A Station Core option to pick the music of that station.

Success: on a probe run, flying from a planet to space and back plays the right mood's track with a
smooth crossfade and no switch inside the cooldown; settings change that behaviour live; a station
with a pinned track plays it; a track loops at its own loop points with no click.

Out of scope (the user: "adding too much stuff right now is not worth it"): biome music (desert,
ice...), underwater/night/danger moods, layered (`*_multi`) track mixing, mp3/flac custom tracks,
music from SMG1. The catalog keeps a `tags` field (e.g. `desert`) so biome music can come later
without reclassifying.

## What exists

- Minecraft 26.3 Fabric client mod (`fabric/`), SMG2 in Dolphin. The galaxy scene plays no SMG2
  stage music and the host mutes Dolphin while entering; Minecraft is the only thing to hear.
  (Never call `MR::stopStageBGM` per frame: it froze the game.)
- The launcher extracts files from the player's `.rvz` with `dolphin-tool extract`
  (`launcher/src/main/installer.js`).
- The disc has 96 `AudioRes/Stream/*.ast` (996 MB). Header read: `STRM`, format 1 = **16-bit PCM
  big-endian**, 32 kHz, 2 channels (4 for `*_multi`), loop start/end in samples. No ADPCM, no
  encoder needed.
- Flat stations (`voxel/StationStore`) have a Station Core block with a menu.
- Soundtrack Player's jar targets 1.21.11 and cannot be dropped into 26.3. Its design is the
  baseline; its code is MIT and can be ported where useful (`SoundtrackPlayerScreen`,
  `PlaylistScreen`, `NowPlayingHUD`, `MiniPlayerWidget`, `KeyBindings`, config screen).

## Design

### Engine (`dev.moui.galaxycraft.music`)

One streaming engine on Minecraft's OpenAL (LWJGL is already loaded; no new libraries).

- `AstFile`: parses the header (channels, rate, samples, loop start/end), reads PCM frames, byte
  swaps to little-endian. Pure Java, unit tested on a synthetic file.
- `PcmStream`: a track being played. Produces buffers; at the loop end it continues at the loop
  start inside the same buffer (sample accurate, no gap). Tracks without loop flag stop (or go to
  the next track).
- `MusicEngine`: two decks (A and B). Each deck is an OpenAL source with a queue of ~4 buffers
  of ~0.5 s, refilled by one low-priority streaming thread. Gain per deck is set every client tick
  and ramped per frame. Pause is `alSourcePause` (true pause/resume). Master gain = soundtrack
  volume x Minecraft's Music slider x master slider. One thread, no allocation per buffer (buffers
  recycled), so no GC spikes while flying.
- `Crossfade`: pure equal-power curves (cos/sin) for a given duration; unit tested.

### Mood logic

`MoodDirector` is a pure state machine (no Minecraft types): input `(nowMillis, wantedMood,
settings)`, output "keep" or "switch to mood M". Unit tested with a fake clock.

- **Wanted mood.** `MoodSensor` reads the game each tick: space unless the player is in a voxel
  planet's gravity (the plan will confirm the exact signal in `gravity/GravityFrame`). Inside a
  flat station the wanted mood is the station's setting (default space).
- **Dwell.** The wanted mood must stay different for `dwellSeconds` before it counts (default 5).
- **Cooldown.** After a switch, further switches wait `cooldownSeconds` (default 120). A pending
  change is only applied if it is still wanted when the cooldown ends. Going into a planet and out
  inside the cooldown changes nothing.
- **Player picks bypass everything.** A manual pick (player screen, Station Core) plays at once,
  with a crossfade, and pins playback until the player presses Auto.
- **Choosing a track.** From the mood's pool of enabled tracks, shuffled without repeating until
  the pool is used up, never the track that just played.
- **When the track ends.** The next track of the current mood crossfades in before the end if
  the track has no loop, otherwise it loops until a mood switch, then the pool continues.
- **Crossfade.** `crossfadeSeconds` (default 4). The new track starts at its beginning (intro
  included) while the old fades out; with 0 it is a cut.

### Music source (SMG2, Minecraft, both, or random)

A setting, **Music source**, decides which games' songs the automatic music uses:

| Source | Space | Planet |
|---|---|---|
| Both (default) | SMG2 space songs | SMG2 planet songs **and** Minecraft's music, mixed in one pool |
| Super Mario Galaxy 2 only | SMG2 space songs | SMG2 planet songs |
| Minecraft only | Minecraft's music everywhere (no silence in space) | Minecraft's music |
| Random | any song of either game, no criteria: space and planet make no difference | same |

In every source the cooldown, dwell and crossfade apply (Random has no moods, so it only
changes track when one ends, or when the player skips). Minecraft's songs are `source: "minecraft"`
catalog entries (mood `planet` by default; in "Minecraft only" and "Random" the mood is ignored).
They are played by **our engine**, not by vanilla's `MusicManager`: the engine decodes Minecraft's
own `sounds/music/**` ogg files (found through the resource manager, so a resource pack's music
is respected) with LWJGL's `STBVorbis` pull API, streaming, no whole-file decode. That gives the
two games one crossfade, true pause, one volume and one player list. The mixin
silences vanilla's `MusicManager` (and its jukebox/menu music stays untouched). The plan must
verify that the resource manager exposes those files; fallback: read the files from the assets
index in the game directory, the way the baseline mod does.

### Catalog and settings

`soundtrack/tracks.json` in the game directory (written by the install step, edited by the
player). One entry per song:

```json
{ "id": "galaxy02", "title": "Yoshi Star Galaxy", "file": "SMG2_galaxy02_strm.ast",
  "mood": "planet", "tags": [], "enabled": true }
```

`mood` is `space`, `planet` or `null` (not in any automatic pool, still in the manual library).
Tracks not yet classified are `null`, so nothing plays by itself that the user did not choose.
The user's classification so far (titles; files are matched when importing):

| Mood | Songs |
|---|---|
| space | Sky Station Galaxy, Unknown Star, Starship Mario Launch!, The Starship Travel, Puzzle Plank Galaxy, Tip TV, Wild Glide Galaxy, Cosmic Cove Galaxy, Slide, Cloudy Court Galaxy, World 5, Space Storm Galaxy, Sweet Mystery Galaxy |
| planet | Another Story, Yoshi Star Galaxy, Starship Mario, Hightail Falls Galaxy, World 3, Freezy Flake Galaxy, Starship Mario 2, World 4, Honeybloom Galaxy, Starshine Beach Galaxy, Starship Mario 3, Throwback Galaxy, World S |
| later (desert) | Slipsand Galaxy (`tags: ["desert"]`, `mood: null`: stays out of every automatic pool until biome music exists) |

`config/galaxycraft-soundtrack.json` (and the mod's settings screen, ported from the baseline):

| Setting | Default | Range |
|---|---|---|
| Automatic music | on | on/off (off: only what the player picks) |
| Music source | Both | Both / SMG2 only / Minecraft only / Random |
| Cooldown between switches | 120 s | 0 (off) to 600 s |
| Dwell time | 5 s | 0 to 60 s |
| Crossfade | 4 s | 0 (cut) to 15 s |
| Soundtrack volume | 100 % | 0 to 100 % (times the Music slider) |
| Show Now Playing | on | toast corner, progress bar, mini player (baseline options) |

### Station Core music

The Station Core's menu gets a **Music** button: *Space music* (default), *Planet music*, a
specific track, or *Silence*. Stored per station in `StationStore`. While you are in that station
the wanted mood is that setting. The choice applies at once (a player pick, so no cooldown).

### Player UI (ported from the baseline)

A keybind opens the player: library list with a mood filter (Space / Planet / All), search,
context menu to set a track's mood or enable/disable it, transport buttons, shuffle/loop,
volume, Auto button. Keybinds: open, play/pause, next, previous, volume up/down, shuffle, loop.
Now Playing toast (title, "Super Mario Galaxy 2") and mini player on the pause menu.
Mixin: Minecraft's `MusicManager` stops starting its own tracks while automatic music is on.

### Install (later, once classified)

The launcher's disc step copies the `.ast` files listed in `tracks.json` from `AudioRes/Stream` to
`<game>/soundtrack/` (about 12 MB per song). Nothing is copied until the user finishes classifying;
until then the engine is developed and tested against a synthetic `.ast` and the 3 Starship Mario
files extracted into the scratchpad.

## Testing

- JUnit (fabric, plain): `AstFile` header/loop/byte swap, `PcmStream` loop seam, `Crossfade`
  curves, `MoodDirector` (dwell, cooldown, pending change dropped, bypass, cooldown 0, dwell 0).
- Gametest probe `SoundtrackProbe`: with the engine on a null audio device, drives the sensor
  through planet to space to planet and reads decisions; and a real-device run for the listening
  check.
- Manual: the user listens (crossfade feel, loop seams) and confirms before merge.

## Doubts for the user

Settled: Slipsand stays out; unclassified songs are out of every automatic pool; Minecraft's music
plays on planets (not in space) under "Both".

Open: titles to files: the in-game names above are matched to `.ast` files by a draft list the user
   corrects (the user will check); a few (Tip TV, Slide, Another Story, The Starship Travel, Unknown Star, World 3/4/5)
   are event/map tracks whose filename does not say the title.
