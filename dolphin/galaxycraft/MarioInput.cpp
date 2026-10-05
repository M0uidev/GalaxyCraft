#include "MarioInput.h"

#include <cmath>

namespace gxc
{
namespace
{
bool Down(const u8 keys[64], int scancode)
{
  return (keys[scancode / 8] >> (scancode % 8)) & 1;
}
}  // namespace

WiimoteState MarioInput::Update(const u8 keys[64], u32 buttons, bool in_game, bool free_pointer,
                                bool item_active, bool mc_feel)
{
  WiimoteState s;
  float x = static_cast<float>(Down(keys, SC_D)) - static_cast<float>(Down(keys, SC_A));
  float y = static_cast<float>(Down(keys, SC_W)) - static_cast<float>(Down(keys, SC_S));
  if (x != 0 && y != 0)
  {
    const float inv = 1.f / std::sqrt(2.f);
    x *= inv, y *= inv;
  }
  const bool shift = Down(keys, SC_LSHIFT) || Down(keys, SC_RSHIFT);
  const bool ctrl = Down(keys, SC_LCTRL) || Down(keys, SC_RCTRL);
  const bool space = Down(keys, SC_SPACE);
  if (mc_feel && in_game)
  {
    const float lean = shift ? m_leans.sneak : ctrl ? m_leans.sprint : m_leans.walk;
    x *= lean, y *= lean;
    s.sneak = shift;
    s.sprint = ctrl && !shift;
    s.walking = x != 0 || y != 0;
  }
  s.stick_x = x, s.stick_y = y;

  const bool clicks = !in_game || !item_active;
  const bool left = (buttons & MOUSE_LEFT) != 0;
  if (mc_feel && in_game)
  {
    // Held, Space presses A again every other few frames: each landing starts a new jump.
    m_jump_frames = space ? m_jump_frames + 1 : 0;
    s.a = space && (m_jump_frames - 1) % (2 * JUMP_PULSE_FRAMES) < JUMP_PULSE_FRAMES;
    s.b = false;
  }
  else
  {
    m_jump_frames = 0;
    s.a = space || (left && !in_game);
    s.b = (buttons & MOUSE_RIGHT) != 0 && clicks;
    s.z = shift;
    s.c = ctrl;
  }
  s.plus = Down(keys, SC_ESCAPE);
  s.minus = Down(keys, SC_TAB);

  const bool spin_click = left && clicks && !(mc_feel && in_game);
  const bool f = Down(keys, SC_F) && !(mc_feel && in_game);
  if ((spin_click && !m_left_was_down) || (f && !m_f_was_down))
    m_shake_frames = SHAKE_MIN_FRAMES;
  m_left_was_down = spin_click;
  m_f_was_down = f;
  s.shake = spin_click || f || m_shake_frames > 0;
  if (m_shake_frames > 0)
    m_shake_frames--;

  s.center_ir = in_game && !free_pointer;
  return s;
}

void MarioInput::Reset()
{
  m_shake_frames = 0;
  m_left_was_down = false;
  m_f_was_down = false;
  m_jump_frames = 0;
}
}  // namespace gxc
