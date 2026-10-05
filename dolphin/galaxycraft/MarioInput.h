#pragma once
#include "Shm.h"

namespace gxc
{
// SDL scancodes (what InputState carries) of the keys Mario mode reads.
enum : int
{
  SC_A = 4,
  SC_D = 7,
  SC_F = 9,
  SC_S = 22,
  SC_W = 26,
  SC_ESCAPE = 41,
  SC_TAB = 43,
  SC_SPACE = 44,
  SC_LCTRL = 224,
  SC_LSHIFT = 225,
  SC_RCTRL = 228,
  SC_RSHIFT = 229,
};
// Protocol mouse mask: bit n = SDL button n.
constexpr u32 MOUSE_LEFT = 1u << 1, MOUSE_RIGHT = 1u << 3;

// What the Wii Remote + Nunchuk override reports this frame.
struct WiimoteState
{
  bool a = false, b = false, minus = false, plus = false, home = false, one = false, two = false;
  bool c = false, z = false;
  float stick_x = 0, stick_y = 0;  // -1..1, y up
  // Minecraft's feel: Ctrl (sprint) or Shift (sneak) held, for the game's speed (GXC_MBX_MC_*).
  bool sprint = false, sneak = false;
  // Minecraft's feel: a movement key (WASD) is held; let go, the game stops holding his speed.
  bool walking = false;
  bool shake = false;
  // true: IR at (0, MarioInput::CENTER_IR_Y), under Minecraft's crosshair; false: no override
  // (the user's pointer mapping)
  bool center_ir = false;
};

// Keyboard and mouse -> Wii Remote (spec §3): WASD stick, Space A, Shift Z, Ctrl C, Esc +, Tab -,
// right click B, left click shake (and A in menus), F shake. With an item in Minecraft's main hand
// the clicks break and place blocks instead (voxel planets spec): no shake, no B.
class MarioInput
{
public:
  // Frames a click keeps shaking, so a quick click still reads as a spin.
  static constexpr int SHAKE_MIN_FRAMES = 6;
  // IR Y (-1..1, up) that puts SMG2's star pointer at the screen centre, where Minecraft draws its
  // crosshair. Dolphin holds the emulated remote below the sensor bar (Vertical Offset 10 cm,
  // Total Pitch 20°, the defaults), so IR (0,0) lands 8% of the screen above it. Measured live.
  static constexpr float CENTER_IR_Y = -0.156f;

  // Minecraft's feel (GXC_PLAYER_MC_FEEL): how far the stick leans walking, sprinting (Ctrl) and
  // sneaking (Shift). The game then holds Mario at Minecraft's speed (sprint and sneak go to it
  // in WiimoteState); the lean picks his gait, the nearest of his steps of speed (measured
  // through this override: 1.46, 2.43, 3.89, 4.87, 5.85 blocks/s, at leans of 22, 27, 32, 38
  // and 42 steps of 127) above Minecraft's 4.317, 5.612 and 1.31 blocks/s.
  static constexpr float WALK_LEAN = 0.303f, SPRINT_LEAN = 0.335f, SNEAK_LEAN = 0.18f;
  // Space held keeps jumping, as Minecraft's does: A is pressed this many frames, then let go as
  // many, so each landing takes a new press.
  static constexpr int JUMP_PULSE_FRAMES = 4;

  // keys: SDL scancode bitmap (64 bytes); buttons: protocol mouse mask; in_game: game/menu rule;
  // free_pointer: the Galaxy view, where the mouse points even while playing; item_active: the
  // clicks belong to Minecraft; mc_feel: Minecraft's feel (speeds by lean, Ctrl sprints and Shift
  // sneaks instead of C and Z, no spin from the clicks or F). Called once per Wii Remote frame.
  WiimoteState Update(const u8 keys[64], u32 buttons, bool in_game, bool free_pointer = false,
                      bool item_active = false, bool mc_feel = false);
  // Mode switch: everything released, counters cleared.
  void Reset();
  // Minecraft's feel: other leans than WALK_LEAN, SPRINT_LEAN, SNEAK_LEAN (ctl lean, measuring).
  void SetLeans(float walk, float sprint, float sneak) { m_leans = {walk, sprint, sneak}; }

private:
  int m_shake_frames = 0;  // frames left of the current shake
  bool m_left_was_down = false;
  bool m_f_was_down = false;
  int m_jump_frames = 0;  // Minecraft's feel: frames Space has been held
  struct Leans
  {
    float walk, sprint, sneak;
  } m_leans = {WALK_LEAN, SPRINT_LEAN, SNEAK_LEAN};
};
}  // namespace gxc
