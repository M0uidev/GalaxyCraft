# Brief: playing SMG2's own Toad voice in the mod (reverse-engineering task)

Status 2026-10-12: not started. The mod plays a pitched Minecraft villager sound per letter; players
dislike it and want the game's Toad voices. This is the task to replace that, in a dedicated session.

## Goal

`ToadVoice.speak(ToadType type, char c, int index)` (fabric/src/client/.../ToadVoice.java) is the only
place a Toad's sound is played. Make it play clips that come from the player's own disc (the game's
Kinopio "voice": the short babble Toads make when they talk), exported once by the launcher into the
shared folder, never shipped in the repo (copyright: same rule as `Kinopio.arc` and the soundtrack).

## Where the voice lives (what the 2026-10-12 survey found on the USA disc)

Extract with `dolphin/build/Binaries/dolphin-tool extract -i <disc.rvz> -o <dir>`; files under
`DATA/files/AudioRes/`:

- `Waves/*.aw` (607 files, names like `B104take_0.aw`): the wave banks: raw sample data, many clips per
  file, in Nintendo's AFC (4-bit ADPCM, 9 bytes per 16 samples) mostly; the index of clips is NOT in the
  `.aw` itself but in the WSYS tables in `Seqs/JaiSeq.arc` / the "JaiInit" archive, one entry per wave
  id with offset, size, sample rate, format and loop.
- `Seqs/JaiSeq.arc` (BMS sequences: JAudio's bytecode), `Info/SoundIdToInstList.arc` (sound id ->
  instrument/bank/wave group), `Info/ActionSound.arc` (what an actor's action plays; the string
  `SvKinopio` / `KINOPIO_` labels live here: "Sv" = the stage object's own sounds), `Info/JaiMe.arc`.
- `Stream/*.ast`: streamed music only (the soundtrack feature already decodes these as PCM16 BE).
  `SMG2_ev_kinotan_strm.ast` is a Toad-themed song, not the voice.
- Per-stage `AudioRes/Waves` are loaded by group (`B..take_N`: stage/gimmick groups). The Toad's
  babble should be in a "common" group loaded for the star-hub / Kinopio actors; finding which one is
  step 1.

## Plan

1. **Map the sound id.** Find the sound ids the game plays for a talking Kinopio (the actor
   `Kinopio`/`KinopioTalk` in ActionSound.arc; names like `SE_SV_KINOPIO_*`/`ES_SV_KINOPIO_*`;
   `strings` on the archives and the game's own `.rel`/`.bcsv` text help; the SMG2 community's
   `JaiSeq`/`WSYS` documentation and Whitehole/Syati sources list the ids).
2. **Resolve to waves.** Parse the WSYS (wave system) in the bank archive: wave id -> (aw file, offset,
   size, sample rate, AFC/PCM16/PCM8). Parse the INST/BNK instrument for the sound's key-range -> wave
   and its pitch/base note (a voice may be one wave pitched by key, which is good for per-letter pitch).
3. **Decode AFC.** Implement the AFC ADPCM decoder (Nintendo "AFC": 16-sample frames of 9 bytes: 1
   header byte = scale (4 bits) + coefficient index (4 bits), 8 bytes of 4-bit nibbles; two-tap
   predictor with a 16x2 coefficient table; the same maths as DSP-ADPCM's "AFC" variant — see
   `vgmstream`'s `afc.c`, `JAudio2`'s `JASWaveArc`/`JASAramStream` and `libjaudio`). Output PCM16.
   Pure Java in `fabric/src/main/.../audio/` with golden tests against a small clip decoded by
   vgmstream (do not commit disc audio: test with a synthetic frame built in the test).
4. **Launcher export.** `launcher/src/core/soundtrack.js` already extracts `AudioRes/Stream` from the
   disc into the shared folder at install: add `AudioRes/Waves` + the bank archives needed, or (better)
   do steps 1-3 at export time in the launcher (node) and write the few small `.ogg`/`.wav` clips
   (e.g. `toad-voice-0..N.wav`) into `~/.local/share/galaxycraft/voice/`. Smaller, no decoder in the mod.
5. **Play.** `ToadVoice.speak` loads the clips once (Java Sound or Minecraft's SoundEngine via a
   dynamic `SoundInstance`/resource-pack-free path: the soundtrack feature's `PcmSource` + Java Sound
   sink is the existing player) and plays clip `(index + type)` pitched by `ToadVoicePlan.pitch`.
   If the folder is missing it falls back to today's villager sound.

## Done when

- A talking Guide, Miner, Explorer, Technician and Mailman each babble with the disc's Toad voice at
  slightly different pitch, one blip per `ToadVoicePlan.blip` letter, no files from the disc in git.
- A missing export falls back silently; `launcher` tests cover the export with a fake disc (as the
  soundtrack's do).

## Notes

- Keep it cheap: one or two babble clips pitched are enough; the game's Toad voice is a few short
  "mamma-mia"-style syllables.
- Do not decode on the game's thread; decode at export or on first use on a worker.
