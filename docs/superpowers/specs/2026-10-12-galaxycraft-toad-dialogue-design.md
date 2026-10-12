# Toad dialogue box: talk to a Toad while you keep moving — design

Date: 2026-10-12. Status: requested by the user after playtesting test/everything; no open questions.

## Goal

Today a Toad's lines arrive as chat messages and its trade opens at once. Make talking feel like a
classic game: a dialogue box at the bottom of the screen, text typed letter by letter with a
talking sound, and a few things to ask. **The player never stops**: it is a HUD overlay, not a
Screen, so the mouse stays grabbed and movement goes on.

## Decisions (user, via orchestrator, 2026-10-12)

1. **Box**: bottom of the screen, above the hotbar: the Toad's name, a portrait slot, the text with a
   typewriter effect; a click or Enter finishes the line being typed, then goes to the next.
2. **Sound**: a talk sound per letter group. SMG2's own Toad voice if the disc has it, else a pitched
   Minecraft sound behind one method (`ToadVoice.blip`) so the real one can be swapped in.
3. **Choices** inside the box after the greeting: 1 Trade, 2 Tell me about this place, 3 Where
   should I go?, 4 Bye. Number keys, or Up/Down + Enter / right click. Several lines per type and
   tier/star, from what `ToadDialogue` already has plus new "advice" lines.
4. **Keep moving**: HUD overlay; the Toad keeps turning to the player and talking; the box closes
   when the player is more than 8 blocks away, or any screen opens (Esc: the pause menu). Trade
   still opens Minecraft's merchant screen (closing the box first).

## Investigation: SMG2's Toad voice (2026-10-12)

The disc's `AudioRes` holds `Waves/*.aw` wave banks (607 files named by group, `B104take_0.aw`...),
`Seqs/JaiSeq.arc` (sequences), `Info/SoundIdToInstList.arc`, `ActionSound.arc` (the strings
`SvKinopio`/`KINOPIO_` are there), and `Stream/*.ast` (music; the soundtrack feature already reads
those). A Toad voice therefore needs: the sound id -> instrument -> wave id lookup (JAudio2 sequence and
bank formats) and an AFC ADPCM decoder for the `.aw` data, then a launcher step to export the clips.
That is a self-contained reverse-engineering task of its own, not feasible inside this feature.
Decision: ship the fallback now; `ToadVoice` is the single seam. If later exported to `ogg` clips in
the launcher's shared folder, `ToadVoice` plays those instead (same `blip(type, char)` signature).

## Model

- `ToadConversation` (pure, no Minecraft types): a script of pages (each a text), a typewriter clock
  (`CPS` letters per second), `advance()` (finish the typing, else next page, at the last page show
  the choices), `choose(i)`, and an outcome (`NONE`, `TRADE`, `CLOSED`). Deterministic with an
  explicit `tick(seconds)`.
- `ToadScript` (pure): builds a conversation for a `ToadType` and `ToadContext`: greeting pages
  (`ToadDialogue.lines`), place pages (star note + tier line + whether you have been here), advice
  pages (what to do next for this type and tier, the Warp Core / missing module notes when known).
  Text comes as lang keys; the client resolves them.
- `ToadVoice` (client): `blip(ToadType, char)` plays a pitched villager sound (pitch by letter and by
  type, only every second letter, never for spaces or punctuation). A pure `ToadVoice.shouldBlip(char,
  index)` is unit tested.
- `ToadDialogueHud` (client): draws the box from the active conversation (HudElementRegistry, after
  the hotbar). `ToadClient` owns the active conversation (one at a time), advances its clock per
  frame, reads input, and closes it.

## Input

Polled in the client tick the right click is already read in (`ToadClient.tick`): right click
advances / selects the highlighted choice, Enter the same, keys 1-4 choose, Up/Down move the highlight.
A number key also changes the hotbar slot: the slot is restored while the box is open. Left click is
left alone (the player can still hit things).

## Out of scope

Real portraits (a coloured square with the Toad's cap colour until then), the disc voice, a
written history of what you asked, Toads that answer in other languages than the game's.

## Testing

Unit tests for the conversation (typing, skipping, paging, choices, outcomes), the script (every
type x tier has pages, every key exists in `en_us.json`, texts fit the box) and the blip rule. The
Dolphin probe (later, when the slot is free): open the box, see letters appear, choose Trade and get the
merchant screen, walk away and see it close; a Minecraft-window screenshot of the box.
